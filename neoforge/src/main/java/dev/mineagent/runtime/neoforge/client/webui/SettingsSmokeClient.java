package dev.mineagent.runtime.neoforge.client.webui;
import com.google.gson.*;
import dev.mineagent.runtime.api.ui.UiProtocol;
import dev.mineagent.runtime.neoforge.client.screen.NativeSecretScreen;
import dev.mineagent.runtime.neoforge.ui.SettingsSmokeServer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.input.*;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import java.nio.file.*;
import java.util.*;

/** Actual DOM settings actions and Native-only secret character callbacks; no OS input or real credential. */
@EventBusSubscriber(modid="mineagent_runtime",value=Dist.CLIENT)
public final class SettingsSmokeClient {
    private static final Gson JSON=new Gson();private static JsonObject probe;private static int phase,ticks,typedAt;private static boolean opened,busy,done,staleChecked;
    public static void accept(JsonObject p){if(SettingsSmokeServer.enabled())probe=p;}
    private static Path root(){return Minecraft.getInstance().gameDirectory.toPath().resolve("settings-evidence").resolve(SettingsSmokeServer.stage()).resolve("client");}
    private static void require(boolean ok,String code){if(!ok)throw new IllegalStateException(code);}
    private static void script(String s){var h=WebGuiHostAdapter.INSTANCE;h.browser().executeJavaScript("(()=>{"+s+"})();",h.browser().getURL(),0);}
    private static void action(String key,String code){script("window.__settingsActions??={};if(window.__settingsActions["+JSON.toJson(key)+"])return;const result=(()=>{"+code+"})();if(result)window.__settingsActions["+JSON.toJson(key)+"]=true;");}
    private static void capture(String name,Runnable then){busy=true;var mc=Minecraft.getInstance();net.minecraft.client.Screenshot.takeScreenshot(mc.getMainRenderTarget(),image->{try(image){image.writeToFile(root().resolve(name+".png"));mc.execute(()->{busy=false;then.run();});}catch(Exception e){mc.execute(()->{busy=false;throw new IllegalStateException(e);});}});}
    private static Button button(NativeSecretScreen s,String text){return s.children().stream().filter(e->e instanceof Button b&&b.getMessage().getString().contains(text)).map(e->(Button)e).findFirst().orElse(null);}
    private static boolean typed(NativeSecretScreen s){var clear=button(s,"清除");if(clear==null||!clear.active)return false;var input=s.children().stream().filter(e->e instanceof EditBox).map(e->(EditBox)e).findFirst().orElseThrow();input.setValue("");s.setFocused(input);input.setFocused(true);for(int cp:SettingsSmokeServer.TEST_KEY.codePoints().toArray())require(s.charTyped(new CharacterEvent(cp)),"NATIVE_CHARACTER_REJECTED");require(input.getValue().equals(SettingsSmokeServer.TEST_KEY),"NATIVE_SECRET_INPUT_MISMATCH");return true;}
    private static void press(Button b){require(b!=null&&b.active,"NATIVE_BUTTON_NOT_READY");b.onPress(new KeyEvent(257,0,0));}
    private static boolean nativeStatus(NativeSecretScreen s,String text){return s.children().stream().filter(e->e instanceof StringWidget).map(e->((StringWidget)e).getMessage().getString()).anyMatch(v->v.contains(text));}
    private static void checkBrowser()throws Exception{if(probe==null)return;String text=JSON.toJson(probe);require(!text.contains(SettingsSmokeServer.TEST_KEY),"SECRET_REACHED_BROWSER");Files.writeString(root().resolve("browser-check.json"),text);}
    @SubscribeEvent public static void tick(ClientTickEvent.Post event)throws Exception{
        if(!SettingsSmokeServer.enabled()||done)return;var mc=Minecraft.getInstance();var host=WebGuiHostAdapter.INSTANCE;ticks++;Files.createDirectories(root());
        if(!SettingsSmokeServer.failure.isEmpty()||ticks>3000){done=true;Files.writeString(root().resolve("failure.json"),JSON.toJson(Map.of("phase",phase,"error",SettingsSmokeServer.failure,"probe",probe==null?new JsonObject():probe)));host.close();mc.stop();throw new IllegalStateException("SETTINGS_NATIVE_FAILED");}
        if(SettingsSmokeServer.done&&!busy){checkBrowser();done=true;Files.writeString(root().resolve("result.json"),JSON.toJson(Map.of("phase",phase,"systemInputInjected",false,"paidCalls",0,"fullV1",false)));host.close();mc.stop();dev.mineagent.runtime.neoforge.MineAgentRuntimeMod.LOGGER.info("MINEAGENT_SETTINGS_{}_OK",SettingsSmokeServer.stage().toUpperCase(Locale.ROOT));return;}
        if(mc.player!=null&&!opened){opened=true;host.open();}
        if(busy||!host.ready()||UiClientSessions.current()==null||ticks%10!=0)return;
        script("window.mineagentQuery({request:JSON.stringify({channel:'settingsProbe',text:document.body.innerText,storage:JSON.stringify(Object.entries(localStorage)),model:document.querySelector('[data-setting=\"provider.openai.model\"]')?.value??'',errors:[...document.querySelectorAll('.settings-field .error')].map(e=>e.textContent).join(' '),privateHidden:document.querySelector('#settings-private-provider')?.hidden??null,permissionsHidden:document.querySelector('#settings-permissions')?.hidden??null}),persistent:false,onSuccess(){},onFailure(){}});");checkBrowser();
        if(SettingsSmokeServer.stage().equals("resume")){
            if(phase==0&&SettingsSmokeServer.step>=11){action("open","document.querySelector('#open-server-settings').click();return true;");if(probe!=null&&probe.get("privateHidden").isJsonPrimitive()&&probe.get("privateHidden").getAsBoolean()&&probe.get("permissionsHidden").getAsBoolean()){
                SettingsSmokeServer.seen.add("non-owner-read-redacted");busy=true;UiClientSessions.command("settings.write",Map.of("kind","save","revision",Long.toString(dev.mineagent.runtime.neoforge.network.PanelSnapshotInbox.snapshot().revision()),"values","{\"provider.openai.model\":\"must-not-save\"}","providerChangeConfirmed","false"),UUID.randomUUID()).whenComplete((r,e)->mc.execute(()->{busy=false;try{require(e==null&&r.code()==UiProtocol.Code.PERMISSION_DENIED,"UNAUTHORIZED_SETTINGS_SAVED");Files.writeString(root().resolve("denied.json"),JSON.toJson(r));SettingsSmokeServer.seen.add("unauthorized-save-denied");phase=1;}catch(Exception failure){SettingsSmokeServer.failure=failure.toString();}}));}}
            else if(phase==1&&SettingsSmokeServer.step>=12&&probe!=null&&probe.get("model").getAsString().equals("configured-model")&&probe.get("text").getAsString().contains("API Key 已保存")){capture("restored-settings",()->{SettingsSmokeServer.seen.add("restored-ui");phase=2;});}return;
        }
        switch(phase){
            case 0->{if(SettingsSmokeServer.step<1)return;action("invalid","document.querySelector('#open-server-settings').click();const model=document.querySelector('[data-setting=\"provider.openai.model\"]'),url=document.querySelector('[data-setting=\"provider.openai.baseUrl\"]');if(!model||model.disabled)return false;window.__originalSettingsUrl=url.value;model.value='configured-model';model.dispatchEvent(new Event('input',{bubbles:true}));url.value='ftp://invalid.test';url.dispatchEvent(new Event('input',{bubbles:true}));document.querySelector('#settings-save').click();return true;");if(probe!=null&&!probe.get("errors").getAsString().isBlank())phase=1;}
            case 1->{require(probe.get("model").getAsString().equals("configured-model"),"SETTINGS_DRAFT_LOST");Files.writeString(root().resolve("validation.json"),JSON.toJson(probe));action("valid","const u=document.querySelector('[data-setting=\"provider.openai.baseUrl\"]');u.value=window.__originalSettingsUrl;u.dispatchEvent(new Event('input',{bubbles:true}));document.querySelector('#settings-save').click();return true;");if(SettingsSmokeServer.step>=2)phase=2;}
            case 2->{
                if(!staleChecked){busy=true;UiClientSessions.command("settings.write",Map.of("kind","save","revision","0","values","{\"provider.openai.model\":\"stale-change\"}","providerChangeConfirmed","false"),UUID.randomUUID()).whenComplete((r,e)->mc.execute(()->{busy=false;try{require(e==null&&r.code()==UiProtocol.Code.FAILED&&"STALE_REVISION".equals(r.values().get("errorCode")),"STALE_SETTINGS_NOT_REJECTED");Files.writeString(root().resolve("stale-save.json"),JSON.toJson(r));staleChecked=true;}catch(Exception failure){SettingsSmokeServer.failure=failure.toString();}}));return;}
                action("secret-open","const b=document.querySelector('#settings-key-open');if(!b||b.disabled)return false;b.click();return true;");if(mc.screen instanceof NativeSecretScreen s&&typed(s)){typedAt=ticks;phase=20;}}
            case 20->{if(ticks-typedAt<10)return;require(mc.screen instanceof NativeSecretScreen,"SECRET_SCREEN_CHANGED");var screen=(NativeSecretScreen)mc.screen;capture("native-key-masked",()->{checkSecretAndSave(screen);phase=3;});}
            case 3->{if(mc.screen instanceof NativeSecretScreen s&&nativeStatus(s,"密钥已保存")){checkBrowser();SettingsSmokeServer.seen.add("key-not-in-browser");s.onClose();phase=4;}}
            case 4->{action("clear-open","document.querySelector('#settings-key-open').click();return true;");if(mc.screen instanceof NativeSecretScreen s){var clear=button(s,"清除");if(clear!=null&&clear.active){press(clear);press(clear);phase=5;}}}
            case 5->{if(SettingsSmokeServer.step>=4&&mc.screen instanceof NativeSecretScreen s&&nativeStatus(s,"密钥已清除")){s.onClose();phase=6;}}
            case 6->{action("replace-open","document.querySelector('#settings-key-open').click();return true;");if(mc.screen instanceof NativeSecretScreen s&&typed(s)){press(button(s,"替换"));phase=7;}}
            case 7->{if(mc.screen instanceof NativeSecretScreen s&&nativeStatus(s,"密钥已保存")){s.onClose();phase=21;}}
            case 21->{action("workflow-open","const b=[...document.querySelectorAll('#settings-private-provider button')].find(b=>b.textContent==='原生高级 Workflow 编辑');if(!b)return false;b.click();return true;");if(mc.screen instanceof dev.mineagent.runtime.neoforge.client.screen.ControlCenterScreen s){require(s.children().stream().filter(e->e instanceof StringWidget).map(e->((StringWidget)e).getMessage().getString()).anyMatch(text->text.contains("ComfyUI Workflow JSON")),"DELEGATED_PROVIDER_EDITOR_WRONG_PAGE");Files.writeString(root().resolve("workflow-entry.json"),JSON.toJson(Map.of("nativeEditor",true,"providerGrantOnly",true,"sourceNotSentToPage",true)));s.onClose();phase=8;}}
            case 8->{action("permissions","const area=document.querySelector('#settings-permissions');if(!area||area.hidden)return false;area.open=true;document.querySelector('#permission-player-id').value='"+SettingsSmokeServer.target+"';[...area.querySelectorAll('button')].find(b=>b.textContent==='载入离线玩家授权').click();const c=area.querySelector('[data-permission=CREATE_AGENT]');if(!c||c.disabled)return false;c.checked=true;c.dispatchEvent(new Event('change',{bubbles:true}));document.querySelector('#permission-confirm').checked=true;document.querySelector('#permission-save').click();return true;");if(SettingsSmokeServer.step>=5)phase=9;}
            case 9->{action("disable","const c=document.querySelector('[data-setting=\"provider.openai.enabled\"]');if(!c||c.disabled)return false;c.checked=false;c.dispatchEvent(new Event('change',{bubbles:true}));document.querySelector('#settings-save').click();return true;");if(SettingsSmokeServer.step>=6)phase=10;}
            case 10->{action("enable","const c=document.querySelector('[data-setting=\"provider.openai.enabled\"]');if(!c||c.disabled)return false;c.checked=true;c.dispatchEvent(new Event('change',{bubbles:true}));document.querySelector('#settings-save').click();return true;");if(probe!=null&&probe.get("text").getAsString().contains("已保存")){SettingsSmokeServer.seen.add("reenabled");phase=11;}}
            default->{}
        }
    }
    private static void checkSecretAndSave(NativeSecretScreen s){try{checkBrowser();press(button(s,"替换"));}catch(Exception failure){SettingsSmokeServer.failure=failure.toString();}}
}
