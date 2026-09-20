package dev.mineagent.runtime.core.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mineagent.runtime.core.packages.RuntimePackageCanonicalizer;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.Clock;
import java.util.*;

/** Explicit, Owner-private preferences; neither world facts nor an authorization source. */
public final class PlayerPreferenceStore implements AutoCloseable {
    public static final Set<String> ERRORS=Set.of("PREFERENCE_CONTEXT_CHANGED","PREFERENCE_CONTEXT_BUDGET","PREFERENCE_USE_BUDGET","PREFERENCE_STORE_UNAVAILABLE","PREFERENCE_USE_ID_REUSED");
    public static String error(Throwable failure){for(int i=0;failure!=null&&i<16;i++,failure=failure.getCause()){String value=Objects.toString(failure.getMessage(),"");if(ERRORS.contains(value))return value;}return "";}
    public static final Set<String> PURPOSES=Set.of("CONVERSATION","PLANNING","GENERATION");
    private static final ObjectMapper JSON=new ObjectMapper().enable(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    public record Entry(UUID id,UUID owner,UUID world,UUID agent,String key,String value,Set<String> purposes,boolean enabled,boolean deleted,long revision,long createdAt,long updatedAt,UUID sourceWorld){public Entry{purposes=Collections.unmodifiableSet(new TreeSet<>(purposes));if(id==null||owner==null||sourceWorld==null||revision<1||createdAt<0||updatedAt<0||world==null&&agent!=null||!PURPOSES.containsAll(purposes)||enabled&&(deleted||purposes.isEmpty()))throw new IllegalArgumentException("PREFERENCE_RECORD");if(!deleted)validateText(key,value);else if(!key.isEmpty()||!value.isEmpty())throw new IllegalArgumentException("PREFERENCE_DELETED_CONTENT");}}
    public record Input(UUID operation,String action,UUID id,long expected,String key,String value,String scope,UUID agent,Set<String> purposes,boolean enabled){public Input{Objects.requireNonNull(operation);purposes=Set.copyOf(purposes);if(!Set.of("SAVE","DELETE","ENABLE").contains(action)||expected<0||key==null||value==null||!Set.of("CURRENT_WORLD","ALL_WORLDS").contains(scope)||!PURPOSES.containsAll(purposes))throw new IllegalArgumentException("PREFERENCE_INPUT");}}
    public record Receipt(UUID operation,UUID id,String outcome,long revision){}
    public record Page<T>(List<T> items,int offset,int nextOffset,boolean more,long total){public Page{items=List.copyOf(items);}}
    public record Ref(UUID id,long revision,String key){}
    public record Snapshot(UUID owner,UUID world,UUID agent,String purpose,List<Ref> refs,String section,String hash){public Snapshot{refs=List.copyOf(refs);}public String append(String prompt){return section.isEmpty()?prompt:prompt+"\n"+section;}}
    public record Use(UUID request,UUID owner,UUID world,UUID agent,String purpose,List<Ref> refs,String hash,String phase,long at){public Use{refs=List.copyOf(refs);}}
    private final Connection db;private final Clock clock;
    public PlayerPreferenceStore(Path path,Clock clock)throws Exception {
        this.clock=clock;Files.createDirectories(path.toAbsolutePath().getParent());db=DriverManager.getConnection("jdbc:sqlite:"+path.toAbsolutePath());
        try(var s=db.createStatement()){
            s.execute("PRAGMA busy_timeout=1500");s.execute("PRAGMA journal_mode=WAL");
            s.execute("CREATE TABLE IF NOT EXISTS player_preferences_v1(id TEXT PRIMARY KEY,owner TEXT NOT NULL,world TEXT NOT NULL,agent TEXT NOT NULL,key_norm TEXT NOT NULL,enabled INTEGER NOT NULL,deleted INTEGER NOT NULL,revision INTEGER NOT NULL,payload TEXT NOT NULL,bytes INTEGER NOT NULL,updated INTEGER NOT NULL)");
            s.execute("CREATE INDEX IF NOT EXISTS player_preferences_owner_v1 ON player_preferences_v1(owner,updated DESC,id)");
            s.execute("CREATE UNIQUE INDEX IF NOT EXISTS player_preference_key_v1 ON player_preferences_v1(owner,world,agent,key_norm) WHERE deleted=0");
            s.execute("CREATE TABLE IF NOT EXISTS player_preference_ops_v1(owner TEXT NOT NULL,id TEXT NOT NULL,fingerprint TEXT NOT NULL,payload TEXT NOT NULL,PRIMARY KEY(owner,id))");
            s.execute("CREATE TABLE IF NOT EXISTS player_preference_use_v1(id TEXT PRIMARY KEY,owner TEXT NOT NULL,payload TEXT NOT NULL,bytes INTEGER NOT NULL,at INTEGER NOT NULL)");
            s.execute("CREATE INDEX IF NOT EXISTS player_preference_use_owner_v1 ON player_preference_use_v1(owner,at DESC,id)");
        }catch(Exception failure){db.close();throw failure;}
    }
    public synchronized Entry get(UUID owner,UUID id)throws Exception {try(var q=db.prepareStatement("SELECT payload FROM player_preferences_v1 WHERE owner=? AND id=?")){q.setString(1,owner.toString());q.setString(2,id.toString());try(var r=q.executeQuery()){if(!r.next())throw new IllegalStateException("PREFERENCE_NOT_OWNED");var entry=JSON.readValue(r.getString(1),Entry.class);if(!entry.owner().equals(owner)||!entry.id().equals(id))throw new IllegalStateException("PREFERENCE_RECORD_SCOPE");return entry;}}}
    public synchronized Receipt receipt(UUID owner,UUID operation)throws Exception {try(var q=db.prepareStatement("SELECT payload FROM player_preference_ops_v1 WHERE owner=? AND id=?")){q.setString(1,owner.toString());q.setString(2,operation.toString());try(var r=q.executeQuery()){if(!r.next())throw new IllegalStateException("PREFERENCE_RECEIPT_MISSING");return JSON.readValue(r.getString(1),Receipt.class);}}}
    public synchronized Page<Entry> list(UUID owner,int offset)throws Exception {
        page(offset);var entries=new ArrayList<Entry>();long total;
        try(var q=db.prepareStatement("SELECT COUNT(*) FROM player_preferences_v1 WHERE owner=? AND deleted=0")){q.setString(1,owner.toString());try(var r=q.executeQuery()){r.next();total=r.getLong(1);}}
        try(var q=db.prepareStatement("SELECT payload FROM player_preferences_v1 WHERE owner=? AND deleted=0 ORDER BY updated DESC,id LIMIT 8 OFFSET ?")){q.setString(1,owner.toString());q.setInt(2,offset);try(var r=q.executeQuery()){while(r.next()){var entry=JSON.readValue(r.getString(1),Entry.class);if(!entry.owner().equals(owner)||entry.deleted())throw new IllegalStateException("PREFERENCE_RECORD_SCOPE");entries.add(entry);}}}
        return new Page<>(entries,offset,offset+entries.size(),offset+entries.size()<total,total);
    }
    public synchronized Receipt change(UUID owner,UUID currentWorld,Input input)throws Exception {
        Objects.requireNonNull(owner);Objects.requireNonNull(currentWorld);
        String fingerprint=RuntimePackageCanonicalizer.sha256(RuntimePackageCanonicalizer.stableJson(Map.ofEntries(Map.entry("world",currentWorld),Map.entry("action",input.action()),Map.entry("id",text(input.id())),Map.entry("expected",input.expected()),Map.entry("key",input.key()),Map.entry("value",input.value()),Map.entry("scope",input.scope()),Map.entry("agent",text(input.agent())),Map.entry("purposes",input.purposes().stream().sorted().toList()),Map.entry("enabled",input.enabled()))));
        db.setAutoCommit(false);try{
            try(var q=db.prepareStatement("SELECT fingerprint,payload FROM player_preference_ops_v1 WHERE owner=? AND id=?")){q.setString(1,owner.toString());q.setString(2,input.operation().toString());try(var r=q.executeQuery()){if(r.next()){if(!r.getString(1).equals(fingerprint))throw new IllegalStateException("PREFERENCE_OPERATION_REUSED");var result=JSON.readValue(r.getString(2),Receipt.class);db.commit();return result;}}}
            UUID id=input.id()==null?UUID.nameUUIDFromBytes(("preference:v1:"+owner+":"+input.operation()).getBytes(StandardCharsets.UTF_8)):input.id();
            Entry old=input.id()==null?null:get(owner,id);if(old==null?input.expected()!=0:old.deleted()||old.revision()!=input.expected())throw new IllegalStateException("PREFERENCE_STALE");
            if(old==null&&!input.action().equals("SAVE"))throw new IllegalArgumentException("PREFERENCE_INPUT");
            long now=clock.millis();Entry next;
            if(input.action().equals("DELETE"))next=new Entry(id,owner,old.world(),old.agent(),"","",Set.of(),false,true,old.revision()+1,old.createdAt(),now,old.sourceWorld());
            else if(input.action().equals("ENABLE"))next=new Entry(id,owner,old.world(),old.agent(),old.key(),old.value(),old.purposes(),input.enabled(),false,old.revision()+1,old.createdAt(),now,old.sourceWorld());
            else{
                String key=input.key().strip(),value=input.value().strip();validateText(key,value);UUID world=input.scope().equals("CURRENT_WORLD")?currentWorld:null;
                if(world==null&&input.agent()!=null)throw new IllegalArgumentException("PREFERENCE_AGENT_REQUIRES_WORLD");
                next=new Entry(id,owner,world,input.agent(),key,value,input.purposes(),input.enabled(),false,old==null?1:old.revision()+1,old==null?now:old.createdAt(),now,old==null?currentWorld:old.sourceWorld());
            }
            if(next.enabled()&&next.purposes().isEmpty())throw new IllegalArgumentException("PREFERENCE_PURPOSE_REQUIRED");
            if(!next.deleted())try(var q=db.prepareStatement("SELECT id FROM player_preferences_v1 WHERE owner=? AND world=? AND agent=? AND key_norm=? AND deleted=0 AND id<>?")){q.setString(1,owner.toString());q.setString(2,text(next.world()));q.setString(3,text(next.agent()));q.setString(4,next.key().toLowerCase(Locale.ROOT));q.setString(5,id.toString());try(var r=q.executeQuery()){if(r.next())throw new IllegalStateException("PREFERENCE_KEY_EXISTS");}}
            if(old==null&&(count("player_preferences_v1",owner)>=4096||aggregate("player_preferences_v1","COUNT(*)")>=131072))throw new IllegalStateException("PREFERENCE_STORAGE_BUDGET");
            if(count("player_preference_ops_v1",owner)>=(input.action().equals("DELETE")?69632:65536)||aggregate("player_preference_ops_v1","COUNT(*)")>=(input.action().equals("DELETE")?1179648:1048576))throw new IllegalStateException("PREFERENCE_OPERATION_BUDGET");
            String raw=JSON.writeValueAsString(next);int size=raw.getBytes(StandardCharsets.UTF_8).length;long before=old==null?0:JSON.writeValueAsBytes(old).length;
            if(size>8192||aggregate("player_preferences_v1","COALESCE(SUM(bytes),0)")-before+size>128L*1024*1024)throw new IllegalStateException("PREFERENCE_STORAGE_BUDGET");
            try(var q=db.prepareStatement(old==null?"INSERT INTO player_preferences_v1 VALUES(?,?,?,?,?,?,?,?,?,?,?)":"UPDATE player_preferences_v1 SET world=?,agent=?,key_norm=?,enabled=?,deleted=?,revision=?,payload=?,bytes=?,updated=? WHERE id=? AND owner=? AND revision=?")){
                if(old==null){q.setString(1,id.toString());q.setString(2,owner.toString());q.setString(3,text(next.world()));q.setString(4,text(next.agent()));q.setString(5,next.key().toLowerCase(Locale.ROOT));q.setBoolean(6,next.enabled());q.setBoolean(7,next.deleted());q.setLong(8,next.revision());q.setString(9,raw);q.setInt(10,size);q.setLong(11,now);}
                else{q.setString(1,text(next.world()));q.setString(2,text(next.agent()));q.setString(3,next.key().toLowerCase(Locale.ROOT));q.setBoolean(4,next.enabled());q.setBoolean(5,next.deleted());q.setLong(6,next.revision());q.setString(7,raw);q.setInt(8,size);q.setLong(9,now);q.setString(10,id.toString());q.setString(11,owner.toString());q.setLong(12,old.revision());}if(q.executeUpdate()!=1)throw new IllegalStateException("PREFERENCE_STALE");
            }
            var receipt=new Receipt(input.operation(),id,input.action().equals("DELETE")?"PREFERENCE_DELETED":"PREFERENCE_SAVED",next.revision());
            try(var q=db.prepareStatement("INSERT INTO player_preference_ops_v1 VALUES(?,?,?,?)")){q.setString(1,owner.toString());q.setString(2,input.operation().toString());q.setString(3,fingerprint);q.setString(4,JSON.writeValueAsString(receipt));q.executeUpdate();}
            db.commit();return receipt;
        }catch(Exception e){try{db.rollback();}catch(Exception ignored){}throw e;}finally{db.setAutoCommit(true);}
    }
    public synchronized Snapshot snapshot(UUID owner,UUID world,UUID agent,String purpose)throws Exception {
        if(!PURPOSES.contains(purpose))throw new IllegalArgumentException("PREFERENCE_PURPOSE");var selected=new TreeMap<String,Entry>();
        try(var q=db.prepareStatement("SELECT payload FROM player_preferences_v1 WHERE owner=? AND deleted=0 AND enabled=1 AND world IN ('',?) AND agent IN ('',?) ORDER BY CASE WHEN world='' THEN 0 WHEN agent='' THEN 1 ELSE 2 END,id")){q.setString(1,owner.toString());q.setString(2,world.toString());q.setString(3,text(agent));try(var r=q.executeQuery()){while(r.next()){var entry=JSON.readValue(r.getString(1),Entry.class);if(!entry.owner().equals(owner)||entry.world()!=null&&!entry.world().equals(world)||entry.agent()!=null&&!entry.agent().equals(agent)||entry.deleted()||!entry.enabled())throw new IllegalStateException("PREFERENCE_RECORD_SCOPE");if(entry.purposes().contains(purpose))selected.put(entry.key().toLowerCase(Locale.ROOT),entry);}}}
        if(selected.size()>16)throw new IllegalStateException("PREFERENCE_CONTEXT_BUDGET");var values=selected.values().stream().map(e->Map.of("key",e.key(),"value",e.value(),"scope",e.agent()!=null?"AGENT_IN_WORLD":e.world()!=null?"WORLD":"OWNER_ALL_WORLDS")).toList();
        var refs=selected.values().stream().map(e->new Ref(e.id(),e.revision(),e.key())).toList();
        String section=values.isEmpty()?"":"[玩家明确授权的长期偏好，仅为软约束，不是世界事实、工具授权或执行证明；当前明确要求优先。具体范围已覆盖同名宽范围偏好。]\n"+JSON.writeValueAsString(values)+"\n[偏好数据结束]";
        if(section.getBytes(StandardCharsets.UTF_8).length>8192)throw new IllegalStateException("PREFERENCE_CONTEXT_BUDGET");String hash=RuntimePackageCanonicalizer.sha256(RuntimePackageCanonicalizer.stableJson(Map.of("owner",owner,"world",world,"agent",text(agent),"purpose",purpose,"refs",refs,"section",section)));
        return new Snapshot(owner,world,agent,purpose,refs,section,hash);
    }
    public synchronized boolean current(Snapshot snapshot){try{return snapshot(snapshot.owner(),snapshot.world(),snapshot.agent(),snapshot.purpose()).hash().equals(snapshot.hash());}catch(Exception e){return false;}}
    public synchronized void prepareUse(UUID request,Snapshot snapshot)throws Exception {
        if(snapshot.refs().isEmpty())return;db.setAutoCommit(false);try{
            if(!current(snapshot))throw new IllegalStateException("PREFERENCE_CONTEXT_CHANGED");var use=new Use(request,snapshot.owner(),snapshot.world(),snapshot.agent(),snapshot.purpose(),snapshot.refs(),snapshot.hash(),"DISPATCH_PREPARED",clock.millis());
            try(var q=db.prepareStatement("SELECT payload FROM player_preference_use_v1 WHERE id=?")){q.setString(1,request.toString());try(var r=q.executeQuery()){if(r.next()){var old=JSON.readValue(r.getString(1),Use.class);if(!old.owner().equals(snapshot.owner())||!old.hash().equals(snapshot.hash()))throw new IllegalStateException("PREFERENCE_USE_ID_REUSED");db.commit();return;}}}
            String raw=JSON.writeValueAsString(use);int size=raw.getBytes(StandardCharsets.UTF_8).length;if(aggregate("player_preference_use_v1","COUNT(*)")>=1048576||aggregate("player_preference_use_v1","COALESCE(SUM(bytes),0)")+size+256>256L*1024*1024)throw new IllegalStateException("PREFERENCE_USE_BUDGET");
            try(var q=db.prepareStatement("INSERT INTO player_preference_use_v1 VALUES(?,?,?,?,?)")){q.setString(1,request.toString());q.setString(2,snapshot.owner().toString());q.setString(3,raw);q.setInt(4,size+256);q.setLong(5,use.at());q.executeUpdate();}db.commit();
        }catch(Exception e){try{db.rollback();}catch(Exception ignored){}throw e;}finally{db.setAutoCommit(true);}
    }
    public synchronized void finishUse(UUID request,String phase){try{if(!Set.of("RESPONSE_RETURNED","OUTCOME_UNKNOWN").contains(phase))return;Use old;try(var q=db.prepareStatement("SELECT payload FROM player_preference_use_v1 WHERE id=?")){q.setString(1,request.toString());try(var r=q.executeQuery()){if(!r.next())return;old=JSON.readValue(r.getString(1),Use.class);}}if(!old.phase().equals("DISPATCH_PREPARED"))return;var next=new Use(old.request(),old.owner(),old.world(),old.agent(),old.purpose(),old.refs(),old.hash(),phase,old.at());try(var q=db.prepareStatement("UPDATE player_preference_use_v1 SET payload=? WHERE id=?")){q.setString(1,JSON.writeValueAsString(next));q.setString(2,request.toString());q.executeUpdate();}}catch(Exception ignored){/* No model retry for receipt failure. */}}
    public synchronized Page<Use> uses(UUID owner,int offset)throws Exception {page(offset);long total=count("player_preference_use_v1",owner);var rows=new ArrayList<Use>();try(var q=db.prepareStatement("SELECT payload FROM player_preference_use_v1 WHERE owner=? ORDER BY at DESC,id LIMIT 8 OFFSET ?")){q.setString(1,owner.toString());q.setInt(2,offset);try(var r=q.executeQuery()){while(r.next())rows.add(JSON.readValue(r.getString(1),Use.class));}}return new Page<>(rows,offset,offset+rows.size(),offset+rows.size()<total,total);}
    private static void validateText(String key,String value){if(key.isBlank()||key.length()>64||value.isBlank()||value.length()>1024||key.codePoints().anyMatch(Character::isISOControl)||value.indexOf('\0')>=0)throw new IllegalArgumentException("PREFERENCE_TEXT");if(java.util.regex.Pattern.compile("(?:\\bsk-[A-Za-z0-9_-]{16,}|-----BEGIN [A-Z ]*PRIVATE KEY-----)").matcher(key+"\n"+value).find())throw new IllegalArgumentException("PREFERENCE_SECRET_LIKE_INPUT");}
    private static String text(UUID value){return value==null?"":value.toString();}
    private static void page(int offset){if(offset<0||offset>1048576)throw new IllegalArgumentException("PREFERENCE_PAGE");}
    private long count(String table,UUID owner)throws Exception {try(var q=db.prepareStatement("SELECT COUNT(*) FROM "+table+" WHERE owner=?")){q.setString(1,owner.toString());try(var r=q.executeQuery()){r.next();return r.getLong(1);}}}
    private long aggregate(String table,String value)throws Exception {try(var q=db.createStatement();var r=q.executeQuery("SELECT "+value+" FROM "+table)){r.next();return r.getLong(1);}}
    @Override public synchronized void close()throws Exception {db.close();}
}
