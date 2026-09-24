package dev.mineagent.runtime.neoforge.ui;
import com.fasterxml.jackson.databind.JsonNode;
import dev.mineagent.runtime.neoforge.MineAgentRuntimeServices;
import net.minecraft.server.level.ServerPlayer;
import dev.mineagent.runtime.neoforge.task.ServerTaskStart;
import net.minecraft.world.phys.Vec3;
import java.util.*;
public final class ConversationBodyTools {
    private ConversationBodyTools(){}
    public static Map<String,Object> execute(ServerPlayer p,UUID agent,JsonNode a,boolean read)throws Exception{
        if(!ServerTaskStart.allowed(p,agent))throw new SecurityException("AGENT_BODY_PERMISSION");
        var s=p.level().getServer();var manager=MineAgentRuntimeServices.bodies(s);var b=manager.body(agent).orElseThrow(()->new IllegalStateException("AGENT_BODY_UNAVAILABLE"));
        var config=MineAgentRuntimeServices.config(s);String key="agent."+agent+".follow";
        String action=read?"inspect":a.path("action").asText();String status="OBSERVED";
        if(!read){if(!b.canAct())throw new IllegalStateException("AGENT_BODY_NOT_ALIVE");if(b.taskControlOwned()&&!(action.equals("stop")&&(NativeAgentBlockActions.stop(p,agent)||NativeAgentPathBuilder.stop(p,agent))))throw new IllegalStateException("AGENT_BODY_TASK_OWNS_INPUT");
            if(!Set.of("move","look","sprint","sneak","select","drop","follow","stop","teleport_to_player").contains(action))throw new IllegalArgumentException("AGENT_BODY_ACTION");
            if(action.equals("move")||action.equals("look")){var target=a.path("target");if(!target.isArray()||target.size()!=3)throw new IllegalArgumentException("AGENT_BODY_TARGET");for(var part:target)if(!part.isNumber()||!Double.isFinite(part.doubleValue())||Math.abs(part.doubleValue())>30000000)throw new IllegalArgumentException("AGENT_BODY_TARGET");}
            if(action.equals("follow")&&!p.getUUID().equals(b.ownerPlayerId()))throw new SecurityException("AGENT_FOLLOW_OWNER_REQUIRED");
            if(action.equals("teleport_to_player")&&!p.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER))throw new SecurityException("AGENT_TELEPORT_PERMISSION");
            if(Set.of("move","stop","teleport_to_player").contains(action)||action.equals("follow")){
                var r=config.apply(new dev.mineagent.runtime.api.config.ConfigPatch(config.snapshot().revision(),Map.of(key,Boolean.toString(action.equals("follow")))),true);if(!r.accepted())throw new IllegalStateException("AGENT_BODY_CONFIG_CONFLICT");
            }
            switch(action){
                case "move","look"->{var n=a.path("target");if(!n.isArray()||n.size()!=3)throw new IllegalArgumentException("AGENT_BODY_TARGET");double[] v=new double[3];for(int i=0;i<3;i++){if(!n.get(i).isNumber()||!Double.isFinite(v[i]=n.get(i).asDouble())||Math.abs(v[i])>30000000)throw new IllegalArgumentException("AGENT_BODY_TARGET");}var target=new Vec3(v[0],v[1],v[2]);if(action.equals("move")){b.movementController().movePreciselyTo(target);status="STARTED_NOT_ARRIVED";}else b.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES,target);}
                case "sprint"->{if(!a.path("enabled").isBoolean())throw new IllegalArgumentException("AGENT_BODY_BOOLEAN");b.setSprinting(a.path("enabled").booleanValue());}
                case "sneak"->{if(!a.path("enabled").isBoolean())throw new IllegalArgumentException("AGENT_BODY_BOOLEAN");b.movementController().setSneaking(b,a.path("enabled").booleanValue());}
                case "select"->{var n=a.path("slot");if(!n.isIntegralNumber()||!n.canConvertToInt()||n.intValue()<0||n.intValue()>35)throw new IllegalArgumentException("AGENT_BODY_SLOT");if(n.intValue()<9)b.getInventory().setSelectedSlot(n.intValue());else b.getInventory().pickSlot(n.intValue());}
                case "drop"->{if(!a.path("whole_stack").isBoolean())throw new IllegalArgumentException("AGENT_BODY_BOOLEAN");if(!b.getMainHandItem().toString().equals(a.path("expected_item").asText()))throw new IllegalStateException("AGENT_BODY_ITEM_CHANGED");b.drop(a.path("whole_stack").booleanValue());}
                case "stop"->{b.movementController().stop();b.setSprinting(false);}
                case "follow"->{if(!p.getUUID().equals(b.ownerPlayerId()))throw new SecurityException("AGENT_FOLLOW_OWNER_REQUIRED");if(p.level()==b.level())b.movementController().moveTo(p.position());status="FOLLOWING";}
                case "teleport_to_player"->{if(!p.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER))throw new SecurityException("AGENT_TELEPORT_PERMISSION");b.movementController().stop();if(!manager.changeDimension(agent,p.level(),p.position().add(1,0,0)))throw new IllegalStateException("AGENT_TELEPORT_FAILED");}
            }
            b.inventoryMenu.broadcastChanges();
        }
        var rows=new ArrayList<Object>();for(int i=0;i<b.getInventory().getContainerSize();i++){var stack=b.getInventory().getItem(i);if(!stack.isEmpty())rows.add(Map.of("slot",i,"item",stack.toString(),"name",stack.getHoverName().getString(),"count",stack.getCount()));}
        var out=new LinkedHashMap<String,Object>(Map.of("status",status,"entity",agent,"position",List.of(b.getX(),b.getY(),b.getZ()),"yaw",b.getYRot(),"pitch",b.getXRot(),"sprinting",b.isSprinting(),"following",Boolean.parseBoolean(config.snapshot().values().getOrDefault(key,"false")),"movement",b.movementController().outcome(),"inventory",rows,"mainHand",b.getMainHandItem().toString()));out.put("sneaking",b.isShiftKeyDown());out.put("pose",b.getPose().name());out.put("manualSneak",b.movementController().manualSneak());return out;
    }
}
