package dev.mineagent.runtime.neoforge.boot;

import com.google.gson.*;
import dev.mineagent.runtime.neoforge.client.webui.*;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.nio.file.*;
import java.util.*;

/** Actual dependency-aware BOOT management through the production CEF page. */
@EventBusSubscriber(modid = "mineagent_runtime", value = Dist.CLIENT)
public final class BootDependencySmokeClient {
    private static final Gson JSON = new Gson();
    private static JsonObject probe;
    private static int ticks, phase;
    private static boolean backup, trusted, trustPressed, opened, finished, glass;

    private BootDependencySmokeClient() {}
    public static void accept(JsonObject value) {
        if (!BootDependencySmokeServer.enabled()) return;probe = value;
        glass |= text("cardBackground").startsWith("rgba(");
    }
    private static Path root() { return Minecraft.getInstance().gameDirectory.toPath().resolve("boot-dependency-smoke"); }
    private static void write(String name,Object value)throws Exception{Files.createDirectories(root());Files.writeString(root().resolve(name+".json"),JSON.toJson(value));}
    private static void require(boolean value,String code){if(!value)throw new IllegalStateException(code);}
    private static String text(String key){return probe==null||!probe.has(key)?"":probe.get(key).getAsString();}
    private static void script(String source){var host=WebGuiHostAdapter.INSTANCE;host.browser().executeJavaScript("(()=>{"+source+"})();",host.browser().getURL(),0);}
    private static void action(String key,String source){script("window.__bootDependencySmoke??={};if(window.__bootDependencySmoke["+JSON.toJson(key)+"])return;const ok=(()=>{"+source+"})();if(ok)window.__bootDependencySmoke["+JSON.toJson(key)+"]=true;");}
    private static void poll(){script("window.dispatchEvent(new Event('mineagent:boot-upgrade-probe'));");}
    private static String catalogButton(String name,String label){return "const p=[...document.querySelectorAll('.window')].find(n=>n.dataset.viewId==='runtime-package-catalog');const c=[...p?.querySelectorAll('article')||[]].find(n=>n.querySelector('strong')?.textContent==="+JSON.toJson(name)+");const b=[...c?.querySelectorAll('button')||[]].find(n=>n.textContent==="+JSON.toJson(label)+");if(!b)return false;b.click();return true;";}
    private static String bootButton(String name,String label){return "const p=[...document.querySelectorAll('.window')].find(n=>n.dataset.viewId==='runtime-boot-extensions');const c=[...p?.querySelectorAll('article')||[]].find(n=>n.querySelector('strong')?.textContent.startsWith("+JSON.toJson(name)+")&&[...n.querySelectorAll('button')].some(b=>b.textContent==="+JSON.toJson(label)+"));const q=c?.querySelector('input[type=checkbox]');const b=[...c?.querySelectorAll('button')||[]].find(n=>n.textContent==="+JSON.toJson(label)+");if(!q||!b||b.disabled)return false;q.checked=true;b.click();return true;";}
    private static void finish(String status,Map<String,Object> extra)throws Exception{var values=new LinkedHashMap<String,Object>(extra);values.put("status",status);values.put("stage",BootDependencySmokeServer.stage());values.put("glass",glass);values.put("providerCalls",0);values.put("systemInputInjected",false);values.put("fullV1",false);write("client-"+BootDependencySmokeServer.stage(),values);finished=true;dev.mineagent.runtime.neoforge.MineAgentRuntimeMod.LOGGER.info("MINEAGENT_BOOT_DEPENDENCY_{}_OK",BootDependencySmokeServer.stage().toUpperCase(Locale.ROOT));WebGuiHostAdapter.INSTANCE.close();Minecraft.getInstance().stop();}
    private static void fail(Throwable failure)throws Exception{finished=true;write("client-failure-"+BootDependencySmokeServer.stage(),Map.of("stage",BootDependencySmokeServer.stage(),"phase",phase,"ticks",ticks,"error",failure.toString(),"serverFailure",Objects.toString(BootDependencySmokeServer.failure,""),"probe",probe==null?"{}":probe.toString()));WebGuiHostAdapter.INSTANCE.close();Minecraft.getInstance().stop();}

