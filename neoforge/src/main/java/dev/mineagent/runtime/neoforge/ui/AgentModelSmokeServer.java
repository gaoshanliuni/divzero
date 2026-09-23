package dev.mineagent.runtime.neoforge.ui;
import dev.mineagent.runtime.neoforge.MineAgentRuntimeServices;import dev.mineagent.runtime.core.config.AgentModelSettings;
import net.minecraft.server.MinecraftServer;import java.util.*;import java.nio.file.*;
public final class AgentModelSmokeServer {
 public static volatile boolean ready,clientReady,complete;public static volatile UUID first,second;public static volatile int verified;public static volatile String failure="";
 private static final Set<UUID> checked=new HashSet<>();private static final com.fasterxml.jackson.databind.ObjectMapper JSON=new com.fasterxml.jackson.databind.ObjectMapper();
 public static boolean active(){return Boolean.getBoolean("mineagent.conversationAgentSmoke")&&Boolean.getBoolean("mineagent.conversationAgentReal")&&Set.of("agent_models","agent_models_ui").contains(System.getProperty("mineagent.conversationAgentScenario",""));}
 public static boolean uiOnly(){return System.getProperty("mineagent.conversationAgentScenario","").equals("agent_models_ui");}
 public static void tick(MinecraftServer s){if(complete||!failure.isEmpty())return;var p=s.getPlayerList().getPlayers().stream().filter(x->!(x instanceof dev.mineagent.runtime.neoforge.body.MineAgentPlayer)).findFirst().orElse(null);if(p==null)return;try{
  Path root=Files.createDirectories(s.getServerDirectory().resolve("agent-model-smoke"));var cfg=MineAgentRuntimeServices.config(s);if(!ready){s.getPlayerList().op(p.nameAndId());first=MineAgentRuntimeServices.bodies(s).createPersistentAt("默认助手",p.getUUID(),p.level(),p.position().add(3,0,0)).agentId();second=MineAgentRuntimeServices.bodies(s).createPersistentAt("独立助手",p.getUUID(),p.level(),p.position().add(-3,0,0)).agentId();ready=true;return;}if(!clientReady)return;
  if(!cfg.snapshot().values().getOrDefault("provider.openai.model","").equals("deepseek-flash"))throw new IllegalStateException("GLOBAL_MODEL_WAS_CHANGED");
  if(!AgentModelSettings.read(cfg.snapshot().values(),MineAgentRuntimeServices.worldId(s),first).mode().equals("DEFAULT"))throw new IllegalStateException("OTHER_AGENT_CHANGED");
  if(verified==1&&s.getTickCount()%40==0)Files.writeString(root.resolve("catalog.json"),JSON.writeValueAsString(ServerProviderModels.view(p,false,0,"")));
  if(uiOnly()){var choice=AgentModelSettings.read(cfg.snapshot().values(),MineAgentRuntimeServices.worldId(s),second);if(verified==0)verified=1;if(choice.mode().equals("CUSTOM")&&choice.model().equals("deepseek-v4-pro"))verified=2;if(verified==2&&choice.mode().equals("DEFAULT")&&choice.revision()>=2){verified=3;complete=true;Files.writeString(root.resolve("server.json"),JSON.writeValueAsString(Map.of("status","UI_SELECTION_RESTORE_ZERO_MODEL","choice",choice)));}return;}
  var store=ServerConversations.get(s).store();for(UUID id:List.of(first,second))for(var c:store.list(p.getUUID(),id,"ALL","",0,20).conversations()){
   if(c.messageCount()<2||!c.activeOperation().isEmpty())continue;var ctx=store.context(p.getUUID(),id,c.conversationId(),null).orElseThrow();if(!checked.add(ctx.operationId()))continue;
   String expected=verified==1?"deepseek-v4-pro":"deepseek-flash";if(!ctx.requestState().equals("COMPLETE")||ctx.modelReceipt()==null||!ctx.modelReceipt().requestedModel().equals(expected))throw new IllegalStateException("AGENT_MODEL_REAL_MISMATCH:"+ctx.errorCode()+":"+(ctx.modelReceipt()==null?"none":ctx.modelReceipt().requestedModel()));
   if(verified==0&&!id.equals(first)||verified>0&&!id.equals(second))throw new IllegalStateException("AGENT_MODEL_CHAT_ORDER");
   Files.writeString(root.resolve("reply-"+verified+".json"),JSON.writeValueAsString(Map.of("agent",id,"context",ctx,"selection",ServerAgentModels.read(p,id),"globalModel","deepseek-flash")));verified++;
   if(verified==3){complete=true;Files.writeString(root.resolve("server.json"),JSON.writeValueAsString(Map.of("status","REAL_PER_AGENT_REQUESTED_MODELS_VERIFIED","sequence",List.of("deepseek-flash","deepseek-v4-pro","deepseek-flash"),"globalUnchanged",true,"restoreDefault",true)));}
  }
 }catch(Exception e){failure=e.toString();try{Files.writeString(Files.createDirectories(s.getServerDirectory().resolve("agent-model-smoke")).resolve("failure.json"),JSON.writeValueAsString(Map.of("error",failure,"verified",verified)));}catch(Exception ignored){}}}
}
