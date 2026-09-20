package dev.mineagent.runtime.core.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.channels.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** Save anchor + exclusive scope assignment. Binding an old UUID never rewrites old world rows or their payloads. */
public final class WorldSaveIdentity implements AutoCloseable {
    public static final String ANCHOR_FILE="mineagent-save-identity.json";
    private static final ObjectMapper JSON=new ObjectMapper();
    public record Anchor(int format,UUID saveId,UUID scopeId,UUID token){public Anchor{if(format!=1||saveId==null||scopeId==null||token==null||saveId.equals(new UUID(0,0))||token.equals(new UUID(0,0))||parseScope(scopeId.toString())==null)throw new IllegalArgumentException("WORLD_ANCHOR_INVALID");}}
    private record Binding(Anchor anchor,String path,String home,String state,String decision,String previousHash){}
    public record Status(String state,String challenge,UUID saveId,UUID scopeId,UUID legacyHint,List<UUID> candidates,boolean more,String note){public Status{candidates=List.copyOf(candidates);}}
    private record Lease(FileChannel channel,FileLock lock) implements AutoCloseable{public void close(){try{lock.release();}catch(Exception ignored){}try{channel.close();}catch(Exception ignored){}}}
    private final Path home,save,anchorPath,runtimeDb;private final UUID legacyHint;private final Connection db;private final Lease pathLease;
    private Lease scopeLease;private boolean reopenRequired,closed;
    private WorldSaveIdentity(Path home,Path save,UUID legacyHint,Connection db,Lease pathLease){this.home=home;this.save=save;this.legacyHint=legacyHint;this.db=db;this.pathLease=pathLease;anchorPath=save.resolve(ANCHOR_FILE);runtimeDb=home.resolve("runtime.db");}

