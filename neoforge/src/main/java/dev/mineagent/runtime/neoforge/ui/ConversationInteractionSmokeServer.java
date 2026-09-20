package dev.mineagent.runtime.neoforge.ui;

import com.fasterxml.jackson.databind.*;
import dev.mineagent.runtime.neoforge.MineAgentRuntimeServices;
import net.minecraft.core.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.properties.*;
import java.nio.file.*;
import java.util.*;

/** Independent real-model scene: item conservation and native lever effect, not a scripted tool sequence. */
public final class ConversationInteractionSmokeServer {
    public static volatile boolean ready,verified;
    public static volatile String failure="";
    public static volatile UUID agent;
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final List<Map<String,Object>> actions=new ArrayList<>();
    private static BlockPos chest,lever;
    private static String leverOffHash,oldSession="";private static long oldRevision=1;
    private static int phase;
    public static boolean active(){return Boolean.getBoolean("mineagent.conversationAgentReal")&&System.getProperty("mineagent.conversationAgentScenario","").equals("interaction");}
    public static void observe(String tool,JsonNode args,Map<String,Object> receipt){
        if(!active()||phase!=0)return;actions.add(Map.of("tool",tool,"arguments",args,"receipt",receipt));
        if(tool.equals("quick_move_container")){oldSession=args.path("session_id").asText();Object revision=receipt.get("revision");if(revision instanceof Number number)oldRevision=number.longValue();}
    }
    private static void save(MinecraftServer s,String name,Object value)throws Exception{var root=Files.createDirectories(s.getServerDirectory().resolve("conversation-interaction-smoke"));Files.writeString(root.resolve(name+".json"),JSON.writeValueAsString(value));}
    private static int count(ServerPlayer p,Item item){int n=0;for(int i=0;i<p.getInventory().getContainerSize();i++)if(p.getInventory().getItem(i).is(item))n+=p.getInventory().getItem(i).getCount();return n;}
    private static ChestBlockEntity chest(ServerPlayer p){if(!(p.level().getBlockEntity(chest) instanceof ChestBlockEntity c))throw new IllegalStateException("INTERACTION_CHEST_REMOVED");return c;}
    private static void readback(ServerPlayer p){
        var c=chest(p);int diamonds=0,iron=0;for(int i=0;i<c.getContainerSize();i++){var stack=c.getItem(i);if(stack.is(Items.DIAMOND))diamonds+=stack.getCount();if(stack.is(Items.IRON_INGOT))iron+=stack.getCount();}
        if(count(p,Items.DIAMOND)!=3||diamonds!=0||iron!=7||count(p,Items.IRON_INGOT)!=0)throw new IllegalStateException("INTERACTION_ITEM_CONSERVATION");
        if(p.containerMenu!=p.inventoryMenu||!p.inventoryMenu.getCarried().isEmpty())throw new IllegalStateException("INTERACTION_MENU_NOT_CLOSED");
        if(!p.level().getBlockState(lever).is(Blocks.LEVER)||!p.level().getBlockState(lever).getValue(BlockStateProperties.POWERED))throw new IllegalStateException("INTERACTION_LEVER_NOT_POWERED");
    }
    public static void tick(MinecraftServer s){
        if(!failure.isEmpty()||verified)return;var p=s.getPlayerList().getPlayers().stream().filter(v->!(v instanceof dev.mineagent.runtime.neoforge.body.MineAgentPlayer)).findFirst().orElse(null);if(p==null)return;
        try{
            if(!ready){
                var center=p.blockPosition();for(var pos:BlockPos.betweenClosed(center.offset(-1,-1,-1),center.offset(4,3,4)))p.level().setBlockAndUpdate(pos,pos.getY()<center.getY()?Blocks.STONE.defaultBlockState():Blocks.AIR.defaultBlockState());
                p.getInventory().clearContent();p.getInventory().setSelectedSlot(0);p.inventoryMenu.broadcastFullState();chest=center.south(2);lever=chest.east(2);
                p.level().setBlockAndUpdate(chest,Blocks.CHEST.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING,Direction.NORTH));var inventory=chest(p);inventory.setItem(0,new ItemStack(Items.DIAMOND,3));inventory.setItem(1,new ItemStack(Items.IRON_INGOT,7));inventory.setChanged();
                var off=Blocks.LEVER.defaultBlockState().setValue(BlockStateProperties.ATTACH_FACE,AttachFace.FLOOR).setValue(BlockStateProperties.HORIZONTAL_FACING,Direction.NORTH).setValue(BlockStateProperties.POWERED,false);p.level().setBlockAndUpdate(lever,off);leverOffHash=NativeContainerSnapshot.sha(net.minecraft.commands.arguments.blocks.BlockStateParser.serialize(off));
                p.connection.teleport(center.getX()+.5,center.getY(),center.getZ()+.5,0,35);
                agent=MineAgentRuntimeServices.bodies(s).createPersistentAt("工具助手",p.getUUID(),p.level(),p.position().add(-1,0,0)).agentId();
                save(s,"initial",Map.of("chest",List.of(chest.getX(),chest.getY(),chest.getZ()),"lever",List.of(lever.getX(),lever.getY(),lever.getZ()),"chestDiamonds",3,"chestIron",7,"playerDiamonds",0,"leverPowered",false,"fixtureGrantsOp",false));ready=true;return;
            }
            if(phase!=0)return;
            var store=ServerConversations.get(s).store();var list=store.list(p.getUUID(),agent,"ALL","",0,20).conversations();if(list.isEmpty())return;var conversation=list.getFirst();if(conversation.messageCount()<2||!conversation.activeOperation().isEmpty())return;
            var context=store.context(p.getUUID(),agent,conversation.conversationId(),null).orElseThrow();save(s,"actions",actions);save(s,"conversation",context);
            if(!context.requestState().equals("COMPLETE"))throw new IllegalStateException("INTERACTION_CONVERSATION_"+context.errorCode());
            if(context.modelReceipt()==null||!context.modelReceipt().requestedModel().equals("deepseek-flash"))throw new IllegalStateException("INTERACTION_REAL_PROVIDER_REQUIRED");
            if(actions.isEmpty()||actions.stream().anyMatch(a->!Set.of("interact_block","quick_move_container","close_container").contains(a.get("tool")))||oldSession.isEmpty())throw new IllegalStateException("INTERACTION_NON_NATIVE_TOOL_OR_NO_MOVE");
            readback(p);phase=1;
            var staleBlock=JSON.valueToTree(Map.of("position",List.of(lever.getX(),lever.getY(),lever.getZ()),"hand","main","expected_block_hash",leverOffHash,"expected_hand_hash",ConversationAgentTools.hash(p,p.getMainHandItem())));
            ConversationAgentTools.execute(p,agent,UUID.randomUUID(),"interact_block",staleBlock.toString(),()->true).whenComplete((result,error)->s.execute(()->{
                try{
                    if(error!=null||!"AGENT_BLOCK_CHANGED".equals(result.get("error")))throw new IllegalStateException("INTERACTION_STALE_BLOCK_NOT_REJECTED");readback(p);save(s,"stale-block",result);
                    var staleMenu=JSON.valueToTree(Map.of("session_id",oldSession,"expected_revision",oldRevision,"slot",0));
                    ConversationAgentTools.execute(p,agent,UUID.randomUUID(),"quick_move_container",staleMenu.toString(),()->true).whenComplete((outcome,problem)->s.execute(()->{
                        try{if(problem!=null||!"AGENT_CONTAINER_SESSION_CHANGED".equals(outcome.get("error")))throw new IllegalStateException("INTERACTION_OLD_MENU_NOT_REJECTED");readback(p);save(s,"stale-menu",outcome);save(s,"result",Map.of("status","NATIVE_INTERACTION_REAL_VERIFIED","diamondsMoved",3,"ironLeftInChest",7,"leverPowered",true,"menuClosed",true,"staleBlockRejected",true,"oldMenuRejected",true,"positiveActions",actions.size()));verified=true;}
                        catch(Exception failed){failure=failed.toString();}
                    }));
                }catch(Exception failed){failure=failed.toString();}
            }));
        }catch(Exception failed){failure=failed.toString();try{save(s,"failure",Map.of("error",failure,"actions",actions));}catch(Exception ignored){}}
    }
}
