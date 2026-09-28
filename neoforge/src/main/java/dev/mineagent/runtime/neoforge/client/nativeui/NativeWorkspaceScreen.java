package dev.mineagent.runtime.neoforge.client.nativeui;

import com.google.gson.*;
import com.lowdragmc.lowdraglib2.gui.holder.ModularUIScreen;
import com.lowdragmc.lowdraglib2.gui.texture.ColorRectTexture;
import com.lowdragmc.lowdraglib2.gui.ui.*;
import com.lowdragmc.lowdraglib2.gui.ui.data.TextWrap;
import com.lowdragmc.lowdraglib2.gui.ui.elements.*;
import dev.mineagent.runtime.api.config.PanelSection;
import dev.mineagent.runtime.api.ui.UiProtocol.*;
import dev.mineagent.runtime.neoforge.client.language.ClientLanguage;
import dev.mineagent.runtime.neoforge.client.screen.*;
import dev.mineagent.runtime.neoforge.client.webui.BuildingFilesClient;
import dev.vfyjxf.taffy.style.FlexDirection;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** F2/Ctrl+M share this direct LDLib2 workspace. No HTML, browser or KubeJS is used here. */
public final class NativeWorkspaceScreen extends ModularUIScreen {
    private static final Gson JSON=new Gson();
    private static final class Model {
        String agent="",conversation="",filter="ACTIVE",notice="";JsonObject selected;
        final dev.mineagent.runtime.client.conversation.PendingConversationSends sends=new dev.mineagent.runtime.client.conversation.PendingConversationSends();
        JsonArray agents=new JsonArray();final Map<String,String> drafts=new HashMap<>(),speechOps=new HashMap<>();final Map<String,WorkspaceWindow.Placement> placements=new HashMap<>();long generation;
    }
    private static final java.util.concurrent.ExecutorService STATE_IO=java.util.concurrent.Executors.newSingleThreadExecutor(Thread.ofVirtual().name("native-workspace-state").factory());
    private static String stateScope;private static long saveAt;private static Model model=new Model();private static NativeWorkspaceScreen active;private static Object connection,level;
    private final UIElement root,desktop=new UIElement(),dock=new UIElement(),content=new UIElement(),directory=new UIElement();private final ScrollerView history=new ScrollerView();
    private final Map<String,WorkspaceWindow> windows=new LinkedHashMap<>();private WorkspaceWindow chatWindow,filesWindow;private final Selector<Choice> agentChoice=new Selector<>();private final Map<String,String> knownAgents=new HashMap<>();
    private record Choice(String key,String label){@Override public String toString(){return label;}}
    private final TextElement status=label(""),heading=label("DivZero");private final TextArea composer=new TextArea();private final TextField search=new TextField();
    private final Map<String,MessageRow> rows=new LinkedHashMap<>();private final Map<String,Long> loading=new HashMap<>();
    private PanelSection pendingSection;private final UUID context=UUID.randomUUID();private String page="chat",agentSignature="";private long nextMessages,nextList;private boolean messagesBusy,listBusy,writing;private long nextBefore,listBefore;private int fileOffset;private float pinPixel=-1,anchorOffset;private UIElement anchor;private boolean pinBottom;private int restoreScrollFrames;private long nextLayoutSave;
    private record MessageRow(long sequence,UIElement root,TextElement text,TextElement thinking,Button thinkingButton){}
    private NativeWorkspaceScreen(){this(new UIElement());}
    private NativeWorkspaceScreen(UIElement root){
        super(new ModularUI(NativeUiTheme.ui(root),Minecraft.getInstance().player),Component.literal("DivZero"));this.root=root;composer.registerValueListener(value->saveDraft());
        root.getLayout().widthPercent(100).heightPercent(100).paddingAll(7);root.getStyle().backgroundTexture(new ColorRectTexture(0x35080d15));
        var toolbar=NativeUiTheme.card(row());toolbar.getLayout().height(36).paddingAll(6).marginBottom(5);toolbar.getStyle().zIndex(2000);root.addChild(toolbar);
        var brand=NativeUiTheme.text("DivZero",NativeUiTheme.ACCENT,13);brand.getLayout().width(72);toolbar.addChild(brand);
        toolbar.addChild(button(t("对话"),this::showChat));toolbar.addChild(button(t("AI 玩家"),()->WorkspacePanels.agents(this)));toolbar.addChild(button(t("包管理"),()->WorkspacePanels.packages(this)));toolbar.addChild(button(t("文件"),this::showFiles));
        var spacer=new UIElement();spacer.getLayout().flex(1);toolbar.addChild(spacer);toolbar.addChild(button(t("设置"),()->WorkspacePanels.settings(this)));toolbar.addChild(button("×",this::onClose));
        desktop.getLayout().flex(1).widthPercent(100);root.addChild(desktop);
        dock.getLayout().height(28).widthPercent(100).flexDirection(FlexDirection.ROW).paddingVertical(3);dock.getStyle().zIndex(2000);root.addChild(dock);
        status.getLayout().height(13).widthPercent(100);status.textStyle(style->style.fontSize(8).textColor(0xffffffff));root.addChild(status);
        search.textFieldStyle(style->style.placeholder(Component.literal(t("搜索对话"))));search.registerValueListener(value->{listBefore=0;nextList=System.currentTimeMillis()+350;});
        agentChoice.setOnValueChanged(choice->{if(choice!=null&&!choice.key().equals(model.agent))selectAgent(choice.key());});
        history.viewPort.getStyle().backgroundTexture(NativeUiTheme.inset());
        showChat();drawAgents();
    }
    WorkspaceWindow window(String id,String title,float width,float height){
        var old=windows.get(id);if(old!=null&&!old.closed()){old.reveal();return old;}
        var mc=Minecraft.getInstance();float w=mc.getWindow().getGuiScaledWidth(),h=mc.getWindow().getGuiScaledHeight();int cascade=windows.size()%5;
        var value=new WorkspaceWindow(desktop,dock,title,16+cascade*18,48+cascade*12,Math.max(240,Math.min(width,w-36)),Math.max(170,Math.min(height,h-105)),closed->{model.placements.put(id,closed.placement());windows.remove(id);saveAt=System.currentTimeMillis()+300;});windows.put(id,value);var placement=model.placements.get(id);if(placement!=null)value.dialog.overlay.addEventListener(com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents.LAYOUT_CHANGED,event->{value.restore(placement);event.currentElement.removeEventListener(com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents.LAYOUT_CHANGED,event.currentListener);});return value;
    }
    public static void openForAgent(String id,String name){open();active.knownAgents.put(id,name);active.conversationWith(id);}
    public static void openConversation(String agent,String name,String conversation){openForAgent(agent,name);active.select(conversation);}
    public static void openPackage(JsonObject item){open();WorkspacePanels.packageDetail(active,item);}
    boolean revealWindow(String id){var window=windows.get(id);if(window==null||window.closed())return false;window.reveal();return true;}
    void rememberAgent(String id,String name){knownAgents.put(id,name);}
    void conversationWith(String id){if(!knownAgents.containsKey(id))knownAgents.put(id,"AI");selectAgent(id);if(chatWindow!=null)chatWindow.reveal();}

