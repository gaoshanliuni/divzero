package dev.mineagent.runtime.neoforge.ui;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mineagent.runtime.core.conversation.*;
import dev.mineagent.runtime.neoforge.MineAgentRuntimeServices;
import net.minecraft.server.level.*;
import java.nio.file.*;
import java.util.*;

/** Fixed native UI inputs; only the explicit manual reply and resumed summary use the real provider. */
public final class ConversationUxSmokeServer {
    private static final ObjectMapper JSON=new ObjectMapper();
    public static final String THOUGHT="FIXTURE_ONLY 思考展示样本：先核对材料，尚未执行任何世界修改。";
    public static final String MANUAL="这是玩家手动发言：请只用一句话确认收到了这条协作会话消息，不调用任何工具。";
    private static UUID recipient,conversation,message;
    private static void guard(){if(!Boolean.getBoolean("mineagent.skillSmoke"))throw new SecurityException("FIXTURE_DISABLED");}
    private static Path root(ServerPlayer p)throws Exception{Path root=p.level().getServer().getServerDirectory().resolve("conversation-feedback-smoke");Files.createDirectories(root);return root;}
    public static Map<String,Object> prepare(ServerPlayer p,UUID sender)throws Exception{
        guard();var server=p.level().getServer();var config=MineAgentRuntimeServices.config(server);if(!config.apply(new dev.mineagent.runtime.api.config.ConfigPatch(config.revision(),Map.of("voice.output.enabled","true")),true).accepted())throw new IllegalStateException("FIXTURE_VOICE_CONFIG");
        var definition=MineAgentRuntimeServices.bodies(server).createPersistentAt("采木搭档",p.getUUID(),p.level(),p.position().add(6,0,4));recipient=definition.agentId();
        var runtime=ServerConversations.get(server);var store=runtime.store();var peer=store.peerThread(p.getUUID(),sender,recipient,"持续技能搭档","采木搭档");conversation=peer.conversationId();
        var c=store.get(p.getUUID(),recipient,conversation);c=store.change(p.getUUID(),recipient,conversation,UUID.randomUUID(),c.revision(),"rename","持续技能搭档 → 采木搭档");
        var turn=store.begin(p.getUUID(),recipient,conversation,UUID.randomUUID(),c.revision(),"这是界面联验消息，尚未派发砍树动作。",0,null,null,null,new ConversationStore.Author("AI",sender,"持续技能搭档"),new ConversationStore.Author("AI",recipient,"采木搭档"));
        store.thinkingDelta(turn.operationId(),THOUGHT,false);store.finish(turn.operationId(),"COMPLETE","DivZero 云端朗读与协作会话测试。","");message=turn.assistantMessageId();
        var result=Map.<String,Object>of("sender",sender,"recipient",recipient,"conversation",conversation,"message",message,"fixedInput",true,"modelCallsAtSetup",0);Files.writeString(root(p).resolve("ui-fixture.json"),JSON.writeValueAsString(result));return result;
    }
    public static Map<String,Object> manualReply(ServerPlayer p)throws Exception{
        guard();var store=ServerConversations.get(p.level().getServer()).store();var page=store.messages(p.getUUID(),recipient,conversation,0,20);if(page.messages().size()<4)return Map.of("complete",false);
        var user=page.messages().get(page.messages().size()-2);var reply=page.messages().getLast();if(Set.of("PENDING","GENERATING").contains(reply.status()))return Map.of("complete",false);
        if(!reply.status().equals("COMPLETE")||reply.textLength()==0)throw new IllegalStateException("MANUAL_PEER_REPLY_FAILED_"+reply.errorCode());
        var author=store.author(p.getUUID(),recipient,conversation,user);if(!author.kind().equals("PLAYER")||!author.senderId().equals(p.getUUID()))throw new IllegalStateException("MANUAL_PEER_SENDER_IMPERSONATED");
        if(!store.chunk(p.getUUID(),recipient,conversation,user.messageId(),user.revision(),0,4096).text().equals(MANUAL))throw new IllegalStateException("MANUAL_PEER_TEXT_CHANGED");
        var context=store.context(p.getUUID(),recipient,conversation,reply.messageId()).orElseThrow();if(context.modelReceipt()==null)throw new IllegalStateException("REAL_PEER_MODEL_RECEIPT_MISSING");
        var result=Map.<String,Object>of("complete",true,"actualSender",author,"recipient",recipient,"messageCount",page.messages().size(),"modelReceipt",context.modelReceipt());Files.writeString(root(p).resolve("manual-peer-result.json"),JSON.writeValueAsString(result));return result;
    }
    private static ConversationSummaryStore summaries(ServerConversations runtime)throws Exception{var field=ServerConversations.class.getDeclaredField("summaries");field.setAccessible(true);return (ConversationSummaryStore)field.get(runtime);}
    public static Map<String,Object> seedSummary(ServerPlayer p,UUID agent)throws Exception{
        guard();var runtime=ServerConversations.get(p.level().getServer());var store=runtime.store();var c=store.create(p.getUUID(),agent,UUID.randomUUID(),"摘要恢复固定输入");
        String constraint="以后所有建筑都必须使用石砖，不能用砂岩；北门尚未完成，需要继续修建。";
        for(int i=0;i<6;i++){var turn=store.begin(p.getUUID(),agent,c.conversationId(),UUID.randomUUID(),c.revision(),constraint+"这一条是第"+i+"次记录，旧库存不能当作当前库存。".repeat(35),0);store.finish(turn.operationId(),"COMPLETE","好的。","");}
        var pending=summaries(runtime).prepare(store,p.getUUID(),agent,c.conversationId(),UUID.randomUUID(),0,12,16384,8192,null);
        var data=Map.<String,Object>of("agent",agent,"conversation",c.conversationId(),"pendingSummary",pending.summaryId(),"target",12,"constraint",constraint,"modelCalls",0);Files.writeString(root(p).resolve("summary-recovery.json"),JSON.writeValueAsString(data));return data;
    }
    public static UUID summaryAgent(ServerPlayer p)throws Exception{return UUID.fromString(JSON.readTree(Files.readString(root(p).resolve("summary-recovery.json"))).path("agent").asText());}
    public static Map<String,Object> recoveredSummary(ServerPlayer p)throws Exception{
        guard();var data=JSON.readTree(Files.readString(root(p).resolve("summary-recovery.json")));UUID agent=UUID.fromString(data.path("agent").asText()),conversation=UUID.fromString(data.path("conversation").asText());
        var runtime=ServerConversations.get(p.level().getServer());var store=runtime.store();var summaries=summaries(runtime);var old=summaries.get(p.getUUID(),agent,conversation,UUID.fromString(data.path("pendingSummary").asText()));
        if(!old.state().equals("INTERRUPTED"))throw new IllegalStateException("OLD_SUMMARY_NOT_INTERRUPTED");
        var latest=summaries.latest(store,p.getUUID(),agent,conversation,12,8192).orElse(null);if(latest==null||latest.endOffset()!=0||latest.endSequence()<=12)return Map.of("complete",false,"oldState",old.state());
        if(!latest.text().contains("石砖")||!latest.text().contains("北门"))throw new IllegalStateException("USER_CONSTRAINT_LOST_IN_REAL_SUMMARY");
        var flights=ServerConversations.class.getDeclaredField("flights");flights.setAccessible(true);if(!((Map<?,?>)flights.get(runtime)).isEmpty())throw new IllegalStateException("RECOVERY_REQUIRED_MAIN_CHAT");
        if(latest.modelReceipt()==null||latest.requestId().equals(old.requestId()))throw new IllegalStateException("SUMMARY_REPLAYED_OLD_ATTEMPT");
        var result=Map.<String,Object>of("complete",true,"oldSummaryState",old.state(),"newSummary",latest.summaryId(),"newRequest",latest.requestId(),"summary",latest.text(),"realProvider",latest.modelReceipt(),"activeMainChats",0,"originalMessages",store.get(p.getUUID(),agent,conversation).messageCount());Files.writeString(root(p).resolve("summary-recovered.json"),JSON.writeValueAsString(result));return result;
    }
    private ConversationUxSmokeServer(){}
}
