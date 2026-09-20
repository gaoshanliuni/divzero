package dev.mineagent.runtime.core.persistence;

import dev.mineagent.runtime.core.config.ServiceCallBudget;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** One Worker writer per runtime directory. No network operation executes inside a SQLite transaction. */
public final class ServiceCallLedger implements AutoCloseable {
    public static final long MAX_REQUESTS = 1_000_000, MAX_ATTEMPTS = 4_000_000;
    public static final Set<String> CATEGORIES = Set.of("SEMANTIC", "PLANNING", "CODING", "VISION", "EMBEDDING", "IMAGE", "TTS", "WEB");
    private final Connection db;
    private final FileChannel channel;
    private final FileLock lock;
    private final Clock clock;
    private Ticket pending;
    private String pendingState;

    public static final class Rejected extends RuntimeException {
        public Rejected(String code) { super(ServiceCallBudget.ERRORS.contains(code) ? code : "SERVICE_BUDGET_UNAVAILABLE"); }
    }
    public final class Scope {
        private final UUID requestId;
        private final String world, agent, task;
        private final boolean taskBound;
        private final long taskIntent;
        private final String taskRevisionKind;
        private int ordinal;
        private boolean denied;
        private Scope(UUID requestId, Map<String, Object> values) {
            this.requestId = requestId;
            world = context(values, "worldId"); agent = context(values, "agentId"); task = context(values, "taskId");
            taskBound=values.containsKey("taskId");
            taskRevisionKind=String.valueOf(values.getOrDefault("budgetTaskRevisionKind",""));
            long intent=0;try{intent=Long.parseLong(String.valueOf(values.get("taskRevision")));}catch(NumberFormatException ignored){}taskIntent=intent;
        }
    }
    public record Ticket(UUID requestId, int ordinal) {}

