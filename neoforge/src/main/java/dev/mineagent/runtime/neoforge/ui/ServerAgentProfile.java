package dev.mineagent.runtime.neoforge.ui;

import dev.mineagent.runtime.neoforge.MineAgentRuntimeServices;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import java.util.*;

/** Read-only, AI-specific panel projection. Private inventory/persona remain owner/collaborator scoped. */
public final class ServerAgentProfile {
    public static Map<String,Object> read(ServerPlayer viewer,Map<String,String> args)throws Exception{
        if(!Set.of("agentId","inventoryOffset","contentOffset").containsAll(args.keySet()))throw new IllegalArgumentException("AGENT_PANEL_ARGUMENTS");
        var server=viewer.level().getServer();if(!server.isSameThread()||server.getPlayerList().getPlayer(viewer.getUUID())!=viewer)throw new SecurityException("AGENT_PANEL_CONNECTION");
        UUID id=UUID.fromString(args.get("agentId"));var bodies=MineAgentRuntimeServices.bodies(server);var definition=bodies.definitions().stream().filter(a->a.agentId().equals(id)).findFirst().orElseThrow(()->new IllegalArgumentException("AGENT_NOT_FOUND"));
        boolean operator=viewer.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER),privateAccess=MineAgentRuntimeServices.permissions(server).canMutateAgent(definition,viewer.getUUID(),operator);
        var body=bodies.body(id).orElse(null);var result=new LinkedHashMap<String,Object>();result.put("agentId",id);result.put("name",definition.displayName());result.put("revision",bodies.revision(id));result.put("bodyState",bodies.bodyState(id));result.put("mode",definition.mode().name());result.put("canManage",privateAccess);result.put("canEditPersona",dev.mineagent.runtime.core.agent.AgentPersonaService.mayEdit(definition,viewer.getUUID(),operator));
        result.put("health",body==null?0:body.getHealth());result.put("maxHealth",body==null?0:body.getMaxHealth());result.put("food",body==null?0:body.getFoodData().getFoodLevel());result.put("inventoryVisible",privateAccess&&body!=null);
        int offset=Integer.parseInt(args.getOrDefault("inventoryOffset","0")),contentOffset=Integer.parseInt(args.getOrDefault("contentOffset","0"));if(offset<0||contentOffset<0)throw new IllegalArgumentException("AGENT_PANEL_OFFSET");
        var items=new ArrayList<Map<String,Object>>();int next=-1;
        if(privateAccess&&body!=null){var inventory=body.getInventory();int end=Math.min(inventory.getContainerSize(),offset+9);for(int slot=offset;slot<end;slot++){
            ItemStack stack=inventory.getItem(slot);String encoded="";if(!stack.isEmpty()){var tag=ItemStack.CODEC.encodeStart(viewer.registryAccess().createSerializationContext(net.minecraft.nbt.NbtOps.INSTANCE),stack).getOrThrow();String source=tag.toString();if(source.length()<=1700)encoded=source;}
            items.add(Map.of("slot",slot,"name",stack.isEmpty()?"":stack.getHoverName().getString(),"item",BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),"count",stack.getCount(),"stack",encoded,"empty",stack.isEmpty()));
        }next=end<inventory.getContainerSize()?end:-1;result.put("inventorySize",inventory.getContainerSize());}
        result.put("inventory",items);result.put("inventoryOffset",offset);result.put("nextInventoryOffset",next);
        result.put("contents",definition.ownerPlayerId().equals(viewer.getUUID())?ServerPackageRuntime.get(server).createdByAgent(viewer.getUUID(),id,contentOffset):Map.of("items",List.of(),"private",true,"nextOffset",-1));
        return result;
    }
    private ServerAgentProfile(){}
}
