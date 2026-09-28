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
        JsonArray agents=new JsonArray();final Map<String,String> drafts=new HashMap<>(),speechOps=new HashMap<>();long generation;
    }
    private static Model model=new Model();private static NativeWorkspaceScreen active;private static Object connection,level;
    private final UIElement root,content=new UIElement();private final ScrollerView directory=new ScrollerView(),history=new ScrollerView();
    private final TextElement status=label(""),heading=label("DivZero");private final TextArea composer=new TextArea();private final TextField titleInput=new TextField(),search=new TextField();
    private final Map<String,MessageRow> rows=new LinkedHashMap<>();private final Map<String,Long> loading=new HashMap<>();
    private final UUID context=UUID.randomUUID();private String page="chat",agentSignature="";private long nextMessages,nextList;private boolean messagesBusy,listBusy,writing;private long nextBefore,listBefore;private int fileOffset;private float pinPixel=-1;private UIElement anchor;private boolean pinBottom;
    private record MessageRow(long sequence,UIElement root,TextElement text,TextElement thinking,Button thinkingButton){}
    private NativeWorkspaceScreen(){this(new UIElement());}
    private NativeWorkspaceScreen(UIElement root){
        super(new ModularUI(UI.of(root,size->size),Minecraft.getInstance().player),Component.literal("DivZero"));this.root=root;composer.registerValueListener(value->saveDraft());
        root.getLayout().widthPercent(100).heightPercent(100).paddingAll(8);root.getStyle().backgroundTexture(new ColorRectTexture(0xd9141c29));
        var toolbar=row();toolbar.getLayout().height(24);root.addChild(toolbar);
        toolbar.addChild(button("DivZero",()->showChat()));toolbar.addChild(button(t("AI 管理"),()->panel(PanelSection.AGENTS)));toolbar.addChild(button(t("文件"),this::showFiles));toolbar.addChild(button(t("内容包"),()->panel(PanelSection.PACKAGES)));toolbar.addChild(button(t("API 设置"),()->panel(PanelSection.PROVIDERS)));toolbar.addChild(button(t("更多"),()->panel(PanelSection.OVERVIEW)));toolbar.addChild(button(t("语言"),()->Minecraft.getInstance().setScreen(new LanguageScreen(this))));toolbar.addChild(button(t("关于"),()->Minecraft.getInstance().setScreen(new AboutScreen(this))));toolbar.addChild(button(t("返回游戏 · Esc"),this::onClose));
        var body=row();body.getLayout().flex(1).widthPercent(100);root.addChild(body);directory.getLayout().width(170).heightPercent(100);body.addChild(directory);content.getLayout().flex(1).heightPercent(100).paddingLeft(8);body.addChild(content);
        status.getLayout().height(18).widthPercent(100);root.addChild(status);showChat();drawAgents();
    }
    public static void open(){
        var mc=Minecraft.getInstance();if(!net.neoforged.fml.ModList.get().isLoaded("ldlib2"))throw new IllegalStateException("LDLIB2_REQUIRED");
        if(connection!=mc.getConnection()||level!=mc.level){model=new Model();connection=mc.getConnection();level=mc.level;}
        active=new NativeWorkspaceScreen();mc.setScreen(active);NativeWorkspaceConnection.open();if(NativeWorkspaceConnection.ready())active.list();
    }
    public static void toggle(){if(Minecraft.getInstance().screen instanceof NativeWorkspaceScreen screen)screen.onClose();else open();}
    public static boolean visible(){return Minecraft.getInstance().screen instanceof NativeWorkspaceScreen;}
    public static void notice(String text){model.notice=text;if(active!=null)active.status.setText(Component.literal(text));}
    public static void sessionReady(){if(active!=null){active.nextMessages=0;active.list();}}
    public static void disconnected(){model=new Model();active=null;connection=level=null;}
    public static void snapshot(Map<String,String> values){
        if(values.containsKey("agents"))model.agents=JsonParser.parseString(values.get("agents")).getAsJsonArray();
        if(active!=null)active.drawAgents();
    }
    public static void push(String channel,JsonElement data){if(active==null)return;if(channel.equals("conversationChanged"))active.nextMessages=0;else if(channel.equals("buildingFilesOpen")){var value=data.getAsJsonObject();if(value.has("agentId")&&!value.get("agentId").getAsString().isBlank())model.agent=value.get("agentId").getAsString();active.showFiles();}else if(channel.equals("conversationSpeechDraft")){var value=data.getAsJsonObject();if(value.has("text")&&value.get("agentId").getAsString().equals(model.agent)&&value.get("conversationId").getAsString().equals(model.conversation)&&value.get("contextId").getAsString().equals(active.context.toString())){String draft=model.drafts.getOrDefault(active.draftKey(),"");model.drafts.put(active.draftKey(),draft+value.get("text").getAsString());model.speechOps.put(active.draftKey(),value.get("speechOperation").getAsString());active.showChat();}}}
    private boolean current(){return active==this&&connection==Minecraft.getInstance().getConnection()&&level==Minecraft.getInstance().level;}
    private static String t(String text){return ClientLanguage.t(text);}
    private static TextElement label(String text){var label=new TextElement().setText(Component.literal(text));label.getLayout().widthPercent(100);label.textStyle(s->s.adaptiveWidth(false).adaptiveHeight(true).textWrap(TextWrap.WRAP).textColor(0xffeef2f7));return label;}
    private static UIElement row(){var row=new UIElement();row.getLayout().flexDirection(FlexDirection.ROW).widthPercent(100);return row;}
    private static Button button(String text,Runnable action){var button=new Button().setText(Component.literal(text));button.getLayout().height(21).marginRight(4);button.setOnClick(event->action.run());return button;}
    private void panel(PanelSection section){Minecraft.getInstance().setScreen(ControlCenterScreen.create(this,section));}
    private void drawAgents(){
        String signature=model.agents+"|"+model.agent;if(signature.equals(agentSignature))return;agentSignature=signature;directory.clearAllScrollViewChildren();directory.addScrollViewChild(label(t("选择 AI")));
        for(var value:model.agents){var agent=value.getAsJsonObject();String id=agent.get("id").getAsString(),name=agent.get("name").getAsString();var select=button((id.equals(model.agent)?"● ":"")+name,()->selectAgent(id));select.getLayout().widthPercent(100);directory.addScrollViewChild(select);}
        directory.addScrollViewChild(button(t("AI 管理"),()->panel(PanelSection.AGENTS)));
    }
    private void selectAgent(String id){saveDraft();clearFocus();model.agent=id;model.conversation="";model.selected=null;model.generation++;listBefore=0;rows.clear();history.clearAllScrollViewChildren();drawAgents();showChat();list();}
    private void showChat(){
        page="chat";content.clearAllChildren();content.addChild(heading);heading.setText(Component.literal(t("对话")));
        var tools=row();tools.getLayout().height(23);titleInput.setText(t("新的对话"),false);titleInput.getLayout().flex(1);tools.addChild(titleInput);tools.addChild(button(t("新建对话"),()->write("create",Map.of("title",titleInput.getValue()),state->{select(state.get("conversationId").getAsString());list();})));tools.addChild(button(t("刷新"),this::list));tools.addChild(button(t("已归档"),()->{model.filter=model.filter.equals("ACTIVE")?"ARCHIVED":"ACTIVE";listBefore=0;list();}));content.addChild(tools);
        var searchRow=row();searchRow.getLayout().height(23);search.getLayout().flex(1);searchRow.addChild(search);searchRow.addChild(button(t("搜索"),()->{listBefore=0;list();}));searchRow.addChild(button(t("上一页"),()->{listBefore=0;list();}));content.addChild(searchRow);
        conversationList=new ScrollerView();conversationList.getLayout().height(65).widthPercent(100);content.addChild(conversationList);
        var controls=row();controls.getLayout().height(23);controls.addChild(button(t("更早的消息"),()->messages(nextBefore)));controls.addChild(button(t("最新消息"),()->{rows.clear();history.clearAllScrollViewChildren();messages(0);}));controls.addChild(button(t("重命名"),()->write("rename",Map.of("title",titleInput.getValue()),state->{model.selected=state;list();})));controls.addChild(button(t("归档"),()->changeConversation("archive")));controls.addChild(button(t("恢复"),()->changeConversation("restore")));content.addChild(controls);
        history.getLayout().flex(1).widthPercent(100);content.addChild(history);composer.getLayout().height(64).widthPercent(100);composer.setValue(model.drafts.getOrDefault(draftKey(),"").split("\n",-1),false);content.addChild(composer);
        var actions=row();actions.getLayout().height(24);actions.addChild(button(t("发送"),this::send));actions.addChild(button(t("停止"),this::cancel));actions.addChild(button(t("附件"),this::showFiles));actions.addChild(button(t("语音输入"),()->{var session=NativeWorkspaceConnection.current();if(session!=null&&model.selected!=null)Minecraft.getInstance().setScreen(new NativeSpeechScreen(this,session.binding().worldId(),UUID.fromString(model.agent),UUID.fromString(model.conversation),context));}));actions.addChild(button(t("配置与权限"),()->panel(PanelSection.PERMISSIONS)));content.addChild(actions);
        status.setText(Component.literal(model.notice));if(!model.agent.isEmpty()&&NativeWorkspaceConnection.ready())list();
    }
    private ScrollerView conversationList;
    private String draftKey(){return model.agent+"/"+model.conversation;}
    private void saveDraft(){if(!model.conversation.isEmpty())model.drafts.put(draftKey(),String.join("\n",composer.getValue()));}
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
            listBusy=false;if(!current()||generation!=model.generation||!page.equals("chat"))return;if(error!=null){notice(error.getMessage());return;}
            conversationList.clearAllScrollViewChildren();for(var item:state.getAsJsonArray("conversations")){var conversation=item.getAsJsonObject();String id=conversation.get("conversationId").getAsString();var select=button((id.equals(model.conversation)?"● ":"")+conversation.get("title").getAsString(),()->select(id));select.getLayout().widthPercent(100);conversationList.addScrollViewChild(select);}
            long next=state.get("nextBefore").getAsLong();if(next>0)conversationList.addScrollViewChild(button(t("下一页"),()->{listBefore=next;list();}));
        });
    }
    private void select(String id){saveDraft();clearFocus();model.conversation=id;model.generation++;rows.clear();loading.clear();history.clearAllScrollViewChildren();model.selected=null;composer.setValue(model.drafts.getOrDefault(draftKey(),"").split("\n",-1),false);nextMessages=0;messages(0);request(true,"focus",Map.of("contextId",context.toString())).thenAccept(f->{var session=NativeWorkspaceConnection.current();if(session!=null&&current())dev.mineagent.runtime.neoforge.client.audio.ConversationVoicePlayback.focus(session.binding().worldId(),UUID.fromString(model.agent),UUID.fromString(model.conversation),context);}).exceptionally(error->{notice(error.getMessage());return null;});}
    private void clearFocus(){dev.mineagent.runtime.neoforge.client.audio.ConversationVoicePlayback.clear(context);if(model.conversation.isEmpty()||!NativeWorkspaceConnection.ready())return;request(true,"unfocus",Map.of("contextId",context.toString()));}
    private void changeConversation(String kind){if(model.selected==null)return;write(kind,Map.of(),state->{model.selected=state;list();});}
    private void send(){String text=String.join("\n",composer.getValue());if(text.isBlank()||model.selected==null)return;String key=draftKey();var arguments=new LinkedHashMap<String,String>();arguments.put("text",text);if(model.speechOps.containsKey(key))arguments.put("speechOperation",model.speechOps.get(key));write("send",arguments,state->{model.selected=state;if(model.drafts.getOrDefault(key,"").equals(text)){model.drafts.put(key,"");model.speechOps.remove(key);composer.setValue(new String[]{""},false);}messages(0);});}
    private void cancel(){if(model.selected==null)return;String target=model.selected.has("activeOperation")?model.selected.get("activeOperation").getAsString():"";if(!target.isEmpty())write("cancel",Map.of("targetOperation",target),state->model.selected=state);}
    private void messages(long before){
        if(!current()||model.conversation.isEmpty()||messagesBusy||!page.equals("chat"))return;messagesBusy=true;long generation=model.generation;String conversation=model.conversation;
        request(false,"messages",Map.of("before",Long.toString(before))).whenComplete((state,error)->{
            messagesBusy=false;if(!current()||generation!=model.generation||!conversation.equals(model.conversation))return;if(error!=null){notice(error.getMessage());return;}
            model.selected=state.getAsJsonObject("conversation");heading.setText(Component.literal(model.selected.get("title").getAsString()));nextBefore=state.get("nextBefore").getAsLong();boolean changed=false;
            var thinking=new HashMap<String,JsonObject>();if(state.has("thinking"))for(var v:state.getAsJsonArray("thinking")){var q=v.getAsJsonObject();thinking.put(q.get("messageId").getAsString(),q);}
            for(var item:state.getAsJsonArray("messages")){
                var message=item.getAsJsonObject();String id=message.get("messageId").getAsString();long revision=message.get("revision").getAsLong();var entry=rows.get(id);
                if(entry==null){var container=new UIElement();container.getLayout().widthPercent(100).paddingAll(5).marginBottom(4);container.getStyle().backgroundTexture(new ColorRectTexture(0x772b3544));container.addChild(label(message.get("role").getAsString().equals("USER")?t("你"):"AI"));var body=label("");container.addChild(body);var thought=label("");thought.setDisplay(false);var toggle=button(t("思考"),()->thought.setDisplay(!thought.isDisplayed()));container.addChild(toggle);container.addChild(thought);if(!message.get("role").getAsString().equals("USER"))container.addChild(button(t("朗读"),()->write("voice",Map.of("messageId",id,"contextId",context.toString()),voice->notice(t("正在处理…")))));entry=new MessageRow(message.get("sequence").getAsLong(),container,body,thought,toggle);rows.put(id,entry);changed=true;}
                var th=thinking.get(id);entry.thinkingButton.setDisplay(th!=null&&th.get("textLength").getAsInt()>0);if(th!=null&&entry.thinking.isDisplayed()&&!Objects.equals(loading.get("thinking:"+id),th.get("revision").getAsLong())){loading.put("thinking:"+id,th.get("revision").getAsLong());textChunk(id,th.get("revision").getAsLong(),"thinking",entry.thinking,generation,0,new StringBuilder());}
                if(!Objects.equals(loading.get(id),revision)){loading.put(id,revision);textChunk(id,revision,"message",entry.text,generation,0,new StringBuilder());}
            }
            if(changed){pinBottom=history.verticalScroller.getNormalizedValue()>.97f;float prior=history.verticalScroller.getValue();history.clearAllScrollViewChildren();rows.values().stream().sorted(Comparator.comparingLong(MessageRow::sequence)).forEach(row->history.addScrollViewChild(row.root));history.verticalScroller.setValue(prior);}
        });
    }
    private void textChunk(String id,long revision,String kind,TextElement target,long generation,int offset,StringBuilder text){
        request(false,kind,Map.of("messageId",id,"messageRevision",Long.toString(revision),"offset",Integer.toString(offset))).whenComplete((chunk,error)->{
            if(!current()||generation!=model.generation)return;if(error!=null){loading.remove(kind.equals("thinking")?"thinking:"+id:id);return;}
            if(chunk.get("revision").getAsLong()!=revision){loading.remove(kind.equals("thinking")?"thinking:"+id:id);return;}String part=chunk.get("text").getAsString();text.append(part);target.setText(Component.literal(text.toString()));int next=offset+part.length();if(next<chunk.get("total").getAsInt()&&!part.isEmpty())textChunk(id,revision,kind,target,generation,next,text);
        });
    }
    private void showFiles(){
        saveDraft();page="files";fileOffset=0;content.clearAllChildren();content.addChild(label(t("文件")));var actions=row();actions.getLayout().height(24);content.addChild(actions);
        actions.addChild(button(t("上传文件"),()->fileAction("upload",Map.of())));actions.addChild(button(t("上传文件夹"),()->fileAction("folder",Map.of())));actions.addChild(button(t("停止"),()->fileAction("cancel",Map.of())));actions.addChild(button(t("刷新"),this::files));actions.addChild(button(t("返回"),this::showChat));
        fileRows=new ScrollerView();fileRows.getLayout().flex(1).widthPercent(100);content.addChild(fileRows);var pager=row();pager.getLayout().height(24);pager.addChild(button(t("上一页"),()->{fileOffset=Math.max(0,fileOffset-32);files();}));pager.addChild(button(t("下一页"),()->{fileOffset+=32;files();}));content.addChild(pager);files();
    }
    private ScrollerView fileRows;private long nextFilePoll;private boolean transferWasBusy;
    private CompletableFuture<JsonObject> fileRequest(String kind,Map<String,Object> extra){var data=new LinkedHashMap<String,Object>(extra);data.put("kind",kind);data.put("agentId",model.agent);return BuildingFilesClient.handle(JSON.toJsonTree(data).getAsJsonObject()).thenApply(value->JSON.toJsonTree(value).getAsJsonObject());}
    private void fileAction(String kind,Map<String,Object> extra){fileRequest(kind,extra).whenComplete((value,error)->Minecraft.getInstance().execute(()->{if(!current())return;if(error!=null)notice(error.getMessage());else{notice(t("正在处理…"));nextFilePoll=0;transferWasBusy=true;}}));}
    private void files(){fileRequest("list",Map.of("query","","offset",fileOffset)).whenComplete((data,error)->Minecraft.getInstance().execute(()->{if(!current()||!page.equals("files"))return;if(error!=null){notice(error.getMessage());return;}fileRows.clearAllScrollViewChildren();for(var item:data.getAsJsonArray("files")){var file=item.getAsJsonObject();String id=file.get("id").getAsString();var line=new UIElement();line.getLayout().widthPercent(100).paddingAll(5);line.addChild(label(file.get("name").getAsString()));var actions=row();actions.getLayout().height(23);actions.addChild(button(t("交给 AI"),()->fileRequest("select",Map.of("fileId",id)).whenComplete((value,failure)->Minecraft.getInstance().execute(()->{if(failure!=null)notice(failure.getMessage());else if(value.has("status")&&value.get("status").getAsString().equals("SENT_TO_AI"))notice(t("AI 已收到文件，继续处理。"));else if(!model.conversation.isEmpty()){String reference="\n["+file.get("name").getAsString()+" file_id="+id+"]";String key=draftKey();String draft=model.drafts.getOrDefault(key,"");if(!draft.contains("file_id="+id))model.drafts.put(key,draft+reference);notice(t("已加入对话草稿，填写需求后发送。"));}else notice(t("选择或新建对话"));}))));actions.addChild(button(t("下载"),()->fileAction("download",Map.of("fileId",id))));line.addChild(actions);fileRows.addScrollViewChild(line);}}));}
    @Override public void tick(){super.tick();if(!current())return;long now=System.currentTimeMillis();if(page.equals("chat")&&NativeWorkspaceConnection.ready()&&now>=nextMessages){nextMessages=now+700;messages(0);}if(pinBottom&&!rows.isEmpty()){history.scrollToChildDelayed(rows.values().stream().max(Comparator.comparingLong(MessageRow::sequence)).orElseThrow().root);pinBottom=false;}if(page.equals("files")&&now>=nextFilePoll){nextFilePoll=now+700;fileRequest("status",Map.of()).thenAccept(data->Minecraft.getInstance().execute(()->{if(!current())return;notice(data.get("status").getAsString()+(data.get("error").getAsString().isEmpty()?"":" · "+data.get("error").getAsString()));boolean busy=data.get("busy").getAsBoolean();if(transferWasBusy&&!busy)files();transferWasBusy=busy;}));}}
    @Override public void onClose(){saveDraft();clearFocus();super.onClose();}
}