    public static ServiceCallLedger open(Path database, Clock clock) throws Exception {
        Path parent = database.toAbsolutePath().normalize().getParent();
        Files.createDirectories(parent);
        parent = parent.toRealPath();
        var channel = FileChannel.open(parent.resolve("service-call-budget.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock lock = null;
        Connection db = null;
        try {
            lock = channel.tryLock();
            if (lock == null) throw new Rejected("SERVICE_BUDGET_UNAVAILABLE");
            db = DriverManager.getConnection("jdbc:sqlite:" + parent.resolve(database.getFileName()));
            var ledger = new ServiceCallLedger(db, channel, lock, clock);
            ledger.initialize();
            return ledger;
        } catch (Exception failure) {
            if (db != null) try { db.close(); } catch (Exception ignored) {}
            if (lock != null) try { lock.release(); } catch (Exception ignored) {}
            try { channel.close(); } catch (Exception ignored) {}
            throw failure;
        }
    }

    private ServiceCallLedger(Connection db, FileChannel channel, FileLock lock, Clock clock) {
        this.db = db; this.channel = channel; this.lock = lock; this.clock = clock;
    }

    private void initialize() throws SQLException {
        execute(db, "PRAGMA busy_timeout=1500");
        execute(db, "PRAGMA journal_mode=WAL");
        execute(db, "PRAGMA synchronous=FULL");
        execute(db, "PRAGMA foreign_keys=ON");
        transaction(() -> {
            execute(db, "CREATE TABLE IF NOT EXISTS service_budget_meta_v1(id INTEGER PRIMARY KEY CHECK(id=1),high_water INTEGER NOT NULL,requests INTEGER NOT NULL,attempts INTEGER NOT NULL,last_code TEXT NOT NULL)");
            execute(db, "INSERT OR IGNORE INTO service_budget_meta_v1 VALUES(1,0,0,0,'')");
            execute(db, "CREATE TABLE IF NOT EXISTS service_budget_requests_v1(id TEXT PRIMARY KEY,world_id TEXT NOT NULL,agent_id TEXT NOT NULL,task_id TEXT NOT NULL,attempts INTEGER NOT NULL,last_code TEXT NOT NULL)");
            execute(db, "CREATE TABLE IF NOT EXISTS service_budget_days_v1(day TEXT NOT NULL,category TEXT NOT NULL,used INTEGER NOT NULL,returned INTEGER NOT NULL,failed INTEGER NOT NULL,unknown INTEGER NOT NULL,denied INTEGER NOT NULL,PRIMARY KEY(day,category))");
            execute(db, "CREATE TABLE IF NOT EXISTS service_budget_attempts_v1(request_id TEXT NOT NULL,ordinal INTEGER NOT NULL,category TEXT NOT NULL,provider TEXT NOT NULL,started INTEGER NOT NULL,day TEXT NOT NULL,state TEXT NOT NULL CHECK(state IN ('DISPATCHING','RETURNED','FAILED','UNKNOWN')),PRIMARY KEY(request_id,ordinal),FOREIGN KEY(request_id) REFERENCES service_budget_requests_v1(id))");
            execute(db, "CREATE INDEX IF NOT EXISTS service_budget_pending_v1 ON service_budget_attempts_v1(state,day,category)");
            boolean rootColumn=false;
            try(var q=db.prepareStatement("PRAGMA table_info(service_budget_attempts_v1)");var r=q.executeQuery()){while(r.next())if(r.getString("name").equals("budget_root"))rootColumn=true;}
            if(!rootColumn)execute(db,"ALTER TABLE service_budget_attempts_v1 ADD COLUMN budget_root TEXT NOT NULL DEFAULT ''");
            execute(db,"CREATE TABLE IF NOT EXISTS service_budget_task_usage_v1(world TEXT NOT NULL,root TEXT NOT NULL,category TEXT NOT NULL,used INTEGER NOT NULL,returned INTEGER NOT NULL,failed INTEGER NOT NULL,unknown INTEGER NOT NULL,denied INTEGER NOT NULL,last_code TEXT NOT NULL,last_at INTEGER NOT NULL,PRIMARY KEY(world,root,category))");
            // Only exclusive writer-lock acquisition proves that the previous Worker no longer owns these calls.
            try (var q = db.prepareStatement("SELECT day,category,COUNT(*) FROM service_budget_attempts_v1 WHERE state='DISPATCHING' GROUP BY day,category"); var rows = q.executeQuery()) {
                while (rows.next()) update(db, "UPDATE service_budget_days_v1 SET unknown=unknown+? WHERE day=? AND category=?", rows.getLong(3), rows.getString(1), rows.getString(2));
            }
            try(var q=db.prepareStatement("SELECT r.world_id,a.budget_root,a.category,COUNT(*) FROM service_budget_attempts_v1 a JOIN service_budget_requests_v1 r ON r.id=a.request_id WHERE a.state='DISPATCHING' AND a.budget_root!='' GROUP BY r.world_id,a.budget_root,a.category");var rows=q.executeQuery()){
                while(rows.next())update(db,"UPDATE service_budget_task_usage_v1 SET unknown=unknown+? WHERE world=? AND root=? AND category=?",rows.getLong(4),rows.getString(1),rows.getString(2),rows.getString(3));
            }
            execute(db, "UPDATE service_budget_attempts_v1 SET state='UNKNOWN' WHERE state='DISPATCHING'");
            return null;
        });
    }

    public synchronized Scope begin(UUID requestId, Map<String, Object> values) {
        flush();
        try {
            if (scalar(db, "SELECT COUNT(*) FROM service_budget_requests_v1 WHERE id=?", requestId.toString()) != 0)
                throw new Rejected("SERVICE_BUDGET_REPLAY");
            return new Scope(requestId, values);
        } catch (SQLException unavailable) { throw new Rejected("SERVICE_BUDGET_UNAVAILABLE"); }
    }

    public synchronized Ticket reserve(Scope scope, String category, String provider) {
        flush();
        if (scope == null || scope.denied || !CATEGORIES.contains(category)
                || !Set.of("openai-compatible", "ollama", "comfyui", "edge-tts", "public-web").contains(provider))
            throw new Rejected("SERVICE_BUDGET_UNAVAILABLE");
        try {
            String rejection = transaction(() -> {
                long high = scalar(db, "SELECT high_water FROM service_budget_meta_v1 WHERE id=1");
                long now = Math.max(high, Math.max(0, clock.millis()));
                String day = day(now);
                long requests = scalar(db, "SELECT requests FROM service_budget_meta_v1 WHERE id=1");
                long attempts = scalar(db, "SELECT attempts FROM service_budget_meta_v1 WHERE id=1");
                if ((scope.ordinal == 0 && requests >= MAX_REQUESTS) || attempts >= MAX_ATTEMPTS) {
                    update(db, "UPDATE service_budget_meta_v1 SET high_water=?,last_code='SERVICE_BUDGET_CAPACITY' WHERE id=1", now);
                    return "SERVICE_BUDGET_CAPACITY";
                }
                if (scope.ordinal == 0) {
                    update(db, "INSERT INTO service_budget_requests_v1 VALUES(?,?,?,?,0,'')", scope.requestId.toString(), scope.world, scope.agent, scope.task);
                    execute(db, "UPDATE service_budget_meta_v1 SET requests=requests+1 WHERE id=1");
                }
                String code = "",root="";
                try {
                    ServiceCallBudget policy = policy(db);
                    if(scope.taskBound){
                        try{
                            if(scope.world.isEmpty()||scope.task.isEmpty()||scope.agent.isEmpty()||scope.taskIntent<1||!Set.of("MUTATION","INTENT").contains(scope.taskRevisionKind))throw new IllegalArgumentException();
                            var lineage=TaskBudgetLineage.resolve(db,scope.world,scope.task,scope.agent,scope.taskIntent,scope.taskRevisionKind);
                            root=lineage.known()?lineage.root():"";
                            if(!lineage.known()&&policy.taskLimit()>0)code="SERVICE_BUDGET_TASK_UNRESOLVED";
                        }catch(IllegalArgumentException invalid){code="SERVICE_BUDGET_TASK_CONTEXT";}
                    }
                    long used = scalar(db, "SELECT COALESCE(SUM(used),0) FROM service_budget_days_v1 WHERE day=?", day);
                    if(code.isEmpty()){
                        if (policy.paused()) code = "SERVICE_BUDGET_PAUSED";
                        else if (policy.dailyLimit() > 0 && used >= policy.dailyLimit()) code = "SERVICE_BUDGET_DAILY_LIMIT";
                        else if (policy.requestLimit() > 0 && scope.ordinal >= policy.requestLimit()) code = "SERVICE_BUDGET_REQUEST_LIMIT";
                        else if(!root.isEmpty()&&policy.taskLimit()>0&&scalar(db,"SELECT COALESCE(SUM(used),0) FROM service_budget_task_usage_v1 WHERE world=? AND root=?",scope.world,root)>=policy.taskLimit())code="SERVICE_BUDGET_TASK_LIMIT";
                    }
                } catch (IllegalArgumentException invalid) { code = "SERVICE_BUDGET_CONFIG_INVALID"; }
                if(!root.isEmpty())update(db,"INSERT OR IGNORE INTO service_budget_task_usage_v1 VALUES(?,?,?,0,0,0,0,0,'',0)",scope.world,root,category);
                update(db, "INSERT OR IGNORE INTO service_budget_days_v1 VALUES(?,?,0,0,0,0,0)", day, category);
                update(db, "UPDATE service_budget_meta_v1 SET high_water=? WHERE id=1", now);
                if (!code.isEmpty()) {
                    update(db, "UPDATE service_budget_requests_v1 SET last_code=? WHERE id=?", code, scope.requestId.toString());
                    update(db, "UPDATE service_budget_days_v1 SET denied=denied+1 WHERE day=? AND category=?", day, category);
                    update(db, "UPDATE service_budget_meta_v1 SET last_code=? WHERE id=1", code);
                    if(!root.isEmpty())update(db,"UPDATE service_budget_task_usage_v1 SET denied=denied+1,last_code=?,last_at=? WHERE world=? AND root=? AND category=?",code,now,scope.world,root,category);
                    return code;
                }
                update(db, "INSERT INTO service_budget_attempts_v1(request_id,ordinal,category,provider,started,day,state,budget_root) VALUES(?,?,?,?,?,?,'DISPATCHING',?)", scope.requestId.toString(), scope.ordinal + 1, category, provider, now, day,root);
                update(db, "UPDATE service_budget_requests_v1 SET attempts=attempts+1 WHERE id=?", scope.requestId.toString());
                execute(db, "UPDATE service_budget_meta_v1 SET attempts=attempts+1 WHERE id=1");
                update(db, "UPDATE service_budget_days_v1 SET used=used+1 WHERE day=? AND category=?", day, category);
                if(!root.isEmpty())update(db,"UPDATE service_budget_task_usage_v1 SET used=used+1 WHERE world=? AND root=? AND category=?",scope.world,root,category);
                return "";
            });
            if (!rejection.isEmpty()) { scope.denied = true; throw new Rejected(rejection); }
            return new Ticket(scope.requestId, ++scope.ordinal);
        } catch (SQLException unavailable) { scope.denied = true; throw new Rejected("SERVICE_BUDGET_UNAVAILABLE"); }
    }

    /** Best effort after dispatch: a ledger write failure must not discard the Provider's returned artifact. */
    public synchronized void finish(Ticket ticket, boolean returned) {
        pending = ticket; pendingState = returned ? "RETURNED" : "FAILED";
        try { flush(); } catch (Rejected unavailable) { /* Retain only the receipt; never re-invoke the Provider. */ }
    }

    private void flush() {
        if (pending == null) return;
        try {
            transaction(() -> {
                try (var q = db.prepareStatement("SELECT a.day,a.category,a.state,a.budget_root,r.world_id FROM service_budget_attempts_v1 a JOIN service_budget_requests_v1 r ON r.id=a.request_id WHERE a.request_id=? AND a.ordinal=?")) {
                    q.setString(1, pending.requestId().toString()); q.setInt(2, pending.ordinal());
                    try (var rows = q.executeQuery()) {
                        if (!rows.next()) throw new SQLException("missing ticket");
                        String state = rows.getString(3);
                        if (state.equals(pendingState)) return null; // An earlier COMMIT acknowledgement may have been lost.
                        if (!state.equals("DISPATCHING")) throw new SQLException("ticket state mismatch");
                        String column = pendingState.equals("RETURNED") ? "returned" : "failed";
                        update(db, "UPDATE service_budget_days_v1 SET " + column + "=" + column + "+1 WHERE day=? AND category=?", rows.getString(1), rows.getString(2));
                        if(!rows.getString(4).isEmpty())update(db,"UPDATE service_budget_task_usage_v1 SET "+column+"="+column+"+1 WHERE world=? AND root=? AND category=?",rows.getString(5),rows.getString(4),rows.getString(2));
                    }
                }
                update(db, "UPDATE service_budget_attempts_v1 SET state=? WHERE request_id=? AND ordinal=?", pendingState, pending.requestId().toString(), pending.ordinal());
                return null;
            });
            pending = null; pendingState = null;
        } catch (SQLException unavailable) { throw new Rejected("SERVICE_BUDGET_UNAVAILABLE"); }
    }

    /** Short read-only SQLite snapshot for Native settings; never waits on Worker IPC or takes its writer lock. */
    public static Map<String, Object> snapshot(Path database, Clock clock) {
        if (!Files.isRegularFile(database)) return Map.of("state", "NOT_INITIALIZED");
        try (var db = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath().toUri() + "?mode=ro")) {
            execute(db, "PRAGMA query_only=ON"); execute(db, "PRAGMA busy_timeout=25");
            db.setAutoCommit(false);
            if (scalar(db, "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='service_budget_meta_v1'") == 0)
                return Map.of("state", "NOT_INITIALIZED");
            var result = new LinkedHashMap<String, Object>();
            long high = scalar(db, "SELECT high_water FROM service_budget_meta_v1 WHERE id=1"), now = Math.max(0, clock.millis());
            String day = day(Math.max(high, now));
            var policy = policy(db);
            result.put("state", "SNAPSHOT"); result.put("day", day); result.put("observedAt", now); result.put("clockBehind", now < high);
            result.put("dailyLimit", policy.dailyLimit()); result.put("requestLimit", policy.requestLimit()); result.put("paused", policy.paused());
            result.put("taskLimit",policy.taskLimit());
            result.put("retainedRequests", scalar(db, "SELECT requests FROM service_budget_meta_v1 WHERE id=1"));
            result.put("retainedAttempts", scalar(db, "SELECT attempts FROM service_budget_meta_v1 WHERE id=1"));
            result.put("maxRequests", MAX_REQUESTS); result.put("maxAttempts", MAX_ATTEMPTS);
            var categories = new ArrayList<Map<String, Object>>();
            try (var q = db.prepareStatement("SELECT category,used,returned,failed,unknown,denied FROM service_budget_days_v1 WHERE day=? ORDER BY category")) {
                q.setString(1, day);
                try (var rows = q.executeQuery()) { while (rows.next()) categories.add(Map.of("category", rows.getString(1), "used", rows.getLong(2), "returned", rows.getLong(3), "failed", rows.getLong(4), "unknown", rows.getLong(5), "denied", rows.getLong(6), "dispatching", rows.getLong(2)-rows.getLong(3)-rows.getLong(4)-rows.getLong(5))); }
            }
            result.put("categories", categories);
            try (var q = db.prepareStatement("SELECT last_code FROM service_budget_meta_v1 WHERE id=1"); var rows = q.executeQuery()) {
                result.put("lastRejection", rows.next() && ServiceCallBudget.ERRORS.contains(rows.getString(1)) ? rows.getString(1) : "");
            }
            return Map.copyOf(result);
        } catch (Exception unavailable) { return Map.of("state", "UNAVAILABLE"); }
    }

    public static Map<String,Object> taskSnapshot(Path database,Clock clock,UUID world,UUID owner,UUID task,int offset){
        if(offset<0||offset>TaskRetention.MAX_RETAINED)throw new IllegalArgumentException("TASK_BUDGET_PAGE");
        if(!Files.isRegularFile(database))return Map.of("state","NOT_INITIALIZED");
        try(var db=DriverManager.getConnection("jdbc:sqlite:"+database.toAbsolutePath().toUri()+"?mode=ro")){
            execute(db,"PRAGMA query_only=ON");execute(db,"PRAGMA busy_timeout=25");db.setAutoCommit(false);
            var lineage=TaskBudgetLineage.read(db,world.toString(),task.toString());
            if(lineage==null||!lineage.owner().equals(owner.toString()))throw new SecurityException("TASK_BUDGET_NOT_OWNED");
            var result=new LinkedHashMap<String,Object>();result.put("lineage",lineage);result.put("observedAt",clock.millis());result.put("limit",policy(db).taskLimit());
            if(!lineage.known()){result.put("state","LEGACY_UNRESOLVED");return Map.copyOf(result);}
            if(scalar(db,"SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='service_budget_task_usage_v1'")==0){result.put("state","NOT_INITIALIZED");return Map.copyOf(result);}
            result.put("state","SNAPSHOT");var categories=new ArrayList<Map<String,Object>>();
            try(var q=db.prepareStatement("SELECT category,used,returned,failed,unknown,denied,last_code,last_at FROM service_budget_task_usage_v1 WHERE world=? AND root=? ORDER BY category")){
                q.setString(1,world.toString());q.setString(2,lineage.root());try(var r=q.executeQuery()){while(r.next())categories.add(Map.of("category",r.getString(1),"used",r.getLong(2),"returned",r.getLong(3),"failed",r.getLong(4),"unknown",r.getLong(5),"denied",r.getLong(6),"dispatching",r.getLong(2)-r.getLong(3)-r.getLong(4)-r.getLong(5),"lastCode",r.getString(7),"lastAt",r.getLong(8)));}
            }
            result.put("categories",categories);
            long total=scalar(db,"SELECT COUNT(*) FROM mineagent_task_budget_lineage_v1 WHERE world=? AND root=? AND owner=?",world.toString(),lineage.root(),owner.toString());
            var members=new ArrayList<Map<String,Object>>();
            try(var q=db.prepareStatement("SELECT task,parent,relation,depth FROM mineagent_task_budget_lineage_v1 WHERE world=? AND root=? AND owner=? ORDER BY task LIMIT 8 OFFSET ?")){
                q.setString(1,world.toString());q.setString(2,lineage.root());q.setString(3,owner.toString());q.setInt(4,offset);try(var r=q.executeQuery()){while(r.next())members.add(Map.of("taskId",r.getString(1),"parent",r.getString(2),"relation",r.getString(3),"depth",r.getInt(4)));}
            }
            result.put("members",members);result.put("total",total);result.put("offset",offset);result.put("nextOffset",offset+members.size());result.put("more",total>offset+members.size());
            return Map.copyOf(result);
        }catch(SecurityException denied){throw denied;}catch(Exception unavailable){return Map.of("state","UNAVAILABLE");}
    }

    private static ServiceCallBudget policy(Connection db) throws SQLException {
        var values = new LinkedHashMap<String, String>();
        // Deliberate whitelist; credentials, URLs and arbitrary config values are never loaded.
        try (var q = db.prepareStatement("SELECT key,value FROM mineagent_config WHERE secret=0 AND key IN (?,?,?,?)")) {
            q.setString(1, ServiceCallBudget.DAILY); q.setString(2, ServiceCallBudget.REQUEST); q.setString(3, ServiceCallBudget.PAUSED);
            q.setString(4,ServiceCallBudget.TASK);
            try (var rows = q.executeQuery()) { while (rows.next()) values.put(rows.getString(1), rows.getString(2)); }
        }
        return ServiceCallBudget.from(values);
    }
    private static String context(Map<String, Object> values, String key) {
        try { return UUID.fromString(String.valueOf(values.get(key))).toString(); } catch (IllegalArgumentException invalid) { return ""; }
    }
    private static String day(long millis) { return Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate().toString(); }
    private interface SqlWork<T> { T run() throws SQLException; }
    private <T> T transaction(SqlWork<T> work) throws SQLException {
        execute(db, "BEGIN IMMEDIATE");
        try { T result = work.run(); execute(db, "COMMIT"); return result; }
        catch (SQLException | RuntimeException failure) { try { execute(db, "ROLLBACK"); } catch (SQLException ignored) {} throw failure; }
    }
    private static void execute(Connection db, String sql) throws SQLException { try (var s = db.createStatement()) { s.execute(sql); } }
    private static int update(Connection db, String sql, Object... values) throws SQLException {
        try (var q = db.prepareStatement(sql)) { for (int i=0;i<values.length;i++) q.setObject(i+1, values[i]); return q.executeUpdate(); }
    }
    private static long scalar(Connection db, String sql, Object... values) throws SQLException {
        try (var q = db.prepareStatement(sql)) { for (int i=0;i<values.length;i++) q.setObject(i+1, values[i]); try (var rows=q.executeQuery()) { if (!rows.next()) throw new SQLException("missing counter"); return rows.getLong(1); } }
    }
    @Override public synchronized void close() {
        try { flush(); } catch (RuntimeException ignored) {}
        try { db.close(); } catch (SQLException ignored) {}
        try { lock.release(); } catch (Exception ignored) {}
        try { channel.close(); } catch (Exception ignored) {}
    }
}
