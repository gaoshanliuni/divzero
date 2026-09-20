package dev.mineagent.runtime.neoforge.task;

import com.fasterxml.jackson.databind.*;
import dev.mineagent.runtime.api.directory.ObjectRef;
import dev.mineagent.runtime.api.permission.PermissionAction;
import dev.mineagent.runtime.api.task.*;
import dev.mineagent.runtime.core.directory.*;
import dev.mineagent.runtime.core.task.*;
import dev.mineagent.runtime.neoforge.*;
import dev.mineagent.runtime.neoforge.body.MineAgentPlayer;
import dev.mineagent.runtime.neoforge.ui.WorldUiSmokeServer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import java.nio.file.*;
import java.util.*;

/** Explicit fixed tool requests, no Provider or OS input. Uses real server objects and the production action journal. */
@EventBusSubscriber(modid="mineagent_runtime")
public final class ObjectDirectorySmokeServer {
    public static volatile boolean finished;private static int phase,ticks,waitAt;private static UUID agent,task,foreign,foreignTask,fenceTask,batchId;
    private static String red,blue,previousTeam,entityCursor;private static Set<PermissionAction> previousGrants;
    private static Map<String,String> playerReceipt,freshReceipt;private static ObjectRef playerRef;private static final Set<String> entityIds=new HashSet<>();
    private static net.minecraft.world.entity.decoration.ArmorStand transientEntity;private static ObjectRef transientRef;
    private static boolean flipped,operatorChanged;private static final ObjectMapper JSON=new ObjectMapper();
    private static void require(boolean value,String code){if(!value)throw new IllegalStateException(code);}
    private static Path root(MinecraftServer server)throws Exception{var path=server.getServerDirectory().resolve(WorldUiSmokeServer.directory()).resolve("object-directory");Files.createDirectories(path);return path;}
    private static void save(MinecraftServer server,String name,Object value)throws Exception{Files.writeString(root(server).resolve(name+".json"),JSON.writeValueAsString(value));}
    private static List<Map<String,Object>> calls(String tool,String args){return List.of(Map.of("id","directory-"+UUID.randomUUID(),"name",tool,"arguments",args));}
    private static ManagedTask task(MinecraftServer server,UUID id){return MineAgentRuntimeServices.tasks(server).get(id).orElseThrow();}
    private static UUID create(MinecraftServer server,UUID actor,UUID owner,String name)throws Exception{var tasks=MineAgentRuntimeServices.tasks(server);var t=tasks.create(actor,owner,name,50,List.of(new TaskStepSpec("fixture_plan",Set.of()),new TaskStepSpec("execute",Set.of("fixture_plan"))));return tasks.completeStep(t.taskId(),t.revision(),"fixture_plan").task().taskId();}
    private static void submit(MinecraftServer server,UUID target,String tool,String args)throws Exception{batchId=MineAgentRuntimeServices.taskExecutor(server).worldActions().submit(task(server,target),calls(tool,args)).batchId();}
    private static WorldActionJournal.Batch batch(MinecraftServer server,UUID owner){return MineAgentRuntimeServices.taskExecutor(server).worldActions().list(owner).stream().filter(b->b.batchId().equals(batchId)).findFirst().orElseThrow();}
    private static JsonNode page(WorldActionJournal.Batch b)throws Exception{return JSON.readTree(b.receipts().getLast().after().get("result"));}
    private static String entitiesQuery(String cursor)throws Exception{var q=new LinkedHashMap<String,Object>();q.put("kind","ENTITY");q.put("ids",List.of(WorldUiSmokeServer.firstObject.toString(),WorldUiSmokeServer.secondObject.toString()));q.put("limit",1);if(cursor!=null)q.put("cursor",cursor);return JSON.writeValueAsString(q);}
    private static void grant(MinecraftServer s,UUID owner,boolean value){var p=MineAgentRuntimeServices.permissions(s);var set=new HashSet<>(p.trustedActions(owner));if(value)set.add(PermissionAction.DISCOVER_OBJECTS);else set.remove(PermissionAction.DISCOVER_OBJECTS);p.setTrustedActions(owner,set);}
    @SubscribeEvent public static void tick(ServerTickEvent.Post event)throws Exception{
        if(!Boolean.getBoolean("mineagent.objectDirectorySmoke")||finished||WorldUiSmokeServer.failure!=null||!WorldUiSmokeServer.ready)return;
        var server=event.getServer();var viewer=server.getPlayerList().getPlayers().stream().filter(p->!(p instanceof MineAgentPlayer)).findFirst().orElse(null);if(viewer==null)return;
        var actions=MineAgentRuntimeServices.taskExecutor(server).worldActions();var directory=actions.directory();ticks++;
        try{
            require(ticks<900,"DIRECTORY_NATIVE_TIMEOUT");if(ticks%20==0)save(server,"progress",Map.of("phase",phase,"ticks",ticks));
            if(phase==0){
                previousGrants=MineAgentRuntimeServices.permissions(server).trustedActions(viewer.getUUID());previousTeam=viewer.getTeam()==null?"":viewer.getTeam().getName();
                if(!server.getPlayerList().isOp(viewer.nameAndId())){server.getPlayerList().op(viewer.nameAndId());operatorChanged=true;}
                require(viewer.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER),"DIRECTORY_NATIVE_OP_NOT_APPLIED");
                agent=MineAgentRuntimeServices.bodies(server).createPersistentAt("通用寻址 Actor",viewer.getUUID(),server.overworld(),new net.minecraft.world.phys.Vec3(526,80,1)).agentId();
                task=create(server,agent,viewer.getUUID(),"对象目录显式固定工具回归，不调用模型");grant(server,viewer.getUUID(),false);
                submit(server,task,"query_objects","{\"kind\":\"PLAYER\"}");phase=1;return;
            }
            if(phase==1){
                var b=batch(server,viewer.getUUID());if(!Set.of("FAILED","INTERRUPTED").contains(b.state()))return;require(b.error().equals("DIRECTORY_PERMISSION_REQUIRED"),"DIRECTORY_GRANT_NOT_REQUIRED");
                save(server,"explicit-grant-denied",Map.of("batch",b,"viewerOperator",viewer.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER)));
                if(operatorChanged){server.getPlayerList().deop(viewer.nameAndId());operatorChanged=false;}
                grant(server,viewer.getUUID(),true);task=create(server,agent,viewer.getUUID(),"真实目录及目标失效回归");red="dir_r_"+UUID.randomUUID().toString().substring(0,6);blue="dir_b_"+UUID.randomUUID().toString().substring(0,6);
                server.getScoreboard().addPlayerTeam(red);server.getScoreboard().addPlayerTeam(blue);server.getScoreboard().addPlayerToTeam(viewer.getScoreboardName(),server.getScoreboard().getPlayerTeam(red));
                submit(server,task,"query_objects",JSON.writeValueAsString(Map.of("kind","PLAYER","team",red)));phase=2;return;
            }
            if(phase>=2&&phase<=11||phase==91||phase==92||phase==93){var b=batch(server,viewer.getUUID());if(!b.state().equals("COMPLETED")){require(!Set.of("FAILED","INTERRUPTED").contains(b.state()),"DIRECTORY_ACTION_FAILED_"+phase+"_"+b.error());return;}save(server,"phase-"+phase,b);var p=page(b);
                if(phase==2){require(p.path("items").size()==1,"DIRECTORY_PLAYER_COUNT");playerRef=JSON.treeToValue(p.path("items").get(0).path("ref"),ObjectRef.class);require(playerRef.id().equals(viewer.getUUID().toString()),"DIRECTORY_FAKE_BODY_AS_RECIPIENT");playerReceipt=b.receipts().getLast().after();submit(server,task,"query_objects",JSON.writeValueAsString(Map.of("kind","TEAM","ids",List.of(red,blue))));phase=3;return;}
                if(phase==3){require(p.path("items").size()==2,"DIRECTORY_NATIVE_TEAMS");server.getScoreboard().addPlayerToTeam(viewer.getScoreboardName(),server.getScoreboard().getPlayerTeam(blue));submit(server,task,"revalidate_objects",new ObjectRevalidation(ObjectQuery.parse(JSON.writeValueAsString(Map.of("kind","PLAYER","team",red))),List.of(playerRef)).canonical());phase=4;return;}
                if(phase==4){require(p.path("results").get(0).path("status").asText().equals("OBJECT_CHANGED"),"DIRECTORY_TEAM_CHANGE_ACCEPTED");require(directory.forModel(task(server,task),"query_objects",playerReceipt).get("executionMode").equals("DIRECTORY_OBSERVATION_REDACTED"),"DIRECTORY_STALE_MODEL_CONTEXT");submit(server,task,"query_objects","{\"kind\":\"DIMENSION\"}");phase=5;return;}
                if(phase==5){require(p.path("items").size()>=3,"DIRECTORY_DIMENSIONS_MISSING");submit(server,task,"query_objects",entitiesQuery(null));phase=6;return;}
                if(phase==6){require(p.path("items").size()==1&&!p.path("nextCursor").asText().isEmpty(),"DIRECTORY_ENTITY_PAGE_MISSING");entityIds.add(p.path("items").get(0).path("ref").path("id").asText());entityCursor=p.path("nextCursor").asText();submit(server,task,"query_objects",entitiesQuery(entityCursor));phase=7;return;}
                if(phase==7){entityIds.add(p.path("items").get(0).path("ref").path("id").asText());require(p.path("nextCursor").asText().isEmpty()&&entityIds.equals(Set.of(WorldUiSmokeServer.firstObject.toString(),WorldUiSmokeServer.secondObject.toString())),"DIRECTORY_PAGE_IDENTITY");submit(server,task,"query_objects","{\"kind\":\"INSTANCE\"}");phase=8;return;}
                if(phase==8){require(p.path("items").size()==2,"DIRECTORY_INSTANCE_COUNT");submit(server,task,"query_objects","{\"kind\":\"ENTITY\",\"near\":{\"reference\":\"ACTOR\",\"radius\":3}}");phase=9;return;}
                if(phase==9){var ids=new HashSet<String>();p.path("items").forEach(e->ids.add(e.path("ref").path("id").asText()));require(ids.contains(WorldUiSmokeServer.secondObject.toString())&&!ids.contains(WorldUiSmokeServer.firstObject.toString()),"DIRECTORY_VIEWER_DISTANCE_USED");submit(server,task,"query_objects",JSON.writeValueAsString(Map.of("kind","ENTITY","ids",List.of(WorldUiSmokeServer.firstObject,WorldUiSmokeServer.secondObject),"dimension","minecraft:overworld","region",Map.of("minX",520,"minY",79,"minZ",0,"maxX",521,"maxY",82,"maxZ",1))));phase=91;return;}
                if(phase==91){require(p.path("items").size()==1&&p.path("items").get(0).path("ref").path("id").asText().equals(WorldUiSmokeServer.firstObject.toString()),"DIRECTORY_REGION_FILTER");submit(server,task,"query_objects",JSON.writeValueAsString(Map.of("kind","ENTITY","ids",List.of(WorldUiSmokeServer.firstObject,WorldUiSmokeServer.secondObject),"near",Map.of("reference","POSITION","radius",2,"position",Map.of("dimension","minecraft:overworld","x",520.5,"y",80,"z",0.5)))));phase=92;return;}
                if(phase==92){require(p.path("items").size()==1&&p.path("items").get(0).path("ref").path("id").asText().equals(WorldUiSmokeServer.firstObject.toString()),"DIRECTORY_EXPLICIT_POSITION_FILTER");transientEntity=new net.minecraft.world.entity.decoration.ArmorStand(server.overworld(),528,80,2);require(server.overworld().addFreshEntity(transientEntity),"DIRECTORY_ENTITY_FIXTURE_SPAWN");submit(server,task,"query_objects",JSON.writeValueAsString(Map.of("kind","ENTITY","ids",List.of(transientEntity.getUUID()))));phase=93;return;}
                if(phase==93){require(p.path("items").size()==1,"DIRECTORY_TRANSIENT_ENTITY_MISSING");transientRef=JSON.treeToValue(p.path("items").get(0).path("ref"),ObjectRef.class);transientEntity.discard();int before=server.overworld().getChunkSource().getLoadedChunksCount();
                    var receipt=directory.revalidate(task(server,task),UUID.randomUUID(),new ObjectRevalidation(ObjectQuery.parse("{\"kind\":\"ENTITY\"}"),List.of(transientRef)).canonical());int after=server.overworld().getChunkSource().getLoadedChunksCount();require(JSON.readTree(receipt.get("result")).path("results").get(0).path("status").asText().equals("UNAVAILABLE_IN_AUTHORIZED_LOADED_DIRECTORY")&&before==after,"DIRECTORY_REMOVED_ENTITY_REVALIDATED_OR_LOADED_CHUNKS");save(server,"removed-entity",Map.of("receipt",receipt,"chunksBefore",before,"chunksAfter",after));phase=94;waitAt=ticks;return;}
                if(phase==10){freshReceipt=b.receipts().getLast().after();require(directory.forModel(task(server,task),"query_objects",freshReceipt).equals(freshReceipt),"DIRECTORY_FRESH_MODEL_CONTEXT_MISSING");long before=directory.epoch(viewer.getUUID());grant(server,viewer.getUUID(),false);grant(server,viewer.getUUID(),true);require(directory.epoch(viewer.getUUID())>before,"DIRECTORY_EPOCH_NOT_ADVANCED");require(directory.forModel(task(server,task),"query_objects",freshReceipt).get("executionMode").equals("DIRECTORY_OBSERVATION_REDACTED"),"DIRECTORY_REGRANT_REPLAY");
                    boolean denied=false;try{directory.query(task(server,task),UUID.randomUUID(),entitiesQuery(entityCursor));}catch(IllegalArgumentException expected){denied=expected.getMessage().equals("DIRECTORY_CURSOR_SCOPE");}require(denied,"DIRECTORY_OLD_CURSOR_REVIVED");save(server,"regrant",Map.of("oldEpoch",before,"newEpoch",directory.epoch(viewer.getUUID()),"oldCursorRejected",true,"oldModelContextRedacted",true));submit(server,task,"query_objects",JSON.writeValueAsString(Map.of("kind","PLAYER","team",blue)));phase=11;return;}
                if(phase==11){var operation=b.receipts().getLast().operationId();actions.finishTask(task(server,task),JSON.writeValueAsString(Map.of("checks",List.of(Map.of("kind","directory_observation","operation_id",operation,"minimum_count",1)))));require(task(server,task).status()==TaskStatus.COMPLETED,"DIRECTORY_READ_TASK_NOT_COMPLETED");save(server,"completed-read-task",task(server,task));
                    var foreignOwner=UUID.randomUUID();grant(server,foreignOwner,true);foreign=MineAgentRuntimeServices.bodies(server).createPersistentAt("外域查询夹具 Actor（非第二客户端）",foreignOwner,server.overworld(),new net.minecraft.world.phys.Vec3(529,80,4)).agentId();foreignTask=create(server,foreign,foreignOwner,"其它owner不可发现私有实例");submit(server,foreignTask,"query_objects","{\"kind\":\"INSTANCE\"}");phase=12;return;}
            }
            if(phase==94&&ticks>waitAt){transientEntity=new net.minecraft.world.entity.decoration.ArmorStand(server.overworld(),528,80,2);transientEntity.setUUID(UUID.fromString(transientRef.id()));require(server.overworld().addFreshEntity(transientEntity),"DIRECTORY_REPLACEMENT_ENTITY_SPAWN");
                var receipt=directory.revalidate(task(server,task),UUID.randomUUID(),new ObjectRevalidation(ObjectQuery.parse("{\"kind\":\"ENTITY\"}"),List.of(transientRef)).canonical());require(JSON.readTree(receipt.get("result")).path("results").get(0).path("status").asText().equals("STALE_GENERATION"),"DIRECTORY_SAME_UUID_NEW_ENTITY_ACCEPTED");save(server,"replacement-generation",receipt);transientEntity.discard();transientEntity=null;submit(server,task,"query_objects",JSON.writeValueAsString(Map.of("kind","PLAYER","team",blue)));phase=10;return;
            }
            if(phase==12||phase==13){var t=task(server,foreignTask);var b=batch(server,t.ownerPlayerId());if(!b.state().equals("COMPLETED")){require(!Set.of("FAILED","INTERRUPTED").contains(b.state()),"DIRECTORY_FOREIGN_READ_FAILED");return;}save(server,"foreign-"+phase,b);require(page(b).path("items").isEmpty(),"DIRECTORY_PRIVATE_OBJECT_LEAK");if(phase==12){submit(server,foreignTask,"query_objects",entitiesQuery(null));phase=13;return;}
                fenceTask=create(server,agent,viewer.getUUID(),"撤权重授不可复活排队目录动作");var two=new ArrayList<>(calls("query_objects","{\"kind\":\"PLAYER\"}"));two.addAll(calls("query_objects","{\"kind\":\"TEAM\"}"));batchId=actions.submit(task(server,fenceTask),two).batchId();phase=14;return;
            }
            if(phase==14){var b=batch(server,viewer.getUUID());if(!flipped&&b.receipts().size()==1&&b.state().equals("READY")){grant(server,viewer.getUUID(),false);grant(server,viewer.getUUID(),true);flipped=true;waitAt=ticks;}
                if(flipped&&ticks-waitAt>=15){require(b.receipts().size()==1&&b.state().equals("INTERRUPTED"),"DIRECTORY_QUEUED_WORK_REVIVED");save(server,"queued-regrant-fence",Map.of("batch",b,"task",task(server,fenceTask)));cleanup(server,viewer);save(server,"result",Map.of("status","NATIVE_DIRECTORY_AND_REFERENCE_FENCES_VERIFIED","systemInputInjected",false,"providerCalls",0,"secondRealClient",false,"modelSelectedTools",false,"fullV1",false));finished=true;MineAgentRuntimeMod.LOGGER.info("MINEAGENT_OBJECT_DIRECTORY_OK");}
            }
        }catch(Exception failure){save(server,"failure",Map.of("phase",phase,"error",failure.toString()));cleanup(server,viewer);WorldUiSmokeServer.failure="OBJECT_DIRECTORY_FAILED_"+phase;}
    }
    private static void cleanup(MinecraftServer server,ServerPlayer viewer){
        if(transientEntity!=null){transientEntity.discard();transientEntity=null;}
        if(operatorChanged){server.getPlayerList().deop(viewer.nameAndId());operatorChanged=false;}
        if(previousGrants!=null)MineAgentRuntimeServices.permissions(server).setTrustedActions(viewer.getUUID(),previousGrants);
        if(previousTeam!=null){server.getScoreboard().removePlayerFromTeam(viewer.getScoreboardName());if(!previousTeam.isEmpty()&&server.getScoreboard().getPlayerTeam(previousTeam)!=null)server.getScoreboard().addPlayerToTeam(viewer.getScoreboardName(),server.getScoreboard().getPlayerTeam(previousTeam));}
        for(String name:List.of(red==null?"":red,blue==null?"":blue)){var team=server.getScoreboard().getPlayerTeam(name);if(team!=null)server.getScoreboard().removePlayerTeam(team);}
    }
}
