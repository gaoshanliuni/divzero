package dev.mineagent.runtime.neoforge.client.nativeui;

import com.google.gson.*;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.*;
import dev.mineagent.runtime.neoforge.MineAgentRuntimeServices;
import dev.mineagent.runtime.neoforge.client.chat.*;
import dev.mineagent.runtime.neoforge.mixin.client.ChatHistoryAccess;
import dev.mineagent.runtime.neoforge.network.UiPayloads;
import dev.mineagent.runtime.neoforge.skill.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.network.chat.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** Real client rendering, pointer clicks and authoritative readback. Explicit isolated fixtures only. */
@net.neoforged.fml.common.EventBusSubscriber(modid="mineagent_runtime",value=net.neoforged.api.distmarker.Dist.CLIENT)
public final class OctoberFeedbackSmoke {
    private record Step(String name,Supplier<CompletableFuture<Boolean>> run){}
    private static final Deque<Step> steps=new ArrayDeque<>();private static final List<Object> evidence=new ArrayList<>();
    private static UUID agent,stream;private static CompletableFuture<Map<String,Object>> result;private static boolean busy;private static int tick,started;private static long received;
    private static final String BODY="正文探针"+"完整内容".repeat(180),THOUGHT="之前的思考\n"+"正在核对".repeat(80)+"思考末尾";
    private static Minecraft mc(){return Minecraft.getInstance();}
    private static void require(boolean yes,String error){if(!yes)throw new IllegalStateException(error);}
    private static <T> CompletableFuture<T> server(Function<ServerPlayer,T> action){var out=new CompletableFuture<T>();var s=mc().getSingleplayerServer();var id=mc().player.getUUID();s.submit(()->action.apply(s.getPlayerList().getPlayer(id))).whenComplete((v,e)->mc().execute(()->{if(e!=null)out.completeExceptionally(e);else out.complete(v);}));return out;}
    private static void step(String name,Supplier<CompletableFuture<Boolean>> run){steps.add(new Step(name,run));}
    private static void act(String name,Supplier<CompletableFuture<?>> run){step(name,()->run.get().thenApply(v->{if(v!=null)evidence.add(Map.of("step",name,"value",v));return true;}));}
    private static CompletableFuture<Boolean> yes(){return CompletableFuture.completedFuture(true);}
    private static UIElement root(){return mc().screen instanceof NativeWorkspaceScreen s?s.smokeRoot():((AgentProfileScreen)mc().screen).smokeRoot();}
    private static Button find(UIElement root,Predicate<Button> match){if(root instanceof Button b&&match.test(b))return b;for(var child:root.getChildren()){var found=find(child,match);if(found!=null)return found;}return null;}
    private static boolean click(Predicate<Button> match){
        var button=find(root(),match);if(button==null||!button.isActive()||button.getSizeWidth()<1)return false;
        float x=button.getPositionX()+button.getSizeWidth()/2,y=button.getPositionY()+button.getSizeHeight()/2;
        for(var p=button.getParent();p!=null;p=p.getParent())if(p instanceof ScrollerView scroll){float low=scroll.viewPort.getContentY(),high=low+scroll.viewPort.getContentHeight(),range=scroll.getContainerHeight()-scroll.viewPort.getContentHeight();if(range>0&&(y-button.getSizeHeight()/2<low||y+button.getSizeHeight()/2>high)){scroll.verticalScroller.setNormalizedValue(Math.clamp(scroll.verticalScroller.getNormalizedValue()+(y-(low+high)/2)/range,0,1));return false;}}
        var screen=(com.lowdragmc.lowdraglib2.gui.holder.ModularUIScreen)mc().screen;screen.modularUI.refreshHoveredElementAtScreen(x,y);boolean hovered=false;for(var p=screen.modularUI.getLastHoveredElement();p!=null;p=p.getParent())if(p==button)hovered=true;if(!hovered)return false;
        var widget=com.lowdragmc.lowdraglib2.gui.ui.ModularUIClientAccess.getWidget(screen.modularUI);var event=new net.minecraft.client.input.MouseButtonEvent(x,y,new net.minecraft.client.input.MouseButtonInfo(0,0));widget.mouseClicked(event,false);widget.mouseReleased(event);return true;
    }
    private static void clickText(String text){step("click-"+text,()->CompletableFuture.completedFuture(click(b->b.text.getText().getString().equals(text))));}
    private static void clickId(String id){step("click-"+id,()->CompletableFuture.completedFuture(click(b->b.getId().equals(id))));}
    private static GuiMessage message(){var list=((ChatHistoryAccess)mc().gui.getChat()).mineagent$messages().stream().filter(m->m.content().getString().contains("正文探针")).toList();require(list.size()==1,"STREAM_HISTORY_WAS_SPLIT_"+list.size());return list.getFirst();}
    private static CompletableFuture<?> stream(int offset,String body,int thinkingOffset,String thought,boolean done){return server(p->{var n=new JsonObject();n.addProperty("agent",agent.toString());n.addProperty("name","持续技能搭档");n.addProperty("bodyOffset",offset);n.addProperty("body",body);n.addProperty("thinkingOffset",thinkingOffset);n.addProperty("thinking",thought);n.addProperty("done",done);n.addProperty("state",done?"COMPLETE":"GENERATING");n.addProperty("color","#FFFFFF");net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(p,new UiPayloads.Event(stream,"nativeChatStream",n.toString()));return Map.of("bodyCharacters",body.length(),"done",done);});}
    private static void assertChat(boolean done){var message=message();require(message.source()!=net.minecraft.client.multiplayer.chat.GuiMessageSource.PLAYER,"PLAYER_CHAT_STYLE_REMAINED");require(message.content().getString().startsWith("[持续技能搭档]"),"AI_NAME_PREFIX_LOST");if(done)require(message.content().getString().contains(BODY+"正文尾部"),"BODY_TRUNCATED");else require(message.content().getString().contains("打断"),"INTERRUPT_LOST");
        var lines=NativeStreamingChat.lines(message,mc().font,300);require(lines!=null&&!lines.isEmpty(),"STREAM_LINES_MISSING");var first=new StringBuilder();var hover=new boolean[1];for(var line:lines)line.accept((i,style,cp)->{if(style.getHoverEvent() instanceof HoverEvent.ShowText h&&h.value().getString().matches(".*[0-9]{2}:[0-9]{2}.*"))hover[0]=true;return true;});lines.getFirst().accept((i,style,cp)->{first.appendCodePoint(cp);return true;});require(first.toString().contains("[思考]")&&first.toString().contains("思考末尾"),"THINKING_TAIL_MISSING_"+first);require(!first.toString().contains("之前的思考"),"THINKING_NOT_ONE_LINE_TAIL");require(hover[0],"TIMESTAMP_HOVER_MISSING");}
    private static void toggle(String field,String on,String off){clickText(on);step("saved-"+field+"-off",()->server(p->!value(ActorEnhancements.read(p,agent),field)));clickText(off);step("saved-"+field+"-on",()->server(p->value(ActorEnhancements.read(p,agent),field)));}
    private static boolean value(ActorEnhancements.Settings s,String field){return switch(field){case "boost"->s.boost();case "learning"->s.learning();case "neural"->s.neural();default->s.recovery();};}
    public static CompletableFuture<Map<String,Object>> run(UUID id){
        if(!Boolean.getBoolean("mineagent.skillSmoke")||result!=null)throw new IllegalStateException("ISOLATED_FIXTURE_ONLY");agent=id;stream=UUID.randomUUID();result=new CompletableFuture<>();
        act("enable-thinking",()->NativeChatPreferencesClient.save(true,((Number)NativeChatPreferencesClient.view().get("revision")).longValue()));
        act("stream-first-fragment",()->stream(0,BODY.substring(0,200),0,THOUGHT,false));
        step("first-fragment-visible-before-completion",()->{if(tick-started<5)return CompletableFuture.completedFuture(false);assertChat(false);received=((ChatMessageClock)(Object)message()).mineagent$receivedAt();return yes();});
        act("stream-long-fragment",()->stream(200,BODY.substring(200),THOUGHT.length(),"",false));
        step("long-stream-updates-in-place",()->{if(tick-started<5)return CompletableFuture.completedFuture(false);assertChat(false);require(((ChatMessageClock)(Object)message()).mineagent$receivedAt()==received,"STREAM_TIMESTAMP_CHANGED");return yes();});
        act("stream-completion",()->stream(BODY.length(),"正文尾部",THOUGHT.length(),"",true));
        step("complete-body-single-entry",()->{if(tick-started<5)return CompletableFuture.completedFuture(false);assertChat(true);return yes();});
        act("rich-copy-option",()->server(p->dev.mineagent.runtime.neoforge.chat.AiPlayerChat.send(p,agent,Component.literal("回归复制按钮").withStyle(s->s.withClickEvent(new ClickEvent.CopyToClipboard("回归内容"))))));
        step("copy-option-preserved",()->{var entries=((ChatHistoryAccess)mc().gui.getChat()).mineagent$messages();for(var m:entries)if(m.content().getString().contains("回归复制按钮")){var found=new boolean[1];m.content().visit((s,t)->{if(s.getClickEvent() instanceof ClickEvent.CopyToClipboard c&&c.value().equals("回归内容"))found[0]=true;return Optional.empty();},Style.EMPTY);require(found[0],"COPY_OPTION_LOST");return yes();}return CompletableFuture.completedFuture(false);});
        act("open-f2",()->{NativeWorkspaceScreen.open();return yes();});step("workspace-ready",()->CompletableFuture.completedFuture(NativeWorkspaceConnection.ready()&&mc().screen instanceof NativeWorkspaceScreen));
        act("open-behavior-panel",()->{NativeBehaviorPanel.open((NativeWorkspaceScreen)mc().screen,agent.toString(),"持续技能搭档");return yes();});step("behavior-read-ready",()->CompletableFuture.completedFuture(NativeBehaviorPanel.smokeReady(agent.toString())));
        clickText("Boost：关闭");step("boost-saved-on",()->server(p->ActorEnhancements.read(p,agent).boost()));clickText("Boost：开启");step("boost-saved-off",()->server(p->!ActorEnhancements.read(p,agent).boost()));
        toggle("learning","权重学习模式：开启","权重学习模式：关闭");toggle("neural","神经策略：开启","神经策略：关闭");toggle("recovery","自主脱困：开启","自主脱困：关闭");
        act("seed-reset-only-probes",()->server(p->{var body=MineAgentRuntimeServices.bodies(p.level().getServer()).body(agent).orElseThrow();for(int i=0;i<13;i++)LocalPolicyRuntime.outcome(body,new double[16],.4);return LocalPolicyRuntime.inspect(p,agent);}));
        clickText("恢复预训练权重");step("cancel-reset-modal",()->CompletableFuture.completedFuture(click(b->b.hasClass("__cancel-button__"))));
        act("cancel-preserves-probes",()->server(p->{require(((Number)LocalPolicyRuntime.inspect(p,agent).get("samples")).longValue()==13,"CANCEL_RESET_CHANGED_WEIGHTS");return true;}));
        clickText("恢复预训练权重");step("confirm-reset-modal",()->CompletableFuture.completedFuture(click(b->b.hasClass("__confirm-button__"))));
        step("confirmed-reset-clears-probes",()->server(p->((Number)LocalPolicyRuntime.inspect(p,agent).get("samples")).longValue()==0));
        act("open-ai-profile",()->{AgentProfileScreen.open(agent,"持续技能搭档");return yes();});
        step("profile-has-native-mode",()->CompletableFuture.completedFuture(find(root(),b->b.getId().equals("agent-game-mode")&&b.text.getText().getString().contains(" · "))!=null));
        for(var mode:List.of(GameType.CREATIVE,GameType.ADVENTURE,GameType.SPECTATOR,GameType.SURVIVAL)){clickId("agent-game-mode");step("native-mode-"+mode,()->{if(find(root(),b->b.getId().equals("agent-game-mode")&&b.text.getText().getString().endsWith(NativeUiTheme.state(mode.name())))==null)return CompletableFuture.completedFuture(false);return server(p->MineAgentRuntimeServices.bodies(p.level().getServer()).body(agent).orElseThrow().gameMode.getGameModeForPlayer()==mode);});}
        act("separate-before-teleport",()->server(p->{var b=MineAgentRuntimeServices.bodies(p.level().getServer()).body(agent).orElseThrow();b.teleportTo(p.level(),p.getX()+15,p.getY(),p.getZ(),Set.of(),0,0,true);return true;}));
        clickId("agent-teleport-owner");step("native-teleport-receipt",()->server(p->MineAgentRuntimeServices.bodies(p.level().getServer()).body(agent).orElseThrow().distanceTo(p)<1));
        act("close-panel",()->{mc().screen.onClose();return yes();});return result;
    }
    @net.neoforged.bus.api.SubscribeEvent public static void tick(net.neoforged.neoforge.client.event.ClientTickEvent.Post e){
        if(result==null||result.isDone())return;tick++;if(steps.isEmpty()){result.complete(Map.of("status","PASS","paidModelCalls",0,"evidence",evidence));return;}
        if(tick-started>500){result.completeExceptionally(new IllegalStateException("FEEDBACK_UI_TIMEOUT_"+steps.getFirst().name));return;}
        if(busy||tick%4!=0)return;busy=true;var next=steps.getFirst();try{next.run.get().whenComplete((ok,error)->mc().execute(()->{busy=false;if(error!=null){result.completeExceptionally(new IllegalStateException(next.name+": "+error,error));return;}if(ok){evidence.add(Map.of("completed",next.name,"tick",tick));steps.removeFirst();started=tick;}}));}catch(Exception error){busy=false;result.completeExceptionally(error);}
    }
    private OctoberFeedbackSmoke(){}
}
