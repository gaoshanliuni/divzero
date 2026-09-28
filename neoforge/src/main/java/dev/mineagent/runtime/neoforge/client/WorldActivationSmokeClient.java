package dev.mineagent.runtime.neoforge.client;

import dev.mineagent.runtime.neoforge.MineAgentRuntimeServices;
import dev.mineagent.runtime.neoforge.client.nativeui.*;
import dev.mineagent.runtime.neoforge.mixin.client.ChatHistoryAccess;
import dev.mineagent.runtime.neoforge.network.PanelSnapshotInbox;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.network.chat.*;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import java.nio.file.*;
import java.util.*;

/** Isolated opt-in fixture: uses the emitted chat commands, never pre-trusts or sends /ai accept. */
@EventBusSubscriber(modid="mineagent_runtime", value=Dist.CLIENT)
public final class WorldActivationSmokeClient {
    private static int ticks,stage,after;private static boolean done,busy;private static Object originalConnection;
    private static String oldButton,oldChallenge;private static UUID agent;private static final List<Object> evidence=new ArrayList<>();
    private static String mode(){return System.getProperty("mineagent.worldActivationSmoke","");}
    private static Path root()throws Exception{return Files.createDirectories(Minecraft.getInstance().gameDirectory.toPath().resolve("world-activation-smoke").resolve(mode()));}
    private static void require(boolean value,String code){if(!value)throw new IllegalStateException(code);}
    private static String state(){return PanelSnapshotInbox.snapshot().values().getOrDefault("runtime.activation.state","");}
    private static String button(Component text,String action){if(text.getStyle().getClickEvent() instanceof ClickEvent.RunCommand run&&run.command().startsWith("/divzero_setup "+action+" "))return run.command();for(var child:text.getSiblings()){String found=button(child,action);if(found!=null)return found;}return null;}
    private static String button(String action){for(var message:((ChatHistoryAccess)Minecraft.getInstance().gui.getChat()).mineagent$messages()){String value=button(message.content(),action);if(value!=null)return value;}throw new IllegalStateException("CHAT_BUTTON_MISSING_"+action);}
    private static void click(String command){var mc=Minecraft.getInstance();var screen=new ChatScreen("",false);mc.setScreen(screen);screen.handleChatInput(command,false);mc.setScreen(null);}
    private static void capture(String name)throws Exception{var path=root().resolve(name+".png");net.minecraft.client.Screenshot.takeScreenshot(Minecraft.getInstance().getMainRenderTarget(),image->{try(image){image.writeToFile(path);}catch(Exception error){fail(error);}});}
    @SubscribeEvent public static void tick(ClientTickEvent.Post event){
        if(mode().isEmpty()||done)return;var mc=Minecraft.getInstance();try{
            if(++ticks>2400)throw new IllegalStateException("ACTIVATION_TIMEOUT_STAGE_"+stage);
            if(mc.player==null||mc.getSingleplayerServer()==null||state().isBlank()||busy)return;
            if(originalConnection==null)originalConnection=mc.getConnection();require(originalConnection==mc.getConnection(),"UNEXPECTED_RECONNECT");
            if(stage==0){
                require(!(mc.screen instanceof WorkspaceSetupScreen),"AUTOMATIC_SETUP_SCREEN");
                if(mode().equals("disabled")){require(state().equals("DISABLED")&&!MineAgentClientTrustPrompt.enabled(),"DISABLED_NOT_RESTORED");require(((ChatHistoryAccess)mc.gui.getChat()).mineagent$messages().stream().noneMatch(m->button(m.content(),"enable")!=null),"DISABLED_REPROMPTED");finish();return;}
                if(mode().equals("enabled")){require(state().equals("ENABLED")&&MineAgentClientTrustPrompt.enabled(),"ENABLED_NOT_RESTORED");require(((ChatHistoryAccess)mc.gui.getChat()).mineagent$messages().stream().noneMatch(m->button(m.content(),"enable")!=null),"ENABLED_REPROMPTED");MineAgentClientTrustPrompt.showChoice(true);click(button("disable"));stage=90;return;}
                require(state().equals("UNDECIDED")&&!MineAgentClientTrustPrompt.enabled(),"FRESH_WORLD_ALREADY_ENABLED");oldButton=button("enable");oldChallenge=PanelSnapshotInbox.snapshot().values().get("runtime.activation.challenge");button("disable");
                // Give this isolated fixture an operator before either choice; activation itself must grant nothing.
                busy=true;UUID player=mc.player.getUUID();var server=mc.getSingleplayerServer();
                server.submit(()->{var owner=server.getPlayerList().getPlayer(player);server.getPlayerList().op(owner.nameAndId());require(!MineAgentRuntimeServices.permissions(server).allowed(player,true,dev.mineagent.runtime.api.permission.PermissionAction.CREATE_AGENT),"OP_BYPASSED_UNDECIDED");return true;})
                    .whenComplete((value,error)->mc.execute(()->{busy=false;if(error!=null){fail(error);return;}evidence.add(Map.of("fixtureOperatorBeforeConsent",true));mc.setScreen(new ChatScreen("",false));after=ticks+15;stage=1;}));return;
            }
            if(stage==1&&ticks>=after){capture("01-first-entry-buttons");click(button("disable"));stage=2;return;}
            if(stage==2){if(!state().equals("DISABLED"))return;require(!MineAgentClientTrustPrompt.enabled(),"CLIENT_NOT_DISABLED");click(oldButton);mc.player.connection.sendCommand("ai activation enable "+oldChallenge);after=ticks+20;stage=3;return;}
            if(stage==3&&ticks>=after){require(state().equals("DISABLED"),"STALE_BUTTON_ENABLED_WORLD");MineAgentClientTrustPrompt.showChoice(true);click(button("enable"));stage=4;return;}
            if(stage==4){if(!MineAgentClientTrustPrompt.enabled())return;require(state().equals("ENABLED"),"SERVER_NOT_ENABLED");NativeWorkspaceScreen.open();mc.player.connection.sendCommand("ai create ActivationProbe");stage=5;after=ticks+20;return;}
            if(stage==5&&ticks>=after&&NativeWorkspaceConnection.ready()){
                busy=true;UUID player=mc.player.getUUID();var server=mc.getSingleplayerServer();server.submit(()->{
                    var p=server.getPlayerList().getPlayer(player);var definitions=MineAgentRuntimeServices.bodies(server).definitions().stream().filter(d->d.displayName().equals("ActivationProbe")).toList();require(definitions.size()==1,"CREATE_NOT_IMMEDIATELY_AVAILABLE");
                    require(MineAgentRuntimeServices.permissions(server).trustedActions(player).isEmpty(),"ENABLE_GRANTED_PRIVILEGES");
                    require(dev.mineagent.runtime.neoforge.WorldActivationRuntime.decide(p.createCommandSourceStack(),false,oldChallenge)==0,"SERVER_STALE_ACCEPTED");
                    return definitions.getFirst().agentId();
                }).whenComplete((id,error)->mc.execute(()->{busy=false;if(error!=null){fail(error);return;}agent=id;stage=6;}));return;
            }
            if(stage==6){busy=true;NativeWorkspaceConnection.command("conversation.write",Map.of("kind","create","agentId",agent.toString(),"title","即时启用测试"),UUID.randomUUID()).whenComplete((receipt,error)->{busy=false;try{if(error!=null)throw new IllegalStateException(error);require(receipt.code()==dev.mineagent.runtime.api.ui.UiProtocol.Code.APPLIED,"CONVERSATION_NOT_IMMEDIATE_"+receipt);evidence.add(receipt);stage=7;after=ticks+20;}catch(Exception failure){fail(failure);}});return;}
            if(stage==7&&ticks>=after){capture("02-enabled-workspace");require(originalConnection==mc.getConnection(),"RECONNECT_REQUIRED");finish();}
            if(stage==90&&state().equals("DISABLED"))finish();
        }catch(Exception failure){fail(failure);}
    }
    private static void finish()throws Exception{Files.writeString(root().resolve("result.json"),new com.google.gson.Gson().toJson(Map.of("status","PASS","mode",mode(),"modelCalls",0,"reconnected",false,"state",state(),"evidence",evidence)));done=true;Minecraft.getInstance().stop();}
    private static void fail(Throwable failure){if(done)return;done=true;try{Files.writeString(root().resolve("failure.json"),new com.google.gson.Gson().toJson(Map.of("stage",stage,"error",failure.toString())));}catch(Exception ignored){}Minecraft.getInstance().stop();}
    private WorldActivationSmokeClient(){}
}