    public static void openSection(PanelSection section){open();active.pendingSection=section;}
    public static void open(){
        var mc=Minecraft.getInstance();if(!net.neoforged.fml.ModList.get().isLoaded("ldlib2"))throw new IllegalStateException("LDLIB2_REQUIRED");
        if(connection!=mc.getConnection()||level!=mc.level){model=new Model();connection=mc.getConnection();level=mc.level;}
        if(mc.screen instanceof NativeWorkspaceScreen){return;}
        if(active!=null&&connection==mc.getConnection()&&level==mc.level){active.saveDraft();persistState();}
        active=new NativeWorkspaceScreen();mc.setScreen(active);NativeWorkspaceConnection.open();if(NativeWorkspaceConnection.ready())active.list();
    }
    public static void toggle(){if(Minecraft.getInstance().screen instanceof NativeWorkspaceScreen screen)screen.onClose();else open();}
    public static boolean visible(){return Minecraft.getInstance().screen instanceof NativeWorkspaceScreen;}
    public static void notice(String text){model.notice=text;if(active!=null)active.status.setText(Component.literal(text));}
    public static void sessionReady(){loadState();if(active!=null){active.nextMessages=0;active.list();}}
    public static void disconnected(){persistState();stateScope=null;model=new Model();active=null;connection=level=null;}
    public static void snapshot(Map<String,String> values){
        if(values.containsKey("agents"))model.agents=JsonParser.parseString(values.get("agents")).getAsJsonArray();
        if(active!=null)active.drawAgents();
    }
    public static void push(String channel,JsonElement data){if(active==null)return;if(channel.equals("conversationChanged")){active.nextMessages=0;active.nextList=System.currentTimeMillis()+100;}else if(channel.equals("buildingFilesOpen")){var value=data.getAsJsonObject();if(value.has("agentId")&&!value.get("agentId").getAsString().isBlank())model.agent=value.get("agentId").getAsString();active.showFiles();}else if(channel.equals("conversationSpeechDraft")){var value=data.getAsJsonObject();if(value.has("text")&&value.get("agentId").getAsString().equals(model.agent)&&value.get("conversationId").getAsString().equals(model.conversation)&&value.get("contextId").getAsString().equals(active.context.toString())){String draft=model.drafts.getOrDefault(active.draftKey(),"");model.drafts.put(active.draftKey(),draft+value.get("text").getAsString());model.speechOps.put(active.draftKey(),value.get("speechOperation").getAsString());active.composer.setValue(model.drafts.getOrDefault(active.draftKey(),"").split("\n",-1),false);saveAt=System.currentTimeMillis()+300;active.showChat();}}}
    private boolean current(){return active==this&&connection==Minecraft.getInstance().getConnection()&&level==Minecraft.getInstance().level;}
    private static String t(String text){return ClientLanguage.t(text);}
    private static TextElement label(String text){var label=new TextElement().setText(Component.literal(text));label.getLayout().widthPercent(100);label.textStyle(s->s.adaptiveWidth(false).adaptiveHeight(true).textWrap(TextWrap.WRAP).textColor(0xffeef2f7));return label;}
    private static UIElement row(){var row=new UIElement();row.getLayout().flexDirection(FlexDirection.ROW).widthPercent(100);return row;}
    private static Button button(String text,Runnable action){return NativeUiTheme.button(text,action);}
    private void panel(PanelSection section){switch(section){case AGENTS->WorkspacePanels.agents(this);case PACKAGES->WorkspacePanels.packages(this);case PERMISSIONS->WorkspacePanels.permissions(this);default->WorkspacePanels.settings(this);}}
    private void drawAgents(){
        String signature=model.agents+"|"+model.agent;if(signature.equals(agentSignature))return;agentSignature=signature;
        var choices=new ArrayList<Choice>();for(var raw:model.agents){var value=raw.getAsJsonObject();String id=value.get("id").getAsString(),name=value.get("name").getAsString();knownAgents.put(id,name);choices.add(new Choice(id,name));}
        if(!model.agent.isEmpty()&&choices.stream().noneMatch(c->c.key().equals(model.agent)))choices.add(new Choice(model.agent,knownAgents.getOrDefault(model.agent,"AI")));
        agentChoice.setCandidates(choices);if(model.agent.isEmpty()&&!choices.isEmpty()){selectAgent(choices.getFirst().key());return;}for(var choice:choices)if(choice.key().equals(model.agent)){agentChoice.setValue(choice,false);break;}
    }

