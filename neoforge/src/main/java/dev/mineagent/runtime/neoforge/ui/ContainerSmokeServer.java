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
/** Isolated chest fixture. Does not modify the player's pre-existing stacks; a unique named stack tracks conservation. */
@EventBusSubscriber(modid="mineagent_runtime")
public final class ContainerSmokeServer {
    public static final String RUN=UUID.randomUUID().toString(),ITEM_NAME="容器验收 "+RUN.substring(0,8);
    public static volatile String agentId,packageId;public static volatile boolean finish,verified;private static BlockPos chest;
    public static String directory(){return "container-evidence/"+RUN;}
    public static boolean resume(){return !System.getProperty("mineagent.containerResume","").isBlank();}
    public static boolean actions(){return Boolean.getBoolean("mineagent.containerActions");}
    public static volatile int expectedPlayerCount,expectedChestCount=12;
    @SubscribeEvent public static void tick(ServerTickEvent.Post event)throws Exception{
        if(!Boolean.getBoolean("mineagent.containerSmoke"))return;var server=event.getServer();var player=server.getPlayerList().getPlayers().stream().filter(p->!(p instanceof MineAgentPlayer)).findFirst().orElse(null);if(player==null)return;
        var runtime=ServerPackageRuntime.get(server);var root=server.getServerDirectory().resolve(directory()).toAbsolutePath().normalize();Files.createDirectories(root);var json=new com.fasterxml.jackson.databind.ObjectMapper();
        if(chest==null){
            int x=64;while(!server.overworld().getBlockState(new BlockPos(x,170,3)).isAir()&&x<1024)x+=4;if(x>=1024)throw new IllegalStateException("CONTAINER_FIXTURE_SPACE");chest=new BlockPos(x,170,3);
            server.overworld().setBlock(chest.below(),Blocks.STONE.defaultBlockState(),3);server.overworld().setBlock(chest,Blocks.CHEST.defaultBlockState(),3);
            var block=(ChestBlockEntity)server.overworld().getBlockEntity(chest);var stack=new ItemStack(Items.COBBLESTONE,12);stack.set(DataComponents.CUSTOM_NAME,Component.literal(ITEM_NAME));block.setItem(0,stack);block.setChanged();
            if(player.getInventory().getFreeSlot()<0)throw new IllegalStateException("CONTAINER_FIXTURE_NEEDS_INVENTORY_SPACE");
            player.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);player.teleportTo(server.overworld(),x+0.5,170,0.5,Set.of(),0,20,true);
            // Minimal floor prevents gravity from changing the actor's reach while a browser is open.
            server.overworld().setBlock(new BlockPos(x,169,0),Blocks.STONE.defaultBlockState(),3);
            if(resume()){var job=runtime.generation(player.getUUID(),UUID.fromString(System.getProperty("mineagent.containerResume"))).orElseThrow();agentId=job.agentId().toString();packageId=job.packageId().toString();}
            else{var agent=MineAgentRuntimeServices.bodies(server).create("Container Fixture "+RUN.substring(0,8),player,AgentMode.CREATOR);agentId=agent.agentId().toString();MineAgentRuntimeServices.permissions(server).registerOwnership(agent.agentId(),player.getUUID());}
            Files.writeString(root.resolve("fixture.json"),json.writeValueAsString(Map.of("actor",player.getUUID(),"agent",agentId,"chest",List.of(chest.getX(),chest.getY(),chest.getZ()),"itemName",ITEM_NAME,"bodyMode","SURVIVAL","readOnlyStage",!actions())));
        }
        var job=runtime.list(player.getUUID()).stream().filter(j->j.agentId().toString().equals(agentId)).findFirst().orElse(null);if(job==null)return;
        if(!Set.of("GENERATING","PUBLISHED").contains(job.state()))throw new IllegalStateException("CONTAINER_GENERATION_FAILED "+job.errorCode());
        if(job.state().equals("PUBLISHED")&&packageId==null)packageId=job.packageId().toString();
        if(packageId!=null&&!Files.exists(root.resolve("package.json"))){var pack=runtime.heads(player.getUUID()).stream().filter(p->p.packageId().toString().equals(packageId)).findFirst().orElseThrow();Files.writeString(root.resolve("package.json"),json.writeValueAsString(Map.of("job",job,"package",pack,"resumed",resume())));var store=new dev.mineagent.runtime.core.content.ContentAddressedStore(server.getServerDirectory().resolve("mineagent-runtime-data/content"));
            for(var file:pack.resources().values()){var target=root.toAbsolutePath().resolve("package").resolve(file.path()).normalize();if(!target.startsWith(root.toAbsolutePath()))throw new IllegalStateException("EVIDENCE_PATH");Files.createDirectories(target.getParent());Files.write(target,store.read(file.sha256()));}}
        if(actions()&&!finish){for(var s:ServerUiRuntime.get(server).sessions().list(player.getUUID()))if(dev.mineagent.runtime.api.ui.ContainerProtocol.bound(s.binding())){
            var operations=ServerUiRuntime.get(server).sessions().completedOperations(player.getUUID(),s.sessionId(),64);Files.writeString(root.resolve("operations.json"),json.writeValueAsString(operations));}}
        if(finish&&!verified){
            if(player.containerMenu!=player.inventoryMenu)return;int playerCount=0,chestCount=0;var block=(ChestBlockEntity)server.overworld().getBlockEntity(chest);
            for(int i=0;i<player.getInventory().getContainerSize();i++)if(tagged(player.getInventory().getItem(i)))playerCount+=player.getInventory().getItem(i).getCount();
            for(int i=0;i<block.getContainerSize();i++)if(tagged(block.getItem(i)))chestCount+=block.getItem(i).getCount();
            if(playerCount!=expectedPlayerCount||chestCount!=expectedChestCount||playerCount+chestCount!=12)throw new IllegalStateException("CONTAINER_CONSERVATION_FAILED");
            Files.writeString(root.resolve("server-result.json"),json.writeValueAsString(Map.of("playerCount",playerCount,"chestCount",chestCount,"carried",player.inventoryMenu.getCarried().getCount(),"actor",player.getUUID(),"nativeMenuClosed",true)));
            verified=true;MineAgentRuntimeMod.LOGGER.info("MINEAGENT_CONTAINER_SERVER_OK run={} actions={}",RUN,actions());
        }
    }
    private static boolean tagged(ItemStack stack){return !stack.isEmpty()&&stack.getHoverName().getString().equals(ITEM_NAME);}
}