    private static void prepare(String catalog,String boot)throws Exception{
        switch(phase){
            case 0->{action("catalog-dep","document.querySelector('#open-package-catalog')?.click();return true;");if(catalog.contains(BootDependencySmokeServer.DEP_NAME)&&catalog.contains(BootDependencySmokeServer.CONSUMER_NAME))phase=1;}
            case 1->{action("open-dep",catalogButton(BootDependencySmokeServer.DEP_NAME,"构建 / 安装启动扩展"));if(boot.contains("待构建："+BootDependencySmokeServer.DEP_NAME))phase=2;}
            case 2->{action("build-dep","const p=[...document.querySelectorAll('.window')].find(n=>n.dataset.viewId==='runtime-boot-extensions');const b=[...p?.querySelectorAll('button')||[]].find(n=>n.textContent==='构建此 BOOT_EXTENSION');if(!b||b.disabled)return false;b.click();return true;");if((boot.split(BootDependencySmokeServer.DEP_NAME,-1).length-1)>=2&&boot.contains(" · BUILT · "))phase=3;}
            case 3->{action("install-dep",bootButton(BootDependencySmokeServer.DEP_NAME,"全局安装，下一次重启加载"));if(boot.contains(BootDependencySmokeServer.DEP_NAME)&&boot.contains("INSTALLED_PENDING_RESTART")&&boot.contains("文件 HASH_MATCHED"))phase=4;}
            case 4->{action("catalog-consumer","document.querySelector('#open-package-catalog')?.click();return true;");if(catalog.contains(BootDependencySmokeServer.CONSUMER_NAME))phase=5;}
            case 5->{action("open-consumer",catalogButton(BootDependencySmokeServer.CONSUMER_NAME,"构建 / 安装启动扩展"));if(boot.contains("待构建："+BootDependencySmokeServer.CONSUMER_NAME))phase=6;}
            case 6->{action("build-consumer","const p=[...document.querySelectorAll('.window')].find(n=>n.dataset.viewId==='runtime-boot-extensions');const b=[...p?.querySelectorAll('button')||[]].find(n=>n.textContent==='构建此 BOOT_EXTENSION');if(!b||b.disabled)return false;b.click();return true;");if((boot.split(BootDependencySmokeServer.CONSUMER_NAME,-1).length-1)>=2&&boot.contains(" · BUILT · ")&&boot.contains("1.0.0"))phase=7;}
            case 7->{action("install-consumer",bootButton(BootDependencySmokeServer.CONSUMER_NAME,"全局安装，下一次重启加载"));if((boot.split("INSTALLED_PENDING_RESTART",-1).length-1)>=2&&(boot.split("文件 HASH_MATCHED",-1).length-1)>=2)phase=8;}
            case 8->{action("catalog-dep-remove","document.querySelector('#open-package-catalog')?.click();return true;");if(catalog.contains(BootDependencySmokeServer.DEP_NAME))phase=9;}
            case 9->{action("open-dep-remove",catalogButton(BootDependencySmokeServer.DEP_NAME,"构建 / 安装启动扩展"));if(boot.contains("待构建："+BootDependencySmokeServer.DEP_NAME)&&boot.contains(BootDependencySmokeServer.CONSUMER_NAME))phase=10;}
            case 10->{action("reject-dep-remove-prepare",bootButton(BootDependencySmokeServer.DEP_NAME,"全局移出，下次重启停用"));if(boot.contains("BOOT_DEPENDENCY_REQUIRED_BY_INSTALLED")&&boot.contains(BootDependencySmokeServer.DEP_NAME)&&boot.contains("FILE_STATE_UNKNOWN")&&boot.contains("文件 HASH_MATCHED")){require(glass,"BOOT_DEPENDENCY_GLASS");finish("REAL_BOOT_DEPENDENCY_BUILD_INSTALL_REVERSE_GUARD_VERIFIED",Map.of("dependency",BootDependencySmokeServer.dependencyPackage,"consumer",BootDependencySmokeServer.consumerPackage,"dependencyRemovalRejected",true));}}
        }
    }

    private static void verify(String boot)throws Exception{
        switch(phase){
            case 0->{action("verify-open","document.querySelector('#open-boot-extensions')?.click();return true;");phase=1;}
            case 1->{int loaded=boot.split("LOADER_CONSTRUCTOR_RETURNED",-1).length-1;if(loaded>=2&&boot.contains(BootDependencySmokeServer.DEP_NAME)&&boot.contains(BootDependencySmokeServer.CONSUMER_NAME)&&boot.contains("BOOT_DEPENDENCY_REQUIRED_BY_INSTALLED")){require(glass,"BOOT_DEPENDENCY_GLASS");finish("REAL_BOOT_DEPENDENCY_LOADER_CALL_AND_REVERSE_GUARD_VERIFIED",Map.of("dependencyMarker",Files.readString(Minecraft.getInstance().gameDirectory.toPath().resolve("boot-dependency-api.txt")),"consumerMarker",Files.readString(Minecraft.getInstance().gameDirectory.toPath().resolve("boot-dependency-consumer.txt")),"offlineRemovalRequired",true));}}
        }
    }

