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
public final class NativeWorkspaceScreen extends NativeInputScreen {
    private static final Gson JSON=new Gson();
    private static final class Model {
        String agent="",conversation="",filter="ACTIVE",notice="";JsonObject selected;
        final dev.mineagent.runtime.client.conversation.PendingConversationSends sends=new dev.mineagent.runtime.client.conversation.PendingConversationSends();
        JsonArray agents=new JsonArray();final Map<String,String> drafts=new HashMap<>(),speechOps=new HashMap<>();final Map<String,WorkspaceWindow.Placement> placements=new HashMap<>();long generation;
    }
    private static final java.util.concurrent.ExecutorService STATE_IO=java.util.concurrent.Executors.newSingleThreadExecutor(Thread.ofVirtual().name("native-workspace-state").factory());
    private static String stateScope;private static long saveAt;private static Model model=new Model();private static NativeWorkspaceScreen active;private static Object connection,level;
    private final boolean compact=Minecraft.getInstance().getWindow().getGuiScaledWidth()<540;
    private final UIElement root,desktop=new UIElement(),dock=new UIElement(),content=new UIElement(),directory=new UIElement();private final ScrollerView history=new ScrollerView();
    private final Map<String,WorkspaceWindow> windows=new LinkedHashMap<>();private WorkspaceWindow chatWindow,filesWindow;private final Selector<Choice> agentChoice=new Selector<>();private final Map<String,String> knownAgents=new HashMap<>();
    private record Choice(String key,String label){@Override public String toString(){return label;}}
    private final TextElement status=label(""),heading=label("DivZero");private final TextArea composer=new TextArea();private final TextField search=new TextField();
    private final Button decisions=button(NativeDecisionPanel.label(),()->NativeDecisionPanel.open(this));
    private final Map<String,MessageRow> rows=new LinkedHashMap<>();private final Map<String,Long> loading=new HashMap<>();
    private final Map<String,Button> conversationButtons=new LinkedHashMap<>();private final Map<String,String> conversationTitles=new LinkedHashMap<>();
    private long messagesGeneration=-1,listGeneration=-1;
    private long renderedListNext=-1;
    private long renderedListBefore=-1;private final Deque<Long> listPages=new ArrayDeque<>();private final UIElement conversationPager=row();
    private PanelSection pendingSection;private final UUID context=UUID.randomUUID();private String page="chat",agentSignature="";private long nextMessages,nextList;private boolean messagesBusy,listBusy,writing;private long nextBefore,listBefore;private int fileOffset;private float pinPixel=-1,anchorOffset;private UIElement anchor;private boolean pinBottom;private int restoreScrollFrames;private long nextLayoutSave;
    private record MessageRow(long sequence,UIElement root,TextElement text,TextElement thinking,Button thinkingButton,MessageActions actions){}
    private NativeWorkspaceScreen(){this(new UIElement());}
    private NativeWorkspaceScreen(UIElement root){
        super(new ModularUI(NativeUiTheme.ui(root),Minecraft.getInstance().player),Component.literal("DivZero"));this.root=root;composer.setId("conversation-composer");composer.registerValueListener(value->saveDraft());
        root.getLayout().widthPercent(100).heightPercent(100).paddingAll(compact?4:7);root.getStyle().backgroundTexture(new ColorRectTexture(0x35080d15));
        var toolbar=NativeUiTheme.card(row());toolbar.getLayout().height(compact?29:36).flexShrink(0).paddingAll(compact?3:6).marginBottom(3);toolbar.getStyle().zIndex(2000);root.addChild(toolbar);
        var brand=NativeUiTheme.text("DivZero",NativeUiTheme.ACCENT,13);brand.getLayout().width(compact?39:72).flexShrink(0);if(compact)brand.textStyle(style->style.fontSize(9));toolbar.addChild(brand);
        toolbar.addChild(button(t("对话"),this::showChat));toolbar.addChild(button(t("AI 玩家"),()->WorkspacePanels.agents(this)));toolbar.addChild(button(t("包管理"),()->WorkspacePanels.packages(this)));toolbar.addChild(button(t("文件"),this::showFiles));toolbar.addChild(button(t(compact?"建筑":"建筑计划"),()->NativeBuildingPanel.open(this,model.agent)));
        var spacer=new UIElement();spacer.getLayout().flex(1);toolbar.addChild(spacer);toolbar.addChild(decisions);toolbar.addChild(button(t("设置"),()->WorkspacePanels.settings(this)));toolbar.addChild(button(t("尺寸"),this::scaleSettings));toolbar.addChild(NativeUiTheme.iconButton("×",this::onClose));
        desktop.getLayout().flex(1).minHeight(0).widthPercent(100);root.addChild(desktop);
        dock.getLayout().height(compact?23:28).flexShrink(0).widthPercent(100).flexDirection(FlexDirection.ROW).paddingVertical(3);dock.getStyle().zIndex(2000);root.addChild(dock);
        status.setId("workspace-status");status.getLayout().height(11).flexShrink(0).widthPercent(100);status.textStyle(style->style.fontSize(8).textColor(0xffffffff));root.addChild(status);
        search.textFieldStyle(style->style.placeholder(Component.literal(t("搜索对话"))));search.registerValueListener(value->{listBefore=0;listPages.clear();nextList=System.currentTimeMillis()+350;});
        agentChoice.setOnValueChanged(choice->{if(choice!=null&&!choice.key().equals(model.agent))selectAgent(choice.key());});
        history.viewPort.getStyle().backgroundTexture(NativeUiTheme.inset());
        var frameWitness=new UIElement(){@Override protected void drawBackgroundAdditional(com.lowdragmc.lowdraglib2.gui.ui.rendering.IGUIContext context){NativePackageViews.workspacePainted(NativeWorkspaceScreen.this);}};
        frameWitness.getLayout().positionType(dev.vfyjxf.taffy.style.TaffyPosition.ABSOLUTE).left(0).top(0).width(1).height(1);frameWitness.setActive(false);root.addChild(frameWitness);
        if(compact)for(var child:toolbar.getChildren())if(child instanceof Button button){button.getLayout().height(21).minHeight(21).paddingHorizontal(3).marginRight(2);button.text.textStyle(style->style.fontSize(8));}
        showChat();drawAgents();
    }
    WorkspaceWindow window(String id,String title,float width,float height){
        var old=windows.get(id);if(old!=null&&!old.closed()){old.reveal();return old;}
        var mc=Minecraft.getInstance();float w=mc.getWindow().getGuiScaledWidth(),h=mc.getWindow().getGuiScaledHeight();int cascade=windows.size()%5;
        var value=new WorkspaceWindow(desktop,dock,title,compact?0:16+cascade*18,compact?0:48+cascade*12,Math.max(240,Math.min(width,w-(compact?8:36))),Math.max(150,Math.min(height,h-(compact?65:105))),closed->{model.placements.put(id,closed.placement());windows.remove(id);saveAt=System.currentTimeMillis()+300;});windows.put(id,value);var placement=model.placements.get(id);if(placement!=null)value.dialog.overlay.addEventListener(com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents.LAYOUT_CHANGED,event->{value.restore(placement);event.currentElement.removeEventListener(com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents.LAYOUT_CHANGED,event.currentListener);});return value;
    }
    public static void openForAgent(String id,String name){open();if(!name.equals("AI")||!active.knownAgents.containsKey(id))active.knownAgents.put(id,name);active.conversationWith(id);}
    public static void openConversation(String agent,String name,String conversation){openForAgent(agent,name);active.select(conversation);}
    static NativeWorkspaceScreen previewHost(){open();return active;}
    public static void openPackage(JsonObject item){open();WorkspacePanels.packageDetail(active,item);}
    boolean activeContext(){return current();}
    String selectedAgentId(){return model.agent;}
    boolean revealWindow(String id){var window=windows.get(id);if(window==null||window.closed())return false;window.reveal();return true;}
    String agentName(String id){return knownAgents.getOrDefault(id,t("AI 玩家"));}
    void rememberAgent(String id,String name){knownAgents.put(id,name);}
    void conversationWith(String id){if(!knownAgents.containsKey(id))knownAgents.put(id,"AI");if(!id.equals(model.agent))selectAgent(id);showChat();list();}

