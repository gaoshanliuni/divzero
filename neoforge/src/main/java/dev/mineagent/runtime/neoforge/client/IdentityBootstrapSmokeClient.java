package dev.mineagent.runtime.neoforge.client;

import dev.mineagent.runtime.neoforge.*;
import dev.mineagent.runtime.neoforge.client.nativeui.*;
import dev.mineagent.runtime.neoforge.mixin.client.ChatHistoryAccess;
import dev.mineagent.runtime.neoforge.network.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.network.chat.*;
import net.neoforged.bus.api.SubscribeEvent;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Real bootstrap command, signed consent and saved AI restoration, in an explicitly isolated fixture only. */
@net.neoforged.fml.common.EventBusSubscriber(modid="mineagent_runtime",value=net.neoforged.api.distmarker.Dist.CLIENT)
public final class IdentityBootstrapSmokeClient {
    private static int ticks,stage,after;private static boolean done,busy;private static Object connection;private static UUID scope,agent;
    private static final List<String> checks=new ArrayList<>();private static final com.fasterxml.jackson.databind.ObjectMapper JSON=new com.fasterxml.jackson.databind.ObjectMapper();
    private static Minecraft mc(){return Minecraft.getInstance();}
    private static String mode(){return System.getProperty("mineagent.identityBootstrapSmoke","");}
    private static Path game(){return mc().gameDirectory.toPath();}
    private static void require(boolean value,String error){if(!value)throw new IllegalStateException(error);}
    private static String choiceButton(Component c,String action){if(c.getStyle().getClickEvent() instanceof ClickEvent.RunCommand r&&r.command().startsWith("/divzero_setup bootstrap_"+action+" "))return r.command();for(var child:c.getSiblings()){var found=choiceButton(child,action);if(found!=null)return found;}return null;}
    private static String choiceButton(String action){for(var m:((ChatHistoryAccess)mc().gui.getChat()).mineagent$messages()){var c=choiceButton(m.content(),action);if(c!=null)return c;}throw new IllegalStateException("BOOTSTRAP_CHOICE_MISSING");}
    private static void command(String text){var chat=new ChatScreen("",false);mc().setScreen(chat);chat.handleChatInput(text,false);mc().setScreen(null);}
    private static long prompts(){return ((ChatHistoryAccess)mc().gui.getChat()).mineagent$messages().stream().filter(m->choiceButton(m.content(),"enable")!=null).count();}
    private static void finish(Throwable error){if(done)return;done=true;try{var out=game().resolve("identity-bootstrap").resolve(mode());Files.createDirectories(out);Files.writeString(out.resolve("result.json"),JSON.writeValueAsString(Map.of("status",error==null?"PASS":"FAILED","stage",stage,"error",error==null?"":error.toString(),"checks",checks,"reconnected",false,"modelCalls",0)));}catch(Exception ignored){}mc().stop();}
    private static <T> void server(java.util.concurrent.Callable<T> operation,java.util.function.Consumer<T> accept){busy=true;mc().getSingleplayerServer().submit(()->{try{return operation.call();}catch(Exception e){throw new CompletionException(e);}}).whenComplete((value,error)->mc().execute(()->{busy=false;if(error!=null)finish(error);else try{accept.accept(value);}catch(Throwable failure){finish(failure);}}));}
    @SubscribeEvent public static void tick(net.neoforged.neoforge.client.event.ClientTickEvent.Post event){
        if(mode().isEmpty()||done)return;
        try{
            if(++ticks>5000)throw new IllegalStateException("IDENTITY_BOOTSTRAP_TIMEOUT_"+stage);
            if(mc().player==null||mc().getSingleplayerServer()==null||busy)return;
            if(connection==null)connection=mc().getConnection();require(connection==mc().getConnection(),"RECONNECT_REQUIRED");
            var server=mc().getSingleplayerServer();UUID player=mc().player.getUUID();
            if(stage==0){
                if(mode().equals("declined")){if(ticks<150)return;require(prompts()==0,"DECLINED_WORLD_REPROMPTED");server(()->{var p=server.getPlayerList().getPlayer(player);require(WorldIdentityRuntime.pendingDisabled(p)&&!WorldIdentityRuntime.ready(server)&&!MineAgentRuntimeServices.workerReady(server),"PENDING_DECLINE_NOT_PERSISTED");server.getPlayerList().op(p.nameAndId());return true;},v->{checks.add("pre-bootstrap-disable-survives-rejoin-without-prompt");MineAgentClientTrustPrompt.showChoice(true);command(choiceButton("enable"));stage=2;});return;}

                if(mode().equals("rejoin")){if(!MineAgentClientTrustPrompt.enabled()||!NativeWorkspaceConnection.ready())return;require(prompts()==0,"IDENTITY_REPROMPTED_AFTER_REJOIN");checks.add("cold-rejoin-enabled-without-prompt");stage=4;return;}
                if(ticks<100)return;
                server(()->{require(!WorldIdentityRuntime.ready(server),"FIXTURE_NOT_PENDING");require(!MineAgentRuntimeServices.workerReady(server),"WORKER_STARTED_BEFORE_IDENTITY");server.getPlayerList().op(server.getPlayerList().getPlayer(player).nameAndId());return true;},v->{checks.add("unresolved-save-without-world-services");stage=1;after=ticks+230;});return;
            }
            if(stage==1){
                if(ticks%40==0)net.neoforged.neoforge.client.network.ClientPacketDistributor.sendToServer(new MineAgentPayloads.PanelRequest());
                if(ticks<after)return;require(prompts()==1,"BOOTSTRAP_PROMPT_SPAM_"+prompts());checks.add("repeated-panel-requests-one-prompt");
                require(((ChatHistoryAccess)mc().gui.getChat()).mineagent$messages().stream().noneMatch(m->m.content().getString().contains("存档身份")||m.content().getString().contains("/ai identity")),"IDENTITY_TEXT_WAS_DISPLAYED");
                if(mode().equals("fresh")){command(choiceButton("disable"));stage=7;after=ticks+20;}else{command(choiceButton("enable"));stage=2;}return;
            }
            if(stage==7&&ticks>=after){server(()->{var p=server.getPlayerList().getPlayer(player);require(!WorldIdentityRuntime.ready(server)&&!MineAgentRuntimeServices.workerReady(server)&&WorldIdentityRuntime.pendingDisabled(p),"DISABLE_STARTED_SERVICES_OR_NOT_SAVED");return true;},v->{checks.add("normal-disable-choice-before-world-bootstrap");command("/ai accept");stage=2;});return;}
            if(stage==2){if(!MineAgentClientTrustPrompt.enabled()||!NativeWorkspaceConnection.ready())return;
                server(()->{require(WorldIdentityRuntime.ready(server)&&MineAgentRuntimeServices.workerReady(server),"SERVICES_NOT_STARTED_LIVE");scope=MineAgentRuntimeServices.worldId(server);return true;},v->{checks.add("one-command-starts-services-and-trusted-workspace");if(mode().equals("fresh")||mode().equals("declined")){mc().player.connection.sendCommand("ai create HotIdentityProbe");}stage=3;after=ticks+15;});return;
            }
            if(stage==3&&ticks>=after){stage=4;}
            if(stage==4){
                server(()->{var p=server.getPlayerList().getPlayer(player);var definitions=MineAgentRuntimeServices.bodies(server).definitions().stream().filter(d->d.displayName().equals("HotIdentityProbe")).toList();if(definitions.size()!=1)return false;agent=definitions.getFirst().agentId();if(MineAgentRuntimeServices.bodies(server).body(agent).isEmpty())return false;scope=MineAgentRuntimeServices.worldId(server);require(MineAgentRuntimeServices.permissions(server).trustedActions(player).isEmpty(),"ACCEPT_GRANTED_PRIVILEGES");
                    var record=game().resolve("identity-fixture-record.json");if(mode().equals("fresh")||mode().equals("declined")){var original=JSON.readTree(Files.readString(game().resolve("identity-original.json")));require(!scope.toString().equals(original.path("scopeId").asText()),"COPIED_IDENTITY_REUSED");Files.writeString(record,JSON.writeValueAsString(Map.of("scope",scope,"agent",agent)));}
                    else{var saved=JSON.readTree(Files.readString(record));require(scope.toString().equals(saved.path("scope").asText())&&agent.toString().equals(saved.path("agent").asText()),"MOVED_WORLD_LOST_ITS_AI_OR_SCOPE");}return true;
                },found->{if(found){checks.add(mode().equals("fresh")||mode().equals("declined")?"independent-import-and-immediate-ai-command":"original-world-scope-and-ai-restored");stage=5;}});return;
            }
            if(stage==5){busy=true;NativeWorkspaceConnection.command("conversation.write",Map.of("kind","create","agentId",agent.toString(),"title","即时接入验证"),UUID.randomUUID()).whenComplete((receipt,error)->mc().execute(()->{busy=false;try{require(error==null&&receipt.code()==dev.mineagent.runtime.api.ui.UiProtocol.Code.APPLIED,"LIVE_WORKSPACE_COMMAND_FAILED");checks.add("real-workspace-write-without-reconnect");finish(null);}catch(Throwable failure){finish(failure);}}));stage=6;}
        }catch(Throwable failure){finish(failure);}
    }
    private IdentityBootstrapSmokeClient(){}
}
