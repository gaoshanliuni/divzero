package dev.mineagent.runtime.neoforge.client.screen;

import com.fasterxml.jackson.databind.*;
import dev.mineagent.runtime.neoforge.network.MineAgentPayloads;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import java.util.*;
import java.util.function.*;

/** Explicit per-file workspace editor. File operations use the full draft revision, never per-file guessed versions. */
public final class NativeWorkspaceScreen extends Screen {
    private enum View { FILES,EDITOR,HISTORY,DEPENDENCIES,DEPENDENCY_EDITOR }
    private record Control(Button button,BooleanSupplier allowed){}
    private final NativeCodeStudioScreen parent;
    private final String world,draftId;
    private final Object listener,wire;
    private final UUID owner;
    private final boolean editable;
    private long revision,version;
    private View view=View.FILES;
    private List<JsonNode> files=List.of(),history=List.of(),dependencies=List.of(),dependencyCandidates=List.of();
    private JsonNode selectedDependency,selectedCandidate;
    private int dependencyOffset,dependencyNext,dependencyRow,candidateOffset,candidateNext,candidateRow;
    private boolean dependencyMore,candidateMore,supportedDependencies;
    private String dependencyInfo="";
    private final List<Control> controls=new ArrayList<>();
    private final List<AbstractWidget> inputs=new ArrayList<>();
    private int offset,next,row,historyOffset,historyNext,historyRow,ticks,waiting;
    private boolean more,historyMore,invalid,initialized,entry,newFile;
    private String workspaceHash="",filePath="",originalPath="",source="",original="",target="",armed="",notice="只读加载工作区，不发布或执行。";
    private UUID pending;
    private Consumer<JsonNode> completion;
    private FittingMultiLineTextWidget status;
    public NativeWorkspaceScreen(NativeCodeStudioScreen parent,String world,String draftId,long revision,boolean editable){
        super(Component.literal("工作区文件与入口"));this.parent=parent;this.world=world;this.draftId=draftId;this.revision=revision;this.editable=editable;
        var mc=Minecraft.getInstance();listener=mc.getConnection();wire=mc.getConnection()==null?null:mc.getConnection().getConnection();owner=mc.player==null?null:mc.player.getUUID();
    }
    private boolean current(){var mc=Minecraft.getInstance();return !invalid&&listener!=null&&listener==mc.getConnection()&&mc.player!=null&&mc.player.getUUID().equals(owner);}
    private boolean ready(){return current()&&pending==null;}
    private boolean dirty(){return view==View.EDITOR&&version==0&&(!source.equals(original)||newFile&&!filePath.isEmpty());}
    private boolean confirm(String key,String message){if(!armed.equals(key)){armed=key;note(message+" 再次点击确认。");return false;}armed="";return true;}
    private boolean leave(String key){return !dirty()||confirm(key,"将放弃当前文件的未保存编辑。");}
    private void note(String text){notice=text;if(status!=null)status.setMessage(Component.literal(text));}
    private void label(int x,int y,int w,String text){addRenderableWidget(new StringWidget(x,y,w,18,Component.literal(text),font).setMaxWidth(w));}
    private void button(int x,int y,int w,String text,Runnable action,BooleanSupplier allowed){
        var b=addRenderableWidget(Button.builder(Component.literal(text),v->{if(ready()&&allowed.getAsBoolean())try{action.run();}catch(Exception e){note("工作区操作未完成，请检查参数。");}}).bounds(x,y,w,20).build());controls.add(new Control(b,allowed));
    }
    private void refresh(){for(var c:controls)c.button().active=ready()&&c.allowed().getAsBoolean();for(var input:inputs)input.active=ready();if(pending!=null)setFocused(null);if(status!=null)status.setMessage(Component.literal(notice));}
    @Override protected void init(){build();if(!initialized){initialized=true;list(0,0);}}
    private void build(){
        clearWidgets();controls.clear();inputs.clear();status=null;int x=Math.max(8,width/2-240),w=Math.min(480,width-16),third=(w-8)/3;
        label(x,7,w,getTitle().getString()+" · head r"+revision+(version>0?" / 历史 r"+version:""));
        if(width<320||height<240){status=addRenderableWidget(new FittingMultiLineTextWidget(x,35,w,Math.max(25,height-65),Component.literal("窗口过小，请调低 GUI 缩放；不执行操作。"),font));}
        else{
            button(x,28,third,"当前文件",()->{if(leave("current"))list(0,0);},()->true);
            button(x+third+4,28,third,"添加文件",()->{if(leave("add")){version=0;newFile=true;entry=false;filePath=originalPath=source=original=target="";view=View.EDITOR;armed="";build();}},()->editable&&version==0);
            button(x+2*(third+4),28,w-2*(third+4),"完整版本历史",()->{if(leave("history"))history(0);},()->true);
            if(view==View.EDITOR)edit(x,w);else if(view==View.HISTORY)historyView(x,w);else if(view==View.DEPENDENCIES)dependencyList(x,w);else if(view==View.DEPENDENCY_EDITOR)dependencyEditor(x,w);else fileList(x,w);
        }
        addRenderableWidget(Button.builder(Component.literal("返回主文件（不自动发布/运行）"),b->onClose()).bounds(x,height-23,w,18).build());refresh();
    }
    private void footer(int x,int y,int w){status=addRenderableWidget(new FittingMultiLineTextWidget(x,y,w,Math.max(18,height-y-29),Component.literal(notice),font));}
    private void fileList(int x,int w){
        button(x,52,w,"Java / Rhino 依赖声明与诊断",()->dependencyRead(0),()->true);int rows=Math.max(1,(height-160)/24);
        for(int i=row;i<Math.min(files.size(),row+rows);i++){var f=files.get(i);button(x,76+(i-row)*24,w,f.path("path").asText()+(f.path("entry").asBoolean()?" [entry]":""),()->load(f,"",0),()->true);}
        int y=height-101,third=(w-8)/3;
        button(x,y,third,"前面文件",()->{if(row>0){row=Math.max(0,row-rows);build();}else list(Math.max(0,offset-8),version);},()->row>0||offset>0);
        button(x+third+4,y,third,"后面文件",()->{if(row+rows<files.size()){row+=rows;build();}else list(next,version);},()->row+rows<files.size()||more);
        button(x+2*(third+4),y,w-2*(third+4),"恢复完整历史",()->{if(confirm("restore","将此历史文件集/入口/依赖声明恢复为新的草稿 revision。"))change("restore","",Long.toString(version),"");},()->editable&&version>0);
        footer(x,y+24,w);note("workspace SHA "+workspaceHash+"；"+(version>0?"历史只读":"改动只保存草稿"));
    }
    private void edit(int x,int w){
        var name=new EditBox(font,x,52,w,20,Component.literal("文件路径"));name.setMaxLength(128);name.setValue(filePath);name.setEditable(newFile&&editable&&version==0);
        if(newFile&&editable&&version==0){name.setResponder(v->{filePath=v;armed="";refresh();});inputs.add(name);}addRenderableWidget(name);
        int h=Math.max(40,height-220),y=80+h;
        if(editable&&version==0){var box=MultiLineEditBox.builder().setX(x).setY(76).setPlaceholder(Component.literal("文件源码，最多 16000 字符")).build(font,w,h,Component.literal("源码"));box.setCharacterLimit(16000);box.setValue(source);box.setValueListener(v->{source=v;armed="";});inputs.add(box);addRenderableWidget(box);}
        else addRenderableWidget(new FittingMultiLineTextWidget(x,76,w,h,Component.literal(source),font));
        int half=(w-4)/2;
        button(x,y,half,newFile?"添加（不覆盖同名）":"保存文件",()->change(newFile?"add":"put",filePath,"",source),()->editable&&version==0&&!filePath.isBlank());
        button(x+half+4,y,half,"设为入口",()->{if(source.equals(original)&&confirm("entry","改变入口，不自动执行。"))change("entry",originalPath,"","");},()->editable&&version==0&&!newFile&&!entry);
        var rename=new EditBox(font,x,y+24,half,20,Component.literal("新路径"));rename.setMaxLength(128);rename.setValue(target);rename.setResponder(v->{target=v;armed="";});rename.setEditable(editable&&version==0&&!newFile);if(editable&&version==0&&!newFile)inputs.add(rename);addRenderableWidget(rename);
        button(x+half+4,y+24,half,"明确改名",()->{if(!source.equals(original)){note("先保存文件编辑，再改名。");return;}if(confirm("rename","仅更名，不改写引用。"))change("rename",originalPath,target,"");},()->editable&&version==0&&!newFile&&!target.isBlank());
        button(x,y+48,half,"删除文件",()->{if(source.equals(original)&&confirm("remove","删除此文件；保留完整旧版本引用。"))change("remove",originalPath,"","");},()->editable&&version==0&&!newFile&&!entry);
        button(x+half+4,y+48,half,"返回文件列表",()->{if(leave("files"))list(offset,version);},()->true);
        footer(x,y+72,w);
    }
    private void list(int page,long selectedVersion){
        read("workspaceFiles",Map.of("version",Long.toString(selectedVersion),"offset",Integer.toString(page)),data->{version=selectedVersion;workspaceHash=data.path("workspaceHash").asText();files=elements(data.path("files"));offset=page;row=0;next=data.path("nextOffset").asInt();more=data.path("more").asBoolean();view=View.FILES;armed="";build();});
    }
    private void load(JsonNode file,String accumulated,int position){
        read("workspaceSource",Map.of("version",Long.toString(version),"path",file.path("path").asText(),"hash",file.path("hash").asText(),"offset",Integer.toString(position)),data->{
            if(!workspaceHash.equals(data.path("workspaceHash").asText())||!file.path("hash").asText().equals(data.path("hash").asText()))throw new IllegalStateException();
            String text=accumulated+data.path("text").asText();if(text.length()>16000)throw new IllegalStateException();
            if(data.path("more").asBoolean()){int next=data.path("nextOffset").asInt();if(next<=position)throw new IllegalStateException();load(file,text,next);return;}
            filePath=originalPath=file.path("path").asText();source=original=text;entry=file.path("entry").asBoolean();newFile=false;target="";view=View.EDITOR;armed="";build();
        });
    }
    private void change(String action,String path,String target,String body){
        var args=new LinkedHashMap<String,String>();args.put("action","workspaceFile");args.put("confirmed","true");args.put("draftId",draftId);args.put("revision",Long.toString(revision));args.put("change",action);args.put("path",path);args.put("target",target);args.put("source",body);
        request(true,args,data->{revision=data.path("revision").asLong();version=0;source=original="";newFile=false;note("工作区已保存 r"+revision+"，不自动发布/运行。");list(0,0);});
    }
    private void history(int page){
        read("workspaceHistory",Map.of("version","0","offset",Integer.toString(page)),data->{history=elements(data.path("history"));historyOffset=page;historyRow=0;historyNext=data.path("nextOffset").asInt();historyMore=data.path("more").asBoolean();view=View.HISTORY;build();});
    }
    private void historyView(int x,int w){
        label(x,52,w,"仅记录真实完整文件集版本，不补造旧单文件历史。");int rows=Math.max(1,(height-154)/24);
        for(int i=historyRow;i<Math.min(history.size(),historyRow+rows);i++){var v=history.get(i);button(x,76+(i-historyRow)*24,w,"r"+v.path("revision").asLong()+" · "+v.path("entry").asText()+" · "+v.path("files").asInt()+" 文件 · "+v.path("dependencyCount").asInt()+" 依赖",()->list(0,v.path("revision").asLong()),()->true);}
        int y=height-95,half=(w-4)/2;
        button(x,y,half,"前面版本",()->{if(historyRow>0){historyRow=Math.max(0,historyRow-rows);build();}else history(Math.max(0,historyOffset-4));},()->historyRow>0||historyOffset>0);
        button(x+half+4,y,half,"后面版本",()->{if(historyRow+rows<history.size()){historyRow+=rows;build();}else history(historyNext);},()->historyRow+rows<history.size()||historyMore);footer(x,y+24,w);
    }
    private void dependencyRead(int page){
        read("dependencies",Map.of("version",Long.toString(version),"offset",Integer.toString(page)),data->{
            dependencies=elements(data.path("dependencies"));dependencyOffset=page;dependencyNext=data.path("nextOffset").asInt();dependencyMore=data.path("more").asBoolean();supportedDependencies=data.path("supported").asBoolean();dependencyRow=0;selectedDependency=null;
            dependencyInfo=data.path("resolution").asText()+" · "+data.path("total").asInt()+" 直接依赖 · graph "+data.path("graphHash").asText();view=View.DEPENDENCIES;armed="";build();note(dependencyInfo);
        });
    }
    private void dependencyList(int x,int w){
        button(x,52,w,"从当前包目录选择依赖",()->dependencyCandidates(0),()->editable&&version==0&&supportedDependencies);
        int rows=Math.max(1,(height-178)/24);
        for(int i=dependencyRow;i<Math.min(dependencies.size(),dependencyRow+rows);i++){
            var dep=dependencies.get(i);button(x,76+(i-dependencyRow)*24,w,dep.path("packageId").asText()+" @ "+dep.path("version").asText(),()->{
                selectedDependency=dep;armed="";note(dep.path("state").asText()+" · "+dep.path("className").asText()+"\npublication "+dep.path("publicationId").asText()+" · 间接依赖 "+dep.path("transitiveCount").asInt()+" · 引用消费者 "+dep.path("consumerCount").asInt()+" "+dep.path("consumers"));refresh();
            },()->true);
        }
        int y=height-96,third=(w-8)/3;
        button(x,y,third,"前面依赖",()->{if(dependencyRow>0){dependencyRow=Math.max(0,dependencyRow-rows);build();}else dependencyRead(Math.max(0,dependencyOffset-4));},()->dependencyRow>0||dependencyOffset>0);
        button(x+third+4,y,third,"后面依赖",()->{if(dependencyRow+rows<dependencies.size()){dependencyRow+=rows;build();}else dependencyRead(dependencyNext);},()->dependencyRow+rows<dependencies.size()||dependencyMore);
        button(x+2*(third+4),y,w-2*(third+4),"移除选中声明",()->{if(confirm("removeDependency","仅从此草稿删除依赖声明，不停止已有实例。"))dependencyChange("remove",selectedDependency.path("packageId").asText(),"",0);},()->editable&&version==0&&selectedDependency!=null);
        footer(x,y+24,w);
    }
    private void dependencyCandidates(int page){
        read("dependencyCandidates",Map.of("offset",Integer.toString(page)),data->{dependencyCandidates=elements(data.path("items"));candidateOffset=page;candidateNext=data.path("nextOffset").asInt();candidateMore=data.path("more").asBoolean();candidateRow=0;selectedCandidate=null;view=View.DEPENDENCY_EDITOR;armed="";build();note("选择当前可归属的 "+data.path("targetSide").asText()+" 依赖；不手输内部 ID 或版本。");});
    }
    private void dependencyEditor(int x,int w){
        label(x,52,w,"选择可归属的准确依赖包与版本（不自动安装或启动）");int rows=Math.max(1,(height-190)/24);
        for(int i=candidateRow;i<Math.min(dependencyCandidates.size(),candidateRow+rows);i++){var item=dependencyCandidates.get(i);button(x,76+(i-candidateRow)*24,w,item.path("name").asText()+" · "+item.path("targetSide").asText()+" "+item.path("language").asText()+" · "+item.path("version").asText(),()->{selectedCandidate=item;armed="";note(item.path("packageId").asText()+" @ "+item.path("version").asText());refresh();},()->true);}
        int y=height-112,third=(w-8)/3;button(x,y,third,"前面候选",()->{if(candidateRow>0){candidateRow=Math.max(0,candidateRow-rows);build();}else dependencyCandidates(Math.max(0,candidateOffset-8));},()->candidateRow>0||candidateOffset>0);button(x+third+4,y,third,"后面候选",()->{if(candidateRow+rows<dependencyCandidates.size()){candidateRow+=rows;build();}else dependencyCandidates(candidateNext);},()->candidateRow+rows<dependencyCandidates.size()||candidateMore);
        button(x+2*(third+4),y,w-2*(third+4),"保存所选依赖",()->{if(selectedCandidate!=null&&confirm("putDependency","添加或修改所选准确版本，保留包含旧声明的完整历史。"))dependencyChange("put",selectedCandidate.path("packageId").asText(),selectedCandidate.path("version").asText(),selectedCandidate.path("revision").asLong());},()->editable&&version==0&&selectedCandidate!=null);
        footer(x,y+24,w);
    }
    private void dependencyChange(String change,String id,String value,long packageRevision){
        UUID.fromString(id);if(change.equals("put")&&!value.matches("[0-9A-Za-z_.+-]{1,64}"))throw new IllegalArgumentException();
        var args=new LinkedHashMap<String,String>();args.put("action","dependency");args.put("confirmed","true");args.put("draftId",draftId);args.put("revision",Long.toString(revision));args.put("change",change);args.put("packageId",id);args.put("version",value);
        if(change.equals("put")){if(packageRevision<1)throw new IllegalArgumentException();args.put("packageRevision",Long.toString(packageRevision));}
        request(true,args,data->{revision=data.path("revision").asLong();version=0;selectedCandidate=null;dependencyRead(0);});
    }
    private void read(String kind,Map<String,String> args,Consumer<JsonNode> next){var values=new LinkedHashMap<>(args);values.put("kind",kind);values.put("draftId",draftId);values.put("revision",Long.toString(revision));request(false,values,next);}
    private void request(boolean write,Map<String,String> args,Consumer<JsonNode> next){
        if(!ready())return;pending=UUID.randomUUID();completion=next;waiting=ticks;refresh();
        try{ClientPacketDistributor.sendToServer(new MineAgentPayloads.NativeStudioRequest(pending,UUID.randomUUID(),world,write,args));}catch(Exception e){pending=null;completion=null;note("STUDIO_WORKSPACE_SEND_FAILED；不自动重发。");refresh();}
    }
    public static void accept(MineAgentPayloads.NativeStudioResponse result,Object connection){
        var mc=Minecraft.getInstance();if(!(mc.screen instanceof NativeWorkspaceScreen screen)||!screen.current()||screen.wire!=connection||!result.requestId().equals(screen.pending))return;
        var next=screen.completion;screen.pending=null;screen.completion=null;
        if(!result.worldId().isEmpty()&&!screen.world.equals(result.worldId())){screen.invalid=true;screen.note("世界已改变，请重新打开工作区。");screen.refresh();return;}
        if(!Set.of("APPLIED","OBSERVED").contains(result.code())){screen.note(result.code()+"；文件编辑保留，请返回主稿重新核查 revision。");screen.refresh();return;}
        try{var data=new ObjectMapper().readTree(result.state());if(data==null||!data.isObject())throw new IllegalStateException();if(next!=null)next.accept(data);}catch(Exception e){screen.note("STUDIO_WORKSPACE_RESPONSE_INVALID；不自动重发。");}screen.refresh();
    }
    private static List<JsonNode> elements(JsonNode n){var values=new ArrayList<JsonNode>();if(n.isArray())n.forEach(values::add);return List.copyOf(values);}
    @Override public void tick(){super.tick();ticks++;if(!current()){note("连接已变化，旧文件操作失效。");refresh();return;}if(pending!=null&&ticks-waiting>300){pending=null;completion=null;armed="";note("操作回执超时；返回主稿只读核查，不自动重发。");refresh();}}
    @Override public boolean isPauseScreen(){return false;}
    @Override public void removed(){pending=null;completion=null;armed="";super.removed();}
    @Override public void onClose(){if(current()&&!leave("close"))return;pending=null;completion=null;Minecraft.getInstance().setScreen(current()?parent:null);if(current())parent.workspaceReturned();}
}
