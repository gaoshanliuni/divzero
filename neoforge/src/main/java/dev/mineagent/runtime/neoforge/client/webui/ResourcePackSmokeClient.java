package dev.mineagent.runtime.neoforge.client.webui;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.*;
import dev.mineagent.runtime.client.resources.LocalResourcePackStore;
import dev.mineagent.runtime.core.packages.RuntimePackageCanonicalizer;
import dev.mineagent.runtime.neoforge.ui.ResourcePackSmokeServer;
import net.minecraft.client.Minecraft;
import net.minecraft.server.packs.PackResources;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Real CEF download/consent/reload/disable flow. DOM actions only; no OS input and no Provider. */
@EventBusSubscriber(modid="mineagent_runtime",value=Dist.CLIENT)
public final class ResourcePackSmokeClient {
    private static final Gson JSON=new Gson();private static final ObjectMapper JACKSON=new ObjectMapper();
    private static JsonObject probe;private static int ticks,phase,trustTicks;private static boolean opened,backup,finished,trusted,trustPressed;private static List<String> initialPacks=List.of();private static String filename="";private static final String COPY_NAME=ResourcePackSmokeServer.PACKAGE_NAME+" · 历史副本";
    private ResourcePackSmokeClient(){}
    public static void accept(JsonObject value){if(ResourcePackSmokeServer.enabled())probe=value;}
    private static Path root(){return Minecraft.getInstance().gameDirectory.toPath().resolve("resource-pack-smoke");}
    private static void script(String code){var host=WebGuiHostAdapter.INSTANCE;host.browser().executeJavaScript("(()=>{"+code+"})();",host.browser().getURL(),0);}
    private static void action(String key,String code){script("window.__resourcePackSmoke??={};if(window.__resourcePackSmoke["+JSON.toJson(key)+"])return;const ok=(()=>{"+code+"})();if(ok)window.__resourcePackSmoke["+JSON.toJson(key)+"]=true;");}
    private static void poll(){script("window.dispatchEvent(new Event('mineagent:resource-pack-probe'));");}
    private static String text(String key){return probe==null||!probe.has(key)?"":probe.get(key).getAsString();}
    private static List<String> selected(){return Minecraft.getInstance().getResourceManager().listPacks().map(PackResources::packId).toList();}
    private static void require(boolean value,String code){if(!value)throw new IllegalStateException(code);}
    private static LocalResourcePackStore.Asset asset()throws Exception{var id=ResourcePackSmokeServer.runtimePackage.packageId();return LocalResourcePackStore.get(Minecraft.getInstance().gameDirectory.toPath()).list().stream().filter(a->!a.manifest().packageId().equals(id)&&a.manifest().name().equals(COPY_NAME)).findFirst().orElseThrow(()->new IllegalStateException("RESOURCE_PACK_SMOKE_ASSET_MISSING"));}
    private static void write(String name,Object value)throws Exception{Files.createDirectories(root());Files.writeString(root().resolve(name+".json"),JACKSON.writeValueAsString(value));}
    private static void fail(Throwable error)throws Exception{finished=true;write("client-failure",Map.of("phase",phase,"ticks",ticks,"error",error.toString(),"serverFailure",Objects.toString(ResourcePackSmokeServer.failure,""),"probe",probe==null?"{}":probe.toString()));var mc=Minecraft.getInstance();WebGuiHostAdapter.INSTANCE.close();mc.stop();}
    private static Map<String,Object> actualResource(LocalResourcePackStore.Asset asset)throws Exception{
        var id=net.minecraft.resources.Identifier.fromNamespaceAndPath("mineagent_runtime","native-resource-smoke.txt");var resource=Minecraft.getInstance().getResourceManager().getResource(id).orElseThrow(()->new IllegalStateException("RESOURCE_PACK_SMOKE_RESOURCE_MISSING"));byte[] bytes;try(var input=resource.open()){bytes=input.readAllBytes();}
        require(new String(bytes,StandardCharsets.UTF_8).equals(ResourcePackSmokeServer.RESOURCE_TEXT),"RESOURCE_PACK_SMOKE_RESOURCE_CONTENT");return Map.of("identifier",id.toString(),"source",resource.sourcePackId(),"sha256",RuntimePackageCanonicalizer.sha256(bytes),"bytes",bytes.length);
    }
    private static Map<String,Object> optionsEvidence()throws Exception{
        Path file=Minecraft.getInstance().gameDirectory.toPath().resolve("options.txt");var values=new LinkedHashMap<String,String>();for(String line:Files.readAllLines(file,StandardCharsets.UTF_8))for(String key:List.of("resourcePacks","incompatibleResourcePacks"))if(line.startsWith(key+":"))values.put(key,line.substring(key.length()+1));return Map.of("resourcePacks",values.getOrDefault("resourcePacks",""),"incompatibleResourcePacks",values.getOrDefault("incompatibleResourcePacks",""));
    }
    @SubscribeEvent public static void tick(ClientTickEvent.Post event)throws Exception{
        if(!ResourcePackSmokeServer.enabled()||finished)return;var mc=Minecraft.getInstance();var host=WebGuiHostAdapter.INSTANCE;ticks++;
        try{
            if(ticks>9000)throw new IllegalStateException("RESOURCE_PACK_CEF_TIMEOUT_"+phase);if(ResourcePackSmokeServer.failure!=null)throw new IllegalStateException(ResourcePackSmokeServer.failure);
            if(!backup&&mc.screen instanceof net.minecraft.client.gui.screens.BackupConfirmScreen screen){backup=true;var field=screen.getClass().getDeclaredField("onProceed");field.setAccessible(true);((net.minecraft.client.gui.screens.BackupConfirmScreen.Listener)field.get(screen)).proceed(false,false);}
            if(!trusted&&mc.player!=null){
                if(ticks%20==0)net.neoforged.neoforge.client.network.ClientPacketDistributor.sendToServer(new dev.mineagent.runtime.neoforge.network.MineAgentPayloads.PanelRequest());
                if(mc.screen instanceof dev.mineagent.runtime.neoforge.client.screen.ControlCenterScreen screen&&!trustPressed)for(var child:screen.children())if(child instanceof net.minecraft.client.gui.components.Button button&&button.active&&button.getMessage().getString().equals("信任此服务器")){button.onPress(new net.minecraft.client.input.InputWithModifiers(){public int input(){return org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER;}public int modifiers(){return 0;}});trustPressed=true;net.neoforged.neoforge.client.network.ClientPacketDistributor.sendToServer(new dev.mineagent.runtime.neoforge.network.MineAgentPayloads.PanelRequest());break;}
                var snapshot=dev.mineagent.runtime.neoforge.network.PanelSnapshotInbox.snapshot();String fingerprint=snapshot.values().getOrDefault("security.identityFingerprint","");if(ticks%20==0)write("startup",Map.of("ticks",ticks,"screen",mc.screen==null?"NONE":mc.screen.getClass().getName(),"trustPressed",trustPressed,"signatureValid",dev.mineagent.runtime.neoforge.network.PanelSnapshotInbox.signatureValid(),"snapshotRevision",snapshot.revision(),"fingerprint",fingerprint,"initialized",snapshot.values().getOrDefault("runtime.initialized","")));
                if(trustPressed&&new dev.mineagent.runtime.client.trust.ServerTrustStore(mc.gameDirectory.toPath().resolve("config/mineagent-trusted-servers.properties")).status("local-integrated",fingerprint)==dev.mineagent.runtime.client.trust.TrustStatus.TRUSTED){trusted=true;write("trust",Map.of("actualNativeTrustButton",true,"fingerprint",fingerprint,"runtimeInitialized",Boolean.parseBoolean(snapshot.values().getOrDefault("runtime.initialized","false")),"systemInputInjected",false));mc.setScreen(null);mc.player.connection.sendCommand("ai accept");}else return;
            }
            if(trusted&&++trustTicks<40)return;
            if(mc.player!=null&&!opened&&ResourcePackSmokeServer.runtimePackage!=null){opened=true;initialPacks=selected();host.open();}
            if(!opened||!host.ready()||UiClientSessions.current()==null||ticks%10!=0)return;poll();String catalog=text("catalogText"),resources=text("resourceText");if(catalog.contains("结果待核对"))throw new IllegalStateException("RESOURCE_CATALOG_REJECTED:"+catalog);var localView=ClientResourcePacks.read(0);if(ticks%40==0)write("progress",Map.of("phase",phase,"ticks",ticks,"local",localView,"probe",probe==null?"{}":probe.toString()));
            switch(phase){
                case 0->{action("catalog","document.querySelector('#open-package-catalog')?.click();return true;");if(catalog.contains(ResourcePackSmokeServer.PACKAGE_NAME)&&catalog.contains("本机资源包下载 / 启用"))phase=1;}
                case 1->{action("history-list","const c=[...document.querySelectorAll('#package-catalog-list article')].find(n=>n.querySelector('strong')?.textContent==="+JSON.toJson(ResourcePackSmokeServer.PACKAGE_NAME)+");const b=[...c?.querySelectorAll('button')||[]].find(n=>n.textContent==='当前包与真实版本');if(!b)return false;b.click();return true;");if(catalog.contains("确认启用此版本"))phase=2;}
                case 2->{action("history-enable","const b=[...document.querySelectorAll('#package-catalog-detail button')].filter(n=>n.textContent==='确认启用此版本').at(-1);if(!b||b.disabled)return false;b.click();return true;");if(resources.contains("所选服务器包："+COPY_NAME)&&ResourcePackSmokeServer.historicalCopyVerified)phase=3;}
                case 3->{if(resources.contains("ENABLED · 当前 ResourceManager 已装入")&&resources.contains("COMPLETED · RESOURCE_PACK_RELOADED_AND_OPTIONS_OBSERVED")){var a=asset();filename=a.filename();require(a.state().equals("ENABLED")&&a.globalConsent(),"RESOURCE_PACK_ENABLE_LEDGER");var actual=actualResource(a);require(actual.get("source").equals("file/"+filename),"RESOURCE_PACK_SMOKE_SOURCE");require(selected().contains("file/"+filename)&&mc.options.resourcePacks.contains("file/"+filename),"RESOURCE_PACK_ENABLE_SELECTION");write("enabled",Map.of("asset",a,"resource",actual,"selected",selected(),"options",optionsEvidence(),"oneCatalogClick",true));phase=4;}}
                case 4->{action("disable","const p=[...document.querySelectorAll('.window')].find(n=>n.dataset.viewId==='runtime-resource-packs');const c=[...p?.querySelectorAll('article')||[]].find(n=>n.querySelector('strong')?.textContent==="+JSON.toJson(COPY_NAME)+");const q=c?.querySelector('input[type=checkbox]');const b=[...c?.querySelectorAll('button')||[]].find(n=>n.textContent==='本机停用同来源包并重载');if(!q||!b||b.disabled)return false;q.checked=true;b.click();return true;");phase=5;}
                case 5->{action("disable","const p=[...document.querySelectorAll('.window')].find(n=>n.dataset.viewId==='runtime-resource-packs');const c=[...p?.querySelectorAll('article')||[]].find(n=>n.querySelector('strong')?.textContent==="+JSON.toJson(COPY_NAME)+");const q=c?.querySelector('input[type=checkbox]');const b=[...c?.querySelectorAll('button')||[]].find(n=>n.textContent==='本机停用同来源包并重载');if(!q||!b||b.disabled)return false;q.checked=true;b.click();return true;");if(!resources.contains("DISABLED · 当前 ResourceManager 未装入")||!resources.contains("COMPLETED · RESOURCE_PACK_RELOADED_AND_OPTIONS_OBSERVED"))return;var a=asset();require(a.state().equals("DISABLED")&&!a.globalConsent(),"RESOURCE_PACK_DISABLE_LEDGER");require(selected().equals(initialPacks),"RESOURCE_PACK_OTHER_SELECTION_CHANGED");require(!mc.options.resourcePacks.contains("file/"+filename),"RESOURCE_PACK_DISABLE_OPTIONS");require(Files.isRegularFile(LocalResourcePackStore.get(mc.gameDirectory.toPath()).cacheDirectory().resolve(filename)),"RESOURCE_PACK_DISABLE_REMOVED_CACHE");var latest=LocalResourcePackStore.get(mc.gameDirectory.toPath()).latest(filename).orElseThrow();require(latest.phase().equals("COMPLETED")&&latest.code().equals("RESOURCE_PACK_RELOADED_AND_OPTIONS_OBSERVED"),"RESOURCE_PACK_DISABLE_RESULT");write("result",Map.ofEntries(Map.entry("status","REAL_CEF_RESOURCE_HISTORY_COPY_ENABLE_RELOAD_DISABLE_VERIFIED"),Map.entry("package",ResourcePackSmokeServer.runtimePackage),Map.entry("historicalCopyVerified",ResourcePackSmokeServer.historicalCopyVerified),Map.entry("asset",a),Map.entry("job",latest),Map.entry("selectedRestored",selected()),Map.entry("initialSelected",initialPacks),Map.entry("cacheRetained",true),Map.entry("providerCalls",0),Map.entry("systemInputInjected",false),Map.entry("clientJavaOrRhinoExecuted",false),Map.entry("fullV1",false)));finished=true;dev.mineagent.runtime.neoforge.MineAgentRuntimeMod.LOGGER.info("MINEAGENT_RESOURCE_PACK_OK");host.close();mc.stop();}
            }
        }catch(Exception error){fail(error);throw error;}
    }
}
