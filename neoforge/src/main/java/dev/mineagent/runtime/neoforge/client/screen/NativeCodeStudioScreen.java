package dev.mineagent.runtime.neoforge.client.screen;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mineagent.runtime.client.studio.*;
import dev.mineagent.runtime.neoforge.network.MineAgentPayloads;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import java.util.*;
import java.util.function.*;

/** Owner/world/request-bound Native editor; no shared CodeState inbox or implicit Agent selection. */
public final class NativeCodeStudioScreen extends Screen {
    private enum View { LIST,EDIT,TOOLS,RUNS,HISTORY,TEXT }
    private record Control(Button widget,BooleanSupplier allowed){}
    private record TextRequest(String kind,Map<String,String> arguments){}
    private final Screen parent;
    private final Object connection,wire;
    private final UUID owner;
    private final ObjectMapper json=new ObjectMapper();
    private final CodeStudioTextTools tools=new CodeStudioTextTools();
    private final List<Control> controls=new ArrayList<>();
    private final List<AbstractWidget> inputs=new ArrayList<>();
    private String world="",draftId="",path="script.js",packageId="",packageName="",agentId="",agentName="",source="",baseSource="",sourceHash="";
    private String loadedWorkspaceHash="";
    private String notice="读取服务端上下文；不会创建 Task 或执行代码。",armed="",search="",replacement="";
    private long loadedRevision,packageRevision;
    private View view=View.LIST;
    private View shownView;
    private JsonNode info;
    private List<JsonNode> drafts=List.of(),agents=List.of(),history=List.of();
    private int ticks,waitingAt,listOffset,listNext,listRow,agentOffset,agentNext,runOffset,selectedRun,historyOffset,historyNext,historyRow,textOffset,textNext;
    private boolean initialized,invalid,staleSource,startAllowed,listMore,agentMore,historyMore,textMore;
    private UUID pending,lastOperation;
    private boolean pendingWrite;
    private String lastWriteAction="";
    private Consumer<JsonNode> completion;
    private FittingMultiLineTextWidget status;
    private Button reconnect;
    private String textBody="",textHash="",textTitle="",historyText="",historyDraft="";
    private Component styledText;
    private long historyRevision;
    private TextRequest textRequest;
    private String initialDraft="",initialWorld="";
    private final Deque<String> undo=new ArrayDeque<>(),redo=new ArrayDeque<>();

