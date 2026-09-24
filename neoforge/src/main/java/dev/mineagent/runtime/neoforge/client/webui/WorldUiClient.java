package dev.mineagent.runtime.neoforge.client.webui;
import dev.mineagent.runtime.api.ui.WorldUiProtocol;
import dev.mineagent.runtime.neoforge.network.UiPayloads;
import dev.mineagent.runtime.neoforge.content.RuntimeObjectEntity;
import net.minecraft.client.Minecraft;
import java.util.*;

/** Opens only a current server-issued physical interaction. No control-panel window and no implicit trust. */
public final class WorldUiClient {
    private static final com.google.gson.Gson JSON=new com.google.gson.Gson();private static Pending pending;
    private static final class Pending{final WorldUiProtocol.Launch launch;final Object connection,level;final long deadline;long nextHostAttempt=System.currentTimeMillis()+250;net.minecraft.client.gui.screens.Screen screen;boolean started;Pending(WorldUiProtocol.Launch launch,Object connection,Object level){this.launch=launch;this.connection=connection;this.level=level;this.deadline=Math.min(launch.expiresAt(),System.currentTimeMillis()+30_000);}}
    private WorldUiClient(){}
    public static boolean accept(UiPayloads.Event packet,Object source){
        if(!packet.channel().equals("worldUiLaunch"))return false;var mc=Minecraft.getInstance();
        try{
            var launch=JSON.fromJson(packet.json(),WorldUiProtocol.Launch.class);
            if(mc.player==null||mc.level==null||mc.getConnection()==null||mc.getConnection().getConnection()!=source||!launch.viewerId().equals(mc.player.getUUID())||!packet.requestId().equals(launch.id()))throw new SecurityException("WORLD_UI_LAUNCH_CONTEXT");
            if(!(mc.level.getEntity(launch.entityId()) instanceof RuntimeObjectEntity entity)||entity.header()==null||!entity.header().instance().equals(launch.instanceId())||!entity.header().part().equals(launch.part()))throw new SecurityException("WORLD_UI_ENTITY_MISSING");
            if(PackageContentClient.session(launch.viewId())!=null){WebGuiHostAdapter.INSTANCE.revealWorldView(launch.viewId());return true;}
            if(pending!=null){if(pending.launch.id().equals(launch.id()))return true;throw new IllegalStateException("WORLD_UI_OPEN_BUSY");}
            if(mc.screen!=null&&!(mc.screen instanceof WebGuiInteractionScreen))throw new IllegalStateException("WORLD_UI_PLAYER_BUSY");
            if(launch.expiresAt()<=System.currentTimeMillis())throw new IllegalStateException("WORLD_UI_LAUNCH_EXPIRED");
            pending=new Pending(launch,source,mc.level);WebGuiHostAdapter.INSTANCE.openStandalone();pending.screen=mc.screen;
        }catch(Exception failure){report(failure);}return true;
    }
    public static void tick(){
        var p=pending;if(p==null)return;var mc=Minecraft.getInstance();var host=WebGuiHostAdapter.INSTANCE;
        var next=dev.mineagent.runtime.client.webui.WorldUiOpenPolicy.next(sameContext(p),System.currentTimeMillis(),p.deadline,p.started,host.browser()!=null,host.ready(),UiClientSessions.current()!=null,PackagePreviewClient.idle(),p.nextHostAttempt);
        switch(next){
            case CANCEL->{pending=null;host.cancelPendingStandalone();return;}
            case TIMEOUT->{pending=null;host.cancelPendingStandalone();report(new IllegalStateException("WORLD_UI_OPEN_TIMEOUT"));return;}
            case WAIT->{return;}
            case OPEN_HOST->{
                p.nextHostAttempt=System.currentTimeMillis()+250;host.openStandalone();p.screen=mc.screen;
                if(host.browser()==null&&!host.diagnostic().startsWith("BROWSER_NOT_READY")){pending=null;host.cancelPendingStandalone();report(new IllegalStateException(host.diagnostic()));}
                return;
            }
            case OPEN_CONTENT->{ /* Single transfer; authorization remains checked at every asynchronous boundary. */ }
        }
        if(!(mc.screen instanceof WebGuiInteractionScreen)){pending=null;return;}
        p.started=true;PackagePreviewClient.openWorld(p.launch,()->current(p)).whenComplete((reply,error)->mc.execute(()->{
            if(pending!=p)return;pending=null;if(error!=null)report(error);
        }));
    }
    private static boolean sameContext(Pending p){var mc=Minecraft.getInstance();return pending==p&&mc.getConnection()!=null&&mc.getConnection().getConnection()==p.connection&&mc.level==p.level&&mc.screen==p.screen;}
    private static boolean current(Pending p){return sameContext(p)&&System.currentTimeMillis()<p.deadline;}
    private static void report(Throwable error){var mc=Minecraft.getInstance();String message=error.getMessage();if(message==null||!message.matches("[A-Z0-9_: .-]{1,120}"))message="WORLD_UI_UNAVAILABLE";if(mc.player!=null)mc.player.sendSystemMessage(net.minecraft.network.chat.Component.literal(dev.mineagent.runtime.neoforge.client.language.ClientLanguage.t("MineAgent 世界界面: ")+message));}
}
