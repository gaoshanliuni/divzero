package dev.mineagent.runtime.neoforge.ui;
import dev.mineagent.runtime.neoforge.*;
import dev.mineagent.runtime.neoforge.body.MineAgentPlayer;
import dev.mineagent.runtime.api.agent.AgentMode;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import java.util.*;
import java.nio.file.*;
@EventBusSubscriber(modid="mineagent_runtime")
public final class AgentContainerSmokeServer {
    public static final String RUN=UUID.randomUUID().toString(),NAME="Agent 物资 "+RUN.substring(0,8),VIEWER_MARKER="Viewer 私有 "+RUN.substring(0,8);
    public static final UUID PACKAGE=UUID.fromString("1d8f75e9-2b13-4ab7-9e5d-792876269dc6");
    public static volatile String agentId,patchOperation,taskId;public static volatile long revision;public static volatile boolean patchApplied,verified;private static BlockPos chest;private static MineAgentPlayer body;
    public static boolean patch(){return Boolean.getBoolean("mineagent.agentContainerPatch");}public static boolean far(){return Boolean.getBoolean("mineagent.agentContainerFar");}
    public static String directory(){return "agent-container-evidence/"+RUN;}
    @SubscribeEvent public static void tick(ServerTickEvent.Post event)throws Exception{
        if(!Boolean.getBoolean("mineagent.agentContainerSmoke"))return;var server=event.getServer();var viewer=server.getPlayerList().getPlayers().stream().filter(p->!(p instanceof MineAgentPlayer)).findFirst().orElse(null);if(viewer==null)return;
        var root=server.getServerDirectory().resolve(directory()).toAbsolutePath().normalize();Files.createDirectories(root);var json=new com.fasterxml.jackson.databind.ObjectMapper();var runtime=ServerPackageRuntime.get(server);
        var head=runtime.heads(viewer.getUUID()).stream().filter(p->p.packageId().equals(PACKAGE)).findFirst().orElseThrow();revision=head.revision();
        if(chest==null){
            int x=256;while(!server.overworld().getBlockState(new BlockPos(x,175,3)).isAir()&&x<1024)x+=4;if(x>=1024)throw new IllegalStateException("AGENT_CONTAINER_FIXTURE_SPACE");chest=new BlockPos(x,175,3);
            server.overworld().setBlock(chest.below(),Blocks.STONE.defaultBlockState(),3);server.overworld().setBlock(chest,Blocks.CHEST.defaultBlockState(),3);
            var box=(ChestBlockEntity)server.overworld().getBlockEntity(chest);var stack=new ItemStack(Items.COBBLESTONE,12);stack.set(DataComponents.CUSTOM_NAME,Component.literal(NAME));box.setItem(0,stack);box.setChanged();
            for(int dx:new int[]{0,24})server.overworld().setBlock(new BlockPos(x+dx,174,0),Blocks.STONE.defaultBlockState(),3);
            var agent=runtime.list(viewer.getUUID()).stream().filter(j->j.packageId().equals(PACKAGE)).findFirst().orElseThrow().agentId();var definition=MineAgentRuntimeServices.bodies(server).definitions().stream().filter(a->a.agentId().equals(agent)).findFirst().orElseThrow();agentId=agent.toString();body=MineAgentRuntimeServices.bodies(server).body(agent).orElseThrow();
            MineAgentRuntimeServices.permissions(server).registerOwnership(definition.agentId(),viewer.getUUID());
            viewer.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);body.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
            body.movementController().stop();body.abortMining();body.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
            body.teleportTo(server.overworld(),x+0.5+(far()?24:0),175,0.5,Set.of(),0,20,true);viewer.teleportTo(server.overworld(),x+0.5+(far()?0:24),175,0.5,Set.of(),0,20,true);
            if(viewer.getInventory().getFreeSlot()<0)throw new IllegalStateException("VIEWER_INVENTORY_SPACE");var marker=new ItemStack(Items.DIAMOND,7);marker.set(DataComponents.CUSTOM_NAME,Component.literal(VIEWER_MARKER));viewer.getInventory().add(marker);viewer.inventoryMenu.broadcastFullState();
            Files.writeString(root.resolve("fixture.json"),json.writeValueAsString(Map.of("viewer",viewer.getUUID(),"actor",body.getUUID(),"chest",List.of(x,175,3),"farActor",far(),"itemName",NAME,"viewerMarker",VIEWER_MARKER)));
        }
        if(patch())for(var job:runtime.patchJobs(viewer.getUUID()))if(job.base().packageId().equals(PACKAGE)&&job.prompt().contains(RUN)){
            patchOperation=job.operationId().toString();Files.writeString(root.resolve("patch-job.json"),json.writeValueAsString(job));
            if(job.state().equals("APPLIED"))patchApplied=true;
            if(Set.of("FAILED","REJECTED","CANCELLED").contains(job.state()))throw new IllegalStateException("CONTAINER_CODER_PATCH_FAILED "+job.errorCode());
        }
        if((!patch()||patchApplied)&&!Files.exists(root.resolve("package.json"))){Files.writeString(root.resolve("package.json"),json.writeValueAsString(head));var store=new dev.mineagent.runtime.core.content.ContentAddressedStore(server.getServerDirectory().resolve("mineagent-runtime-data/content"));for(var ref:head.resources().values()){var p=root.resolve("package").resolve(ref.path()).normalize();if(!p.startsWith(root))throw new IllegalStateException("EVIDENCE_PATH");Files.createDirectories(p.getParent());Files.write(p,store.read(ref.sha256()));}}
        if(verified)return;
        for(var record:MineAgentRuntimeServices.audit(server).recent(128))if(record.action().equals("UI_CONTAINER_ADMISSION_FAILED")){
            var t=MineAgentRuntimeServices.tasks(server).get(UUID.fromString(record.target())).orElse(null);
            if(t!=null&&t.title().contains(NAME)){Files.writeString(root.resolve("admission-failure.json"),record.payload());if(!far())throw new IllegalStateException("AGENT_CONTAINER_ADMISSION_FAILED: "+record.payload());}
        }
        if(far())for(var task:MineAgentRuntimeServices.tasks(server).all())if(task.agentId().toString().equals(agentId)&&task.title().contains(NAME)&&task.status()==dev.mineagent.runtime.api.task.TaskStatus.CANCELLED){
            if(body.containerMenu!=body.inventoryMenu)throw new IllegalStateException("FAR_AGENT_MENU_OPENED");checkInventory(viewer,0);taskId=task.taskId().toString();Files.writeString(root.resolve("distance-denied.json"),json.writeValueAsString(Map.of("task",task,"viewerDistance",viewer.distanceToSqr(chest.getCenter()),"actorDistance",body.distanceToSqr(chest.getCenter()),"modelStarted",false)));verified=true;return;
        }
        for(var audit:MineAgentRuntimeServices.audit(server).recent(128))if(audit.action().equals("UI_AGENT_RESULT")){
            var result=json.readTree(audit.payload());if(!result.path("session").path("binding").path("actorId").asText().equals(agentId))continue;
            var resultTask=MineAgentRuntimeServices.tasks(server).get(UUID.fromString(result.path("taskId").asText())).orElse(null);if(resultTask==null||!resultTask.title().contains(NAME))continue;
            Files.writeString(root.resolve("agent-result.json"),audit.payload());taskId=result.path("taskId").asText();
            if(!result.path("status").asText().equals("VERIFIED")||!result.path("businessVerified").asBoolean()||!result.path("verificationScope").asText().equals("AGENT_NATIVE_CONTAINER"))throw new IllegalStateException("AGENT_CONTAINER_NOT_VERIFIED "+result.path("status").asText());
            if(body.containerMenu!=body.inventoryMenu)throw new IllegalStateException("AGENT_MENU_NOT_CLOSED");checkInventory(viewer,12);
            var box=(ChestBlockEntity)server.overworld().getBlockEntity(chest);if(!box.getItem(0).isEmpty())throw new IllegalStateException("AGENT_CONTAINER_SOURCE_NOT_EMPTY");
            Files.writeString(root.resolve("server-result.json"),json.writeValueAsString(Map.of("taskId",taskId,"actor",body.getUUID(),"viewer",viewer.getUUID(),"actorTaggedCount",count(body,NAME),"viewerTaggedCount",count(viewer,NAME),"viewerMarkerCount",count(viewer,VIEWER_MARKER),"menuClosed",true,"viewerDistance",viewer.distanceToSqr(chest.getCenter()),"actorDistance",body.distanceToSqr(chest.getCenter()))));
            verified=true;MineAgentRuntimeMod.LOGGER.info("MINEAGENT_AGENT_CONTAINER_SERVER_OK run={}",RUN);break;
        }
    }
    private static int count(net.minecraft.server.level.ServerPlayer p,String name){int count=0;for(int i=0;i<p.getInventory().getContainerSize();i++){var s=p.getInventory().getItem(i);if(!s.isEmpty()&&s.getHoverName().getString().equals(name))count+=s.getCount();}return count;}
    private static void checkInventory(net.minecraft.server.level.ServerPlayer viewer,int expected){if(count(body,NAME)!=expected||count(viewer,NAME)!=0||count(viewer,VIEWER_MARKER)!=7)throw new IllegalStateException("AGENT_VIEWER_INVENTORY_CROSSED");}
}
