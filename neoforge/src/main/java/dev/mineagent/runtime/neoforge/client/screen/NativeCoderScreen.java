package dev.mineagent.runtime.neoforge.client.screen;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mineagent.runtime.neoforge.network.MineAgentPayloads;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Native fallback to the same Coder job ledger. Reads, confirmation and adoption never imply execution. */
public final class NativeCoderScreen extends Screen {
    private enum View { REQUEST,HISTORY,LOOKUP,DETAIL,TEXT,REPAIR,FILES,NATIVE }
    private record Control(Button button,BooleanSupplier allowed){}
    private final Screen parent;
    private final Object listener,wireConnection;
    private final UUID owner;
    private final String baseDraft;
    private final long baseRevision;
    private final ObjectMapper json=new ObjectMapper();
    private final List<Control> controls=new ArrayList<>();
    private final List<AbstractWidget> inputs=new ArrayList<>();
    private View view=View.REQUEST;
    private String world="",nativeSnapshot="",nativeModule="",nativeClass="",path="script.js",agentId="",agentName="",prompt,repairPrompt="",lookup="",notice="连接到服务器后才能创建请求。";
    private List<JsonNode> agents=List.of(),jobs=List.of(),candidateFiles=List.of();
    private final List<String> nativeTypes=new ArrayList<>();
    private JsonNode base,job,documentJob;
    private JsonNode filesJob,documentFile;
    private int filesAttempt,filesOffset,filesNext,filesRow;
    private boolean filesMore,workspace,sourceShareConfirmed,nativeShareConfirmed;
    private int agentOffset,agentNext,historyOffset,historyNext,historyRow,maximum=1,ticks,waitingSince,attempt=1,partIndex,docOffset,docNext;
    private boolean agentMore,historyMore,startAllowed,feeConfirmed,adoptArmed,cancelArmed,docMore,initialized,invalidContext;
    private UUID pending,lastSubmitted;
    private Consumer<JsonNode> completion;
    private FittingMultiLineTextWidget status;
    private Button feeButton,contextRefresh;
    private String documentText="",documentHash="",documentPart="metadata",jobSignature="";
    private final String[] parts={"metadata","request","dependencies","dependencyApi","dependencySources","nativeSelection","nativeContext","baseFiles","files","base","diagnostics","source","raw","validation"};
    private int diagnosticIndex=-1;

