package dev.mineagent.runtime.neoforge.client.webui;
import com.google.gson.*;
import dev.mineagent.runtime.neoforge.ui.WorldUiRepairSmokeServer;
import net.minecraft.client.Minecraft;
import java.nio.file.*;
/** Drives existing trusted patch controls. Only external exact-SHA review flags permit application. */
public final class WorldUiRepairSmokeClient {
    private static final Gson JSON=new Gson();private static int ticks;
    private static String q(Object value){return JSON.toJson(value);}
    private static void main(String code){var h=WebGuiHostAdapter.INSTANCE;h.browser().executeJavaScript("(()=>{"+code+"})();",h.browser().getURL(),0);}
    public static void accept(JsonObject value){try{Path root=Minecraft.getInstance().gameDirectory.toPath().resolve(WorldUiRepairSmokeServer.directory());Files.createDirectories(root);Files.writeString(root.resolve("repair-gui.json"),JSON.toJson(value));}catch(Exception e){throw new IllegalStateException(e);}}
    public static void step(){
        if(++ticks%10!=0||WorldUiRepairSmokeServer.packageId==null)return;
        main("""
            if(!document.querySelector('#generation-prompt'))document.querySelector('#open-generation').click();
            if(WORLD_SUBMIT&&!window.__repairWorldSent){const button=document.querySelector('[data-world-patch-package="'+PKG+'"]');if(button){button.click();const input=document.querySelector('#world-patch-prompt');input.value=WORLD_PROMPT;input.dispatchEvent(new Event('input',{bubbles:true}));window.__repairWorldSent=true;document.querySelector('#world-patch-submit').click();}}
            if(WORLD_OP&&WORLD_HASH){const review=document.querySelector('[data-world-patch-review="'+WORLD_OP+'"]');if(review&&!document.querySelector('[data-world-patch-apply="'+WORLD_OP+'"]'))review.click();
              const files=document.querySelector('select[aria-label="世界候选资源"]');if(files&&[...files.options].some(o=>o.value==='server/main.js')&&files.value!=='server/main.js'){files.value='server/main.js';files.dispatchEvent(new Event('change',{bubbles:true}));}
              const apply=document.querySelector('[data-world-patch-apply="'+WORLD_OP+'"]');if(apply&&apply.disabled&&window.__repairRefreshedHash!==WORLD_HASH){const refresh=[...apply.parentElement.querySelectorAll('button')].find(b=>b.textContent==='刷新审查状态');if(refresh){window.__repairRefreshedHash=WORLD_HASH;refresh.click();}}
              if(WORLD_APPLY&&apply&&!apply.disabled&&!window.__repairWorldApplied){window.__repairWorldApplied=true;const consent=apply.parentElement.querySelector('[data-world-patch-consent]');if(!consent.checked)consent.click();apply.click();}
            }
            if(UI_SUBMIT&&!window.__repairUiSent){const button=document.querySelector('[data-patch-package="'+PKG+'"]');if(button){button.click();const input=document.querySelector('#ui-patch-prompt');input.value=UI_PROMPT;input.dispatchEvent(new Event('input',{bubbles:true}));window.__repairUiSent=true;document.querySelector('#ui-patch-submit').click();}}
            if(UI_REBUILD&&UI_OP&&!window.__repairUiRebuildSent){const input=document.querySelector('[data-patch-rebuild-sha="'+UI_OP+'"]');const button=document.querySelector('[data-patch-rebuild="'+UI_OP+'"]');if(input&&button&&!button.disabled){input.closest('details').open=true;input.value=UI_RAW;input.dispatchEvent(new Event('input',{bubbles:true}));const consent=document.querySelector('[data-patch-rebuild-consent="'+UI_OP+'"]');if(!consent.checked)consent.click();window.__repairUiRebuildSent=true;button.click();}}
            if(UI_APPLY&&UI_OP&&!window.__repairUiApplied){const button=document.querySelector('.patch-job[data-operation-id="'+UI_OP+'"] [data-patch-action="patchApply"]');if(button&&!button.disabled){window.__repairUiApplied=true;button.click();}}
            window.mineagentQuery({request:JSON.stringify({channel:'worldUiRepairProbe',worldSubmitted:!!window.__repairWorldSent,worldApplied:!!window.__repairWorldApplied,uiSubmitted:!!window.__repairUiSent,uiRebuildSent:!!window.__repairUiRebuildSent,uiApplied:!!window.__repairUiApplied,text:document.body.innerText.slice(-22000)}),persistent:false,onSuccess(){},onFailure(){}});
            """.replace("WORLD_SUBMIT",q(WorldUiRepairSmokeServer.worldSubmit)).replace("WORLD_PROMPT",q(WorldUiRepairSmokeServer.WORLD_PROMPT)).replace("WORLD_APPLY",q(WorldUiRepairSmokeServer.allowWorldApply)).replace("WORLD_OP",q(WorldUiRepairSmokeServer.worldOperation)).replace("WORLD_HASH",q(WorldUiRepairSmokeServer.worldHash)).replace("UI_SUBMIT",q(WorldUiRepairSmokeServer.uiSubmit)).replace("UI_REBUILD",q(WorldUiRepairSmokeServer.uiRebuild)).replace("UI_RAW",q(WorldUiRepairSmokeServer.uiRaw)).replace("UI_PROMPT",q(WorldUiRepairSmokeServer.UI_PROMPT)).replace("UI_APPLY",q(WorldUiRepairSmokeServer.allowUiApply)).replace("UI_OP",q(WorldUiRepairSmokeServer.uiOperation)).replace("PKG",q(WorldUiRepairSmokeServer.packageId)));
    }
}
