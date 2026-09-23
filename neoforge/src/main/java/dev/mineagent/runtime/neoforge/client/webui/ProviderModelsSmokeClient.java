package dev.mineagent.runtime.neoforge.client.webui;
import com.google.gson.*;
import dev.mineagent.runtime.neoforge.ui.ProviderModelsSmokeServer;
import dev.mineagent.runtime.neoforge.client.screen.*;
import dev.mineagent.runtime.neoforge.network.PanelSnapshotInbox;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.input.*;
import java.nio.file.*;
import java.util.*;
/** Native callbacks + actual CEF DOM in a fresh profile; credentials never enter JS. */
public final class ProviderModelsSmokeClient {
 private static int ticks,phase,wait;private static boolean done,captured,busy,customCaptured,webCaptured,webCustomCaptured;private static JsonObject probe;
 public static void accept(JsonObject value){if(ProviderModelsSmokeServer.active())probe=value;}
 private static void require(boolean test,String code){if(!test)throw new IllegalStateException(code);}
 private static Button button(net.minecraft.client.gui.screens.Screen s,String text){return s.children().stream().filter(v->v instanceof Button b&&b.getMessage().getString().contains(text)).map(v->(Button)v).filter(b->b.active).findFirst().orElse(null);}
 private static void press(Button b){require(b!=null,"BUTTON_NOT_READY");b.onPress(new KeyEvent(257,0,0));}
 private static void enterKey(NativeSecretScreen s){var input=s.children().stream().filter(v->v instanceof EditBox).map(v->(EditBox)v).findFirst().orElseThrow();char[] key=ProviderModelsSmokeServer.consumeInput();require(key!=null,"KEY_FIXTURE_UNAVAILABLE");try{input.setValue(new String(key));}finally{Arrays.fill(key,'\0');}press(button(s,"保存"));}
 private static void script(String code){var h=WebGuiHostAdapter.INSTANCE;h.browser().executeJavaScript("(()=>{"+code+"})();",h.browser().getURL(),0);}
 private static void capture(Path path){busy=true;var mc=Minecraft.getInstance();net.minecraft.client.Screenshot.takeScreenshot(mc.getMainRenderTarget(),image->{try(image){image.writeToFile(path);}catch(Exception error){ProviderModelsSmokeServer.failure="SCREENSHOT_FAILED";}finally{busy=false;}});}
 public static void tick()throws Exception{if(done)return;var mc=Minecraft.getInstance();var host=WebGuiHostAdapter.INSTANCE;Path root=Files.createDirectories(mc.gameDirectory.toPath().resolve("provider-models-smoke"));ticks++;try{
  if(ticks%100==0)Files.writeString(root.resolve("progress.json"),new Gson().toJson(Map.of("phase",phase,"probe",probe==null?new JsonObject():probe,"screen",mc.screen==null?"none":mc.screen.getClass().getSimpleName(),"revision",PanelSnapshotInbox.snapshot().revision(),"providerPermission",PanelSnapshotInbox.snapshot().values().getOrDefault("permission.manage_providers","missing"),"labels",mc.screen==null?List.of():mc.screen.children().stream().filter(v->v instanceof StringWidget).map(v->((StringWidget)v).getMessage().getString()).toList())));
  require(ProviderModelsSmokeServer.failure.isEmpty(),ProviderModelsSmokeServer.failure);require(ticks<3600,"PROVIDER_MODELS_TIMEOUT_PHASE_"+phase);if(!ProviderModelsSmokeServer.ready||mc.player==null||busy)return;
  if(phase>=5&&phase<12&&mc.screen==null)throw new IllegalStateException("FIXTURE_INTERACTION_SCREEN_CLOSED");
  switch(phase){
   case 0->{
    String fp=PanelSnapshotInbox.snapshot().values().getOrDefault("security.identityFingerprint","");
    if(fp.isEmpty()||new dev.mineagent.runtime.client.trust.ServerTrustStore(mc.gameDirectory.toPath().resolve("config/mineagent-trusted-servers.properties")).status("local-integrated",fp)!=dev.mineagent.runtime.client.trust.TrustStatus.TRUSTED){if(mc.screen instanceof ControlCenterScreen s){var trust=button(s,"信任此服务器");if(trust!=null)press(trust);}return;}
    if(++wait==1)mc.player.connection.sendCommand("ai accept");if(wait<40)return;wait=0;mc.setScreen(new NativeSecretScreen(null));phase=1;
   }
   case 1->{if(!(mc.screen instanceof NativeSecretScreen s)||button(s,"清除")==null)return;enterKey(s);phase=2;}
   case 2->{if(!(mc.screen instanceof ProviderModelScreen s))return;var choice=button(s,"deepseek-flash");if(choice==null)return;if(!captured){captured=true;capture(root.resolve("native-model-list.png"));return;}press(choice);phase=3;}
   case 3->{if(mc.screen instanceof ProviderModelScreen)return;require(PanelSnapshotInbox.snapshot().values().getOrDefault("provider.openai.model","").equals("deepseek-flash"),"NATIVE_MODEL_NOT_SAVED");mc.setScreen(new ProviderModelScreen(null));phase=30;}
   case 30->{if(!(mc.screen instanceof ProviderModelScreen s))return;var custom=s.children().stream().filter(v->v instanceof EditBox box&&box.getMessage().getString().equals("自定义模型名称")).map(v->(EditBox)v).findFirst().orElse(null);if(custom==null)return;custom.setValue("my-private/model-v9");var save=button(s,"保存");if(save==null)return;if(++wait<8)return;wait=0;if(!customCaptured){customCaptured=true;capture(root.resolve("native-custom-model.png"));return;}press(save);phase=31;}
   case 31->{if(mc.screen instanceof ProviderModelScreen)return;require(PanelSnapshotInbox.snapshot().values().getOrDefault("provider.openai.model","").equals("my-private/model-v9"),"NATIVE_CUSTOM_MODEL_NOT_SAVED");ProviderModelsSmokeServer.resetRequested=true;phase=4;}
   case 4->{if(!ProviderModelsSmokeServer.resetDone||!PanelSnapshotInbox.snapshot().values().getOrDefault("provider.openai.model","").equals("select-from-catalog"))return;host.open();phase=7;}
   case 7->{if(!host.ready()||UiClientSessions.current()==null||ticks%10!=0)return;script("if(!document.querySelector('#api-key-open')){document.querySelector('#more-menu').open=true;document.querySelector('#open-api-settings')?.click();}const b=document.querySelector('#api-key-open');if(b&&!b.disabled&&!window.__modelsKeyOpened){window.__modelsKeyOpened=true;b.click();}");if(mc.screen instanceof NativeSecretScreen s&&button(s,"清除")!=null){enterKey(s);phase=8;}}
   case 8->{if(mc.screen instanceof NativeSecretScreen)return;require(mc.screen instanceof WebGuiInteractionScreen,"KEY_DID_NOT_RETURN_TO_WEB");phase=5;}
   case 5->{if(!host.ready()||UiClientSessions.current()==null||ticks%10!=0)return;
    script("if(!document.querySelector('#api-model-select'))document.querySelector('#open-api-settings')?.click();const select=document.querySelector('#api-model-select');if(select&&!select.disabled&&!window.__modelsSelected){const ids=[...select.options].map(o=>o.value);if(ids.includes('deepseek-flash')){select.value='deepseek-flash';select.dispatchEvent(new Event('change',{bubbles:true}));window.__modelsSelected=true;document.querySelector('#api-settings-save').click();}}window.mineagentQuery({request:JSON.stringify({channel:'conversationAgentProbe',selected:select?.value??'',models:select?[...select.options].map(o=>o.value):[],status:document.querySelector('#api-settings-status')?.textContent??'',modelStatus:document.querySelector('#api-model-status')?.textContent??'',saved:window.__modelsSelected??false}),persistent:false,onSuccess(){},onFailure(){}});");
    if(probe!=null&&probe.has("saved")&&probe.get("saved").getAsBoolean()&&PanelSnapshotInbox.snapshot().values().getOrDefault("provider.openai.model","").equals("deepseek-flash")){phase=6;wait=0;}
   }
   case 6->{if(++wait<12)return;if(!webCaptured){webCaptured=true;capture(root.resolve("web-model-list.png"));return;}wait=0;script("const s=document.querySelector('#api-model-select'),c=document.querySelector('#api-model-custom');if(!c.parentElement.hidden||getComputedStyle(c.parentElement).display!=='none')throw Error('CUSTOM_INPUT_MUST_START_HIDDEN');s.value='';s.dispatchEvent(new Event('change',{bubbles:true}));if(c.parentElement.hidden||getComputedStyle(c.parentElement).display==='none')throw Error('CUSTOM_INPUT_NOT_SHOWN');c.value='my-private/web-model-v9';c.dispatchEvent(new Event('input',{bubbles:true}));document.querySelector('#api-settings-save').click();");phase=9;}
   case 9->{if(!PanelSnapshotInbox.snapshot().values().getOrDefault("provider.openai.model","").equals("my-private/web-model-v9"))return;if(++wait<8)return;if(!webCustomCaptured){webCustomCaptured=true;capture(root.resolve("web-custom-model.png"));return;}wait=0;Files.writeString(root.resolve("custom-models.json"),new Gson().toJson(Map.of("native","my-private/model-v9","web","my-private/web-model-v9","bothSavedAndReadBack",true,"availabilityNotClaimed",true)));phase=10;}
   case 10->{script("const s=document.querySelector('#api-model-select');if(s&&!s.disabled&&!window.__customRestored){s.value='deepseek-flash';s.dispatchEvent(new Event('change',{bubbles:true}));if(!document.querySelector('#api-model-custom').parentElement.hidden||getComputedStyle(document.querySelector('#api-model-custom').parentElement).display!=='none')throw Error('CUSTOM_INPUT_NOT_HIDDEN');document.querySelector('#api-settings-save').click();window.__customRestored=true;}");if(PanelSnapshotInbox.snapshot().values().getOrDefault("provider.openai.model","").equals("deepseek-flash"))phase=11;}
   case 11->{if(++wait<12)return;Files.writeString(root.resolve("client.json"),new Gson().toJson(Map.of("status","REAL_PROVIDER_MODELS_NATIVE_WEB_VERIFIED","nativeSecretSaved",true,"automaticNativePicker",true,"nativeSelectionSaved",true,"webSelectionSaved",true,"webKeySavedAndReturned",true,"customModelsSaved",true,"chatCompletions",0,"systemInputInjected",false,"web",probe)));done=true;host.close();dev.mineagent.runtime.neoforge.MineAgentRuntimeMod.LOGGER.info("MINEAGENT_CONVERSATION_AGENT_OK");mc.stop();}
  }
 }catch(Exception error){done=true;Files.writeString(root.resolve("failure.json"),new Gson().toJson(Map.of("error",error.getMessage(),"phase",phase,"probe",probe==null?new JsonObject():probe)));host.close();mc.stop();}}
}
