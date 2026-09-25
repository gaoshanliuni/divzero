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
                a=MineAgentRuntimeServices.bodies(server).createPersistentAt("显示甲",player.getUUID(),player.level(),player.position().add(2,0,0)).agentId();
                b=MineAgentRuntimeServices.bodies(server).createPersistentAt("显示乙",player.getUUID(),player.level(),player.position().add(-2,0,0)).agentId();
                runtime.submitNative(player,a,"这是新的只读文本测试，不调用任何工具。请逐行列出1到10000，每行带一句中文解释；不要更改世界。",true);
                ca=store.nativeConversation(player.getUUID(),a).conversationId();firstOperation=UUID.fromString(store.get(player.getUUID(),a,ca).activeOperation());
                runtime.submitNative(player,a,"排队后的新文本任务，不调用任何工具：逐行列出1到10000，每行解释一次奇偶性。不更改世界。",true);
                runtime.submitNative(player,b,"只读聊天测试，不调用工具、不修改世界。写一篇约600汉字的中文短文，解释在Minecraft里规划房屋时如何考虑采光和动线，可含😀，分段输出，最后原样写DISPLAY_STREAM_END。",true);
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
            if(phase.equals("PENDING_BUTTON")&&clientCommandSent){
                var messages=store.messages(player.getUUID(),a,ca,0,20).messages();if(messages.size()!=4)throw new IllegalStateException("QUEUE_DUPLICATED_MESSAGES");
                if(!messages.get(3).status().equals("CANCELLED"))return;
                var context=store.context(player.getUUID(),b,cb,null).orElseThrow();if(Set.of("PENDING","GENERATING").contains(context.requestState()))return;
                if(!context.requestState().equals("COMPLETE")||context.modelReceipt()==null||!context.modelReceipt().requestedModel().equals("deepseek-flash"))throw new IllegalStateException("OTHER_AI_NOT_COMPLETE");
                var msg=store.message(player.getUUID(),b,cb,context.assistantMessageId());var thought=store.thinking(player.getUUID(),b,cb,msg.messageId());
                var body=new StringBuilder();var thinking=new StringBuilder();while(body.length()<msg.textLength())body.append(store.chunk(player.getUUID(),b,cb,msg.messageId(),msg.revision(),body.length(),4096).text());while(thinking.length()<thought.textLength())thinking.append(store.thinkingChunk(player.getUUID(),b,cb,msg.messageId(),thought.revision(),thinking.length(),4096).text());
                expectedBody=body.toString();expectedThinking=thinking.toString();if(!expectedBody.contains("DISPLAY_STREAM_END")||expectedThinking.isEmpty())throw new IllegalStateException("REAL_STREAM_MISSING");
                var root=Files.createDirectories(server.getServerDirectory().resolve("native-delivery-smoke"));Files.writeString(root.resolve("server.json"),JSON.writeValueAsString(Map.of("status","PASSED","lostSubscriptionInjected",dropped,"oldButtonLeftNewRequestRunning",verifiedOldButton,"queuedRequestCancelledWithoutResend",true,"aMessages",messages,"bContext",context,"body",expectedBody,"thinking",expectedThinking)));complete=true;phase="DRAIN";
            }
        }catch(Exception e){failure=e.toString();try{Files.writeString(Files.createDirectories(server.getServerDirectory().resolve("native-delivery-smoke")).resolve("server-failure.json"),JSON.writeValueAsString(Map.of("phase",phase,"error",failure)));}catch(Exception ignored){}}
    }
    private NativeDeliverySmokeServer(){}
}