    private void selectAgent(String id){saveDraft();releaseConversationFocus();model.agent=id;model.conversation="";model.selected=null;model.generation++;listBefore=0;rows.clear();history.clearAllScrollViewChildren();drawAgents();composer.setValue(draftText().split("\n",-1),false);heading.setText(Component.literal(t("选择或新建对话")));showChat();list();}
    private void showChat(){
        if(chatWindow!=null&&!chatWindow.closed()){chatWindow.reveal();return;}
        page="chat";chatWindow=window("chat",t("对话"),720,500);chatWindow.body.clearAllChildren();var body=row();body.getLayout().flex(1);chatWindow.body.addChild(body);
        directory.clearAllChildren();directory.getLayout().width(150).heightPercent(100).paddingRight(8);body.addChild(directory);directory.addChild(NativeUiTheme.text(t("AI 与会话"),NativeUiTheme.MUTED,8));agentChoice.getLayout().height(25).widthPercent(100);directory.addChild(agentChoice);
        directory.addChild(button(t("新建对话"),()->write("create",Map.of("title",t("新的对话"),"autoTitle","true"),state->{select(state.get("conversationId").getAsString());list();})));
        search.getLayout().height(24).widthPercent(100).marginTop(5);directory.addChild(search);
        var filter=new Selector<Choice>();filter.setCandidates(List.of(new Choice("ACTIVE",t("进行中")),new Choice("ARCHIVED",t("已归档")),new Choice("DELETED",t("已删除"))));filter.setValue(new Choice(model.filter,t(model.filter.equals("ACTIVE")?"进行中":model.filter.equals("ARCHIVED")?"已归档":"已删除")),false);filter.setOnValueChanged(choice->{model.filter=choice.key();listBefore=0;list();});filter.getLayout().height(23).widthPercent(100);directory.addChild(filter);
        conversationList=new ScrollerView();conversationList.getLayout().flex(1).widthPercent(100);directory.addChild(conversationList);
        content.clearAllChildren();content.getLayout().flex(1).heightPercent(100).paddingLeft(8);body.addChild(content);heading.setText(Component.literal(model.selected==null?t("选择或新建对话"):model.selected.get("title").getAsString()));heading.textStyle(style->style.fontSize(12).textColor(NativeUiTheme.TEXT));content.addChild(heading);
        var controls=row();controls.getLayout().height(26);controls.addChild(button(t("更早"),()->messages(nextBefore)));controls.addChild(button(t("最新"),()->{rows.clear();history.clearAllScrollViewChildren();messages(0);}));controls.addChild(button(t("重命名"),this::renameConversation));controls.addChild(button(t("归档"),()->changeConversation("archive")));controls.addChild(button(t("恢复"),()->changeConversation("restore")));content.addChild(controls);
        history.getLayout().flex(1).widthPercent(100).marginVertical(6);content.addChild(history);
        composer.getLayout().height(62).widthPercent(100);composer.setValue(draftText().split("\n",-1),false);content.addChild(composer);
        var actions=row();actions.getLayout().height(27).paddingTop(4);actions.addChild(button(t("发送"),this::send));actions.addChild(button(t("停止"),this::cancel));actions.addChild(button(t("附件"),this::showFiles));actions.addChild(button(t("语音输入"),()->{var session=NativeWorkspaceConnection.current();if(session!=null&&model.selected!=null)Minecraft.getInstance().setScreen(new NativeSpeechScreen(this,session.binding().worldId(),UUID.fromString(model.agent),UUID.fromString(model.conversation),context));}));content.addChild(actions);
        if(!model.agent.isEmpty()&&NativeWorkspaceConnection.ready())list();
    }
    private void renameConversation(){if(model.selected==null)return;Dialog.stringEditorDialog(t("重命名"),model.selected.get("title").getAsString(),value->!value.isBlank()&&value.length()<=128,value->write("rename",Map.of("title",value),state->{model.selected=state;heading.setText(Component.literal(state.get("title").getAsString()));list();})).show(root);}

