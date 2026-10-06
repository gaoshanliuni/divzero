package dev.mineagent.runtime.neoforge.client.body;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import dev.mineagent.runtime.neoforge.mixin.NativeUseStateAccess;
import java.util.*;

/** Handles equipment packet ordering, cancel and late entry into tracking. Never runs item use locally. */
@net.neoforged.fml.common.EventBusSubscriber(modid="mineagent_runtime",value=net.neoforged.api.distmarker.Dist.CLIENT)
public final class RemoteNativeUse {
    private record State(JsonObject data,long received){}
    private static final Map<UUID,State> STATES=new HashMap<>();private static Object level;
    public static void accept(JsonObject data){
        var mc=Minecraft.getInstance();if(mc.level==null)return;if(level!=mc.level){STATES.clear();level=mc.level;}
        var id=UUID.fromString(data.get("uuid").getAsString());var old=STATES.get(id);
        if(old!=null&&old.data.get("tick").getAsLong()>data.get("tick").getAsLong())return;
        STATES.put(id,new State(data,mc.level.getGameTime()));
    }
    @net.neoforged.bus.api.SubscribeEvent public static void tick(net.neoforged.neoforge.client.event.ClientTickEvent.Post event){
        var mc=Minecraft.getInstance();if(mc.level!=level||mc.level==null){STATES.clear();level=mc.level;return;}
        var it=STATES.entrySet().iterator();while(it.hasNext()){
            var entry=it.next();var state=entry.getValue();var d=state.data;long age=mc.level.getGameTime()-state.received;
            var raw=mc.level.getEntity(d.get("entity").getAsInt());
            if(raw==mc.player||raw!=null&&!raw.getUUID().equals(entry.getKey())){it.remove();continue;}
            if(!(raw instanceof LivingEntity p)){if(age>20)it.remove();continue;}
            if(age>20||!p.isAlive()||!d.get("active").getAsBoolean()){if(p.isUsingItem())p.stopUsingItem();it.remove();continue;}
            var hand=InteractionHand.valueOf(d.get("hand").getAsString());var stack=p.getItemInHand(hand);
            if(!net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().equals(d.get("item").getAsString())){if(p.isUsingItem())p.stopUsingItem();continue;}
            if(!p.isUsingItem()||p.getUsedItemHand()!=hand){p.stopUsingItem();p.startUsingItem(hand);}
            var access=(NativeUseStateAccess)p;access.divzero$useItem(stack);
            access.divzero$useRemaining(Math.max(1,stack.getUseDuration(p)-d.get("elapsed").getAsInt()-(int)Math.max(0,age)));
        }
    }
    private RemoteNativeUse(){}
}
