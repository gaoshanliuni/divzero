package dev.mineagent.runtime.neoforge.client.webui;

import dev.mineagent.runtime.neoforge.ui.SharedStateSmokeSupport;
import dev.mineagent.runtime.neoforge.ui.SharedStateResumeSmokeServer;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/** No input automation or new graphical-restore claim. Ends after the real server restore assertions. */
@EventBusSubscriber(modid="mineagent_runtime",value=Dist.CLIENT)
public final class SharedStateResumeSmokeClient {
    private static boolean stopped,backup;private static int ticks;
    @SubscribeEvent public static void tick(ClientTickEvent.Post event)throws Exception{
        if(!SharedStateSmokeSupport.resuming()||stopped)return;var mc=Minecraft.getInstance();
        if(!backup&&mc.screen instanceof net.minecraft.client.gui.screens.BackupConfirmScreen s){backup=true;var f=s.getClass().getDeclaredField("onProceed");f.setAccessible(true);((net.minecraft.client.gui.screens.BackupConfirmScreen.Listener)f.get(s)).proceed(false,false);}
        if(++ticks>2000||SharedStateResumeSmokeServer.failure!=null||SharedStateResumeSmokeServer.finished){stopped=true;WebGuiHostAdapter.INSTANCE.close();mc.stop();}
    }
}
