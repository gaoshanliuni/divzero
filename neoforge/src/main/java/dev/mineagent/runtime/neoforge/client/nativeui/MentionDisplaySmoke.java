package dev.mineagent.runtime.neoforge.client.nativeui;
import com.fasterxml.jackson.databind.*;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.sun.net.httpserver.HttpServer;
import dev.mineagent.runtime.neoforge.MineAgentRuntimeServices;
import dev.mineagent.runtime.neoforge.body.MineAgentConnection;
import dev.mineagent.runtime.neoforge.ui.*;
import dev.mineagent.runtime.neoforge.network.UiPayloads;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.*;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.server.level.*;
import net.minecraft.server.network.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.nio.charset.StandardCharsets;

/** One real native client plus a registered server-side packet witness; no paid model or public listener. */
@net.neoforged.fml.common.EventBusSubscriber(modid="mineagent_runtime",value=net.neoforged.api.distmarker.Dist.CLIENT)
public final class MentionDisplaySmoke {
    private record Step(String name,Supplier<CompletableFuture<Boolean>> run){}
    private static final Deque<Step> steps=new ArrayDeque<>();private static final List<Object> evidence=new ArrayList<>();
    private static final ObjectMapper JSON=new ObjectMapper();private static UUID agent,conversation,operation;private static HttpServer http;private static Witness witness;
    private static CompletableFuture<Map<String,Object>> result;private static volatile Throwable failure;private static int tick,started;private static boolean busy,layoutScrolled;
    private static final class Witness extends ServerPlayer {
        final List<Component> messages=new ArrayList<>();final List<UiPayloads.Event> updates=new ArrayList<>();
        Witness(net.minecraft.server.MinecraftServer s,ServerLevel level){super(s,level,new com.mojang.authlib.GameProfile(UUID.randomUUID(),"ChatWitness"),ClientInformation.createDefault());}
        @Override public void sendChatMessage(OutgoingChatMessage message,boolean filtered,ChatType.Bound type){messages.add(message.content());super.sendChatMessage(message,filtered,type);}
        void clear(){messages.clear();updates.clear();}
        boolean saw(String marker){return messages.stream().anyMatch(m->m.getString().contains(marker))||updates.stream().anyMatch(e->e.json().contains(marker));}
    }
    private static final class RecordingListener extends ServerGamePacketListenerImpl {
        private final Witness witness;
        RecordingListener(net.minecraft.server.MinecraftServer s,MineAgentConnection c,Witness p){super(s,c,p,CommonListenerCookie.createInitial(p.getGameProfile(),false));witness=p;}
        @Override public void send(Packet<?> packet,io.netty.channel.ChannelFutureListener completion){if(packet instanceof ClientboundCustomPayloadPacket p&&p.payload() instanceof UiPayloads.Event e)witness.updates.add(e);}
    }
    private static Minecraft mc(){return Minecraft.getInstance();}
    private static void require(boolean yes,String error){if(!yes)throw new IllegalStateException(error);}
    private static <T> CompletableFuture<T> server(Function<ServerPlayer,T> action){var f=new CompletableFuture<T>();var s=mc().getSingleplayerServer();var id=mc().player.getUUID();s.submit(()->action.apply(s.getPlayerList().getPlayer(id))).whenComplete((v,e)->mc().execute(()->{if(e!=null)f.completeExceptionally(e);else f.complete(v);}));return f;}
    private static void step(String name,Supplier<CompletableFuture<Boolean>> action){steps.add(new Step(name,action));}
    private static void act(String name,Supplier<CompletableFuture<?>> action){step(name,()->action.get().thenApply(v->{if(v!=null)evidence.add(Map.of("step",name,"value",v));return true;}));}
    private static CompletableFuture<Boolean> yes(){return CompletableFuture.completedFuture(true);}
    private static UIElement find(UIElement root,String id){if(root.getId().equals(id))return root;for(var child:root.getChildren()){var found=find(child,id);if(found!=null)return found;}return null;}
    private static NativeWorkspaceScreen host(){return (NativeWorkspaceScreen)mc().screen;}
    private static void clickDisplay(String mode){NativeBehaviorPanel.smokeClickElement(host().smokeRoot(),"聊天展示："+mode,false);NativeBehaviorPanel.smokeClickElement(host().smokeRoot(),"聊天展示："+mode,true);}
    private static void mode(ServerPlayer p,boolean shared){var current=ServerMentionDisplay.read(p.level().getServer(),agent);ServerMentionDisplay.write(p,Map.of("kind","chat_display","agentId",agent.toString(),"displayRevision",Long.toString(current.revision()),"mode",shared?"PUBLIC":"PRIVATE"));}
    private static void incoming(ServerPlayer p,String marker,boolean expectedPublic){
        try{witness.clear();String raw="@"+MineAgentRuntimeServices.bodies(p.level().getServer()).body(agent).orElseThrow().getGameProfile().name()+" "+marker;
            var event=new net.neoforged.neoforge.event.ServerChatEvent(p,raw,Component.literal(raw));net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(event);require(event.isCanceled()!=expectedPublic,"MENTION_INPUT_AUDIENCE");
            if(!event.isCanceled())p.level().getServer().getPlayerList().broadcastChatMessage(PlayerChatMessage.unsigned(p.getUUID(),raw),p,ChatType.bind(ChatType.CHAT,p));
            var store=ServerConversations.get(p.level().getServer()).store();conversation=store.nativeConversation(p.getUUID(),agent).conversationId();operation=store.context(p.getUUID(),agent,conversation,null).orElseThrow().operationId();
        }catch(Exception e){throw new CompletionException(e);}
    }
    private static void finished(String marker,boolean shared){step("reply-"+marker,()->server(p->{try{
        var store=ServerConversations.get(p.level().getServer()).store();var usage=store.context(p.getUUID(),agent,conversation,null).orElseThrow();require(!Set.of("FAILED","CANCELLED","INTERRUPTED").contains(usage.requestState()),"CHAT_FAILED_"+usage.errorCode());if(!usage.requestState().equals("COMPLETE")||tick-started<30)return false;
        if(shared){if(!witness.saw("REPLY_"+marker))return false;boolean complete=false;for(var event:witness.updates)if(event.requestId().equals(operation)&&event.channel().equals("nativeChatStream")){var value=JSON.readTree(event.json());require(value.path("thinking").asText().isEmpty()&&value.path("thinkingOffset").asInt()==0&&value.path("observer").asBoolean(),"PRIVATE_OBSERVER_FIELDS");complete|=value.path("done").asBoolean();}if(!complete)return false;}
        else require(!witness.saw(marker),"PRIVATE_TURN_BROADCAST");
        require(store.chunk(p.getUUID(),agent,conversation,usage.assistantMessageId(),store.message(p.getUUID(),agent,conversation,usage.assistantMessageId()).revision(),0,4096).text().contains("REPLY_"+marker),"REQUESTER_REPLY_MISSING");
        evidence.add(Map.of("marker",marker,"observerReceived",shared,"operation",operation));return true;
    }catch(Exception e){throw new CompletionException(e);}}));}
    private static void provider(com.sun.net.httpserver.HttpExchange exchange){try{
        var input=JSON.readTree(exchange.getRequestBody().readNBytes(4*1024*1024));String marker="TITLE";
        if(input.path("tools").isArray())for(var message:input.path("messages"))if(message.path("role").asText().equals("user")){var matcher=java.util.regex.Pattern.compile("CHAT_TEST_[A-Z_]+").matcher(message.path("content").asText());while(matcher.find())marker=matcher.group();}
        if(marker.equals("CHAT_TEST_INFLIGHT"))Thread.sleep(1200);
        if(!input.path("stream").asBoolean()){byte[] out=JSON.writeValueAsBytes(Map.of("model","controlled-mention-display","choices",List.of(Map.of("message",Map.of("content","聊天展示验证")))));exchange.sendResponseHeaders(200,out.length);exchange.getResponseBody().write(out);return;}
        exchange.getResponseHeaders().set("Content-Type","text/event-stream");exchange.sendResponseHeaders(200,0);
        for(var delta:List.of(Map.of("reasoning_content","PRIVATE_REASONING_"+marker),Map.of("content","REPLY_"+marker))){exchange.getResponseBody().write(("data: "+JSON.writeValueAsString(Map.of("model","controlled-mention-display","choices",List.of(Map.of("delta",delta))))+"\n\n").getBytes(StandardCharsets.UTF_8));exchange.getResponseBody().flush();}
        exchange.getResponseBody().write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
    }catch(Exception e){if(result!=null&&!result.isDone())failure=e;}finally{exchange.close();}}
    public static CompletableFuture<Map<String,Object>> run(UUID id){
        if(!Boolean.getBoolean("mineagent.skillSmoke")||result!=null)throw new IllegalStateException("SMOKE_DISABLED");result=new CompletableFuture<>();agent=id;
        try{http=HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);http.createContext("/v1/chat/completions",MentionDisplaySmoke::provider);http.setExecutor(Executors.newVirtualThreadPerTaskExecutor());http.start();}catch(Exception e){result.completeExceptionally(e);return result;}
        act("new-ai-default-public-and-witness",()->server(p->{var s=p.level().getServer();require(ServerMentionDisplay.shared(s,agent),"NEW_AI_NOT_PUBLIC");var config=MineAgentRuntimeServices.config(s);require(config.apply(new dev.mineagent.runtime.api.config.ConfigPatch(config.snapshot().revision(),Map.of("provider.openai.enabled","true","provider.openai.baseUrl","http://127.0.0.1:"+http.getAddress().getPort()+"/v1/","provider.openai.model","controlled-mention-display","provider.openai.apiKey","fixture-only","voice.output.enabled","false")),true).accepted(),"PROVIDER_FIXTURE_CONFIG");witness=new Witness(s,p.level());var connection=new MineAgentConnection();s.getPlayerList().placeNewPlayer(connection,witness,CommonListenerCookie.createInitial(witness.getGameProfile(),false));witness.connection=new RecordingListener(s,connection,witness);witness.setGameMode(net.minecraft.world.level.GameType.SPECTATOR);witness.connection.markClientLoaded();witness.clear();return Map.of("display",ServerMentionDisplay.read(s,agent),"witness","REGISTERED_SERVER_PLAYER_PACKET_BOUNDARY");}));
        step("client-ui-enabled",()->{if(dev.mineagent.runtime.neoforge.client.MineAgentClientTrustPrompt.enabled())return yes();dev.mineagent.runtime.neoforge.client.MineAgentClientTrustPrompt.smokeEnable();return CompletableFuture.completedFuture(false);});
        act("open-ai-cards",()->{NativeWorkspaceScreen.open();return yes();});
        step("workspace-ready",()->CompletableFuture.completedFuture(NativeWorkspaceConnection.ready()&&mc().screen instanceof NativeWorkspaceScreen));
        act("open-manager",()->{WorkspacePanels.agents(host());return yes();});
        step("delete-position-and-toggle",()->{var display=find(host().smokeRoot(),"agent-chat-display-"+agent);var remove=find(host().smokeRoot(),"agent-delete-"+agent);if(display==null||remove==null||remove.getSizeWidth()==0)return CompletableFuture.completedFuture(false);if(!layoutScrolled){for(var ancestor=display.getParent();ancestor!=null;ancestor=ancestor.getParent())if(ancestor instanceof com.lowdragmc.lowdraglib2.gui.ui.elements.ScrollerView scroll){scroll.verticalScroller.setNormalizedValue(1);break;}layoutScrolled=true;return CompletableFuture.completedFuture(false);}require(remove.getPositionX()>=display.getPositionX()+display.getSizeWidth(),"DELETE_NOT_RIGHT_OF_CHAT_DISPLAY");require(remove.getParent().getContentX()+remove.getParent().getContentWidth()-(remove.getPositionX()+remove.getSizeWidth())<12,"DELETE_NOT_AT_CARD_EDGE");require(((Button)display).text.getText().getString().contains("群聊"),"PUBLIC_LABEL_MISSING");if(mc().getWindow().getGuiScaledWidth()>=600){var rename=display.getParent().getChildren().stream().filter(n->n instanceof Button button&&button.text.getText().getString().equals("重命名")).findFirst().orElseThrow();require(Math.abs(display.getPositionY()-rename.getPositionY())<2,"CHAT_DISPLAY_NOT_IN_PREVIOUS_DELETE_ROW");}try{net.minecraft.client.Screenshot.takeScreenshot(mc().getMainRenderTarget(),image->{try(image){image.writeToFile(mc().gameDirectory.toPath().resolve("mention-display-layout.png"));}catch(Exception e){failure=e;}});}catch(Exception e){throw new CompletionException(e);}clickDisplay("群聊");return yes();});
        step("ui-toggle-saved-private",()->server(p->!ServerMentionDisplay.shared(p.level().getServer(),agent)));
        act("private-at",()->server(p->{incoming(p,"CHAT_TEST_PRIVATE",false);return true;}));finished("CHAT_TEST_PRIVATE",false);
        act("ui-toggle-public",()->{clickDisplay("私聊");return yes();});
        step("ui-toggle-saved-public",()->server(p->ServerMentionDisplay.shared(p.level().getServer(),agent)));
        act("public-at",()->server(p->{incoming(p,"CHAT_TEST_PUBLIC",true);return true;}));finished("CHAT_TEST_PUBLIC",true);
        act("default-non-at-stays-private",()->server(p->{try{witness.clear();ServerChatSettings.defaultReply(p,agent.toString());var event=new net.neoforged.neoforge.event.ServerChatEvent(p,"CHAT_TEST_DEFAULT",Component.literal("CHAT_TEST_DEFAULT"));net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(event);require(event.isCanceled(),"DEFAULT_CHAT_WAS_PUBLIC");operation=ServerConversations.get(p.level().getServer()).store().context(p.getUUID(),agent,conversation,null).orElseThrow().operationId();return true;}catch(Exception e){throw new CompletionException(e);}}));finished("CHAT_TEST_DEFAULT",false);
        act("workspace-message-stays-private",()->server(p->{try{witness.clear();var runtime=ServerConversations.get(p.level().getServer());var c=runtime.store().create(p.getUUID(),agent,UUID.randomUUID(),"私有工作区",false);conversation=c.conversationId();operation=UUID.randomUUID();return runtime.write(p,operation,Map.of("kind","send","agentId",agent.toString(),"conversationId",conversation.toString(),"expectedRevision",Long.toString(c.revision()),"text","CHAT_TEST_WORKSPACE"));}catch(Exception e){throw new CompletionException(e);}}));finished("CHAT_TEST_WORKSPACE",false);
        act("private-inflight-then-public-setting",()->server(p->{mode(p,false);incoming(p,"CHAT_TEST_INFLIGHT",false);mode(p,true);return true;}));finished("CHAT_TEST_INFLIGHT",false);
        act("non-owner-cannot-change-display",()->server(p->{try{ServerMentionDisplay.write(witness,Map.of("kind","chat_display","agentId",agent.toString(),"displayRevision",Long.toString(ServerMentionDisplay.read(p.level().getServer(),agent).revision()),"mode","PRIVATE"));throw new IllegalStateException("UNAUTHORIZED_DISPLAY_CHANGE");}catch(SecurityException expected){require(ServerMentionDisplay.shared(p.level().getServer(),agent),"DENIED_WRITE_CHANGED_SETTING");return true;}}));
        act("observer-stream-to-real-client",()->server(p->{operation=UUID.randomUUID();require(dev.mineagent.runtime.neoforge.chat.AiPlayerChat.streamAudience(witness,List.of(p),agent,operation,"body:0",Component.literal("OBSERVER_RENDER_CHECK")),"OBSERVER_NATIVE_SEND");var data=Map.<String,Object>of("agent",agent.toString(),"name","持续技能搭档","bodyOffset",0,"body","OBSERVER_RENDER_CHECK","done",false,"state","GENERATING","color","#FFFFFF","nativeParts",1,"nativeAllowed",true);try{net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(p,new UiPayloads.Event(operation,"nativeChatStream",JSON.writeValueAsString(dev.mineagent.runtime.core.conversation.PublicMentionUpdate.of(data))));}catch(Exception e){throw new CompletionException(e);}return true;}));
        step("observer-render-has-no-private-controls",()->{var messages=((dev.mineagent.runtime.neoforge.mixin.client.ChatHistoryAccess)mc().gui.getChat()).mineagent$messages();var entry=messages.stream().filter(m->m.content().getString().contains("OBSERVER_RENDER_CHECK")).findFirst();if(entry.isEmpty())return CompletableFuture.completedFuture(false);require(!entry.get().content().getString().contains("打断")&&!entry.get().content().getString().contains("PRIVATE_REASONING"),"OBSERVER_CONTROL_LEAK");return yes();});
        act("cleanup-witness",()->server(p->{p.level().getServer().getPlayerList().remove(witness);witness.discard();return true;}));return result;
    }
    @net.neoforged.bus.api.SubscribeEvent public static void tick(net.neoforged.neoforge.client.event.ClientTickEvent.Post event){if(result==null||result.isDone())return;tick++;if(failure!=null||tick-started>600){http.stop(0);result.completeExceptionally(new IllegalStateException((steps.isEmpty()?"DONE":steps.getFirst().name)+": "+Objects.toString(failure,"timeout"),failure));return;}if(steps.isEmpty()){http.stop(0);result.complete(Map.of("status","PASS","model","CONTROLLED_LOCAL_HTTP_NOT_MODEL","paidModelCalls",0,"evidence",evidence));return;}if(busy||tick%5!=0)return;busy=true;var next=steps.getFirst();try{next.run.get().whenComplete((ok,error)->{busy=false;if(error!=null){failure=error;return;}if(ok){evidence.add(Map.of("completed",next.name,"tick",tick));steps.removeFirst();started=tick;}});}catch(Exception e){failure=e;}}
    private MentionDisplaySmoke(){}
}
