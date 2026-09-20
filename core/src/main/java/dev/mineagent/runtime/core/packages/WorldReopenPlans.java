package dev.mineagent.runtime.core.packages;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** A durable next-open selection, distinct from the running server selection. No Native callbacks run inside these transactions. */
public final class WorldReopenPlans {
    public static final String TABLE="mineagent_world_reopen_plans_v1";
    private static final ObjectMapper JSON=new ObjectMapper();
    public record Plan(UUID world,DataPackInstallStore.Input input,String savePath,String filename,String zipHash,UUID blockedSource,
            List<String> beforeEnabled,List<String> beforeDisabled,List<String> desiredEnabled,List<String> desiredDisabled,
            String state,String code,long revision,UUID cancelRequest,String cancelActor,String worldgenHash,boolean nativeStarted){
        public Plan{beforeEnabled=names(beforeEnabled);beforeDisabled=names(beforeDisabled);desiredEnabled=names(desiredEnabled);desiredDisabled=names(desiredDisabled);cancelActor=cancelActor==null?"":cancelActor;worldgenHash=worldgenHash==null?"":worldgenHash;if(!worldgenHash.matches("[a-f0-9]{64}")||revision<1||cancelActor.length()>160)throw new IllegalArgumentException("WORLD_REOPEN_RECORD");}
        public Map<String,Object> view(){return Map.ofEntries(Map.entry("operation",input.operation()),Map.entry("owner",input.owner()),Map.entry("packageId",input.pkg()),Map.entry("canonical",input.canonical()),Map.entry("state",state),Map.entry("code",code),Map.entry("revision",revision),Map.entry("beforeCount",beforeEnabled.size()),Map.entry("desiredCount",desiredEnabled.size()),Map.entry("filename",filename),Map.entry("cancelActor",cancelActor),Map.entry("nativeStarted",nativeStarted));}
    }
    private WorldReopenPlans(){}
    private static List<String> names(List<String> value){if(value==null||value.size()>512||value.stream().anyMatch(v->v==null||v.isBlank()||v.length()>512)||new HashSet<>(value).size()!=value.size())throw new IllegalArgumentException("WORLD_REOPEN_SELECTION_LIMIT");return List.copyOf(value);}
    public static void initialize(Connection db)throws SQLException{try(var s=db.createStatement()){s.execute("CREATE TABLE IF NOT EXISTS "+TABLE+"(world TEXT NOT NULL,id TEXT NOT NULL,filename TEXT NOT NULL,save_path TEXT NOT NULL,payload TEXT NOT NULL,state TEXT NOT NULL,bytes INTEGER NOT NULL,updated INTEGER NOT NULL,PRIMARY KEY(world,id))");s.execute("CREATE INDEX IF NOT EXISTS mineagent_world_reopen_pending ON "+TABLE+"(world,state,updated DESC)");s.execute("CREATE INDEX IF NOT EXISTS mineagent_world_reopen_path ON "+TABLE+"(save_path,state)");}}
    private static boolean present(Connection db)throws SQLException{try(var q=db.prepareStatement("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?")){q.setString(1,TABLE);try(var r=q.executeQuery()){return r.next();}}}
    public static boolean pendingAt(Connection db,String save)throws Exception{if(!present(db))return false;try(var q=db.prepareStatement("SELECT 1 FROM "+TABLE+" WHERE save_path=? AND state NOT IN ('APPLIED','CANCELLED') LIMIT 1")){q.setString(1,save);try(var r=q.executeQuery()){return r.next();}}}
    public static Optional<Plan> pending(Connection db,UUID world)throws Exception{
        if(!present(db))return Optional.empty();try(var q=db.prepareStatement("SELECT payload FROM "+TABLE+" WHERE world=? AND state NOT IN ('APPLIED','CANCELLED') ORDER BY updated DESC LIMIT 2")){q.setString(1,world.toString());try(var r=q.executeQuery()){if(!r.next())return Optional.empty();var plan=parse(r.getString(1));if(r.next())throw new IllegalStateException("WORLD_REOPEN_MULTIPLE_PENDING");return Optional.of(plan);}}
    }
    public static List<Plan> listPending(Connection db,int offset)throws Exception{
        if(!present(db))return List.of();if(offset<0||offset>65536)throw new IllegalArgumentException("WORLD_REOPEN_PAGE");var rows=new ArrayList<Plan>();try(var q=db.prepareStatement("SELECT payload FROM "+TABLE+" WHERE state NOT IN ('APPLIED','CANCELLED') ORDER BY updated DESC LIMIT 8 OFFSET ?")){q.setInt(1,offset);try(var r=q.executeQuery()){while(r.next())rows.add(parse(r.getString(1)));}}return rows;
    }
    public static Plan get(Connection db,UUID world,UUID operation)throws Exception{try(var q=db.prepareStatement("SELECT payload FROM "+TABLE+" WHERE world=? AND id=?")){q.setString(1,world.toString());q.setString(2,operation.toString());try(var r=q.executeQuery()){if(!r.next())throw new IllegalStateException("WORLD_REOPEN_PLAN_MISSING");var p=parse(r.getString(1));if(!p.world().equals(world)||!p.input().operation().equals(operation))throw new IllegalStateException("WORLD_REOPEN_RECORD");return p;}}}
    private static Plan parse(String raw)throws Exception{if(raw.length()>256*1024)throw new IllegalStateException("WORLD_REOPEN_RECORD_LIMIT");return JSON.readValue(raw,Plan.class);}
    public static Plan prepare(Connection db,UUID world,DataPackInstallStore.Job job,DataPackInstallStore.Artifact artifact,String hash,UUID source,List<String> before,List<String> disabled,List<String> desired,List<String> desiredDisabled,String worldgenHash)throws Exception{
        if(!job.phase().equals("BUILDING")||!job.input().action().equals("ENABLE_AT_REOPEN")||!DataPackPlan.inspect(artifact.manifest()).reopensWorld())throw new IllegalStateException("WORLD_REOPEN_ARGUMENTS");
        var plan=new Plan(world,job.input(),artifact.savePath(),artifact.filename(),hash,source,before,disabled,desired,desiredDisabled,"PREPARING_SELECTION","",1,null,"",worldgenHash,false);
        db.setAutoCommit(false);try{
            if(pending(db,world).isPresent())throw new IllegalStateException("WORLD_REOPEN_PENDING");
            try(var q=db.prepareStatement("SELECT COUNT(*),COALESCE(SUM(bytes),0) FROM "+TABLE+" WHERE world=?")){q.setString(1,world.toString());try(var r=q.executeQuery()){if(r.next()&&(r.getLong(1)>=4096||r.getLong(2)+JSON.writeValueAsBytes(plan).length+4096>64L*1024*1024))throw new IllegalStateException("WORLD_REOPEN_PLAN_BUDGET");}}
            artifact(db,world,artifact.filename(),"STAGED_REOPEN",hash);job(db,world,job.input().operation(),"SAVING_REOPEN","");write(db,plan);db.commit();return plan;
        }catch(Exception failure){db.rollback();throw failure;}finally{db.setAutoCommit(true);}
    }
    public static void ready(Connection db,UUID world,UUID operation)throws Exception{
        db.setAutoCommit(false);try{var p=get(db,world,operation);if(!p.state().equals("PREPARING_SELECTION"))throw new IllegalStateException("WORLD_REOPEN_PLAN_CHANGED");artifact(db,world,p.filename(),"WAIT_REOPEN",p.zipHash());job(db,world,operation,"AWAITING_REOPEN","WORLD_REOPEN_REQUIRED");write(db,copy(p,"WAIT_REOPEN","",p.cancelRequest(),p.cancelActor()));db.commit();}catch(Exception failure){db.rollback();throw failure;}finally{db.setAutoCommit(true);}
    }
    public static void failed(Connection db,UUID world,UUID operation,String code)throws Exception{
        db.setAutoCommit(false);try{var p=get(db,world,operation);if(Set.of("APPLIED","CANCELLED").contains(p.state())){db.rollback();return;}write(db,copy(p,"RECOVERY_REQUIRED",safe(code),p.cancelRequest(),p.cancelActor()));job(db,world,operation,"UNKNOWN",safe(code));db.commit();}catch(Exception failure){db.rollback();throw failure;}finally{db.setAutoCommit(true);}
    }
    public static void started(Connection db,UUID world,UUID operation)throws Exception{
        db.setAutoCommit(false);try{var p=get(db,world,operation);if(p.nativeStarted()){db.rollback();return;}if(!p.state().equals("WAIT_REOPEN"))throw new IllegalStateException("WORLD_REOPEN_PLAN_CHANGED");
            write(db,new Plan(p.world(),p.input(),p.savePath(),p.filename(),p.zipHash(),p.blockedSource(),p.beforeEnabled(),p.beforeDisabled(),p.desiredEnabled(),p.desiredDisabled(),p.state(),"WORLD_REOPEN_SERVER_CONSTRUCTED",p.revision()+1,p.cancelRequest(),p.cancelActor(),p.worldgenHash(),true));db.commit();
        }catch(Exception failure){db.rollback();throw failure;}finally{db.setAutoCommit(true);}
    }
    public static Plan cancel(Connection db,UUID world,UUID operation,UUID cancelRequest,String actor)throws Exception{
        db.setAutoCommit(false);try{var p=get(db,world,operation);if(p.state().equals("APPLIED")||p.nativeStarted())throw new IllegalStateException("WORLD_REOPEN_ALREADY_APPLIED");if(Set.of("CANCELLED","CANCEL_REQUESTED").contains(p.state())){db.rollback();return p;}
            var next=copy(p,"CANCEL_REQUESTED","WORLD_REOPEN_CANCEL_PENDING",cancelRequest,actor);artifact(db,world,p.filename(),"REOPEN_CANCELLED",p.zipHash());job(db,world,operation,"CANCEL_REQUESTED","WORLD_REOPEN_CANCEL_PENDING");if(cancelRequest!=null)job(db,world,cancelRequest,"CANCEL_REQUESTED","WORLD_REOPEN_CANCEL_PENDING");write(db,next);db.commit();return next;
        }catch(Exception failure){db.rollback();throw failure;}finally{db.setAutoCommit(true);}
    }
    public static void complete(Connection db,UUID world,UUID operation,boolean cancelled,String code)throws Exception{
        db.setAutoCommit(false);try{var p=get(db,world,operation);String finalState=cancelled?"CANCELLED":"APPLIED";if(p.state().equals(finalState)){db.rollback();return;}if(!p.state().equals(cancelled?"CANCEL_REQUESTED":"WAIT_REOPEN"))throw new IllegalStateException("WORLD_REOPEN_PLAN_CHANGED");
            if(cancelled)artifact(db,world,p.filename(),"DISABLED",p.zipHash());
            else{var files=new ArrayList<String>();try(var q=db.prepareStatement("SELECT filename FROM "+DataPackInstallStore.ARTIFACTS+" WHERE world=? AND package_id=? AND owner=?")){q.setString(1,world.toString());q.setString(2,p.input().pkg().toString());q.setString(3,p.input().owner().toString());try(var r=q.executeQuery()){while(r.next())files.add(r.getString(1));}}for(var file:files)artifact(db,world,file,file.equals(p.filename())?"ENABLED":"DISABLED",null);}
            job(db,world,operation,cancelled?"CANCELLED":code.equals("WORLD_REOPEN_VERIFIED")?"COMPLETED":"LOADED_UNVERIFIED",safe(code));if(p.cancelRequest()!=null)job(db,world,p.cancelRequest(),"COMPLETED","WORLD_REOPEN_PLAN_CANCELLED");write(db,copy(p,finalState,safe(code),p.cancelRequest(),p.cancelActor()));db.commit();
        }catch(Exception failure){db.rollback();throw failure;}finally{db.setAutoCommit(true);}
    }
    private static Plan copy(Plan p,String state,String code,UUID request,String actor){return new Plan(p.world(),p.input(),p.savePath(),p.filename(),p.zipHash(),p.blockedSource(),p.beforeEnabled(),p.beforeDisabled(),p.desiredEnabled(),p.desiredDisabled(),state,code,p.revision()+1,request,actor,p.worldgenHash(),p.nativeStarted());}
    private static String safe(String code){return code!=null&&code.matches("[A-Z0-9_]{1,80}")?code:"WORLD_REOPEN_FAILED";}
    private static void write(Connection db,Plan plan)throws Exception{String raw=JSON.writeValueAsString(plan);int bytes=raw.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;if(bytes>256*1024)throw new IllegalStateException("WORLD_REOPEN_PLAN_BUDGET");try(var q=db.prepareStatement("INSERT INTO "+TABLE+" VALUES(?,?,?,?,?,?,?,?) ON CONFLICT(world,id) DO UPDATE SET payload=excluded.payload,state=excluded.state,bytes=excluded.bytes,updated=excluded.updated")){q.setString(1,plan.world().toString());q.setString(2,plan.input().operation().toString());q.setString(3,plan.filename());q.setString(4,plan.savePath());q.setString(5,raw);q.setString(6,plan.state());q.setInt(7,bytes);q.setLong(8,System.currentTimeMillis());q.executeUpdate();}}
    private static void job(Connection db,UUID world,UUID id,String phase,String code)throws Exception{
        String raw;try(var q=db.prepareStatement("SELECT payload FROM "+DataPackInstallStore.JOBS+" WHERE world=? AND id=?")){q.setString(1,world.toString());q.setString(2,id.toString());try(var r=q.executeQuery()){if(!r.next())throw new IllegalStateException("WORLD_REOPEN_JOB_MISSING");raw=r.getString(1);}}
        var old=JSON.readValue(raw,DataPackInstallStore.Job.class);if(!old.input().operation().equals(id))throw new IllegalStateException("WORLD_REOPEN_RECORD");var next=new DataPackInstallStore.Job(old.input(),phase,code,old.revision()+1);String body=JSON.writeValueAsString(next);
        try(var q=db.prepareStatement("UPDATE "+DataPackInstallStore.JOBS+" SET payload=?,phase=?,bytes=?,updated=? WHERE world=? AND id=? AND payload=?")){q.setString(1,body);q.setString(2,phase);q.setInt(3,body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);q.setLong(4,System.currentTimeMillis());q.setString(5,world.toString());q.setString(6,id.toString());q.setString(7,raw);if(q.executeUpdate()!=1)throw new IllegalStateException("WORLD_REOPEN_JOB_CHANGED");}
    }
    private static void artifact(Connection db,UUID world,String file,String state,String hash)throws Exception{
        String raw;try(var q=db.prepareStatement("SELECT payload FROM "+DataPackInstallStore.ARTIFACTS+" WHERE world=? AND filename=?")){q.setString(1,world.toString());q.setString(2,file);try(var r=q.executeQuery()){if(!r.next())throw new IllegalStateException("WORLD_REOPEN_ARTIFACT_MISSING");raw=r.getString(1);}}
        var a=JSON.readValue(raw,DataPackInstallStore.Artifact.class);if(!a.world().equals(world)||!a.filename().equals(file))throw new IllegalStateException("WORLD_REOPEN_RECORD");var next=new DataPackInstallStore.Artifact(a.world(),a.owner(),a.savePath(),a.manifest(),a.filename(),hash==null?a.zipHash():hash,a.runGeneration(),a.manageGeneration(),state,a.revision()+1);String body=JSON.writeValueAsString(next);
        try(var q=db.prepareStatement("UPDATE "+DataPackInstallStore.ARTIFACTS+" SET payload=?,bytes=? WHERE world=? AND filename=? AND payload=?")){q.setString(1,body);q.setInt(2,body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);q.setString(3,world.toString());q.setString(4,file);q.setString(5,raw);if(q.executeUpdate()!=1)throw new IllegalStateException("WORLD_REOPEN_ARTIFACT_CHANGED");}
    }
    public static Connection open(Path database,boolean readOnly)throws Exception{if(!Files.isRegularFile(database,LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(database))throw new IllegalStateException("WORLD_REOPEN_STORE_MISSING");var db=DriverManager.getConnection("jdbc:sqlite:"+database.toAbsolutePath().toUri()+(readOnly?"?mode=ro":"?mode=rw"));try{try(var s=db.createStatement()){s.execute("PRAGMA busy_timeout=1500");if(readOnly)s.execute("PRAGMA query_only=ON");}return db;}catch(Exception failure){db.close();throw failure;}}
}
