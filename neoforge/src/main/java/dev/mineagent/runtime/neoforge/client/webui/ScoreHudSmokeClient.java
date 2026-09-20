package dev.mineagent.runtime.neoforge.client.webui;

import com.google.gson.*;
import dev.mineagent.runtime.neoforge.ui.ScoreHudSmokeServer;
import dev.mineagent.runtime.api.ui.UiProtocol.*;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import java.nio.file.*;
import java.util.*;

/** Actual CEF/GUI acceptance driver, not an AI page operator. Real Coder creates both entrypoints. */
@EventBusSubscriber(modid="mineagent_runtime",value=Dist.CLIENT)
public final class ScoreHudSmokeClient {
    private static final Gson JSON=new Gson();
    private static boolean opened,backup,busy,ended;private static int ticks,phase,afterTick;private static String hud,editor,failure;
    private static JsonObject probe;private static net.minecraft.world.phys.Vec3 movementStart;
    private static Session initialLease;
    private static Session hiddenLease;
    private static Session stalledLease;
    public static void accept(JsonObject value){probe=value;}
    public static boolean pauseRead(String view){return phase==41&&Objects.equals(view,hud);}
    @SubscribeEvent public static void tick(ClientTickEvent.Post event)throws Exception {
        if(!Boolean.getBoolean("mineagent.scoreHudSmoke")||ended)return;
        if(Set.of("restore","closed","rejected").contains(System.getProperty("mineagent.hudPersistencePhase",""))){HudRestoreSmokeClient.tick();return;}
        var mc=Minecraft.getInstance();var host=WebGuiHostAdapter.INSTANCE;ticks++;
        if(!backup&&mc.screen instanceof net.minecraft.client.gui.screens.BackupConfirmScreen s){backup=true;var f=s.getClass().getDeclaredField("onProceed");f.setAccessible(true);((net.minecraft.client.gui.screens.BackupConfirmScreen.Listener)f.get(s)).proceed(false,false);}
        if(mc.player!=null&&!opened){opened=true;host.open();}
        if(host.ready()&&UiClientSessions.current()!=null&&ticks%10==0){
            if(phase==0&&ScoreHudSmokeServer.agentId!=null)main("""
                if(!document.querySelector('#generation-prompt'))document.querySelector('#open-generation').click();
                const agent=document.querySelector('#generation-agent');
                if(!RESUME&&!window.__hudSubmitted&&[...agent.options].some(o=>o.value===AGENT)){
                  window.__hudSubmitted=true;agent.value=AGENT;agent.dispatchEvent(new Event('change',{bubbles:true}));
                  const p=document.querySelector('#generation-prompt');p.value=REQUEST;p.dispatchEvent(new Event('input',{bubbles:true}));document.querySelector('#generation-submit').click();
                }
                const card=[...document.querySelectorAll('.generation-job:not(.patch-job)')].find(c=>c.dataset.agentId===AGENT&&c.querySelector('strong').textContent.startsWith('PUBLISHED'));if(!card)return;
                const source=card.querySelector('select[aria-label="已有计分目标"]');
                if(!window.__hudBound&&source&&[...source.options].some(o=>o.value===SOURCE)){
                  window.__hudBound=true;source.value=SOURCE;source.dispatchEvent(new Event('change',{bubbles:true}));[...card.querySelectorAll('button')].find(b=>b.textContent==='创建独立绑定视图').click();
                }
                const edit=card.querySelector('button[data-source-id="'+SOURCE+'"]');
                if(edit&&!window.__hudEditorOpened){window.__hudEditorOpened=true;edit.click();}
                if(OPEN_HUD&&!window.__hudOpened&&TARGET){const button=card.querySelector('button[data-hud-view-id="'+TARGET+'"]');if(button){window.__hudOpened=true;button.click();}}
                """.replace("RESUME",Boolean.toString(ScoreHudSmokeServer.resume())).replace("AGENT",q(ScoreHudSmokeServer.agentId)).replace("SOURCE",q(ScoreHudSmokeServer.sourceId))
                    .replace("REQUEST",q(request())).replace("OPEN_HUD",Boolean.toString(editor!=null&&PackageContentClient.session(editor)!=null)).replace("TARGET",q(ScoreHudSmokeServer.targetId)));
            main("const h=document.querySelector('[data-mode=PASSIVE_HUD]'),e=document.querySelector('[data-mode=CONTENT]');window.mineagentQuery({request:JSON.stringify({channel:'scoreHudProbe',hud:h?.dataset.viewId??'',editor:e?.dataset.viewId??'',interacting:document.body.dataset.interacting,editorVisible:e?getComputedStyle(e).display!=='none':false,hudVisible:h?getComputedStyle(h).display!=='none':false,hudInert:!!h?.querySelector('.content')?.inert,activeFrame:document.activeElement?.tagName==='IFRAME',activeHud:document.activeElement===h?.querySelector('iframe'),dockVisible:getComputedStyle(document.querySelector('#dock')).visibility!=='hidden',status:document.querySelector('#status').textContent,layout:{dock:document.querySelector('#dock').getBoundingClientRect().toJSON(),tray:document.querySelector('#minimized').getBoundingClientRect().toJSON(),windows:[...document.querySelectorAll('.window')].map(n=>({id:n.dataset.viewId,mode:n.dataset.mode,display:getComputedStyle(n).display,z:getComputedStyle(n).zIndex,rect:n.getBoundingClientRect().toJSON(),frame:n.querySelector('iframe')?.getBoundingClientRect().toJSON()}))}}),persistent:false,onSuccess(){},onFailure(){}});");
        }
        if(probe!=null){if(!probe.get("editor").getAsString().isBlank())editor=probe.get("editor").getAsString();if(!probe.get("hud").getAsString().isBlank())hud=probe.get("hud").getAsString();}
        if(phase==0&&hud!=null&&PackageContentClient.session(hud)!=null&&!busy){
            var session=PackageContentClient.session(hud);var b=session.binding();
            if(b.preview()||b.actorKind()!=ActorKind.PLAYER||!b.capabilities().equals(Set.of("scoreview.read"))||!host.viewPackage(hud).passive()||!probe.get("hudInert").getAsBoolean())throw new IllegalStateException("HUD_NOT_PASSIVE_READ_ONLY");
            initialLease=session;
            Files.createDirectories(root());Files.writeString(root().resolve("hud-session.json"),JSON.toJson(session));phase=1;afterTick=ticks+20;
            frame("const f=document.createElement('input');f.id='hud-focus-probe';f.autofocus=true;f.value='FOCUS_PROBE';document.body.append(f);f.focus();(async()=>{let p=document.createElement('p');p.id='hud-write-probe';document.body.append(p);try{const s=await mineagentUi.read();await mineagentUi.patch(s.viewRevision,{title:'FORBIDDEN_HUD_WRITE'});p.textContent='HUD_UNEXPECTED_WRITE';}catch(e){p.textContent='HUD_WRITE_'+e.message;}})();");
        }
        if(phase==1&&ticks>=afterTick&&!busy)inspect("before-dom.json",text->{
            if(!text.contains("Alice")||!text.contains("Bob")||!text.contains("7")||!text.contains("HUD_WRITE_PERMISSION_DENIED"))return false;
            if(probe.get("activeHud").getAsBoolean())throw new IllegalStateException("PASSIVE_HUD_AUTOFOCUS_STOLE_INPUT");
            try{Files.writeString(root().resolve("autofocus.json"),JSON.toJson(probe));}catch(Exception e){throw new IllegalStateException(e);}
            frame("document.querySelector('#hud-focus-probe')?.remove();document.querySelector('#hud-write-probe')?.remove();");
            phase=2;busy=true;
            main("const g=document.querySelector('[data-view-id=runtime-generation]');g?.querySelector('[aria-label=收起]').click();const e=document.querySelector('[data-view-id=\""+editor+"\"]');e?.querySelector('[aria-label=关闭]').click();document.querySelector('#open-chat').click();document.querySelector('[data-view-id=runtime-chat] [aria-label=关闭]').click();");
            afterTick=ticks+30;return true;
        });
        if(phase==2&&ticks>=afterTick){phase=3;busy=true;PackagePageAgent.captureManagedView(hud).whenComplete((shot,error)->mc.execute(()->{try{
            if(error!=null)throw new IllegalStateException(error);Files.write(root().resolve("hud-private.png"),shot.png());
            if(!(mc.screen instanceof WebGuiInteractionScreen screen))throw new IllegalStateException("HUD_INTERACTION_SCREEN_MISSING");
            screen.onClose();ScoreHudSmokeServer.externalRequested=true;movementStart=mc.player.position();mc.options.keyUp.setDown(true);phase=4;afterTick=ticks+30;busy=false;
        }catch(Exception e){failure=e.toString();}}));}
        if(phase==4&&ticks>=afterTick&&!busy&&ScoreHudSmokeServer.externalChanged){
            mc.options.keyUp.setDown(false);
            if(Boolean.getBoolean("mineagent.scoreHudRenewSmoke")&&System.currentTimeMillis()<initialLease.expiresAtMillis()+9000)return;
            if(Boolean.getBoolean("mineagent.scoreHudRenewSmoke")){
                var current=PackageContentClient.session(hud);
                if(current==null||!dev.mineagent.runtime.api.ui.ReadOnlyUiLease.sameContext(initialLease,current)||current.expiresAtMillis()<=System.currentTimeMillis())throw new IllegalStateException("HUD_LEASE_NOT_RENEWED");
                Files.writeString(root().resolve("lease-renewal.json"),JSON.toJson(Map.of("initial",initialLease,"current",current,"observedAt",System.currentTimeMillis(),"hiddenEditorUnaffected",true)));
            }
            if(mc.screen!=null||probe==null||probe.get("dockVisible").getAsBoolean()||!probe.get("hudVisible").getAsBoolean()||probe.get("activeFrame").getAsBoolean())throw new IllegalStateException("HUD_GAMEPLAY_INPUT_OR_VISIBILITY");
            Files.writeString(root().resolve("gameplay-input.json"),JSON.toJson(Map.of("screen","none","forwardReleased",!mc.options.keyUp.isDown(),"distanceSquared",mc.player.position().distanceToSqr(movementStart),"inputDriver","GAME_KEYMAPPING_NOT_OS","shell",probe)));
            inspect("external-after-chat-close-dom.json",text->{if(!text.contains("Alice")||!text.contains("19")||!text.contains("Bob"))return false;
                if(Boolean.getBoolean("mineagent.scoreHudRenewSmoke")){
                    stalledLease=PackageContentClient.session(hud);
                    phase=41;afterTick=ticks+180;
                }else{phase=5;afterTick=ticks+10;}return true;});
        }
        if(phase==41&&ticks>=afterTick&&!busy){
            Files.writeString(root().resolve("paused-lease.json"),JSON.toJson(Map.of("old",stalledLease,"current",PackageContentClient.rawSession(hud),"observedAt",System.currentTimeMillis())));
            inspect("paused-bridge-dom.json",text->{
                if(!text.contains("FIXTURE_READ_PAUSED")||PackageContentClient.rawSession(hud).expiresAtMillis()>System.currentTimeMillis())throw new IllegalStateException("READ_PAUSE_NOT_OBSERVED");
                phase=42;afterTick=ticks+80;return true;});
        }
        if(phase==42&&ticks>=afterTick&&!busy&&PackageContentClient.session(hud)!=null){
            var fresh=PackageContentClient.session(hud);
            if(fresh.sessionId().equals(stalledLease.sessionId())){
                Files.writeString(root().resolve("visible-expiry-failure.json"),JSON.toJson(Map.of("old",stalledLease,"current",fresh,"observedAt",System.currentTimeMillis(),"probe",probe)));
                failure="VISIBLE_EXPIRED_HUD_NOT_READMITTED";phase=99;return;
            }
            Files.writeString(root().resolve("visible-expiry-readmission.json"),JSON.toJson(Map.of("old",stalledLease,"fresh",fresh,"observedAt",System.currentTimeMillis(),"driver","SCOPED_NATIVE_READ_PAUSE_NOT_NETWORK_DISCONNECT")));
            inspect("visible-recovered-dom.json",text->{if(!text.contains("19")||text.contains("FIXTURE_READ_PAUSED"))return false;phase=5;afterTick=ticks+10;return true;});
        }
        if(phase==42&&ticks>afterTick+200&&PackageContentClient.session(hud)==null)failure="VISIBLE_HUD_READMISSION_TIMEOUT";
        if(phase==5&&ticks>=afterTick&&!busy){busy=true;net.minecraft.client.Screenshot.takeScreenshot(mc.getMainRenderTarget(),image->{try(image){image.writeToFile(root().resolve("gameplay-hud.png"));phase=6;afterTick=ticks+20;busy=false;}catch(Exception e){failure=e.toString();}});}
        if(phase==6&&ticks>=afterTick&&!busy){
            hiddenLease=PackageContentClient.session(hud);
            host.open();main("document.querySelector('[data-view-id=\""+hud+"\"] [aria-label=收起]').click();");phase=7;afterTick=ticks+(Boolean.getBoolean("mineagent.scoreHudRenewSmoke")?240:30);
        }
        if(phase==7&&ticks>=afterTick&&!busy){
            if(PackageContentClient.session(hud)!=null||!host.packageHidden(hud))throw new IllegalStateException("HUD_MINIMIZE_STILL_ADMITTED");
            Files.writeString(root().resolve("hidden.json"),JSON.toJson(probe));
            if(Boolean.getBoolean("mineagent.scoreHudRenewSmoke")){
                // Normal shell renewal also prunes expired child sessions. Reproduce it after the accelerated HUD TTL.
                UiClientSessions.reset(false);UiClientSessions.open();phase=71;afterTick=ticks+20;
            }else{showHiddenHud();phase=8;afterTick=ticks+30;}
        }
        if(phase==71&&ticks>=afterTick&&UiClientSessions.current()!=null){Files.writeString(root().resolve("renewed-shell.json"),JSON.toJson(UiClientSessions.current()));showHiddenHud();phase=8;afterTick=ticks+30;}
        if(phase==8&&ticks>afterTick+200&&PackageContentClient.session(hud)==null)failure="HIDDEN_HUD_READMISSION_TIMEOUT";
        if(phase==8&&ticks>=afterTick&&!busy&&PackageContentClient.session(hud)!=null)inspect("reopened-dom.json",text->{if(!text.contains("19"))return false;
            if(Boolean.getBoolean("mineagent.scoreHudRenewSmoke")){
                var fresh=PackageContentClient.session(hud);
                if(fresh.sessionId().equals(hiddenLease.sessionId())||!fresh.binding().equals(hiddenLease.binding())||fresh.expiresAtMillis()<=System.currentTimeMillis())throw new IllegalStateException("EXPIRED_HUD_NOT_READMITTED");
                try{Files.writeString(root().resolve("readmitted-lease.json"),JSON.toJson(Map.of("old",hiddenLease,"fresh",fresh,"observedAt",System.currentTimeMillis())));}catch(Exception e){throw new IllegalStateException(e);}
            }
            if("save".equals(System.getProperty("mineagent.hudPersistencePhase",""))){
                main("const b=document.querySelector('[data-view-id=\""+hud+"\"] [data-action=remember-hud]');if(b&&!b.disabled&&b.getAttribute('aria-pressed')!=='true')b.click();");phase=81;afterTick=ticks+40;return true;
            }
            main("document.querySelector('[data-view-id=\""+hud+"\"] [aria-label=关闭]').click();");ScoreHudSmokeServer.closeRequested=true;phase=9;return true;
        });
        if(phase==81&&ticks>=afterTick&&HudPersistenceClient.settled()&&HudPersistenceClient.entries().stream().anyMatch(e->e.targetId().toString().equals(ScoreHudSmokeServer.targetId))){
            var entry=HudPersistenceClient.entries().stream().filter(e->e.targetId().toString().equals(ScoreHudSmokeServer.targetId)).findFirst().orElseThrow();
            var checkpoint=Map.of("entry",entry,"session",PackageContentClient.session(hud),"sourceId",ScoreHudSmokeServer.sourceId,"saveRun",ScoreHudSmokeServer.RUN,"providerCalls",0);
            Files.writeString(mc.gameDirectory.toPath().resolve("hud-restore-checkpoint.json"),JSON.toJson(checkpoint));Files.writeString(root().resolve("restore-checkpoint.json"),JSON.toJson(checkpoint));
            ended=true;host.close();mc.stop();dev.mineagent.runtime.neoforge.MineAgentRuntimeMod.LOGGER.info("MINEAGENT_HUD_PERSIST_SAVED run={}",ScoreHudSmokeServer.RUN);
        }
        if(phase==9&&ScoreHudSmokeServer.closeVerified){
            Files.writeString(root().resolve("client-result.json"),JSON.toJson(Map.of("status","HUD_SLICE_VERIFIED","driver","REAL_CODER_THEN_TRUSTED_GUI_FIXTURE","hudReadOnly",true,"editorAndChatClosed",true,"externalRefreshWithoutModel",true,"hiddenAndReopened",true,"theme","glass-sage","tint","e0ffffff","fullV1",false)));
            ended=true;host.close();dev.mineagent.runtime.neoforge.MineAgentRuntimeMod.LOGGER.info("MINEAGENT_SCORE_HUD_CLIENT_OK run={}",ScoreHudSmokeServer.RUN);mc.stop();
        }
        if(failure!=null||ticks>12000){Files.createDirectories(root());Files.writeString(root().resolve("failure.json"),JSON.toJson(Map.of("phase",phase,"error",failure==null?"TIMEOUT":failure,"probe",probe==null?new JsonObject():probe)));ended=true;mc.options.keyUp.setDown(false);host.close();mc.stop();throw new IllegalStateException("SCORE_HUD_SMOKE_FAILED: "+failure);}
    }
    private static void inspect(String file,java.util.function.Predicate<String> next){busy=true;PackagePageAgent.inspectManagedView(hud).whenComplete((value,error)->Minecraft.getInstance().execute(()->{try{
        if(error!=null)throw new IllegalStateException(error);Files.writeString(root().resolve(file),value);busy=false;next.test(JsonParser.parseString(value).getAsJsonObject().get("visibleText").getAsString());
    }catch(Exception e){failure=e.toString();}}));}
    private static Path root(){return Minecraft.getInstance().gameDirectory.toPath().resolve(ScoreHudSmokeServer.directory());}
    private static String q(String s){return JSON.toJson(s);}
    private static void showHiddenHud(){main("[...document.querySelectorAll('#minimized button')].find(b=>b.textContent===document.querySelector('[data-view-id=\""+hud+"\"]').dataset.title)?.click();");}
    private static void main(String script){var h=WebGuiHostAdapter.INSTANCE;h.browser().executeJavaScript("(()=>{"+script+"})();",h.browser().getURL(),0);}
    private static void frame(String script){var h=WebGuiHostAdapter.INSTANCE;String url=h.packageUrl(hud);for(long id:h.browser().getFrameIdentifiers()){var f=h.browser().getFrame(id);if(f!=null&&!f.isMain()&&url.equals(f.getURL())){f.executeJavaScript(script,url,0);return;}}throw new IllegalStateException("HUD_FRAME_MISSING");}
    private static String request(){return "生成一个全新可编辑计分网页及独立被动 HUD，主题为松林观测排名。必须有两个不同 HTML 入口 ui 和 hud。编辑页可改 ScoreView 标题并显示分数；HUD 紧凑显示来源、标题、Alice/Bob 等服务端 rows、总行数、连接状态，每秒无重叠 read 刷新，不含保存按钮、不调用 patch。使用正式 mineagentUi SDK，不模拟分数，不使用外部网络。两页独立生命周期；关闭编辑页不影响 HUD。glass-sage 配色、清楚中文、半透明面板，不使用 Canvas 或图像。";}
}
