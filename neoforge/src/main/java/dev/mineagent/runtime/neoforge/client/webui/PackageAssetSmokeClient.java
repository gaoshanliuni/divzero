package dev.mineagent.runtime.neoforge.client.webui;

import com.google.gson.*;
import dev.mineagent.runtime.neoforge.ui.PackageAssetSmokeServer;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.nio.file.*;
import java.util.*;

/** Real CEF DOM flow; fixtures create only the source package, authority and world identities. */
@EventBusSubscriber(modid="mineagent_runtime",value=Dist.CLIENT)
public final class PackageAssetSmokeClient {
    private static final Gson JSON=new Gson();private static int phase,ticks;private static boolean opened,finished,glass;private static JsonObject probe;
    private PackageAssetSmokeClient(){}
    public static void accept(JsonObject value){if(PackageAssetSmokeServer.enabled()){probe=value;glass|=value.has("glass")&&value.get("glass").getAsBoolean();}}
    private static Path root(){return Minecraft.getInstance().gameDirectory.toPath().resolve("package-asset-smoke").resolve(PackageAssetSmokeServer.stage()).resolve("client");}
    private static String q(Object value){return JSON.toJson(value);}
    private static void script(String value){var host=WebGuiHostAdapter.INSTANCE;host.browser().executeJavaScript("(()=>{"+value+"})();",host.browser().getURL(),0);}
    private static void action(String key,String value){script("window.__packageAssetsSmoke??={};if(window.__packageAssetsSmoke["+q(key)+"])return;const ok=(()=>{"+value+"})();if(ok)window.__packageAssetsSmoke["+q(key)+"]=true;");}
    private static void probe(){script("const card=document.querySelector('.event-card');const cardBackground=card?getComputedStyle(card).backgroundColor:'';window.mineagentQuery({request:JSON.stringify({channel:'packageAssetProbe',text:document.body.innerText,cardBackground,glass:cardBackground.startsWith('rgba(')&&!cardBackground.endsWith(', 1)'),windows:[...document.querySelectorAll('.window')].map(n=>n.dataset.viewId)}),persistent:false,onSuccess(){},onFailure(){}});");}
    private static boolean window(String id){if(probe==null||!probe.has("windows"))return false;for(var value:probe.getAsJsonArray("windows"))if(value.getAsString().equals(id))return true;return false;}
    private static boolean windowPrefix(String id){if(probe==null||!probe.has("windows"))return false;for(var value:probe.getAsJsonArray("windows"))if(value.getAsString().startsWith(id))return true;return false;}
    private static boolean text(String value){return probe!=null&&probe.has("text")&&probe.get("text").getAsString().contains(value);}
    private static void finish()throws Exception{
        if(!PackageAssetSmokeServer.stage().equals("verify")&&!glass)throw new IllegalStateException("PACKAGE_ASSET_GLASS_NOT_OBSERVED");Files.createDirectories(root());Files.writeString(root().resolve("result.json"),JSON.toJson(Map.of("stage",PackageAssetSmokeServer.stage(),"phase",phase,"probe",probe==null?new JsonObject():probe,"semiTransparent",glass,"providerCalls",0,"systemInputInjected",false,"fullV1",false)));finished=true;WebGuiHostAdapter.INSTANCE.close();Minecraft.getInstance().stop();dev.mineagent.runtime.neoforge.MineAgentRuntimeMod.LOGGER.info("MINEAGENT_PACKAGE_ASSETS_{}_OK",PackageAssetSmokeServer.stage().toUpperCase(Locale.ROOT));
    }
    private static String catalog(String name,String button){return "const p=[...document.querySelectorAll('.window')].find(n=>n.dataset.viewId==='runtime-package-catalog');const c=[...p?.querySelectorAll('article')||[]].find(n=>n.querySelector('strong')?.textContent==="+q(name)+");const b=[...c?.querySelectorAll('button')||[]].find(n=>n.textContent==="+q(button)+");if(!b||b.disabled)return false;b.click();return true;";}
    private static String packageTools(UUID pkg){return "const p=[...document.querySelectorAll('.window')].find(n=>n.dataset.viewId==='runtime-package-tools');const s=p?.querySelector('select');if(!s||![...s.options].some(o=>o.value==="+q(PackageAssetSmokeServer.agent)+"))return false;s.value="+q(PackageAssetSmokeServer.agent)+";s.dispatchEvent(new Event('change',{bubbles:true}));const b=[...p.querySelectorAll('button')].find(n=>n.textContent==='查看并启用世界内容');if(!b||b.disabled)return false;b.click();return true;";}
    private static String activate(UUID pkg,int x,boolean restore){return "const p=[...document.querySelectorAll('.window')].find(n=>n.dataset.viewId?.startsWith('world-"+pkg+"-r'));if(!p)return false;const f=k=>p.querySelector('[data-world-field='+k+']');for(const [k,v] of Object.entries({dimension:'minecraft:overworld',x:'"+x+"',y:'120',z:'0'})){const n=f(k);if(!n)return false;n.value=v;n.dispatchEvent(new Event('change',{bubbles:true}));}const c=p.querySelector('[data-world-consent=true]'),r=p.querySelector('[data-world-auto-restore=true]'),b=p.querySelector('[data-world-activate]');if(!c||!r||!b||b.disabled||("+restore+"&&r.disabled))return false;c.checked=true;r.checked="+restore+";b.click();return true;";}
    private static void prepare(){
        switch(phase){
            case 0->{if(!PackageAssetSmokeServer.ready)return;action("catalog-open","const b=document.querySelector('#open-package-catalog');if(!b)return false;b.click();return true;");if(window("runtime-package-catalog"))phase=1;}
            case 1->{action("source-tools",catalog(PackageAssetSmokeServer.SOURCE_NAME,"打开现有操作"));if(window("runtime-package-tools"))phase=2;}
            case 2->{action("source-world",packageTools(PackageAssetSmokeServer.sourcePackage));if(windowPrefix("world-"+PackageAssetSmokeServer.sourcePackage+"-r"))phase=3;}
            case 3->{action("source-activate",activate(PackageAssetSmokeServer.sourcePackage,650,false));if(PackageAssetSmokeServer.sourceActive)phase=4;}
            case 4->{action("catalog-assets-open","const b=document.querySelector('#open-package-catalog');if(!b)return false;b.click();return true;");if(window("runtime-package-catalog"))phase=5;}
            case 5->{action("source-assets",catalog(PackageAssetSmokeServer.SOURCE_NAME,"库名称 / 复制 / 保存资产"));if(window("runtime-package-assets")&&text("修改我的库名称"))phase=6;}
            case 6->{action("alias","const p=[...document.querySelectorAll('.window')].find(n=>n.dataset.viewId==='runtime-package-assets');const i=p?.querySelector('[aria-label=\"我的库名称\"]'),b=[...p?.querySelectorAll('button')||[]].find(n=>n.textContent==='保存库名称');if(!i||!b||b.disabled)return false;i.value="+q(PackageAssetSmokeServer.ALIAS)+";i.dispatchEvent(new Event('input',{bubbles:true}));b.click();return true;");if(PackageAssetSmokeServer.aliasSaved)phase=7;}
            case 7->{action("after-alias","const p=[...document.querySelectorAll('.window')].find(n=>n.dataset.viewId==='runtime-package-assets');const b=[...p?.querySelectorAll('button')||[]].find(n=>n.textContent==='刷新当前管理页');if(!b||b.disabled)return false;b.click();return true;");if(text("复制为独立包"))phase=8;}
            case 8->{action("local-copy","const p=[...document.querySelectorAll('.window')].find(n=>n.dataset.viewId==='runtime-package-assets');const s=[...p?.querySelectorAll('section')||[]].find(n=>n.querySelector('h4')?.textContent==='复制为独立包'),i=s?.querySelector('[aria-label=\"副本名称\"]'),q=s?.querySelector('input[type=checkbox]'),b=[...s?.querySelectorAll('button')||[]].find(n=>n.textContent==='创建当前世界的独立副本');if(!i||!q||!b||b.disabled)return false;i.value="+q(PackageAssetSmokeServer.LOCAL_COPY)+";i.dispatchEvent(new Event('input',{bubbles:true}));q.checked=true;b.click();return true;");if(PackageAssetSmokeServer.sameWorldCopy!=null)phase=9;}
            case 9->{action("copy-catalog","const b=document.querySelector('#open-package-catalog');if(!b)return false;b.click();return true;");if(window("runtime-package-catalog"))phase=10;}
            case 10->{action("copy-assets",catalog(PackageAssetSmokeServer.LOCAL_COPY,"库名称 / 复制 / 保存资产"));if(window("runtime-package-assets")&&text(PackageAssetSmokeServer.LOCAL_COPY)&&text("保存准确版本供跨世界复用"))phase=11;}
            case 11->{action("save-asset","const p=[...document.querySelectorAll('.window')].find(n=>n.dataset.viewId==='runtime-package-assets');const s=[...p?.querySelectorAll('section')||[]].find(n=>n.querySelector('h4')?.textContent==='保存准确版本供跨世界复用'),q=s?.querySelector('input[type=checkbox]'),b=[...s?.querySelectorAll('button')||[]].find(n=>n.textContent==='加入我的跨世界资产');if(!q||!b||b.disabled)return false;q.checked=true;b.click();return true;");}
            default->{}
        }
    }
    private static void reuse(){
        switch(phase){
            case 0->{if(!PackageAssetSmokeServer.ready)return;action("assets-open","const b=document.querySelector('#open-package-assets');if(!b)return false;b.click();return true;");if(window("runtime-package-assets")&&text("我的跨世界资产"))phase=1;}
            case 1->{action("reuse-copy","const p=[...document.querySelectorAll('.window')].find(n=>n.dataset.viewId==='runtime-package-assets');const c=[...p?.querySelectorAll('article')||[]].find(n=>n.querySelector('strong')?.textContent.startsWith("+q(PackageAssetSmokeServer.LOCAL_COPY)+")),i=c?.querySelector('[aria-label=\"复用副本名称\"]'),q=c?.querySelector('input[type=checkbox]'),b=[...c?.querySelectorAll('button')||[]].find(n=>n.textContent==='在当前世界复用此版本');if(!i||!q||!b||b.disabled)return false;i.value="+q(PackageAssetSmokeServer.CROSS_COPY)+";i.dispatchEvent(new Event('input',{bubbles:true}));q.checked=true;b.click();return true;");if(PackageAssetSmokeServer.crossWorldCopy!=null)phase=2;}
            case 2->{action("reuse-tools","const p=[...document.querySelectorAll('.window')].find(n=>n.dataset.viewId==='runtime-package-assets');const b=[...p?.querySelectorAll('button')||[]].find(n=>n.textContent==='打开此包现有操作 / 预览');if(!b||b.disabled)return false;b.click();return true;");if(window("runtime-package-tools"))phase=3;}
            case 3->{action("reuse-world",packageTools(PackageAssetSmokeServer.crossWorldCopy));if(windowPrefix("world-"+PackageAssetSmokeServer.crossWorldCopy+"-r"))phase=4;}
            case 4->{action("reuse-activate",activate(PackageAssetSmokeServer.crossWorldCopy,700,true));if(PackageAssetSmokeServer.crossInstance!=null)phase=5;}
            case 5->{action("asset-list-again","const b=document.querySelector('#open-package-assets');if(!b)return false;b.click();return true;");if(text("撤回保存版本"))phase=6;}
            case 6->{action("withdraw","const p=[...document.querySelectorAll('.window')].find(n=>n.dataset.viewId==='runtime-package-assets');const c=[...p?.querySelectorAll('article')||[]].find(n=>n.querySelector('strong')?.textContent.startsWith("+q(PackageAssetSmokeServer.LOCAL_COPY)+")),q=[...c?.querySelectorAll('input[type=checkbox]')||[]].at(-1),b=[...c?.querySelectorAll('button')||[]].find(n=>n.textContent==='撤回保存版本');if(!q||!b||b.disabled)return false;q.checked=true;b.click();return true;");if(PackageAssetSmokeServer.withdrawn)phase=7;}
            case 7->{action("withdraw-refresh","const p=[...document.querySelectorAll('.window')].find(n=>n.dataset.viewId==='runtime-package-assets');const b=[...p?.querySelectorAll('button')||[]].find(n=>n.textContent==='刷新当前管理页');if(!b||b.disabled)return false;b.click();return true;");if(text("查看已撤回资产"))phase=8;}
            case 8->{action("show-withdrawn","const p=[...document.querySelectorAll('.window')].find(n=>n.dataset.viewId==='runtime-package-assets');const b=[...p?.querySelectorAll('button')||[]].find(n=>n.textContent==='查看已撤回资产');if(!b||b.disabled)return false;b.click();return true;");if(text("恢复保存版本"))phase=9;}
            case 9->{action("restore","const p=[...document.querySelectorAll('.window')].find(n=>n.dataset.viewId==='runtime-package-assets');const c=[...p?.querySelectorAll('article')||[]].find(n=>n.querySelector('strong')?.textContent.startsWith("+q(PackageAssetSmokeServer.LOCAL_COPY)+")),q=c?.querySelector('input[type=checkbox]'),b=[...c?.querySelectorAll('button')||[]].find(n=>n.textContent==='恢复保存版本');if(!q||!b||b.disabled)return false;q.checked=true;b.click();return true;");}
            default->{}
        }
    }
    @SubscribeEvent public static void tick(ClientTickEvent.Post event)throws Exception{
        if(!PackageAssetSmokeServer.enabled()||finished)return;var mc=Minecraft.getInstance();ticks++;Files.createDirectories(root());var host=WebGuiHostAdapter.INSTANCE;
        if(ticks%100==0){var progress=new LinkedHashMap<String,Object>();progress.put("stage",PackageAssetSmokeServer.stage());progress.put("phase",phase);progress.put("ticks",ticks);progress.put("ready",PackageAssetSmokeServer.ready);progress.put("sourceActive",PackageAssetSmokeServer.sourceActive);progress.put("aliasSaved",PackageAssetSmokeServer.aliasSaved);progress.put("sameWorldCopy",Objects.toString(PackageAssetSmokeServer.sameWorldCopy,""));progress.put("shelfSaved",PackageAssetSmokeServer.shelfSaved);progress.put("crossWorldCopy",Objects.toString(PackageAssetSmokeServer.crossWorldCopy,""));progress.put("crossInstance",Objects.toString(PackageAssetSmokeServer.crossInstance,""));progress.put("withdrawn",PackageAssetSmokeServer.withdrawn);progress.put("restored",PackageAssetSmokeServer.restored);progress.put("probe",probe==null?new JsonObject():probe);Files.writeString(root().resolve("progress.json"),JSON.toJson(progress));}
        if(!PackageAssetSmokeServer.failure.isEmpty()||ticks>12000){finished=true;Files.writeString(root().resolve("failure.json"),JSON.toJson(Map.of("stage",PackageAssetSmokeServer.stage(),"phase",phase,"error",PackageAssetSmokeServer.failure,"probe",probe==null?new JsonObject():probe)));host.close();mc.stop();throw new IllegalStateException("PACKAGE_ASSET_NATIVE_FAILED");}
        if(PackageAssetSmokeServer.done){if(PackageAssetSmokeServer.stage().equals("verify")||probe!=null)finish();return;}
        if(mc.player!=null&&!opened){opened=true;host.open();}
        if(!host.ready()||UiClientSessions.current()==null||ticks%10!=0)return;probe();if(PackageAssetSmokeServer.stage().equals("prepare"))prepare();else if(PackageAssetSmokeServer.stage().equals("reuse"))reuse();
    }
}
