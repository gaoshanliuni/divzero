package dev.mineagent.runtime.neoforge.ui;
import dev.mineagent.runtime.neoforge.MineAgentRuntimeServices;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
@EventBusSubscriber(modid="mineagent_runtime")
public final class DesktopWindowsSmokeServer {
    public static volatile boolean ready;public static volatile String failure="";
    @SubscribeEvent public static void tick(ServerTickEvent.Post event){if(!Boolean.getBoolean("mineagent.desktopWindowsSmoke")||ready||!failure.isEmpty())return;var s=event.getServer();var p=s.getPlayerList().getPlayers().stream().filter(v->!(v instanceof dev.mineagent.runtime.neoforge.body.MineAgentPlayer)).findFirst().orElse(null);if(p==null)return;
        try{s.getPlayerList().op(p.nameAndId());MineAgentRuntimeServices.bodies(s).createPersistentAt("桌面助手",p.getUUID(),s.overworld(),p.position().add(2,0,0));ready=true;}catch(Exception e){failure=e.toString();}
    }
}
