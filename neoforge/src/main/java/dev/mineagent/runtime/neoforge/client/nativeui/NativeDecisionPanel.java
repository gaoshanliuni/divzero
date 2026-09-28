package dev.mineagent.runtime.neoforge.client.nativeui;

import com.google.gson.*;
import com.lowdragmc.lowdraglib2.gui.ui.elements.*;
import dev.mineagent.runtime.api.decision.*;
import dev.mineagent.runtime.api.ui.UiProtocol.Code;
import dev.mineagent.runtime.client.decision.DecisionDraft;
import dev.mineagent.runtime.client.webui.UiStateStore;
import dev.mineagent.runtime.neoforge.client.language.ClientLanguage;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import java.util.*;
import java.util.concurrent.*;

/** Trusted player-only cards. Package observers/actions never receive these widget roots. */
final class NativeDecisionPanel {
    private static final Gson JSON=new Gson();
    private static final ExecutorService IO=Executors.newSingleThreadExecutor(Thread.ofVirtual().name("native-decision-drafts").factory());
    private static final Map<UUID,Entry> ENTRIES=new LinkedHashMap<>();
    private static String scope="";private static int pendingCount;private static Index index;
    private static final class Entry {
        final DecisionDraft draft;Card card;JsonObject effect;boolean busy,unknown;long saveAt;String loadedScope="";
        Entry(DecisionRequest request){draft=new DecisionDraft(request);}
    }
    private static UiStateStore store(){return new UiStateStore(Minecraft.getInstance().gameDirectory.toPath().resolve("mineagent-runtime-data/native-decision-drafts"));}
    static void reset(){for(var entry:ENTRIES.values())save(entry);ENTRIES.clear();scope="";pendingCount=0;index=null;}
    static void sessionReady(){var session=NativeWorkspaceConnection.current();if(session==null)return;var server=Minecraft.getInstance().getCurrentServer();String next=(server==null?"integrated":server.ip)+"|"+session.binding().worldId()+"|"+session.binding().viewerPlayerId();if(!scope.isEmpty()&&!scope.equals(next))reset();scope=next;}
    static String label(){return t("待决定")+" ("+pendingCount+")";}
    static void snapshot(Map<String,String> values){
        if(values.containsKey("decisionPaging"))pendingCount=JsonParser.parseString(values.get("decisionPaging")).getAsJsonObject().get("pendingCount").getAsInt();
        if(values.containsKey("decisions"))for(var raw:JsonParser.parseString(values.get("decisions")).getAsJsonArray())accept(JSON.fromJson(raw,DecisionRequest.class),null,null);
    }
    private static Entry accept(DecisionRequest request,DecisionAnswerSubmission answer,JsonObject effect){
        var session=NativeWorkspaceConnection.current();if(session==null||!session.binding().viewerPlayerId().equals(request.recipientPlayerId()))throw new IllegalArgumentException("DECISION_RECIPIENT");
        var entry=ENTRIES.computeIfAbsent(request.decisionId(),id->new Entry(request));
        var before=entry.draft.request();if(!entry.draft.update(request))return entry;
        if(answer!=null)entry.draft.restoreAccepted(answer);
        boolean effectChanged=effect!=null&&!effect.equals(entry.effect);if(effect!=null)entry.effect=effect.deepCopy();
        if(!before.equals(request)||answer!=null){entry.saveAt=System.currentTimeMillis()+300;}
        if(entry.card!=null&&entry.card.current()&&(!before.equals(request)||answer!=null||effectChanged))entry.card.draw();
        return entry;
    }
    static void open(NativeWorkspaceScreen host){if(index!=null&&index.host==host&&!index.window.closed()){index.window.reveal();index.load();}else index=new Index(host);}
    static void tick(){
        long now=System.currentTimeMillis();for(var entry:List.copyOf(ENTRIES.values())){
            if(entry.saveAt>0&&now>=entry.saveAt)save(entry);
            if(entry.card!=null&&entry.card.current()&&entry.card.window.visible()&&now>=entry.card.nextRead)entry.card.refresh();
        }
    }
    private static void load(Entry entry){
        if(scope.isBlank()||entry.loadedScope.equals(scope))return;String expected=scope;entry.loadedScope=scope;String key=scope+"|"+entry.draft.request().decisionId();var storage=store();
        CompletableFuture.supplyAsync(()->{try{var saved=JSON.fromJson(storage.load(key),DecisionDraft.Saved.class);if(saved==null||saved.decisionId()==null){var oldStore=new UiStateStore(Minecraft.getInstance().gameDirectory.toPath().resolve("mineagent-runtime-data/ui-state"));var old=JsonParser.parseString(oldStore.load(expected)).getAsJsonObject();String id=entry.draft.request().decisionId().toString();if(old.has("drafts")&&old.getAsJsonObject("drafts").has(id))saved=JSON.fromJson(old.getAsJsonObject("drafts").get(id),DecisionDraft.Saved.class);}return saved;}catch(Exception failure){throw new CompletionException(failure);}},IO).whenComplete((saved,error)->Minecraft.getInstance().execute(()->{
            if(!scope.equals(expected)||ENTRIES.get(entry.draft.request().decisionId())!=entry)return;
            if(error==null){entry.draft.restore(saved);if(entry.card!=null&&entry.card.current())entry.card.draw();}
            else if(entry.card!=null&&entry.card.current())entry.card.notice.setText(Component.literal(t("草稿读取失败，当前输入仍保留。")));
        }));
    }
    private static void save(Entry entry){
        if(scope.isBlank()||entry.saveAt==0)return;entry.saveAt=0;String expected=scope,key=scope+"|"+entry.draft.request().decisionId(),value=JSON.toJson(entry.draft.snapshot());var storage=store();
        CompletableFuture.runAsync(()->{try{storage.save(key,value);}catch(Exception failure){throw new CompletionException(failure);}},IO).exceptionally(error->{Minecraft.getInstance().execute(()->{if(scope.equals(expected))NativeWorkspaceScreen.notice(t("选择草稿保存失败，当前输入仍保留。"));});return null;});
    }
    private static void show(NativeWorkspaceScreen host,DecisionRequest request){var entry=accept(request,null,null);if(entry.card!=null&&entry.card.host==host&&!entry.card.window.closed()){entry.card.window.reveal();entry.card.refresh();return;}entry.card=new Card(host,entry);load(entry);entry.card.refresh();}
    private static final class Index {
        final NativeWorkspaceScreen host;final WorkspaceWindow window;final ScrollerView list;final TextElement notice=WorkspacePanels.text("");int page,pages=1;boolean busy;
        Index(NativeWorkspaceScreen host){this.host=host;window=host.window("decisions",t("待决定与历史"),480,360);window.body.addChild(notice);list=WorkspacePanels.scroller(window.body);var controls=WorkspacePanels.row();controls.getLayout().height(25);window.body.addChild(controls);controls.addChild(button("上一页",()->{if(!busy&&page>0){page--;load();}}));controls.addChild(button("下一页",()->{if(!busy&&page+1<pages){page++;load();}}));controls.addChild(button("刷新",this::load));load();}
        void load(){if(busy)return;busy=true;WorkspacePanels.request("shell.read",Map.of("decisionPage",Integer.toString(page))).whenComplete((receipt,error)->{busy=false;if(!host.activeContext()||window.closed())return;if(error!=null){WorkspacePanels.failure(notice,error);return;}snapshot(receipt.values());var paging=JsonParser.parseString(receipt.values().get("decisionPaging")).getAsJsonObject();page=paging.get("page").getAsInt();pages=paging.get("pages").getAsInt();list.clearAllScrollViewChildren();for(var raw:JsonParser.parseString(receipt.values().get("decisions")).getAsJsonArray()){var request=JSON.fromJson(raw,DecisionRequest.class);var card=WorkspacePanels.card(list,request.title());card.addChild(WorkspacePanels.text(status(request.status())));card.addChild(button("查看问题",()->show(host,request)));}notice.setText(Component.literal(label()+" · "+(page+1)+" / "+Math.max(1,pages)));});}
    }
    private static final class Card {
        final NativeWorkspaceScreen host;final Entry entry;final WorkspaceWindow window;final TextElement notice=WorkspacePanels.text("");final ScrollerView body;long nextRead;boolean reading;
        Card(NativeWorkspaceScreen host,Entry entry){this.host=host;this.entry=entry;window=host.window("decision-"+entry.draft.request().decisionId(),t("选择")+" · "+entry.draft.request().title(),490,390);window.body.addChild(notice);body=WorkspacePanels.scroller(window.body);draw();}
        boolean current(){return host.activeContext()&&!window.closed()&&ENTRIES.get(entry.draft.request().decisionId())==entry;}
        void draw(){
            if(!current())return;var draft=entry.draft;var request=draft.request();body.clearAllScrollViewChildren();var question=WorkspacePanels.card(body,request.title());question.addChild(WorkspacePanels.text(request.question()));question.addChild(WorkspacePanels.text(status(request.status())));
            if(request.kind()==DecisionKind.AUTHORIZATION)question.addChild(WorkspacePanels.text(t("权限申请：仅由玩家确认，AI 不可代批。")));
            if(entry.effect!=null){String state=text(entry.effect,"state");question.addChild(WorkspacePanels.text(t(switch(state){case "APPLIED"->"外观已应用";case "APPLYING"->"正在应用外观";case "FAILED"->"外观未应用";default->"外观结果待核对，请先重新读取";})+" · "+text(entry.effect,"error")+" · r"+text(entry.effect,"resultRevision")));}
            boolean writable=request.status()==DecisionStatus.OPEN&&!entry.busy&&!entry.unknown;
            var controls=new LinkedHashMap<String,Toggle>();
            for(var option:request.options()){var card=WorkspacePanels.card(body,option.title());if(!option.description().isBlank())card.addChild(WorkspacePanels.text(option.description()));var toggle=new Toggle().setText(t("选择"));toggle.setOn(draft.selected().contains(option.optionId()),false);toggle.setActive(writable);controls.put(option.optionId(),toggle);toggle.registerValueListener(on->{if(!current()||entry.busy||entry.unknown)return;try{draft.toggle(option.optionId());controls.forEach((id,field)->field.setOn(draft.selected().contains(id),false));changed();}catch(Exception error){WorkspacePanels.failure(notice,error);}});card.addChild(toggle);}
            if(request.allowCustomInput()){var card=WorkspacePanels.card(body,t(request.kind()==DecisionKind.AUTHORIZATION?"补充说明（仍须明确选择授权选项）":"补充或提出新的方案（可只填写此项）"));var input=new TextArea();input.getLayout().height(74).widthPercent(100);input.setValue(draft.customText().split("\n",-1),false);input.setActive(writable);input.registerValueListener(value->{try{draft.edit(String.join("\n",value));changed();}catch(Exception error){input.setValue(draft.customText().split("\n",-1),false);WorkspacePanels.failure(notice,error);}});card.addChild(input);}
            var actions=WorkspacePanels.row();actions.getLayout().height(26);body.addScrollViewChild(actions);
            if(request.status()==DecisionStatus.OPEN){var submit=button("提交",()->execute("submit"));submit.setActive(writable);actions.addChild(submit);var clear=button("清除选项",()->{draft.clear();changed();draw();});clear.setActive(writable);actions.addChild(clear);var defer=button("稍后决定",()->execute("defer"));defer.setActive(!entry.busy&&!entry.unknown);actions.addChild(defer);}
            else if(request.status()==DecisionStatus.DEFERRED){var resume=button("继续回答",()->execute("resume"));resume.setActive(!entry.busy&&!entry.unknown);actions.addChild(resume);}
            actions.addChild(button("读取实际状态",this::refresh));
            if(Set.of(DecisionStatus.OPEN,DecisionStatus.DEFERRED).contains(request.status())){var cancel=button("取消关联任务",()->Dialog.showCheckBox(t("取消关联任务"),request.title(),yes->{if(yes&&current())execute("cancelTask");}).show(window.body));cancel.setActive(!entry.busy&&!entry.unknown);body.addScrollViewChild(cancel);}
            body.addScrollViewChild(WorkspacePanels.text(t("关闭或收起本窗口只隐藏界面，不提交、不授权、不取消任务。")));
        }
        void changed(){entry.saveAt=System.currentTimeMillis()+400;}
        void refresh(){
            if(reading||entry.busy||!current())return;reading=true;nextRead=System.currentTimeMillis()+1500;var id=entry.draft.request().decisionId();
            WorkspacePanels.request("shell.read",Map.of("watchDecisionId",id.toString())).whenComplete((receipt,error)->{reading=false;if(!current())return;if(error!=null){WorkspacePanels.failure(notice,error);return;}var values=receipt.values();if(!values.containsKey("decisionUpdate")){notice.setText(Component.literal(t("问题已不可用；保留草稿，不会提交。")));return;}boolean wasUnknown=entry.unknown;accept(JSON.fromJson(values.get("decisionUpdate"),DecisionRequest.class),values.containsKey("decisionAnswer")?JSON.fromJson(values.get("decisionAnswer"),DecisionAnswerSubmission.class):null,values.containsKey("decisionEffect")?JsonParser.parseString(values.get("decisionEffect")).getAsJsonObject():null);entry.unknown=false;if(wasUnknown){draw();notice.setText(Component.literal(t("已读取服务器状态；如需提交，请再次点击提交。")));}});
        }
        void execute(String action){
            if(!current()||entry.busy||entry.unknown)return;var request=entry.draft.request();var args=new LinkedHashMap<String,String>();args.put("decisionId",request.decisionId().toString());args.put("expectedRevision",Long.toString(request.revision()));
            try{if(action.equals("submit")){var answer=entry.draft.submission();args.put("submissionId",answer.submissionId().toString());args.put("selectedCount",Integer.toString(answer.selectedOptionIds().size()));for(int i=0;i<answer.selectedOptionIds().size();i++)args.put("selected."+i,answer.selectedOptionIds().get(i));args.put("customText",answer.customText());changed();save(entry);}}catch(Exception invalid){WorkspacePanels.failure(notice,invalid);return;}
            entry.busy=true;draw();NativeWorkspaceConnection.command("decision."+action,args,UUID.randomUUID()).whenComplete((receipt,error)->{
                entry.busy=false;if(ENTRIES.get(request.decisionId())!=entry)return;
                if(error!=null){entry.unknown=true;if(current()){draw();WorkspacePanels.failure(notice,error);}return;}
                var values=receipt.values();if(values.containsKey("decision"))accept(JSON.fromJson(values.get("decision"),DecisionRequest.class),values.containsKey("answer")?JSON.fromJson(values.get("answer"),DecisionAnswerSubmission.class):null,values.containsKey("domainEffect")?JsonParser.parseString(values.get("domainEffect")).getAsJsonObject():null);
                entry.unknown=!Set.of(Code.APPLIED,Code.OBSERVED,Code.ACCEPTED,Code.OK,Code.INVALID_REQUEST,Code.STATE_CONFLICT,Code.PERMISSION_DENIED,Code.STALE_VIEW,Code.EXPIRED,Code.VIEW_NOT_RENDERED).contains(receipt.code());
                if(current()){draw();notice.setText(Component.literal(!values.getOrDefault("errorCode","").isBlank()?values.get("errorCode"):entry.unknown?t("结果待核对，请读取实际状态。"):status(entry.draft.request().status())));nextRead=0;}if(index!=null&&index.host.activeContext()&&!index.window.closed())index.load();
            });
        }
    }
    private static Entry smokeEntry(UUID id){if(!Boolean.getBoolean("mineagent.nativeExtrasSmoke"))throw new IllegalStateException("SMOKE_DISABLED");return Objects.requireNonNull(ENTRIES.get(id));}
    static void smokeOpen(NativeWorkspaceScreen host,DecisionRequest request){if(!Boolean.getBoolean("mineagent.nativeExtrasSmoke"))throw new IllegalStateException("SMOKE_DISABLED");show(host,request);}
    static void smokeEdit(UUID id,String selected,String text){var entry=smokeEntry(id);if(selected!=null)entry.draft.toggle(selected);if(text!=null)entry.draft.edit(text);entry.card.changed();entry.card.draw();}
    static void smokeAction(UUID id,String action){smokeEntry(id).card.execute(action);}
    static void smokeClose(UUID id){smokeEntry(id).card.window.close();}
    static Map<String,Object> smokeState(UUID id){var entry=smokeEntry(id);return Map.of("status",entry.draft.request().status().name(),"selected",entry.draft.selected(),"custom",entry.draft.customText(),"busy",entry.busy,"notice",entry.card.notice.getText().getString(),"visible",entry.card.window.visible());}
    private static String status(DecisionStatus status){return t(switch(status){case OPEN->"等待你的选择";case DEFERRED->"已稍后处理；未提交或授权。";case RESOLVED->"已提交 · 服务器已确认";case CANCELLED->"任务已取消，此问题不再接受回答。";case SUPERSEDED->"问题已失效；保留的草稿仅供查看，不会自动提交。";case EXPIRED->"问题已过期；保留草稿，不会提交。";});}
    private static String text(JsonObject object,String key){return object.has(key)&&!object.get(key).isJsonNull()?object.get(key).getAsString():"";}
    private static String t(String value){return ClientLanguage.t(value);}
    private static Button button(String value,Runnable action){return NativeUiTheme.button(t(value),action);}
    private NativeDecisionPanel(){}
}
