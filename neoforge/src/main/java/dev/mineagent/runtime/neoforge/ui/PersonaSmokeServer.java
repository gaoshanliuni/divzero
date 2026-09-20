package dev.mineagent.runtime.neoforge.ui;
import dev.mineagent.runtime.neoforge.*;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import java.nio.file.*;
import java.util.*;
/** Explicit Native fixture; no model configuration or generated response. */
@EventBusSubscriber(modid="mineagent_runtime")
public final class PersonaSmokeServer {
    public static final String A_TEXT="  身份：严谨的建筑师\n背景：山间工坊\n语气：温和、准确\n与玩家的关系：搭档。  ",B_TEXT="身份：喜欢冒险的吟游诗人\n用富有想象力的中文交流。",A_FINAL="严谨建筑师 · 已保存最终人设",DRAFT="重启后仍未提交的人设草稿";
    public static volatile UUID a,b,foreign,owner,task,replayOperation;public static volatile boolean ready,grant,granted,revoke,revoked,finish,done;public static volatile String failure;
    public static volatile boolean testOperator,operatorReady,restoreOperator,operatorRestored;
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON=new com.fasterxml.jackson.databind.ObjectMapper();private static boolean initialized;
    public static boolean enabled(){return Boolean.getBoolean("mineagent.personaSmoke");}public static String stage(){return System.getProperty("mineagent.personaStage","prepare");}
    @SubscribeEvent public static void tick(ServerTickEvent.Post event)throws Exception{
        if(!enabled()||done||failure!=null)return;var server=event.getServer();var viewer=server.getPlayerList().getPlayers().stream().filter(p->!(p instanceof dev.mineagent.runtime.neoforge.body.MineAgentPlayer)).findFirst().orElse(null);if(viewer==null)return;
        Path root=Files.createDirectories(server.getServerDirectory().resolve("persona-evidence"));var bodies=MineAgentRuntimeServices.bodies(server);var service=MineAgentRuntimeServices.personas(server);
        try{
            if(!initialized){initialized=true;var config=MineAgentRuntimeServices.config(server);config.apply(new dev.mineagent.runtime.api.config.ConfigPatch(config.snapshot().revision(),Map.of("runtime.initialized","true","voice.output.enabled","false")),true);
                if(viewer.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER))throw new IllegalStateException("PERSONA_FIXTURE_EXPECTS_NON_OP");
                if(stage().equals("prepare")){
                    a=bodies.createPersistentAt("人设建筑师",viewer.getUUID(),server.overworld(),viewer.position().add(2,0,0)).agentId();b=bodies.createPersistentAt("人设吟游诗人",viewer.getUUID(),server.overworld(),viewer.position().add(4,0,0)).agentId();owner=UUID.randomUUID();foreign=bodies.createPersistentAt("他人的AI",owner,server.overworld(),viewer.position().add(6,0,0)).agentId();
                    var def=bodies.definitions().stream().filter(x->x.agentId().equals(foreign)).findFirst().orElseThrow();service.save(def,owner,false,UUID.randomUUID(),0,"FOREIGN_PERSONA_CANARY");
                    task=MineAgentRuntimeServices.tasks(server).create(a,viewer.getUUID(),"Independent task unchanged by persona",1,List.of(new dev.mineagent.runtime.core.task.TaskStepSpec("persona_independent_wait",Set.of()))).taskId();
                }else{
                    var old=JSON.readTree(root.resolve("journal.json").toFile());a=UUID.fromString(old.path("a").asText());b=UUID.fromString(old.path("b").asText());foreign=UUID.fromString(old.path("foreign").asText());owner=UUID.fromString(old.path("owner").asText());task=UUID.fromString(old.path("task").asText());replayOperation=UUID.fromString(old.path("replayOperation").asText());
                    if(!service.read(def(server,a),viewer.getUUID(),false).text().equals(A_FINAL)||!service.read(def(server,b),viewer.getUUID(),false).text().equals(B_TEXT))throw new IllegalStateException("PERSONA_RESTART_LOST");
                }
                ready=true;
            }
            if(grant&&!granted){if(!bodies.setCollaborator(foreign,owner,viewer.getUUID(),true))throw new IllegalStateException("PERSONA_GRANT_FAILED");granted=true;}
            if(revoke&&!revoked){if(!bodies.setCollaborator(foreign,owner,viewer.getUUID(),false))throw new IllegalStateException("PERSONA_REVOKE_FAILED");revoked=true;}
            if(testOperator&&!operatorReady){server.getPlayerList().op(viewer.nameAndId());boolean gm=viewer.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER);if(!gm||!server.getPlayerList().isOp(viewer.nameAndId()))throw new IllegalStateException("PERSONA_NATIVE_OP_NOT_APPLIED");Files.writeString(root.resolve("prepare-operator-native.json"),JSON.writeValueAsString(Map.of("viewer",viewer.getUUID(),"nativeGameMaster",gm,"nativeOpEntry",true,"personaAccess",dev.mineagent.runtime.core.agent.AgentPersonaService.mayEdit(def(server,foreign),viewer.getUUID(),gm))));operatorReady=true;}
            if(restoreOperator&&!operatorRestored){server.getPlayerList().deop(viewer.nameAndId());operatorRestored=true;}
            if(finish){
                if(viewer.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER))throw new IllegalStateException("PERSONA_FIXTURE_OP_NOT_RESTORED");
                var pa=service.read(def(server,a),viewer.getUUID(),false);var pb=service.read(def(server,b),viewer.getUUID(),false);var pc=service.read(def(server,foreign),owner,false);var live=MineAgentRuntimeServices.tasks(server).get(task).orElseThrow();
                if(pa.revision()!=3||!pa.text().equals(A_FINAL)||pb.revision()!=1||!pb.text().equals(B_TEXT)||pc.revision()!=2||!pc.text().equals("协作者已保存")||live.revision()!=1||live.status()!=dev.mineagent.runtime.api.task.TaskStatus.RUNNING)throw new IllegalStateException("PERSONA_NATIVE_STATE_MISMATCH");
                if(stage().equals("prepare")){
                    try(var db=java.sql.DriverManager.getConnection("jdbc:sqlite:"+server.getServerDirectory().resolve("mineagent-runtime-data/runtime.db"));var q=db.prepareStatement("SELECT operation_id FROM mineagent_persona_operations_v1 WHERE world_id=? AND applied_revision=3")){
                        q.setString(1,MineAgentRuntimeServices.worldId(server).toString());try(var rows=q.executeQuery()){if(!rows.next())throw new IllegalStateException("PERSONA_REPLAY_ID_MISSING");replayOperation=UUID.fromString(rows.getString(1));if(rows.next())throw new IllegalStateException("PERSONA_REPLAY_ID_AMBIGUOUS");}
                    }
                    Files.writeString(root.resolve("journal.json"),JSON.writeValueAsString(Map.of("a",a,"b",b,"foreign",foreign,"owner",owner,"task",task,"replayOperation",replayOperation)));
                }
                Files.writeString(root.resolve(stage()+"-native.json"),JSON.writeValueAsString(Map.of("a",pa,"b",pb,"foreign",pc,"task",live,"viewerOp",false,"providerCalls",0,"permissionUnchangedByPersonaSave",true,"personaPrompt",dev.mineagent.runtime.core.agent.PersonaPrompt.section(def(server,a),pa))));done=true;
            }
        }catch(Exception e){failure=e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();Files.writeString(root.resolve(stage()+"-failure.json"),JSON.writeValueAsString(Map.of("error",failure)));}
    }
    private static dev.mineagent.runtime.api.agent.AgentDefinition def(net.minecraft.server.MinecraftServer server,UUID id){return MineAgentRuntimeServices.bodies(server).definitions().stream().filter(x->x.agentId().equals(id)).findFirst().orElseThrow();}
}