    Map<String,Object> smokeState(){if(!Boolean.getBoolean("mineagent.nativeUiSmoke"))throw new IllegalStateException("SMOKE_DISABLED");return Map.of("agents",model.agents.size(),"agent",model.agent,"conversation",model.conversation,"selected",model.selected==null?"":model.selected.get("title").getAsString(),"draft",String.join("\n",composer.getValue()));}
    private ScrollerView conversationList;
    private String draftKey(){return model.agent+"/"+model.conversation;}
    private String draftText(){return model.drafts.getOrDefault(draftKey(),model.drafts.getOrDefault("legacy/"+model.conversation,""));}
    private void saveDraft(){if(!model.conversation.isEmpty()){model.drafts.put(draftKey(),String.join("\n",composer.getValue()));model.drafts.remove("legacy/"+model.conversation);saveAt=System.currentTimeMillis()+700;}}
    private static dev.mineagent.runtime.client.webui.UiStateStore stateStore(boolean legacy){return new dev.mineagent.runtime.client.webui.UiStateStore(Minecraft.getInstance().gameDirectory.toPath().resolve(legacy?"mineagent-runtime-data/ui-state":"mineagent-runtime-data/native-workspace-state"));}
    private static void loadState(){
        var session=NativeWorkspaceConnection.current();if(session==null)return;var mc=Minecraft.getInstance();var server=mc.getCurrentServer();String scope=(server==null?"integrated":server.ip)+"|"+session.binding().worldId()+"|"+session.binding().viewerPlayerId();if(scope.equals(stateScope))return;stateScope=scope;var target=model;var store=stateStore(false);var legacy=stateStore(true);
        CompletableFuture.supplyAsync(()->{try{var saved=JsonParser.parseString(store.load(scope)).getAsJsonObject();if(!saved.has("drafts")){var prior=JsonParser.parseString(legacy.load(scope)).getAsJsonObject();if(prior.has("conversationDrafts")){var old=prior.getAsJsonObject("conversationDrafts");var drafts=new JsonObject();if(old.has("drafts"))for(var e:old.getAsJsonObject("drafts").entrySet())drafts.add("legacy/"+e.getKey(),e.getValue());saved.add("drafts",drafts);if(old.has("requests"))saved.add("legacyRequests",old.get("requests"));}}return saved;}catch(Exception error){throw new java.util.concurrent.CompletionException(error);}},STATE_IO).whenComplete((saved,error)->mc.execute(()->{
            if(model!=target||!Objects.equals(stateScope,scope))return;if(error!=null){notice(t("草稿读取失败，当前输入仍保留。"));return;}
            if(saved.has("drafts"))for(var e:saved.getAsJsonObject("drafts").entrySet())if(e.getKey().length()<160&&e.getValue().isJsonPrimitive()&&e.getValue().getAsString().length()<=16384)model.drafts.putIfAbsent(e.getKey(),e.getValue().getAsString());
            if(saved.has("legacyRequests"))for(var e:saved.getAsJsonObject("legacyRequests").entrySet())try{var values=new LinkedHashMap<String,String>();for(var v:e.getValue().getAsJsonObject().entrySet())values.put(v.getKey(),v.getValue().getAsString());if(!e.getKey().equals(values.get("conversationId")))throw new IllegalArgumentException();model.sends.restoreLegacy(values);}catch(RuntimeException invalid){notice(t("待核对的发送记录无法读取，不会自动重发。"));}
            if(saved.has("windows"))try{for(var e:saved.getAsJsonObject("windows").entrySet()){var placement=JSON.fromJson(e.getValue(),WorkspaceWindow.Placement.class);model.placements.putIfAbsent(e.getKey(),placement);if(active!=null&&active.windows.containsKey(e.getKey()))active.windows.get(e.getKey()).restore(placement);}}catch(RuntimeException invalid){notice(t("窗口布局已重置。"));}
            if(saved.has("pending"))try{var values=JSON.fromJson(saved.get("pending"),new com.google.gson.reflect.TypeToken<List<dev.mineagent.runtime.client.conversation.PendingConversationSends.Pending>>(){}.getType());model.sends.restore((List<dev.mineagent.runtime.client.conversation.PendingConversationSends.Pending>)values);}catch(RuntimeException invalid){notice(t("待核对的发送记录无法读取，不会自动重发。"));}
            if(active!=null&&!model.conversation.isEmpty()&&String.join("\n",active.composer.getValue()).isEmpty())active.composer.setValue(active.draftText().split("\n",-1),false);
        }));
    }
    private static void persistState(){
        if(stateScope==null)return;String scope=stateScope;var store=stateStore(false);String source=JSON.toJson(Map.of("format",1,"drafts",Map.copyOf(model.drafts),"pending",model.sends.snapshot(),"windows",Map.copyOf(model.placements)));saveAt=0;
        CompletableFuture.runAsync(()->{try{store.save(scope,source);}catch(Exception error){throw new java.util.concurrent.CompletionException(error);}},STATE_IO).exceptionally(error->{Minecraft.getInstance().execute(()->{if(scope.equals(stateScope))notice(t("草稿保存失败，当前输入仍保留。"));});return null;});
    }
    private CompletableFuture<JsonObject> request(boolean write,String kind,Map<String,String> extra){
        if(model.agent.isEmpty())return CompletableFuture.failedFuture(new IllegalStateException(t("选择 AI")));
        var args=new LinkedHashMap<String,String>(extra);args.put("kind",kind);args.put("agentId",model.agent);if(!model.conversation.isEmpty())args.putIfAbsent("conversationId",model.conversation);
        if(write&&model.selected!=null)args.putIfAbsent("expectedRevision",model.selected.get("revision").getAsString());
        return NativeWorkspaceConnection.command(write?"conversation.write":"conversation.read",args,UUID.randomUUID()).thenApply(receipt->{
            if(!Set.of(Code.OBSERVED,Code.APPLIED,Code.ACCEPTED).contains(receipt.code())||receipt.values().containsKey("errorCode"))throw new IllegalStateException(receipt.values().getOrDefault("errorCode",receipt.code().name()));
            return JsonParser.parseString(receipt.values().getOrDefault("state","{}")).getAsJsonObject();
        });
    }
    private void write(String kind,Map<String,String> extra,java.util.function.Consumer<JsonObject> done){
        if(writing)return;writing=true;long generation=model.generation;request(true,kind,extra).whenComplete((state,error)->{writing=false;if(!current()||generation!=model.generation)return;if(error!=null){notice(error.getMessage());return;}done.accept(state);nextMessages=0;});
    }
    private void list(){
        if(!current()||!page.equals("chat")||model.agent.isEmpty()||!NativeWorkspaceConnection.ready()||listBusy)return;
        listBusy=true;long generation=model.generation;request(false,"list",Map.of("state",model.filter,"search",search.getValue(),"before",Long.toString(listBefore))).whenComplete((state,error)->{
            listBusy=false;if(!current())return;if(generation!=model.generation){nextList=System.currentTimeMillis()+100;return;}if(!page.equals("chat"))return;if(error!=null){notice(error.getMessage());return;}
            conversationList.clearAllScrollViewChildren();for(var item:state.getAsJsonArray("conversations")){var conversation=item.getAsJsonObject();String id=conversation.get("conversationId").getAsString();var select=button((id.equals(model.conversation)?"● ":"")+conversation.get("title").getAsString(),()->select(id));select.getLayout().widthPercent(100);conversationList.addScrollViewChild(select);}
            long next=state.get("nextBefore").getAsLong();if(next>0)conversationList.addScrollViewChild(button(t("下一页"),()->{listBefore=next;list();}));
        });
    }
    private void select(String id){saveDraft();releaseConversationFocus();model.conversation=id;model.generation++;rows.clear();loading.clear();history.clearAllScrollViewChildren();pinBottom=true;anchor=null;pinPixel=-1;model.selected=null;composer.setValue(draftText().split("\n",-1),false);nextMessages=0;messages(0);request(true,"focus",Map.of("contextId",context.toString())).thenAccept(f->{var session=NativeWorkspaceConnection.current();if(session!=null&&current())dev.mineagent.runtime.neoforge.client.audio.ConversationVoicePlayback.focus(session.binding().worldId(),UUID.fromString(model.agent),UUID.fromString(model.conversation),context);}).exceptionally(error->{notice(error.getMessage());return null;});}
    private void releaseConversationFocus(){dev.mineagent.runtime.neoforge.client.audio.ConversationVoicePlayback.clear(context);if(model.conversation.isEmpty()||!NativeWorkspaceConnection.ready())return;request(true,"unfocus",Map.of("contextId",context.toString()));}
    private void changeConversation(String kind){if(model.selected==null)return;write(kind,Map.of(),state->{model.selected=state;list();});}
    private void send(){
        String text=String.join("\n",composer.getValue());if(writing||text.isBlank()||model.selected==null)return;saveDraft();String key=draftKey();var ownerModel=model;
        var pending=model.sends.prepare(UUID.fromString(model.agent),UUID.fromString(model.conversation),model.selected.get("revision").getAsLong(),text,model.speechOps.get(key));persistState();writing=true;
        NativeWorkspaceConnection.command("conversation.write",pending.arguments(),pending.operation()).whenComplete((receipt,error)->{
            writing=false;if(error!=null||!Set.of(Code.APPLIED,Code.ACCEPTED).contains(receipt.code())||!receipt.values().getOrDefault("errorCode","").isBlank()){if(current())notice(error==null?receipt.values().getOrDefault("errorCode",receipt.code().name()):error.getMessage());return;}
            ownerModel.sends.accepted(pending.operation());if(ownerModel.drafts.getOrDefault(key,"").equals(text)){ownerModel.drafts.put(key,"");ownerModel.speechOps.remove(key);}
            if(model!=ownerModel)return;persistState();if(!current()||!key.equals(draftKey()))return;model.selected=JsonParser.parseString(receipt.values().get("state")).getAsJsonObject();composer.setValue(draftText().split("\n",-1),false);nextMessages=0;messages(0);
        });
    }

