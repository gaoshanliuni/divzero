package dev.mineagent.runtime.neoforge.body;
import dev.mineagent.runtime.neoforge.network.UiPayloads;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import java.util.Map;

/** Tracked visual use state only; the server's native item code owns charging, release and consumption. */
public final class NativeUseSync {
    private static final com.google.gson.Gson JSON=new com.google.gson.Gson();
    private static UiPayloads.Event state(MineAgentPlayer p){
        boolean active=p.isAlive()&&p.isUsingItem();
        return new UiPayloads.Event(p.getUUID(),"agentUse",JSON.toJson(Map.of("entity",p.getId(),"uuid",p.getUUID().toString(),"tick",p.level().getGameTime(),"active",active,
                "hand",active?p.getUsedItemHand().name():"MAIN_HAND","item",net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(active?p.getUseItem().getItem():net.minecraft.world.item.Items.AIR).toString(),"elapsed",active?p.getTicksUsingItem():0)));
    }
    public static void send(MineAgentPlayer p){if(p.connection!=null)PacketDistributor.sendToPlayersTrackingEntity(p,state(p));}
    public static void send(MineAgentPlayer p,ServerPlayer observer){PacketDistributor.sendToPlayer(observer,state(p));}
    private NativeUseSync(){}
}
