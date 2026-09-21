package dev.mineagent.runtime.neoforge.client.chat;
import com.google.gson.*;
import dev.mineagent.runtime.neoforge.ui.ConversationHostSmokeServer;
import dev.mineagent.runtime.neoforge.client.webui.*;
import dev.mineagent.runtime.neoforge.client.host.HostCommandScreen;
import dev.mineagent.runtime.neoforge.client.screen.NativeSecretScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.input.KeyEvent;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

final class ConversationHostSmokeClient {
    private static final Gson JSON=new Gson();private static JsonObject probe;private static int ticks,phase,wait;private static boolean done,opening,capturing,captured;private static String original,changed;private static long revision;private static final Set<UUID> approved=new HashSet<>();private static CompletableFuture<Map<String,Object>> nativeProbe;
    static void accept(JsonObject value){probe=value;}
    private static Path root()throws Exception{return Files.createDirectories(Minecraft.getInstance().gameDirectory.toPath().resolve("host-command-smoke"));}
    private static void write(String name,Object v)throws Exception{Files.writeString(root().resolve(name+".json"),JSON.toJson(v));}
    private static void script(String s){var h=WebGuiHostAdapter.INSTANCE;h.browser().executeJavaScript("(()=>{"+s+"})();",h.browser().getURL(),0);}
    private static void require(boolean value,String code){if(!value)throw new IllegalStateException(code);}
    private static void poll(){script("window.mineagentQuery({request:JSON.stringify({channel:'conversationAgentProbe',api:!!document.querySelector('#api-base-url'),url:document.querySelector('#api-base-url')?.value||'',disabled:document.querySelector('#api-base-url')?.disabled??true,keyDisabled:document.querySelector('#api-key-open')?.disabled??true,status:document.querySelector('#api-settings-status')?.textContent||'',browserKeyField:!!document.querySelector('[data-setting=\"provider.openai.apiKey\"]')}),persistent:false,onSuccess(){},onFailure(){}});");}
    private static void editUrl(String value){script("const u=document.querySelector('#api-base-url');u.value="+JSON.toJson(value)+";u.dispatchEvent(new Event('input',{bubbles:true}));document.querySelector('#api-address-consent').checked=true;document.querySelector('#api-settings-save').click();");}
    private static void review(HostCommandScreen screen)throws Exception{
        UUID id=screen.operation();Path pending=root().resolve("command-"+id+".json");if(!Files.exists(pending))Files.writeString(pending,JSON.toJson(Map.of("operation",id.toString(),"sha256",screen.commandHash(),"script",screen.script(),"executed",false)));
        Path decision=root().resolve("approve-"+id+".txt");if(approved.contains(id)||!Files.exists(decision)||!Files.readString(decision).strip().equals(screen.commandHash()))return;
        var run=screen.children().stream().filter(w->w instanceof Button b&&b.getMessage().getString().equals("执行这条命令")).map(w->(Button)w).findFirst().orElse(null);
        if(run!=null&&run.active){approved.add(id);run.onPress(new KeyEvent(257,0,0));return;}
        screen.children().stream().filter(w->w instanceof Button b&&b.active&&b.getMessage().getString().equals("下一页")).map(w->(Button)w).findFirst().ifPresent(b->b.onPress(new KeyEvent(257,0,0)));
    }
    private static CompletableFuture<Map<String,Object>> verifyNativeApp(){var mc=Minecraft.getInstance();var directory=mc.gameDirectory.toPath();return CompletableFuture.supplyAsync(()->{
        String source="Add-Type -TypeDefinition 'using System; using System.Runtime.InteropServices; public static class MineAgentWindowProbe { [DllImport(\"user32.dll\")] public static extern bool IsWindowVisible(IntPtr h); }'; @(Get-Process -Name cloudmusic -ErrorAction SilentlyContinue | Where-Object { $_.MainWindowHandle -ne 0 } | ForEach-Object { [pscustomobject]@{pid=$_.Id; title=$_.MainWindowTitle; path=$_.Path; visible=[MineAgentWindowProbe]::IsWindowVisible($_.MainWindowHandle)} }) | ConvertTo-Json -Compress";
        var request=new dev.mineagent.runtime.core.host.HostCommandRequest(UUID.randomUUID(),"只读验证网易云实际进程与窗口",source,15);return new dev.mineagent.runtime.client.host.LocalPowerShellExecutor(directory,dev.mineagent.runtime.client.host.LocalPowerShellExecutor.discover().orElseThrow()).execute(request,request.sha256(),()->true);
    });}
    static void tick()throws Exception{
        if(done)return;var mc=Minecraft.getInstance();ticks++;var host=WebGuiHostAdapter.INSTANCE;
        try{
            if(ticks%40==0)write("progress",Map.of("phase",phase,"opening",opening,"probe",probe==null?new JsonObject():probe,"verified",ConversationHostSmokeServer.verified));
            if(!ConversationHostSmokeServer.failure.isEmpty())throw new IllegalStateException(ConversationHostSmokeServer.failure);if(ticks>22000)throw new IllegalStateException("HOST_NATIVE_TIMEOUT_"+phase);if(mc.player==null||!ConversationHostSmokeServer.ready)return;
            if(mc.screen instanceof HostCommandScreen screen){require(!ConversationHostSmokeServer.geometryOnly(),"GEOMETRY_READ_ONLY_HOST_REQUEST");review(screen);return;}
            if(phase==0){if(ConversationHostSmokeServer.geometryOnly()){phase=9;mc.player.connection.sendChat("@工具助手 只读测试：先查看最新建模能力和完整JSON格式，再分别用validate_model_geometry验证长方体、平面、圆盘、平面圆环、圆柱、圆锥、圆台、棱柱、棱锥、椭球、胶囊11种新增形状。每种用简单有效尺寸、world目标。之前通用错误不便定位，本轮可按新返回的具体字段提示修正。不要发布包或操作游戏、电脑。");}else{host.open();phase=1;}}
            if(phase<7&&host.ready()&&UiClientSessions.current()!=null&&ticks%10==0){if(!opening){opening=true;script("document.querySelector('#more-menu').open=true;document.querySelector('#open-api-settings').click();");}poll();}
            if(phase==1&&probe!=null&&probe.get("api").getAsBoolean()&&!probe.get("disabled").getAsBoolean()){original=probe.get("url").getAsString();require(original.startsWith("https://api.deepseek.com/"),"API_ORIGINAL_NOT_OFFICIAL");changed=original.endsWith("/")?original.substring(0,original.length()-1):original+"/";revision=ConversationHostSmokeServer.configRevision;editUrl(changed);phase=2;}
            if(phase==2&&ConversationHostSmokeServer.url.equals(changed)&&ConversationHostSmokeServer.configRevision>revision&&probe!=null&&!probe.get("keyDisabled").getAsBoolean()){revision=ConversationHostSmokeServer.configRevision;write("api-url-written",Map.of("url",changed,"revision",revision));editUrl(original);phase=3;}
            if(phase==3&&ConversationHostSmokeServer.url.equals(original)&&ConversationHostSmokeServer.configRevision>revision&&probe!=null&&!probe.get("keyDisabled").getAsBoolean()){script("document.querySelector('#api-key-open').click();");phase=4;}
            if(phase==4&&mc.screen instanceof NativeSecretScreen secret){var input=secret.children().stream().filter(w->w instanceof EditBox).map(w->(EditBox)w).findFirst().orElseThrow();require(input.getValue().isEmpty(),"SECRET_INPUT_PREFILLED");require(probe!=null&&!probe.get("browserKeyField").getAsBoolean(),"SECRET_BROWSER_FIELD");write("api-settings",Map.of("shortcutInMore",true,"urlSavedAndRestored",true,"nativeSecretScreen",true,"noPlaintextKeyInBrowser",true,"savedKeyUnchanged",true));secret.onClose();phase=5;wait=0;}
            if(phase==5&&++wait>35&&!captured){captured=true;capturing=true;net.minecraft.client.Screenshot.takeScreenshot(mc.getMainRenderTarget(),img->{try(img){img.writeToFile(root().resolve("api-settings.png"));}catch(Exception e){ConversationHostSmokeServer.failure=e.toString();}finally{capturing=false;}});}
            if(phase==5&&captured&&!capturing){host.hideWorkspace();phase=7;mc.player.connection.sendChat("@工具助手 帮我打开电脑上的网易云音乐。请自己查找已安装的软件，用PowerShell打开并检查实际进程/可见窗口；不要下载安装或操作其它应用。每条命令我会在本机审查确认。");}
            if(phase==7&&ConversationHostSmokeServer.verified>=1){nativeProbe=verifyNativeApp();phase=8;}
            if(phase==8&&nativeProbe.isDone()){var result=nativeProbe.join();write("native-app-window",result);require(result.get("status").equals("EXECUTED"),"HOST_INDEPENDENT_PROBE_FAILED");var value=JsonParser.parseString(result.get("stdout").toString());var rows=value.isJsonArray()?value.getAsJsonArray():new JsonArray();if(value.isJsonObject())rows.add(value);boolean visible=false;for(var row:rows){var v=row.getAsJsonObject();visible|=v.get("visible").getAsBoolean()&&v.get("path").getAsString().toLowerCase(Locale.ROOT).endsWith("cloudmusic.exe");}require(visible,"NETEASE_VISIBLE_WINDOW_NOT_FOUND");phase=9;mc.player.connection.sendChat("@工具助手 现在只读测试建模能力：先查支持的形状，再分别用validate_model_geometry验证长方体、平面、圆盘、平面圆环、圆柱、圆锥、圆台、棱柱、棱锥、椭球、胶囊共11种新增形状。每个用简单有效尺寸和world目标。不要发布包、修改游戏或电脑；遇到无效参数按真实错误修正。");}
            if(phase==9&&ConversationHostSmokeServer.verified>=2){write("client",Map.of("status",ConversationHostSmokeServer.geometryOnly()?"PRIMITIVE_TOOLS_NATIVE_VERIFIED":"HOST_APP_API_SETTINGS_NATIVE_VERIFIED","approvedCommands",approved.size(),"systemInputInjected",false,"appLeftOpen",!ConversationHostSmokeServer.geometryOnly(),"geometryPreviewRendered",false));done=true;host.close();dev.mineagent.runtime.neoforge.MineAgentRuntimeMod.LOGGER.info("MINEAGENT_CONVERSATION_AGENT_OK");mc.stop();}
        }catch(Exception e){done=true;write("client-failure",Map.of("phase",phase,"error",e.toString()));host.close();mc.stop();}
    }
}