    public static WorldSaveIdentity open(Path runtimeDirectory,Path saveDirectory,UUID legacyHint)throws Exception{
        Files.createDirectories(runtimeDirectory);Path home=runtimeDirectory.toRealPath(),save=saveDirectory.toRealPath();
        if(!Files.isDirectory(save))throw new IllegalStateException("WORLD_SAVE_DIRECTORY_UNAVAILABLE");
        for(Path database:List.of(home.resolve("runtime.db"),home.resolve("world-identities.db")))if(!Files.notExists(database)&&(!Files.isRegularFile(database,LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(database)))throw new IllegalStateException("WORLD_IDENTITY_DATABASE_LINK_UNSUPPORTED");
        var pathLease=lease(home.resolve("identity-path-locks"),hash(pathKey(save).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        Connection db=null;
        try{
            db=DriverManager.getConnection("jdbc:sqlite:"+home.resolve("world-identities.db"));var service=new WorldSaveIdentity(home,save,legacyHint,db,pathLease);service.initialize();
            service.recoverPending();
            var status=service.status();
            // Only an empty Runtime world-data inventory can be safely treated as a first installation.
            if(status.state().equals("UNANCHORED")&&status.candidates().isEmpty()&&!status.more())service.bind("FRESH",null,status.challenge(),true,"SYSTEM_EMPTY_ROOT");
            var current=service.status();if(current.state().equals("READY"))service.scopeLease=lease(home.resolve("identity-scope-locks"),current.scopeId().toString());
            return service;
        }catch(Exception failure){if(db!=null)try{db.close();}catch(Exception ignored){}pathLease.close();throw failure;}
    }
    private void initialize()throws SQLException{
        try(var s=db.createStatement()){
            s.execute("PRAGMA busy_timeout=1500");s.execute("PRAGMA journal_mode=WAL");s.execute("PRAGMA synchronous=FULL");
            s.execute("CREATE TABLE IF NOT EXISTS identity_meta_v1(id INTEGER PRIMARY KEY CHECK(id=1),revision INTEGER NOT NULL)");s.execute("INSERT OR IGNORE INTO identity_meta_v1 VALUES(1,0)");
            s.execute("CREATE TABLE IF NOT EXISTS identity_bindings_v1(save_id TEXT PRIMARY KEY,scope_id TEXT NOT NULL UNIQUE,token TEXT NOT NULL,save_path TEXT UNIQUE,home_root TEXT NOT NULL,state TEXT NOT NULL,decision TEXT NOT NULL,previous_hash TEXT NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS identity_decisions_v1(id INTEGER PRIMARY KEY AUTOINCREMENT,save_id TEXT NOT NULL,scope_id TEXT NOT NULL,save_path TEXT NOT NULL,decision TEXT NOT NULL,previous_hash TEXT NOT NULL,created_at INTEGER NOT NULL,actor TEXT NOT NULL)");
        }
    }
    public synchronized Status status()throws Exception{
        requireOpen();byte[] raw=anchorBytes();Anchor anchor=null;String state="UNANCHORED",note="";
        if(raw!=null)try{anchor=JSON.readValue(raw,Anchor.class);}catch(Exception invalid){state="ANCHOR_INVALID";note="原锚点保留；必须明确选择，不自动覆盖。";}
        Binding byPath=pathBinding();
        if(anchor!=null){var binding=binding("save_id",anchor.saveId().toString());
            if(binding==null) {state="ANCHOR_UNBOUND";note="锚点不等于数据库备份；需明确接入旧域或建立新域。";}
            else if(!binding.anchor().equals(anchor)){state="ANCHOR_CONFLICT";note="锚点与注册记录不一致，不能自动接入。";}
            else if(binding.path()==null){state="SCOPE_DETACHED";note="旧作用域保留但不再绑定这个位置，需要明确接入。";}
            else if(!binding.path().equals(pathKey(save))||!binding.home().equals(pathKey(home))){state=binding.home().equals(pathKey(home))?"PATH_CHANGED":"ROOT_RESTORE_REQUIRED";note="复制不能共享原作用域；移址/根目录恢复须明确确认。";}
            else state=binding.state().equals("BOUND")?"READY":"WRITE_PENDING";
        }else if(raw==null&&byPath!=null){state="ANCHOR_MISSING";note="该位置已有绑定但锚点缺失，不能假定是新世界。";}
        if(byPath!=null&&(anchor==null||!byPath.anchor().saveId().equals(anchor.saveId())))note+=" 此位置原注册 scope: "+byPath.anchor().scopeId();
        if(reopenRequired){state="REOPEN_REQUIRED";note="绑定已保存；保存并重新打开世界后才恢复 MineAgent 功能。";}
        var discovery=discover(runtimeDb,16);
        long revision;try(var q=db.prepareStatement("SELECT revision FROM identity_meta_v1 WHERE id=1");var r=q.executeQuery()){if(!r.next())throw new SQLException("WORLD_IDENTITY_METADATA");revision=r.getLong(1);}
        String challenge=hash((pathKey(home)+"\n"+pathKey(save)+"\n"+(raw==null?"ABSENT":hash(raw))+"\n"+revision+"\n"+state).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return new Status(state,challenge,anchor==null?(byPath==null?null:byPath.anchor().saveId()):anchor.saveId(),anchor==null?(byPath==null?null:byPath.anchor().scopeId()):anchor.scopeId(),legacyHint,discovery.ids(),discovery.more(),note);
    }
    public synchronized UUID scopeId(){try{if(scopeLease==null||!scopeLease.lock().isValid()||reopenRequired)throw new IllegalStateException("WORLD_IDENTITY_NOT_READY");var status=status();if(!status.state().equals("READY"))throw new IllegalStateException("WORLD_IDENTITY_CHANGED");return status.scopeId();}catch(RuntimeException failure){throw failure;}catch(Exception failure){throw new IllegalStateException("WORLD_IDENTITY_UNAVAILABLE",failure);}}
    /** Native caches this readiness result; normal ticks do not enumerate SQLite or read the anchor. */
    public synchronized boolean ready(){return !closed&&!reopenRequired&&scopeLease!=null&&scopeLease.lock().isValid();}
    public synchronized void choose(String choice,UUID scope,String challenge,String actor)throws Exception{if(actor==null||!actor.matches("(?:PLAYER:[a-f0-9-]{36}|SERVER_COMMAND_SOURCE)"))throw new IllegalArgumentException("WORLD_IDENTITY_ACTOR");bind(choice,scope,challenge,false,actor);}

    private void bind(String choice,UUID requestedScope,String challenge,boolean automatic,String actor)throws Exception{
        requireOpen();if(ready()||reopenRequired)throw new IllegalStateException("WORLD_IDENTITY_ALREADY_SELECTED");
        Status status=status();if(!status.challenge().equals(challenge))throw new IllegalStateException("WORLD_IDENTITY_STALE");
        if(!Set.of("FRESH","ADOPT","RELOCATE","RESTORE_ROOT").contains(choice))throw new IllegalArgumentException("WORLD_IDENTITY_CHOICE");
        byte[] oldBytes=anchorBytes();Anchor oldAnchor=null;try{if(oldBytes!=null)oldAnchor=JSON.readValue(oldBytes,Anchor.class);}catch(Exception ignored){}
        Anchor next;Binding replace=null;
        if(choice.equals("FRESH")){
            next=new Anchor(1,UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID());replace=pathBinding();
            // A replaced save at the same folder may explicitly start fresh; the old scope stays retained.
        }else if(choice.equals("ADOPT")){
            if(requestedScope==null)throw new IllegalStateException("WORLD_LEGACY_SCOPE_MISSING");
            var existing=binding("scope_id",requestedScope.toString());
            boolean sameRegisteredPath=existing!=null&&Objects.equals(existing.path(),pathKey(save))&&existing.home().equals(pathKey(home));
            if(!sameRegisteredPath&&!hasScope(runtimeDb,requestedScope))throw new IllegalStateException("WORLD_LEGACY_SCOPE_MISSING");
            if(existing!=null&&existing.path()!=null&&(!existing.path().equals(pathKey(save))||!existing.home().equals(pathKey(home))))throw new IllegalStateException("WORLD_SCOPE_ALREADY_BOUND");
            var currentPath=pathBinding();if(currentPath!=null&&(existing==null||!currentPath.anchor().equals(existing.anchor())))throw new IllegalStateException("WORLD_IDENTITY_PATH_ALREADY_BOUND");
            replace=existing;next=existing==null?new Anchor(1,UUID.randomUUID(),requestedScope,UUID.randomUUID()):existing.anchor();
        }else{
            if(oldAnchor==null)throw new IllegalStateException("WORLD_MOVE_ANCHOR_REQUIRED");
            replace=binding("save_id",oldAnchor.saveId().toString());if(replace==null||!replace.anchor().equals(oldAnchor))throw new IllegalStateException("WORLD_MOVE_BINDING_MISSING");
            if(choice.equals("RELOCATE")){if(replace.path()==null||!replace.home().equals(pathKey(home))||replace.path().equals(pathKey(save))||!Files.notExists(Path.of(replace.path())))throw new IllegalStateException("WORLD_OLD_PATH_STILL_PRESENT_OR_UNKNOWN");}
            else {if(replace.home().equals(pathKey(home)))throw new IllegalStateException("WORLD_ROOT_RESTORE_NOT_APPLICABLE");if(!hasScope(runtimeDb,oldAnchor.scopeId()))throw new IllegalStateException("WORLD_RESTORE_DATABASE_SCOPE_MISSING");}
            if(pathBinding()!=null&&!pathBinding().anchor().equals(oldAnchor))throw new IllegalStateException("WORLD_IDENTITY_PATH_ALREADY_BOUND");next=oldAnchor;
        }
        Lease retained=lease(home.resolve("identity-scope-locks"),next.scopeId().toString());
        try{
            String previous=oldBytes==null?"ABSENT":hash(oldBytes);
            execute("BEGIN IMMEDIATE");try{
                if(!status().challenge().equals(challenge))throw new IllegalStateException("WORLD_IDENTITY_STALE");
                if(choice.equals("FRESH")&&replace!=null){try(var q=db.prepareStatement("UPDATE identity_bindings_v1 SET save_path=NULL,state='DETACHED' WHERE save_id=? AND save_path=?")){q.setString(1,replace.anchor().saveId().toString());q.setString(2,pathKey(save));if(q.executeUpdate()!=1)throw new SQLException("WORLD_IDENTITY_BINDING_CHANGED");}}
                if(replace==null||choice.equals("FRESH")){try(var q=db.prepareStatement("INSERT INTO identity_bindings_v1 VALUES(?,?,?,?,?,'PENDING_WRITE',?,?)")){q.setString(1,next.saveId().toString());q.setString(2,next.scopeId().toString());q.setString(3,next.token().toString());q.setString(4,pathKey(save));q.setString(5,pathKey(home));q.setString(6,choice);q.setString(7,previous);q.executeUpdate();}}
                else{try(var q=db.prepareStatement("UPDATE identity_bindings_v1 SET save_path=?,home_root=?,state='PENDING_WRITE',decision=?,previous_hash=? WHERE save_id=? AND scope_id=? AND token=?")){q.setString(1,pathKey(save));q.setString(2,pathKey(home));q.setString(3,choice);q.setString(4,previous);q.setString(5,next.saveId().toString());q.setString(6,next.scopeId().toString());q.setString(7,next.token().toString());if(q.executeUpdate()!=1)throw new SQLException("WORLD_IDENTITY_BINDING_CHANGED");}}
                try(var q=db.prepareStatement("INSERT INTO identity_decisions_v1(save_id,scope_id,save_path,decision,previous_hash,created_at,actor) VALUES(?,?,?,?,?,?,?)")){q.setString(1,next.saveId().toString());q.setString(2,next.scopeId().toString());q.setString(3,pathKey(save));q.setString(4,choice);q.setString(5,previous);q.setLong(6,System.currentTimeMillis());q.setString(7,actor);q.executeUpdate();}
                execute("UPDATE identity_meta_v1 SET revision=revision+1 WHERE id=1");execute("COMMIT");
            }catch(Exception failure){try{execute("ROLLBACK");}catch(Exception ignored){}throw failure;}
            writeAnchor(next,previous);finishBinding(next);
            if(automatic){retained.close();retained=null;}else{scopeLease=retained;retained=null;reopenRequired=true;}
        }finally{if(retained!=null)retained.close();}
    }
    private void recoverPending()throws Exception{
        var pending=pathBinding();if(pending==null||!pending.home().equals(pathKey(home))||!pending.state().equals("PENDING_WRITE"))return;
        try(var lease=lease(home.resolve("identity-scope-locks"),pending.anchor().scopeId().toString())){writeAnchor(pending.anchor(),pending.previousHash());finishBinding(pending.anchor());}
    }
    private void finishBinding(Anchor anchor)throws SQLException{
        try(var q=db.prepareStatement("UPDATE identity_bindings_v1 SET state='BOUND' WHERE save_id=? AND scope_id=? AND token=? AND save_path=? AND state='PENDING_WRITE'")){q.setString(1,anchor.saveId().toString());q.setString(2,anchor.scopeId().toString());q.setString(3,anchor.token().toString());q.setString(4,pathKey(save));if(q.executeUpdate()!=1)throw new SQLException("WORLD_IDENTITY_COMMIT_UNCERTAIN");}
    }
    private void writeAnchor(Anchor next,String previous)throws Exception{
        byte[] current=anchorBytes(),bytes=JSON.writeValueAsBytes(next);
        if(current!=null){try{if(JSON.readValue(current,Anchor.class).equals(next))return;}catch(Exception ignored){}}
        if(!(current==null?"ABSENT":hash(current)).equals(previous))throw new IllegalStateException("WORLD_ANCHOR_CHANGED");
        if(current!=null){Path backups=home.resolve("identity-anchor-backups");Files.createDirectories(backups);Path backup=backups.resolve(previous+".json");if(!Files.exists(backup))Files.write(backup,current,StandardOpenOption.CREATE_NEW);else if(!Arrays.equals(Files.readAllBytes(backup),current))throw new IllegalStateException("WORLD_ANCHOR_BACKUP_CONFLICT");}
        Path temp=Files.createTempFile(save,".mineagent-identity-",".tmp");
        try{
            try(var out=FileChannel.open(temp,StandardOpenOption.WRITE)){var buffer=java.nio.ByteBuffer.wrap(bytes);while(buffer.hasRemaining())out.write(buffer);out.force(true);}
            try{Files.move(temp,anchorPath,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException unsupported){throw new IllegalStateException("WORLD_ANCHOR_ATOMIC_MOVE_REQUIRED",unsupported);}
        }finally{Files.deleteIfExists(temp);}
    }
    private byte[] anchorBytes()throws Exception{
        if(Files.notExists(anchorPath))return null;if(!Files.isRegularFile(anchorPath,LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(anchorPath))throw new IllegalStateException("WORLD_ANCHOR_FILE_UNSAFE");
        try(var in=Files.newInputStream(anchorPath)){byte[] raw=in.readNBytes(16385);if(raw.length>16384)throw new IllegalStateException("WORLD_ANCHOR_TOO_LARGE");return raw;}
    }
    private Binding pathBinding()throws SQLException{return binding("save_path",pathKey(save));}
    private Binding binding(String column,String value)throws SQLException{
        if(!Set.of("save_path","scope_id","save_id").contains(column))throw new IllegalArgumentException();
        try(var q=db.prepareStatement("SELECT save_id,scope_id,token,save_path,home_root,state,decision,previous_hash FROM identity_bindings_v1 WHERE "+column+"=?")){q.setString(1,value);try(var r=q.executeQuery()){return r.next()?new Binding(new Anchor(1,UUID.fromString(r.getString(1)),UUID.fromString(r.getString(2)),UUID.fromString(r.getString(3))),r.getString(4),r.getString(5),r.getString(6),r.getString(7),r.getString(8)):null;}}
    }
    private record Discovery(List<UUID> ids,boolean more){}
    private static Discovery discover(Path database,int maximum)throws Exception{
        if(Files.notExists(database))return new Discovery(List.of(),false);
        var ids=new TreeSet<UUID>();boolean more=false;
        try(var db=DriverManager.getConnection("jdbc:sqlite:"+database.toAbsolutePath().toUri()+"?mode=ro")){
            try(var s=db.createStatement()){s.execute("PRAGMA query_only=ON");s.execute("PRAGMA busy_timeout=1500");}
            for(var pair:scopeColumns(db)){try(var q=db.prepareStatement("SELECT DISTINCT "+quote(pair[1])+" FROM "+quote(pair[0])+" WHERE "+quote(pair[1])+" IS NOT NULL LIMIT 257");var r=q.executeQuery()){int seen=0;while(r.next()){seen++;UUID id=parseScope(r.getString(1));if(id!=null){ids.add(id);if(ids.size()>maximum){more=true;ids.pollLast();}}}if(seen==257)more=true;}}
        }return new Discovery(List.copyOf(ids),more);
    }
    private static boolean hasScope(Path database,UUID scope)throws Exception{
        if(parseScope(scope.toString())==null||Files.notExists(database))return false;
        try(var db=DriverManager.getConnection("jdbc:sqlite:"+database.toAbsolutePath().toUri()+"?mode=ro")){try(var s=db.createStatement()){s.execute("PRAGMA query_only=ON");s.execute("PRAGMA busy_timeout=1500");}for(var pair:scopeColumns(db))try(var q=db.prepareStatement("SELECT 1 FROM "+quote(pair[0])+" WHERE "+quote(pair[1])+"=? LIMIT 1")){q.setString(1,scope.toString());try(var r=q.executeQuery()){if(r.next())return true;}}}return false;
    }
    private static List<String[]> scopeColumns(Connection db)throws SQLException{
        var tables=new ArrayList<String>();try(var q=db.prepareStatement("SELECT name FROM sqlite_master WHERE type='table' AND (name LIKE 'mineagent_%' OR name LIKE 'service_budget_%')");var r=q.executeQuery()){while(r.next()){if(tables.size()>=512)throw new SQLException("WORLD_IDENTITY_SCHEMA_LIMIT");tables.add(r.getString(1));}}
        var result=new ArrayList<String[]>();for(String table:tables)try(var q=db.prepareStatement("PRAGMA table_info("+quote(table)+")");var r=q.executeQuery()){while(r.next())if(Set.of("world","world_id").contains(r.getString("name")))result.add(new String[]{table,r.getString("name")});}return result;
    }
    private static UUID parseScope(String value){try{if(value==null||!value.matches("[a-fA-F0-9-]{36}"))return null;var id=UUID.fromString(value);return id.equals(new UUID(0,0))||id.equals(dev.mineagent.runtime.core.packages.RuntimePackageLibrary.GLOBAL_LIBRARY_ID)?null:id;}catch(IllegalArgumentException invalid){return null;}}
    private static Lease lease(Path directory,String key)throws Exception{Files.createDirectories(directory);var channel=FileChannel.open(directory.resolve(key+".lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);try{var lock=channel.tryLock();if(lock==null)throw new IllegalStateException("WORLD_IDENTITY_IN_USE");return new Lease(channel,lock);}catch(Exception failure){channel.close();throw failure;}}
    private static String pathKey(Path path){String value=path.toAbsolutePath().normalize().toString();return System.getProperty("os.name","").startsWith("Windows")?value.toLowerCase(Locale.ROOT):value;}
    private static String quote(String value){return "\""+value.replace("\"","\"\"")+"\"";}
    private static String hash(byte[] value)throws Exception{return dev.mineagent.runtime.core.packages.RuntimePackageCanonicalizer.sha256(value);}
    private void execute(String sql)throws SQLException{try(var s=db.createStatement()){s.execute(sql);}}
    private void requireOpen(){if(closed)throw new IllegalStateException("WORLD_IDENTITY_CLOSED");}
    @Override public synchronized void close(){if(closed)return;closed=true;if(scopeLease!=null)scopeLease.close();try{db.close();}catch(Exception ignored){}pathLease.close();}
}