    private void cancel(){if(model.selected==null)return;String target=model.selected.has("activeOperation")?model.selected.get("activeOperation").getAsString():"";if(!target.isEmpty())write("cancel",Map.of("targetOperation",target),state->model.selected=state);}
    private void messages(long before){
        if(!current()||model.conversation.isEmpty()||messagesBusy||!page.equals("chat"))return;messagesBusy=true;long generation=model.generation;String conversation=model.conversation;
        request(false,"messages",Map.of("before",Long.toString(before))).whenComplete((state,error)->{
            messagesBusy=false;if(!current()||generation!=model.generation||!conversation.equals(model.conversation))return;if(error!=null){notice(error.getMessage());return;}
            model.selected=state.getAsJsonObject("conversation");heading.setText(Component.literal(model.selected.get("title").getAsString()));nextBefore=state.get("nextBefore").getAsLong();boolean changed=false;
            var thinking=new HashMap<String,JsonObject>();if(state.has("thinking"))for(var v:state.getAsJsonArray("thinking")){var q=v.getAsJsonObject();thinking.put(q.get("messageId").getAsString(),q);}
            for(var item:state.getAsJsonArray("messages")){
                var message=item.getAsJsonObject();String id=message.get("messageId").getAsString();long revision=message.get("revision").getAsLong();var entry=rows.get(id);
                if(entry==null){var container=new UIElement();container.getLayout().widthPercent(100).paddingAll(5).marginBottom(4);container.getStyle().backgroundTexture(NativeUiTheme.inset());container.addChild(label(message.get("role").getAsString().equals("USER")?t("你"):"AI"));var body=label("");container.addChild(body);var thought=label("");thought.setDisplay(false);var toggle=button(t("思考"),()->thought.setDisplay(!thought.isDisplayed()));container.addChild(toggle);container.addChild(thought);if(!message.get("role").getAsString().equals("USER"))container.addChild(button(t("朗读"),()->write("voice",Map.of("messageId",id,"contextId",context.toString()),voice->notice(t("正在处理…")))));entry=new MessageRow(message.get("sequence").getAsLong(),container,body,thought,toggle);rows.put(id,entry);changed=true;}
                var th=thinking.get(id);entry.thinkingButton.setDisplay(th!=null&&th.get("textLength").getAsInt()>0);if(th!=null&&entry.thinking.isDisplayed()&&!Objects.equals(loading.get("thinking:"+id),th.get("revision").getAsLong())){loading.put("thinking:"+id,th.get("revision").getAsLong());textChunk(id,th.get("revision").getAsLong(),"thinking",entry.thinking,generation,0,new StringBuilder());}
                if(!Objects.equals(loading.get(id),revision)){loading.put(id,revision);textChunk(id,revision,"message",entry.text,generation,0,new StringBuilder());}
            }
            if(changed){captureScroll();history.clearAllScrollViewChildren();rows.values().stream().sorted(Comparator.comparingLong(MessageRow::sequence)).forEach(row->history.addScrollViewChild(row.root));restoreScrollFrames=3;}
        });
    }
    private void textChunk(String id,long revision,String kind,TextElement target,long generation,int offset,StringBuilder text){
        request(false,kind,Map.of("messageId",id,"messageRevision",Long.toString(revision),"offset",Integer.toString(offset))).whenComplete((chunk,error)->{
            String loadKey=kind.equals("thinking")?"thinking:"+id:id;if(!current()||generation!=model.generation||!Objects.equals(loading.get(loadKey),revision))return;if(error!=null){loading.remove(loadKey);return;}
            if(chunk.get("revision").getAsLong()!=revision){loading.remove(kind.equals("thinking")?"thinking:"+id:id);return;}String part=chunk.get("text").getAsString();text.append(part);int next=offset+part.length();if(next<chunk.get("total").getAsInt()&&!part.isEmpty())textChunk(id,revision,kind,target,generation,next,text);else if(next==chunk.get("total").getAsInt()){captureScroll();target.setText(Component.literal(text.toString()));restoreScrollFrames=3;}else loading.remove(loadKey);
        });
    }
    private void showFiles(){
        saveDraft();if(filesWindow!=null&&!filesWindow.closed()){filesWindow.reveal();return;}filesWindow=window("files",t("文件"),480,365);var fileBody=filesWindow.body;fileBody.clearAllChildren();fileOffset=0;
        var actions=row();actions.getLayout().height(26);fileBody.addChild(actions);actions.addChild(button(t("上传文件"),()->fileAction("upload",Map.of())));actions.addChild(button(t("上传文件夹"),()->fileAction("folder",Map.of())));actions.addChild(button(t("停止"),()->fileAction("cancel",Map.of())));actions.addChild(button(t("刷新"),this::files));
        fileRows=new ScrollerView();fileRows.getLayout().flex(1).widthPercent(100);fileBody.addChild(fileRows);var pager=row();pager.getLayout().height(26);pager.addChild(button(t("上一页"),()->{fileOffset=Math.max(0,fileOffset-32);files();}));pager.addChild(button(t("下一页"),()->{fileOffset+=32;files();}));fileBody.addChild(pager);files();
    }

