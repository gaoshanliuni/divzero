package dev.mineagent.runtime.neoforge.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mineagent.runtime.core.task.PvpMapProfile;
import dev.mineagent.runtime.neoforge.MineAgentRuntimeServices;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.*;
import java.util.*;

/** Equipment and scores last only for the current world visit; raw match evidence is separate. */
@net.neoforged.fml.common.EventBusSubscriber(modid="mineagent_runtime")
public final class PvpMapSupport {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final Map<net.minecraft.server.MinecraftServer,Map<UUID,PvpMapProfile>> PROFILES=new WeakHashMap<>();
    public static boolean enabled(){return NativeHumanDuel.mapEnabled();}
    public static PvpMapProfile profile(ServerPlayer p){return PROFILES.computeIfAbsent(p.level().getServer(),s->new HashMap<>()).computeIfAbsent(p.getUUID(),id->PvpMapProfile.defaults());}
    public static void save(ServerPlayer p,PvpMapProfile next){
        var before=profile(p);if(next.revision()!=before.revision()+1)throw new IllegalStateException("对练设置已变化，请重新选择");PROFILES.get(p.level().getServer()).put(p.getUUID(),next);
    }
    @net.neoforged.bus.api.SubscribeEvent public static void login(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent event){if(event.getEntity() instanceof ServerPlayer p&&!(p instanceof dev.mineagent.runtime.neoforge.body.MineAgentPlayer)&&enabled())PROFILES.computeIfAbsent(p.level().getServer(),s->new HashMap<>()).put(p.getUUID(),PvpMapProfile.defaults());}
    @net.neoforged.bus.api.SubscribeEvent public static void stopped(net.neoforged.neoforge.event.server.ServerStoppedEvent event){PROFILES.remove(event.getServer());}
    public static List<String> choices(String slot){
        var values=new ArrayList<String>();values.add("minecraft:air");
        if(Set.of("head","chest","legs","feet").contains(slot)){
            String suffix=switch(slot){case "head"->"helmet";case "chest"->"chestplate";case "legs"->"leggings";default->"boots";};
            for(String material:List.of("leather","chainmail","iron","golden","diamond","netherite"))values.add("minecraft:"+material+"_"+suffix);
            if(slot.equals("head"))values.add("minecraft:turtle_helmet");
        }else if(slot.equals("mainhand")||slot.equals("secondary")){
            for(String type:List.of("sword","axe"))for(String material:List.of("wooden","stone","iron","golden","diamond","netherite"))values.add("minecraft:"+material+"_"+type);
            for(String name:List.of("mace","trident","bow","crossbow"))values.add("minecraft:"+name);
            if(slot.equals("secondary")){values.add("minecraft:ender_pearl");values.add("minecraft:shears");}
        }else if(slot.equals("offhand")){for(String name:List.of("shield","totem_of_undying","golden_apple","cooked_beef"))values.add("minecraft:"+name);}
        else if(slot.equals("supply")){for(String name:List.of("golden_apple","enchanted_golden_apple","cooked_beef"))values.add("minecraft:"+name);}
        else throw new IllegalArgumentException("未知装备部位");
        return List.copyOf(values);
    }
    public static Map<String,List<String>> catalog(){var result=new LinkedHashMap<String,List<String>>();for(String slot:PvpMapProfile.SLOTS)result.put(slot,choices(slot));return result;}
    public static void apply(ServerPlayer p,Map<String,String> gear){
        p.stopUsingItem();p.closeContainer();p.getInventory().clearContent();
        for(String slot:PvpMapProfile.SLOTS){String id=gear.get(slot);if(!choices(slot).contains(id))throw new IllegalArgumentException("所选装备不受支持");var item=BuiltInRegistries.ITEM.getValue(Identifier.parse(id));if(item==null)throw new IllegalArgumentException("所选物品不存在");var stack=item==Items.AIR?ItemStack.EMPTY:new ItemStack(item);
            if(slot.equals("secondary")){if(stack.is(Items.ENDER_PEARL))stack.setCount(16);p.getInventory().setItem(3,stack);}
            else if(slot.equals("supply")){if(!stack.isEmpty())stack.setCount(stack.is(Items.COOKED_BEEF)?16:3);p.getInventory().setItem(2,stack);}
            else if(slot.equals("mainhand"))p.getInventory().setItem(0,stack);else p.setItemSlot(switch(slot){case "head"->EquipmentSlot.HEAD;case "chest"->EquipmentSlot.CHEST;case "legs"->EquipmentSlot.LEGS;case "feet"->EquipmentSlot.FEET;default->EquipmentSlot.OFFHAND;},stack);
        }
        if(Set.of("minecraft:bow","minecraft:crossbow").contains(gear.get("mainhand"))||Set.of("minecraft:bow","minecraft:crossbow").contains(gear.get("secondary")))for(int slot=9;slot<13;slot++)p.getInventory().setItem(slot,new ItemStack(Items.ARROW,64));
        p.getInventory().setSelectedSlot(0);dev.mineagent.runtime.neoforge.body.NativeInventorySync.full(p);
    }
    public static void push(ServerPlayer p,String phase,long seconds,boolean open){
        if(!enabled())return;
        try{var profile=profile(p);var message=new LinkedHashMap<String,Object>();message.put("open",open);message.put("phase",phase);message.put("seconds",seconds);message.put("profile",profile);message.put("winRate",profile.winRate());message.put("averageKill",profile.averageKill());message.put("averageDeath",profile.averageDeath());if(open)message.put("catalog",catalog());
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(p,new dev.mineagent.runtime.neoforge.network.UiPayloads.Event(UUID.randomUUID(),"pvpMap",JSON.writeValueAsString(message)));
        }catch(Exception e){throw new IllegalStateException("对练面板同步失败",e);}
    }
    private PvpMapSupport(){}
}
