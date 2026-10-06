package dev.mineagent.runtime.neoforge.client.body;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.Items;
import java.nio.file.*;
import java.util.*;

/** Observer-side evidence in the separately launched native fixture. */
@net.neoforged.fml.common.EventBusSubscriber(modid="mineagent_runtime",value=net.neoforged.api.distmarker.Dist.CLIENT)
public final class ModernPvpClientVerification {
    private static int ticks,bowFrames,maxCharge;private static boolean ended,wasTracked,leftTracking;private static int reentries;private static UUID archer;
    @net.neoforged.bus.api.SubscribeEvent public static void tick(net.neoforged.neoforge.client.event.ClientTickEvent.Post event){
        if(!Boolean.getBoolean("mineagent.modernPvpFixture"))return;var mc=Minecraft.getInstance();
        if(mc.level==null||mc.player==null)return;ticks++;
        if(mc.screen!=null&&mc.screen.getClass().getName().contains("PvpMapClient$Loadouts"))mc.setScreen(null);
        boolean tracked=archer!=null&&mc.level.players().stream().anyMatch(p->p.getUUID().equals(archer));if(wasTracked&&!tracked)leftTracking=true;if(leftTracking&&tracked&&!wasTracked)reentries++;wasTracked=tracked;
        for(var p:mc.level.players())if(p!=mc.player){
            if(p.isUsingItem()&&p.getUseItem().is(Items.BOW)){bowFrames++;maxCharge=Math.max(maxCharge,p.getTicksUsingItem());archer=p.getUUID();}
            else if(archer!=null&&archer.equals(p.getUUID())&&maxCharge>=20)ended=true;
        }
        if(ticks%10==0){
            try{Files.writeString(mc.gameDirectory.toPath().resolve("modern-pvp-client.json"),new com.google.gson.Gson().toJson(Map.of("source","FIXTURE_ONLY","bowUseFrames",bowFrames,"maxChargeTicks",maxCharge,"bowEnded",ended,"trackingReentries",reentries,"selectedSlot",mc.player.getInventory().getSelectedSlot(),"localUsing",mc.player.isUsingItem())));}catch(Exception e){throw new IllegalStateException(e);}
        }
    }
    private ModernPvpClientVerification(){}
}
