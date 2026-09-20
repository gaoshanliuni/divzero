package dev.mineagent.runtime.neoforge.content;
import dev.mineagent.runtime.neoforge.*;
import dev.mineagent.runtime.neoforge.ui.ServerPackageRuntime;
import dev.mineagent.runtime.api.packages.*;
import dev.mineagent.runtime.core.packages.WorldActivationLedger;
import com.fasterxml.jackson.databind.*;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import java.nio.file.*;
import java.util.*;
@EventBusSubscriber(modid="mineagent_runtime")
public final class WorldRestoreSmokeServer {
    public static final String RUN=UUID.randomUUID().toString(),STAGE=System.getProperty("mineagent.worldRestoreStage","seed");
    public static final String PROMPT="生成一个此前没有的可恢复观测信标：用 minecraft:stone_bricks 做小基座，minecraft:light_blue_stained_glass 做两层错位台阶，minecraft:sea_lantern 做顶灯，共 6 到 10 个方块，不生成网页。所有放置放在 instance.create，created 持久计数仅在 create 中递增一次，pulse 仅在 create 中初始化为 0。每 20 ticks 持久 pulse 递增 1。instance.restore 只将持久 restores 计数加 1，保留 pulse 和已有世界块，不重复创建。声明 server.restore 与 server 相同路径，严格采用纯注册顶层，不顶层调用 Native 方法。只用 content.placeBlock 和 content.state 管理方块与状态。";
    public static volatile String agentId,packageId,failure;public static volatile boolean finished,revokeRequested;public static volatile RuntimeInstanceLocation location;
    private static final ObjectMapper JSON=new ObjectMapper();private static final long STARTED=System.currentTimeMillis();
    private static boolean setup,published;private static UUID world,owner,operation,aId,bId;private static long readyAt,revokeAt;private static long revokedPulse;private static JsonNode previous;
    public static String directory(){return "world-restore-evidence/"+RUN;}
    public static String activationToRevoke(){return aId==null?null:aId.toString();}
    private static Path root(net.minecraft.server.MinecraftServer server){return server.getServerDirectory().resolve(directory()).toAbsolutePath().normalize();}
    @SubscribeEvent public static void tick(ServerTickEvent.Post event)throws Exception{
        if(!Boolean.getBoolean("mineagent.worldRestoreSmoke")||finished||failure!=null)return;
        var server=event.getServer();var viewer=server.getPlayerList().getPlayers().stream().filter(p->!(p instanceof dev.mineagent.runtime.neoforge.body.MineAgentPlayer)).findFirst().orElse(null);if(viewer==null)return;
        var root=root(server);Files.createDirectories(root);var runtime=WorldContentRuntime.get(server);var packages=ServerPackageRuntime.get(server);
        try{
            if(!setup){
                world=MineAgentRuntimeServices.worldId(server);owner=viewer.getUUID();setup=true;
                if(STAGE.equals("seed")){
                    var agent=MineAgentRuntimeServices.bodies(server).definitions().stream().filter(a->a.ownerPlayerId().equals(owner)).findFirst().orElseThrow();agentId=agent.agentId().toString();
                    var actions=new HashSet<>(MineAgentRuntimeServices.permissions(server).trustedActions(owner));actions.addAll(Set.of(dev.mineagent.runtime.api.permission.PermissionAction.RUN_CODE,dev.mineagent.runtime.api.permission.PermissionAction.MANAGE_PACKAGES));
                    var config=MineAgentRuntimeServices.config(server);var value=actions.stream().map(Enum::name).sorted().collect(java.util.stream.Collectors.joining(","));
                    if(!config.apply(new dev.mineagent.runtime.api.config.ConfigPatch(config.snapshot().revision(),Map.of("permission.player."+owner,value)),true).accepted())throw new IllegalStateException("FIXTURE_PERMISSION_PERSIST");MineAgentRuntimeServices.permissions(server).setTrustedActions(owner,actions);
                    int x=160;var used=runtime.list(owner).stream().map(a->(int)a.location().x()).toList();while(used.contains(x)||used.contains(x+12))x+=24;location=new RuntimeInstanceLocation("minecraft:overworld",x,200,0,0,0);
                    String resume=System.getProperty("mineagent.worldRestoreResume","");if(!resume.isBlank()){operation=UUID.fromString(resume);var job=packages.generation(owner,operation).orElseThrow();if(!job.state().equals("PUBLISHED"))throw new IllegalStateException("RESUME_NOT_PUBLISHED");agentId=job.agentId().toString();}
                }else{
                    var prior=server.getServerDirectory().resolve("world-restore-evidence").resolve(UUID.fromString(System.getProperty("mineagent.worldRestoreCheckpoint")).toString());
                    var checkpoint=JSON.readTree(prior.resolve("checkpoint.json").toFile());previous=JSON.readTree(prior.resolve("stop-state.json").toFile());
                    aId=UUID.fromString(checkpoint.path("aId").asText());bId=UUID.fromString(checkpoint.path("bId").asText());packageId=checkpoint.path("packageId").asText();
                    location=JSON.treeToValue(checkpoint.path("location"),RuntimeInstanceLocation.class);
                }
                viewer.setGameMode(net.minecraft.world.level.GameType.CREATIVE);viewer.getAbilities().flying=true;viewer.onUpdateAbilities();viewer.teleportTo(server.overworld(),location.x()+7,location.y()+3,location.z()+10,Set.of(),145,15,true);
                Files.writeString(root.resolve("fixture.json"),JSON.writeValueAsString(Map.of("stage",STAGE,"world",world,"owner",owner,"location",location,"prompt",PROMPT)));readyAt=server.getTickCount();
            }
            if(STAGE.equals("seed")){
                var job=operation==null?packages.list(owner).stream().filter(j->j.purpose().equals("WORLD_CONTENT")&&j.prompt().equals(PROMPT)&&j.updatedAtEpochMillis()>=STARTED).findFirst().orElse(null):packages.generation(owner,operation).orElse(null);if(job==null)return;operation=job.operationId();
                if(!Set.of("GENERATING","PUBLISHED").contains(job.state()))throw new IllegalStateException("GENERATION_"+job.errorCode());if(!job.state().equals("PUBLISHED"))return;
                var pack=packages.worldLibrary().get(job.packageId()).orElseThrow();packageId=pack.packageId().toString();
                if(!published){published=true;Files.writeString(root.resolve("job.json"),JSON.writeValueAsString(job));Files.writeString(root.resolve("package.json"),JSON.writeValueAsString(pack));Files.write(root.resolve("model-output.json"),packages.worldContent().read(job.rawOutputSha256()));for(var ref:pack.resources().values()){var target=root.resolve("package").resolve(ref.path()).normalize();if(!target.startsWith(root.resolve("package")))throw new IllegalStateException("EVIDENCE_PATH");Files.createDirectories(target.getParent());Files.write(target,packages.worldContent().read(ref.sha256()));}}
                var a=runtime.list(owner).stream().filter(r->r.packageId().equals(pack.packageId())&&r.location().equals(location)).findFirst().orElse(null);if(a==null)return;if(!a.state().equals("ACTIVE"))throw new IllegalStateException("ACTIVATION_"+a.error());if(!a.autoRestore())throw new IllegalStateException("GUI_RESTORE_CONSENT_MISSING");aId=a.operationId();
                if(bId==null){var b=runtime.activate(viewer,UUID.randomUUID(),pack.packageId(),packages.worldLibrary().get(pack.packageId()).orElseThrow().revision(),a.definitionId(),new RuntimeInstanceLocation(location.dimension(),location.x()+12,location.y(),location.z(),0,0),true,false);bId=b.operationId();readyAt=server.getTickCount();}
                if(server.getTickCount()-readyAt<100)return;
                var b=find(runtime,bId);var ai=runtime.instance(a.instanceId()).orElseThrow();var bi=runtime.instance(b.instanceId()).orElseThrow();
                requireState(ai,1,0);requireState(bi,1,0);if(!b.state().equals("ACTIVE")||b.autoRestore()||pulse(ai)<3||pulse(bi)<3)throw new IllegalStateException("SEED_SCOPE_STATE");
                writeVerified(root,"seed-verified",a,b,ai,bi,runtime.verifiedBlocks(a.instanceId()),runtime.verifiedBlocks(b.instanceId()));checkpoint(root);finished=true;
            }else{
                if(server.getTickCount()-readyAt<100)return;
                var a=find(runtime,aId);var b=find(runtime,bId);var ai=runtime.instance(a.instanceId()).orElseThrow();var bi=runtime.instance(b.instanceId()).orElseThrow();
                if(STAGE.equals("restore")){
                    requireState(ai,1,1);requireState(bi,1,0);
                    if(!a.state().equals("ACTIVE")||!b.state().equals("INTERRUPTED")||pulse(ai)<=previous.path("a").path("state").path("pulse").asLong()||pulse(bi)!=previous.path("b").path("state").path("pulse").asLong())throw new IllegalStateException("RESTORE_SCOPE_STATE");
                    if(!revokeRequested){writeVerified(root,"restore-verified",a,b,ai,bi,runtime.verifiedBlocks(a.instanceId()),blocks(server,bi));revokeRequested=true;}
                    if(!a.autoRestore()){
                        if(revokeAt==0){revokeAt=server.getTickCount();revokedPulse=pulse(ai);}
                        if(server.getTickCount()-revokeAt>=60){if(pulse(ai)<=revokedPulse)throw new IllegalStateException("POLICY_REVOKE_STOPPED_ACTIVE");Files.writeString(root.resolve("revoked-verified.json"),JSON.writeValueAsString(Map.of("activation",a,"instance",ai,"pulseBeforeRevokeWait",revokedPulse)));checkpoint(root);finished=true;}
                    }
                }else if(STAGE.equals("revoked")){
                    requireState(ai,1,1);requireState(bi,1,0);
                    if(!a.state().equals("INTERRUPTED")||a.autoRestore()||!b.state().equals("INTERRUPTED")||pulse(ai)!=previous.path("a").path("state").path("pulse").asLong()||pulse(bi)!=previous.path("b").path("state").path("pulse").asLong())throw new IllegalStateException("REVOKED_SCOPE_REPLAYED");
                    writeVerified(root,"revoked-restart-verified",a,b,ai,bi,blocks(server,ai),blocks(server,bi));checkpoint(root);finished=true;
                }else throw new IllegalStateException("RESTORE_STAGE");
            }
        }catch(Exception e){failure=e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();Files.writeString(root.resolve("failure.json"),JSON.writeValueAsString(Map.of("stage",STAGE,"failure",failure)));for(var id:List.of(aId==null?new UUID(0,0):aId,bId==null?new UUID(0,0):bId))try{runtime.disable(viewer,id);}catch(Exception ignored){}}
    }
    private static WorldActivationLedger.Activation find(WorldContentRuntime r,UUID id){return r.list(owner).stream().filter(a->a.operationId().equals(id)).findFirst().orElseThrow();}
    private static long pulse(RuntimeInstance i){return Long.parseLong(i.state().getOrDefault("pulse","0"));}
    private static void requireState(RuntimeInstance i,int creates,int restores){if(!Integer.toString(creates).equals(i.state().get("created"))||Integer.parseInt(i.state().getOrDefault("restores","0"))!=restores)throw new IllegalStateException("LIFECYCLE_COUNTERS");}
    private static int blocks(net.minecraft.server.MinecraftServer s,RuntimeInstance i)throws Exception{int n=0;for(var e:i.state().entrySet())if(e.getKey().startsWith("_block.")){var b=JSON.readTree(e.getValue());var p=new net.minecraft.core.BlockPos(b.path("x").asInt(),b.path("y").asInt(),b.path("z").asInt());if(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(s.overworld().getBlockState(p).getBlock()).toString().equals(b.path("block").asText()))n++;}return n;}
    private static void writeVerified(Path root,String name,WorldActivationLedger.Activation a,WorldActivationLedger.Activation b,RuntimeInstance ai,RuntimeInstance bi,int ac,int bc)throws Exception{if(ac<6||ac>10||bc<6||bc>10)throw new IllegalStateException("NATIVE_BLOCK_COUNT");Files.writeString(root.resolve(name+".json"),JSON.writeValueAsString(Map.of("aActivation",a,"bActivation",b,"a",ai,"b",bi,"aBlocks",ac,"bBlocks",bc)));}
    private static void checkpoint(Path root)throws Exception{Files.writeString(root.resolve("checkpoint.json"),JSON.writeValueAsString(Map.of("aId",aId,"bId",bId,"packageId",packageId,"location",location)));}
    @SubscribeEvent public static void stopped(ServerStoppedEvent e)throws Exception{
        if(!Boolean.getBoolean("mineagent.worldRestoreSmoke")||!finished||world==null||aId==null||bId==null)return;
        try(var repo=new dev.mineagent.runtime.core.persistence.SqliteRuntimeRepository(e.getServer().getServerDirectory().resolve("mineagent-runtime-data/runtime.db"))){
            var a=JSON.readValue(repo.get(world,"world_package_activations_v1",aId.toString()).orElseThrow().payload(),WorldActivationLedger.Activation.class);var b=JSON.readValue(repo.get(world,"world_package_activations_v1",bId.toString()).orElseThrow().payload(),WorldActivationLedger.Activation.class);
            var ai=JSON.readTree(repo.get(world,"runtime_instances_v2",a.instanceId().toString()).orElseThrow().payload());var bi=JSON.readTree(repo.get(world,"runtime_instances_v2",b.instanceId().toString()).orElseThrow().payload());
            Files.writeString(root(e.getServer()).resolve("stop-state.json"),JSON.writeValueAsString(Map.of("aActivation",a,"bActivation",b,"a",ai,"b",bi)));
        }
    }
}