    public static void openSection(PanelSection section){open();active.pendingSection=section;}
    public static void open(){
        var mc=Minecraft.getInstance();if(!net.neoforged.fml.ModList.get().isLoaded("ldlib2"))throw new IllegalStateException("LDLIB2_REQUIRED");
        if(connection!=mc.getConnection()||level!=mc.level){model=new Model();connection=mc.getConnection();level=mc.level;}
        if(mc.screen instanceof NativeWorkspaceScreen){return;}
        if(active!=null&&connection==mc.getConnection()&&level==mc.level){active.saveDraft();persistState();}
        active=new NativeWorkspaceScreen();mc.setScreen(active);NativePackageViews.workspaceOpened(active);NativeWorkspaceConnection.open();if(NativeWorkspaceConnection.ready())active.list();
    }
    public static void toggle(){if(!dev.mineagent.runtime.neoforge.client.MineAgentClientTrustPrompt.enabled()){Minecraft.getInstance().setScreen(new net.minecraft.client.gui.screens.ChatScreen("",false));dev.mineagent.runtime.neoforge.client.MineAgentClientTrustPrompt.showChoice(true);return;}if(Minecraft.getInstance().screen instanceof NativeWorkspaceScreen screen)screen.onClose();else open();}
    public static boolean visible(){return Minecraft.getInstance().screen instanceof NativeWorkspaceScreen;}
    public static void notice(String text){model.notice=text;if(active!=null)active.status.setText(Component.literal(text));}
    public static void sessionReady(){loadState();NativeDecisionPanel.sessionReady();if(active!=null){active.nextMessages=0;active.list();active.focusConversation();}}
    public static void disconnected(){persistState();NativeDecisionPanel.reset();stateScope=null;model=new Model();active=null;connection=level=null;}
    public static void snapshot(Map<String,String> values){
        NativeDecisionPanel.snapshot(values);if(active!=null)active.decisions.setText(Component.literal(NativeDecisionPanel.label()));
        if(values.containsKey("agents"))model.agents=JsonParser.parseString(values.get("agents")).getAsJsonArray();
        if(active!=null)active.drawAgents();
    }
    public static void push(String channel,JsonElement data){if(channel.equals("deliveryInboxChanged"))NativeDeliveryPanel.changed();if(active==null)return;if(channel.equals("conversationChanged")){active.nextMessages=0;active.nextList=System.currentTimeMillis()+100;}else if(channel.equals("skinUiOpen")){NativeAppearancePanel.open(active,data.getAsJsonObject().get("agentId").getAsString());}else if(channel.equals("buildingFilesOpen")){var value=data.getAsJsonObject();if(value.has("agentId")&&!value.get("agentId").getAsString().isBlank())model.agent=value.get("agentId").getAsString();active.showFiles();}else if(channel.equals("conversationSpeechDraft")){var value=data.getAsJsonObject();if(value.has("text")&&value.get("agentId").getAsString().equals(model.agent)&&value.get("conversationId").getAsString().equals(model.conversation)&&value.get("contextId").getAsString().equals(active.context.toString())){String draft=model.drafts.getOrDefault(active.draftKey(),"");model.drafts.put(active.draftKey(),draft+value.get("text").getAsString());model.speechOps.put(active.draftKey(),value.get("speechOperation").getAsString());active.composer.setValue(model.drafts.getOrDefault(active.draftKey(),"").split("\n",-1),false);saveAt=System.currentTimeMillis()+300;active.showChat();}}}
    private boolean current(){return active==this&&connection==Minecraft.getInstance().getConnection()&&level==Minecraft.getInstance().level;}
    private static String t(String text){return ClientLanguage.t(text);}
    private static TextElement label(String text){var label=new TextElement().setText(Component.literal(text));label.addClass("divzero-flow-text");label.getLayout().widthPercent(100);label.textStyle(s->s.adaptiveWidth(false).adaptiveHeight(true).textWrap(TextWrap.WRAP).textColor(0xffeef2f7));return label;}
    private static UIElement row(){return WorkspacePanels.row();}
    private static Button button(String text,Runnable action){return NativeUiTheme.button(text,action);}
    private void panel(PanelSection section){switch(section){case AGENTS->WorkspacePanels.agents(this);case PACKAGES->WorkspacePanels.packages(this);case PERMISSIONS->WorkspacePanels.permissions(this);default->WorkspacePanels.settings(this);}}
    private void drawAgents(){
        String signature=model.agents+"|"+model.agent;if(signature.equals(agentSignature))return;agentSignature=signature;
        var choices=new ArrayList<Choice>();for(var raw:model.agents){var value=raw.getAsJsonObject();String id=value.get("id").getAsString(),name=value.get("name").getAsString();knownAgents.put(id,name);choices.add(new Choice(id,name));}
        if(!model.agent.isEmpty()&&choices.stream().noneMatch(c->c.key().equals(model.agent)))choices.add(new Choice(model.agent,knownAgents.getOrDefault(model.agent,"AI")));
        agentChoice.setCandidates(choices);if(model.agent.isEmpty()&&!choices.isEmpty()){selectAgent(choices.getFirst().key(),false);return;}for(var choice:choices)if(choice.key().equals(model.agent)){agentChoice.setValue(choice,false);break;}
    }

