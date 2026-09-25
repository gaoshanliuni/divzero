package dev.mineagent.runtime.neoforge.ui;

import dev.mineagent.runtime.neoforge.*;
import dev.mineagent.runtime.neoforge.body.MineAgentPlayer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import java.util.*;
import java.nio.file.*;

/** Isolated real-provider regression. Explicitly injects one lost delivery subscription, not a fake model response. */
@EventBusSubscriber(modid="mineagent_runtime")
public final class NativeDeliverySmokeServer {
    public static boolean plain(){return Boolean.getBoolean("mineagent.nativeStreamPlainSmoke");}
    public static volatile String largeTail="";public static volatile int largeToolChars;public static volatile boolean modelSetFull;
    public static void observe(String tool,Map<String,Object> result){if(!enabled())return;if(tool.equals("inspect_player"))try{largeToolChars=Math.max(largeToolChars,JSON.writeValueAsString(result).length());}catch(Exception ignored){}if(tool.equals("set_chat_messages")&&"APPLIED".equals(result.get("status"))&&"full".equals(result.get("thinking")))modelSetFull=true;}
    public static boolean enabled(){return Boolean.getBoolean("mineagent.nativeDeliverySmoke");}
    public static volatile String phase="BOOT",failure="",expectedBody="",expectedThinking="";
    public static volatile boolean complete,activeButtonReady,pendingButtonReady,clientCommandSent;
    public static volatile UUID a,b,firstOperation,secondOperation;
    private static UUID ca,cb;private static int started,phaseTick;private static boolean dropped,verifiedOldButton;
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON=new com.fasterxml.jackson.databind.ObjectMapper();
    @SubscribeEvent public static void tick(ServerTickEvent.Post event){
        if(!enabled()||complete||!failure.isEmpty())return;
        var server=event.getServer();var player=server.getPlayerList().getPlayers().stream().filter(p->!(p instanceof MineAgentPlayer)).findFirst().orElse(null);if(player==null)return;
        try{
            int now=server.getTickCount();if(started==0)started=now;if(now-started>12000)throw new IllegalStateException("NATIVE_DELIVERY_TIMEOUT_"+phase);
            var runtime=ServerConversations.get(server);var store=runtime.store();
            if(phase.equals("BOOT")){
                server.getPlayerList().op(player.nameAndId());server.getCommands().performPrefixedCommand(player.createCommandSourceStack(),"ai accept");
                if(!plain()){
                    largeTail="LARGE_TOOL_TAIL_"+UUID.randomUUID().toString().substring(0,8);
                    for(int slot=0;slot<16;slot++){
                        var item=new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STONE);
                        String lore="READ_ONLY_ROW_"+slot+":"+"x".repeat(1800)+(slot==15?largeTail:"_ROW_END");
                        item.set(net.minecraft.core.component.DataComponents.LORE,new net.minecraft.world.item.component.ItemLore(List.of(net.minecraft.network.chat.Component.literal(lore))));
                        player.getInventory().setItem(slot,item);
                    }player.inventoryMenu.broadcastChanges();
                }
                a=MineAgentRuntimeServices.bodies(server).createPersistentAt("显示甲",player.getUUID(),player.level(),player.position().add(2,0,0)).agentId();
                b=MineAgentRuntimeServices.bodies(server).createPersistentAt("显示乙",player.getUUID(),player.level(),player.position().add(-2,0,0)).agentId();
                if(plain()){
                    ServerChatSettings.set(player,b,JSON.createObjectNode().put("thinking_depth","off"));
                    runtime.submitNative(player,b,"不调用工具、不修改世界。请用约500汉字说明Minecraft里房屋采光，可以包含😀，最后原样写DISPLAY_STREAM_END。",true);
                    cb=store.nativeConversation(player.getUUID(),b).conversationId();phase="PLAIN";return;
                }
                runtime.submitNative(player,a,"这是新的只读文本测试，不调用任何工具。请逐行列出1到10000，每行带一句中文解释；不要更改世界。",true);
                ca=store.nativeConversation(player.getUUID(),a).conversationId();firstOperation=UUID.fromString(store.get(player.getUUID(),a,ca).activeOperation());
                runtime.submitNative(player,a,"排队后的新文本任务，不调用任何工具：逐行列出1到10000，每行解释一次奇偶性。不更改世界。",true);
                runtime.submitNative(player,b,"这是新测试。先调用inspect_player(section=inventory,offset=0)读取玩家前16个背包槽（只读，不修改或丢出物品），把第15槽Lore末尾的LARGE_TOOL_TAIL_开头的完整标记写入回复，不复述中间大量x。然后set_chat_messages将我的思考显示thinking设为full（不是thinking_depth），核对回执。最后写约600汉字解释Minecraft里房屋采光和动线，含😀，结尾原样写DISPLAY_STREAM_END。不要执行其它游戏或电脑操作。",true);
                cb=store.nativeConversation(player.getUUID(),b).conversationId();phase="STREAM";return;
            }
            if(phase.equals("STREAM")&&activeButtonReady&&pendingButtonReady){
                var ctx=store.context(player.getUUID(),a,ca,null).orElseThrow();
                if(store.thinking(player.getUUID(),a,ca,ctx.assistantMessageId()).textLength()>0){
                    var field=ServerConversations.class.getDeclaredField("nativeReplies");field.setAccessible(true);
                    dropped=((Map<?,?>)field.get(runtime)).remove(firstOperation)!=null;if(!dropped)throw new IllegalStateException("FAULT_INJECTION_TARGET_MISSING");
                    phase="INTERRUPT_A";clientCommandSent=false;
                }
            }
            if(phase.equals("INTERRUPT_A")&&clientCommandSent){
                var c=store.get(player.getUUID(),a,ca);if(!c.activeOperation().isEmpty()&&!c.activeOperation().equals(firstOperation.toString())){
                    var messages=store.messages(player.getUUID(),a,ca,0,20).messages();if(!messages.get(1).status().equals("CANCELLED"))throw new IllegalStateException("ACTIVE_BUTTON_DID_NOT_CANCEL");
                    secondOperation=UUID.fromString(c.activeOperation());phase="OLD_BUTTON";clientCommandSent=false;phaseTick=now;
                }
            }
            if(phase.equals("OLD_BUTTON")&&clientCommandSent&&now-phaseTick>12){
                if(!store.pending(secondOperation))throw new IllegalStateException("STALE_BUTTON_INTERRUPTED_NEW_REQUEST");verifiedOldButton=true;phase="PENDING_BUTTON";clientCommandSent=false;
            }
            if(plain()||phase.equals("PENDING_BUTTON")&&clientCommandSent){
                var messages=plain()?List.<dev.mineagent.runtime.core.conversation.ConversationStore.Message>of():store.messages(player.getUUID(),a,ca,0,20).messages();if(!plain()&&messages.size()!=4)throw new IllegalStateException("QUEUE_DUPLICATED_MESSAGES");
                if(!plain()&&!messages.get(3).status().equals("CANCELLED"))return;
                var context=store.context(player.getUUID(),b,cb,null).orElseThrow();if(Set.of("PENDING","GENERATING").contains(context.requestState()))return;
                if(!context.requestState().equals("COMPLETE")||context.modelReceipt()==null||!context.modelReceipt().requestedModel().equals("deepseek-flash"))throw new IllegalStateException("OTHER_AI_NOT_COMPLETE");
                var msg=store.message(player.getUUID(),b,cb,context.assistantMessageId());var thought=store.thinking(player.getUUID(),b,cb,msg.messageId());
                var body=new StringBuilder();var thinking=new StringBuilder();while(body.length()<msg.textLength())body.append(store.chunk(player.getUUID(),b,cb,msg.messageId(),msg.revision(),body.length(),4096).text());while(thinking.length()<thought.textLength())thinking.append(store.thinkingChunk(player.getUUID(),b,cb,msg.messageId(),thought.revision(),thinking.length(),4096).text());
                expectedBody=body.toString();expectedThinking=thinking.toString();if(!expectedBody.contains("DISPLAY_STREAM_END")||(plain()?!expectedThinking.isEmpty():expectedThinking.isEmpty()))throw new IllegalStateException("REAL_STREAM_MISSING");
                if(!plain()&&(largeToolChars<=24000||!modelSetFull||!expectedBody.contains(largeTail)))throw new IllegalStateException("LARGE_TOOL_OR_MODEL_DISPLAY_SETTING_NOT_VERIFIED");
                var root=Files.createDirectories(server.getServerDirectory().resolve("native-delivery-smoke"));Files.writeString(root.resolve("server.json"),JSON.writeValueAsString(Map.of("status","PASSED","lostSubscriptionInjected",dropped,"oldButtonLeftNewRequestRunning",verifiedOldButton,"queuedRequestCancelledWithoutResend",true,"aMessages",messages,"bContext",context,"body",expectedBody,"thinking",expectedThinking,"largeToolChars",largeToolChars,"modelSetFull",modelSetFull)));complete=true;phase="DRAIN";
            }
        }catch(Exception e){failure=e.toString();try{Files.writeString(Files.createDirectories(server.getServerDirectory().resolve("native-delivery-smoke")).resolve("server-failure.json"),JSON.writeValueAsString(Map.of("phase",phase,"error",failure,"largeToolChars",largeToolChars,"modelSetFull",modelSetFull)));}catch(Exception ignored){}}
    }
    private NativeDeliverySmokeServer(){}
}
