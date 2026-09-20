package dev.mineagent.runtime.neoforge.client.webui;
import com.google.gson.*;
import dev.mineagent.runtime.neoforge.ui.MultiWindowInputSmokeServer;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import java.nio.file.*;
import java.util.*;
/** Two real model tasks; native characters in A must not cancel B or be redirected by B's DOM fill. */
@EventBusSubscriber(modid="mineagent_runtime",value=Dist.CLIENT)
public final class MultiWindowInputSmokeClient {
    private static int ticks,focusAt=-1;private static boolean opened,backup,typed,observing,done,ended;private static volatile boolean captured;
    private static JsonObject probe;
    @SubscribeEvent public static void tick(ClientTickEvent.Post event)throws Exception{
        if(!Boolean.getBoolean("mineagent.multiWindowInputSmoke"))return;var mc=Minecraft.getInstance();ticks++;
        if(!backup&&mc.screen instanceof net.minecraft.client.gui.screens.BackupConfirmScreen s){backup=true;var f=s.getClass().getDeclaredField("onProceed");f.setAccessible(true);((net.minecraft.client.gui.screens.BackupConfirmScreen.Listener)f.get(s)).proceed(false,false);}
        var host=WebGuiHostAdapter.INSTANCE;if(mc.player!=null&&!opened){opened=true;host.open();}
        if(host.ready()&&UiClientSessions.current()!=null&&ticks%2==0&&!typed){
            String script="""
              (()=>{
                if(!document.querySelector('#generation-prompt'))document.querySelector('#open-generation').click();
                const card=[...document.querySelectorAll('.generation-job')].find(c=>c.dataset.agentId===AGENT_A&&c.querySelector('strong').textContent.startsWith('PUBLISHED'));if(!card)return;
                const select=card.querySelector('select[aria-label="已有计分目标"]');if(!select)return;
                function bind(source){select.value=source;select.dispatchEvent(new Event('change',{bubbles:true}));[...card.querySelectorAll('button')].find(b=>b.textContent==='创建独立绑定视图').click();}
                const openA=card.querySelector('button[data-source-id="'+SOURCE_A+'"]'),openB=card.querySelector('button[data-source-id="'+SOURCE_B+'"]');
                if(!openA){if(!window.__bindA){window.__bindA=true;bind(SOURCE_A);}return;}
                if(!openB){if(!window.__bindB){window.__bindB=true;bind(SOURCE_B);}return;}
                const a=document.querySelector('[data-target-object-id="'+openA.dataset.targetViewId+'"]'),b=document.querySelector('[data-target-object-id="'+openB.dataset.targetViewId+'"]');
                if(!a){if(!window.__openA){window.__openA=true;openA.click();}return;}
                if(!b){if(!window.__openB){window.__openB=true;openB.click();}return;}
                if(!window.__approveA&&(a.querySelector('[data-action="open-delegation"]').disabled||b.querySelector('[data-action="open-delegation"]').disabled))return;
                b.style.transform='translateX(550px)';
                function approve(node,agent,title){
                  node.querySelector('[data-action="open-delegation"]').click();
                  const form=document.querySelector('[data-delegation-view="'+node.querySelector('iframe').name+'"]');
                  const who=form.querySelector('[data-field="agent"]');who.value=agent;who.dispatchEvent(new Event('change',{bubbles:true}));
                  const goal=form.querySelector('[data-field="goal"]');goal.value='请在真实网页输入并保存标题为「'+title+'」，不改变分数。';goal.dispatchEvent(new Event('input',{bubbles:true}));
                  const expected=form.querySelector('[data-field="expectedTitle"]');expected.value=title;expected.dispatchEvent(new Event('input',{bubbles:true}));
                  form.querySelector('[data-field="consent"]').click();form.querySelector('[data-action="delegate"]').click();
                }
                if(!window.__approveA){window.__approveA=true;approve(a,AGENT_A,'应中断的排行榜 A');if(EARLY_HIDE)a.querySelector('button[aria-label="收起"]').click();}
                if((a.dataset.agentStatus==='RUNNING'||EARLY_HIDE)&&!window.__approveB){window.__approveB=true;approve(b,AGENT_B,'并行排行榜 B');}
                const r=a.querySelector('iframe').getBoundingClientRect();
                window.mineagentQuery({request:JSON.stringify({channel:'multiInputProbe',viewA:a.querySelector('iframe').name,viewB:b.querySelector('iframe').name,pointX:r.x+r.width/2,pointY:r.y+r.height/2,cssWidth:innerWidth,cssHeight:innerHeight,
                  statusA:a.dataset.agentStatus??'',statusB:b.dataset.agentStatus??'',status:document.querySelector('#status').textContent}),persistent:false,onSuccess(){},onFailure(){}});
              })();
              """.replace("AGENT_A",new Gson().toJson(MultiWindowInputSmokeServer.agentA)).replace("AGENT_B",new Gson().toJson(MultiWindowInputSmokeServer.agentB))
                    .replace("SOURCE_A",new Gson().toJson(MultiWindowInputSmokeServer.sourceA)).replace("SOURCE_B",new Gson().toJson(MultiWindowInputSmokeServer.sourceB))
                    .replace("EARLY_HIDE",Boolean.toString(MultiWindowInputSmokeServer.earlyHide()));
            host.browser().executeJavaScript(script,host.browser().getURL(),0);
        }
        if(probe!=null&&MultiWindowInputSmokeServer.earlyHide()&&probe.get("statusB").getAsString().equals("RUNNING"))typed=true;
        if(!MultiWindowInputSmokeServer.earlyHide()&&probe!=null&&focusAt<0&&probe.get("statusA").getAsString().equals("RUNNING")&&probe.get("statusB").getAsString().equals("RUNNING")){
            focusAt=Integer.MAX_VALUE;
            if(Boolean.getBoolean("mineagent.multiWindowPointer")){
                var screen=(WebGuiInteractionScreen)mc.screen;
                var button=new net.minecraft.client.input.MouseButtonEvent(probe.get("pointX").getAsDouble()*screen.width/probe.get("cssWidth").getAsDouble(),
                        probe.get("pointY").getAsDouble()*screen.height/probe.get("cssHeight").getAsDouble(),new net.minecraft.client.input.MouseButtonInfo(0,0));
                screen.mouseClicked(button,false);screen.mouseReleased(button);
            }
            WebGuiNativeInput.afterInputs(()->{
                String url=host.packageUrl(probe.get("viewA").getAsString());for(long id:host.browser().getFrameIdentifiers()){var f=host.browser().getFrame(id);if(f!=null&&!f.isMain()&&url.equals(f.getURL()))f.executeJavaScript("const input=document.querySelector('[data-ai-id=\"title-input\"]');input.focus();input.select();",url,0);}focusAt=ticks;
            });
        }
        if(focusAt>=0&&!typed&&ticks-focusAt>=2){typed=true;if(!(mc.screen instanceof WebGuiInteractionScreen screen))throw new IllegalStateException("INPUT_SCREEN_MISSING");for(int cp:"人工草稿".codePoints().toArray())screen.charTyped(new net.minecraft.client.input.CharacterEvent(cp));}
        if(MultiWindowInputSmokeServer.verified&&typed&&!observing){
            observing=true;var root=mc.gameDirectory.toPath().resolve(MultiWindowInputSmokeServer.evidenceDirectory());Files.createDirectories(root);
            var observeA=MultiWindowInputSmokeServer.earlyHide()?java.util.concurrent.CompletableFuture.completedFuture("{\"elements\":[],\"status\":\"HIDDEN_NOT_OBSERVED\"}"):PackagePageAgent.inspectManagedView(probe.get("viewA").getAsString());
            observeA.thenCompose(a->PackagePageAgent.inspectManagedView(probe.get("viewB").getAsString()).thenApply(b->Map.of("a",a,"b",b)))
                    .whenComplete((data,error)->mc.execute(()->{try{
                        if(error!=null)throw new IllegalStateException("MULTI_WINDOW_OBSERVE_FAILED",error);
                        var a=JsonParser.parseString(data.get("a")).getAsJsonObject();boolean draft=false;for(var e:a.getAsJsonArray("elements")){var n=e.getAsJsonObject();if(n.get("dataAiId").getAsString().equals("title-input")&&n.get("value").getAsString().equals("人工草稿"))draft=true;}
                        if((!MultiWindowInputSmokeServer.earlyHide()&&!draft)||!data.get("b").contains("并行排行榜 B"))throw new IllegalStateException("MULTI_WINDOW_DRAFT_OR_RESULT");
                        Files.writeString(root.resolve("dom-a.json"),data.get("a"));Files.writeString(root.resolve("dom-b.json"),data.get("b"));Files.writeString(root.resolve("client.json"),probe.toString());done=true;
                    }catch(Exception failure){throw new IllegalStateException(failure);}}));
        }
        if(done&&!captured&&!ended){ended=true;net.minecraft.client.Screenshot.takeScreenshot(mc.getMainRenderTarget(),image->{try(image){image.writeToFile(mc.gameDirectory.toPath().resolve(MultiWindowInputSmokeServer.evidenceDirectory()).resolve("render.png"));captured=true;}catch(Exception e){throw new IllegalStateException(e);}});}
        if(captured){host.close();dev.mineagent.runtime.neoforge.MineAgentRuntimeMod.LOGGER.info(MultiWindowInputSmokeServer.earlyHide()?"MINEAGENT_MULTI_WINDOW_EARLY_HIDE_OK hiddenA=true modelCallsA=0 completedB=true":"MINEAGENT_MULTI_WINDOW_INPUT_GRAPHICAL_OK cancelledA=true completedB=true nativeDraftPreserved=true");mc.stop();captured=false;}
        if(ticks>7200)throw new IllegalStateException("MULTI_WINDOW_INPUT_TIMEOUT: "+(probe==null?host.diagnostic():probe.toString()));
    }
    static void accept(JsonObject value){probe=value;try{var root=Minecraft.getInstance().gameDirectory.toPath().resolve(MultiWindowInputSmokeServer.evidenceDirectory());Files.createDirectories(root);Files.writeString(root.resolve("last-shell.json"),value.toString());}catch(Exception ignored){}}
}
