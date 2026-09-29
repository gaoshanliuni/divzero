package dev.mineagent.runtime.neoforge.ui;

import dev.mineagent.runtime.neoforge.MineAgentRuntimeServices;
import dev.mineagent.runtime.neoforge.body.MineAgentPlayer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.registries.BuiltInRegistries;
import java.util.*;

/** Owner-scoped, server-thread inventory transactions. Clients supply slots, never item stacks. */
public final class ServerAgentInventory {
    private static MineAgentPlayer body(ServerPlayer viewer, Map<String,String> args) {
        var server=viewer.level().getServer();
        if(!server.isSameThread()||server.getPlayerList().getPlayer(viewer.getUUID())!=viewer)throw new SecurityException("INVENTORY_CONNECTION_CHANGED");
        var manager=MineAgentRuntimeServices.bodies(server);UUID id=UUID.fromString(args.get("agentId"));
        var definition=manager.definitions().stream().filter(d->d.agentId().equals(id)).findFirst().orElseThrow();
        if(!MineAgentRuntimeServices.permissions(server).canMutateAgent(definition,viewer.getUUID(),viewer.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER)))throw new SecurityException("INVENTORY_PERMISSION_DENIED");
        var body=manager.body(id).orElseThrow(()->new IllegalStateException("INVENTORY_AGENT_OFFLINE"));
        if(!body.isAlive()||!viewer.isAlive()||body.level()!=viewer.level())throw new IllegalStateException("INVENTORY_WORLD_CHANGED");
        return body;
    }

    private static String revision(ServerPlayer player) {
        var values=new StringBuilder(player.getUUID().toString()).append('/').append(player.getId());
        for(int i=0;i<player.getInventory().getContainerSize();i++){
            var stack=player.getInventory().getItem(i);values.append('|').append(i).append(':');
            if(!stack.isEmpty())values.append(ItemStack.CODEC.encodeStart(player.registryAccess().createSerializationContext(net.minecraft.nbt.NbtOps.INSTANCE),stack).getOrThrow());
        }
        return dev.mineagent.runtime.core.packages.RuntimePackageCanonicalizer.sha256(values.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static List<Map<String,Object>> slots(ServerPlayer player,int offset) {
        var rows=new ArrayList<Map<String,Object>>();
        for(int slot=offset;slot<Math.min(36,offset+9);slot++)rows.add(slot(player,slot));
        for(int slot=36;slot<41;slot++)rows.add(slot(player,slot));
        return rows;
    }

    private static Map<String,Object> slot(ServerPlayer player,int index) {
        var stack=player.getInventory().getItem(index);String source="";
        if(!stack.isEmpty())source=ItemStack.CODEC.encodeStart(player.registryAccess().createSerializationContext(net.minecraft.nbt.NbtOps.INSTANCE),stack).getOrThrow().toString();
        String name=stack.isEmpty()?"":stack.getHoverName().getString();if(name.length()>64)name=name.substring(0,64)+"…";
        return Map.of("slot",index,"name",name,"item",BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),"count",stack.getCount(),"stack",source.length()>220?"":source,"empty",stack.isEmpty());
    }

    public static Map<String,Object> read(ServerPlayer viewer,Map<String,String> args) {
        var body=body(viewer,args);int offset=Integer.parseInt(args.getOrDefault("offset","0"));
        if(offset<0||offset>27||offset%9!=0)throw new IllegalArgumentException("INVENTORY_PAGE_INVALID");
        return Map.of("agentId",body.agentId(),"agentName",body.getName().getString(),"agentRevision",revision(body),"playerRevision",revision(viewer),"offset",offset,"agentSlots",slots(body,offset),"playerSlots",slots(viewer,offset),"canEdit",body.containerMenu==body.inventoryMenu&&viewer.containerMenu==viewer.inventoryMenu);
    }

    private static net.minecraft.world.inventory.Slot slot(ServerPlayer player,String raw) {
        int index=Integer.parseInt(raw);if(index<0||index>=41)throw new IllegalArgumentException("INVENTORY_SLOT_INVALID");
        int menu=index<9?index+36:index<36?index:index==40?45:44-index;
        return player.inventoryMenu.getSlot(menu);
    }

    public static Map<String,Object> write(ServerPlayer viewer,Map<String,String> args) {
        var body=body(viewer,args);
        if(body.containerMenu!=body.inventoryMenu||viewer.containerMenu!=viewer.inventoryMenu||!viewer.containerMenu.getCarried().isEmpty())throw new IllegalStateException("INVENTORY_CONTAINER_BUSY");
        if(!revision(body).equals(args.get("agentRevision"))||!revision(viewer).equals(args.get("playerRevision")))throw new IllegalStateException("INVENTORY_CHANGED_REFRESH");
        String from=args.get("from"),to=args.get("to");
        if(!Set.of("agent","player").contains(from)||!Set.of("agent","player").contains(to))throw new IllegalArgumentException("INVENTORY_SIDE_INVALID");
        var sourcePlayer=from.equals("agent")?body:viewer;var targetPlayer=to.equals("agent")?body:viewer;
        var source=slot(sourcePlayer,args.get("sourceSlot"));var target=slot(targetPlayer,args.get("targetSlot"));
        if(sourcePlayer==targetPlayer&&source==target)throw new IllegalArgumentException("INVENTORY_SAME_SLOT");
        int count=Integer.parseInt(args.getOrDefault("count","0"));if(count<0)throw new IllegalArgumentException("INVENTORY_COUNT_INVALID");
        var taken=source.getItem();var present=target.getItem();
        if(taken.isEmpty())throw new IllegalStateException("INVENTORY_SOURCE_EMPTY");
        if(count==0)count=taken.getCount();
        if(sourcePlayer.isUsingItem()||targetPlayer.isUsingItem())throw new IllegalStateException("INVENTORY_ITEM_IN_USE");
        if(!source.mayPickup(sourcePlayer)||!target.mayPlace(taken))throw new IllegalStateException("INVENTORY_SLOT_REJECTED");
        int moved;
        if(present.isEmpty()||ItemStack.isSameItemSameComponents(taken,present)){
            moved=Math.min(Math.min(count,taken.getCount()),target.getMaxStackSize(taken)-present.getCount());
            if(moved<=0)throw new IllegalStateException("INVENTORY_TARGET_FULL");
            var next=present.isEmpty()?taken.copyWithCount(moved):present.copyWithCount(present.getCount()+moved);
            source.set(taken.copyWithCount(taken.getCount()-moved));target.set(next);
        }else{
            if(count<taken.getCount()||!target.mayPickup(targetPlayer)||!source.mayPlace(present)||present.getCount()>source.getMaxStackSize(present)||taken.getCount()>target.getMaxStackSize(taken))throw new IllegalStateException("INVENTORY_SWAP_REJECTED");
            moved=taken.getCount();source.set(present.copy());target.set(taken.copy());
        }
        source.setChanged();target.setChanged();body.getInventory().setChanged();viewer.getInventory().setChanged();body.inventoryMenu.broadcastChanges();viewer.inventoryMenu.broadcastChanges();
        var result=new LinkedHashMap<String,Object>(read(viewer,args));result.put("moved",moved);return result;
    }

    private ServerAgentInventory(){}
}