    public NativeCoderScreen(Screen parent,String seed){this(parent,seed,"",0);}
    public NativeCoderScreen(Screen parent,String seed,String baseDraft,long baseRevision){
        super(Component.literal("Coder 候选 · 不自动运行"));
        this.parent=parent;this.prompt=seed;this.baseDraft=baseDraft;this.baseRevision=baseRevision;
        var mc=Minecraft.getInstance();listener=mc.getConnection();wireConnection=mc.getConnection()==null?null:mc.getConnection().getConnection();owner=mc.player==null?null:mc.player.getUUID();
    }
    @Override protected void init(){build();if(!initialized){initialized=true;bootstrap(0,true);}}
    private boolean current(){var mc=Minecraft.getInstance();return !invalidContext&&listener!=null&&listener==mc.getConnection()&&mc.player!=null&&mc.player.getUUID().equals(owner);}
    private boolean ready(){return current()&&pending==null&&!world.isEmpty();}
    private void text(int x,int y,int w,String value){addRenderableWidget(new StringWidget(x,y,w,18,Component.literal(value),font).setMaxWidth(w));}
    private Button button(int x,int y,int w,String title,Runnable action,BooleanSupplier allowed){
        var b=addRenderableWidget(Button.builder(Component.literal(title),ignored->{if(ready()&&allowed.getAsBoolean())action.run();}).bounds(x,y,w,20).build());
        controls.add(new Control(b,allowed));return b;
    }
    private void changeNotice(String value){notice=value;if(status!=null)status.setMessage(Component.literal(value));}
    private void clearConfirmation(){feeConfirmed=adoptArmed=cancelArmed=sourceShareConfirmed=nativeShareConfirmed=false;}
    private void refresh(){
        for(var c:controls)c.button().active=ready()&&c.allowed().getAsBoolean();
        for(var input:inputs)input.active=current()&&pending==null;
        if(pending!=null)setFocused(null);
        if(status!=null)status.setMessage(Component.literal(notice));
        if(feeButton!=null)feeButton.setMessage(Component.literal(feeTitle()));
        if(contextRefresh!=null)contextRefresh.active=current()&&pending==null;
    }
    private void build(){
        clearWidgets();controls.clear();inputs.clear();status=null;feeButton=contextRefresh=null;
        int left=Math.max(8,width/2-230),w=Math.min(460,width-16),third=(w-8)/3;
        text(left,7,w,getTitle().getString());
        if(width<320||height<240){statusAt(left,32,w,Math.max(25,height-64));changeNotice("窗口空间不足。请调低 GUI 缩放或扩大窗口后使用 Coder；不会自动创建请求。");addRenderableWidget(Button.builder(Component.literal("返回"),b->onClose()).bounds(left,height-23,w,18).build());return;}
        if(world.isEmpty())contextRefresh=addRenderableWidget(Button.builder(Component.literal("重读服务器上下文"),b->{if(current()&&pending==null)bootstrap(0,true);}).bounds(left,28,third,20).build());
        else button(left,28,third,"表单 / 刷新权限",()->{view=View.REQUEST;clearConfirmation();build();bootstrap(agentOffset,false);},()->true);
        button(left+third+4,28,third,"本人历史",()->loadHistory(0),()->true);
        button(left+2*(third+4),28,w-2*(third+4),"按原 ID 查询",()->{view=View.LOOKUP;build();},()->true);
        switch(view){
            case REQUEST,REPAIR -> requestForm(left,w);
            case HISTORY -> historyView(left,w);
            case LOOKUP -> lookupView(left,w);
            case DETAIL -> detailView(left,w);
            case TEXT -> textView(left,w);
            case FILES -> filesView(left,w);
            case NATIVE -> nativeView(left,w);
        }
        addRenderableWidget(Button.builder(Component.literal("返回（不自动取消已受理请求）"),b->onClose()).bounds(left,height-23,w,18).build());
        refresh();
    }
    private void statusAt(int left,int y,int w,int h){status=addRenderableWidget(new FittingMultiLineTextWidget(left,y,w,Math.max(18,h),Component.literal(notice),font));}
    private boolean needsNative(boolean repair){return repair?job!=null&&job.path("nativeTypeCount").asInt()+job.path("nativeOverlayCount").asInt()>0:!nativeTypes.isEmpty();}
    private boolean needsSources(boolean repair){return repair?job!=null&&job.path("dependencyContextKind").asText().equals("RHINO_SOURCES"):base!=null&&base.path("dependencyCount").asInt()>0&&base.path("language").asText().equals("RHINO");}
    private String feeTitle(){return needsSources(view==View.REPAIR)&&!sourceShareConfirmed?"确认发送依赖源码":needsNative(view==View.REPAIR)&&!nativeShareConfirmed?"确认发送 Native 声明":feeConfirmed?"已确认费用 ✓":"确认次数与费用";}
    private void requestForm(int left,int w){
        boolean repair=view==View.REPAIR;
        if(repair){text(left,52,w,"修复 "+(job==null?"":job.path("id").asText())+"；原需求保留");}
        else{
            var input=new EditBox(font,left,52,w,20,Component.literal("源码路径：.js / .mjs / .java"));input.setMaxLength(128);input.setValue(path);
            if(baseDraft.isEmpty()){input.setResponder(value->{path=value;feeConfirmed=false;refresh();});inputs.add(input);}else input.setEditable(false);
            addRenderableWidget(input);
        }
        int quarter=(w-12)/4;
        if(repair)text(left,76,w,"新修复请求会另行计费，继承原 Task 预算。");
        else if(!baseDraft.isEmpty())text(left,76,w,base==null?"等待原稿来源…":"原稿 "+baseDraft+" · "+base.path("dependencyCount").asInt()+" 依赖 · Agent "+agentId);
        else{
            button(left,76,Math.max(80,w-104),agentId.isEmpty()?"选择真实 Agent":agentName,()->{
                if(agents.isEmpty())return;int index=-1;for(int i=0;i<agents.size();i++)if(agents.get(i).path("id").asText().equals(agentId))index=i;
                var next=agents.get((index+1)%agents.size());agentId=next.path("id").asText();agentName=next.path("name").asText();feeConfirmed=false;build();
            },()->!agents.isEmpty());
            button(left+w-100,76,48,"前页",()->bootstrap(Math.max(0,agentOffset-8),false),()->agentOffset>0);
            button(left+w-48,76,48,"后页",()->bootstrap(agentNext,false),()->agentMore);
        }
        int boxHeight=Math.max(32,height-228),bottom=104+boxHeight;
        var editor=MultiLineEditBox.builder().setX(left).setY(100).setPlaceholder(Component.literal(repair?"填写新增修复要求":"明确需求，最多 8192 字符"))
                .build(font,w,boxHeight,Component.literal(repair?"新增修复要求":"Coder 需求"));
        editor.setCharacterLimit(32768);editor.setValue(repair?repairPrompt:prompt);
        editor.setValueListener(value->{if(repair)repairPrompt=value;else prompt=value;feeConfirmed=false;changeNotice(value.length()>8192?"当前 "+value.length()+" 字符超过 8192；原输入保留，请编辑缩短后确认。":"需求已编辑；提交前需重新确认次数与费用。");refresh();});
        inputs.add(editor);addRenderableWidget(editor);
        int optionWidth=(w-8)/3;
        button(left,bottom,optionWidth,"最多 "+maximum+" 次",()->{maximum=maximum%3+1;feeConfirmed=false;build();},()->true);
        button(left+optionWidth+4,bottom,optionWidth,(repair?job.path("workspace").asBoolean():workspace)?"完整工作区":"单文件",()->{workspace=!workspace;feeConfirmed=false;build();},()->!repair&&(base==null||base.path("fileCount").asInt(1)<=1));
        feeButton=button(left+2*(optionWidth+4),bottom,w-2*(optionWidth+4),feeTitle(),()->{if(needsSources(repair)&&!sourceShareConfirmed){sourceShareConfirmed=true;feeConfirmed=false;changeNotice("已确认发送完整依赖 JS 源码；还需确认其它上下文及费用。");}else if(needsNative(repair)&&!nativeShareConfirmed){nativeShareConfirmed=true;feeConfirmed=false;changeNotice("已确认发送选中 Native class 的声明元数据（可能含 private 名称/signature，无方法体/常量）；还需确认费用。");}else feeConfirmed=!feeConfirmed;build();},()->true);
        if(!repair&&!baseDraft.isEmpty()){
            int third=(w-8)/3;
            button(left,bottom+24,third,diagnosticIndex<0?"不附带诊断":"附带诊断 "+(diagnosticIndex+1),()->{
                int count=base==null?0:base.path("publications").size();diagnosticIndex=count==0?-1:(diagnosticIndex+2)%(count+1)-1;feeConfirmed=false;build();
            },()->base!=null);
            button(left+third+4,bottom+24,third,"Native class "+nativeTypes.size(),()->{view=View.NATIVE;build();},()->!nativeSnapshot.isEmpty());
            button(left+2*(third+4),bottom+24,w-2*(third+4),"确认新建请求",this::submit,()->canSubmit(false));
        }else if(!repair){int half=(w-4)/2;button(left,bottom+24,half,"Native class "+nativeTypes.size(),()->{view=View.NATIVE;build();},()->!nativeSnapshot.isEmpty());button(left+half+4,bottom+24,half,"确认新建请求",this::submit,()->canSubmit(false));}
        else button(left,bottom+24,w,"确认新建修复请求",this::repair,()->canSubmit(true));
        statusAt(left,bottom+48,w,height-bottom-76);
        if((repair?repairPrompt:prompt).length()>8192)changeNotice("原输入超过 8192 字符，未截断；请编辑缩短后再提交。");
    }
    private boolean canSubmit(boolean repair){
        String value=repair?repairPrompt:prompt;
        return startAllowed&&feeConfirmed&&(!needsSources(repair)||sourceShareConfirmed)&&(!needsNative(repair)||nativeShareConfirmed)&&!value.isBlank()&&value.length()<=8192&&(repair?job!=null:!agentId.isEmpty()&&(baseDraft.isEmpty()||base!=null));
    }
    private String nativeJson(){
        try{return json.writeValueAsString(nativeTypes.stream().map(value->{int split=value.indexOf('\n');return Map.of("module",value.substring(0,split),"class",value.substring(split+1));}).toList());}
        catch(Exception failure){throw new IllegalStateException("STUDIO_CODER_NATIVE_SELECTION",failure);}
    }
    private void nativeView(int left,int w){
        text(left,52,w,"snapshot "+nativeSnapshot+" · 精确 module / binary class · "+nativeTypes.size()+" / 5");
        var module=new EditBox(font,left,75,w,20,Component.literal("module 名称"));module.setMaxLength(160);module.setValue(nativeModule);module.setResponder(v->{nativeModule=v;clearConfirmation();});inputs.add(module);addRenderableWidget(module);
        var type=new EditBox(font,left,99,w,20,Component.literal("binary class 名称"));type.setMaxLength(512);type.setValue(nativeClass);type.setResponder(v->{nativeClass=v;clearConfirmation();});inputs.add(type);addRenderableWidget(type);
        int y=123,half=(w-4)/2;
        button(left,y,half,"添加准确 class",()->{String key=nativeModule.trim()+"\n"+nativeClass.trim();if(nativeTypes.size()>=5&&!nativeTypes.contains(key)){changeNotice("Native 请求最多选择 5 个 class；F2 可选择 16 个。");return;}if(nativeModule.trim().isEmpty()||nativeClass.trim().isEmpty()||!nativeTypes.add(key))changeNotice("需要非空且未重复的 module/class。");else{nativeShareConfirmed=feeConfirmed=false;nativeClass="";changeNotice("已选入；提交前另行确认发送声明元数据与费用。");}build();},()->true);
        button(left+half+4,y,half,"清空选择",()->{nativeTypes.clear();nativeShareConfirmed=feeConfirmed=false;build();},()->!nativeTypes.isEmpty());
        int rows=Math.max(1,Math.min(5,(height-230)/23));for(int i=0;i<Math.min(rows,nativeTypes.size());i++){String value=nativeTypes.get(i);button(left,y+24+i*23,w,value.replace('\n',' '),()->{nativeTypes.remove(value);nativeShareConfirmed=feeConfirmed=false;build();},()->true);}
        int bottom=height-92;button(left,bottom,w,"返回请求表单",()->{view=View.REQUEST;build();},()->true);statusAt(left,bottom+24,w,40);
        if(nativeTypes.isEmpty())changeNotice("Native 入口不自动捕获。请先在 F2 Native API 刷新，或输入其精确 module 与 binary class；服务端会按当前 snapshot 复核。");
    }
    private void historyView(int left,int w){
        text(left,52,w,"原持久请求 · 本页 "+jobs.size()+" 项");
        int rows=Math.max(1,Math.min(4,(height-148)/25)),start=historyRow;
        for(int i=start;i<Math.min(jobs.size(),start+rows);i++){var value=jobs.get(i);button(left,76+(i-start)*25,w,value.path("path").asText()+" · "+value.path("state").asText()+" · "+value.path("id").asText(),()->loadJob(value.path("id").asText()),()->true);}
        int y=height-93;
        button(left,y,(w-4)/2,"前面的记录",()->{if(historyRow>0){historyRow=Math.max(0,historyRow-rows);build();}else loadHistory(Math.max(0,historyOffset-4));},()->historyRow>0||historyOffset>0);
        button(left+(w+4)/2,y,(w-4)/2,"后面的记录",()->{if(historyRow+rows<jobs.size()){historyRow+=rows;build();}else loadHistory(historyNext);},()->historyRow+rows<jobs.size()||historyMore);
        statusAt(left,height-69,w,40);
    }
    private void lookupView(int left,int w){
        text(left,53,w,"只读查询不会继续 Provider 请求，也不重新计费。");
        var input=new EditBox(font,left,80,w,22,Component.literal("原 Coder job ID"));input.setMaxLength(36);input.setValue(lookup);input.setResponder(value->{lookup=value;refresh();});inputs.add(input);addRenderableWidget(input);
        button(left,108,w,"读取原请求",()->loadJob(lookup.strip()),()->!lookup.isBlank());
        statusAt(left,137,w,height-167);
    }
    private JsonNode selectedAttempt(){return job==null||attempt<1||attempt>job.path("attempts").size()?null:job.path("attempts").get(attempt-1);}
    private JsonNode lastAttempt(){return job==null||job.path("attempts").isEmpty()?null:job.path("attempts").get(job.path("attempts").size()-1);}
    private void detailView(int left,int w){
        if(job==null){statusAt(left,54,w,height-84);return;}
        String state=job.path("state").asText();var a=selectedAttempt();
        text(left,52,w,state+" · "+job.path("path").asText()+" · r"+job.path("revision").asLong());
        String value="job "+job.path("id").asText()+"\nTask "+job.path("taskId").asText()+"\n"+job.path("error").asText()
                +"\n登记尝试 "+job.path("attempts").size()+" / "+job.path("maxAttempts").asInt()+"（不代表实际收费次数）"
                +(a==null?"":"\n"+a.path("state").asText()+" · "+a.path("provider").asText()+"\nrequested "+a.path("requestedModel").asText()+" / response "+a.path("responseModel").asText())
                +(a==null?"":"\n"+a.path("fileCount").asInt(1)+" 文件 · 完整 hash "+a.path("candidateHash").asText())+"\nNative raw "+job.path("nativeTypeCount").asInt()+" / overlay "+job.path("nativeOverlayCount").asInt()+" · "+job.path("nativeSelectionHash").asText()+"\n依赖 "+job.path("dependencyCount").asInt()+" · graph "+job.path("dependencyHash").asText()+"\n按 dependencies / dependencyApi / dependencySources 读取冻结声明与派发前保存的上下文。\nbase/source 是主文件；完整文件使用 files/baseFiles。\n不自动发布、运行或消除旧未知影响。";
        addRenderableWidget(new FittingMultiLineTextWidget(left,73,w,Math.max(35,height-220),Component.literal(value),font));
        int y=Math.max(112,height-143),half=(w-4)/2,third=(w-8)/3;
        button(left,y,third,"尝试 "+attempt,()->{int n=job.path("attempts").size();attempt=n==0?1:attempt%n+1;build();},()->job.path("attempts").size()>0);
        button(left+third+4,y,third,"查看: "+parts[partIndex],()->{partIndex=(partIndex+1)%parts.length;build();},()->true);
        button(left+2*(third+4),y,w-2*(third+4),"读取内容",()->readPart(job,parts[partIndex],attempt,0),()->canReadPart(parts[partIndex],a));
        y+=24;
        button(left,y,half,adoptArmed?"再次确认采用":"采用 / 收束原采用",()->{
            if(!adoptArmed){adoptArmed=true;changeNotice(job.path("workspace").asBoolean()?"请审阅 files 增删改和 dependencies。再次点击采用全部文件与声明为新稿；不覆盖原稿、不执行。":"再次点击确认采用源码与依赖声明为新 CodeDraft；不覆盖原稿、不发布或运行。");build();}else mutate("coderAdopt");
        },()->Set.of("READY","ADOPTING").contains(state)&&lastAttempt()!=null&&a!=null&&a.path("id").equals(lastAttempt().path("id"))&&a.path("state").asText().equals("ACCEPTED"));
        button(left+half+4,y,half,cancelArmed?"再次确认取消":"取消后续工作",()->{
            if(!cancelArmed){cancelArmed=true;changeNotice("已发送的 Provider 请求可能仍计费。再次点击取消后续工作。");build();}else mutate("coderCancel");
        },()->Set.of("PENDING","GENERATING","VALIDATING","READY").contains(state));
        y+=24;
        String adopted=job.path("adoptedDraft").asText();
        if(!adopted.isEmpty())button(left,y,half,"打开已采用的草稿",()->openAdopted(adopted),()->true);
        else button(left,y,half,"基于确定失败新修复",()->{view=View.REPAIR;repairPrompt="";maximum=1;clearConfirmation();build();},
                ()->state.equals("FAILED")&&lastAttempt()!=null&&lastAttempt().path("state").asText().equals("REJECTED")&&!lastAttempt().path("rawHash").asText().isEmpty());
        button(left+half+4,y,half,"只读刷新",()->loadJob(job.path("id").asText()),()->true);
        statusAt(left,y+24,w,Math.max(18,height-y-52));
        if(!adopted.isEmpty())changeNotice("已采用的新稿 "+adopted+"。可按准确 ID 打开，不受最近 5 条限制；未发布或运行。");
    }
    private void openAdopted(String id){
        if(!ready())return;
        if(parent instanceof NativeCodeStudioScreen screen&&screen.hasUnsavedSource()){changeNotice("原编辑器有未保存内容，先返回保存；候选与原输入均保留。");return;}
        Minecraft.getInstance().setScreen(new NativeCodeStudioScreen(parent,id,world));
    }
    private boolean canReadPart(String part,JsonNode a){
        return switch(part){case "raw"->a!=null&&!a.path("rawHash").asText().isEmpty();case "source","files"->a!=null&&!a.path("sourceHash").asText().isEmpty();case "validation"->a!=null;case "dependencyApi"->a!=null&&!a.path("dependencyApiHash").asText().isEmpty();case "dependencySources"->a!=null&&!a.path("dependencySourcesHash").asText().isEmpty();case "nativeContext"->a!=null&&!a.path("nativeContextHash").asText().isEmpty();case "nativeSelection"->job!=null&&job.path("nativeTypeCount").asInt()+job.path("nativeOverlayCount").asInt()>0;default->true;};
    }
    private void textView(int left,int w){
        text(left,52,w,documentPart+" · 原记录 r"+(documentJob==null?0:documentJob.path("revision").asLong())+" · offset "+docOffset);
        addRenderableWidget(new FittingMultiLineTextWidget(left,74,w,Math.max(40,height-174),Component.literal(documentText),font));
        int y=height-94,third=(w-8)/3;
        button(left,y,third,"上一段",()->{if(documentFile!=null)readFile(documentJob,documentFile,Math.max(0,docOffset-1024));else readPart(documentJob,documentPart,attempt,Math.max(0,docOffset-1024));},()->docOffset>0);
        button(left+third+4,y,third,"下一段",()->{if(documentFile!=null)readFile(documentJob,documentFile,docNext);else readPart(documentJob,documentPart,attempt,docNext);},()->docMore);
        button(left+2*(third+4),y,w-2*(third+4),"返回当前详情",()->loadJob(documentJob.path("id").asText()),()->documentJob!=null);
        statusAt(left,y+24,w,40);changeNotice("只读文本 · SHA "+documentHash+"；没有保存、采用或执行。");
    }
    private void bootstrap(int offset,boolean first){
        request(false,UUID.randomUUID(),Map.of("kind","init","offset",Integer.toString(offset)),value->{
            String observed=value.path("worldId").asText();if(observed.isEmpty())throw new IllegalStateException("STUDIO_NATIVE_WORLD_MISSING");
            world=observed;String nextNative=value.path("nativeSnapshot").asText();if(!nativeSnapshot.equals(nextNative)){nativeSnapshot=nextNative;nativeTypes.clear();nativeShareConfirmed=feeConfirmed=false;}agents=elements(value.path("agents"));agentOffset=offset;agentNext=value.path("nextOffset").asInt();agentMore=value.path("more").asBoolean();
            startAllowed=value.path("startAllowed").asBoolean();
            if(first&&!baseDraft.isEmpty())request(false,UUID.randomUUID(),Map.of("kind","get","draftId",baseDraft,"revision",Long.toString(baseRevision),"offset","0"),info->{
                base=info;path=info.path("path").asText();agentId=info.path("agentId").asText();workspace=info.path("workspace").asBoolean()||info.path("fileCount").asInt(1)>1;clearConfirmation();changeNotice(info.path("dependencyCount").asInt()>0&&info.path("language").asText().equals("RHINO")?"Rhino 依赖请求会向 Provider 发送整个依赖图的完整 JS 源码（含私有实现）。请先单独确认源码发送，再确认费用。":"已读取准确保存稿。完整工作区发送全部文件；Java 依赖携带真实 API。新候选须明确采用，不启动依赖。");build();
            });
            else{changeNotice(startAllowed?"请选择 Agent 与调用次数。仅确定拒绝可按预算修复，未知结果不重发。":"没有 START_TASK 权限；只能读取既有候选。权限变化后可刷新。");build();}
        });
    }
    private void submit(){
        if(!canSubmit(false)||!ready())return;var args=new LinkedHashMap<String,String>();
        args.put("action","coderSubmit");args.put("confirmed","true");args.put("path",path);args.put("prompt",prompt);args.put("agentId",agentId);
        args.put("baseDraft",baseDraft);args.put("baseRevision",baseDraft.isEmpty()?"0":Long.toString(base.path("revision").asLong()));args.put("maxAttempts",Integer.toString(maximum));
        args.put("workspace",Boolean.toString(workspace||base!=null&&base.path("fileCount").asInt(1)>1));args.put("shareDependencySources",Boolean.toString(needsSources(false)&&sourceShareConfirmed));args.put("nativeSnapshot",needsNative(false)?nativeSnapshot:"");args.put("nativeClasses",nativeJson());args.put("shareNativeContext",Boolean.toString(needsNative(false)&&nativeShareConfirmed));
        JsonNode diagnostic=base==null||diagnosticIndex<0||diagnosticIndex>=base.path("publications").size()?null:base.path("publications").get(diagnosticIndex);
        args.put("diagnosticKind",diagnostic==null?"":diagnostic.path("language").asText());args.put("publicationId",diagnostic==null?"":diagnostic.path("id").asText());
        newJob(args);
    }
    private void repair(){
        if(!canSubmit(true)||!ready())return;
        newJob(Map.of("action","coderRepair","confirmed","true","jobId",job.path("id").asText(),"revision",job.path("revision").asText(),"prompt",repairPrompt,"maxAttempts",Integer.toString(maximum),"shareDependencySources",Boolean.toString(needsSources(true)&&sourceShareConfirmed),"shareNativeContext",Boolean.toString(needsNative(true)&&nativeShareConfirmed)));
    }
    private void newJob(Map<String,String> args){
        UUID operation=UUID.randomUUID();lastSubmitted=operation;lookup=operation.toString();clearConfirmation();
        changeNotice("本次原请求 ID "+operation+"。结果未知时只读查询，不自动重发。");
        request(true,operation,args,value->{selectJob(value);changeNotice("已受理 "+value.path("id").asText()+"；不是完成证明。");});
    }
    private void mutate(String action){
        var args=new LinkedHashMap<String,String>();args.put("action",action);args.put("confirmed","true");args.put("jobId",job.path("id").asText());args.put("revision",job.path("revision").asText());
        if(action.equals("coderAdopt"))args.put("sourceHash",lastAttempt().path("candidateHash").asText(lastAttempt().path("sourceHash").asText()));String id=job.path("id").asText();clearConfirmation();
        request(true,UUID.randomUUID(),args,value->{changeNotice(value.has("draftId")?"已采用 "+value.path("draftId").asText()+"；未发布/运行。":"已返回操作回执，请观察原账本。");loadJob(id);});
    }
    private void loadHistory(int offset){
        request(false,UUID.randomUUID(),Map.of("kind","coderList","offset",Integer.toString(offset)),value->{jobs=elements(value.path("jobs"));historyOffset=offset;historyRow=0;historyNext=value.path("nextOffset").asInt();historyMore=value.path("more").asBoolean();view=View.HISTORY;clearConfirmation();changeNotice("只读历史，不触发 Provider。");build();});
    }
    private void loadJob(String id){
        try{UUID.fromString(id);}catch(Exception e){changeNotice("请输入有效的原请求 UUID。");return;}
        request(false,UUID.randomUUID(),Map.of("kind","coderGet","jobId",id),this::selectJob);
    }
    private void selectJob(JsonNode value){
        String signature=value.toString();boolean changed=!signature.equals(jobSignature)||view!=View.DETAIL;
        boolean latest=job==null||!job.path("id").asText().equals(value.path("id").asText())||attempt>=job.path("attempts").size();
        job=value;jobSignature=signature;lookup=value.path("id").asText();view=View.DETAIL;attempt=Math.max(1,Math.min(attempt,value.path("attempts").size()));
        if(latest)attempt=Math.max(1,value.path("attempts").size());
        if(changed){clearConfirmation();build();}else refresh();
    }
    private void readPart(JsonNode selected,String part,int ordinal,int offset){
        if(part.equals("files")||part.equals("baseFiles")){loadFiles(selected,part.equals("baseFiles")?0:ordinal,offset);return;}
        if(selected==null)return;request(false,UUID.randomUUID(),Map.of("kind","coderText","jobId",selected.path("id").asText(),"revision",selected.path("revision").asText(),"part",part,"attempt",Integer.toString(ordinal),"offset",Integer.toString(offset)),value->{
            documentFile=null;documentJob=selected;documentPart=part;documentText=value.path("text").asText();documentHash=value.path("hash").asText();docOffset=offset;docNext=value.path("nextOffset").asInt();docMore=value.path("more").asBoolean();attempt=ordinal;view=View.TEXT;build();
        });
    }
    private void loadFiles(JsonNode selected,int ordinal,int offset){
        if(selected==null)return;request(false,UUID.randomUUID(),Map.of("kind","coderFiles","jobId",selected.path("id").asText(),"revision",selected.path("revision").asText(),"attempt",Integer.toString(ordinal),"offset",Integer.toString(offset)),value->{filesJob=selected;filesAttempt=ordinal;filesOffset=offset;filesNext=value.path("nextOffset").asInt();filesMore=value.path("more").asBoolean();candidateFiles=elements(value.path("files"));filesRow=0;view=View.FILES;clearConfirmation();build();});
    }
    private void filesView(int x,int w){
        text(x,52,w,"完整文件集 / 增删改，删除项可只读查看原文件。");int rows=Math.max(1,(height-154)/24);
        for(int i=filesRow;i<Math.min(candidateFiles.size(),filesRow+rows);i++){var file=candidateFiles.get(i);button(x,76+(i-filesRow)*24,w,file.path("change").asText()+" · "+file.path("path").asText(),()->readFile(filesJob,file,0),()->true);}
        int y=height-95,third=(w-8)/3;
        button(x,y,third,"前面文件",()->{if(filesRow>0){filesRow=Math.max(0,filesRow-rows);build();}else loadFiles(filesJob,filesAttempt,Math.max(0,filesOffset-8));},()->filesRow>0||filesOffset>0);
        button(x+third+4,y,third,"后面文件",()->{if(filesRow+rows<candidateFiles.size()){filesRow+=rows;build();}else loadFiles(filesJob,filesAttempt,filesNext);},()->filesRow+rows<candidateFiles.size()||filesMore);
        button(x+2*(third+4),y,w-2*(third+4),"返回当前详情",()->loadJob(filesJob.path("id").asText()),()->true);statusAt(x,y+24,w,38);
    }
    private void readFile(JsonNode selected,JsonNode file,int offset){
        request(false,UUID.randomUUID(),Map.of("kind","coderFile","jobId",selected.path("id").asText(),"revision",selected.path("revision").asText(),"attempt",file.path("sourceAttempt").asText(),"path",file.path("path").asText(),"hash",file.path("hash").asText(),"offset",Integer.toString(offset)),value->{if(!file.path("hash").asText().equals(value.path("hash").asText()))throw new IllegalStateException();documentJob=selected;documentFile=file;documentPart=file.path("change").asText()+" "+file.path("path").asText();documentText=value.path("text").asText();documentHash=value.path("hash").asText();docOffset=offset;docNext=value.path("nextOffset").asInt();docMore=value.path("more").asBoolean();view=View.TEXT;build();});
    }
    private void request(boolean write,UUID operation,Map<String,String> args,Consumer<JsonNode> callback){
        if(!current()||pending!=null||world.isEmpty()&&!args.getOrDefault("kind","").equals("init"))return;
        pending=UUID.randomUUID();completion=callback;waitingSince=ticks;refresh();
        try{ClientPacketDistributor.sendToServer(new MineAgentPayloads.NativeStudioRequest(pending,operation,world,write,args));}
        catch(Exception e){pending=null;completion=null;changeNotice("STUDIO_NATIVE_SEND_FAILED；不自动重发。");refresh();}
    }
    public static void accept(MineAgentPayloads.NativeStudioResponse response,Object connection){
        var mc=Minecraft.getInstance();if(!(mc.screen instanceof NativeCoderScreen screen)||!screen.current()||screen.wireConnection!=connection||!response.requestId().equals(screen.pending))return;
        var callback=screen.completion;screen.pending=null;screen.completion=null;
        if(!screen.world.isEmpty()&&!response.worldId().isEmpty()&&!screen.world.equals(response.worldId())){screen.invalidContext=true;screen.clearConfirmation();screen.changeNotice("世界已改变，旧请求不再作用于当前界面。请重新打开。");screen.refresh();return;}
        if(!Set.of("OBSERVED","APPLIED").contains(response.code())){screen.changeNotice(response.code()+"；结果未知时只读核查原 ID，不自动再发。");screen.refresh();return;}
        try{JsonNode value=screen.json.readTree(response.state());if(value==null||!value.isObject())throw new IllegalArgumentException();if(callback!=null)callback.accept(value);}
        catch(Exception e){screen.changeNotice("STUDIO_NATIVE_RESPONSE_INVALID；请只读核查原请求。");}
        screen.refresh();
    }
    private static List<JsonNode> elements(JsonNode value){var values=new ArrayList<JsonNode>();if(value.isArray())value.forEach(values::add);return List.copyOf(values);}
    @Override public void tick(){
        super.tick();ticks++;
        if(!current()){clearConfirmation();changeNotice("连接或身份已改变；旧操作已失效。");refresh();return;}
        if(pending!=null&&ticks-waitingSince>300){pending=null;completion=null;clearConfirmation();changeNotice("回执等待超时，结果未知。保留原 ID "+(lastSubmitted==null?lookup:lastSubmitted)+"，不自动重发。");refresh();}
        if(view==View.DETAIL&&job!=null&&pending==null&&ticks%60==0&&Set.of("PENDING","GENERATING","VALIDATING").contains(job.path("state").asText()))loadJob(job.path("id").asText());
    }
    @Override public boolean isPauseScreen(){return false;}
    @Override public void removed(){pending=null;completion=null;clearConfirmation();super.removed();}
    @Override public void onClose(){pending=null;completion=null;Minecraft.getInstance().setScreen(current()?parent:null);}
}
