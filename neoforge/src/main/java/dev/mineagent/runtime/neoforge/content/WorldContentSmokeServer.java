package dev.mineagent.runtime.neoforge.content;
import dev.mineagent.runtime.neoforge.*;
import dev.mineagent.runtime.neoforge.ui.ServerPackageRuntime;
import dev.mineagent.runtime.api.packages.RuntimeInstanceLocation;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import java.util.*;
import java.nio.file.*;
/** Opt-in real-provider/world verification driver, never a production object definition. */
@EventBusSubscriber(modid="mineagent_runtime")
public final class WorldContentSmokeServer {
    public static final String RUN=UUID.randomUUID().toString();
    public static final String PROMPT="生成一个不对称的铜石路标兼小遮雨架：左侧三格高的铜柱，右侧两格高的深板岩柱，顶端用黄色玻璃连成折角，保留中间可穿过的空隙。使用 minecraft:copper_block、minecraft:deepslate 和 minecraft:yellow_stained_glass 共 8 到 16 个实际方块，不生成网页。用 content.placeBlock 放置，坐标是实例原点的整数偏移；启用后每 20 ticks 将 content.state('pulse') 递增 1 并持久保存，不广播聊天。必须是这次按需生成的独立定义与 JS，不调用旧演示物件。";
    public static volatile String agentId,packageId,operationId,failure;public static volatile long packageRevision;public static volatile boolean verified,disabledVerified,interfaceClosed,afterUiCloseVerified;public static volatile RuntimeInstanceLocation location;
    private static long startedAfter=System.currentTimeMillis(),closedAt;private static long closedPulse;
    public static volatile boolean restartVerified;
    private static boolean setup,published;private static long activeAt;private static UUID activationId,instanceId;private static String stoppedPulse;
    private static WorldContentRuntime.InstanceHost heldHost;
    public static String directory(){return "world-content-evidence/"+RUN;}
    @SubscribeEvent public static void tick(ServerTickEvent.Post e)throws Exception{
        if(!Boolean.getBoolean("mineagent.worldContentSmoke"))return;var server=e.getServer();var viewer=server.getPlayerList().getPlayers().stream().filter(p->!(p instanceof dev.mineagent.runtime.neoforge.body.MineAgentPlayer)).findFirst().orElse(null);if(viewer==null)return;
        var root=server.getServerDirectory().resolve(directory()).toAbsolutePath().normalize();Files.createDirectories(root);var json=new com.fasterxml.jackson.databind.ObjectMapper();var packages=ServerPackageRuntime.get(server);
        if(!System.getProperty("mineagent.worldContentVerifyRestart","").isBlank()){
            var runtime=WorldContentRuntime.get(server);var id=UUID.fromString(System.getProperty("mineagent.worldContentVerifyRestart"));
            var a=runtime.list(viewer.getUUID()).stream().filter(v->v.operationId().equals(id)).findFirst().orElseThrow();
            if(!a.state().equals("DISABLED"))throw new IllegalStateException("WORLD_RESTART_NOT_DISABLED");var instance=runtime.instance(a.instanceId()).orElseThrow();
            if(!setup){setup=true;activeAt=server.getTickCount();stoppedPulse=instance.state().get("pulse");viewer.setGameMode(net.minecraft.world.level.GameType.CREATIVE);viewer.getAbilities().flying=true;viewer.onUpdateAbilities();viewer.teleportTo(server.overworld(),a.location().x()+7,a.location().y()+3,a.location().z()+10,Set.of(),145,15,true);}
            if(server.getTickCount()-activeAt>=60&&!restartVerified){
                if(!Objects.equals(stoppedPulse,instance.state().get("pulse"))||Long.parseLong(stoppedPulse)<3)throw new IllegalStateException("WORLD_RESTART_STATE_CHANGED");
                int count=0;for(var b:instance.state().entrySet())if(b.getKey().startsWith("_block.")){var v=json.readTree(b.getValue());var pos=new net.minecraft.core.BlockPos(v.path("x").asInt(),v.path("y").asInt(),v.path("z").asInt());if(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(server.overworld().getBlockState(pos).getBlock()).toString().equals(v.path("block").asText()))count++;}
                if(count<8||count>16)throw new IllegalStateException("WORLD_RESTART_BLOCK_LOSS");Files.writeString(root.resolve("restart-verified.json"),json.writeValueAsString(Map.of("activation",a,"instance",instance,"nativeBlocks",count,"pulse",stoppedPulse,"automaticReplay",false)));restartVerified=true;
            }return;
        }
        if(!setup){
            String resume=System.getProperty("mineagent.worldContentResume","");
            var agent=MineAgentRuntimeServices.bodies(server).definitions().stream().filter(a->a.ownerPlayerId().equals(viewer.getUUID())).findFirst().orElseGet(()->MineAgentRuntimeServices.bodies(server).create("World Content Fixture",viewer,dev.mineagent.runtime.api.agent.AgentMode.CREATOR));agentId=agent.agentId().toString();
            var actions=new HashSet<>(MineAgentRuntimeServices.permissions(server).trustedActions(viewer.getUUID()));actions.addAll(Set.of(dev.mineagent.runtime.api.permission.PermissionAction.RUN_CODE,dev.mineagent.runtime.api.permission.PermissionAction.MANAGE_PACKAGES,dev.mineagent.runtime.api.permission.PermissionAction.START_TASK));MineAgentRuntimeServices.permissions(server).setTrustedActions(viewer.getUUID(),actions);
            int x=64;var oldActivations=WorldContentRuntime.get(server).list(viewer.getUUID());while(oldActivations.stream().map(a->(int)a.location().x()).toList().contains(x))x+=12;location=new RuntimeInstanceLocation("minecraft:overworld",x,190,0,0,0);
            viewer.setGameMode(net.minecraft.world.level.GameType.CREATIVE);viewer.getAbilities().flying=true;viewer.onUpdateAbilities();viewer.teleportTo(server.overworld(),x+7,193,10,Set.of(),145,15,true);
            if(!resume.isBlank()){operationId=UUID.fromString(resume).toString();var job=packages.generation(viewer.getUUID(),UUID.fromString(resume)).orElseThrow();if(!job.purpose().equals("WORLD_CONTENT")||!job.state().equals("PUBLISHED"))throw new IllegalStateException("WORLD_RESUME_NOT_PUBLISHED");agentId=job.agentId().toString();}
            Files.writeString(root.resolve("fixture.json"),json.writeValueAsString(Map.of("run",RUN,"prompt",PROMPT,"location",location,"resume",resume)));setup=true;
        }
        var job=operationId==null?packages.list(viewer.getUUID()).stream().filter(j->j.purpose().equals("WORLD_CONTENT")&&j.prompt().equals(PROMPT)&&j.updatedAtEpochMillis()>=startedAfter).findFirst().orElse(null):packages.generation(viewer.getUUID(),UUID.fromString(operationId)).orElse(null);
        if(job==null)return;operationId=job.operationId().toString();
        if(Set.of("FAILED","STALE","INTERRUPTED","CANCELLED").contains(job.state())){failure="WORLD_GENERATION_"+job.errorCode();Files.writeString(root.resolve("generation-failed.json"),json.writeValueAsString(job));return;}
        if(!job.state().equals("PUBLISHED"))return;
        var pack=packages.worldLibrary().get(job.packageId()).orElseThrow();packageId=pack.packageId().toString();packageRevision=pack.revision();
        if(!published){published=true;Files.writeString(root.resolve("package.json"),json.writeValueAsString(pack));Files.writeString(root.resolve("job.json"),json.writeValueAsString(job));Files.write(root.resolve("model-output.json"),packages.worldContent().read(job.rawOutputSha256()));for(var ref:pack.resources().values()){var target=root.resolve("package").resolve(ref.path()).normalize();if(!target.startsWith(root.resolve("package")))throw new IllegalStateException("EVIDENCE_PATH");Files.createDirectories(target.getParent());Files.write(target,packages.worldContent().read(ref.sha256()));}}
        var runtime=WorldContentRuntime.get(server);var a=runtime.list(viewer.getUUID()).stream().filter(r->r.packageId().equals(pack.packageId())&&r.location().equals(location)).findFirst().orElse(null);if(a==null)return;
        if(a.state().equals("FAILED")){failure="WORLD_ACTIVATION_"+a.error();Files.writeString(root.resolve("activation-failed.json"),json.writeValueAsString(a));return;}
        if(a.state().equals("ACTIVE")&&!verified){
            if(activeAt==0){activeAt=server.getTickCount();activationId=a.operationId();instanceId=a.instanceId();var field=WorldContentRuntime.class.getDeclaredField("hosts");field.setAccessible(true);heldHost=(WorldContentRuntime.InstanceHost)((Map<?,?>)field.get(runtime)).get(instanceId);}
            var instance=runtime.instance(instanceId).orElseThrow();int blocks=runtime.verifiedBlocks(instanceId);long pulse=Long.parseLong(instance.state().getOrDefault("pulse","0"));
            if(server.getTickCount()-activeAt>=100){
                if(blocks<8||blocks>16||pulse<3){failure="WORLD_RESULT_MISMATCH";return;}
                var retry=runtime.activate(viewer,a.operationId(),a.packageId(),a.packageRevision(),a.definitionId(),a.location(),true);if(!retry.instanceId().equals(instanceId)||runtime.list(viewer.getUUID()).stream().filter(r->r.packageId().equals(pack.packageId())&&r.location().equals(location)).count()!=1)throw new IllegalStateException("WORLD_ACTIVATION_DUPLICATED");
                Files.writeString(root.resolve("world-verified.json"),json.writeValueAsString(Map.of("activation",a,"instance",instance,"nativeBlocks",blocks,"pulse",pulse,"ticks",server.getTickCount()-activeAt,"duplicate",retry)));verified=true;
            }
        }
        if(verified&&a.state().equals("DISABLED")&&!disabledVerified){
            var instance=runtime.instance(instanceId).orElseThrow();String pulse=instance.state().getOrDefault("pulse","");if(stoppedPulse==null){stoppedPulse=pulse;activeAt=server.getTickCount();}
            if(server.getTickCount()-activeAt>=60){if(!stoppedPulse.equals(pulse))throw new IllegalStateException("WORLD_SCRIPT_STILL_TICKING");boolean rejected=false;try{heldHost.state("lateProbe","must-not-save");}catch(IllegalStateException expected){rejected=true;}if(!rejected||runtime.instance(instanceId).orElseThrow().state().containsKey("lateProbe"))throw new IllegalStateException("WORLD_STALE_HOST_ACCEPTED");int nativeBlocks=0;for(var entry:instance.state().entrySet())if(entry.getKey().startsWith("_block.")){var b=json.readTree(entry.getValue());var pos=new net.minecraft.core.BlockPos(b.path("x").asInt(),b.path("y").asInt(),b.path("z").asInt());if(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(server.overworld().getBlockState(pos).getBlock()).toString().equals(b.path("block").asText()))nativeBlocks++;}if(nativeBlocks<8||nativeBlocks>16)throw new IllegalStateException("WORLD_DISABLED_BLOCK_LOSS");Files.writeString(root.resolve("disabled-verified.json"),json.writeValueAsString(Map.of("activation",a,"instance",instance,"pulse",pulse,"nativeBlocks",nativeBlocks,"staleHostRejected",true)));disabledVerified=true;}
        }
        if(verified&&interfaceClosed&&a.state().equals("ACTIVE")&&!afterUiCloseVerified){
            long pulse=Long.parseLong(runtime.instance(instanceId).orElseThrow().state().getOrDefault("pulse","0"));
            if(closedAt==0){closedAt=server.getTickCount();closedPulse=pulse;}
            if(server.getTickCount()-closedAt>=80){if(pulse<=closedPulse)throw new IllegalStateException("WORLD_DEPENDS_ON_GUI");Files.writeString(root.resolve("browser-closed-verified.json"),json.writeValueAsString(Map.of("beforePulse",closedPulse,"pulse",pulse,"nativeBlocks",runtime.verifiedBlocks(instanceId))));afterUiCloseVerified=true;}
        }
    }
}
