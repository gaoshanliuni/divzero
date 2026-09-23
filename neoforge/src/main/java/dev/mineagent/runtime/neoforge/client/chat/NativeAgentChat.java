package dev.mineagent.runtime.neoforge.client.chat;
import dev.mineagent.runtime.neoforge.network.MineAgentPayloads;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import java.util.*;

/** Only world-local public AI names are cached. No conversation/message/provider data is requested for completion. */
@EventBusSubscriber(modid="mineagent_runtime",value=Dist.CLIENT)
public final class NativeAgentChat {
    private static Object connection,level;private static UUID request;private static long sentAt;private static List<String> names=List.of();private static Object screen;private static int nextPage;private static UUID responseWorld;private static final List<String> incoming=new ArrayList<>();
    private NativeAgentChat(){}
    private static boolean current(){var mc=Minecraft.getInstance();return mc.getConnection()!=null&&connection==mc.getConnection().getConnection()&&level==mc.level;}
    public static List<String> names(){return current()?names:List.of();}
    public static void accept(MineAgentPayloads.AgentNames response,Object wire){if(!current()||connection!=wire||!response.request().equals(request))return;if(response.page()!=nextPage||responseWorld!=null&&!responseWorld.equals(response.world())){request=null;incoming.clear();return;}responseWorld=response.world();nextPage++;incoming.addAll(response.names());if(!response.last())return;var next=List.copyOf(incoming);boolean changed=!names.equals(next);names=next;incoming.clear();request=null;if(changed&&Minecraft.getInstance().screen instanceof ChatScreen chat)((dev.mineagent.runtime.neoforge.mixin.client.ChatSuggestionsAccess)chat).mineagent$suggestions().updateCommandInfo();}
    @SubscribeEvent public static void tick(ClientTickEvent.Post event){var mc=Minecraft.getInstance();if(!current()){connection=mc.getConnection()==null?null:mc.getConnection().getConnection();level=mc.level;names=List.of();request=null;screen=null;sentAt=0;}if(mc.player==null||!(mc.screen instanceof ChatScreen)){screen=null;return;}long now=System.currentTimeMillis();if(screen==mc.screen&&now-sentAt<2000)return;screen=mc.screen;sentAt=now;request=UUID.randomUUID();nextPage=0;responseWorld=null;incoming.clear();ClientPacketDistributor.sendToServer(new MineAgentPayloads.AgentNamesRequest(request));}
}
