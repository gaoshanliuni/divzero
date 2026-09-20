package dev.mineagent.runtime.neoforge.client.webui;
import com.google.gson.*;
import dev.mineagent.runtime.neoforge.ui.AgentContainerSmokeServer;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import java.nio.file.*;
import java.util.*;
@EventBusSubscriber(modid="mineagent_runtime",value=Dist.CLIENT)
public final class AgentContainerSmokeClient {
    private static final Gson JSON=new Gson();private static boolean opened,backup,finishing,ended;private static int ticks;private static JsonObject probe;private static String failure;
    public static void accept(JsonObject p){probe=p;}
    @SubscribeEvent public static void tick(ClientTickEvent.Post e)throws Exception{
        if(!Boolean.getBoolean("mineagent.agentContainerSmoke")||ended)return;var mc=Minecraft.getInstance();var host=WebGuiHostAdapter.INSTANCE;ticks++;
        if(!backup&&mc.screen instanceof net.minecraft.client.gui.screens.BackupConfirmScreen s){backup=true;var f=s.getClass().getDeclaredField("onProceed");f.setAccessible(true);((net.minecraft.client.gui.screens.BackupConfirmScreen.Listener)f.get(s)).proceed(false,false);}
        if(mc.player!=null&&!opened){opened=true;mc.options.pauseOnLostFocus=false;host.open();}
        if(host.ready()&&UiClientSessions.current()!=null&&ticks%10==0&&AgentContainerSmokeServer.agentId!=null&&!finishing){
            if(AgentContainerSmokeServer.patch()&&!AgentContainerSmokeServer.patchApplied)main("""
                if(!document.querySelector('#generation-prompt'))document.querySelector('#open-generation').click();
                const card=document.querySelector('.generation-job:not(.patch-job)[data-package-id="'+PACKAGE+'"]');if(!card)return;
                if(!window.__containerPatchSubmitted){const open=card.querySelector('[data-patch-package]');if(!open)return;open.click();const input=document.querySelector('#ui-patch-prompt');input.value=PATCH;input.dispatchEvent(new Event('input',{bubbles:true}));window.__containerPatchSubmitted=true;document.querySelector('#ui-patch-submit').click();}
                const job=[...document.querySelectorAll('.patch-job')].find(c=>c.dataset.packageId===PACKAGE&&c.textContent.includes(TOKEN));
                if(job?.dataset.state==='READY'&&!window.__containerPatchApplied){window.__containerPatchApplied=true;job.querySelector('[data-patch-action=patchApply]').click();}
                """.replace("PACKAGE",q(AgentContainerSmokeServer.PACKAGE.toString())).replace("PATCH",q(patchPrompt())).replace("TOKEN",q(AgentContainerSmokeServer.RUN)));
            else main("""
                if(!document.querySelector('#generation-prompt'))document.querySelector('#open-generation').click();
                const card=document.querySelector('.generation-job:not(.patch-job)[data-package-id="'+PACKAGE+'"]');if(!card||Number(card.dataset.packageRevision)!==REVISION)return;
                if(!window.__agentContainerOpened){window.__agentContainerOpened=true;card.querySelector('[data-container-agent-package]').click();}
                const form=document.querySelector('[data-view-id=runtime-container-agent]');if(!form||window.__agentContainerStarted)return;
                const values={agentId:AGENT,goal:GOAL,group:'player',itemId:'minecraft:cobblestone',name:NAME,count:'12'};
                const agent=form.querySelector('[data-container-agent-field=agentId]');if(![...agent.options].some(o=>o.value===AGENT))return;
                for(const [key,value] of Object.entries(values)){const input=form.querySelector('[data-container-agent-field='+key+']');input.value=value;input.dispatchEvent(new Event('input',{bubbles:true}));}
                form.querySelector('[data-container-agent-consent]').click();window.__agentContainerStarted=true;form.querySelector('[data-container-agent-start]').click();
                """.replace("PACKAGE",q(AgentContainerSmokeServer.PACKAGE.toString())).replace("REVISION",Long.toString(AgentContainerSmokeServer.revision)).replace("AGENT",q(AgentContainerSmokeServer.agentId)).replace("NAME",q(AgentContainerSmokeServer.NAME)).replace("GOAL",q("使用当前真实容器网页，将箱子里的全部 12 个名为「"+AgentContainerSmokeServer.NAME+"」的圆石快速移动到你自己的 Agent 背包。可以对实际槽位按钮 click 并设置 shiftKey=true。不要动其他物品。完成后滚动到背包中包含这堆物品的真实槽位，确认其显示且光标为空。")));
            main("const f=document.querySelector('[data-content-kind=CONTAINER] iframe');window.mineagentQuery({request:JSON.stringify({channel:'agentContainerProbe',view:f?.name??'',status:document.querySelector('#status').textContent}),persistent:false,onSuccess(){},onFailure(){}});");
        }
        if(AgentContainerSmokeServer.verified&&!finishing){finishing=true;Files.createDirectories(root());
            if(AgentContainerSmokeServer.far()){Files.writeString(root().resolve("client-result.json"),JSON.toJson(probe));finish();return;}
            String view=probe.get("view").getAsString();PackagePageAgent.inspectManagedView(view).whenComplete((dom,error)->mc.execute(()->{try{
                if(error!=null)throw new IllegalStateException(error);Files.writeString(root().resolve("after-dom.json"),dom);Files.writeString(root().resolve("client-result.json"),JSON.toJson(probe));
                net.minecraft.client.Screenshot.takeScreenshot(mc.getMainRenderTarget(),image->{try(image){image.writeToFile(root().resolve("after-render.png"));finish();}catch(Exception ex){failure=ex.toString();}});
            }catch(Exception ex){failure=ex.toString();}}));
        }
        if(failure!=null||ticks>12000){Files.createDirectories(root());Files.writeString(root().resolve("failure.json"),JSON.toJson(Map.of("error",failure==null?"TIMEOUT":failure,"probe",probe==null?new JsonObject():probe)));ended=true;host.close();mc.stop();throw new IllegalStateException("AGENT_CONTAINER_SMOKE_FAILED "+failure);}
    }
    private static void finish(){ended=true;WebGuiHostAdapter.INSTANCE.close();dev.mineagent.runtime.neoforge.MineAgentRuntimeMod.LOGGER.info("MINEAGENT_AGENT_CONTAINER_CLIENT_OK run={}",AgentContainerSmokeServer.RUN);Minecraft.getInstance().stop();}
    private static Path root(){return Minecraft.getInstance().gameDirectory.toPath().resolve(AgentContainerSmokeServer.directory());}
    private static String q(String s){return JSON.toJson(s);}
    private static void main(String code){var h=WebGuiHostAdapter.INSTANCE;h.browser().executeJavaScript("(()=>{"+code+"})();",h.browser().getURL(),0);}
    private static String patchPrompt(){return AgentContainerSmokeServer.RUN+" 修复同包 container.html，不改变 entrypoints。1 未改变 revision 的轮询不得重建槽位 DOM，确保 elementRef 稳定；2 pointerdown/dragstart 开始拖分时锁定当时 revision，暂停轮询与 DOM 更新，结束/取消释放；3 迟到 read 若跨手势/写请求 epoch 必须丢弃，不能覆盖新状态；4 RPC与手势锁分开，performDrag仍能执行；5 空物品以 count>0 判断，冲突按 e.code 或 e.message 识别，仅读取恢复，不重放操作；6保留左击/Shift快速移动/HTML5拖分与data-slot-index实际槽位、半透明样式，不自动移动物品。";}
}
