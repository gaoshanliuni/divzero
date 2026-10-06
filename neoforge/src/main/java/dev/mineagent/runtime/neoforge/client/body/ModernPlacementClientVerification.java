package dev.mineagent.runtime.neoforge.client.body;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
@net.neoforged.fml.common.EventBusSubscriber(modid="mineagent_runtime",value=net.neoforged.api.distmarker.Dist.CLIENT)
public final class ModernPlacementClientVerification {
    private static JsonObject pending;private static int elapsed;private static boolean issued;private static String error="";
    private static final Set<UUID> seen=new HashSet<>();
    public static void accept(JsonObject value){if(!Boolean.getBoolean("mineagent.modernLifecycleFixture")||!seen.add(UUID.fromString(value.get("id").getAsString())))return;pending=value;elapsed=0;issued=false;error="";}
    @net.neoforged.bus.api.SubscribeEvent public static void tick(net.neoforged.neoforge.client.event.ClientTickEvent.Post event){
        if(pending==null)return;var mc=Minecraft.getInstance();var p=mc.player;if(p==null||mc.level==null||mc.gameMode==null)return;
        String kind=pending.get("kind").getAsString();var floor=new BlockPos(pending.get("x").getAsInt(),pending.get("y").getAsInt(),pending.get("z").getAsInt());
        try{
            if(!issued){
                if(kind.equals("place")){
                    if(p.getInventory().getSelectedSlot()!=pending.get("selected").getAsInt())throw new IllegalStateException("CLIENT_PRE_USE_SELECTED_SLOT");
                    p.getInventory().setSelectedSlot(1);var d=Vec3.atCenterOf(floor).add(0,.499,0).subtract(p.getEyePosition());p.setYRot((float)Math.toDegrees(Math.atan2(-d.x,d.z)));p.setXRot((float)-Math.toDegrees(Math.atan2(d.y,d.horizontalDistance())));
                    var hit=p.pick(p.blockInteractionRange(),0,false);if(!(hit instanceof BlockHitResult block)||!block.getBlockPos().equals(floor))throw new IllegalStateException("CLIENT_NATIVE_RAY_MISSED");
                    mc.gameMode.useItemOn(p,InteractionHand.MAIN_HAND,block);p.swing(InteractionHand.MAIN_HAND);
                }else if(kind.equals("hold")){mc.options.keyUse.setDown(true);p.getInventory().setSelectedSlot(2);mc.gameMode.useItem(p,InteractionHand.MAIN_HAND);}
                else if(kind.equals("respawn")){mc.options.keyUse.setDown(false);if(++elapsed<25)return;p.respawn();elapsed=0;}
                else p.connection.sendCommand("ai duel "+kind);
                issued=true;
            }
            if(++elapsed< (kind.equals("place")||kind.equals("respawn")?12:2))return;
        }catch(Throwable failure){error=failure.toString();}
        try{
            var result=Map.of("source","FIXTURE_ONLY","id",pending.get("id").getAsString(),"kind",kind,"selected",p.getInventory().getSelectedSlot(),"count",p.getInventory().getItem(1).getCount(),"wool",mc.level.getBlockState(floor.above()).is(Blocks.WHITE_WOOL),"error",error,"alive",p.isAlive(),"using",p.isUsingItem(),"supply",p.getInventory().getItem(2).getCount());
            var temp=mc.gameDirectory.toPath().resolve("modern-placement-client.tmp");Files.writeString(temp,new Gson().toJson(result));Files.move(temp,mc.gameDirectory.toPath().resolve("modern-placement-client.json"),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);pending=null;
        }catch(Exception e){throw new IllegalStateException(e);}
    }
    private ModernPlacementClientVerification(){}
}
