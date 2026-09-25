package dev.mineagent.runtime.neoforge.client.objects;
import dev.mineagent.runtime.neoforge.ui.NativeBossSmokeServer;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import java.nio.file.*;
@EventBusSubscriber(modid="mineagent_runtime",value=Dist.CLIENT)
public final class NativeBossSmokeClient {
    private static int serial,ticks;private static boolean writing;
    @SubscribeEvent public static void tick(ClientTickEvent.Post event){
        if(!NativeBossSmokeServer.enabled())return;var mc=Minecraft.getInstance();if(mc.level==null)return;
        // The isolated server's initial trust page may otherwise cover every screenshot.
        // This is in-process fixture UI control, not injected OS keyboard/mouse input.
        if(mc.screen!=null)mc.setScreen(null);
        if(serial!=NativeBossSmokeServer.captureSerial){serial=NativeBossSmokeServer.captureSerial;ticks=0;}
        if(++ticks==65&&!writing){writing=true;var file=mc.gameDirectory.toPath().resolve("native-boss-smoke/visual-"+serial+".png");mc.options.hideGui=true;
            net.minecraft.client.Screenshot.takeScreenshot(mc.getMainRenderTarget(),image->{try(image){Files.createDirectories(file.getParent());image.writeToFile(file);}catch(Exception ignored){}finally{writing=false;}});
        }
        if(NativeBossSmokeServer.done&&!writing)mc.stop();
    }
    private NativeBossSmokeClient(){}
}