    public NativeCodeStudioScreen(Screen parent){this(parent,"script.js");}
    public NativeCodeStudioScreen(Screen parent,String newPath){super(Component.literal("Native Code Studio"));this.parent=parent;path=newPath;var mc=Minecraft.getInstance();connection=mc.getConnection();wire=mc.getConnection()==null?null:mc.getConnection().getConnection();owner=mc.player==null?null:mc.player.getUUID();}
    public NativeCodeStudioScreen(Screen parent,String draftId,String world){this(parent);initialDraft=draftId;initialWorld=world;this.world=world;}
    public static NativeCodeStudioScreen createDraft(Screen parent,String path){var screen=new NativeCodeStudioScreen(parent,path);screen.view=View.EDIT;return screen;}
    public boolean hasUnsavedSource(){return !source.equals(baseSource);}
    private boolean current(){var mc=Minecraft.getInstance();return !invalid&&connection!=null&&connection==mc.getConnection()&&mc.player!=null&&mc.player.getUUID().equals(owner);}
    private boolean ready(){return current()&&pending==null&&!world.isEmpty();}
    private boolean writable(){return ready()&&(draftId.isEmpty()||info!=null&&info.path("status").asText().equals("DRAFT"));}
    @Override protected void init(){build();if(!initialized){initialized=true;context(0,true);}}
    private void note(String value){notice=value;if(status!=null)status.setMessage(Component.literal(value));}
    private void label(int x,int y,int width,String value){addRenderableWidget(new StringWidget(x,y,width,18,Component.literal(value),font).setMaxWidth(width));}
    private Button button(int x,int y,int width,String title,Runnable action,BooleanSupplier allowed){
        var b=addRenderableWidget(Button.builder(Component.literal(title),ignored->{if(ready()&&allowed.getAsBoolean())try{action.run();}catch(Exception e){note("STUDIO_NATIVE_ACTION_FAILED；没有自动重试。");}}).bounds(x,y,width,20).build());
        controls.add(new Control(b,allowed));return b;
    }
    private void refresh(){
        for(var c:controls)c.widget().active=ready()&&c.allowed().getAsBoolean();
        for(var input:inputs)input.active=current()&&pending==null;
        if(pending!=null)setFocused(null);
        if(reconnect!=null)reconnect.active=current()&&pending==null;
        if(status!=null)status.setMessage(Component.literal(notice));
    }
    private void status(int x,int y,int width,int height){status=addRenderableWidget(new FittingMultiLineTextWidget(x,y,width,Math.max(18,height),Component.literal(notice),font));}
    private boolean confirm(String key,String message){if(!armed.equals(key)){armed=key;note(message+" 再次点击确认。");return false;}armed="";return true;}
    private boolean leave(String key){return !hasUnsavedSource()||confirm(key,"将放弃当前未保存编辑。");}
    private void changed(String value){
        if(value.equals(source))return;undo.addLast(source);while(undo.size()>64)undo.removeFirst();redo.clear();source=value;armed="";note("本地源码已编辑；保存使用加载的 revision，不自动跟随服务器改版。");refresh();
    }
    private void local(String value){if(!writable())return;if(value.length()>16000){note("超过 16000 字符，未作部分修改。");return;}changed(value);build();}
    private void build(){
        shownView=view;
        clearWidgets();controls.clear();inputs.clear();status=null;reconnect=null;
        int x=Math.max(8,width/2-250),w=Math.min(500,width-16),q=(w-12)/4;
        label(x,7,w,getTitle().getString()+" · "+(draftId.isEmpty()?"尚未创建":draftId));
        if(width<320||height<240){status(x,34,w,Math.max(30,height-65));note("界面空间不足，请调低 GUI 缩放或扩大窗口。没有自动执行操作。");addRenderableWidget(Button.builder(Component.literal("返回"),b->onClose()).bounds(x,height-23,w,18).build());return;}
        if(world.isEmpty())reconnect=addRenderableWidget(Button.builder(Component.literal("重读上下文"),b->context(0,true)).bounds(x,28,q,20).build());
        else button(x,28,q,"草稿列表",()->{if(leave("list"))list(0);},()->true);
        button(x+q+4,28,q,"新 JS",()->fresh("script.js"),()->true);
        button(x+2*(q+4),28,q,"新 mjs",()->fresh("script.mjs"),()->true);
        button(x+3*(q+4),28,w-3*(q+4),"新 Java",()->fresh("Extension.java"),()->true);
        switch(view){
            case LIST -> listView(x,w);
            case EDIT -> editView(x,w);
            case TOOLS -> toolsView(x,w);
            case RUNS -> runsView(x,w);
            case HISTORY -> historyView(x,w);
            case TEXT -> textView(x,w);
        }
        addRenderableWidget(Button.builder(Component.literal("返回（不自动停止已发布代码）"),b->onClose()).bounds(x,height-23,w,18).build());
        refresh();
    }
    private void fresh(String nextPath){
        if(!leave("fresh:"+nextPath))return;draftId="";path=nextPath;packageId="";packageRevision=0;packageName="";agentId=agentName="";source=baseSource="";sourceHash="";loadedRevision=0;info=null;staleSource=false;undo.clear();redo.clear();view=View.EDIT;armed="";build();
    }
    private void context(int offset,boolean first){
        request(false,UUID.randomUUID(),Map.of("kind","init","offset",Integer.toString(offset)),value->{
            String actual=value.path("worldId").asText();if(actual.isEmpty()||!initialWorld.isEmpty()&&!initialWorld.equals(actual))throw new IllegalArgumentException();
            world=actual;agents=elements(value.path("agents"));agentOffset=offset;agentNext=value.path("nextOffset").asInt();agentMore=value.path("more").asBoolean();startAllowed=value.path("startAllowed").asBoolean();
            if(first&&!initialDraft.isEmpty()){String target=initialDraft;initialDraft="";load(target);}
            else if(first&&view==View.LIST)list(0);
            else{note(startAllowed?"上下文已读取；新稿请选择实际 Agent。":"没有 START_TASK 权限，可以只读检查旧稿。");build();}
        });
    }
    private void list(int offset){
        request(false,UUID.randomUUID(),Map.of("kind","list","offset",Integer.toString(offset)),value->{drafts=elements(value.path("drafts"));listOffset=offset;listNext=value.path("nextOffset").asInt();listMore=value.path("more").asBoolean();listRow=0;view=View.LIST;armed="";build();});
    }
    private void listView(int x,int w){
        int rows=Math.max(1,Math.min(8,(height-146)/24));label(x,52,w,"本人保存稿；不会读取 OP 可见的其他玩家源码。");
        for(int i=listRow;i<Math.min(drafts.size(),listRow+rows);i++){var d=drafts.get(i);button(x,76+(i-listRow)*24,w,d.path("path").asText()+" · "+d.path("status").asText()+" r"+d.path("revision").asLong(),()->{if(leave("open:"+d.path("id").asText()))load(d.path("id").asText());},()->true);}
        int y=height-91,half=(w-4)/2;
        button(x,y,half,"前面的记录",()->{if(listRow>0){listRow=Math.max(0,listRow-rows);build();}else list(Math.max(0,listOffset-8));},()->listRow>0||listOffset>0);
        button(x+half+4,y,half,"后面的记录",()->{if(listRow+rows<drafts.size()){listRow+=rows;build();}else list(listNext);},()->listRow+rows<drafts.size()||listMore);
        status(x,y+24,w,38);
    }
    private void load(String id){
        load(id,null);
    }
    private void load(String id,String nameOverride){request(false,UUID.randomUUID(),Map.of("kind","get","draftId",id,"revision","0","offset","0"),value->loadSource(value,"",0,nameOverride));}
    private void loadSource(JsonNode meta,String accumulated,int offset,String nameOverride){
        request(false,UUID.randomUUID(),Map.of("kind","source","draftId",meta.path("id").asText(),"revision",meta.path("revision").asText(),"offset",Integer.toString(offset)),part->{
            if(!part.path("hash").asText().equals(meta.path("sourceHash").asText()))throw new IllegalStateException();
            String text=accumulated+part.path("text").asText();if(text.length()>16000)throw new IllegalStateException();
            if(part.path("more").asBoolean()){int next=part.path("nextOffset").asInt();if(next<=offset)throw new IllegalStateException();loadSource(meta,text,next,nameOverride);return;}
            info=meta;draftId=meta.path("id").asText();loadedRevision=meta.path("revision").asLong();path=meta.path("path").asText();source=baseSource=text;sourceHash=meta.path("sourceHash").asText();
            loadedWorkspaceHash=meta.path("workspaceHash").asText(sourceHash);packageId=meta.path("packageId").asText();packageRevision=meta.path("packageRevision").asLong();packageName=nameOverride==null?meta.path("packageName").asText():nameOverride;agentId=meta.path("agentId").asText();agentName=agentId;
            staleSource=false;view=View.EDIT;runOffset=selectedRun=0;armed="";undo.clear();redo.clear();note("已按固定 revision/hash 读取；不会自动发布或运行。");build();
        });
    }
    private void field(int x,int y,int w,String name,String value,int limit,Consumer<String> listener,boolean edit){
        var input=new EditBox(font,x,y,w,20,Component.literal(name));input.setMaxLength(limit);input.setValue(value);input.setEditable(edit);
        if(edit){input.setResponder(listener);inputs.add(input);}addRenderableWidget(input);
    }
    private void editView(int x,int w){
        int half=(w-4)/2;
        field(x,52,half,"源码路径",path,128,value->{path=value;armed="";refresh();},draftId.isEmpty());
        field(x+half+4,52,half,draftId.isEmpty()?"创建后设置包名称":"包名称",packageName,128,value->{packageName=value;armed="";},!draftId.isEmpty());
        if(draftId.isEmpty()){
            button(x,76,w-104,agentId.isEmpty()?"明确选择 Agent":agentName,()->{if(agents.isEmpty())return;int index=-1;for(int i=0;i<agents.size();i++)if(agents.get(i).path("id").asText().equals(agentId))index=i;var a=agents.get((index+1)%agents.size());agentId=a.path("id").asText();agentName=a.path("name").asText();armed="";build();},()->!agents.isEmpty());
            button(x+w-100,76,48,"前页",()->context(Math.max(0,agentOffset-8),false),()->agentOffset>0);
            button(x+w-48,76,48,"后页",()->context(agentNext,false),()->agentMore);
        }else label(x,76,w,"加载 r"+loadedRevision+" / 服务端 r"+info.path("revision").asLong()+" · "+info.path("fileCount").asInt(1)+" 文件 · "+info.path("status").asText()+(hasUnsavedSource()?" · 本地未保存":"")+(staleSource?" · 版本冲突":""));
        int h=Math.max(40,height-232),y=104+h,third=(w-8)/3;
        if(draftId.isEmpty()||info.path("status").asText().equals("DRAFT")){
            var editor=MultiLineEditBox.builder().setX(x).setY(100).setPlaceholder(Component.literal("输入真实 Java / Rhino 源码，不提供默认可执行示例")).build(font,w,h,Component.literal("源码"));
            editor.setCharacterLimit(16000);editor.setValue(source);editor.setValueListener(this::changed);inputs.add(editor);addRenderableWidget(editor);
        }else addRenderableWidget(new FittingMultiLineTextWidget(x,100,w,h,Component.literal(source),font));
        button(x,y,third,draftId.isEmpty()?"创建 Task / 草稿":"保存此加载版本",()->mutate(draftId.isEmpty()?"create":"save"),()->writable()&&!source.isBlank()&&(draftId.isEmpty()?startAllowed&&!agentId.isEmpty():true));
        button(x+third+4,y,third,"发布源码（确认）",()->{if(confirm("publish","发布保存源至统一包，不执行。"))mutate("publishSource");},()->!draftId.isEmpty()&&!hasUnsavedSource()&&!staleSource);
        button(x+2*(third+4),y,w-2*(third+4),"运行（再次确认）",()->{if(confirm("run","将实际编译/start 或执行 Rhino 顶层；可能产生 Native 副作用。"))mutate("run");},()->writable()&&!draftId.isEmpty()&&!hasUnsavedSource()&&!staleSource);
        y+=24;
        int quarter=(w-12)/4;
        button(x,y,quarter,"本地工具",()->{view=View.TOOLS;armed="";build();},()->true);
        button(x+quarter+4,y,quarter,"工作区文件",()->Minecraft.getInstance().setScreen(new NativeWorkspaceScreen(this,world,draftId,loadedRevision,info.path("status").asText().equals("DRAFT"))),()->!draftId.isEmpty()&&!hasUnsavedSource()&&!staleSource);
        button(x+2*(quarter+4),y,quarter,"执行 / 历史",()->{view=View.RUNS;observe();},()->!draftId.isEmpty());
        button(x+3*(quarter+4),y,w-3*(quarter+4),"Coder",()->{
            if(hasUnsavedSource()){note("先创建或保存本地源码，再请求 Coder。");return;}
            Minecraft.getInstance().setScreen(draftId.isEmpty()?new NativeCoderScreen(this,""):new NativeCoderScreen(this,"",draftId,loadedRevision));
        },()->true);
        status(x,y+24,w,height-y-53);
    }
    private void mutate(String action){
        if(!ready())return;
        var args=new LinkedHashMap<String,String>();args.put("action",action);args.put("confirmed","true");
        if(action.equals("create")){args.put("path",path);args.put("source",source);args.put("agentId",agentId);args.put("packageId",packageId);args.put("packageRevision",Long.toString(packageRevision));}
        else{args.put("draftId",draftId);args.put("revision",Long.toString(loadedRevision));if(action.equals("save"))args.put("source",source);if(action.equals("publishSource")){args.put("packageRevision",Long.toString(packageRevision));args.put("name",packageName.isBlank()?info.path("packageName").asText():packageName);}}
        UUID operation=UUID.randomUUID();lastOperation=operation;lastWriteAction=action;String expectedSource=source;armed="";
        request(true,operation,args,value->{
            if(action.equals("create")||action.equals("save")){
                if(!source.equals(expectedSource)){note("操作已返回，期间本地内容变化；请只读检查原记录。");return;}
                baseSource=source;load(value.path("draftId").asText());
            }else{note(value.toString());view=View.RUNS;observe();}
        });
    }
    private void observe(){
        if(draftId.isEmpty())return;
        request(false,UUID.randomUUID(),Map.of("kind","get","draftId",draftId,"revision","0","offset",Integer.toString(runOffset)),value->{
            if(!value.path("id").asText().equals(draftId))throw new IllegalStateException();
            boolean changed=shownView!=view||info==null||!info.equals(value);
            boolean same=sourceHash.equals(value.path("sourceHash").asText())&&loadedWorkspaceHash.equals(value.path("workspaceHash").asText(value.path("sourceHash").asText()));
            if(!hasUnsavedSource()&&same)loadedRevision=value.path("revision").asLong();
            staleSource=!same||value.path("revision").asLong()!=loadedRevision;
            info=value;packageRevision=value.path("packageRevision").asLong();selectedRun=Math.min(selectedRun,Math.max(0,value.path("publications").size()-1));if(changed)build();else refresh();
        });
    }
    private JsonNode publication(){return info==null||selectedRun<0||selectedRun>=info.path("publications").size()?null:info.path("publications").get(selectedRun);}
    private void runsView(int x,int w){
        var run=publication();label(x,52,w,"执行记录不代表效果验收；源包 enabled 不等于已运行。");
        String text=run==null?"此页无 publication。":run.toPrettyString();
        if(info.path("legacyActive").asBoolean())text+="\n存在旧 manager 记录；仅准确原稿可明确停止旧运行。";
        addRenderableWidget(new FittingMultiLineTextWidget(x,75,w,Math.max(40,height-220),Component.literal(text),font));
        int y=Math.max(118,height-141),third=(w-8)/3;
        button(x,y,third,"本页下一执行",()->{selectedRun=(selectedRun+1)%info.path("publications").size();armed="";build();},()->info.path("publications").size()>1);
        button(x+third+4,y,third,"较新记录",()->{runOffset=Math.max(0,runOffset-8);observe();},()->runOffset>0);
        button(x+2*(third+4),y,w-2*(third+4),"较早记录",()->{runOffset=info.path("nextPublicationOffset").asInt();observe();},()->info.path("morePublications").asBoolean());
        y+=24;
        button(x,y,third,"停止（再次确认）",()->{if(confirm("stop:"+run.path("id").asText(),"仅停止此 publication，不承诺回滚所有副作用。"))stop(run);},()->run!=null&&!run.path("legacyStop").asBoolean()&&Set.of("PUBLISHED","OUTCOME_UNKNOWN","STARTING","START_RETURNED","STOPPING").contains(run.path("state").asText()));
        button(x+third+4,y,third,"诊断 / 位置",()->readText(run.path("language").asText().equals("RHINO")?"scriptDiag":"diagnostics",Map.of("publicationId",run.path("id").asText(),"publicationRevision",run.path("revision").asText()),0,"执行诊断"),()->run!=null);
        button(x+2*(third+4),y,w-2*(third+4),"保留旧稿",()->history(0),()->true);
        y+=24;
        button(x,y,third,"返回编辑",()->{view=View.EDIT;build();},()->true);
        button(x+third+4,y,third,"重载 / 放弃编辑",()->{if(leave("reload"))load(draftId);},()->true);
        button(x+2*(third+4),y,w-2*(third+4),"更多操作",()->{view=View.TOOLS;build();},()->true);
        status(x,y+24,w,height-y-52);
    }
    private void stop(JsonNode run){
        String id=run.path("draft").asText();
        request(false,UUID.randomUUID(),Map.of("kind","get","draftId",id,"revision","0","offset","0"),meta->request(true,UUID.randomUUID(),Map.of("action","stop","confirmed","true","draftId",id,"revision",meta.path("revision").asText(),"publicationId",run.path("id").asText(),"publicationRevision",run.path("revision").asText()),result->{note("原停止动作已返回；不自动再次 cleanup。");observe();}));
    }
    private void toolsView(int x,int w){
        int half=(w-4)/2;field(x,53,half,"literal 查找",search,256,value->search=value,true);field(x+half+4,53,half,"literal 替换",replacement,512,value->replacement=value,true);
        int third=(w-8)/3;
        button(x,78,third,"统计匹配",()->note("找到 "+tools.search(source,search,true).size()+" 处（区分大小写）。"),()->true);
        button(x+third+4,78,third,"全部替换",()->local(tools.replaceAll(source,search,replacement,true).text()),this::writable);
        button(x+2*(third+4),78,w-2*(third+4),"词法预览",this::previewSyntax,()->true);
        button(x,103,half,"末尾词法补全",()->{
            var options=tools.complete(source,source.length(),path.toLowerCase(Locale.ROOT).endsWith(".java")?CodeLanguage.JAVA:CodeLanguage.JAVASCRIPT);
            if(options.isEmpty()){note("没有词法补全；不是 Native 类型推断。");return;}int start=source.length();while(start>0&&Character.isJavaIdentifierPart(source.charAt(start-1)))start--;local(source.substring(0,start)+options.getFirst());
        },this::writable);
        button(x+half+4,103,half,"Rhino preflight",()->request(false,UUID.randomUUID(),Map.of("kind","scriptCheck","source",source),value->{textBody=value.toPrettyString();textTitle="Rhino preflight，不执行";styledText=null;textRequest=null;textHash="";textMore=false;historyText="";view=View.TEXT;build();}),()->!path.toLowerCase(Locale.ROOT).endsWith(".java")&&!source.isBlank());
        button(x,126,half,"撤销本地编辑",()->undo(false),()->writable()&&!undo.isEmpty());
        button(x+half+4,126,half,"重做本地编辑",()->undo(true),()->writable()&&!redo.isEmpty());
        button(x,149,half,"读取统一包另建稿",()->{if(leave("package"))copyPackage("",0,null);},()->!packageId.isEmpty()&&packageRevision>0);
        button(x+half+4,149,half,"停止匹配旧脚本",()->{if(confirm("legacy","停止准确原稿对应的旧脚本；不自动迁移或回滚。"))request(true,UUID.randomUUID(),Map.of("action","stopLegacyScript","confirmed","true","draftId",draftId,"revision",info.path("revision").asText()),value->{view=View.RUNS;observe();});},()->info!=null&&info.path("legacyCanStop").asBoolean());
        button(x,172,(w-8)/3,"返回编辑",()->{view=View.EDIT;build();},()->true);
        button(x+(w-8)/3+4,172,(w-8)/3,"刷新权限",()->context(agentOffset,false),()->true);
        button(x+2*((w-8)/3+4),172,w-2*((w-8)/3+4),"核查原操作",()->{
            if(lastWriteAction.equals("create")){if(leave("recover"))load(dev.mineagent.runtime.scripting.studio.CodeDraftService.idempotentDraftId(UUID.fromString(world),owner,lastOperation).toString());}
            else if(!draftId.isEmpty()){view=View.RUNS;observe();}
        },()->lastOperation!=null);
        status(x,196,w,height-225);
    }
    private void undo(boolean forward){
        var from=forward?redo:undo;var to=forward?undo:redo;if(from.isEmpty())return;to.addLast(source);while(to.size()>64)to.removeFirst();source=from.removeLast();armed="";build();
    }
    private void previewSyntax(){
        var result=Component.literal("");int cursor=0;
        for(var token:tools.highlight(source,path.toLowerCase(Locale.ROOT).endsWith(".java")?CodeLanguage.JAVA:CodeLanguage.JAVASCRIPT)){
            if(token.start()>cursor)result.append(Component.literal(source.substring(cursor,token.start())));
            var color=switch(token.type()){case KEYWORD->net.minecraft.ChatFormatting.AQUA;case STRING->net.minecraft.ChatFormatting.GREEN;case NUMBER->net.minecraft.ChatFormatting.GOLD;case COMMENT->net.minecraft.ChatFormatting.GRAY;};
            result.append(Component.literal(source.substring(token.start(),token.end())).withStyle(color));cursor=token.end();
        }
        if(cursor<source.length())result.append(Component.literal(source.substring(cursor)));
        styledText=result;textBody=source;textTitle="词法预览（不代表语法/运行通过）";textRequest=null;textHash="";textMore=false;historyText="";view=View.TEXT;build();
    }
    private void copyPackage(String accumulated,int offset,String expectedHash){
        request(false,UUID.randomUUID(),Map.of("kind","package","packageId",packageId,"packageRevision",Long.toString(packageRevision),"offset",Integer.toString(offset)),value->{
            String hash=value.path("hash").asText();if(expectedHash!=null&&!expectedHash.equals(hash))throw new IllegalStateException();String text=accumulated+value.path("source").asText();if(text.length()>16000)throw new IllegalStateException();
            if(value.path("more").asBoolean()){int next=value.path("nextOffset").asInt();if(next<=offset)throw new IllegalStateException();copyPackage(text,next,hash);return;}
            draftId="";loadedRevision=0;source=text;baseSource="";sourceHash="";agentId=agentName="";path=value.path("path").asText();packageName=value.path("name").asText();info=null;staleSource=false;undo.clear();redo.clear();view=View.EDIT;armed="";note("已读取真实统一包源码；请选择 Agent 并明确创建新 Task/草稿，不替换旧执行。");build();
        });
    }
    private void history(int offset){
        request(false,UUID.randomUUID(),Map.of("kind","draftHistory","draftId",draftId,"revision",info.path("revision").asText(),"offset",Integer.toString(offset)),value->{history=elements(value.path("history"));historyOffset=offset;historyRow=0;historyNext=value.path("nextOffset").asInt();historyMore=value.path("more").asBoolean();historyRevision=value.path("revision").asLong();historyDraft=draftId;view=View.HISTORY;build();});
    }
    private void historyView(int x,int w){
        label(x,52,w,"最多 20 条保留文本；序号不是历史 revision。");int rows=Math.max(1,(height-146)/24);
        for(int i=historyRow;i<Math.min(history.size(),historyRow+rows);i++){var item=history.get(i);button(x,76+(i-historyRow)*24,w,"保留序号 "+(item.path("index").asInt()+1)+" · "+item.path("hash").asText(),()->loadHistoryText(item,"",0),()->true);}
        int y=height-91,third=(w-8)/3;
        button(x,y,third,"前面的旧稿",()->{if(historyRow>0){historyRow=Math.max(0,historyRow-rows);build();}else history(Math.max(0,historyOffset-8));},()->historyRow>0||historyOffset>0);
        button(x+third+4,y,third,"后面的旧稿",()->{if(historyRow+rows<history.size()){historyRow+=rows;build();}else history(historyNext);},()->historyRow+rows<history.size()||historyMore);
        button(x+2*(third+4),y,w-2*(third+4),"返回编辑",()->{view=View.EDIT;build();},()->true);status(x,y+24,w,38);
    }
    private void loadHistoryText(JsonNode item,String accumulated,int offset){
        request(false,UUID.randomUUID(),Map.of("kind","historySource","draftId",historyDraft,"revision",Long.toString(historyRevision),"entryIndex",item.path("index").asText(),"hash",item.path("hash").asText(),"offset",Integer.toString(offset)),value->{
            if(!item.path("hash").asText().equals(value.path("hash").asText()))throw new IllegalStateException();String text=accumulated+value.path("text").asText();if(text.length()>16000)throw new IllegalStateException();
            if(value.path("more").asBoolean()){int next=value.path("nextOffset").asInt();if(next<=offset)throw new IllegalStateException();loadHistoryText(item,text,next);return;}
            historyText=text;textBody=text;styledText=null;textTitle="保留旧稿（只读）";textHash=item.path("hash").asText();textRequest=null;textMore=false;view=View.TEXT;armed="";build();
        });
    }
    private void readText(String kind,Map<String,String> args,int offset,String title){
        var values=new LinkedHashMap<>(args);values.put("kind",kind);values.put("offset",Integer.toString(offset));
        request(false,UUID.randomUUID(),values,value->{textBody=value.path("text").asText();styledText=null;textHash=value.path("hash").asText("");textOffset=offset;textNext=value.path("nextOffset").asInt();textMore=value.path("more").asBoolean();textRequest=new TextRequest(kind,Map.copyOf(args));textTitle=title;historyText="";view=View.TEXT;build();});
    }
    private void textView(int x,int w){
        label(x,52,w,textTitle);addRenderableWidget(new FittingMultiLineTextWidget(x,74,w,Math.max(45,height-173),styledText==null?Component.literal(textBody):styledText,font));
        int y=height-93,third=(w-8)/3;
        button(x,y,third,"上一段",()->readText(textRequest.kind(),textRequest.arguments(),Math.max(0,textOffset-4096),textTitle),()->textRequest!=null&&textOffset>0);
        button(x+third+4,y,third,"下一段",()->readText(textRequest.kind(),textRequest.arguments(),textNext,textTitle),()->textRequest!=null&&textMore);
        button(x+2*(third+4),y,w-2*(third+4),historyText.isEmpty()||!writable()?"返回编辑":"采用旧稿（本地）",()->{
            if(!historyText.isEmpty()&&writable()){if(!draftId.equals(historyDraft)||loadedRevision!=historyRevision||info.path("revision").asLong()!=historyRevision){note("当前稿版本已变，请重新读取。");return;}if(!confirm("history","将保留旧稿载入本地编辑，可撤销；不自动保存。"))return;local(historyText);historyText="";}
            view=View.EDIT;build();
        },()->true);status(x,y+24,w,38);if(!textHash.isEmpty())note("只读 SHA "+textHash+"；没有运行代码。");
    }
    private void request(boolean write,UUID operation,Map<String,String> args,Consumer<JsonNode> callback){
        if(!current()||pending!=null||world.isEmpty()&&!args.getOrDefault("kind","").equals("init"))return;
        pending=UUID.randomUUID();completion=callback;waitingAt=ticks;pendingWrite=write;if(write){lastOperation=operation;lastWriteAction=args.getOrDefault("action","");}refresh();
        try{ClientPacketDistributor.sendToServer(new MineAgentPayloads.NativeStudioRequest(pending,operation,world,write,args));}
        catch(Exception e){pending=null;completion=null;armed="";note("STUDIO_NATIVE_SEND_FAILED；不自动重发。");refresh();}
    }
    public static void accept(MineAgentPayloads.NativeStudioResponse response,Object wire){
        var mc=Minecraft.getInstance();if(!(mc.screen instanceof NativeCodeStudioScreen screen)||!screen.current()||screen.wire!=wire||!response.requestId().equals(screen.pending))return;
        var next=screen.completion;screen.pending=null;screen.completion=null;
        if(!screen.world.isEmpty()&&!response.worldId().isEmpty()&&!screen.world.equals(response.worldId())){screen.invalid=true;screen.armed="";screen.note("世界已改变；旧界面不再读写。请重新打开。");screen.refresh();return;}
        if(!Set.of("APPLIED","OBSERVED").contains(response.code())){screen.armed="";screen.note(response.code()+"；本地编辑保留，不自动重试。");screen.refresh();return;}
        try{var value=screen.json.readTree(response.state());if(value==null||!value.isObject())throw new IllegalArgumentException();if(next!=null)next.accept(value);}
        catch(Exception e){screen.note("STUDIO_NATIVE_RESPONSE_INVALID；本地输入保留。");}
        screen.refresh();
    }
    private static List<JsonNode> elements(JsonNode node){var list=new ArrayList<JsonNode>();if(node.isArray())node.forEach(list::add);return List.copyOf(list);}
    public void workspaceReturned(){if(!ready()||draftId.isEmpty())return;if(hasUnsavedSource()){note("主文件编辑保留；请先处理本地编辑再重载工作区。");observe();}else load(draftId,packageName);}
    @Override public void tick(){
        super.tick();ticks++;
        if(!current()){armed="";note("连接或身份变化，旧操作失效。");refresh();return;}
        if(pending!=null&&ticks-waitingAt>300){pending=null;completion=null;armed="";note(pendingWrite?"写入回执超时，结果未知；原 operation "+lastOperation+"。请只读核查，不自动重发。":"只读请求超时；可以重新读取，本地编辑保留。");refresh();}
        if(view==View.RUNS&&info!=null&&ready()&&ticks%60==0)observe();
    }
    @Override public boolean isPauseScreen(){return false;}
    @Override public void removed(){pending=null;completion=null;armed="";super.removed();}
    @Override public void onClose(){if(current()&&hasUnsavedSource()&&!confirm("close","未保存内容仍在当前窗口，返回会放弃本地编辑。"))return;pending=null;completion=null;Minecraft.getInstance().setScreen(current()?parent:null);}
}
