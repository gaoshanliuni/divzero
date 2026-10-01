package dev.mineagent.runtime.neoforge.client;

import dev.mineagent.runtime.api.ui.UiProtocol;
import dev.mineagent.runtime.neoforge.client.nativeui.*;
import dev.mineagent.runtime.neoforge.mixin.client.ChatHistoryAccess;
import dev.mineagent.runtime.neoforge.network.PanelSnapshotInbox;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.network.chat.*;
import net.minecraft.world.level.GameType;
import net.neoforged.bus.api.SubscribeEvent;
import java.nio.file.*;
import java.util.*;

/** Opt-in real LAN clients. No pre-trust, permission grants, providers, or production world writes. */
@net.neoforged.fml.common.EventBusSubscriber(modid="mineagent_runtime",value=net.neoforged.api.distmarker.Dist.CLIENT)
public final class OneClickActivationSmokeClient {
    private static int ticks,stage,after;private static boolean done,busy,guestSeen;private static Object connection;
    private static String stale;private static UUID session,guestId;private static final List<String> checks=new ArrayList<>();
    private static Minecraft mc(){return Minecraft.getInstance();}
    private static String mode(){return System.getProperty("mineagent.oneClickActivationSmoke","");}
    private static boolean host(){return mode().equals("host");}
    private static void require(boolean value,String reason){if(!value)throw new IllegalStateException(reason);}
    private static String state(){return PanelSnapshotInbox.snapshot().values().getOrDefault("runtime.activation.state","");}
    private static String button(Component text,String action){if(text.getStyle().getClickEvent() instanceof ClickEvent.RunCommand run&&run.command().startsWith("/divzero_setup "+action+" "))return run.command();for(var child:text.getSiblings()){String found=button(child,action);if(found!=null)return found;}return null;}
    private static String button(String action){for(var message:((ChatHistoryAccess)mc().gui.getChat()).mineagent$messages()){String found=button(message.content(),action);if(found!=null)return found;}throw new IllegalStateException("BUTTON_MISSING_"+action);}
    private static void command(String command){var screen=new ChatScreen("",false);mc().setScreen(screen);screen.handleChatInput(command,false);mc().setScreen(null);}
    private static void write(String name,Map<String,Object> values)throws Exception{Files.writeString(mc().gameDirectory.toPath().resolve(name),new com.google.gson.Gson().toJson(values));}
    private static void finish(Throwable error){if(done)return;done=true;try{write("one-click-activation.json",Map.of("status",error==null?"PASS":"FAILED","mode",mode(),"stage",stage,"checks",checks,"error",error==null?"":error.toString(),"modelCalls",0,"reconnected",false));}catch(Exception ignored){}mc().stop();}
    @SubscribeEvent public static void tick(net.neoforged.neoforge.client.event.ClientTickEvent.Post event){
        if(mode().isBlank()||done)return;
        try{
            if(++ticks>7200)throw new IllegalStateException("ACTIVATION_TIMEOUT_"+stage);
            if(mc().player==null||state().isBlank()||busy)return;
            if(connection==null)connection=mc().getConnection();require(connection==mc().getConnection(),"RECONNECT_REQUIRED");
            if(stage==0){require(state().equals("UNDECIDED")&&!MineAgentClientTrustPrompt.enabled(),"PRE_ENABLED");stale=button("enable");after=ticks+420;stage=1;return;}
            // Deliberately wait past old startup-only client handshake deadlines.
            if(stage==1&&ticks>=after){command(host()?"/ai accept":stale);stage=2;return;}
            if(stage==2){if(!MineAgentClientTrustPrompt.enabled()||!NativeWorkspaceConnection.ready())return;checks.add(host()?"typed-accept-trust-and-workspace":"button-trust-and-workspace");
                if(!host())require(!Boolean.parseBoolean(PanelSnapshotInbox.snapshot().values().get("permission.manage_providers")),"GUEST_GAINED_ADMIN");
                NativeWorkspaceScreen.open();require(mc().screen instanceof NativeWorkspaceScreen,"F2_UNAVAILABLE");session=NativeWorkspaceConnection.current().sessionId();command("/ai accpet");after=ticks+30;stage=3;return;}
            if(stage==3&&ticks>=after){require(MineAgentClientTrustPrompt.enabled()&&NativeWorkspaceConnection.ready()&&session.equals(NativeWorkspaceConnection.current().sessionId()),"REPEAT_ACCEPT_RESET_SESSION");checks.add("alias-repeat-preserves-session");MineAgentClientTrustPrompt.showChoice(true);command(button("disable"));stage=4;return;}
            if(stage==4){if(!state().equals("DISABLED"))return;require(!MineAgentClientTrustPrompt.enabled()&&!NativeWorkspaceConnection.ready(),"DISABLE_NOT_LIVE");command(stale);after=ticks+15;stage=5;return;}
            if(stage==5&&ticks>=after){require(state().equals("DISABLED"),"STALE_BUTTON_ACCEPTED");checks.add("disable-and-stale-button");command("/ai accept");stage=6;return;}
            if(stage==6){if(!MineAgentClientTrustPrompt.enabled()||!NativeWorkspaceConnection.ready())return;busy=true;
                NativeWorkspaceConnection.command("shell.read",Map.of(),UUID.randomUUID()).whenComplete((receipt,error)->mc().execute(()->{busy=false;try{require(error==null&&receipt.code()==UiProtocol.Code.OBSERVED,"READ_AFTER_REENABLE_FAILED");checks.add("reenable-and-real-shell-read");stage=7;}catch(Throwable failure){finish(failure);}}));return;}
            if(stage==7){
                if(!host()){finish(null);return;}
                var server=mc().getSingleplayerServer();require(server!=null,"HOST_SERVER_MISSING");server.setUsesAuthentication(false);
                require(server.publishServer(GameType.SURVIVAL,false,Integer.getInteger("mineagent.activationPort",25584)),"LAN_PUBLISH_FAILED");
                write("activation-lan-ready.json",Map.of("port",Integer.getInteger("mineagent.activationPort",25584),"status","READY"));stage=8;return;
            }
            if(stage==8){
                var server=mc().getSingleplayerServer();busy=true;UUID owner=mc().player.getUUID();server.submit(()->{
                    var others=server.getPlayerList().getPlayers().stream().filter(p->!p.getUUID().equals(owner)).toList();
                    if(!others.isEmpty()){guestSeen=true;guestId=others.getFirst().getUUID();require(dev.mineagent.runtime.neoforge.WorldActivationRuntime.state(server.getPlayerList().getPlayer(owner))==dev.mineagent.runtime.core.permission.WorldActivation.State.ENABLED,"GUEST_CHANGED_HOST_STATE");}
                    if(guestSeen&&others.isEmpty()){
                        var permissions=dev.mineagent.runtime.neoforge.MineAgentRuntimeServices.permissions(server);
                        require(permissions.trustedActions(owner).isEmpty()&&permissions.trustedActions(guestId).isEmpty(),"ACTIVATION_CHANGED_GRANTS");
                        var state=dev.mineagent.runtime.core.permission.WorldActivation.state(dev.mineagent.runtime.neoforge.MineAgentRuntimeServices.config(server).snapshot().values(),dev.mineagent.runtime.neoforge.MineAgentRuntimeServices.worldId(server),guestId);
                        require(state==dev.mineagent.runtime.core.permission.WorldActivation.State.ENABLED,"GUEST_CHOICE_NOT_PERSISTED");return true;
                    }return false;
                }).whenComplete((finished,error)->mc().execute(()->{busy=false;if(error!=null)finish(error);else if(finished){checks.add("real-lan-guest-isolation-and-persistence");finish(null);}}));
            }
        }catch(Throwable error){finish(error);}
    }
    private OneClickActivationSmokeClient(){}
}
