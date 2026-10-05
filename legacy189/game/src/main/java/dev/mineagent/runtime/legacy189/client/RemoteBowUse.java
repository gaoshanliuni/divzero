package dev.mineagent.runtime.legacy189.client;

import dev.mineagent.runtime.legacy189.NativeNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityOtherPlayerMP;
import net.minecraft.entity.Entity;
import net.minecraft.item.ItemBow;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;
import java.util.*;

/** Repair remote-player use state after equipment stack replacement, with bounded server leases. */
final class RemoteBowUse {
    private static final Map<UUID,Entry> entries=new HashMap<UUID,Entry>();
    private static World world;
    private static int ticks;
    static void receive(NativeNetwork.BowUse message){
        Minecraft mc=Minecraft.getMinecraft();
        if(mc.theWorld==null||mc.thePlayer==null||mc.thePlayer.dimension!=message.dimension)return;
        if(world!=mc.theWorld){entries.clear();world=mc.theWorld;}
        entries.put(message.player,new Entry(message,ticks));apply(entries.get(message.player));
    }
    static void clear(){entries.clear();world=null;}
    static void tick(){
        ticks++;if(world!=Minecraft.getMinecraft().theWorld){clear();return;}
        Iterator<Entry> it=entries.values().iterator();while(it.hasNext()){
            Entry entry=it.next();if(ticks-entry.received>12){clearUse(entry);it.remove();}else apply(entry);
        }
    }
    private static EntityOtherPlayerMP player(Entry e){Entity entity=world==null?null:world.getEntityByID(e.message.entity);return entity instanceof EntityOtherPlayerMP&&entity.getUniqueID().equals(e.message.player)?(EntityOtherPlayerMP)entity:null;}
    private static void clearUse(Entry e){EntityOtherPlayerMP p=player(e);if(p!=null){p.clearItemInUse();p.setEating(false);}}
    private static void apply(Entry e){
        EntityOtherPlayerMP p=player(e);if(p==null)return;
        ItemStack held=p.getHeldItem();int remaining=Math.max(0,e.message.remaining-(ticks-e.received));
        if(remaining==0||!p.isEntityAlive()||held==null||!(held.getItem() instanceof ItemBow)){clearUse(e);return;}
        p.setEating(true);
        if(p.getItemInUse()!=held||Math.abs(p.getItemInUseCount()-remaining)>3){p.clearItemInUse();p.setItemInUse(held,remaining);}
    }
    private static final class Entry {final NativeNetwork.BowUse message;final int received;Entry(NativeNetwork.BowUse m,int tick){message=m;received=tick;}}
}
