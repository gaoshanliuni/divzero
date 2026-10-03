package dev.mineagent.runtime.neoforge.client.nativeui;

import com.google.gson.*;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.*;
import dev.mineagent.runtime.neoforge.client.language.ClientLanguage;
import dev.mineagent.runtime.neoforge.client.webui.ClientResourcePacks;
import dev.mineagent.runtime.neoforge.client.webui.ClientScriptPackages;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import java.util.*;
import java.util.concurrent.*;

/** Resource, data, boot and client-code lifecycles share native windows but retain their distinct authority. */
final class NativeLifecyclePanel {
    private static NativeLifecyclePanel smokePanel;
    static Map<String,Object> smokeState(){if(!Boolean.getBoolean("mineagent.nativeStudioSmoke"))throw new IllegalStateException("SMOKE_DISABLED");var p=smokePanel;return p==null?Map.of():Map.of("ready",p.view!=null&&!p.busy,"kind",p.kind.name(),"notice",p.notice.getText().getString());}

    enum Kind { RESOURCE, CLIENT, DATA, BOOT }
    private static final Gson JSON=new GsonBuilder().setPrettyPrinting().create();private static final Set<NativeLifecyclePanel> OPEN=new HashSet<>();
    private final NativeWorkspaceScreen host;private final WorkspaceWindow window;private final Kind kind;private final ScrollerView content;
    private final TextElement notice=WorkspacePanels.text("");private JsonObject head,view,plans;private int offset,planOffset;private boolean busy;private long epoch,nextPoll;private UUID operation;
    static void open(NativeWorkspaceScreen host,JsonObject head,Kind kind){String id="lifecycle-"+kind+"-"+(head==null?"local":text(head,"packageId"));if(host.revealWindow(id))return;new NativeLifecyclePanel(host,head,kind,id);}
    private NativeLifecyclePanel(NativeWorkspaceScreen host,JsonObject head,Kind kind,String id){this.host=host;this.head=head==null?null:head.deepCopy();this.kind=kind;if(Boolean.getBoolean("mineagent.nativeStudioSmoke"))smokePanel=this;window=host.window(id,t(title(kind)),560,400);OPEN.add(this);var tools=WorkspacePanels.row();tools.getLayout().height(25);window.body.addChild(tools);tools.addChild(NativeUiTheme.button(t("只读刷新"),this::read));tools.addChild(NativeUiTheme.button(t("本机资源包"),()->open(host,null,Kind.RESOURCE)));tools.addChild(NativeUiTheme.button(t("本机客户端代码"),()->open(host,null,Kind.CLIENT)));tools.addChild(NativeUiTheme.button(t("启动扩展"),()->open(host,null,Kind.BOOT)));window.body.addChild(notice);content=WorkspacePanels.scroller(window.body);read();}
    private static String title(Kind kind){return switch(kind){case RESOURCE->"本机资源包";case CLIENT->"本机客户端代码";case DATA->"数据包与世界计划";case BOOT->"启动扩展";};}
    private boolean live(){return host.activeContext()&&!window.closed();}
    private static String text(JsonObject value,String key){return value!=null&&value.has(key)&&!value.get(key).isJsonNull()?value.get(key).getAsString():"";}
    private static boolean yes(JsonObject value,String key){return value!=null&&value.has(key)&&value.get(key).isJsonPrimitive()&&value.get(key).getAsBoolean();}
    private static JsonArray array(JsonObject value,String key){return value!=null&&value.has(key)&&value.get(key).isJsonArray()?value.getAsJsonArray(key):new JsonArray();}
    private static JsonObject object(JsonObject value,String key){return value!=null&&value.has(key)&&value.get(key).isJsonObject()?value.getAsJsonObject(key):new JsonObject();}
    private CompletableFuture<JsonObject> request(String action,Map<String,String> values){return WorkspacePanels.request(action,values).thenApply(WorkspacePanels::state);}
    private Map<String,String> packageArgs(){return Map.of("packageId",text(head,"packageId"),"packageRevision",text(head,"revision"));}
    private void read(){
        if(busy||!live())return;busy=true;long ticket=++epoch;
        var fresh=head==null?CompletableFuture.completedFuture((JsonObject)null):request("task.historyRead",Map.of("kind","package","packageId",text(head,"packageId"),"headRevision","0","headHash",""));
        fresh.thenCompose(value->{head=value;try{return switch(kind){
            case RESOURCE->CompletableFuture.completedFuture(JSON.toJsonTree(ClientResourcePacks.read(offset)).getAsJsonObject());
            case CLIENT->CompletableFuture.completedFuture(JSON.toJsonTree(ClientScriptPackages.read(offset)).getAsJsonObject());
            case DATA->{var args=new LinkedHashMap<>(packageArgs());args.put("offset",Integer.toString(offset));args.put("operationId",operation==null?"":operation.toString());yield request("dataPack.read",args);}
            case BOOT->request("boot.read",Map.of("kind","list","buildId","","offset",Integer.toString(offset))).thenCompose(state->request("boot.read",Map.of("kind","plans","buildId","","offset",Integer.toString(planOffset))).thenApply(value2->{plans=value2;return state;}));
        };}catch(Exception error){return CompletableFuture.failedFuture(error);}}).whenComplete((value,error)->{
            busy=false;if(!live()||ticket!=epoch)return;if(error!=null){WorkspacePanels.failure(notice,error);return;}view=value;notice.setText(Component.literal(""));draw();nextPoll=System.currentTimeMillis()+1500;
        });
    }
    private void draw(){content.clearAllScrollViewChildren();if(kind==Kind.DATA){data();return;}if(kind==Kind.BOOT){boot();return;}client();}
    private UIElement card(String title){return WorkspacePanels.card(content,title);}
    private Button button(UIElement parent,String title,Runnable action,boolean allowed){var value=NativeUiTheme.button(t(title),action);value.setActive(allowed&&!busy);parent.addChild(value);return value;}
    private Toggle confirm(UIElement parent,String label){var value=new Toggle().setText(t(label));parent.addChild(value);return value;}
    private void details(UIElement parent,String title,JsonElement value){button(parent,title,()->{var box=host.window("detail-"+UUID.randomUUID(),t(title),500,330);var list=WorkspacePanels.scroller(box.body);NativeEvidenceView.add(host,list,value);},true);}
    private void pager(JsonObject state,int size,boolean plan){int index=plan?planOffset:offset;var row=WorkspacePanels.row();row.getLayout().height(25);content.addScrollViewChild(row);button(row,"上一页",()->{if(plan)planOffset=Math.max(0,index-size);else offset=Math.max(0,index-size);read();},index>0);button(row,"下一页",()->{if(plan)planOffset=state.get("nextOffset").getAsInt();else offset=state.get("nextOffset").getAsInt();read();},yes(state,"more"));}
    private void client(){
        var intro=card(t(title(kind)));intro.addChild(WorkspacePanels.text(t(kind==Kind.RESOURCE?"资源启用会重载本机全局资源，影响菜单及其它世界；失败恢复可能改变其它已选资源。":"客户端代码在本机运行。下载验签不等于批准，启动可能产生不可撤销的副作用。")));
        if(head!=null){intro.addChild(WorkspacePanels.text(text(head,"name")));if(kind==Kind.RESOURCE)button(intro,"下载并验签（不启用）",()->local(()->ClientResourcePacks.startDownload(UUID.fromString(text(head,"packageId")),Long.parseLong(text(head,"revision")),text(head,"canonicalSha256"))),yes(head,"resourcePackAvailable")&&!yes(view,"busy"));else{
            button(intro,"下载 Rhino 源码",()->downloadCode(false),yes(head,"clientScriptAvailable")&&!yes(view,"busy"));button(intro,"下载 Java 源码",()->downloadCode(true),yes(head,"clientJavaAvailable")&&!yes(view,"busy"));
        }}
        var download=object(view,"download");if(download.has("packageId")){intro.addChild(WorkspacePanels.text(t("下载进度")+" "+text(download,"received")+" / "+text(download,"total")));button(intro,"取消下载",()->local(()->{if(kind==Kind.RESOURCE)ClientResourcePacks.cancelDownload();else ClientScriptPackages.cancelDownload();return Map.of("code","CANCELLED");}),true);}
        if(!text(view,"downloadOutcome").isBlank())intro.addChild(WorkspacePanels.text(text(view,"downloadOutcome")));
        for(var raw:array(view,"items")){
            var asset=raw.getAsJsonObject();var item=card(text(asset,"name"));item.addChild(WorkspacePanels.text(text(asset,"state")+" · "+t(yes(asset,"loadedNow")?"已启用":"未启用")));if(!text(asset,"runtimeStatus").isBlank())item.addChild(WorkspacePanels.text(text(asset,"runtimeStatus")));if(!text(asset,"error").isBlank())item.addChild(WorkspacePanels.text(text(asset,"error")));
            details(item,"来源与操作记录",asset);var consent=confirm(item,kind==Kind.RESOURCE?"明确允许此本机全局资源变更":"明确允许此来源和版本在本机执行");
            button(item,kind==Kind.RESOURCE?"本机启用并重载":"编译并启动",()->changeLocal(asset,kind==Kind.RESOURCE?"ENABLE":"START",consent.isOn()),yes(asset,kind==Kind.RESOURCE?"canEnable":"canStart"));
            button(item,kind==Kind.RESOURCE?"本机停用并重载":"停止此本机代码",()->changeLocal(asset,kind==Kind.RESOURCE?"DISABLE":"STOP",consent.isOn()),yes(asset,kind==Kind.RESOURCE?"canDisable":"canStop"));
        }pager(view,8,false);
    }
    private void downloadCode(boolean java){local(()->ClientScriptPackages.startDownload(UUID.fromString(text(head,"packageId")),Long.parseLong(text(head,"revision")),text(head,"canonicalSha256"),java));}
    private void changeLocal(JsonObject asset,String action,boolean consent){if(!consent){notice.setText(Component.literal(t("请先勾选明确确认。")));return;}operation=UUID.randomUUID();local(()->kind==Kind.RESOURCE?ClientResourcePacks.change(operation,text(asset,"filename"),action,Long.parseLong(text(asset,"revision")),text(view,"selection"),text(view,"environment"),true,"PLAYER:"+Minecraft.getInstance().player.getUUID(),ClientResourcePacks.webScope()):ClientScriptPackages.change(operation,text(asset,"filename"),action,Long.parseLong(text(asset,"revision")),text(view,"environment"),true,"PLAYER:"+Minecraft.getInstance().player.getUUID(),ClientScriptPackages.webScope()));}
    private void local(Callable<Map<String,Object>> action){if(busy||!live())return;busy=true;try{var result=action.call();String code=Objects.toString(result.get("code"),"");if(!(code.equals("ACCEPTED")||code.equals("CANCELLED")||code.endsWith("DOWNLOADED_NOT_APPROVED")))throw new IllegalStateException(code);busy=false;read();}catch(Exception error){busy=false;WorkspacePanels.failure(notice,error);}}
    private void data(){
        var intro=card(text(view,"name"));boolean reopen=text(view,"mode").equals("WORLD_REOPEN");intro.addChild(WorkspacePanels.text(t(reopen?"暂存只保存下次开图计划；不会自动退出世界或立即加载世界生成。":"启用或停用会重载全服数据，并可能执行所选数据包的加载函数；停用不会回滚已有世界变化。")));intro.addChild(WorkspacePanels.text(t(yes(view,"loadedNow")?"已装入":"未装入")));if(!text(view,"error").isBlank())intro.addChild(WorkspacePanels.text(text(view,"error")));details(intro,"来源与操作记录",view);
        var consent=confirm(intro,"明确确认本次数据或世界计划变更");
        for(var action:Map.of("ENABLE","启用并重载","DISABLE","停用并重载","STAGE_REOPEN","保存重开计划","CANCEL_REOPEN","取消重开计划").entrySet()){
            String flag=switch(action.getKey()){case "ENABLE"->"canEnable";case "DISABLE"->"canDisable";case "STAGE_REOPEN"->"canStageReopen";default->"canCancelReopen";};
            button(intro,action.getValue(),()->{if(!consent.isOn()){notice.setText(Component.literal(t("请先勾选明确确认。")));return;}var args=new LinkedHashMap<>(packageArgs());args.put("packageRevision",text(view,"packageRevision"));args.put("canonical",text(view,"canonical"));args.put("action",action.getKey());args.put("selection",text(view,"selection"));args.put("environment",text(view,"environment"));args.put("confirmed","true");args.put("reopenOperation",action.getKey().equals("CANCEL_REOPEN")?text(object(view,"reopenPlan"),"operation"):"");mutate("dataPack.change",args);},yes(view,flag));
        }
        for(var raw:array(view,"artifacts")){var artifact=raw.getAsJsonObject();var item=card(text(artifact,"state"));item.addChild(WorkspacePanels.text(text(artifact,"file")));if(!text(artifact,"admissionError").isBlank())item.addChild(WorkspacePanels.text(text(artifact,"admissionError")));}pager(view,8,false);
    }
    private void boot(){
        var intro=card(t("启动扩展"));intro.addChild(WorkspacePanels.text(t("启动扩展修改服务端所在机器的全局 mods，下一次重启才加载；移出不撤销当前运行或已有存档变化。请先备份并停止共用目录的其它实例。")));intro.addChild(WorkspacePanels.text(text(view,"modsDirectory")));
        if(head!=null){intro.addChild(WorkspacePanels.text(text(head,"name")));button(intro,"构建启动扩展",()->build(""),yes(head,"bootAvailable")&&!yes(view,"busy"));}
        for(var raw:array(view,"builds")){
            var build=raw.getAsJsonObject();var item=card(text(build,"name")+" · "+text(build,"modId"));item.addChild(WorkspacePanels.text(text(build,"phase")+" · "+text(build,"loader")));if(!text(build,"error").isBlank())item.addChild(WorkspacePanels.text(text(build,"error")));details(item,"来源与操作记录",build);
            button(item,"编译诊断",()->bootRead("diagnostics",text(build,"id"),0),build.has("diagnostics")&&build.get("diagnostics").getAsInt()>0);button(item,"依赖与构建绑定",()->bootRead("dependencies",text(build,"id"),0),build.has("dependencyCount")&&build.get("dependencyCount").getAsInt()>0);
            if(head!=null&&text(build,"packageId").equals(text(head,"packageId"))&&!text(build,"canonical").equals(text(head,"canonicalSha256"))&&Set.of("INSTALLED_PENDING_RESTART","FILE_STATE_UNKNOWN").contains(text(build,"phase"))&&text(build,"fileState").equals("HASH_MATCHED"))button(item,"编译替换版本",()->build(text(build,"id")),!yes(view,"busy"));
            if(Set.of("BUILT","INSTALLED_PENDING_RESTART","FILE_STATE_UNKNOWN","REMOVED_PENDING_RESTART").contains(text(build,"phase"))){var consent=confirm(item,"已核对全局目录、准确版本与重启影响，并已备份");boolean install=text(build,"phase").equals("BUILT"),upgrade=install&&!text(build,"replaces").isBlank();button(item,upgrade?"暂存停机替换计划":install?"全局安装，下次重启加载":"全局移出，下次重启停用",()->{if(!consent.isOn()){notice.setText(Component.literal(t("请先勾选明确确认。")));return;}var args=new LinkedHashMap<String,String>();args.put("kind",upgrade?"stageUpgrade":"change");args.put("buildId",text(build,"id"));args.put("expectedRevision",text(build,"revision"));args.put("environment",text(view,"environment"));args.put("confirmed","true");args.put("confirmModId",text(build,"modId"));if(!upgrade)args.put("action",install?"INSTALL":"REMOVE");mutate("boot.change",args);},!yes(view,"busy"));}
        }pager(view,8,false);
        for(var raw:array(plans,"plans")){var plan=raw.getAsJsonObject();var item=card(text(plan,"modId")+" · "+text(plan,"phase"));item.addChild(WorkspacePanels.text(t("此计划需停机执行；不会在游戏中替换运行中的 Mod。")));details(item,"停机计划与恢复信息",plan);button(item,"安排退出后应用",()->scheduleMaintenance(plan,"apply",false),yes(plan,"approvedFile")&&!yes(plan,"cancelledFile")&&!yes(plan,"applyReceipt"));button(item,"安排退出后恢复旧版本",()->scheduleMaintenance(plan,"rollback",false),yes(plan,"applyReceipt")&&!yes(plan,"rollbackReceipt"));var consent=confirm(item,"明确取消尚未执行的计划");button(item,"取消此计划",()->{if(consent.isOn())mutate("boot.change",Map.of("kind","cancelUpgrade","upgradeId",text(plan,"operation"),"planHash",text(plan,"planHash"),"confirmed","true"));},!yes(view,"busy")&&Set.of("PREPARING","WAIT_OFFLINE","PLAN_WRITE_FAILED","CANCEL_FAILED").contains(text(plan,"phase"))&&!yes(plan,"cancelledFile"));}if(plans!=null)pager(plans,8,true);
    }
    private void scheduleMaintenance(JsonObject plan,String action,boolean preview){
        var mc=Minecraft.getInstance();if(mc.getSingleplayerServer()==null){notice.setText(Component.literal(t("请在服务器所在机器使用内置 Java 维护程序。")));return;}
        var game=mc.gameDirectory.toPath();var selected=plan.deepCopy();var selectedView=view.deepCopy();
        Dialog.showCheckBox(t("退出后维护"),t("退出游戏后将处理这个已批准版本；保留旧文件，不回滚世界数据。是否安排？"),yes->{if(!yes||!live())return;busy=true;
            CompletableFuture.supplyAsync(()->{try{
                if(!game.toRealPath().equals(java.nio.file.Path.of(text(selectedView,"gameDirectory")).toRealPath()))throw new IllegalStateException("MAINTENANCE_GAME_CONTEXT_CHANGED");
                return dev.mineagent.runtime.client.maintenance.JavaMaintenanceLauncher.schedule(game,Map.of("kind","boot-upgrade","mods",text(selectedView,"modsDirectory"),"operation",text(selected,"operation"),"plan-hash",text(selected,"planHash"),"mod-id",text(selected,"modId"),"action",action,"preview",Boolean.toString(preview)));
            }catch(Exception error){throw new CompletionException(error);}}).whenComplete((result,error)->mc.execute(()->{busy=false;if(!live())return;if(error!=null)WorkspacePanels.failure(notice,error);else notice.setText(Component.literal(t("已安排：退出游戏后由 Java 执行，结果保存在 mineagent-maintenance。")));}));
        }).show(window.body);
    }
    private void build(String replacement){var args=new LinkedHashMap<>(packageArgs());args.put("kind","build");args.put("canonical",text(head,"canonicalSha256"));args.put("environment",text(view,"environment"));args.put("replacementBuildId",replacement);mutate("boot.change",args);}
    private void mutate(String action,Map<String,String> values){if(busy)return;busy=true;operation=UUID.randomUUID();WorkspacePanels.request(action,values,operation).whenComplete((receipt,error)->{busy=false;if(!live())return;if(error!=null){WorkspacePanels.failure(notice,error);return;}read();});}
    private void bootRead(String kind,String id,int offset){String key="boot-detail-"+kind+"-"+id;var detail=host.window(key,t(kind.equals("diagnostics")?"编译诊断":"依赖与构建绑定"),510,360);detail.body.clearAllChildren();var status=WorkspacePanels.text(t("正在读取…"));detail.body.addChild(status);request("boot.read",Map.of("kind",kind,"buildId",id,"offset",Integer.toString(offset))).whenComplete((value,error)->{if(detail.closed()||!live())return;if(error!=null){WorkspacePanels.failure(status,error);return;}status.setText(Component.literal(""));var text=WorkspacePanels.scroller(detail.body);NativeEvidenceView.add(host,text,value);var buttons=WorkspacePanels.row();buttons.getLayout().height(25);detail.body.addChild(buttons);button(buttons,"上一页",()->bootRead(kind,id,Math.max(0,offset-(kind.equals("diagnostics")?4096:8))),offset>0);button(buttons,"下一页",()->bootRead(kind,id,value.get("nextOffset").getAsInt()),yes(value,"more"));});}
    static void tick(){OPEN.removeIf(p->!p.live());if(!NativeWorkspaceScreen.visible())return;for(var p:OPEN)if(p.view!=null&&!p.busy&&System.currentTimeMillis()>=p.nextPoll&&(yes(p.view,"busy")||yes(p.view,"persistingResult")||object(p.view,"download").has("packageId")||Set.of("PREPARING","BUILDING","DISPATCHING","SAVING_REOPEN").contains(text(object(p.view,"job"),"phase"))))p.read();}
    private static String t(String value){return ClientLanguage.t(value);}
}