    private ScrollerView fileRows;private long nextFilePoll;private boolean transferWasBusy;
    private CompletableFuture<JsonObject> fileRequest(String kind,Map<String,Object> extra){var data=new LinkedHashMap<String,Object>(extra);data.put("kind",kind);data.put("agentId",model.agent);return BuildingFilesClient.handle(JSON.toJsonTree(data).getAsJsonObject()).thenApply(value->JSON.toJsonTree(value).getAsJsonObject());}
    private void fileAction(String kind,Map<String,Object> extra){fileRequest(kind,extra).whenComplete((value,error)->Minecraft.getInstance().execute(()->{if(!current())return;if(error!=null)notice(error.getMessage());else{notice(t("正在处理…"));nextFilePoll=0;transferWasBusy=true;}}));}
    private void files(){fileRequest("list",Map.of("query","","offset",fileOffset)).whenComplete((data,error)->Minecraft.getInstance().execute(()->{if(!current()||(filesWindow==null||filesWindow.closed()))return;if(error!=null){notice(error.getMessage());return;}fileRows.clearAllScrollViewChildren();for(var item:data.getAsJsonArray("files")){var file=item.getAsJsonObject();String id=file.get("id").getAsString();var line=new UIElement();line.getLayout().widthPercent(100).paddingAll(5);line.addChild(label(file.get("name").getAsString()));var actions=row();actions.getLayout().height(23);actions.addChild(button(t("交给 AI"),()->fileRequest("select",Map.of("fileId",id)).whenComplete((value,failure)->Minecraft.getInstance().execute(()->{if(failure!=null)notice(failure.getMessage());else if(value.has("status")&&value.get("status").getAsString().equals("SENT_TO_AI"))notice(t("AI 已收到文件，继续处理。"));else if(!model.conversation.isEmpty()){String reference="\n["+file.get("name").getAsString()+" file_id="+id+"]";String key=draftKey();String draft=model.drafts.getOrDefault(key,"");if(!draft.contains("file_id="+id))model.drafts.put(key,draft+reference);composer.setValue(model.drafts.getOrDefault(key,"").split("\n",-1),false);notice(t("已加入对话草稿，填写需求后发送。"));}else notice(t("选择或新建对话"));}))));actions.addChild(button(t("下载"),()->fileAction("download",Map.of("fileId",id))));line.addChild(actions);fileRows.addScrollViewChild(line);}}));}
    @Override public void tick(){super.tick();NativeUiTheme.controls(root);if(!current())return;long now=System.currentTimeMillis();if(pendingSection!=null&&NativeWorkspaceConnection.ready()){var section=pendingSection;pendingSection=null;panel(section);}if(saveAt>0&&now>=saveAt)persistState();if(nextList>0&&now>=nextList){nextList=0;list();}if(page.equals("chat")&&NativeWorkspaceConnection.ready()&&now>=nextMessages){nextMessages=now+700;messages(0);}restoreScroll();if(now>=nextLayoutSave){nextLayoutSave=now+1000;for(var entry:windows.entrySet()){var value=entry.getValue().placement();if(value.width()>0&&value.height()>0&&!value.equals(model.placements.put(entry.getKey(),value)))saveAt=now+300;}}if(filesWindow!=null&&filesWindow.visible()&&now>=nextFilePoll){nextFilePoll=now+700;fileRequest("status",Map.of()).thenAccept(data->Minecraft.getInstance().execute(()->{if(!current())return;notice(data.get("status").getAsString()+(data.get("error").getAsString().isEmpty()?"":" · "+data.get("error").getAsString()));boolean busy=data.get("busy").getAsBoolean();if(transferWasBusy&&!busy)files();transferWasBusy=busy;}));}}
    private void captureScroll(){
        if(restoreScrollFrames>0)return;
        float range=Math.max(0,history.getContainerHeight()-history.viewPort.getContentHeight());
        pinPixel=history.verticalScroller.getNormalizedValue()*range;pinBottom=range<1||range-pinPixel<5;anchor=null;
        if(!pinBottom)for(var row:rows.values().stream().sorted(Comparator.comparingLong(MessageRow::sequence)).toList())if(row.root.getPositionY()+row.root.getSizeHeight()>history.viewPort.getContentY()){anchor=row.root;anchorOffset=anchor.getPositionY()-history.viewPort.getContentY();break;}
    }
    private void restoreScroll(){
        if(restoreScrollFrames<=0)return;
        float range=Math.max(0,history.getContainerHeight()-history.viewPort.getContentHeight());
        if(pinBottom)history.verticalScroller.setNormalizedValue(1);else if(range>0){float current=history.verticalScroller.getNormalizedValue()*range;float target=anchor==null?pinPixel:current+anchor.getPositionY()-history.viewPort.getContentY()-anchorOffset;history.verticalScroller.setNormalizedValue(Math.clamp(target/range,0,1));}
        restoreScrollFrames--;
    }
    @Override public void removed(){if(active!=this){super.removed();return;}saveDraft();for(var entry:windows.entrySet()){var value=entry.getValue().placement();if(value.width()>0&&value.height()>0)model.placements.put(entry.getKey(),value);}persistState();super.removed();}
    @Override public void onClose(){saveDraft();persistState();releaseConversationFocus();super.onClose();}
}
