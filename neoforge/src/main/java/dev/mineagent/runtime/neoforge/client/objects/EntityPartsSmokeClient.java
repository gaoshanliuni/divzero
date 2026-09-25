package dev.mineagent.runtime.neoforge.client.objects;
import dev.mineagent.runtime.neoforge.ui.EntityPartsSmokeServer;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import java.nio.file.*;
@EventBusSubscriber(modid="mineagent_runtime",value=Dist.CLIENT)
public final class EntityPartsSmokeClient {
    private static int capture,ticks;private static boolean writing;
    @SubscribeEvent public static void tick(ClientTickEvent.Post event){if(!EntityPartsSmokeServer.enabled())return;var mc=Minecraft.getInstance();if(mc.level==null)return;if(mc.screen!=null)mc.setScreen(null);mc.options.hideGui=true;
        if(capture!=EntityPartsSmokeServer.capture){capture=EntityPartsSmokeServer.capture;ticks=0;}
        if(capture>0&&++ticks==25&&!writing){writing=true;int current=capture;var file=mc.gameDirectory.toPath().resolve("entity-parts-smoke/visual-"+current+".png");mc.options.hideGui=true;
            net.minecraft.client.Screenshot.takeScreenshot(mc.getMainRenderTarget(),image->{try(image){Files.createDirectories(file.getParent());image.writeToFile(file);}catch(Exception ignored){}finally{writing=false;EntityPartsSmokeServer.captured=current;}});
        }
        if(EntityPartsSmokeServer.done&&!writing)mc.stop();
    }
}
