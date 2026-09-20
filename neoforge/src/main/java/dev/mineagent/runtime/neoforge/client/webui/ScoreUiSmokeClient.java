package dev.mineagent.runtime.neoforge.client.webui;

import com.google.gson.*;
import dev.mineagent.runtime.neoforge.ui.ScoreUiSmokeServer;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import java.nio.file.*;

/** Actual DOM test driver, not an AI planner. It never calls score.patch directly. */
@EventBusSubscriber(modid="mineagent_runtime",value=Dist.CLIENT)
public final class ScoreUiSmokeClient {
    private static int ticks;
    private static boolean opened,backup,editing,inspecting,screenshot,ended;
    private static volatile boolean captured;
    private static JsonObject probe;
    private static String before,after,error;
    private static int phase,nextInspectTick;
    private static String reloadDocument;
    private static int focusAt=-1,interruptAt=-1;
    @SubscribeEvent public static void tick(ClientTickEvent.Post event) throws Exception {
        if(!Boolean.getBoolean("mineagent.scoreUiSmoke"))return;
        var mc=Minecraft.getInstance();ticks++;
        if(!backup&&mc.screen instanceof net.minecraft.client.gui.screens.BackupConfirmScreen screen){
            backup=true;var field=screen.getClass().getDeclaredField("onProceed");field.setAccessible(true);
            ((net.minecraft.client.gui.screens.BackupConfirmScreen.Listener)field.get(screen)).proceed(false,false);
        }
        var host=WebGuiHostAdapter.INSTANCE;
        if(mc.player!=null&&!opened){opened=true;host.open();}
        if(host.ready()&&UiClientSessions.current()!=null&&ticks%20==0){
            host.browser().executeJavaScript("""
              (()=>{
                if(!document.querySelector('#generation-prompt'))document.querySelector('#open-generation').click();
                const agent=document.querySelector('#generation-agent');const option=[...agent.options].find(o=>o.value===AGENT);if(!option)return;
                if(!window.__scoreSubmitted&&!RESUME){
                  window.__scoreSubmitted=true;agent.value=option.value;agent.dispatchEvent(new Event('change',{bubbles:true}));
                  const input=document.querySelector('#generation-prompt');
                  Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype,'value').set.call(input,
                    PAGE_REQUEST);
                  input.dispatchEvent(new InputEvent('input',{bubbles:true}));document.querySelector('#generation-submit').click();
                }
                const card=[...document.querySelectorAll('.generation-job')].find(j=>j.dataset.agentId===AGENT&&j.querySelector('strong').textContent.startsWith('PUBLISHED'));
                if(!card)return;
                const target=card.querySelector('select[aria-label="已有计分目标"]');
                if(!window.__scoreBound&&target&&[...target.options].some(o=>o.value===SOURCE)){
                  window.__scoreBound=true;target.value=SOURCE;target.dispatchEvent(new Event('change',{bubbles:true}));
                  [...card.querySelectorAll('button')].find(b=>b.textContent==='创建独立绑定视图').click();
                }
                const open=card.querySelector('button[data-source-id="'+SOURCE+'"]');
                if(open&&!window.__scoreOpened){window.__scoreOpened=true;open.click();}
                const frame=document.querySelector('[data-mode="CONTENT"] iframe');
                window.mineagentQuery({request:JSON.stringify({channel:'scoreUiProbe',viewId:frame?.name??'',agentStatus:frame?.closest('.window').dataset.agentStatus??'',status:document.querySelector('#status').textContent,
                  layout:[...document.querySelectorAll('.window,#dock,#minimized')].map(n=>({id:n.dataset.viewId||n.id,display:getComputedStyle(n).display,z:getComputedStyle(n).zIndex,rect:n.getBoundingClientRect().toJSON()}))}),persistent:false,onSuccess(){},onFailure(){}});
              })();
              """.replace("PAGE_REQUEST",new Gson().toJson(pageRequest())).replace("AGENT",new Gson().toJson(ScoreUiSmokeServer.agentId)).replace("SOURCE",new Gson().toJson(ScoreUiSmokeServer.sourceId))
                    .replace("RESUME",Boolean.toString(!System.getProperty("mineagent.scoreUiResume","").isBlank())),host.browser().getURL(),0);
        }
        if(ScoreUiSmokeServer.interruptMode()&&editing&&probe!=null&&probe.get("agentStatus").getAsString().equals("RUNNING")&&focusAt<0){
            String url=host.packageUrl(probe.get("viewId").getAsString());
            for(long id:host.browser().getFrameIdentifiers()){var frame=host.browser().getFrame(id);if(frame!=null&&!frame.isMain()&&url.equals(frame.getURL()))
                frame.executeJavaScript("const input=document.querySelector('[data-ai-id=\"title-input\"]');input.focus();input.select();",url,0);}
            focusAt=ticks;
        }
        if(focusAt>=0&&interruptAt<0&&ticks-focusAt>=2){
            if(!(mc.screen instanceof WebGuiInteractionScreen screen))throw new IllegalStateException("NATIVE_INTERRUPT_SCREEN_MISSING");
            for(int cp:"人工草稿".codePoints().toArray())screen.charTyped(new net.minecraft.client.input.CharacterEvent(cp));
            interruptAt=ticks;nextInspectTick=ticks+60;
        }
        if(probe!=null&&!probe.get("viewId").getAsString().isBlank()&&host.packageLoaded(probe.get("viewId").getAsString())&&ticks%20==0&&ticks>=nextInspectTick&&!inspecting&&error==null&&phase<3
                &&!(ScoreUiSmokeServer.agentMode()&&editing&&!ScoreUiSmokeServer.titleVerified&&!ScoreUiSmokeServer.interruptVerified)){
            String view=probe.get("viewId").getAsString();inspecting=true;
            PackagePageAgent.inspectManagedView(view).whenComplete((value,failure)->mc.execute(()->{
                inspecting=false;if(failure!=null){error="DOM_INSPECTION_FAILED";return;}
                try{
                    var dom=JsonParser.parseString(value).getAsJsonObject();String text=dom.get("visibleText").getAsString();
                    var root=mc.gameDirectory.toPath().resolve(ScoreUiSmokeServer.evidenceDirectory());Files.createDirectories(root);Files.writeString(root.resolve("last-dom.json"),value);
                    if(!editing&&text.contains("Alice")&&text.contains("Bob")){
                        before=value;editing=true;Files.writeString(root.resolve("before-dom.json"),value);
                        String url=host.packageUrl(view);
                        if(ScoreUiSmokeServer.agentMode()){
                            host.browser().executeJavaScript("""
                                (()=>{
                                  document.querySelector('[data-mode="CONTENT"] [data-action="open-delegation"]').click();
                                  const card=document.querySelector('[data-delegation-view]');const agent=card.querySelector('[data-field="agent"]');
                                  agent.value=AGENT_ID;agent.dispatchEvent(new Event('change',{bubbles:true}));
                                  const goal=card.querySelector('[data-field="goal"]');goal.value=ACTION_GOAL;goal.dispatchEvent(new Event('input',{bubbles:true}));
                                  const title=card.querySelector('[data-field="expectedTitle"]');title.value=EXPECTED_TITLE;title.dispatchEvent(new Event('input',{bubbles:true}));
                                  card.querySelector('[data-field="consent"]').click();card.querySelector('[data-action="delegate"]').click();
                                })();
                                """.replace("AGENT_ID",new Gson().toJson(ScoreUiSmokeServer.agentId)).replace("ACTION_GOAL",new Gson().toJson((Boolean.getBoolean("mineagent.captureAgentSmoke")&&!ScoreUiSmokeServer.coordinateMode()?"第一步必须使用 capture 工具获取当前视图截图，下一步结合实际图片和 DOM 再继续。":"")+actionGoal())).replace("EXPECTED_TITLE",new Gson().toJson(ScoreUiSmokeServer.expectedTitle())),host.browser().getURL(),0);
                        }else for(long id:host.browser().getFrameIdentifiers()){
                            var frame=host.browser().getFrame(id);if(frame==null||frame.isMain()||!url.equals(frame.getURL()))continue;
                            frame.executeJavaScript("""
                                (()=>{const input=document.querySelector('[data-ai-id="title-input"]'),save=document.querySelector('[data-ai-id="title-save"]');
                                  if(!input||!save)throw new Error('TITLE_CONTROLS_MISSING');input.focus();
                                  const proto=input.tagName==='TEXTAREA'?HTMLTextAreaElement.prototype:HTMLInputElement.prototype;
                                  Object.getOwnPropertyDescriptor(proto,'value').set.call(input,'协作排行榜');
                                  input.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertText',data:'协作排行榜'}));save.click();})();
                                """,url,0);
                        }
                    }
                    if(ScoreUiSmokeServer.agentMode()&&ScoreUiSmokeServer.titleVerified&&text.contains(ScoreUiSmokeServer.expectedTitle())&&text.matches("(?s).*Alice\\s+7.*")){
                        after=value;phase=3;Files.writeString(root.resolve("after-dom.json"),value);
                    }
                    if(ScoreUiSmokeServer.interruptMode()&&ScoreUiSmokeServer.interruptVerified&&interruptAt>=0&&ticks-interruptAt>=60){
                        boolean draft=false;for(var e:dom.getAsJsonArray("elements"))if(e.getAsJsonObject().get("dataAiId").getAsString().equals("title-input")&&e.getAsJsonObject().get("value").getAsString().equals("人工草稿"))draft=true;
                        if(!draft)throw new IllegalStateException("MANUAL_DRAFT_NOT_PRESERVED");
                        if(ScoreUiSmokeServer.takeoverMode()){phase=99;TakeoverSmokeClient.start(view,value);}else{after=value;phase=3;}
                        Files.writeString(root.resolve("after-dom.json"),value);
                    }
                    if(editing&&ScoreUiSmokeServer.externalChanged&&text.contains("协作排行榜")&&text.matches("(?s).*Alice\\s+11.*")){
                        if(phase==0){
                            Files.writeString(root.resolve("external-refresh-dom.json"),value);phase=1;
                            host.browser().executeJavaScript("document.querySelector('#open-chat').click();document.querySelector('[data-title=\"AI 对话\"] button[aria-label=\"关闭\"]').click();",host.browser().getURL(),0);
                        }else if(phase==1){
                            Files.writeString(root.resolve("chat-closed-dom.json"),value);reloadDocument=dom.get("documentId").getAsString();phase=2;nextInspectTick=ticks+60;
                            String url=host.packageUrl(view);
                            for(long id:host.browser().getFrameIdentifiers()){var frame=host.browser().getFrame(id);if(frame!=null&&!frame.isMain()&&url.equals(frame.getURL()))frame.executeJavaScript("location.reload();",url,0);}
                        }else if(phase==2&&!dom.get("documentId").getAsString().equals(reloadDocument)){
                            after=value;phase=3;Files.writeString(root.resolve("after-dom.json"),value);
                        }
                    }
                }catch(Exception invalid){error="DOM_READBACK_FAILED";}
            }));
        }
        if(after!=null&&(ScoreUiSmokeServer.titleVerified||ScoreUiSmokeServer.interruptVerified)&&!screenshot){
            if(CaptureSmokeClient.failure!=null)throw new IllegalStateException(CaptureSmokeClient.failure);
            if(Boolean.getBoolean("mineagent.captureSmoke")&&!CaptureSmokeClient.complete){CaptureSmokeClient.start(probe.get("viewId").getAsString());return;}
            if(!(mc.screen instanceof WebGuiInteractionScreen))throw new IllegalStateException("SCORE_UI_OBSCURED");
            screenshot=true;var root=mc.gameDirectory.toPath().resolve(ScoreUiSmokeServer.evidenceDirectory());
            probe.addProperty("driver",ScoreUiSmokeServer.interruptMode()?"MINECRAFT_SCREEN_NATIVE_CHAR_INPUT_INTERRUPTS_AGENT_NOT_OS_IME":ScoreUiSmokeServer.agentMode()?"TRUSTED_SETUP_DRIVER_THEN_REAL_SERVER_MODEL_AGENT":"ACTUAL_DOM_TEST_DRIVER_NOT_AI_PLANNER");Files.writeString(root.resolve("client.json"),probe.toString());
            net.minecraft.client.Screenshot.takeScreenshot(mc.getMainRenderTarget(),image->{try(image){image.writeToFile(root.resolve("render.png"));captured=true;}catch(Exception e){throw new IllegalStateException(e);}});
        }
        if(captured&&phase==3){
            phase=4;ScoreUiSmokeServer.closeRequested=true;
            host.browser().executeJavaScript("document.querySelector('[data-mode=\"CONTENT\"] button[aria-label=\"关闭\"]').click();",host.browser().getURL(),0);
        }
        if(captured&&ScoreUiSmokeServer.closeVerified&&!ended){ended=true;dev.mineagent.runtime.neoforge.MineAgentRuntimeMod.LOGGER.info(ScoreUiSmokeServer.agentMode()
                ?ScoreUiSmokeServer.interruptMode()?"MINEAGENT_AGENT_UI_INTERRUPT_GRAPHICAL_OK nativeScreenInput=true draftPreserved=true noWorldWrite=true":"MINEAGENT_AGENT_UI_GRAPHICAL_OK actor=AGENT realModel=true businessVerified=true scoresPreserved=true"
                :"MINEAGENT_SCORE_UI_GRAPHICAL_OK titleSave=true scoresPreserved=true externalRefresh=true chatClose=true reload=true editorClose=true duplicateWire=true");host.close();mc.stop();}
        if((ticks>7200||error!=null)&&!ended){
            ended=true;var root=mc.gameDirectory.toPath().resolve(ScoreUiSmokeServer.evidenceDirectory());Files.createDirectories(root);
            Files.writeString(root.resolve("failure.json"),new Gson().toJson(java.util.Map.of("code",error==null?"SCORE_UI_TIMEOUT":error,"status",probe==null?host.diagnostic():probe.toString())));
            throw new IllegalStateException("SCORE_UI_SMOKE_FAILED: "+(error==null?host.diagnostic():error));
        }
    }
    static void accept(JsonObject value){probe=value;try{var root=Minecraft.getInstance().gameDirectory.toPath().resolve(ScoreUiSmokeServer.evidenceDirectory());Files.createDirectories(root);Files.writeString(root.resolve("last-shell.json"),value.toString());}catch(Exception ignored){}}
    private static String actionGoal(){if(ScoreUiSmokeServer.coordinateMode())return "请先将标题输入框 fill 为「协作排行榜」，再 capture 当前实际颜色区域。根据图片找到绿色圆形中心，用 clickAt 和该图片的 captureId、原始 PNG 像素 x/y 点击它保存。必要时先滚动让整个颜色区域可见，滚动后必须重新 capture。不要用 elementRef click、按键或后台接口替代坐标点击；不要修改分数。";return ScoreUiSmokeServer.toolMode()?"先用 verify 条件工具确认当前页面正文包含 Alice。然后将标题输入为「协作排行榜」，使用 key Enter 触发页面保存；接着必须用 waitFor 条件工具等待 heading 的 labelEquals 为「协作排行榜」，再用 drag 将待归档卡片拖到归档区。最终滚回顶部确认可见标题是「协作排行榜 · 已归档」。不要改变任何分数；不得用点击或后台接口代替按键与拖放。":"请把当前排行榜标题改为「协作排行榜」，在真实页面输入并点击保存，确认页面标题已保存。不要修改任何分数。";}
    private static String pageRequest(){if(ScoreUiSmokeServer.coordinateMode())return coordinateRequest();return ScoreUiSmokeServer.toolMode()?"""
        生成独立中文‘排行归档工作台’网页，绑定玩家预建计分目标，所有真实数据只用 window.mineagentUi SDK。
        h1 使用 SDK snapshot.title，表格展示实际 rows 的 holder/score，禁止模拟分数。标题 input 的 data-ai-id=title-input。
        用户在标题输入框按 Enter 时，keydown 处理器 await SDK.patch 当前 viewRevision 保存输入标题，回读后更新 h1；不要提供其他标题保存按钮。
        另有一张可拖动‘待归档卡片’ div，draggable=true、data-ai-id=archive-card、role=button；一个可见归档区 data-ai-drop-target=true、data-ai-id=archive-zone、role=region。
        使用标准 HTML5 dragstart/dragover/drop。dragstart 在 DataTransfer 中设置 text/plain 卡片 ID；dragover preventDefault，drop 验证 DataTransfer。
        只有成功通过 Enter 保存后才允许归档。drop 调用 SDK.patch 将当前已保存标题加一次‘ · 已归档’，用服务端实际返回状态更新 h1，视图 revision 随真实结果推进。
        所有按键/拖放处理器的失败要显示错误，不能假称成功。页面不得修改分数。不要用 native 浏览器默认动作，明确处理 KeyboardEvent 与 DragEvent。
        UI 显示当前步骤与事件次数，1 秒刷新真实数据，保留正在编辑的草稿。无 SDK 时显示等待绑定。
        最多3个文件 ui/index.html/ui/style.css/ui/app.js，无外网，深色紧凑布局。
        """:"生成独立中文排行榜网页，绑定玩家预先创建的原版目标。严格使用 window.mineagentUi SDK 读取真实 rows/title，不能生成模拟分数。提供可编辑标题和保存按钮（data-ai-id=title-input 和 title-save），排序下拉及名称筛选，表格显示 holder 和 score。保存标题必须调用 SDK patch 并根据实际返回状态更新 h1 标题，不能只改本地值。一秒刷新，保存期间保留人工草稿，不因后台刷新覆盖正在编辑的输入。未绑定时显示等待绑定提示。三个文件 ui/index.html/ui/style.css/ui/app.js，深色紧凑布局。";}
    private static String coordinateRequest(){return """
        生成独立中文‘颜色操作面板’网页，使用正常 window.mineagentUi SDK 绑定真实计分目标。h1 展示服务器 snapshot.title，表格列出真实 holder/score，不能模拟分数。
        一个标题输入框 data-ai-id=title-input，保留人工草稿；不提供常规保存按钮、Enter 保存、DOM 语义保存入口。
        使用 HTML Canvas 元素 data-ai-id=color-panel、aria-label=颜色操作区，intrinsic width=420 height=100，CSS 最大宽420px并自适应页面；在 Canvas 上画红、绿、蓝三个大实心圆形，白色描边，三个不重叠槽位。
        每次文档初次加载随机打乱三种颜色的槽位顺序，之后顺序稳定，不能每秒刷新重新随机。只有 Canvas 的 click handler 根据 clientX/clientY 和 getBoundingClientRect 换算 intrinsic 坐标，命中绿色圆形才保存输入框标题，调用 SDK.patch 当前 viewRevision 并用真实返回状态更新 h1。红蓝圆点击不写入，仅提示选错。
        不在 DOM 属性、隐藏文本、CSS class、aria-label 或页面正文泄漏绿色位置/坐标/颜色顺序；圆形内部不画文字，正文只提示‘点击绿色圆形保存标题’。不要增加 canvas 之外的命中 DOM 子按钮。
        页面稳定后1秒读取真实数据，不能自动保存、清空正在编辑的输入或随刷新重排圆形。所有错误显示真实原因。
        紧凑深色布局，目标是全部控件适配约500x285 CSS viewport：h1 20px并单行省略，标题输入框30px，Canvas100px，紧凑两行数据表，边距小，避免大卡片或长说明。
        只输出 ui/index.html、ui/style.css、ui/app.js 三个文件，无外网和任何硬编码模拟数据。
        """;}}
