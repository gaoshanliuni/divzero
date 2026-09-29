package dev.mineagent.runtime.neoforge.client.nativeui;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.mineagent.runtime.neoforge.MineAgentRuntimeServices;
import dev.mineagent.runtime.neoforge.ui.*;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.regex.*;

/** Real-model natural-language requests; the fixture never supplies memory entries or UI definitions. */
@EventBusSubscriber(modid="mineagent_runtime",value=Dist.CLIENT)
public final class NativeTenModelSmokeClient {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final String[] PROMPTS={
        "请记住，我现在站的这个地方就是我的家。",
        "我喜欢钻石胸甲和铁裤子搭配。",
        "我想回家。先告诉我家的维度和地址，不要移动。",
        "给我来一套喜欢的装备。",
        "请在原版熔炉菜单上方附加一个实时剩余时间指示器，跟随熔炉实际进度变化。保留原菜单和物品槽。",
        "请在每个生物头顶实时显示名字和悬浮血条，跟着生物移动和受伤更新，平时不抢鼠标。保留前面的熔炉附加界面。",
        "在游戏内创建一个仿Windows桌面，有便签和文件两个窗口，都可以拖动、调整大小、最小化和恢复，并带底部任务栏。用原生界面实现，先不需要操作真实电脑文件。"
    };
    private static final List<Object> evidence=new ArrayList<>();private static UUID agent,conversation,operation;private static int scenario,ticks,phase;private static boolean requested,trusted,busy,done,authority,verifyOnly;private static long nextPoll;private static String reply="";private static List<Double> homePosition=List.of();
    private static Minecraft mc(){return Minecraft.getInstance();}
    private static boolean healthReview(){return Boolean.getBoolean("mineagent.nativeTenModelReviewHealth");}
    private static Path output()throws Exception{return Files.createDirectories(mc().gameDirectory.toPath().resolve(healthReview()?"native-ten-health-review":"native-ten-model"));}
    private static void require(boolean value,String code){if(!value)throw new IllegalStateException(code);}
    private static <T> CompletableFuture<T> server(Function<ServerPlayer,T> work){var future=new CompletableFuture<T>();var s=mc().getSingleplayerServer();var owner=mc().player.getUUID();s.submit(()->work.apply(s.getPlayerList().getPlayer(owner))).whenComplete((result,error)->mc().execute(()->{if(error!=null)future.completeExceptionally(error);else future.complete(result);}));return future;}
    private static CompletableFuture<JsonNode> tool(String name,ObjectNode args){var result=new CompletableFuture<JsonNode>();server(p->ConversationAgentTools.execute(p,agent,UUID.randomUUID(),name,args.toString(),()->true)).thenCompose(Function.identity()).whenComplete((value,error)->mc().execute(()->{if(error!=null)result.completeExceptionally(error);else result.complete(JSON.valueToTree(value));}));return result;}
    private static void run(CompletableFuture<?> work,Runnable after){busy=true;work.whenComplete((value,error)->mc().execute(()->{busy=false;try{if(error!=null)throw new CompletionException(error);after.run();}catch(Exception failure){fail(failure);}}));}
    private static boolean home(String text){var matcher=Pattern.compile("-?\\d+(?:\\.\\d+)?").matcher(text);var values=new ArrayList<Double>();while(matcher.find())values.add(Double.parseDouble(matcher.group()));for(int i=0;i+2<values.size();i++)if(homePosition.size()==3&&Math.abs(values.get(i)-homePosition.get(0))<1.1&&Math.abs(values.get(i+1)-homePosition.get(1))<1.1&&Math.abs(values.get(i+2)-homePosition.get(2))<1.1)return true;return false;}
    private static CompletableFuture<Void> prepare(){return server(p->{
        try{
            var s=p.level().getServer();s.getPlayerList().op(p.nameAndId());p.setGameMode(net.minecraft.world.level.GameType.CREATIVE);if(!authority){dev.mineagent.runtime.neoforge.WorldActivationRuntime.decide(p.createCommandSourceStack(),true,null);authority=true;}if(agent==null){p.getInventory().clearContent();p.teleportTo(p.level(),137.5,111,-79.5,Set.of(),0,0,true);agent=MineAgentRuntimeServices.bodies(s).createPersistentAt("记忆与界面搭档",p.getUUID(),p.level(),p.position().add(2,0,0)).agentId();}
            if(scenario==2)p.teleportTo(p.level(),150.5,111,-65.5,Set.of(),0,0,true);
            if(scenario==4){p.teleportTo(p.level(),.5,101,3.5,Set.of(),180,0,true);for(int x=-5;x<=5;x++)for(int z=-5;z<=5;z++)for(int y=100;y<=106;y++)p.level().setBlock(new BlockPos(x,y,z),y==100?Blocks.SMOOTH_STONE.defaultBlockState():Blocks.AIR.defaultBlockState(),2);p.level().setBlock(new BlockPos(2,101,0),Blocks.FURNACE.defaultBlockState(),3);var furnace=(net.minecraft.world.level.block.entity.FurnaceBlockEntity)p.level().getBlockEntity(new BlockPos(2,101,0));furnace.setItem(0,new ItemStack(Items.IRON_ORE,64));furnace.setItem(1,new ItemStack(Items.COAL,64));p.openMenu(furnace);}
            if(scenario==5){p.closeContainer();var cow=net.minecraft.world.entity.EntityType.COW.create(p.level(),net.minecraft.world.entity.EntitySpawnReason.COMMAND);cow.setPos(.5,101,1);cow.setNoAi(true);cow.setPersistenceRequired();p.level().addFreshEntity(cow);}
            if(!verifyOnly)conversation=ServerConversations.get(s).store().create(p.getUUID(),agent,UUID.randomUUID(),"自然语言场景 "+scenario,false).conversationId();return null;
        }catch(Exception failure){throw new CompletionException(failure);}
    });}
    private static CompletableFuture<?> send(){return server(p->{try{if(scenario==0)homePosition=List.of(p.getX(),p.getY(),p.getZ());operation=UUID.randomUUID();var args=Map.of("kind","send","agentId",agent.toString(),"conversationId",conversation.toString(),"expectedRevision","1","text",PROMPTS[scenario]);var result=ServerConversations.get(p.level().getServer()).write(p,operation,args);evidence.add(Map.of("scenario",scenario,"prompt",PROMPTS[scenario],"operation",operation,"conversation",conversation,"homeAtSend",homePosition));return result;}catch(Exception failure){throw new CompletionException(failure);}});}
    private static CompletableFuture<Boolean> completed(){return server(p->{try{
        var runtime=ServerConversations.get(p.level().getServer());var store=runtime.store();var context=store.context(p.getUUID(),agent,conversation,null).orElseThrow();require(context.operationId().equals(operation),"MODEL_OPERATION_CHANGED");if(Set.of("PENDING","GENERATING").contains(context.requestState()))return false;
        evidence.add(Map.of("scenario",scenario,"context",context));require(context.requestState().equals("COMPLETE"),"MODEL_NOT_COMPLETE_"+context.errorCode());var message=store.message(p.getUUID(),agent,conversation,context.assistantMessageId());var text=new StringBuilder();for(int offset=0;offset<message.textLength();){var chunk=store.chunk(p.getUUID(),agent,conversation,message.messageId(),message.revision(),offset,4096);text.append(chunk.text());offset+=chunk.text().length();}reply=text.toString();evidence.add(Map.of("scenario",scenario,"reply",reply));return true;
    }catch(Exception failure){throw new CompletionException(failure);}});}
    private static CompletableFuture<?> verify(){
        if(scenario==0||scenario==1)return tool("inspect_memories",JSON.createObjectNode().put("query","").put("offset",0)).thenAccept(data->{evidence.add(Map.of("scenario",scenario,"memories",data));boolean found=false;for(var entry:data.path("entries")){String value=entry.path("value").asText();if(scenario==0&&entry.path("kind").asText().equals("FACT")&&home(value)&&(value.contains("overworld")||value.contains("主世界"))){require(!value.contains("2024"),"MEMORY_INVENTED_DATE");found=true;}if(scenario==1&&entry.path("kind").asText().equals("PREFERENCE")&&(value.contains("diamond_chestplate")||value.contains("钻石胸甲"))&&(value.contains("iron_leggings")||value.contains("铁裤")||value.contains("铁护腿")))found=true;}require(found,scenario==0?"HOME_NOT_AUTOMATICALLY_STORED":"PREFERENCE_NOT_AUTOMATICALLY_STORED");});
        if(scenario==2){require(home(reply)&&(reply.contains("overworld")||reply.contains("主世界")),"NEW_CONVERSATION_DID_NOT_RECALL_HOME");return CompletableFuture.completedFuture(null);}
        if(scenario==3)return server(p->{require(p.getInventory().countItem(Items.DIAMOND_CHESTPLATE)==1&&p.getInventory().countItem(Items.IRON_LEGGINGS)==1,"PREFERRED_EQUIPMENT_NOT_GIVEN");evidence.add(Map.of("diamondChestplates",p.getInventory().countItem(Items.DIAMOND_CHESTPLATE),"ironLeggings",p.getInventory().countItem(Items.IRON_LEGGINGS)));return null;});
        String surface=scenario==4?"SCREEN_OVERLAY":scenario==5?"ENTITY_HUD":"SCREEN";
        return tool("inspect_native_ui",JSON.createObjectNode()).thenCompose(data->{String id="";for(var view:data.path("views"))if(view.path("surface").asText().equals(surface)&&view.path("visible").asBoolean())id=view.path("id").asText();require(!id.isBlank(),"MODEL_UI_SURFACE_MISSING_"+surface);return tool("inspect_native_ui",JSON.createObjectNode().put("id",id));}).thenAccept(data->{try{
            var saved=JSON.readTree(data.path("saved").path("source").asText());evidence.add(Map.of("scenario",scenario,"interface",saved,"client",data.path("client")));var view=data.path("client").path("views").get(0);require(view.path("error").asText().isBlank()&&view.path("attachment").path("error").asText().isBlank(),"MODEL_UI_RUNTIME_ERROR");
            if(scenario==4){NativeInterfacesClient.smokeRejectOldFeed(saved.path("id").asText());require(Boolean.TRUE.equals(view.path("attachment").path("attached").asBoolean())&&view.path("attachment").path("painted").asInt()>0,"MODEL_FURNACE_NOT_ATTACHED");boolean live=false;for(var source:saved.path("sources"))if(source.path("kind").asText().equals("menu"))live=true;require(live,"MODEL_TIMER_NOT_LIVE");}
            if(scenario==5){require(view.path("attachment").path("entityCopies").asInt()>0&&mc().screen==null,"MODEL_HEALTH_NOT_PASSIVE_OR_VISIBLE");for(var bounds:view.path("attachment").path("entityBounds"))require(bounds.path("width").asDouble()>=80&&bounds.path("height").asDouble()<100,"MODEL_HEALTH_LAYOUT_COLLAPSED");}
            if(scenario==6)require(windows(saved.path("root"))>=2,"MODEL_DESKTOP_USES_NO_REAL_WINDOWS");
        }catch(Exception failure){throw new CompletionException(failure);}});
    }
    private static int windows(JsonNode node){int total=node.path("type").asText().equals("window")?1:0;for(var child:node.path("children"))total+=windows(child);return total;}
    private static void screenshot(){try{var path=output().resolve("case-"+scenario+".png");net.minecraft.client.Screenshot.takeScreenshot(mc().getMainRenderTarget(),image->{try(image){image.writeToFile(path);}catch(Exception failure){fail(failure);}});}catch(Exception failure){fail(failure);}}
    @SubscribeEvent public static void tick(ClientTickEvent.Post event){
        if(!Boolean.getBoolean("mineagent.nativeTenModelSmoke")||done)return;
        try{
            if(++ticks>36000)throw new IllegalStateException("TEN_MODEL_TIMEOUT");if(ticks%100==0)Files.writeString(output().resolve("progress.json"),JSON.writeValueAsString(Map.of("scenario",scenario,"phase",phase,"busy",busy,"ticks",ticks,"operation",operation==null?"":operation.toString())));
            if(mc().player==null||mc().getSingleplayerServer()==null||busy)return;
            if(!requested){requested=true;net.neoforged.neoforge.client.network.ClientPacketDistributor.sendToServer(new dev.mineagent.runtime.neoforge.network.MineAgentPayloads.PanelRequest());return;}
            if(!trusted){var values=dev.mineagent.runtime.neoforge.network.PanelSnapshotInbox.snapshot().values();var fingerprint=values.getOrDefault("security.identityFingerprint","");if(fingerprint.isBlank()||!dev.mineagent.runtime.neoforge.network.PanelSnapshotInbox.signatureValid())return;new dev.mineagent.runtime.client.trust.ServerTrustStore(mc().gameDirectory.toPath().resolve("config/mineagent-trusted-servers.properties")).confirm("local-integrated",fingerprint,Base64.getDecoder().decode(values.get("security.identityPublicKey")));trusted=true;}
            if(agent==null&&healthReview()){var saved=JSON.readTree(Files.readString(mc().gameDirectory.toPath().resolve("native-ten-model/checkpoint.json")));agent=UUID.fromString(saved.path("agent").asText());scenario=5;verifyOnly=true;}
            if(agent==null&&Boolean.getBoolean("mineagent.nativeTenModelResume")){var saved=JSON.readTree(Files.readString(output().resolve("checkpoint.json")));agent=UUID.fromString(saved.path("agent").asText());scenario=saved.path("nextScenario").asInt();if(saved.has("homePosition"))homePosition=JSON.convertValue(saved.get("homePosition"),new com.fasterxml.jackson.core.type.TypeReference<List<Double>>(){});for(var item:saved.path("evidence"))evidence.add(item);
                if(Boolean.getBoolean("mineagent.nativeTenModelVerifySaved")){var failed=JSON.readTree(Files.readString(output().resolve("failure.json")));require(failed.path("phase").asInt()==3&&failed.path("scenario").asInt()==scenario,"RESUME_NOT_COMPLETED_VERIFICATION");evidence.clear();for(var item:failed.path("evidence")){evidence.add(item);if(item.path("scenario").asInt(-1)==scenario&&item.has("reply"))reply=item.path("reply").asText();}verifyOnly=true;}
            }
            if(scenario>=PROMPTS.length){Files.writeString(output().resolve("result.json"),JSON.writeValueAsString(Map.of("status","PASS","requests",PROMPTS.length,"modelCalls","SEE_PROVIDER_AUDIT","evidence",evidence)));done=true;mc().stop();return;}
            if(phase==0){run(prepare(),()->{if(healthReview())mc().setScreen(null);phase=verifyOnly?3:1;nextPoll=System.currentTimeMillis()+(verifyOnly?5000:1500);try{Files.writeString(output().resolve("checkpoint.json"),JSON.writeValueAsString(Map.of("agent",agent,"nextScenario",scenario,"homePosition",homePosition,"evidence",evidence)));}catch(Exception error){throw new CompletionException(error);}});return;}
            if(System.currentTimeMillis()<nextPoll)return;
            if(phase==1){run(send(),()->{phase=2;nextPoll=System.currentTimeMillis()+1500;});return;}
            if(phase==2){busy=true;completed().whenComplete((complete,error)->mc().execute(()->{busy=false;if(error!=null){fail(error);return;}nextPoll=System.currentTimeMillis()+1500;if(complete)phase=3;}));return;}
            if(phase==3){run(verify(),()->{screenshot();if(healthReview()){try{Files.writeString(output().resolve("result.json"),JSON.writeValueAsString(Map.of("status","PASS","modelCalls",0,"evidence",evidence)));}catch(Exception error){throw new CompletionException(error);}done=true;mc().stop();return;}verifyOnly=false;scenario++;phase=0;try{Files.writeString(output().resolve("checkpoint.json"),JSON.writeValueAsString(Map.of("agent",agent,"nextScenario",scenario,"homePosition",homePosition,"evidence",evidence)));}catch(Exception error){throw new CompletionException(error);}});}
        }catch(Exception failure){fail(failure);}
    }
    private static void fail(Throwable error){if(done)return;done=true;try{var previous=output().resolve("failure.json");if(Files.exists(previous))Files.copy(previous,output().resolve("failure-"+System.currentTimeMillis()+".json"));Files.writeString(previous,JSON.writeValueAsString(Map.of("scenario",scenario,"phase",phase,"operation",operation==null?"":operation.toString(),"error",error.toString(),"evidence",evidence)));}catch(Exception ignored){}mc().stop();}
    private NativeTenModelSmokeClient(){}
}
