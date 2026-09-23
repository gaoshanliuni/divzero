package dev.mineagent.runtime.neoforge.ui;
import dev.mineagent.runtime.neoforge.MineAgentRuntimeServices;
import dev.mineagent.runtime.core.host.HostCommandRequest;
import net.minecraft.server.MinecraftServer;
import java.util.*;import java.nio.file.*;import java.util.concurrent.*;
/** Real model requests plus a separately labelled local rejection fixture; never fabricates a Provider reply. */
public final class PythonHostSmokeServer {
 public static volatile boolean ready,realComplete,complete,negative,clientReady;public static volatile UUID agent,negativeOperation;public static volatile String failure="";
 private static CompletableFuture<Map<String,Object>> rejected,savedFlight;private static com.fasterxml.jackson.databind.JsonNode savedSource;private static int savedIndex;private static final List<Map<String,Object>> receipts=new ArrayList<>();private static final com.fasterxml.jackson.databind.ObjectMapper JSON=new com.fasterxml.jackson.databind.ObjectMapper();
 public static boolean active(){return Boolean.getBoolean("mineagent.conversationAgentSmoke")&&Boolean.getBoolean("mineagent.conversationAgentReal")&&Set.of("python_host","python_host_saved").contains(System.getProperty("mineagent.conversationAgentScenario",""));}
 public static boolean saved(){return System.getProperty("mineagent.conversationAgentScenario","").equals("python_host_saved");}
 public static void observe(String tool,com.fasterxml.jackson.databind.JsonNode args,Map<String,Object> result){if(active()&&Set.of("python_execute","python_install_packages").contains(tool))receipts.add(Map.of("tool",tool,"arguments",args,"result",result));}
 private static void save(MinecraftServer s,String name,Object value)throws Exception{Files.writeString(Files.createDirectories(s.getServerDirectory().resolve("python-host-smoke")).resolve(name+".json"),JSON.writeValueAsString(value));}
 public static void tick(MinecraftServer s){if(complete||!failure.isEmpty())return;var p=s.getPlayerList().getPlayers().stream().filter(x->!(x instanceof dev.mineagent.runtime.neoforge.body.MineAgentPlayer)).findFirst().orElse(null);if(p==null)return;try{
  if(!ready){s.getPlayerList().op(p.nameAndId());agent=MineAgentRuntimeServices.bodies(s).createPersistentAt("Python助手",p.getUUID(),p.level(),p.position().add(3,0,0)).agentId();ready=true;return;}
  if(!clientReady)return;
  if(!realComplete){if(saved()){
    if(savedSource==null){savedSource=JSON.readTree(Files.readString(s.getServerDirectory().resolve("runtime-item-source.json")));if(!savedSource.path("status").asText().equals("DEEPSEEK_MANAGED_PYTHON_PIP_CHILD_VERIFIED")||savedSource.path("receipts").size()<2)throw new IllegalStateException("PYTHON_SAVED_ARTIFACT_INVALID");}
    if(savedFlight!=null){if(!savedFlight.isDone())return;var result=savedFlight.join();if(!"EXECUTED".equals(result.get("status")))throw new IllegalStateException("PYTHON_SAVED_TOOL_FAILED:"+result);savedFlight=null;savedIndex++;}
    if(savedIndex<savedSource.path("receipts").size()){var row=savedSource.path("receipts").get(savedIndex);String tool=row.path("tool").asText();if(!Set.of("python_execute","python_install_packages").contains(tool))throw new IllegalStateException("PYTHON_SAVED_TOOL_UNSUPPORTED");savedFlight=ConversationAgentTools.execute(p,agent,UUID.randomUUID(),tool,JSON.writeValueAsString(row.path("arguments")),()->true);return;}
   }else{var store=ServerConversations.get(s).store();var list=store.list(p.getUUID(),agent,"ALL","",0,20).conversations();if(list.isEmpty())return;var c=list.getFirst();if(c.messageCount()<2||!c.activeOperation().isEmpty())return;var context=store.context(p.getUUID(),agent,c.conversationId(),null).orElseThrow();save(s,"conversation",context);save(s,"receipts",receipts);
   if(!context.requestState().equals("COMPLETE")||context.modelReceipt()==null||!context.modelReceipt().requestedModel().equals("deepseek-flash"))throw new IllegalStateException("PYTHON_REAL_CHAT_FAILED:"+context.errorCode());}
   for(String tool:List.of("python_install_packages","python_execute"))if(receipts.stream().noneMatch(r->r.get("tool").equals(tool)&&((Map<?,?>)r.get("result")).get("status").equals("EXECUTED")))throw new IllegalStateException("PYTHON_REAL_TOOL_MISSING:"+tool);
   Path root=s.getServerDirectory().resolve("mineagent-host").toRealPath();var proof=JSON.readTree(Files.readString(root.resolve("workspace/python-host-proof.json")));
   if(!proof.path("colorama_version").asText().equals("0.4.6")||!proof.path("child_stdout").asText().contains("PYTHON_CHILD_OK")||!Path.of(proof.path("python").asText()).toRealPath().startsWith(root))throw new IllegalStateException("PYTHON_REAL_PROOF_INVALID");
   save(s,"real-result",Map.of("status","DEEPSEEK_MANAGED_PYTHON_PIP_CHILD_VERIFIED","proof",proof,"receipts",receipts,"savedRealArtifact",saved()));realComplete=true;
   negativeOperation=UUID.randomUUID();negative=true;rejected=dev.mineagent.runtime.neoforge.host.LocalHostCommands.request(p,new HostCommandRequest(negativeOperation,"拒绝按钮故障注入（不执行）","from pathlib import Path; Path('must-not-exist.txt').write_text('BAD')",10));return;
  }
  if(rejected!=null&&rejected.isDone()){var result=rejected.join();if(!result.get("status").equals("REJECTED")||!result.get("error").equals("HOST_USER_REJECTED_NOT_EXECUTED")||Files.exists(s.getServerDirectory().resolve("mineagent-host/operations/"+negativeOperation+".json"))||Files.exists(s.getServerDirectory().resolve("mineagent-host/workspace/must-not-exist.txt")))throw new IllegalStateException("PYTHON_REJECTION_DID_EXECUTE");save(s,"rejection",Map.of("result",result,"fixtureNotModel",true,"noIntentOrProcess",true));complete=true;}
 }catch(Exception e){failure=e.toString();try{save(s,"failure",Map.of("error",failure,"receipts",receipts));}catch(Exception ignored){}}}
}
