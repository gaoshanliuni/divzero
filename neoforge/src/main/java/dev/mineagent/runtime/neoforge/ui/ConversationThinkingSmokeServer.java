package dev.mineagent.runtime.neoforge.ui;
import dev.mineagent.runtime.neoforge.MineAgentRuntimeServices;
import java.util.*;
import java.nio.file.*;
/** One ordinary private chat in an isolated real-provider profile; no world/host tool request. */
public final class ConversationThinkingSmokeServer {
 public static volatile boolean ready,complete,sawStreaming;public static volatile UUID agent,conversation;public static volatile String failure="",thinking="",answer="";
 public static boolean saved(){return System.getProperty("mineagent.conversationAgentScenario","").equals("thinking_saved");}
 public static boolean active(){return Boolean.getBoolean("mineagent.conversationAgentSmoke")&&Boolean.getBoolean("mineagent.conversationAgentReal")&&Set.of("thinking","thinking_saved").contains(System.getProperty("mineagent.conversationAgentScenario",""));}
 public static void tick(net.minecraft.server.MinecraftServer s){if(complete||!failure.isEmpty())return;var p=s.getPlayerList().getPlayers().stream().filter(v->!(v instanceof dev.mineagent.runtime.neoforge.body.MineAgentPlayer)).findFirst().orElse(null);if(p==null)return;
  try{if(!ready){s.getPlayerList().op(p.nameAndId());agent=MineAgentRuntimeServices.bodies(s).createPersistentAt("思考助手",p.getUUID(),p.level(),p.position().add(3,0,0)).agentId();ready=true;return;}
   if(saved()){
    String raw=Files.readString(s.getServerDirectory().resolve("runtime-item-source.json"));var data=new com.fasterxml.jackson.databind.ObjectMapper().readTree(raw);thinking=data.path("thinking").asText();answer=data.path("answer").asText();if(thinking.isEmpty()||answer.isEmpty()||!data.path("context").path("modelReceipt").path("requestedModel").asText().equals("deepseek-flash"))throw new IllegalStateException("SAVED_THINKING_SOURCE_INVALID");
    var store=ServerConversations.get(s).store();var c=store.create(p.getUUID(),agent,UUID.randomUUID(),"真实思考历史复验");conversation=c.conversationId();var turn=store.begin(p.getUUID(),agent,conversation,UUID.randomUUID(),c.revision(),"已保存真实DeepSeek结果的只读历史复验，不请求模型",0);store.thinkingDelta(turn.operationId(),thinking,false);store.finish(turn.operationId(),"COMPLETE",answer,"");
    Files.writeString(Files.createDirectories(s.getServerDirectory().resolve("thinking-smoke")).resolve("source.json"),new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(Map.of("sourceSha256",dev.mineagent.runtime.core.packages.RuntimePackageCanonicalizer.sha256(raw),"thinkingSha256",dev.mineagent.runtime.core.packages.RuntimePackageCanonicalizer.sha256(thinking),"realProviderCalls",0)));complete=true;return;
   }
   var store=ServerConversations.get(s).store();var list=store.list(p.getUUID(),agent,"ALL","",0,20).conversations();if(list.isEmpty())return;var c=list.getFirst();conversation=c.conversationId();if(c.messageCount()<2)return;
   var context=store.context(p.getUUID(),agent,conversation,null).orElseThrow();var meta=store.thinking(p.getUUID(),agent,conversation,context.assistantMessageId());
   if(!c.activeOperation().isEmpty()){if(meta.textLength()>0)sawStreaming=true;return;}
   if(!context.requestState().equals("COMPLETE")||context.modelReceipt()==null||!context.modelReceipt().requestedModel().equals("deepseek-flash")||meta.textLength()==0)throw new IllegalStateException("THINKING_REAL_RESPONSE_FAILED:"+context.errorCode());
   var text=new StringBuilder();while(text.length()<meta.textLength())text.append(store.thinkingChunk(p.getUUID(),agent,conversation,meta.messageId(),meta.revision(),text.length(),4096).text());thinking=text.toString();
   var m=store.message(p.getUUID(),agent,conversation,meta.messageId());text.setLength(0);while(text.length()<m.textLength())text.append(store.chunk(p.getUUID(),agent,conversation,m.messageId(),m.revision(),text.length(),4096).text());answer=text.toString();
   Files.writeString(Files.createDirectories(s.getServerDirectory().resolve("thinking-smoke")).resolve("server.json"),new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(Map.of("context",context,"thinkingMetadata",meta,"thinking",thinking,"answer",answer,"persistedWhileStreaming",sawStreaming)));complete=true;
  }catch(Exception e){failure=e.toString();}
 }
}
