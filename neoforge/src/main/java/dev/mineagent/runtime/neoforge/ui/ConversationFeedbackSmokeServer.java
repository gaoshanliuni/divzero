package dev.mineagent.runtime.neoforge.ui;
import dev.mineagent.runtime.neoforge.MineAgentRuntimeServices;
import net.minecraft.server.MinecraftServer;
import java.util.*;import java.nio.file.*;
public final class ConversationFeedbackSmokeServer {
 public static volatile boolean ready;public static volatile int verified;public static volatile UUID agent;public static volatile String failure="";private static boolean setup,zeroBusy;private static int zeroStep,zeroStarted;
 private static final List<Map<String,Object>> receipts=new ArrayList<>();
 public static boolean zero(){return System.getProperty("mineagent.conversationAgentScenario","").equals("feedback_native_zero");}
 public static boolean interrupt(){return System.getProperty("mineagent.conversationAgentScenario","").equals("feedback_interrupt");}
 public static boolean remaining(){return System.getProperty("mineagent.conversationAgentScenario","").equals("feedback_remaining");}
 public static boolean active(){return Boolean.getBoolean("mineagent.conversationAgentReal")&&(zero()||remaining()||interrupt()||System.getProperty("mineagent.conversationAgentScenario","").equals("feedback"));}
 public static void observe(String tool,Map<String,Object> result){if(active())receipts.add(Map.of("tool",tool,"result",result));}
 static void save(MinecraftServer s,String name,Object value)throws Exception{Files.writeString(Files.createDirectories(s.getServerDirectory().resolve("feedback-smoke")).resolve(name+".json"),new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value));}
 public static void tick(MinecraftServer s){if(!failure.isEmpty())return;var p=s.getPlayerList().getPlayers().stream().filter(v->!(v instanceof dev.mineagent.runtime.neoforge.body.MineAgentPlayer)).findFirst().orElse(null);if(p==null)return;try{
  if(!setup){s.getPlayerList().op(p.nameAndId());var center=p.blockPosition();for(var pos:net.minecraft.core.BlockPos.betweenClosed(center.offset(-10,-1,-10),center.offset(10,5,10)))p.level().setBlockAndUpdate(pos,pos.getY()<center.getY()?net.minecraft.world.level.block.Blocks.STONE.defaultBlockState():net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());for(int i=0;i<(zero()?70:6);i++){var d=MineAgentRuntimeServices.bodies(s).createPersistentAt(i==0?"工具助手":"同伴"+i,p.getUUID(),p.level(),p.position().add(i%14-7,0,3+i/14));if(i==0)agent=d.agentId();}setup=true;return;}
  if(!ready){var b=MineAgentRuntimeServices.bodies(s).body(agent).orElse(null);if(b==null)return;b.getInventory().setItem(0,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.APPLE,3));ready=true;return;}
  if(zero()){zero(s,p);return;}
  var store=ServerConversations.get(s).store();var list=store.list(p.getUUID(),agent,"ALL","",0,20).conversations();if(list.isEmpty())return;var c=list.getFirst();if(!c.activeOperation().isEmpty()||c.messageCount()<2||c.messageCount()/2<=verified)return;var context=store.context(p.getUUID(),agent,c.conversationId(),null).orElseThrow();save(s,"context-"+c.messageCount()/2,context);save(s,"receipts",receipts);
  if(interrupt()){
   if(c.messageCount()<4)return;
   if(!context.requestState().equals("COMPLETE")||!receipts.isEmpty())throw new IllegalStateException("INTERRUPT_FOLLOWUP_FAILED_"+context.requestState());
   var answer=store.message(p.getUUID(),agent,c.conversationId(),context.assistantMessageId());var chunk=store.chunk(p.getUUID(),agent,c.conversationId(),context.assistantMessageId(),answer.revision(),0,512);
   if(!chunk.text().contains("中断成功"))throw new IllegalStateException("INTERRUPT_REPLY_NOT_LATEST");
   if(store.forward(p.getUUID(),agent,c.conversationId(),0,10).stream().noneMatch(m->m.role().equals("ASSISTANT")&&m.status().equals("CANCELLED")))throw new IllegalStateException("PRIOR_REPLY_NOT_CANCELLED");
   save(s,"result",Map.of("status","REAL_NATIVE_INTERRUPT_VERIFIED","messages",c.messageCount(),"requestState",context.requestState(),"answer",chunk.text(),"toolMutations",0));verified=2;return;
  }
  if(!context.requestState().equals("COMPLETE")||context.modelReceipt()==null||!context.modelReceipt().requestedModel().equals("deepseek-flash"))throw new IllegalStateException("FEEDBACK_REAL_CHAT_FAILED");
  var body=MineAgentRuntimeServices.bodies(s).body(agent).orElseThrow();int phase=(int)c.messageCount()/2;String follow=MineAgentRuntimeServices.config(s).snapshot().values().getOrDefault("agent."+agent+".follow","false");
  if(phase==1){if(!follow.equals("true")||receipts.stream().noneMatch(r->r.get("tool").equals("remember")&&((Map<?,?>)r.get("result")).get("status").equals("APPLIED"))||!remaining()&&receipts.stream().noneMatch(r->r.get("tool").equals("python_execute")&&((Map<?,?>)r.get("result")).get("status").equals("EXECUTED")))throw new IllegalStateException("FEEDBACK_PHASE1_EFFECTS");}
  if(phase==2){if(!follow.equals("false")||body.getInventory().getItem(0).getCount()!=2||!MineAgentRuntimeServices.config(s).snapshot().values().getOrDefault("agent."+agent+".chatColor","").equalsIgnoreCase("#55FFFF"))throw new IllegalStateException("FEEDBACK_STOP_DROP_COLOR");save(s,"result",Map.of("status","REAL_FEEDBACK_TOOLS_VERIFIED","agents",MineAgentRuntimeServices.bodies(s).definitions().size(),"body",ConversationBodyTools.execute(p,agent,new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode(),true),"initialized",MineAgentRuntimeServices.config(s).snapshot().values().get("runtime.initialized"),"receipts",receipts));}
  verified=phase;
 }catch(Exception e){failure=e.toString();try{save(s,"failure",Map.of("error",failure,"receipts",receipts));}catch(Exception ignored){}}}
 private static void zero(MinecraftServer s,net.minecraft.server.level.ServerPlayer p)throws Exception{
  if(zeroStarted==0){zeroStarted=s.getTickCount();return;}if(s.getTickCount()-zeroStarted<30)return;
  if(zeroBusy||verified>=2)return;var b=MineAgentRuntimeServices.bodies(s).body(agent).orElseThrow();var json=new com.fasterxml.jackson.databind.ObjectMapper();String tool;var args=json.createObjectNode();
  if(CinematicSmokeTiming.pause("basic-body",zeroStep,6500))return;
  switch(zeroStep){
   case 0->{tool="control_agent_body";args.put("action","follow");}
   case 1->{tool="control_agent_body";args.put("action","stop");}
   case 2->{tool="control_agent_body";args.put("action","select").put("slot",0);}
   case 3->{tool="control_agent_body";args.put("action","drop").put("whole_stack",false).put("expected_item",b.getMainHandItem().toString());}
   case 4->{tool="set_chat_color";args.put("color","#55FFFF");}
   case 5->{tool="send_chat_message";args.put("text","原生回归：点击复制").put("color","#55FFFF");args.putArray("buttons").addObject().put("label","复制").put("action","copy").put("value","/time query daytime");}
   case 6->{tool="inspect_blocks";args.putArray("min").add(0).add(0).add(0);args.putArray("max").add(4095).add(4095).add(4095);}
   case 7->{tool="inspect_agent_body";}
   default->{if(b.getMainHandItem().getCount()!=2||Boolean.parseBoolean(MineAgentRuntimeServices.config(s).snapshot().values().getOrDefault("agent."+agent+".follow","false")))throw new IllegalStateException("ZERO_NATIVE_DROP_STOP_FAILED");save(s,"result",Map.of("status","ZERO_PROVIDER_NATIVE_TOOLS_VERIFIED","modelCalls",0,"receipts",receipts,"agents",MineAgentRuntimeServices.bodies(s).definitions().size()));verified=2;return;}
  }
  zeroBusy=true;final String name=tool;ConversationAgentTools.execute(p,agent,UUID.randomUUID(),name,args.toString(),()->true).whenComplete((result,error)->s.execute(()->{try{if(error!=null||"REJECTED".equals(result.get("status"))||"UNKNOWN".equals(result.get("status")))throw new IllegalStateException("ZERO_NATIVE_TOOL_"+name+"_"+result);if(name.equals("inspect_blocks")&&(((Number)result.get("totalCells")).longValue()!=68719476736L||((Number)result.get("nextOffset")).longValue()<1||!Boolean.FALSE.equals(result.get("truncated"))))throw new IllegalStateException("ZERO_NATIVE_PAGE_FAILED");if(!dev.mineagent.runtime.core.conversation.ConversationTools.mutation(name))observe(name,result);zeroStep++;zeroBusy=false;}catch(Exception e){failure=e.toString();}}));
 }

}
