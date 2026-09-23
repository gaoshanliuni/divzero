package dev.mineagent.runtime.neoforge.client.webui;
import net.minecraft.client.Minecraft;import net.neoforged.bus.api.SubscribeEvent;import net.neoforged.fml.common.EventBusSubscriber;import java.util.*;
@EventBusSubscriber(modid="mineagent_runtime",value=net.neoforged.api.distmarker.Dist.CLIENT)
public final class PreviewClient {
 private static String pending;private static Object connection;
 public static void open(String id){UUID.fromString(id);var mc=Minecraft.getInstance();if(mc.player==null)return;pending=id;connection=mc.getConnection();WebGuiHostAdapter.INSTANCE.open();}
 @SubscribeEvent public static void tick(net.neoforged.neoforge.client.event.ClientTickEvent.Post e){if(connection!=Minecraft.getInstance().getConnection()){pending=null;connection=null;}if(pending!=null&&WebGuiHostAdapter.INSTANCE.ready()&&UiClientSessions.current()!=null){String id=pending;pending=null;WebGuiHostAdapter.INSTANCE.emit("previewOpen",Map.of("previewId",id));}}
 private PreviewClient(){}
}