    private void selectAgent(String id){selectAgent(id,true);}
    private void selectAgent(String id,boolean bringChatForward){saveDraft();releaseConversationFocus();model.agent=id;model.conversation="";model.selected=null;model.generation++;listBefore=0;listPages.clear();conversationButtons.clear();conversationTitles.clear();conversationPager.clearAllChildren();if(conversationList!=null)conversationList.clearAllScrollViewChildren();renderedListNext=renderedListBefore=-1;rows.clear();history.clearAllScrollViewChildren();drawAgents();composer.setValue(draftText().split("\n",-1),false);heading.setText(Component.literal(t("选择或新建对话")));if(bringChatForward||chatWindow==null||chatWindow.closed())showChat();list();}
    private void showChat(){
        page="chat";nextMessages=0;
        if(chatWindow!=null&&!chatWindow.closed()){chatWindow.reveal();return;}
        page="chat";chatWindow=window("chat",t("对话"),720,500);chatWindow.body.clearAllChildren();var body=row();body.getLayout().flex(1).minHeight(0).minWidth(0);chatWindow.body.addChild(body);
        directory.clearAllChildren();directory.setDisplay(!compact);directory.getLayout().width(compact?110:150).minWidth(compact?110:150).flexShrink(0).heightPercent(100).paddingRight(8);body.addChild(directory);directory.addChild(NativeUiTheme.text(t("AI 与会话"),NativeUiTheme.MUTED,8));agentChoice.getLayout().height(25).widthPercent(100);directory.addChild(agentChoice);
        directory.addChild(button(t("新建对话"),()->write("create",Map.of("title",t("新的对话"),"autoTitle","true"),state->{select(state.get("conversationId").getAsString());list();})));
        search.getLayout().height(24).widthPercent(100).marginTop(5);directory.addChild(search);
        var filter=new Selector<Choice>();filter.setCandidates(List.of(new Choice("ACTIVE",t("进行中")),new Choice("ARCHIVED",t("已归档")),new Choice("DELETED",t("已删除"))));filter.setValue(new Choice(model.filter,t(model.filter.equals("ACTIVE")?"进行中":model.filter.equals("ARCHIVED")?"已归档":"已删除")),false);filter.setOnValueChanged(choice->{model.filter=choice.key();listBefore=0;listPages.clear();list();});filter.getLayout().height(23).widthPercent(100);directory.addChild(filter);
        conversationButtons.clear();conversationTitles.clear();renderedListNext=renderedListBefore=-1;conversationList=new ScrollerView();conversationList.getLayout().flex(1).widthPercent(100);directory.addChild(conversationList);conversationPager.clearAllChildren();conversationPager.getLayout().height(25);directory.addChild(conversationPager);
        content.clearAllChildren();content.getLayout().flex(1).minWidth(0).minHeight(0).heightPercent(100).paddingLeft(compact?2:8);body.addChild(content);heading.setText(Component.literal(model.selected==null?t("选择或新建对话"):model.selected.get("title").getAsString()));heading.getLayout().height(compact?13:18).flexShrink(0);heading.textStyle(style->style.fontSize(compact?9:12).textColor(NativeUiTheme.TEXT).textShadow(false));content.addChild(heading);
        var controls=row();controls.getLayout().height(compact?22:26).flexShrink(0);if(compact)controls.addChild(button(t("会话"),()->directory.setDisplay(!directory.isDisplayed())));controls.addChild(button(t("更早"),()->messages(nextBefore)));controls.addChild(button(t("最新"),()->{rows.clear();history.clearAllScrollViewChildren();messages(0);}));controls.addChild(button(t("重命名"),this::renameConversation));controls.addChild(button(t("更多"),this::conversationOptions));content.addChild(controls);
        history.getLayout().flex(1).minHeight(24).widthPercent(100).marginVertical(compact?2:6);content.addChild(history);
        composer.getLayout().height(compact?38:62).minHeight(compact?38:62).flexShrink(0).widthPercent(100);composer.setValue(draftText().split("\n",-1),false);content.addChild(composer);
        var actions=row();actions.getLayout().height(compact?24:27).flexShrink(0).paddingTop(compact?1:4);actions.addChild(button(t("发送"),this::send));actions.addChild(button(t("停止"),this::cancel));actions.addChild(button(t("附件"),this::showFiles));actions.addChild(button(t("语音输入"),()->{var session=NativeWorkspaceConnection.current();if(session!=null&&model.selected!=null)Minecraft.getInstance().setScreen(new NativeSpeechScreen(this,session.binding().worldId(),UUID.fromString(model.agent),UUID.fromString(model.conversation),context));}));content.addChild(actions);
        if(!model.agent.isEmpty()&&NativeWorkspaceConnection.ready())list();
    }
    private void scaleSettings(){
        var panel=window("gui-scale",t("界面尺寸"),260,170);panel.body.clearAllChildren();
        panel.body.addChild(NativeUiTheme.text(t("游戏 UI 尺寸"),NativeUiTheme.TEXT,10));var choices=row();panel.body.addChild(choices);
        for(int size=2;size<=4;size++){final int scale=size;var button=button(Integer.toString(size),()->changeScale(scale));button.setId("gui-scale-"+size);choices.addChild(button);}
    }
    void changeScale(int scale){
        if(scale<2||scale>4)throw new IllegalArgumentException("GUI_SCALE");saveDraft();persistState();
        var mc=Minecraft.getInstance();mc.options.guiScale().set(scale);mc.options.save();mc.resizeGui();
        // Rebuild responsive geometry once, retaining the model and draft rather than on each tick.
        String selected=model.conversation;active=new NativeWorkspaceScreen();mc.setScreen(active);if(!selected.isBlank())active.messages(0);
    }
    private void conversationOptions(){
        if(model.selected==null||model.conversation.isBlank())return;String selected=model.conversation,agent=model.agent;var window=window("conversation-options-"+selected,t("会话操作"),365,270);window.body.clearAllChildren();
        window.body.addChild(button(t("摘要、来源与预算"),()->NativeConversationExtras.open(this,agent,selected)));window.body.addChild(button(t("导入旧审计片段"),()->NativeConversationExtras.imports(this,agent)));
        String state=model.selected.get("state").getAsString();if(state.equals("ACTIVE"))window.body.addChild(button(t("归档"),()->{if(model.conversation.equals(selected))changeConversation("archive");window.close();}));else window.body.addChild(button(t("恢复"),()->{if(model.conversation.equals(selected))changeConversation("restore");window.close();}));
        window.body.addChild(button(t("将普通聊天关联到此会话"),()->{if(model.conversation.equals(selected))write("route",Map.of("contextId",context.toString(),"enabled","true"),result->notice(t("普通聊天已关联到此会话。")));}));
        window.body.addChild(button(t("解除普通聊天关联"),()->{if(model.conversation.equals(selected))write("route",Map.of("contextId",context.toString(),"enabled","false"),result->notice(t("已解除普通聊天关联。")));}));
        window.body.addChild(button(t("删除会话"),()->Dialog.showCheckBox(t("删除会话"),t("删除所选会话并停止当前回复。"),yes->{if(yes&&model.conversation.equals(selected))write("delete",Map.of(),result->{model.drafts.remove(draftKey());releaseConversationFocus();model.conversation="";model.selected=null;model.generation++;rows.clear();history.clearAllScrollViewChildren();composer.setValue(new String[]{""},false);heading.setText(Component.literal(t("选择或新建对话")));list();window.close();});}).show(window.body)));
    }
    private void renameConversation(){if(model.selected==null)return;Dialog.stringEditorDialog(t("重命名"),model.selected.get("title").getAsString(),value->!value.isBlank()&&value.length()<=128,value->write("rename",Map.of("title",value),state->{model.selected=state;heading.setText(Component.literal(state.get("title").getAsString()));list();})).show(root);}

