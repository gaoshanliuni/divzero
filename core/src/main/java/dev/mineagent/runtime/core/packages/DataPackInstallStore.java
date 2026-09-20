package dev.mineagent.runtime.core.packages;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mineagent.runtime.api.packages.RuntimePackage;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** World-scoped installation intent. State files never stand in for the actual Minecraft resource-manager result. */
public final class DataPackInstallStore implements AutoCloseable {
    public static final String ARTIFACTS="mineagent_data_pack_artifacts_v1",JOBS="mineagent_data_pack_jobs_v1";
    public record Input(UUID operation,UUID owner,UUID pkg,String canonical,long packageRevision,String action,String selection,String environment,long runGeneration,long manageGeneration,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) UUID reopenOperation){
        public Input(UUID operation,UUID owner,UUID pkg,String canonical,long packageRevision,String action,String selection,String environment,long runGeneration,long manageGeneration){this(operation,owner,pkg,canonical,packageRevision,action,selection,environment,runGeneration,manageGeneration,null);}
        public Input{Objects.requireNonNull(operation);Objects.requireNonNull(owner);Objects.requireNonNull(pkg);if(!Set.of("ENABLE","DISABLE","ENABLE_AT_REOPEN","CANCEL_REOPEN").contains(action)||packageRevision<1||runGeneration<0||manageGeneration<0||!canonical.matches("[a-f0-9]{64}")||!selection.matches("[a-f0-9]{64}")||!environment.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("DATA_PACK_ARGUMENTS");}
    }
    public record Job(Input input,String phase,String code,long revision){}
    public record Artifact(UUID world,UUID owner,String savePath,RuntimePackage manifest,String filename,String zipHash,long runGeneration,long manageGeneration,String state,long revision){}
    private final UUID world;private final Connection db;private final ObjectMapper json=new ObjectMapper();private final Map<String,Artifact> artifacts=new LinkedHashMap<>();
    public DataPackInstallStore(Path database,UUID world)throws Exception{
        this.world=world;db=DriverManager.getConnection("jdbc:sqlite:"+database.toAbsolutePath());
        try{try(var s=db.createStatement()){s.execute("PRAGMA busy_timeout=1500");s.execute("PRAGMA journal_mode=WAL");s.execute("CREATE TABLE IF NOT EXISTS "+ARTIFACTS+"(world TEXT NOT NULL,filename TEXT NOT NULL,package_id TEXT NOT NULL,owner TEXT NOT NULL,payload TEXT NOT NULL,bytes INTEGER NOT NULL,content_bytes INTEGER NOT NULL,PRIMARY KEY(world,filename))");s.execute("CREATE TABLE IF NOT EXISTS "+JOBS+"(world TEXT NOT NULL,id TEXT NOT NULL,package_id TEXT NOT NULL,owner TEXT NOT NULL,payload TEXT NOT NULL,phase TEXT NOT NULL,bytes INTEGER NOT NULL,updated INTEGER NOT NULL,PRIMARY KEY(world,id))");s.execute("CREATE INDEX IF NOT EXISTS mineagent_data_pack_job_lookup ON "+JOBS+"(world,owner,package_id,updated DESC)");}
            WorldReopenPlans.initialize(db);
            try(var q=db.prepareStatement("SELECT filename,payload FROM "+ARTIFACTS+" WHERE world=?")){q.setString(1,world.toString());try(var rows=q.executeQuery()){while(rows.next()){var a=json.readValue(rows.getString(2),Artifact.class);if(!a.world().equals(world)||!a.filename().equals(rows.getString(1)))throw new IllegalStateException("DATA_PACK_RECORD_CONTEXT");artifacts.put(a.filename(),a);}}}
            var interrupted=new ArrayList<Job>();try(var q=db.prepareStatement("SELECT payload FROM "+JOBS+" WHERE world=? AND phase IN ('PREPARING','BUILDING','DISPATCHING')")){q.setString(1,world.toString());try(var r=q.executeQuery()){while(r.next())interrupted.add(json.readValue(r.getString(1),Job.class));}}
            for(var job:interrupted)finish(job,false,"DATA_PACK_RESTART_UNKNOWN");
        }catch(Exception failure){db.close();throw failure;}
    }
    public synchronized Optional<Job> job(UUID owner,UUID id)throws Exception{
        try(var q=db.prepareStatement("SELECT owner,payload FROM "+JOBS+" WHERE world=? AND id=?")){q.setString(1,world.toString());q.setString(2,id.toString());try(var r=q.executeQuery()){if(!r.next())return Optional.empty();if(!owner.toString().equals(r.getString(1)))throw new SecurityException("DATA_PACK_OWNER");return Optional.of(json.readValue(r.getString(2),Job.class));}}
    }
    public synchronized Optional<Job> latest(UUID owner,UUID pkg)throws Exception{
        try(var q=db.prepareStatement("SELECT payload FROM "+JOBS+" WHERE world=? AND owner=? AND package_id=? ORDER BY updated DESC LIMIT 1")){q.setString(1,world.toString());q.setString(2,owner.toString());q.setString(3,pkg.toString());try(var r=q.executeQuery()){return r.next()?Optional.of(json.readValue(r.getString(1),Job.class)):Optional.empty();}}
    }
    public synchronized List<Artifact> artifacts(UUID owner,UUID pkg){return artifacts.values().stream().filter(a->a.owner().equals(owner)&&a.manifest().packageId().equals(pkg)).toList();}
    public synchronized Artifact artifact(String filename){return artifacts.get(filename);}
    public synchronized Job begin(Input input)throws Exception{
        var old=job(input.owner(),input.operation());if(old.isPresent()){if(!old.get().input().equals(input))throw new IllegalStateException("DATA_PACK_OPERATION_REUSED");return old.get();}
        if(count(JOBS)>=65536||bytes(JOBS)>64L*1024*1024-16384)throw new IllegalStateException("DATA_PACK_JOB_BUDGET");var next=new Job(input,"PREPARING","",1);writeJob(next);return next;
    }
    public synchronized Job stage(Job job,RuntimePackage manifest,Path save)throws Exception{
        require(job,"PREPARING");var plan=DataPackPlan.inspect(manifest);var old=artifacts.get(plan.filename());
        if(old!=null&&!old.owner().equals(job.input().owner()))throw new SecurityException("DATA_PACK_OWNER");
        var next=new Artifact(world,job.input().owner(),pathKey(save.toRealPath()),manifest,plan.filename(),old==null?"":old.zipHash(),job.input().runGeneration(),job.input().manageGeneration(),"PREPARING",old==null?1:old.revision()+1);
        String raw=json.writeValueAsString(next);long size=raw.getBytes(java.nio.charset.StandardCharsets.UTF_8).length,prior=old==null?0:json.writeValueAsBytes(old).length;long previousContent=old==null?0:DataPackPlan.inspect(old.manifest()).bytes();if(size>512*1024||old==null&&count(ARTIFACTS)>=4096||bytes(ARTIFACTS)+size-prior+256>32L*1024*1024||contentBytes()+plan.bytes()-previousContent>512L*1024*1024)throw new IllegalStateException("DATA_PACK_ARTIFACT_BUDGET");
        var changed=new Job(job.input(),"BUILDING","",job.revision()+1);db.setAutoCommit(false);
        try{writeArtifact(next);writeJob(changed);db.commit();artifacts.put(next.filename(),next);return changed;}catch(Exception failure){db.rollback();throw failure;}finally{db.setAutoCommit(true);}
    }
    public synchronized Job dispatch(Job job,String zipHash)throws Exception{
        require(job,job.input().action().equals("ENABLE")?"BUILDING":"PREPARING");var changed=new Job(job.input(),"DISPATCHING","",job.revision()+1);var pending=new ArrayList<Artifact>();
        if(job.input().action().equals("ENABLE")){var a=artifacts.values().stream().filter(v->v.owner().equals(job.input().owner())&&v.manifest().canonicalSha256().equals(job.input().canonical())&&v.manifest().packageId().equals(job.input().pkg())).findFirst().orElseThrow();pending.add(copy(a,"DISPATCHING",zipHash));}
        else for(var a:artifacts(job.input().owner(),job.input().pkg()))pending.add(copy(a,"DISABLING",a.zipHash()));
        db.setAutoCommit(false);try{for(var a:pending)writeArtifact(a);writeJob(changed);db.commit();pending.forEach(a->artifacts.put(a.filename(),a));return changed;}catch(Exception failure){db.rollback();throw failure;}finally{db.setAutoCommit(true);}
    }
    public synchronized Job finish(Job job,boolean success,String code)throws Exception{
        var current=job(job.input().owner(),job.input().operation()).orElseThrow();if(!current.input().equals(job.input()))throw new IllegalStateException("DATA_PACK_OPERATION_REUSED");refresh(job.input().owner(),job.input().pkg());if(Set.of("COMPLETED","FAILED","UNKNOWN").contains(current.phase()))return current;
        if(code==null||!code.matches("[A-Z0-9_]{0,80}"))throw new IllegalArgumentException("DATA_PACK_CODE");
        boolean dispatched=current.phase().equals("DISPATCHING");var next=new Job(job.input(),success?"COMPLETED":dispatched?"UNKNOWN":"FAILED",code,current.revision()+1);var changed=new ArrayList<Artifact>();
        for(var a:artifacts(job.input().owner(),job.input().pkg())){
            boolean target=a.manifest().canonicalSha256().equals(job.input().canonical());
            if(success)changed.add(copy(a,job.input().action().equals("ENABLE")&&target?"ENABLED":"DISABLED",a.zipHash()));
            else if(Set.of("PREPARING","DISPATCHING","DISABLING").contains(a.state()))changed.add(copy(a,dispatched?"UNKNOWN":"FAILED",a.zipHash()));
        }
        db.setAutoCommit(false);try{for(var a:changed)writeArtifact(a);writeJob(next);db.commit();changed.forEach(a->artifacts.put(a.filename(),a));return next;}catch(Exception failure){db.rollback();throw failure;}finally{db.setAutoCommit(true);}
    }
    public synchronized Optional<WorldReopenPlans.Plan> reopen()throws Exception{return WorldReopenPlans.pending(db,world);}
    public synchronized WorldReopenPlans.Plan prepareReopen(Job job,String hash,UUID source,List<String> before,List<String> disabled,List<String> desired,List<String> desiredDisabled,String worldgenHash)throws Exception{
        var a=artifacts(job.input().owner(),job.input().pkg()).stream().filter(v->v.manifest().canonicalSha256().equals(job.input().canonical())).findFirst().orElseThrow();
        var result=WorldReopenPlans.prepare(db,world,job,a,hash,source,before,disabled,desired,desiredDisabled,worldgenHash);refresh(job.input().owner(),job.input().pkg());return result;
    }
    public synchronized void readyReopen(WorldReopenPlans.Plan plan)throws Exception{WorldReopenPlans.ready(db,world,plan.input().operation());refresh(plan.input().owner(),plan.input().pkg());}
    public synchronized void failedReopen(WorldReopenPlans.Plan plan,String code)throws Exception{WorldReopenPlans.failed(db,world,plan.input().operation(),code);refresh(plan.input().owner(),plan.input().pkg());}
    public synchronized void cancelReopen(WorldReopenPlans.Plan plan,UUID cancelRequest,String actor)throws Exception{WorldReopenPlans.cancel(db,world,plan.input().operation(),cancelRequest,actor);refresh(plan.input().owner(),plan.input().pkg());}
    public synchronized void completeReopen(WorldReopenPlans.Plan plan,boolean cancelled,String code)throws Exception{WorldReopenPlans.complete(db,world,plan.input().operation(),cancelled,code);refresh(plan.input().owner(),plan.input().pkg());}
    private void refresh(UUID owner,UUID pkg)throws Exception{
        var latest=new ArrayList<Artifact>();try(var q=db.prepareStatement("SELECT filename,payload FROM "+ARTIFACTS+" WHERE world=? AND owner=? AND package_id=?")){q.setString(1,world.toString());q.setString(2,owner.toString());q.setString(3,pkg.toString());try(var r=q.executeQuery()){while(r.next()){var a=json.readValue(r.getString(2),Artifact.class);if(!a.world().equals(world)||!a.owner().equals(owner)||!a.manifest().packageId().equals(pkg)||!a.filename().equals(r.getString(1)))throw new IllegalStateException("DATA_PACK_RECORD_CONTEXT");latest.add(a);}}}
        artifacts.values().removeIf(a->a.owner().equals(owner)&&a.manifest().packageId().equals(pkg));latest.forEach(a->artifacts.put(a.filename(),a));
    }
    private void require(Job expected,String phase)throws Exception{var current=job(expected.input().owner(),expected.input().operation()).orElseThrow();if(!current.equals(expected)||!current.phase().equals(phase))throw new IllegalStateException("DATA_PACK_JOB_CHANGED");}
    private Artifact copy(Artifact a,String state,String hash){return new Artifact(a.world(),a.owner(),a.savePath(),a.manifest(),a.filename(),hash,a.runGeneration(),a.manageGeneration(),state,a.revision()+1);}
    private void writeArtifact(Artifact a)throws Exception{
        String raw=json.writeValueAsString(a);try(var q=db.prepareStatement("INSERT INTO "+ARTIFACTS+" VALUES(?,?,?,?,?,?,?) ON CONFLICT(world,filename) DO UPDATE SET payload=excluded.payload,bytes=excluded.bytes,content_bytes=excluded.content_bytes")){q.setString(1,world.toString());q.setString(2,a.filename());q.setString(3,a.manifest().packageId().toString());q.setString(4,a.owner().toString());q.setString(5,raw);q.setInt(6,raw.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);q.setLong(7,DataPackPlan.inspect(a.manifest()).bytes());q.executeUpdate();}
    }
    private void writeJob(Job job)throws Exception{String raw=json.writeValueAsString(job);if(raw.length()>8192)throw new IllegalStateException("DATA_PACK_JOB_BUDGET");try(var q=db.prepareStatement("INSERT INTO "+JOBS+" VALUES(?,?,?,?,?,?,?,?) ON CONFLICT(world,id) DO UPDATE SET payload=excluded.payload,phase=excluded.phase,bytes=excluded.bytes,updated=excluded.updated")){q.setString(1,world.toString());q.setString(2,job.input().operation().toString());q.setString(3,job.input().pkg().toString());q.setString(4,job.input().owner().toString());q.setString(5,raw);q.setString(6,job.phase());q.setInt(7,raw.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);q.setLong(8,System.currentTimeMillis());q.executeUpdate();}}
    private long count(String table)throws SQLException{return aggregate(table,"COUNT(*)");}private long bytes(String table)throws SQLException{return aggregate(table,"COALESCE(SUM(bytes),0)");}private long contentBytes()throws SQLException{return aggregate(ARTIFACTS,"COALESCE(SUM(content_bytes),0)");}
    private long aggregate(String table,String expression)throws SQLException{try(var q=db.prepareStatement("SELECT "+expression+" FROM "+table+" WHERE world=?")){q.setString(1,world.toString());try(var r=q.executeQuery()){return r.next()?r.getLong(1):0;}}}
    public static String pathKey(Path path){String value=path.toAbsolutePath().normalize().toString();return System.getProperty("os.name","").startsWith("Windows")?value.toLowerCase(Locale.ROOT):value;}
    @Override public synchronized void close()throws Exception{db.close();}
}
