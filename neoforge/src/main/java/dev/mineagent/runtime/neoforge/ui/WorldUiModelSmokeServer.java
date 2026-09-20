package dev.mineagent.runtime.neoforge.ui;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mineagent.runtime.api.packages.*;
import dev.mineagent.runtime.api.permission.PermissionAction;
import dev.mineagent.runtime.neoforge.*;
import dev.mineagent.runtime.neoforge.content.*;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import java.nio.file.*;
import java.util.*;

/** Opt-in real Coder evidence. This supplies requirements/observations, never finished object code. */
@EventBusSubscriber(modid="mineagent_runtime")
public final class WorldUiModelSmokeServer {
    public static final String RUN=UUID.randomUUID().toString();
    public static final String PROMPT="""
        本次从零生成一个青铜与浅金色的双翼风向仪，附独立中文配置网页。不要复用库中棱镜、篮球、路标或固定夹具。
        只创建一个定义、两个真实 Native 物件：低位静态底座（含细支柱），高位不对称双翼转头；两个独立 AABB 在 Y 上留至少0.15格空隙，不能相交。
        底座用组合 boxes，高位转头至少用 indexed triangles 表达左右长短不同的楔形翼，不是 Canvas 或平面图片。整个造型约2.5格高、2格宽，围绕实例原点，保持锚定不下落。
        初始 speed=1（度/tick）、enabled=true、configChanges=0。这三个名字作为持久配置 state keys。转头在 SERVER tick 按实际 getYRot()+speed 旋转，disabled 时真实停止；不要每 tick 写任何持久 state，角度只从 Native 回读。
        点击任一真实物件通过 content.openUi 打开同一实例的 ui 入口。创建、恢复与 tick 不自动弹网页；关闭网页、聊天和 AI 控制面板都不停止转动。
        注册 ui.read，显式只回复 speed(number)、enabled(boolean)、angle(number)、configChanges(number)。ui.action 的 configure 动作验证 payload 的 speed（0.1到6的有限数字）和 enabled（boolean），保存配置，configChanges递增一次，然后回复实际回读。
        网页采用 glass-sage 半透明配色和高对比中文，包含标题、当前角度/配置状态、speed数字输入、enabled checkbox、明确应用按钮、错误消息。分别设置 data-ai-id=speed、enabled、apply，便于验收定位。不要外部依赖/网络、localStorage、自动保存或自动重试动作。
        使用真实 mineagentWorld SDK：显式读取后用当前 revision 提交 configure；保留未提交草稿，清楚显示冲突并提供明确刷新按钮。初始化兼容 SDK 已存在以及 mineagent:world-ready 事件。
        保留注册式 instance.create/instance.restore；恢复只重新绑定同一 partKey 对应的旧物件并保留三个配置值，不重置状态。全部源码和几何必须由本次生成并随独立包发布。
        """.strip();
    public static final RuntimeInstanceLocation LOCATION=new RuntimeInstanceLocation("minecraft:overworld",600.5,80,.5,0,0);
    public static volatile String agentId,operationId,packageId,hash,failure,model;
    public static volatile UUID instanceId,activationId,rotorId,baseId;
    public static volatile boolean allowNative,rotating,paused,resumed,closedGui,independent,done,expectedFailure;
    public static volatile int expectation;
    public static volatile boolean restartVerified;private static int restartAt=-1;private static float restartYaw;
    private static final ObjectMapper JSON=new ObjectMapper();
    private static boolean setup,exported;private static int sampleMode=-1,samples;private static float lastYaw;private static long sampleRevision;
    private static final long STARTED=System.currentTimeMillis();
    public static boolean repairing(){return Boolean.getBoolean("mineagent.worldUiRepairSmoke");}
    public static boolean enabled(){return Boolean.getBoolean("mineagent.worldUiModelSmoke")||repairing();}
    public static boolean negative(){return Boolean.getBoolean("mineagent.worldUiFailureSmoke");}
    public static String directory(){return (repairing()?"world-ui-repair-evidence/":"world-ui-model-evidence/")+RUN;}
    private static void write(Path root,String name,Object data)throws Exception{Files.writeString(root.resolve(name),JSON.writeValueAsString(data));}
    @SubscribeEvent(priority=EventPriority.HIGHEST) public static void tick(ServerTickEvent.Post event)throws Exception{
        if(!enabled()||failure!=null||done)return;var server=event.getServer();
        var viewer=server.getPlayerList().getPlayers().stream().filter(p->!(p instanceof dev.mineagent.runtime.neoforge.body.MineAgentPlayer)).findFirst().orElse(null);if(viewer==null)return;
        Path root=server.getServerDirectory().resolve(directory());Files.createDirectories(root);
        var packages=ServerPackageRuntime.get(server);var world=WorldContentRuntime.get(server);
        try{
            if(!setup){
                var config=MineAgentRuntimeServices.config(server);
                model=config.snapshot().values().get("provider.openai.model");
                if(repairing()&&!Objects.equals(model,System.getProperty("mineagent.worldUiRepairModel","")))throw new IllegalStateException("REPAIR_MODEL_MISMATCH");
                if(!config.apply(new dev.mineagent.runtime.api.config.ConfigPatch(config.snapshot().revision(),Map.of("runtime.initialized","true")),true).accepted())throw new IllegalStateException("MODEL_FIXTURE_CONFIG");
                var grants=new HashSet<>(MineAgentRuntimeServices.permissions(server).trustedActions(viewer.getUUID()));grants.addAll(Set.of(PermissionAction.RUN_CODE,PermissionAction.MANAGE_PACKAGES,PermissionAction.START_TASK));MineAgentRuntimeServices.permissions(server).setTrustedActions(viewer.getUUID(),grants);
                if(repairing()){
                    var original=packages.generation(viewer.getUUID(),UUID.fromString(System.getProperty("mineagent.worldUiRepairGeneration"))).orElseThrow();operationId=original.operationId().toString();agentId=original.agentId().toString();
                }else{
                    for(int x=592;x<=609;x++)for(int z=-7;z<=8;z++){server.overworld().setBlockAndUpdate(new net.minecraft.core.BlockPos(x,79,z),net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());for(int y=80;y<=87;y++)server.overworld().setBlockAndUpdate(new net.minecraft.core.BlockPos(x,y,z),net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());}
                    agentId=MineAgentRuntimeServices.bodies(server).createPersistentAt("World UI Coder",viewer.getUUID(),server.overworld(),new net.minecraft.world.phys.Vec3(604.5,80,3.5)).agentId().toString();
                }
                viewer.setGameMode(net.minecraft.world.level.GameType.CREATIVE);viewer.getAbilities().flying=true;viewer.onUpdateAbilities();viewer.teleportTo(server.overworld(),600.5,81,4.5,Set.of(),180,8,true);
                write(root,"fixture.json",Map.of("run",RUN,"prompt",PROMPT,"location",LOCATION,"agent",agentId,"model",config.snapshot().values().get("provider.openai.model"),"generationOrigin",repairing()?"EXISTING_GENERATION_WITH_TWO_SCOPED_CODER_REPAIRS":negative()?"CONTROLLED_LOCAL_HTTP_NOT_MODEL":"REAL_PROVIDER","uiOperator","EXPLICIT_TEST_DRIVER_NOT_AI","fullV1",false));setup=true;
            }
            if(repairing()){if(WorldUiRepairSmokeServer.failure!=null)throw new IllegalStateException(WorldUiRepairSmokeServer.failure);if(!WorldUiRepairSmokeServer.ready)return;}
            var job=operationId==null?packages.list(viewer.getUUID()).stream().filter(j->j.purpose().equals("WORLD_CONTENT")&&j.prompt().equals(PROMPT)&&j.updatedAtEpochMillis()>=STARTED).findFirst().orElse(null):packages.generation(viewer.getUUID(),UUID.fromString(operationId)).orElse(null);
            if(job==null)return;operationId=job.operationId().toString();
            if(Set.of("FAILED","STALE","INTERRUPTED","CANCELLED").contains(job.state())){
                write(root,"generation-failed.json",job);if(!job.rawOutputSha256().isEmpty())Files.write(root.resolve("failed-model-output.json"),packages.worldContent().read(job.rawOutputSha256()));
                if(negative()&&job.state().equals("FAILED")&&job.errorCode().equals("GENERATION_PROVIDER_HTTP_401")&&world.list(viewer.getUUID()).isEmpty()&&MineAgentRuntimeServices.tasks(server).get(job.taskId()).orElseThrow().status()==dev.mineagent.runtime.api.task.TaskStatus.PAUSED){expectedFailure=true;done=true;return;}
                throw new IllegalStateException("MODEL_GENERATION_"+job.state()+"_"+job.errorCode());
            }
            if(!job.state().equals("PUBLISHED"))return;
            var pack=packages.worldLibrary().get(job.packageId()).orElseThrow();packageId=pack.packageId().toString();hash=pack.canonicalSha256();
            if(!exported){
                write(root,"job.json",job);write(root,"package.json",pack);Files.write(root.resolve("model-output.json"),packages.worldContent().read(job.rawOutputSha256()));
                Path files=root.resolve("package").toAbsolutePath().normalize();
                for(var ref:pack.resources().values()){Path target=files.resolve(ref.path()).normalize();if(!target.startsWith(files))throw new IllegalStateException("MODEL_EVIDENCE_PATH");Files.createDirectories(target.getParent());Files.write(target,packages.worldContent().read(ref.sha256()));}
                exported=true;
            }
            if(pack.origin()!=PackageOrigin.GENERATED||pack.definitions().size()!=1||!pack.entrypoints().containsKey("ui"))throw new IllegalStateException("MODEL_CONTENT_AND_UI_REQUIRED");
            var a=world.list(viewer.getUUID()).stream().filter(v->v.packageId().equals(pack.packageId())&&v.canonicalSha256().equals(hash)).findFirst().orElse(null);
            Path approve=root.resolve("approve-native.flag");allowNative=a==null&&Files.isRegularFile(approve)&&Files.readString(approve).strip().equals(hash);
            if(a==null)return;
            if(!Set.of("ACTIVE","DISABLED").contains(a.state())){write(root,"activation-failed.json",a);throw new IllegalStateException("MODEL_ACTIVATION_"+a.state()+"_"+a.error());}
            instanceId=a.instanceId();activationId=a.operationId();var data=world.instance(instanceId).orElseThrow();
            if(repairing()&&WorldUiRepairSmokeServer.verifyOnly()){
                if(!a.state().equals("DISABLED")||world.active(instanceId))throw new IllegalStateException("REPAIR_RESTART_REPLAYED");
                Path previous=server.getServerDirectory().resolve("world-ui-repair-evidence").resolve(UUID.fromString(System.getProperty("mineagent.worldUiRepairPrevious")).toString());
                var saved=JSON.readTree(previous.resolve("native-mode-4-verified.json").toFile());
                if(!saved.path("instance").path("instanceId").asText().equals(instanceId.toString())||!saved.path("instance").path("state").equals(JSON.valueToTree(data.state())))throw new IllegalStateException("REPAIR_RESTART_STATE_CHANGED");
                var loaded=new ArrayList<RuntimeObjectEntity>();
                for(var entry:data.state().entrySet())if(entry.getKey().startsWith("_object.")){var part=JSON.readValue(entry.getValue(),WorldContentRuntime.ObjectPart.class);var entity=server.overworld().getEntity(part.entity());if(!(entity instanceof RuntimeObjectEntity object))return;world.objectBundle(object);if(object.position().distanceToSqr(data.location().x()+part.dx(),data.location().y()+part.dy(),data.location().z()+part.dz())>1e-10)throw new IllegalStateException("REPAIR_RESTART_POSITION");loaded.add(object);}
                if(loaded.size()!=2)throw new IllegalStateException("REPAIR_RESTART_OBJECT_COUNT");loaded.sort(Comparator.comparingDouble(RuntimeObjectEntity::getY));baseId=loaded.get(0).getUUID();rotorId=loaded.get(1).getUUID();float yaw=loaded.get(1).getYRot();
                if(Math.abs(Math.IEEEremainder(yaw-saved.path("yaw").asDouble(),360))>.001)throw new IllegalStateException("REPAIR_RESTART_ORIENTATION");
                if(restartAt<0){restartAt=server.getTickCount();restartYaw=yaw;}if(yaw!=restartYaw)throw new IllegalStateException("REPAIR_RESTART_STILL_ROTATING");
                if(server.getTickCount()-restartAt>=60){restartVerified=true;done=true;write(root,"restart-verified.json",Map.of("instance",data,"activation",a,"base",baseId,"rotor",rotorId,"yaw",yaw,"stableTicks",60,"providerCalls",0,"creationReplayed",false,"model","deepseek-flash"));}return;
            }
            if(rotorId==null){
                if(world.verifiedObjects(instanceId)!=2)return;var entities=new ArrayList<RuntimeObjectEntity>();
                for(var entry:data.state().entrySet())if(entry.getKey().startsWith("_object.")){var part=JSON.readValue(entry.getValue(),WorldContentRuntime.ObjectPart.class);if(server.overworld().getEntity(part.entity()) instanceof RuntimeObjectEntity entity)entities.add(entity);}
                entities.sort(Comparator.comparingDouble(RuntimeObjectEntity::getY));if(entities.size()!=2||entities.get(0).getBoundingBox().intersects(entities.get(1).getBoundingBox()))throw new IllegalStateException("MODEL_TWO_DISJOINT_OBJECTS_REQUIRED");
                baseId=entities.get(0).getUUID();rotorId=entities.get(1).getUUID();write(root,"native-active.json",Map.of("activation",a,"instance",data,"base",baseId,"rotor",rotorId,"nativeObjects",2));
            }
            var rotor=(RuntimeObjectEntity)server.overworld().getEntity(rotorId);var base=(RuntimeObjectEntity)server.overworld().getEntity(baseId);
            if(rotor==null||base==null)throw new IllegalStateException("MODEL_NATIVE_ENTITY_LOST");
            int changes=Integer.parseInt(data.state().getOrDefault("configChanges","-1"));
            int mode=a.state().equals("DISABLED")?4:closedGui?3:expectation;
            int expectedChanges=mode==0?0:mode==1?1:2;boolean expectedEnabled=mode!=1;double expectedSpeed=mode==0?1:3;
            if(changes>expectedChanges)throw new IllegalStateException("MODEL_CONFIG_DUPLICATED");
            if(changes!=expectedChanges)return;
            if(Double.parseDouble(data.state().getOrDefault("speed","-1"))!=expectedSpeed||!data.state().getOrDefault("enabled","").equals(String.valueOf(expectedEnabled)))throw new IllegalStateException("MODEL_CONFIG_STATE_MISMATCH");
            if(sampleMode!=mode){sampleMode=mode;samples=0;lastYaw=rotor.getYRot();sampleRevision=data.revision();write(root,"native-mode-"+mode+"-before.json",Map.of("instance",data,"yaw",lastYaw,"mode",mode));return;}
            if(data.revision()!=sampleRevision)throw new IllegalStateException("MODEL_TICK_PERSISTENT_WRITES");
            float yaw=rotor.getYRot();float delta=((yaw-lastYaw+540)%360)-180;double target=mode==1||mode==4?0:expectedSpeed;
            if(Math.abs(delta-target)>.002||base.getYRot()!=0)throw new IllegalStateException("MODEL_NATIVE_ROTATION_MISMATCH mode="+mode+" delta="+delta);
            lastYaw=yaw;if(++samples==60){
                write(root,"native-mode-"+mode+"-verified.json",Map.of("instance",data,"yaw",yaw,"samples",samples,"degreesPerTick",target,"activation",a,"stateRevisionStable",true));
                switch(mode){case 0->rotating=true;case 1->paused=true;case 2->resumed=true;case 3->independent=true;case 4->{done=true;write(root,"server-result.json",Map.of("status","GENERATED_NATIVE_WORLD_UI_VERIFIED","package",pack.packageId(),"hash",hash,"instance",data,"generationOperation",operationId,"generationReplayed",false,"fullV1",false));}default->throw new IllegalStateException("MODEL_FIXTURE_PHASE");}
            }
        }catch(Exception e){failure=e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();write(root,"failure.json",Map.of("error",failure,"expectation",expectation));}
    }
}