    private static void removed(String boot)throws Exception{
        if(phase==0){action("removed-open","document.querySelector('#open-boot-extensions')?.click();return true;");phase=1;}
        if(phase==1&&BootDependencySmokeServer.ready&&boot.contains(BootDependencySmokeServer.DEP_NAME)&&boot.contains(BootDependencySmokeServer.CONSUMER_NAME)&&(boot.split("REMOVED_PENDING_RESTART",-1).length-1)>=2&&(boot.split("OFFLINE_DISABLED_HASH_MATCHED",-1).length-1)>=2&&(boot.split("NOT_LOADED",-1).length-1)>=2){require(glass,"BOOT_DEPENDENCY_GLASS");finish("REAL_BOOT_DEPENDENCY_NEXT_JVM_REMOVAL_VERIFIED",Map.of("dependencyLoaded",false,"consumerLoaded",false,"markersNotReplayed",true,"offlineRemovalReadBack",true));}
    }

    @SubscribeEvent public static void tick(ClientTickEvent.Post event)throws Exception{
        if(!BootDependencySmokeServer.enabled()||finished)return;var mc=Minecraft.getInstance();ticks++;try{
            if(ticks>20000)throw new IllegalStateException("BOOT_DEPENDENCY_CEF_TIMEOUT_"+BootDependencySmokeServer.stage()+"_"+phase);if(BootDependencySmokeServer.failure!=null)throw new IllegalStateException(BootDependencySmokeServer.failure);
            if(!backup&&mc.screen instanceof net.minecraft.client.gui.screens.BackupConfirmScreen screen){backup=true;var field=screen.getClass().getDeclaredField("onProceed");field.setAccessible(true);((net.minecraft.client.gui.screens.BackupConfirmScreen.Listener)field.get(screen)).proceed(false,false);}
            if(!trusted&&mc.player!=null){if(ticks%20==0)net.neoforged.neoforge.client.network.ClientPacketDistributor.sendToServer(new dev.mineagent.runtime.neoforge.network.MineAgentPayloads.PanelRequest());if(mc.screen instanceof dev.mineagent.runtime.neoforge.client.screen.ControlCenterScreen screen&&!trustPressed)for(var child:screen.children())if(child instanceof net.minecraft.client.gui.components.Button button&&button.active&&button.getMessage().getString().equals("信任此服务器")){button.onPress(new net.minecraft.client.input.InputWithModifiers(){public int input(){return org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER;}public int modifiers(){return 0;}});trustPressed=true;net.neoforged.neoforge.client.network.ClientPacketDistributor.sendToServer(new dev.mineagent.runtime.neoforge.network.MineAgentPayloads.PanelRequest());break;}var snapshot=dev.mineagent.runtime.neoforge.network.PanelSnapshotInbox.snapshot();String fingerprint=snapshot.values().getOrDefault("security.identityFingerprint","");var status=new dev.mineagent.runtime.client.trust.ServerTrustStore(mc.gameDirectory.toPath().resolve("config/mineagent-trusted-servers.properties")).status("local-integrated",fingerprint);if(status==dev.mineagent.runtime.client.trust.TrustStatus.TRUSTED&&(trustPressed||!BootDependencySmokeServer.stage().equals("prepare"))){trusted=true;write("trust-"+BootDependencySmokeServer.stage(),Map.of("actualNativeTrustButton",trustPressed,"storedTrust",!trustPressed,"fingerprint",fingerprint,"systemInputInjected",false));mc.setScreen(null);}else return;}
            if(!BootDependencySmokeServer.ready||BootDependencySmokeServer.dependencyPackage==null||BootDependencySmokeServer.consumerPackage==null)return;var host=WebGuiHostAdapter.INSTANCE;if(!opened){opened=true;host.open();}if(!host.ready()||UiClientSessions.current()==null||ticks%10!=0)return;poll();String catalog=text("catalogText"),boot=text("bootText");if(ticks%100==0)write("progress-"+BootDependencySmokeServer.stage(),Map.of("stage",BootDependencySmokeServer.stage(),"phase",phase,"ticks",ticks,"catalog",catalog,"boot",boot,"serverFailure",Objects.toString(BootDependencySmokeServer.failure,"")));switch(BootDependencySmokeServer.stage()){case "prepare"->prepare(catalog,boot);case "verify"->verify(boot);case "removed"->removed(boot);default->throw new IllegalArgumentException("BOOT_DEPENDENCY_STAGE");}
        }catch(Exception failure){fail(failure);throw failure;}
    }
}
