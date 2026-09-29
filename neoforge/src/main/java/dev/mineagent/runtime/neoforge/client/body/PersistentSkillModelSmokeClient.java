package dev.mineagent.runtime.neoforge.client.body;

import com.fasterxml.jackson.databind.*;
import dev.mineagent.runtime.neoforge.MineAgentRuntimeServices;
import dev.mineagent.runtime.neoforge.WorldActivationRuntime;
import dev.mineagent.runtime.neoforge.ui.*;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.*;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import java.nio.file.*;import java.util.*;import java.util.concurrent.*;import java.util.function.*;

/** Natural language selects the skill and its region; the fixture never supplies a skill definition. */
@EventBusSubscriber(modid="mineagent_runtime",value=Dist.CLIENT)
public final class PersistentSkillModelSmokeClient {
    private static final ObjectMapper JSON=new ObjectMapper();private static final List<Object> evidence=new ArrayList<>();
    private static UUID agent,conversation,operation;private static int ticks,phase;private static long nextPoll,baseline;private static String skill="",reply="";private static long revision;private static boolean busy,done,started;private static JsonNode latest;
    private static Minecraft mc(){return Minecraft.getInstance();}
    private static boolean playerActor(){return System.getProperty("mineagent.skillModelActor","ai").equals("player");}
    private static Path root()throws Exception{return Files.createDirectories(mc().gameDirectory.toPath().resolve("persistent-skill-model"));}
    private static void require(boolean ok,String code){if(!ok)throw new IllegalStateException(code);}
    private static <T> CompletableFuture<T> server(Function<ServerPlayer,T> work){var result=new CompletableFuture<T>();var s=mc().getSingleplayerServer();UUID viewer=mc().player.getUUID();s.submit(()->work.apply(s.getPlayerList().getPlayer(viewer))).whenComplete((value,error)->mc().execute(()->{if(error!=null)result.completeExceptionally(error);else result.complete(value);}));return result;}
    private static CompletableFuture<JsonNode> inspect(){var result=new CompletableFuture<JsonNode>();server(p->ConversationAgentTools.execute(p,agent,UUID.randomUUID(),"inspect_skills","{}",()->true)).thenCompose(Function.identity()).whenComplete((value,error)->mc().execute(()->{if(error!=null)result.completeExceptionally(error);else result.complete(JSON.valueToTree(value));}));return result;}
    private static void run(CompletableFuture<?> future,Runnable then){busy=true;future.whenComplete((value,error)->mc().execute(()->{busy=false;try{if(error!=null)throw new CompletionException(error);then.run();}catch(Exception e){fail(e);}}));}
    private static void crops(ServerPlayer p){for(int x=3;x<=5;x++){p.level().setBlock(new BlockPos(x,100,6),Blocks.FARMLAND.defaultBlockState().setValue(BlockStateProperties.MOISTURE,7),2);p.level().setBlock(new BlockPos(x,101,6),Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE,7),2);}p.level().setBlock(new BlockPos(3,100,7),Blocks.WATER.defaultBlockState(),2);}
    private static CompletableFuture<Void> setup(){return server(p->{try{
        var s=p.level().getServer();s.getPlayerList().op(p.nameAndId());WorldActivationRuntime.decide(p.createCommandSourceStack(),true,null);p.setGameMode(playerActor()?GameType.SURVIVAL:GameType.CREATIVE);p.teleportTo(p.level(),.5,101,6.5,Set.of(),-90,20,true);
        for(int x=-7;x<=12;x++)for(int z=-2;z<=18;z++)for(int y=100;y<=106;y++)p.level().setBlock(new BlockPos(x,y,z),y==100?Blocks.STONE.defaultBlockState():Blocks.AIR.defaultBlockState(),2);crops(p);
        agent=MineAgentRuntimeServices.bodies(s).createPersistentAt("技能搭档",p.getUUID(),p.level(),new Vec3(.5,101,playerActor()?15.5:8.5)).agentId();var body=MineAgentRuntimeServices.bodies(s).body(agent).orElseThrow();body.setGameMode(GameType.SURVIVAL);
        for(var actor:List.of(p,body)){actor.getInventory().clearContent();actor.getInventory().setItem(0,new ItemStack(Items.IRON_HOE));actor.getInventory().setItem(1,new ItemStack(Items.WHEAT_SEEDS,48));actor.inventoryMenu.broadcastChanges();}
        conversation=ServerConversations.get(s).store().create(p.getUUID(),agent,UUID.randomUUID(),"持续技能自然对话",false).conversationId();return null;
    }catch(Exception e){throw new CompletionException(e);}});}
    private static CompletableFuture<Void> send(){return server(p->{try{
        operation=UUID.randomUUID();String text=playerActor()?"接管我的身体，照顾眼前这排小麦，成熟就收，收完补种；之后继续等待生长，直到我主动停止。背包里有种子和锄头。不要用指令改方块。":"把我面前这排成熟的小麦照顾好，成熟了就收，收完补种；你自己干，持续等到我叫停。你的背包里有种子和锄头。不要建东西，也不要用指令改方块。";
        ServerConversations.get(p.level().getServer()).write(p,operation,Map.of("kind","send","agentId",agent.toString(),"conversationId",conversation.toString(),"expectedRevision","1","text",text));evidence.add(Map.of("prompt",text,"operation",operation,"conversation",conversation));return null;
    }catch(Exception e){throw new CompletionException(e);}});}
    private static CompletableFuture<Boolean> modelDone(){return server(p->{try{
        var store=ServerConversations.get(p.level().getServer()).store();var context=store.context(p.getUUID(),agent,conversation,null).orElseThrow();if(Set.of("PENDING","GENERATING").contains(context.requestState()))return false;
        evidence.add(context);require(context.operationId().equals(operation)&&context.requestState().equals("COMPLETE"),"MODEL_RESULT_"+context.requestState()+"_"+context.errorCode());var message=store.message(p.getUUID(),agent,conversation,context.assistantMessageId());var text=new StringBuilder();for(int offset=0;offset<message.textLength();){var part=store.chunk(p.getUUID(),agent,conversation,message.messageId(),message.revision(),offset,4096);text.append(part.text());offset+=part.text().length();}reply=text.toString();evidence.add(Map.of("reply",reply));return true;
    }catch(Exception e){throw new CompletionException(e);}});}
    private static CompletableFuture<Boolean> progress(boolean second){return inspect().thenApply(value->{latest=value;for(var row:value.path("skills")){var s=row.path("session");if(s.path("spec").path("kind").asText().equals("FARM")&&s.path("spec").path("actor").asText().equals(playerActor()?"player":"ai")){
        require(!Set.of("FAILED","PAUSED","CANCELLED").contains(s.path("state").asText()),"MODEL_SKILL_NOT_RUNNING_"+s);skill=s.path("spec").path("id").asText();revision=s.path("revision").asLong();long count=s.path("counters").path("planted").asLong();if(count>(second?baseline:0)){evidence.add(value);baseline=count;return true;}}}
        return false;});}
    @SubscribeEvent public static void tick(ClientTickEvent.Post event){if(!Boolean.getBoolean("mineagent.skillModelSmoke")||done)return;try{
        if(mc().player==null||mc().getSingleplayerServer()==null||busy)return;ticks++;if(ticks>18000)throw new IllegalStateException("SKILL_MODEL_TIMEOUT");if(ticks%100==0)Files.writeString(root().resolve("progress.json"),JSON.writeValueAsString(Map.of("phase",phase,"ticks",ticks,"skill",skill,"observed",latest==null?Map.of():latest)));
        if(System.currentTimeMillis()<nextPoll)return;nextPoll=System.currentTimeMillis()+500;
        if(phase==0){if(!started){started=true;org.lwjgl.glfw.GLFW.glfwFocusWindow(mc().getWindow().handle());}run(setup(),()->phase=1);return;}
        if(phase==1){run(send(),()->phase=2);return;}
        if(phase==2){busy=true;modelDone().whenComplete((complete,error)->mc().execute(()->{busy=false;if(error!=null)fail(error);else if(complete)phase=3;}));return;}
        if(phase==3||phase==5){boolean second=phase==5;busy=true;progress(second).whenComplete((complete,error)->mc().execute(()->{busy=false;if(error!=null)fail(error);else if(complete)phase++;}));return;}
        if(phase==4){run(server(p->{crops(p);return null;}),()->phase=5);return;}
        if(phase==6){var args=JSON.createObjectNode().put("id",skill).put("expected_revision",revision).put("action","stop");run(server(p->ConversationAgentTools.execute(p,agent,UUID.randomUUID(),"control_skill",args.toString(),()->true)).thenCompose(Function.identity()),()->phase=7);return;}
        Files.writeString(root().resolve("result.json"),JSON.writeValueAsString(Map.of("status","PASS","actor",playerActor()?"player":"ai","modelCalls","SEE_AUDIT","evidence",evidence)));done=true;mc().stop();
    }catch(Exception e){fail(e);}}
    private static void fail(Throwable failure){if(done)return;done=true;try{Files.writeString(root().resolve("failure.json"),JSON.writeValueAsString(Map.of("phase",phase,"error",failure.toString(),"evidence",evidence,"observed",latest==null?Map.of():latest)));}catch(Exception ignored){}AutonomousBodyClient.stop("MODEL_SMOKE_END");mc().stop();}
    private PersistentSkillModelSmokeClient(){}
}
