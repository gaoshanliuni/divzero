package dev.mineagent.runtime.neoforge.ui;

import dev.mineagent.runtime.neoforge.MineAgentRuntimeServices;
import dev.mineagent.runtime.core.conversation.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.*;
import java.util.*;
import java.nio.file.*;

/** Explicit fault injection before a NEW real-provider recovery request. Never fabricates a model result. */
public final class ConversationRecoverySmokeServer {
    public static volatile boolean ready,complete,next;public static volatile UUID agent,conversation;public static volatile String failure="";public static volatile int verified;
    private static int seeded;private static UUID oldOperation;private static final List<String> observed=new ArrayList<>();private static final com.fasterxml.jackson.databind.ObjectMapper JSON=new com.fasterxml.jackson.databind.ObjectMapper();
    public static boolean active(){return Boolean.getBoolean("mineagent.conversationAgentSmoke")&&Boolean.getBoolean("mineagent.conversationAgentReal")&&System.getProperty("mineagent.conversationAgentScenario","").equals("recovery");}
    public static void observe(String tool){if(active()&&ready)observed.add(tool);}
    private static int apples(ServerPlayer p){int n=0;for(var stack:p.getInventory().getNonEquipmentItems())if(stack.is(Items.APPLE))n+=stack.getCount();return n;}
    private static void save(MinecraftServer s,String name,Object data)throws Exception{Files.writeString(Files.createDirectories(s.getServerDirectory().resolve("recovery-smoke")).resolve(name+".json"),JSON.writeValueAsString(data));}
    private static void seed(MinecraftServer s,ServerPlayer p)throws Exception{
        observed.clear();var store=ServerConversations.get(s).store();var c=store.create(p.getUUID(),agent,UUID.randomUUID(),seeded==0?"UNKNOWN 故障恢复":"CONTEXT_CHANGED 恢复");conversation=c.conversationId();oldOperation=UUID.randomUUID();
        var t=store.begin(p.getUUID(),agent,conversation,oldOperation,c.revision(),seeded==0?"给我一个苹果，然后清点背包中的苹果数量。不要执行电脑命令。":"只读取我的背包，告诉我苹果数量。不需要给予或修改物品，不操作电脑。",0);
        store.finish(t.operationId(),"FAILED",seeded==0?"给予苹果步骤的最终回执未知，尚未清点。请先核对状态，不要重复给予。":"读取前上下文已改变，尚未完成本次读取。",seeded==0?"AGENT_TOOL_OUTCOME_UNKNOWN":"CONTEXT_CHANGED");
        seeded++;save(s,"injected-"+seeded,Map.of("faultInjection",true,"notARealModelReply",true,"oldOperation",oldOperation,"conversation",conversation,"apples",apples(p)));ready=true;
    }
    public static void tick(MinecraftServer s){if(complete||!failure.isEmpty())return;var p=s.getPlayerList().getPlayers().stream().filter(x->!(x instanceof dev.mineagent.runtime.neoforge.body.MineAgentPlayer)).findFirst().orElse(null);if(p==null)return;try{
        if(agent==null){s.getPlayerList().op(p.nameAndId());p.setGameMode(net.minecraft.world.level.GameType.CREATIVE);p.getInventory().clearContent();p.getInventory().add(new ItemStack(Items.APPLE,1));agent=MineAgentRuntimeServices.bodies(s).createPersistentAt("核对助手",p.getUUID(),p.level(),p.position().add(3,0,0)).agentId();
            var operation=UUID.randomUUID();ConversationToolJournal.save(s.getServerDirectory().resolve("mineagent-runtime-data/runtime.db"),MineAgentRuntimeServices.worldId(s),operation,0,JSON.writeValueAsString(Map.of("owner",p.getUUID(),"agent",agent,"tool","give_item","arguments",Map.of("item","minecraft:apple","count",1),"receipt",Map.of("status","UNKNOWN","error","AGENT_TOOL_OUTCOME_UNKNOWN"))));seed(s,p);return;}
        if(next&&verified==1&&seeded==1){next=false;seed(s,p);return;}if(seeded<=verified)return;
        var store=ServerConversations.get(s).store();var c=store.get(p.getUUID(),agent,conversation);if(c.messageCount()<4||!c.activeOperation().isEmpty())return;var ctx=store.context(p.getUUID(),agent,conversation,null).orElseThrow();
        if(!ctx.requestState().equals("COMPLETE")||ctx.modelReceipt()==null||!ctx.modelReceipt().requestedModel().equals("deepseek-flash"))throw new IllegalStateException("RECOVERY_REAL_FAILED:"+ctx.errorCode());
        if(ctx.operationId().equals(oldOperation)||c.messageCount()!=4||apples(p)!=1||!observed.contains("inspect_operations")||!observed.contains("inspect_player")||observed.stream().anyMatch(ConversationTools::mutation))throw new IllegalStateException("RECOVERY_DUPLICATION_OR_MISSING_READ:"+observed);
        save(s,"verified-"+seeded,Map.of("status","REAL_RECOVERY_AFTER_INJECTED_FAILURE","context",ctx,"tools",List.copyOf(observed),"applesBefore",1,"applesAfter",apples(p),"newOperation",true,"oldActionReplayed",false));verified=seeded;if(verified==2)complete=true;
    }catch(Exception e){failure=e.toString();try{save(s,"failure",Map.of("error",failure,"seeded",seeded,"tools",List.copyOf(observed)));}catch(Exception ignored){}}}
}
