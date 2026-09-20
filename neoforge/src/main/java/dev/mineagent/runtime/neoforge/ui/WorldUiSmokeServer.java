package dev.mineagent.runtime.neoforge.ui;
import dev.mineagent.runtime.api.packages.*;
import dev.mineagent.runtime.api.permission.PermissionAction;
import dev.mineagent.runtime.neoforge.*;
import dev.mineagent.runtime.neoforge.content.*;
import dev.mineagent.runtime.worker.generation.*;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** No Provider: two real independent Native instances of the same explicit test package. */
@EventBusSubscriber(modid="mineagent_runtime")
public final class WorldUiSmokeServer {
    public static final String RUN=UUID.randomUUID().toString();
    public static volatile UUID first,second,firstObject,secondObject;public static volatile boolean ready,changed,closedUi,independent,finish,done;public static volatile String failure;
    public static volatile boolean moveAway,away;public static volatile UUID presentationAgent;public static volatile String presentationStatus="";
    public static volatile UUID workspaceAgent,workspaceConversation,workspaceTask;public static volatile long workspacePulse,workspaceTaskRevision;
    public static volatile boolean moveBegin,moveObserved;private static long moveBeforeContinuity=-1,moveAt=-1;
    private static UUID activation;private static long closedAt,initialPulse;private static final com.fasterxml.jackson.databind.ObjectMapper JSON=new com.fasterxml.jackson.databind.ObjectMapper();
    public static String directory(){return "world-ui-evidence/"+RUN;}
    public static boolean enabled(){return Boolean.getBoolean("mineagent.worldUiSmoke")&&!SharedStateSmokeSupport.resuming()&&(!Boolean.getBoolean("mineagent.opacityPersistenceSmoke")||System.getProperty("mineagent.opacityPersistenceStage","prepare").equals("prepare"));}
    public static boolean moving(){return Boolean.getBoolean("mineagent.worldMoveSmoke");}
    private static final String MODEL="{\"version\":1,\"boxes\":[{\"from\":[-0.4,0,-0.3],\"to\":[0.4,1.3,0.3],\"color\":\"#66887a\"},{\"from\":[0.4,0.9,-0.2],\"to\":[0.7,1.2,0.2],\"color\":\"#dabd79\"}],\"collision\":[-0.7,0,-0.3,0.7,1.3,0.3]}";
    private static final String SCRIPT="""
        function projection(e,readOnly){return JSON.stringify({instance:String(instance.instanceId()),viewer:String(e.player().getUUID()),part:String(e.part()),label:String(content.state('label')),count:Number(content.state('count')),pulse:Number(content.state('pulse')),readOnlyGuard:readOnly});}
        on('instance.create',function(){
          content.state('label','initial');content.state('count','0');content.state('pulse','0');content.state('secret','NATIVE_PRIVATE_NOT_PUBLISHED');
          content.createObject('console','models/control.json',0,0,0);
          var denied=false;try{content.openUi(server.getPlayerList().getPlayers().get(0),'ui');}catch(e){denied=String(e).indexOf('WORLD_UI_INTERACTION_REQUIRED')>=0;}content.state('noAutoOpen',String(denied));
        });
        on('instance.restore',function(){content.createObject('console','models/control.json',0,0,0);});
        on('tick',function(t){if(Number(t)%20===0)content.state('pulse',String(Number(content.state('pulse'))+1));});
        on('object.interact',function(e){content.openUi(e.player(),'ui');});
        on('ui.read',function(e){var denied=false;try{content.state('forbidden','write');}catch(error){denied=String(error).indexOf('WORLD_UI_READ_ONLY')>=0;}e.reply(projection(e,denied));});
        on('ui.action',function(e){if(e.action()!=='rename')throw new Error('UNKNOWN_ACTION');var data=JSON.parse(e.payload());if(typeof data.label!=='string'||data.label.length>128)throw new Error('LABEL_INVALID');content.state('label',data.label);content.state('count',String(Number(content.state('count'))+1));content.object('console').setYRot(55);e.reply(projection(e,false));});
        """;
    private static GeneratedFile file(String path,RuntimeResourceSide side,String media,byte[] bytes){return new GeneratedFile(path,side,media,dev.mineagent.runtime.core.objects.RuntimeModelBundle.hash(bytes),bytes);}
    @SubscribeEvent public static void tick(ServerTickEvent.Post event)throws Exception{
        if(!enabled()||failure!=null||done)return;var server=event.getServer();var player=server.getPlayerList().getPlayers().stream().filter(p->!(p instanceof dev.mineagent.runtime.neoforge.body.MineAgentPlayer)).findFirst().orElse(null);if(player==null)return;
        var root=server.getServerDirectory().resolve(directory());Files.createDirectories(root);var world=WorldContentRuntime.get(server);
        try{
            if(first==null){
                var grants=new HashSet<>(MineAgentRuntimeServices.permissions(server).trustedActions(player.getUUID()));grants.addAll(Set.of(PermissionAction.RUN_CODE,PermissionAction.MANAGE_PACKAGES));MineAgentRuntimeServices.permissions(server).setTrustedActions(player.getUUID(),grants);
                var config=MineAgentRuntimeServices.config(server);var settings=new LinkedHashMap<String,String>();settings.put("runtime.initialized","true");if(SharedStateSmokeSupport.enabled())settings.put("permission.player."+player.getUUID(),grants.stream().map(Enum::name).sorted().collect(java.util.stream.Collectors.joining(",")));if(!config.apply(new dev.mineagent.runtime.api.config.ConfigPatch(config.snapshot().revision(),settings),true).accepted())throw new IllegalStateException("WORLD_UI_FIXTURE_CONFIG");
                for(int x=515;x<=531;x++)for(int z=-5;z<=7;z++){server.overworld().setBlockAndUpdate(new net.minecraft.core.BlockPos(x,79,z),net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());for(int y=80;y<=86;y++)server.overworld().setBlockAndUpdate(new net.minecraft.core.BlockPos(x,y,z),net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());}
                player.setGameMode(net.minecraft.world.level.GameType.CREATIVE);player.getAbilities().flying=true;player.onUpdateAbilities();player.teleportTo(server.overworld(),520.5,81,4.5,Set.of(),180,10,true);
                String source=SCRIPT+(moving()?"\nvar moveAnchored=false;var moveContinuity=0;on('instance.create',function(){content.placeBlock('move-fixture',2,0,0,'minecraft:copper_block');});on('tick',function(){moveContinuity++;if(moveContinuity%20===0)content.state('continuity',String(moveContinuity));if(!moveAnchored&&content.objectCount()===1){var o=content.object('console');o.spring(o.getX(),o.getY(),o.getZ(),0.08,0.4);moveAnchored=true;}});on('instance.moved',function(e){content.state('moves',String(Number(content.state('moves'))+1));content.state('originX',String(instance.location().x()));});":"");
                source=SharedStateSmokeSupport.source(source);
                String geometry=moving()?MODEL.substring(0,MODEL.length()-1)+",\"physics\":{\"dynamic\":true,\"mass\":1,\"gravity\":0,\"restitution\":0,\"drag\":1}}":MODEL;
                var script=file("server/main.js",RuntimeResourceSide.SERVER,"application/javascript",source.getBytes(StandardCharsets.UTF_8));var model=file("models/control.json",RuntimeResourceSide.COMMON,"application/json",geometry.getBytes(StandardCharsets.UTF_8));String page=Files.readString(Path.of(System.getProperty("mineagent.worldUiFixture")));if(!System.getProperty("mineagent.livePlacementSmoke","").isEmpty())page=page.replace("<h1>","<p>LAYOUT_ONLY_PRIVATE_BODY_CANARY</p><h1>");if((Boolean.getBoolean("mineagent.viewSettingsSmoke")||Boolean.getBoolean("mineagent.viewSettingsPaintSmoke")))page=page.replace("</style>","</style><style>body{background:#35203c;color:#f5e0fa}input{background:#28162f;color:#f5e0fa}button{background:#6b3b73;color:#fff0ff}</style>");page=SharedStateSmokeSupport.page(page);var html=file("ui/index.html",RuntimeResourceSide.CLIENT,"text/html",page.getBytes(StandardCharsets.UTF_8));
                var entry=new RuntimeEntrypoint(script.path(),script.side(),script.sha256());var def=new RuntimeDefinition(UUID.randomUUID(),"Scoped world UI fixture",RuntimeDefinitionKind.ENTITY,"server",Set.of(model.path()),Map.of(),1);
                var files=new ArrayList<>(List.of(script,model,html));if(Set.of("opacity-half","opacity-zero").contains(System.getProperty("mineagent.livePlacementSmoke","")))files.add(file("ui/view-settings.json",RuntimeResourceSide.CLIENT,"application/json","{\"schema\":1,\"entries\":{\"ui/index.html\":{\"anchor\":\"TOP_RIGHT\",\"width\":560,\"height\":440,\"offsetX\":-24,\"offsetY\":12,\"appearance\":\"PACKAGE\",\"opacity\":0.35}}}".getBytes(StandardCharsets.UTF_8)));if((Boolean.getBoolean("mineagent.viewSettingsSmoke")||Boolean.getBoolean("mineagent.viewSettingsPaintSmoke")))files.add(file("ui/view-settings.json",RuntimeResourceSide.CLIENT,"application/json","{\"schema\":1,\"entries\":{\"ui/index.html\":{\"anchor\":\"TOP_RIGHT\",\"width\":560,\"height\":440,\"offsetX\":-24,\"offsetY\":12,\"appearance\":\"PACKAGE\"}}}".getBytes(StandardCharsets.UTF_8)));
                var pack=ServerPackageRuntime.get(server).importOwned(player,UUID.randomUUID(),new ParsedRuntimePackage("独立物件界面 · 本地夹具","1",RuntimePackageType.CONTENT,ActivationMode.HOT_RUNTIME,Map.of(),SharedStateSmokeSupport.permissions(),Map.of("server",entry,"server.restore",entry,"ui",new RuntimeEntrypoint(html.path(),html.side(),html.sha256())),List.of(def),files));
                var a=world.activate(player,UUID.randomUUID(),pack.packageId(),pack.revision(),def.definitionId(),new RuntimeInstanceLocation("minecraft:overworld",520.5,80,.5,0,0),true,SharedStateSmokeSupport.enabled());if(!a.state().equals("ACTIVE"))throw new IllegalStateException("WORLD_UI_FIRST_ACTIVATION_FAILED");first=a.instanceId();activation=a.operationId();
                var head=ServerPackageRuntime.get(server).worldLibrary().get(pack.packageId()).orElseThrow();var b=world.activate(player,UUID.randomUUID(),pack.packageId(),head.revision(),def.definitionId(),new RuntimeInstanceLocation("minecraft:overworld",524.5,80,.5,0,0),true,SharedStateSmokeSupport.enabled());if(!b.state().equals("ACTIVE"))throw new IllegalStateException("WORLD_UI_SECOND_ACTIVATION_FAILED");second=b.instanceId();
                firstObject=JSON.readValue(world.instance(first).orElseThrow().state().get("_object.console"),WorldContentRuntime.ObjectPart.class).entity();secondObject=JSON.readValue(world.instance(second).orElseThrow().state().get("_object.console"),WorldContentRuntime.ObjectPart.class).entity();
                Files.writeString(root.resolve("fixture.json"),JSON.writeValueAsString(Map.of("package",pack,"first",first,"second",second,"firstObject",firstObject,"secondObject",secondObject,"origin","EXPLICIT_IMPORT","providerCalls",0)));Files.writeString(root.resolve("server.js"),source);Files.write(root.resolve("page.html"),html.content());
            }
            if(world.verifiedObjects(first)==1&&world.verifiedObjects(second)==1)ready=true;
            if(moveAway&&!away){player.teleportTo(server.overworld(),560.5,81,4.5,Set.of(),180,10,true);away=true;Files.writeString(root.resolve("moved-away.json"),JSON.writeValueAsString(Map.of("viewer",player.getUUID(),"x",player.getX(),"y",player.getY(),"z",player.getZ())));}
            var a=world.instance(first).orElseThrow();var b=world.instance(second).orElseThrow();
            if(!System.getProperty("mineagent.livePlacementSmoke","").isEmpty()){
                if(presentationAgent==null)presentationAgent=MineAgentRuntimeServices.bodies(server).createPersistentAt("位置调整 Agent",player.getUUID(),server.overworld(),new net.minecraft.world.phys.Vec3(528,80,4)).agentId();
                for(var job:ServerUiRuntime.get(server).uiAgents().list(player.getUUID()))if(job.get("agentId").equals(presentationAgent.toString())){presentationStatus=job.get("status");if(Set.of("PRESENTATION_VERIFIED","USER_INTERRUPTED","ACTION_UI_OPACITY_BACKEND_REQUIRED").contains(presentationStatus))Files.writeString(root.resolve("live-placement-task.json"),JSON.writeValueAsString(Map.of("job",job,"task",MineAgentRuntimeServices.tasks(server).get(UUID.fromString(job.get("taskId"))).orElseThrow(),"first",a,"second",b)));}
            }
            if(Boolean.getBoolean("mineagent.workspaceSmoke")){
                if(workspaceAgent==null){workspaceAgent=MineAgentRuntimeServices.bodies(server).createPersistentAt("F2 验收角色",player.getUUID(),server.overworld(),new net.minecraft.world.phys.Vec3(528,80,4)).agentId();var messages=ServerConversations.get(server).store();workspaceConversation=messages.create(player.getUUID(),workspaceAgent,UUID.randomUUID(),"F2 历史保持").conversationId();for(int i=0;i<25;i++){var t=messages.begin(player.getUUID(),workspaceAgent,workspaceConversation,UUID.randomUUID(),1,"F2_HISTORY_"+i,0);messages.finish(t.operationId(),"COMPLETE","EXPLICIT_SEEDED_REPLY_"+i,"");}workspaceTask=MineAgentRuntimeServices.tasks(server).create(workspaceAgent,player.getUUID(),"F2-independent task",1,List.of(new dev.mineagent.runtime.core.task.TaskStepSpec("workspace_independent_wait",Set.of()))).taskId();}
                workspacePulse=Long.parseLong(a.state().get("pulse"));workspaceTaskRevision=MineAgentRuntimeServices.tasks(server).get(workspaceTask).orElseThrow().revision();
                if(WorkspaceProof.requested){WorkspaceProof.requested=false;if(world.verifiedObjects(first)!=1||world.verifiedObjects(second)!=1||!world.active(first)||!world.active(second))throw new IllegalStateException("WORKSPACE_NATIVE_OBJECT_LIFECYCLE");Files.writeString(root.resolve("workspace-native.json"),JSON.writeValueAsString(Map.of("first",a,"second",b,"conversation",ServerConversations.get(server).store().get(player.getUUID(),workspaceAgent,workspaceConversation),"task",MineAgentRuntimeServices.tasks(server).get(workspaceTask).orElseThrow(),"providerCalls",0,"historyOrigin","EXPLICIT_TEST_HISTORY_NOT_MODEL")));WorkspaceProof.saved=true;}
            }
            if(moving()&&moveBegin){
                if(moveBeforeContinuity<0){moveBeforeContinuity=Long.parseLong(a.state().getOrDefault("continuity","0"));Files.writeString(root.resolve("move-before.json"),JSON.writeValueAsString(Map.of("first",a,"second",b)));}
                if(a.location().x()!=520.5){
                    var object=(RuntimeObjectEntity)server.overworld().getEntity(firstObject);var other=(RuntimeObjectEntity)server.overworld().getEntity(secondObject);
                    if(a.location().x()!=518.5||a.location().y()!=81||!a.state().getOrDefault("moves","").equals("1")||!a.state().getOrDefault("originX","").equals("518.5")||b.state().containsKey("moves")||b.location().x()!=524.5||object.position().distanceToSqr(518.5,81,.5)>1e-10||other.position().distanceToSqr(524.5,80,.5)>1e-10||object.getYRot()!=55||world.verifiedBlocks(first)!=1||world.verifiedBlocks(second)!=1||!server.overworld().getBlockState(new net.minecraft.core.BlockPos(522,80,0)).isAir()||!server.overworld().getBlockState(new net.minecraft.core.BlockPos(520,81,0)).is(net.minecraft.world.level.block.Blocks.COPPER_BLOCK)||!server.overworld().getBlockState(new net.minecraft.core.BlockPos(526,80,0)).is(net.minecraft.world.level.block.Blocks.COPPER_BLOCK))throw new IllegalStateException("INSTANCE_MOVE_NATIVE_SCOPE_FAILED");
                    if(moveAt<0)moveAt=server.getTickCount();if(!moveObserved&&server.getTickCount()-moveAt>=60){if(Long.parseLong(a.state().get("continuity"))<=moveBeforeContinuity)throw new IllegalStateException("INSTANCE_MOVE_SCRIPT_RESTARTED");moveObserved=true;Files.writeString(root.resolve("move-verified.json"),JSON.writeValueAsString(Map.of("first",a,"second",b,"firstEntity",object.getUUID(),"secondEntity",other.getUUID(),"firstPosition",List.of(object.getX(),object.getY(),object.getZ()),"secondPosition",List.of(other.getX(),other.getY(),other.getZ()),"beforeContinuity",moveBeforeContinuity,"springStableTicks",60,"providerCalls",0)));}
                }
            }
            if(!a.state().get("noAutoOpen").equals("true")||a.state().containsKey("forbidden")||b.state().containsKey("forbidden"))throw new IllegalStateException("WORLD_UI_READ_OR_GESTURE_GUARD_FAILED");
            if(a.state().get("count").equals("1")&&!changed){if(!a.state().get("label").equals("仅修改实例一")||!b.state().get("label").equals("initial")||!b.state().get("count").equals("0")||((RuntimeObjectEntity)server.overworld().getEntity(firstObject)).getYRot()!=55||((RuntimeObjectEntity)server.overworld().getEntity(secondObject)).getYRot()!=0)throw new IllegalStateException("WORLD_UI_INSTANCE_SCOPE_FAILED");changed=true;Files.writeString(root.resolve("native-changed.json"),JSON.writeValueAsString(Map.of("first",a,"second",b,"firstYaw",55,"secondYaw",0)));}
            if(Integer.parseInt(a.state().get("count"))>1)throw new IllegalStateException("WORLD_UI_DUPLICATE_EFFECT");
            if(closedUi&&!independent){if(closedAt==0){closedAt=server.getTickCount();initialPulse=Long.parseLong(a.state().get("pulse"));}if(server.getTickCount()-closedAt>=60){if(Long.parseLong(a.state().get("pulse"))<=initialPulse||!world.active(first))throw new IllegalStateException("WORLD_UI_OWNS_INSTANCE_LIFECYCLE");independent=true;Files.writeString(root.resolve("without-ui.json"),JSON.writeValueAsString(Map.of("before",initialPulse,"first",a,"second",b)));}}
            if(finish){SharedStateSmokeSupport.verify(root,a,b);if(!SharedStateSmokeSupport.enabled())world.disable(player,activation);done=true;Files.writeString(root.resolve("final.json"),JSON.writeValueAsString(Map.of("first",world.instance(first).orElseThrow(),"second",b,"firstActive",world.active(first),"secondActive",world.active(second),"fullV1",false)));}
        }catch(Exception e){failure=e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();Files.writeString(root.resolve("failure.json"),JSON.writeValueAsString(Map.of("error",failure)));}
    }
    public static final class WorkspaceProof {public static volatile boolean requested,saved;private WorkspaceProof(){}}
}