    Map<String,Object> smokeState(){if(!Boolean.getBoolean("mineagent.nativeUiSmoke")&&!Boolean.getBoolean("mineagent.skillSmoke"))throw new IllegalStateException("SMOKE_DISABLED");return Map.of("agents",model.agents.size(),"agent",model.agent,"conversation",model.conversation,"selected",model.selected==null?"":model.selected.get("title").getAsString(),"draft",String.join("\n",composer.getValue()),"messageRows",rows.size(),"bodyChars",rows.values().stream().mapToInt(row->row.text.getText().getString().length()).sum(),"visibleRows",rows.values().stream().filter(row->row.root.getSizeHeight()>0&&row.text.getSizeHeight()>0).count());}
    Map<String,Object> smokeIme(){
        // The skill fixture also exercises the actual conversation selection widgets.
        if(!Boolean.getBoolean("mineagent.nativeUiSmoke")&&!Boolean.getBoolean("mineagent.skillSmoke"))throw new IllegalStateException("SMOKE_DISABLED");if(!Minecraft.getInstance().isWindowActive())return Map.of("status","SKIPPED_WINDOW_NOT_ACTIVE");
        String original=String.join("\n",composer.getValue());composer.setValue(new String[]{""},false);modularUI.requestFocus(composer);
        if(!preeditUpdated(new net.minecraft.client.input.PreeditEvent("拼",1,List.of("拼"),0))||!nativeComposing()||!String.join("\n",composer.getValue()).isEmpty())throw new IllegalStateException("NATIVE_PREEDIT_INSERTED_TEXT");
        keyPressed(new net.minecraft.client.input.KeyEvent(org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER,0,0));if(!String.join("\n",composer.getValue()).isEmpty())throw new IllegalStateException("NATIVE_PREEDIT_ENTER_INSERTED");preeditUpdated(null);charTyped(new net.minecraft.client.input.CharacterEvent('测'));if(!String.join("\n",composer.getValue()).equals("测"))throw new IllegalStateException("NATIVE_COMMIT_NOT_EXACTLY_ONCE");
        composer.setValue(original.split("\n",-1),false);saveDraft();modularUI.requestFocus(null);return Map.of("status","PASS","kind","NATIVE_PREEDIT_CALLBACK_NOT_OS_IME_QUALITY","preeditSeparate",true,"commitOnce",true);
    }
    private ScrollerView conversationList;
    String smokeHistory(){if(!Boolean.getBoolean("mineagent.skillSmoke"))throw new IllegalStateException("SMOKE_DISABLED");return rows.values().stream().map(row->row.text.getText().getString()).collect(java.util.stream.Collectors.joining("\n"));}
    void smokeDraft(String value){if(!Boolean.getBoolean("mineagent.skillSmoke"))throw new IllegalStateException("SMOKE_DISABLED");composer.setValue(value.split("\n",-1),false);saveDraft();}
    UIElement smokeRoot(){if(!Boolean.getBoolean("mineagent.skillSmoke"))throw new IllegalStateException("SMOKE_DISABLED");return root;}
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
        if(!current()||!page.equals("chat")||model.agent.isEmpty()||!NativeWorkspaceConnection.ready()||listBusy&&listGeneration==model.generation)return;
        listBusy=true;long generation=model.generation;listGeneration=generation;String filter=model.filter,query=search.getValue();long before=listBefore;request(false,"list",Map.of("state",filter,"search",query,"before",Long.toString(before))).whenComplete((state,error)->{
            if(listGeneration==generation)listBusy=false;if(!current())return;if(generation!=model.generation||!filter.equals(model.filter)||!query.equals(search.getValue())||before!=listBefore){nextList=System.currentTimeMillis()+100;return;}if(!page.equals("chat"))return;if(error!=null){notice(error.getMessage());return;}
            long next=state.get("nextBefore").getAsLong();var values=state.getAsJsonArray("conversations");var ids=values.asList().stream().map(v->v.getAsJsonObject().get("conversationId").getAsString()).toList();
            if(ids.equals(new ArrayList<>(conversationButtons.keySet()))&&next==renderedListNext&&before==renderedListBefore){for(var item:values){var conversation=item.getAsJsonObject();String id=conversation.get("conversationId").getAsString(),title=conversation.get("title").getAsString();conversationTitles.put(id,title);conversationButtons.get(id).setText(Component.literal((id.equals(model.conversation)?"● ":"")+title));}return;}
            renderedListNext=next;renderedListBefore=before;conversationButtons.clear();conversationTitles.clear();conversationList.clearAllScrollViewChildren();for(var item:values){var conversation=item.getAsJsonObject();String id=conversation.get("conversationId").getAsString(),title=conversation.get("title").getAsString();var select=button((id.equals(model.conversation)?"● ":"")+title,()->select(id));select.setId("conversation-"+id);select.getLayout().widthPercent(100);conversationButtons.put(id,select);conversationTitles.put(id,title);conversationList.addScrollViewChild(select);}
            conversationPager.clearAllChildren();if(!listPages.isEmpty())conversationPager.addChild(button(t("上一页"),()->{if(!listBusy){listBefore=listPages.pop();list();}}));if(next>0)conversationPager.addChild(button(t("下一页"),()->{if(!listBusy){listPages.push(listBefore);listBefore=next;list();}}));
        });
    }
    private void select(String id){saveDraft();releaseConversationFocus();model.conversation=id;model.generation++;rows.clear();loading.clear();history.clearAllScrollViewChildren();pinBottom=true;anchor=null;pinPixel=-1;model.selected=null;nextBefore=0;heading.setText(Component.literal(conversationTitles.getOrDefault(id,t("读取对话…"))));for(var entry:conversationButtons.entrySet())entry.getValue().setText(Component.literal((entry.getKey().equals(id)?"● ":"")+conversationTitles.get(entry.getKey())));composer.setValue(draftText().split("\n",-1),false);nextMessages=0;messages(0);focusConversation();}
    private void focusConversation(){
        if(!current()||model.conversation.isEmpty()||!NativeWorkspaceConnection.ready())return;
        String agent=model.agent,conversation=model.conversation;long generation=model.generation;
        request(true,"focus",Map.of("contextId",context.toString())).whenComplete((value,error)->{
            if(!current()||generation!=model.generation||!agent.equals(model.agent)||!conversation.equals(model.conversation))return;
            if(error!=null){notice(error.getMessage());return;}
            var session=NativeWorkspaceConnection.current();if(session!=null)dev.mineagent.runtime.neoforge.client.audio.ConversationVoicePlayback.focus(session.binding().worldId(),UUID.fromString(agent),UUID.fromString(conversation),context);
        });
    }
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
        if(!current()||!NativeWorkspaceConnection.ready()||model.conversation.isEmpty()||messagesBusy&&messagesGeneration==model.generation||!page.equals("chat"))return;messagesBusy=true;long generation=model.generation;messagesGeneration=generation;String conversation=model.conversation;
        request(false,"messages",Map.of("before",Long.toString(before))).whenComplete((state,error)->{
            if(messagesGeneration==generation)messagesBusy=false;if(!current()||generation!=model.generation||!conversation.equals(model.conversation))return;if(error!=null){notice(error.getMessage());return;}
            model.selected=state.getAsJsonObject("conversation");heading.setText(Component.literal(model.selected.get("title").getAsString()));nextBefore=state.get("nextBefore").getAsLong();boolean changed=false;
            var thinking=new HashMap<String,JsonObject>();if(state.has("thinking"))for(var item:state.getAsJsonObject("thinking").entrySet()){var q=item.getValue().getAsJsonObject();thinking.put(item.getKey(),q);}
            for(var item:state.getAsJsonArray("messages")){
                var message=item.getAsJsonObject();String id=message.get("messageId").getAsString();long revision=message.get("revision").getAsLong();var entry=rows.get(id);
                if(entry==null){
                    var container=new UIElement();container.getLayout().widthPercent(100).paddingAll(6).marginBottom(5);container.getStyle().backgroundTexture(NativeUiTheme.inset());
                    var header=row();header.getLayout().height(21).marginBottom(3);container.addChild(header);boolean user=message.get("role").getAsString().equals("USER");
                    var speaker=label(user?t("你"):knownAgents.getOrDefault(model.agent,"AI"));speaker.getLayout().flex(1).widthAuto();speaker.textStyle(style->style.textColor(user?0xffc8c8c8:0xffe4eeb5));header.addChild(speaker);
                    var body=label("");var thought=label("");thought.setDisplay(false);var toggle=button(t("思考"),()->{captureScroll();thought.setDisplay(!thought.isDisplayed());restoreScrollFrames=3;});toggle.getLayout().height(19).width(42);header.addChild(toggle);
                    if(!user){String detailAgent=model.agent,detailConversation=model.conversation;var details=button(t("详情"),()->NativeConversationExtras.context(this,detailAgent,detailConversation,id));details.getLayout().height(19).width(42);header.addChild(details);}
                    if(!user){var voice=button(t("朗读"),()->write("voice",Map.of("messageId",id,"contextId",context.toString()),value->notice(t("正在处理…"))));voice.getLayout().height(19).width(42);header.addChild(voice);}
                    var actionPanel=new MessageActions(id,generation);container.addChild(body);container.addChild(thought);container.addChild(actionPanel.root);entry=new MessageRow(message.get("sequence").getAsLong(),container,body,thought,toggle,actionPanel);rows.put(id,entry);changed=true;
                }
                var rich=state.has("richMessages")?state.getAsJsonObject("richMessages"):new JsonObject();entry.actions.refresh(rich.has(id)?rich.get(id).getAsString():null);
                var th=thinking.get(id);entry.thinkingButton.setDisplay(th!=null&&th.get("textLength").getAsInt()>0);if(th!=null&&entry.thinking.isDisplayed()&&!Objects.equals(loading.get("thinking:"+id),th.get("revision").getAsLong())){loading.put("thinking:"+id,th.get("revision").getAsLong());textChunk(id,th.get("revision").getAsLong(),"thinking",entry.thinking,generation,0,new StringBuilder());}
                if(!Objects.equals(loading.get(id),revision)){loading.put(id,revision);textChunk(id,revision,"message",entry.text,generation,0,new StringBuilder());}
            }
            if(changed){captureScroll();history.clearAllScrollViewChildren();rows.values().stream().sorted(Comparator.comparingLong(MessageRow::sequence)).forEach(row->history.addScrollViewChild(row.root));restoreScrollFrames=3;}
        }).exceptionally(failure->{notice(Objects.toString(failure.getCause()==null?failure.getMessage():failure.getCause().getMessage(),"CONVERSATION_RENDER_FAILED"));return null;});
    }
    private final class MessageActions {
        final String message, agent=model.agent, conversation=model.conversation; final long generation;
        final UIElement root=new UIElement(); JsonObject data; String signature=""; boolean loading, pending;
        MessageActions(String message,long generation){this.message=message;this.generation=generation;root.getLayout().widthPercent(100).gapAll(3).marginTop(4);root.setDisplay(false);}
        boolean valid(){return current()&&generation==model.generation&&agent.equals(model.agent)&&conversation.equals(model.conversation);}
        void refresh(String next){
            if(next==null||!valid())return;
            if(loading||pending||next.equals(signature))return;loading=true;
            request(false,"richMessage",Map.of("messageId",message)).whenComplete((value,error)->{
                loading=false;if(!valid())return;if(error!=null){notice(error.getMessage());return;}
                data=value;signature=next;draw();
            });
        }
        void draw(){
            if(!valid()||data==null)return;captureScroll();root.clearAllChildren();root.setDisplay(true);
            String state=data.get("state").getAsString();int selected=data.get("selected").getAsInt();
            for(var raw:data.getAsJsonArray("buttons")){
                var option=raw.getAsJsonObject();int index=option.get("index").getAsInt();String action=option.get("action").getAsString();
                String verb=switch(action){case "confirm"->"确认选择";case "suggest"->"填入输入框";case "copy"->"复制";case "preview"->"预览";default->"";};
                var control=button(t(verb)+" · "+option.get("label").getAsString(),()->activate(index,action));
                control.setId("rich-"+message+"-"+index);control.getLayout().widthPercent(100).heightAuto().minHeight(23).marginAll(0).paddingVertical(4);
                control.text.getLayout().widthPercent(100).heightAuto();control.text.textStyle(style->style.adaptiveWidth(false).adaptiveHeight(true).textWrap(TextWrap.WRAP));
                control.setActive(!pending&&(!action.equals("confirm")||state.equals("OPEN")));root.addChild(control);
            }
            if(pending)root.addChild(label(t("正在提交选择…")));
            else if(!state.equals("OPEN")&&data.getAsJsonArray("buttons").asList().stream().anyMatch(option->option.getAsJsonObject().get("action").getAsString().equals("confirm")))root.addChild(label(t(switch(state){case "ACCEPTED"->"选择已提交";case "EXPIRED"->"选项已过期";case "READ_ONLY"->"归档会话不能提交选择";default->"选择结果待核对，不会自动重发";})+(selected<0?"":" · "+data.getAsJsonArray("buttons").get(selected).getAsJsonObject().get("label").getAsString())));
            restoreScrollFrames=3;
        }
        void activate(int index,String action){
            if(!valid()||pending||data==null||action.equals("confirm")&&!data.get("state").getAsString().equals("OPEN"))return;
            pending=true;draw();boolean confirm=action.equals("confirm");
            request(confirm,confirm?"richConfirm":"richButton",Map.of("messageId",message,"index",Integer.toString(index))).whenComplete((value,error)->{
                pending=false;if(!valid())return;
                if(error!=null){signature="";notice(error.getMessage());draw();return;}
                if(confirm){data=value.getAsJsonObject("choice");signature="";notice(value.has("error")&&!value.get("error").getAsString().isBlank()?t("选择已提交")+" · "+value.get("error").getAsString():t(value.has("queued")&&value.get("queued").getAsBoolean()?"选择已排队":"选择已提交"));nextMessages=0;}
                else if(action.equals(value.get("action").getAsString())){
                    String text=value.get("value").getAsString();
                    if(action.equals("preview")){NativePreview.open(text);}
                    else if(action.equals("copy")){Minecraft.getInstance().keyboardHandler.setClipboard(text);notice(t("已复制"));}
                    else if(action.equals("suggest")){String draft=String.join("\n",composer.getValue());composer.setValue((draft.isBlank()?text:draft+"\n"+text).split("\n",-1),false);saveDraft();getModularUI().requestFocus(composer);notice(t("已填入输入框，尚未发送"));}
                }
                draw();
            });
        }
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
