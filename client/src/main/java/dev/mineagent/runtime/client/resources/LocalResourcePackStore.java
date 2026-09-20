package dev.mineagent.runtime.client.resources;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mineagent.runtime.api.packages.RuntimePackage;
import dev.mineagent.runtime.core.packages.*;
import java.nio.file.*;
import java.nio.channels.*;
import java.sql.*;
import java.util.*;

/** Local-machine resource approvals. A server download alone never creates an enabled approval. */
public final class LocalResourcePackStore implements AutoCloseable {
    public record Asset(String filename,String serverId,String fingerprint,RuntimePackage manifest,String archiveHash,String environment,String state,long revision,boolean globalConsent,String directory){}
    public record Input(UUID operation,String filename,String action,long expectedRevision,String selection,String environment,String actor,String directory){public Input{Objects.requireNonNull(operation);if(!ResourcePackPlan.managedName(filename)||!Set.of("ENABLE","DISABLE").contains(action)||expectedRevision<1||!selection.matches("[a-f0-9]{64}")||!environment.matches("[a-f0-9]{64}")||actor==null||actor.length()>80)throw new IllegalArgumentException("RESOURCE_PACK_OPERATION");}}
    public record Job(Input input,List<String> before,List<String> after,String phase,String code,long revision){public Job{before=List.copyOf(before);after=List.copyOf(after);if(before.size()>512||after.size()>512)throw new IllegalArgumentException("RESOURCE_PACK_SELECTION_LIMIT");}}
    private static final Map<Path,LocalResourcePackStore> OPEN=new HashMap<>();
    private final Path root;private final Connection db;private final FileChannel channel;private final FileLock lock;private final ObjectMapper json=new ObjectMapper();private final Map<String,Asset> assets=new LinkedHashMap<>();
    public static synchronized LocalResourcePackStore get(Path root)throws Exception{Path real=root.toRealPath();var current=OPEN.get(real);if(current==null){current=new LocalResourcePackStore(real);OPEN.put(real,current);}return current;}
    private LocalResourcePackStore(Path root)throws Exception{
        this.root=root;Path directory=root.resolve("config");Files.createDirectories(directory);if(!directory.toRealPath().getParent().equals(root))throw new IllegalStateException("RESOURCE_PACK_CONFIG_LINK");
        Path file=directory.resolve("mineagent-resource-packs.db"),lease=directory.resolve("mineagent-resource-packs.lock");
        for(Path p:List.of(file,lease))if(Files.exists(p,LinkOption.NOFOLLOW_LINKS)&&(!Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(p)))throw new IllegalStateException("RESOURCE_PACK_STORE_LINK");
        channel=FileChannel.open(lease,StandardOpenOption.CREATE,StandardOpenOption.WRITE);FileLock acquired=null;Connection connection=null;
        try{acquired=channel.tryLock();if(acquired==null)throw new IllegalStateException("RESOURCE_PACK_STORE_IN_USE");connection=DriverManager.getConnection("jdbc:sqlite:"+file);db=connection;lock=acquired;
            try(var s=db.createStatement()){s.execute("PRAGMA busy_timeout=1500");s.execute("PRAGMA journal_mode=WAL");s.execute("CREATE TABLE IF NOT EXISTS resource_assets_v1(filename TEXT PRIMARY KEY,payload TEXT NOT NULL,bytes INTEGER NOT NULL,content_bytes INTEGER NOT NULL)");s.execute("CREATE TABLE IF NOT EXISTS resource_jobs_v1(id TEXT PRIMARY KEY,filename TEXT NOT NULL,payload TEXT NOT NULL,phase TEXT NOT NULL,bytes INTEGER NOT NULL,updated INTEGER NOT NULL)");s.execute("CREATE INDEX IF NOT EXISTS resource_job_asset ON resource_jobs_v1(filename,updated DESC)");}
            reloadAssets();var interrupted=new ArrayList<Job>();try(var q=db.prepareStatement("SELECT payload FROM resource_jobs_v1 WHERE phase IN ('PREPARING','DISPATCHING')");var r=q.executeQuery()){while(r.next())interrupted.add(json.readValue(r.getString(1),Job.class));}for(var j:interrupted)finish(j,false,"RESOURCE_PACK_RESTART_UNKNOWN");
        }catch(Exception failure){if(connection!=null)connection.close();if(acquired!=null)acquired.release();channel.close();throw failure;}
    }
    public Path cacheDirectory(){return root.resolve("mineagent-runtime-data/client-resource-cache");}
    public static String publisher(String server,String fingerprint)throws Exception{return RuntimePackageCanonicalizer.sha256(RuntimePackageCanonicalizer.stableJson(Map.of("server",server,"fingerprint",fingerprint)));}
    public synchronized Asset get(String filename){var value=assets.get(filename);if(value==null)throw new IllegalStateException("RESOURCE_PACK_NOT_CACHED");return value;}
    public synchronized List<Asset> list(){return List.copyOf(assets.values());}
    public synchronized Optional<Job> job(UUID id)throws Exception{try(var q=db.prepareStatement("SELECT payload FROM resource_jobs_v1 WHERE id=?")){q.setString(1,id.toString());try(var r=q.executeQuery()){return r.next()?Optional.of(json.readValue(r.getString(1),Job.class)):Optional.empty();}}}
    public synchronized Optional<Job> latest(String filename)throws Exception{try(var q=db.prepareStatement("SELECT payload FROM resource_jobs_v1 WHERE filename=? ORDER BY updated DESC LIMIT 1")){q.setString(1,filename);try(var r=q.executeQuery()){return r.next()?Optional.of(json.readValue(r.getString(1),Job.class)):Optional.empty();}}}
    public synchronized Asset downloaded(String server,String fingerprint,ResourcePackPlan.Resolved resolved)throws Exception{
        String filename=resolved.plan().filename(publisher(server,fingerprint));var old=assets.get(filename);if(old!=null){if(!old.archiveHash().equals(resolved.archiveHash()))throw new IllegalStateException("RESOURCE_PACK_CACHE_CONFLICT");return old;}
        var next=new Asset(filename,server,fingerprint,resolved.plan().manifest(),resolved.archiveHash(),"","DOWNLOADED",1,false,"");byte[] raw=json.writeValueAsBytes(next);
        if(raw.length>512*1024||aggregate("resource_assets_v1","COUNT(*)")>=4096||aggregate("resource_assets_v1","COALESCE(SUM(bytes),0)")+raw.length+512>32L*1024*1024||aggregate("resource_assets_v1","COALESCE(SUM(content_bytes),0)")+resolved.archive().length>512L*1024*1024)throw new IllegalStateException("RESOURCE_PACK_CACHE_BUDGET");
        ResourcePackPlan.publish(cacheDirectory(),filename,resolved.archive(),resolved.archiveHash());write(next);assets.put(filename,next);return next;
    }
    public synchronized Job begin(Input input,List<String> before,List<String> after)throws Exception{
        var old=job(input.operation());if(old.isPresent()){if(!old.get().input().equals(input))throw new IllegalStateException("RESOURCE_PACK_OPERATION_REUSED");return old.get();}
        var asset=get(input.filename());if(asset.revision()!=input.expectedRevision())throw new IllegalStateException("RESOURCE_PACK_STALE");if(aggregate("resource_jobs_v1","COUNT(*)")>=65536||aggregate("resource_jobs_v1","COALESCE(SUM(bytes),0)")>64L*1024*1024-65536)throw new IllegalStateException("RESOURCE_PACK_JOB_BUDGET");var next=new Job(input,before,after,"PREPARING","",1);write(next);return next;
    }
    public synchronized Job dispatch(Job expected)throws Exception{
        var current=job(expected.input().operation()).orElseThrow();if(!current.equals(expected)||!current.phase().equals("PREPARING"))throw new IllegalStateException("RESOURCE_PACK_JOB_CHANGED");var a=get(current.input().filename());if(a.revision()!=current.input().expectedRevision())throw new IllegalStateException("RESOURCE_PACK_STALE");
        var next=new Job(current.input(),current.before(),current.after(),"DISPATCHING","",current.revision()+1);var asset=new Asset(a.filename(),a.serverId(),a.fingerprint(),a.manifest(),a.archiveHash(),current.input().environment(),current.input().action().equals("ENABLE")?"DISPATCHING":"DISABLING",a.revision()+1,current.input().action().equals("ENABLE"),current.input().directory());
        db.setAutoCommit(false);try{write(asset);write(next);db.commit();assets.put(asset.filename(),asset);return next;}catch(Exception failure){db.rollback();throw failure;}finally{db.setAutoCommit(true);}
    }
    public synchronized Job finish(Job expected,boolean success,String code)throws Exception{
        var current=job(expected.input().operation()).orElseThrow();if(!current.input().equals(expected.input()))throw new IllegalStateException("RESOURCE_PACK_OPERATION_REUSED");reloadAssets();if(Set.of("COMPLETED","FAILED","UNKNOWN").contains(current.phase()))return current;
        if(code==null||!code.matches("[A-Z0-9_]{1,80}"))throw new IllegalArgumentException("RESOURCE_PACK_CODE");boolean dispatched=current.phase().equals("DISPATCHING");var next=new Job(current.input(),current.before(),current.after(),success?"COMPLETED":dispatched?"UNKNOWN":"FAILED",code,current.revision()+1);var changed=new ArrayList<Asset>();var target=get(current.input().filename());
        if(success){for(var a:assets.values())if(a.fingerprint().equals(target.fingerprint())&&a.serverId().equals(target.serverId())&&a.manifest().packageId().equals(target.manifest().packageId())){boolean enabled=current.input().action().equals("ENABLE")&&a.filename().equals(target.filename());changed.add(new Asset(a.filename(),a.serverId(),a.fingerprint(),a.manifest(),a.archiveHash(),a.environment(),enabled?"ENABLED":"DISABLED",a.revision()+1,enabled,a.directory()));}}
        else if(dispatched)changed.add(new Asset(target.filename(),target.serverId(),target.fingerprint(),target.manifest(),target.archiveHash(),target.environment(),"UNKNOWN",target.revision()+1,false,target.directory()));
        db.setAutoCommit(false);try{for(var a:changed)write(a);write(next);db.commit();changed.forEach(a->assets.put(a.filename(),a));return next;}catch(Exception failure){db.rollback();throw failure;}finally{db.setAutoCommit(true);}
    }
    private void reloadAssets()throws Exception{var next=new LinkedHashMap<String,Asset>();try(var q=db.prepareStatement("SELECT filename,payload FROM resource_assets_v1");var r=q.executeQuery()){while(r.next()){var a=json.readValue(r.getString(2),Asset.class);if(!a.filename().equals(r.getString(1))||!ResourcePackPlan.managedName(a.filename()))throw new IllegalStateException("RESOURCE_PACK_RECORD");next.put(a.filename(),a);}}assets.clear();assets.putAll(next);}
    private void write(Asset a)throws Exception{String raw=json.writeValueAsString(a);try(var q=db.prepareStatement("INSERT INTO resource_assets_v1 VALUES(?,?,?,?) ON CONFLICT(filename) DO UPDATE SET payload=excluded.payload,bytes=excluded.bytes")){q.setString(1,a.filename());q.setString(2,raw);q.setInt(3,raw.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);q.setLong(4,ResourcePackPlan.inspect(a.manifest()).bytes());q.executeUpdate();}}
    private void write(Job j)throws Exception{String raw=json.writeValueAsString(j);if(raw.length()>65536)throw new IllegalStateException("RESOURCE_PACK_JOB_BUDGET");try(var q=db.prepareStatement("INSERT INTO resource_jobs_v1 VALUES(?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET payload=excluded.payload,phase=excluded.phase,bytes=excluded.bytes,updated=excluded.updated")){q.setString(1,j.input().operation().toString());q.setString(2,j.input().filename());q.setString(3,raw);q.setString(4,j.phase());q.setInt(5,raw.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);q.setLong(6,System.currentTimeMillis());q.executeUpdate();}}
    private long aggregate(String table,String expression)throws SQLException{try(var q=db.prepareStatement("SELECT "+expression+" FROM "+table);var r=q.executeQuery()){return r.next()?r.getLong(1):0;}}
    @Override public synchronized void close()throws Exception{db.close();lock.release();channel.close();}
}
