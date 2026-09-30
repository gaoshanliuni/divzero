package dev.mineagent.runtime.neoforge.client.nativeui;

import com.google.gson.*;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextArea;
import dev.mineagent.runtime.neoforge.MineAgentRuntimeServices;
import dev.mineagent.runtime.neoforge.body.MineAgentPlayer;
import dev.mineagent.runtime.neoforge.chat.AiPlayerChat;
import dev.mineagent.runtime.neoforge.mixin.client.ChatHistoryAccess;
import dev.mineagent.runtime.neoforge.network.UiPayloads;
import dev.mineagent.runtime.neoforge.skill.SkillRuntime;
import dev.mineagent.runtime.neoforge.ui.ServerAgentProfile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.multiplayer.chat.GuiMessageSource;
import net.minecraft.network.chat.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** Opt-in isolated fixture: uses registered players, commands, packets and live editors, no model calls. */
@net.neoforged.fml.common.EventBusSubscriber(modid="mineagent_runtime",value=net.neoforged.api.distmarker.Dist.CLIENT)
public final class PlayerFeedbackSmoke {
    private record Step(String name,Supplier<CompletableFuture<Boolean>> run){}
    private static final Deque<Step> steps=new ArrayDeque<>();private static final List<Object> evidence=new ArrayList<>();
    private static CompletableFuture<Map<String,Object>> result;private static UUID agent,chat;private static MineAgentPlayer deceasedBody;private static boolean busy;private static int ticks,started,deathId;private static String skill;private static String clipboardBefore;private static String[] draftBefore;private static TextArea clipboardEditor;private static NativeImeSupport clipboardProbe;private static String testClipboard;private static boolean osClipboardVerified=true;
    private static Minecraft mc(){return Minecraft.getInstance();}
    private static void check(boolean v,String error){if(!v)throw new IllegalStateException(error);}
    private static <T> CompletableFuture<T> server(Function<ServerPlayer,T> work){var f=new CompletableFuture<T>();var s=mc().getSingleplayerServer();var id=mc().player.getUUID();s.submit(()->work.apply(s.getPlayerList().getPlayer(id))).whenComplete((v,e)->mc().execute(()->{if(e!=null)f.completeExceptionally(e);else f.complete(v);}));return f;}
    private static MineAgentPlayer body(ServerPlayer p){return MineAgentRuntimeServices.bodies(p.level().getServer()).body(agent).orElseThrow();}
    private static void step(String name,Supplier<CompletableFuture<Boolean>> run){steps.add(new Step(name,run));}
    private static void action(String name,Supplier<CompletableFuture<?>> run){step(name,()->run.get().thenApply(v->{if(v!=null)evidence.add(Map.of("step",name,"observed",v));return true;}));}
    private static CompletableFuture<Boolean> yes(){return CompletableFuture.completedFuture(true);}
    private static TextArea editor(UIElement root){if(root instanceof TextArea t)return t;for(var c:root.getChildren()){var v=editor(c);if(v!=null)return v;}return null;}
    private static String visibleText(UIElement root){if(!root.isDisplayed())return "";var out=new StringBuilder();if(root instanceof com.lowdragmc.lowdraglib2.gui.ui.elements.TextElement t)out.append(t.getText().getString());for(var child:root.getChildren())out.append(visibleText(child));return out.toString();}
    private static String readClipboard(){return clipboardProbe==null?mc().keyboardHandler.getClipboard():testClipboard;}
    private static void key(NativeInputScreen screen,int code){var event=new KeyEvent(code,0,org.lwjgl.glfw.GLFW.GLFW_MOD_CONTROL);check(clipboardProbe==null?screen.keyPressed(event):clipboardProbe.shortcut(event),"CLIPBOARD_KEY_NOT_HANDLED");}
    public static CompletableFuture<Map<String,Object>> run(UUID id){
        if(!Boolean.getBoolean("mineagent.skillSmoke")||result!=null)throw new IllegalStateException("SMOKE_DISABLED_OR_BUSY");agent=id;result=new CompletableFuture<>();chat=UUID.randomUUID();
        action("canonical-player-command-identity",()->server(p->{
            try{var b=body(p);var s=p.level().getServer();var manager=MineAgentRuntimeServices.bodies(s);String name="中文伙伴";
                check(manager.rename(agent,p.getUUID(),true,name),"NATIVE_RENAME_FAILED");check(b.getGameProfile().name().equals(name)&&b.getName().getString().equals(name)&&b.nameAndId().name().equals(name),"PROFILE_NAME_NOT_CANONICAL");
                check(s.getPlayerList().getPlayerByName(name)==b&&s.getPlayerList().getPlayer(agent)==b,"NATIVE_PLAYER_LOOKUP");
                var commands=s.getCommands().getDispatcher();var source=p.createCommandSourceStack();
                for(String command:List.of("give "+name+" minecraft:diamond 3","tp "+name+" 2.5 101 6.5","gamemode adventure "+name,"effect give "+name+" minecraft:speed 60 1","tag "+name+" add feedback_native","execute as "+name+" run tag @s add feedback_self","team add feedback","team join feedback "+name,"scoreboard objectives add feedback dummy","scoreboard players set "+name+" feedback 17"))check(commands.execute(command,source)>0,"COMMAND_FAILED_"+command);
                check(b.getInventory().countItem(net.minecraft.world.item.Items.DIAMOND)==3&&b.entityTags().containsAll(Set.of("feedback_native","feedback_self")),"COMMAND_RESULTS_MISSING");
                check(commands.execute("kubejs stages add "+name+" feedback_native",source)>0,"THIRD_PARTY_PLAYER_COMMAND_FAILED");
                Object stages=b.getClass().getMethod("kjs$getStages").invoke(b);check(Boolean.TRUE.equals(Class.forName("dev.latvian.mods.kubejs.stages.Stages").getMethod("has",String.class).invoke(stages,"feedback_native")),"KUBEJS_STAGE_NOT_APPLIED_TO_AI");
                var profile=ServerAgentProfile.read(p,Map.of("agentId",agent.toString()));check(profile.get("mode").equals("ADVENTURE")&&!((List<?>)profile.get("effects")).isEmpty(),"ACTUAL_STATUS_NOT_PROJECTED");
                check(manager.rename(agent,p.getUUID(),true,"伙伴二号"),"SECOND_RENAME_FAILED");check(b.getInventory().countItem(net.minecraft.world.item.Items.DIAMOND)==3&&b.getTeam()!=null,"RENAME_LOST_NATIVE_STATE");
                check(s.getScoreboard().getPlayerScoreInfo(b,s.getScoreboard().getObjective("feedback")).value()==17,"RENAME_LOST_SCORE");commands.execute("gamemode survival 伙伴二号",source);
                return Map.of("uuid",b.getUUID(),"name",b.getGameProfile().name(),"status",profile,"nativeCommands",10,"thirdPartyCommand","kubejs stages add");
            }catch(Exception e){throw new CompletionException(e);}
        }));
        step("renamed-profile-received-by-client",()->CompletableFuture.completedFuture(mc().getConnection().getPlayerInfo(agent)!=null&&mc().getConnection().getPlayerInfo(agent).getProfile().name().equals("伙伴二号")));
        action("native-player-chat-stream-packets",()->server(p->{
            String[] parts={"反馈原生聊天一","二"};int offset=0;for(int i=0;i<parts.length;i++){
                var text=Component.literal(parts[i]);if(i==1)text.withStyle(style->style.withClickEvent(new ClickEvent.CopyToClipboard("反馈按钮")));
                check(AiPlayerChat.stream(p,agent,chat,"body:"+offset,text),"NATIVE_CHAT_REJECTED");var payload=new JsonObject();payload.addProperty("agent",agent.toString());payload.addProperty("name","伙伴二号");payload.addProperty("bodyOffset",offset);payload.addProperty("body",parts[i]);payload.addProperty("thinkingOffset",0);payload.addProperty("thinking","");payload.addProperty("done",i==1);payload.addProperty("state",i==1?"COMPLETE":"RUNNING");payload.addProperty("color","#FFFFFF");payload.addProperty("nativeParts",i+1);payload.addProperty("nativeAllowed",true);net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(p,new UiPayloads.Event(chat,"nativeChatStream",payload.toString()));offset+=parts[i].length();
            }return true;
        }));
        step("one-real-player-stream-with-click-event",()->{
            var messages=((ChatHistoryAccess)mc().gui.getChat()).mineagent$messages().stream().filter(m->m.content().getString().contains("反馈原生聊天一二")).toList();if(messages.isEmpty())return CompletableFuture.completedFuture(false);
            check(messages.size()==1&&messages.getFirst().source()==GuiMessageSource.PLAYER,"NOT_SINGLE_NATIVE_PLAYER_MESSAGE");var seen=new boolean[1];messages.getFirst().content().visit((style,text)->{if(style.getClickEvent() instanceof ClickEvent.CopyToClipboard)seen[0]=true;return Optional.empty();},Style.EMPTY);check(seen[0],"PLAYER_CHAT_LOST_CLICK_ACTION");return yes();
        });
        step("enable-local-workspace",()->{if(dev.mineagent.runtime.neoforge.client.MineAgentClientTrustPrompt.enabled())return yes();dev.mineagent.runtime.neoforge.client.MineAgentClientTrustPrompt.smokeEnable();return CompletableFuture.completedFuture(false);});
        action("open-live-f2-editor",()->{NativeWorkspaceScreen.open();org.lwjgl.glfw.GLFW.glfwFocusWindow(mc().getWindow().handle());return yes();});
        step("live-f2-ready",()->CompletableFuture.completedFuture(mc().isWindowActive()&&NativeWorkspaceConnection.ready()&&mc().screen instanceof NativeWorkspaceScreen));
        action("prepare-os-clipboard-unicode",()->{var screen=(NativeWorkspaceScreen)mc().screen;clipboardEditor=editor(screen.smokeRoot());check(clipboardEditor!=null,"F2_EDITOR_MISSING");clipboardBefore=mc().keyboardHandler.getClipboard();draftBefore=clipboardEditor.getValue().clone();screen.modularUI.requestFocus(clipboardEditor);mc().keyboardHandler.setClipboard("剪贴板中文\r\n第二行");return yes();});
        step("external-clipboard-ready",()->{if(mc().keyboardHandler.getClipboard().equals("剪贴板中文\r\n第二行"))return yes();if(ticks-started<20)return CompletableFuture.completedFuture(false);osClipboardVerified=false;testClipboard="剪贴板中文\r\n第二行";var screen=(NativeWorkspaceScreen)mc().screen;clipboardProbe=new NativeImeSupport(screen,()->screen.modularUI,()->testClipboard,value->testClipboard=value);evidence.add(Map.of("systemClipboard","UNVERIFIED_OS_WRITE_NOT_OBSERVED","widgetClipboardTest","INJECTED_PORT_ON_REAL_LDLIB_EDITOR"));return yes();});
        action("paste-os-clipboard",()->{var screen=(NativeWorkspaceScreen)mc().screen;key(screen,65);key(screen,86);check(String.join("\n",clipboardEditor.getValue()).equals("剪贴板中文\n第二行"),"OS_PASTE_FAILED");key(screen,65);key(screen,67);return yes();});
        step("native-copy-reaches-os-clipboard",()->CompletableFuture.completedFuture(readClipboard().equals("剪贴板中文\n第二行")));
        action("cut-selection",()->{key((NativeWorkspaceScreen)mc().screen,88);check(String.join("\n",clipboardEditor.getValue()).isEmpty(),"CUT_FAILED");return yes();});
        action("paste-cut-text-and-check-ime",()->{var screen=(NativeWorkspaceScreen)mc().screen;key(screen,86);check(String.join("\n",clipboardEditor.getValue()).equals("剪贴板中文\n第二行"),"CUT_PASTE_FAILED");if(clipboardProbe!=null){clipboardProbe.release();clipboardProbe=null;}var ime=screen.smokeIme();clipboardEditor.setValue(draftBefore,false);if(osClipboardVerified)mc().keyboardHandler.setClipboard(clipboardBefore);clipboardBefore=null;return CompletableFuture.completedFuture(ime);});
        action("text-field-shortcuts-and-clipboard-denial-preserve-text",()->{
            var screen=(NativeWorkspaceScreen)mc().screen;var window=screen.window("clipboard-fixture","输入测试",240,90);var field=new com.lowdragmc.lowdraglib2.gui.ui.elements.TextField();field.getLayout().height(25).widthPercent(100);window.body.addChild(field);field.setText("保留原文",false);screen.modularUI.requestFocus(field);String[] port={"粘贴新文本"};var input=new NativeImeSupport(screen,()->screen.modularUI,()->port[0],v->port[0]=v);
            var all=new KeyEvent(65,0,org.lwjgl.glfw.GLFW.GLFW_MOD_CONTROL);input.shortcut(all);input.shortcut(new KeyEvent(86,0,org.lwjgl.glfw.GLFW.GLFW_MOD_CONTROL));check(field.getValue().equals("粘贴新文本"),"FIELD_PASTE_FAILED");input.shortcut(all);input.shortcut(new KeyEvent(67,0,org.lwjgl.glfw.GLFW.GLFW_MOD_CONTROL));check(port[0].equals(field.getValue()),"FIELD_COPY_FAILED");input.release();
            var denied=new NativeImeSupport(screen,()->screen.modularUI,()->"",v->{});denied.shortcut(all);denied.shortcut(new KeyEvent(86,0,org.lwjgl.glfw.GLFW.GLFW_MOD_CONTROL));check(field.getValue().equals("粘贴新文本"),"EMPTY_PASTE_DELETED_SELECTION");denied.shortcut(new KeyEvent(88,0,org.lwjgl.glfw.GLFW.GLFW_MOD_CONTROL));check(field.getValue().equals("粘贴新文本"),"FAILED_CUT_DELETED_SELECTION");denied.release();return yes();
        });
        action("persona-library-live-rename",()->{String profile=UUID.randomUUID().toString();return WorkspacePanels.request("persona.libraryWrite",Map.of("kind","save","agentId",agent.toString(),"profileId",profile,"name","旧人设名","text","不可丢失的人设正文")).thenCompose(v->WorkspacePanels.request("persona.libraryWrite",Map.of("kind","rename","agentId",agent.toString(),"profileId",profile,"expectedRevision","1","name","新的人设名"))).thenCompose(v->WorkspacePanels.request("persona.libraryRead",Map.of("kind","get","agentId",agent.toString(),"profileId",profile))).thenApply(v->{var saved=WorkspacePanels.state(v);check(saved.get("name").getAsString().equals("新的人设名")&&saved.get("text").getAsString().equals("不可丢失的人设正文")&&saved.get("revision").getAsInt()==2,"LIVE_PERSONA_RENAME_FAILED");return Map.of("name",saved.get("name").getAsString(),"revision",saved.get("revision").getAsInt());});});
        action("open-actual-status-panel",()->{AgentProfileScreen.open(agent,"伙伴二号");return yes();});
        step("actual-mode-and-buff-widgets",()->{if(!(mc().screen instanceof AgentProfileScreen panel))return CompletableFuture.completedFuture(false);String text=visibleText(panel.smokeRoot());if(!text.contains("生存模式")||!text.contains("迅捷"))return CompletableFuture.completedFuture(false);check(text.contains("自动重生：开启"),"RESPAWN_CONTROL_MISSING");return yes();});
        action("open-auto-save-mode-editor",()->{NativeWorkspaceScreen.open();return yes();});
        step("workspace-ready-again",()->CompletableFuture.completedFuture(NativeWorkspaceConnection.ready()&&mc().screen instanceof NativeWorkspaceScreen));
        action("attach-auto-save-editor",()->{NativeBehaviorPanel.open((NativeWorkspaceScreen)mc().screen,agent.toString(),"伙伴二号");return yes();});
        step("auto-save-editor-ready",()->CompletableFuture.completedFuture(NativeBehaviorPanel.smokeReady(agent.toString())));
        action("rapid-mode-and-rule-edits-without-apply",()->{NativeBehaviorPanel.smokeSelect(agent.toString(),"mode","FOLLOW");NativeBehaviorPanel.smokeSelect(agent.toString(),"strategy","HIT_AND_RUN");NativeBehaviorPanel.smokeSelect(agent.toString(),"engagement","PROTECT");return yes();});
        step("last-edit-saved-on-running-follow",()->server(p->{var report=SkillRuntime.get(p.level().getServer()).snapshot(p,agent);var json=new Gson().toJsonTree(report).getAsJsonObject();for(var row:json.getAsJsonArray("skills")){var s=row.getAsJsonObject().getAsJsonObject("session");var spec=s.getAsJsonObject("spec");if(!Set.of("CANCELLED","FAILED","COMPLETED").contains(s.get("state").getAsString())&&spec.get("kind").getAsString().equals("FOLLOW")&&spec.getAsJsonObject("combat").get("engagement").getAsString().equals("PROTECT")&&spec.getAsJsonObject("combat").get("strategy").getAsString().equals("HIT_AND_RUN")){skill=spec.get("id").getAsString();evidence.add(report);return true;}}return false;}));
        action("native-death-follow-respawn",()->server(p->{var b=body(p);deathId=b.getId();deceasedBody=b;check(MineAgentRuntimeServices.bodies(p.level().getServer()).autoRespawn(agent),"RESPAWN_NOT_DEFAULT_ON");b.hurtServer(p.level(),b.damageSources().genericKill(),Float.MAX_VALUE);return true;}));
        step("new-body-resumes-same-follow-and-policy",()->server(p->{var b=body(p);if(b==deceasedBody||!b.isAlive())return false;var report=SkillRuntime.get(p.level().getServer()).snapshot(p,agent);var json=new Gson().toJsonTree(report).getAsJsonObject();for(var row:json.getAsJsonArray("skills")){var s=row.getAsJsonObject().getAsJsonObject("session");if(s.getAsJsonObject("spec").get("id").getAsString().equals(skill)&&Set.of("RUNNING","WAITING").contains(s.get("state").getAsString())){check(s.getAsJsonObject("spec").getAsJsonObject("combat").get("engagement").getAsString().equals("PROTECT"),"RESPAWN_LOST_RULES");check(b.distanceTo(p)<8&&s.getAsJsonObject("counters").get("followRespawnTeleports").getAsInt()==1,"FOLLOW_RESPAWN_NOT_NEAR_OWNER");evidence.add(report);return true;}}return false;}));
        action("disable-auto-respawn-then-die",()->server(p->{var m=MineAgentRuntimeServices.bodies(p.level().getServer());m.setAutoRespawn(agent,m.respawnRevision(agent),false);var b=body(p);deathId=b.getId();deceasedBody=b;b.hurtServer(p.level(),b.damageSources().genericKill(),Float.MAX_VALUE);return true;}));
        step("disabled-remains-dead",()->server(p->{if(ticks-started<50)return false;check(body(p)==deceasedBody&&!body(p).isAlive(),"DISABLED_AUTO_RESPAWN_IGNORED");return true;}));
        action("manual-respawn",()->server(p->MineAgentRuntimeServices.bodies(p.level().getServer()).respawnNow(agent)));
        step("manual-respawn-completed",()->server(p->body(p)!=deceasedBody&&body(p).isAlive()));
        action("native-disconnect-cleans-player-list",()->server(p->{try{SkillRuntime.get(p.level().getServer()).stopAll(p,agent);var s=p.level().getServer();body(p).connection.disconnect(Component.translatable("multiplayer.disconnect.kicked"));check(s.getPlayerList().getPlayer(agent)==null,"DISCONNECT_LEFT_AI_IN_PLAYER_LIST");return true;}catch(Exception e){throw new CompletionException(e);}}));
        action("close-ui",()->{mc().screen.onClose();return yes();});return result;
    }
    @net.neoforged.bus.api.SubscribeEvent public static void tick(net.neoforged.neoforge.client.event.ClientTickEvent.Post event){
        if(result==null||result.isDone())return;ticks++;if(steps.isEmpty()){result.complete(Map.of("status",osClipboardVerified?"PASS":"PASS_WITH_SYSTEM_CLIPBOARD_UNVERIFIED","systemClipboardVerified",osClipboardVerified,"evidence",evidence,"modelCalls",0));return;}
        if(ticks-started>700){if(clipboardBefore!=null){mc().keyboardHandler.setClipboard(clipboardBefore);clipboardBefore=null;}result.completeExceptionally(new IllegalStateException("PLAYER_FEEDBACK_TIMEOUT_"+steps.getFirst().name));return;}if(busy||ticks%4!=0)return;busy=true;var step=steps.getFirst();try{step.run.get().whenComplete((v,e)->mc().execute(()->{busy=false;if(e!=null){if(clipboardBefore!=null){mc().keyboardHandler.setClipboard(clipboardBefore);clipboardBefore=null;}result.completeExceptionally(new IllegalStateException(step.name+": "+e,e));return;}if(v){evidence.add(Map.of("completed",step.name,"tick",ticks));steps.removeFirst();started=ticks;}}));}catch(Exception e){if(clipboardBefore!=null){mc().keyboardHandler.setClipboard(clipboardBefore);clipboardBefore=null;}result.completeExceptionally(new IllegalStateException(step.name+": "+e,e));}
    }
    private PlayerFeedbackSmoke(){}
}
