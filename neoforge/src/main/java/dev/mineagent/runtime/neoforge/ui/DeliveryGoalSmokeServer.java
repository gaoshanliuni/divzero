package dev.mineagent.runtime.neoforge.ui;
import com.fasterxml.jackson.databind.*;
import dev.mineagent.runtime.api.task.*;
import dev.mineagent.runtime.core.task.*;
import dev.mineagent.runtime.neoforge.*;
import net.minecraft.server.MinecraftServer;
import java.nio.file.*;
import java.util.*;

/** Explicit fixed lifecycle verification. Model calls=0; not a generated signup/teaching application. */
public final class DeliveryGoalSmokeServer {
    private static final ObjectMapper JSON=new ObjectMapper();private static int stage,lateStage;private static UUID origin,followup,readback;private static boolean resourceChecked;
    public static boolean enabled(){return Boolean.getBoolean("mineagent.deliveryGoalSmoke");}
    public static boolean originFinished(){return !enabled()||stage==2;}
    private static ManagedTask task(MinecraftServer s,UUID id){return MineAgentRuntimeServices.tasks(s).get(id).orElseThrow();}
    private static void require(boolean value,String code){if(!value)throw new IllegalStateException(code);}
    private static void write(MinecraftServer s,String name,Object value)throws Exception{var root=s.getServerDirectory().resolve("delivery-goal-evidence");Files.createDirectories(root);Files.writeString(root.resolve(name),JSON.writerWithDefaultPrettyPrinter().writeValueAsString(value));}
    private static List<JsonNode> rows(MinecraftServer s,UUID task,UUID main,UUID secondary)throws Exception{var result=new ArrayList<JsonNode>();for(UUID batch:List.of(main,secondary)){var response=ServerUiRuntime.get(s).deliveries().execute(task(s,task),UUID.randomUUID(),"query_deliveries",JSON.writeValueAsString(Map.of("batch_id",batch)));var n=JSON.readTree(response.get("result"));require(!n.path("more").asBoolean(),"GOAL_FIXTURE_PAGINATION");n.path("deliveries").forEach(result::add);}return result;}
    private static Object call(String id,String name,Object args)throws Exception{return Map.of("id",id,"name",name,"arguments",JSON.writeValueAsString(args));}
    private static WorldActionJournal.Batch batch(MinecraftServer s,UUID id){return MineAgentRuntimeServices.taskExecutor(s).worldActions().list(task(s,id).ownerPlayerId()).stream().filter(b->b.taskId().equals(id)).max(Comparator.comparingInt(WorldActionJournal.Batch::round)).orElseThrow();}
    private static Map<String,Object> goal(JsonNode row,String proof){return Map.of("kind","content_delivery","delivery_id",row.path("deliveryId").asText(),"expected_revision",row.path("revision").asLong(),"data_revision",row.path("dataRevision").asLong(),"data_sha256",row.path("dataSha256").asText(),"status",row.path("status").asText(),"evidence",proof);}
    private static String checks(List<Map<String,Object>> rows)throws Exception{return JSON.writeValueAsString(Map.of("checks",rows));}
    private static void denied(MinecraftServer s,UUID task,List<Map<String,Object>> checks,String code)throws Exception{String actual="";try{MineAgentRuntimeServices.taskExecutor(s).worldActions().finishTask(task(s,task),checks(checks));}catch(IllegalStateException expected){actual=expected.getMessage();}require(actual.equals(code)&&task(s,task).status()==TaskStatus.RUNNING,"DELIVERY_GOAL_DENIAL_"+code+"_ACTUAL_"+actual);}
    public static UUID tickOrigin(MinecraftServer s,UUID current,UUID agent,UUID main,UUID secondary,UUID a,boolean draftsReady)throws Exception{
        if(!enabled()||stage==2)return stage==2?followup:current;if(origin==null)origin=current;var runtime=MineAgentRuntimeServices.taskExecutor(s).worldActions();
        var rows=rows(s,origin,main,secondary);var primary=rows.stream().filter(r->r.path("batchId").asText().equals(main.toString())).toList();
        if(stage==0){if(!draftsReady||primary.stream().anyMatch(r->!r.path("dataPaintMatchesLatest").asBoolean())||rows.stream().noneMatch(r->r.path("batchId").asText().equals(secondary.toString())&&r.path("status").asText().equals("REJECTED")))return current;
            var recipient=primary.stream().filter(r->r.path("recipient").asText().equals(a.toString())).findFirst().orElseThrow();
            runtime.submit(task(s,origin),List.of(call("goal-update","update_view",Map.of("delivery_id",recipient.path("deliveryId").asText(),"expected_data_revision",1,"data",Map.of("text","ONLY_A_UPDATED"))),call("goal-main","query_deliveries",Map.of("batch_id",main)),call("goal-secondary","query_deliveries",Map.of("batch_id",secondary))));stage=1;return current;
        }
        var batch=batch(s,origin);if(!batch.state().equals("COMPLETED")){require(Set.of("READY","EXECUTING","VERIFIED").contains(batch.state()),"DELIVERY_GOAL_BATCH_"+batch.state()+"_"+batch.error());return current;}
        if(primary.stream().anyMatch(r->!r.path("dataPaintMatchesLatest").asBoolean()))return current;
        var goals=new ArrayList<Map<String,Object>>();for(var row:rows)goals.add(goal(row,row.path("batchId").asText().equals(main.toString())?"DATA_PAINT":"RECORD"));
        var other=primary.stream().filter(r->!r.path("recipient").asText().equals(a.toString())).findFirst().orElseThrow();var missing=goals.stream().filter(g->!g.get("delivery_id").equals(other.path("deliveryId").asText())).toList();denied(s,origin,missing,"DELIVERY_RECIPIENT_COVERAGE");
        String aid=primary.stream().filter(r->r.path("recipient").asText().equals(a.toString())).findFirst().orElseThrow().path("deliveryId").asText();
        var weak=new ArrayList<Map<String,Object>>();for(var g:goals){var copy=new LinkedHashMap<>(g);if(g.get("delivery_id").equals(aid))copy.put("evidence","ASSET_PAINT");weak.add(copy);}denied(s,origin,weak,"DELIVERY_UPDATED_PAINT_REQUIRED");
        var wrong=new ArrayList<Map<String,Object>>();for(var g:goals){var copy=new LinkedHashMap<>(g);if(g.get("delivery_id").equals(aid))copy.put("data_sha256","0".repeat(64));wrong.add(copy);}denied(s,origin,wrong,"DELIVERY_UPDATED_DATA_COVERAGE");
        runtime.finishTask(task(s,origin),checks(goals));require(task(s,origin).status()==TaskStatus.COMPLETED,"DELIVERY_ORIGIN_NOT_COMPLETED");
        followup=MineAgentRuntimeServices.tasks(s).create(agent,task(s,origin).ownerPlayerId(),"Explicit later delivery lifecycle work",1,List.of(new TaskStepSpec("execute",Set.of()))).taskId();stage=2;
        write(s,"origin-finished.json",Map.of("origin",task(s,origin),"followup",task(s,followup),"rows",rows,"goals",goals,"worldActions",batch,"denials",List.of("MISSING_RECIPIENT","ASSET_NOT_UPDATED_DATA","WRONG_DATA_HASH"),"newModelCalls",0));return followup;
    }
    public static boolean finishFollowup(MinecraftServer s,UUID task,UUID main,UUID secondary,UUID a,UUID b)throws Exception{
        if(!enabled())return true;var runtime=MineAgentRuntimeServices.taskExecutor(s).worldActions();var rows=rows(s,task,main,secondary);var primary=rows.stream().filter(r->r.path("batchId").asText().equals(main.toString())).toList();
        if(lateStage==0){var da=primary.stream().filter(r->r.path("recipient").asText().equals(a.toString())).findFirst().orElseThrow();var db=primary.stream().filter(r->r.path("recipient").asText().equals(b.toString())).findFirst().orElseThrow();
            runtime.submit(task(s,task),List.of(call("goal-close","close_view",Map.of("delivery_id",da.path("deliveryId").asText())),call("goal-revoke","revoke_content",Map.of("delivery_id",db.path("deliveryId").asText())),call("goal-close-query","query_deliveries",Map.of("batch_id",main))));lateStage=1;return false;
        }
        var batch=batch(s,task);if(!batch.state().equals("COMPLETED")){require(Set.of("READY","EXECUTING","VERIFIED").contains(batch.state()),"DELIVERY_CLOSE_GOAL_BATCH_"+batch.state()+"_"+batch.error());return false;}
        var goals=new ArrayList<Map<String,Object>>();for(var row:rows)goals.add(goal(row,row.path("batchId").asText().equals(main.toString())?"CLOSE_ACK":"RECORD"));runtime.finishTask(task(s,task),checks(goals));require(task(s,task).status()==TaskStatus.COMPLETED,"DELIVERY_FOLLOWUP_NOT_COMPLETED");lateStage=2;
        write(s,"followup-finished.json",Map.of("task",task(s,task),"rows",rows,"goals",goals,"worldActions",batch,"originStillCompleted",task(s,origin).status()==TaskStatus.COMPLETED,"newModelCalls",0));return true;
    }
    public static UUID resourceProbe(MinecraftServer s,UUID main,UUID owner,UUID agent)throws Exception{
        if(!enabled()||resourceChecked)return readback;var runtime=ServerUiRuntime.get(s).deliveries();var task=MineAgentRuntimeServices.tasks(s).create(agent,owner,"Delivery resource revalidation probe",1,List.of(new TaskStepSpec("execute",Set.of())));readback=task.taskId();var after=runtime.execute(task,UUID.randomUUID(),"query_deliveries",JSON.writeValueAsString(Map.of("batch_id",main)));require(after.equals(runtime.forModel(task,"query_deliveries",after)),"DELIVERY_VALID_HISTORY_REDACTED");var permit=runtime.workerPermit(task,"query_deliveries",after);require(permit.getAsBoolean(),"DELIVERY_INITIAL_WORKER_PERMIT");
        var row=JSON.readTree(after.get("result")).path("deliveries").get(0);var library=ServerPackageRuntime.get(s).worldLibrary();var p=library.get(UUID.fromString(row.path("packageId").asText())).orElseThrow();require(library.setEnabled(p.packageId(),p.revision(),false).accepted(),"DELIVERY_RESOURCE_DISABLE");
        var projected=runtime.forModel(task,"query_deliveries",after);require(projected.get("executionMode").equals("DELIVERY_OBSERVATION_REDACTED")&&!permit.getAsBoolean(),"DELIVERY_STALE_RESOURCE_BORROWED");resourceChecked=true;
        write(s,"resource-probe.json",Map.of("oldPackageRevision",p.revision(),"currentPackageRevision",library.get(p.packageId()).orElseThrow().revision(),"packageId",p.packageId(),"workerAllowedBefore",true,"workerAllowedAfter",false,"projectionAfter",projected,"newModelCalls",0));return readback;
    }
    public static void closeProbe(MinecraftServer s)throws Exception{if(enabled()&&readback!=null){var t=task(s,readback);if(t.status()==TaskStatus.RUNNING)MineAgentRuntimeServices.tasks(s).transition(t.taskId(),t.revision(),true,TaskStatus.CANCELLED);}}
}
