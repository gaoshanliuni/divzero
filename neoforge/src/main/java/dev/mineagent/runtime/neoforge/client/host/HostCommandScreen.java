package dev.mineagent.runtime.neoforge.client.host;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.util.*;
/** Full, paginated exact script review. The model cannot press the local approval control. */
public final class HostCommandScreen extends Screen {
    private final Screen parent;private final ClientHostCommands.Pending pending;private final BitSet read=new BitSet();private int page,pages;private String status="每条命令都需本机确认，输出将返回当前 AI 对话。";private Button approve;private final List<AbstractWidget> body=new ArrayList<>();
    HostCommandScreen(Screen parent,ClientHostCommands.Pending pending){super(Component.literal("本机 PowerShell"));this.parent=parent;this.pending=pending;}
    ClientHostCommands.Pending pending(){return pending;}
    public UUID operation(){return pending.request.operation();}
    public String script(){return pending.request.script();}
    public String commandHash(){return pending.request.sha256();}
    @Override protected void init(){int left=Math.max(16,width/2-310),w=Math.min(620,width-32);addRenderableWidget(new StringWidget(left,15,w,22,getTitle(),font));addRenderableWidget(new StringWidget(left,40,w,18,Component.literal(pending.request.purpose()),font));addRenderableWidget(new StringWidget(left,height-92,w,18,Component.literal("使用当前 Windows 账户 · 非沙箱 · 最长 "+pending.request.timeoutSeconds()+" 秒"),font));
        approve=addRenderableWidget(Button.builder(Component.literal(pending.started?"执行中…":"执行这条命令"),b->{if(read.cardinality()==pages){ClientHostCommands.approve(pending);status="执行中；停止不撤销已发生的操作。";rebuildWidgets();}}).bounds(left,height-44,w/2-4,22).build());
        var cancel=addRenderableWidget(Button.builder(Component.literal(pending.started?"停止":"取消"),b->onClose()).bounds(left+w/2+4,height-44,w/2-4,22).build());drawPage(left,w);setInitialFocus(cancel);}
    private void drawPage(int left,int w){for(var v:body)removeWidget(v);body.clear();int perPage=Math.max(1,(height-190)/12);var lines=font.split(Component.literal(pending.request.script()),w-16);pages=Math.max(1,(lines.size()+perPage-1)/perPage);page=Math.min(page,pages-1);read.set(page);
        for(int i=page*perPage;i<Math.min(lines.size(),(page+1)*perPage);i++){var line=lines.get(i);var text=new StringBuilder();line.accept((index,style,code)->{text.appendCodePoint(code);return true;});var widget=new StringWidget(left,68+(i-page*perPage)*12,Math.max(1,font.width(text.toString())),12,Component.literal(text.toString()),font);body.add(addRenderableWidget(widget));}
        body.add(addRenderableWidget(Button.builder(Component.literal("上一页"),b->{if(page>0)page--;drawPage(left,w);}).bounds(left,height-118,90,20).build()));body.add(addRenderableWidget(new StringWidget(left+95,height-118,w-190,20,Component.literal((page+1)+" / "+pages),font)));body.add(addRenderableWidget(Button.builder(Component.literal("下一页"),b->{if(page+1<pages)page++;drawPage(left,w);}).bounds(left+w-90,height-118,90,20).build()));body.add(addRenderableWidget(new StringWidget(left,height-70,w,18,Component.literal(status),font)));approve.active=!pending.started&&read.cardinality()==pages&&pending.live.get();}
    void completed(Map<String,Object> result){status="结果："+result.get("status");if(Minecraft.getInstance().screen==this)Minecraft.getInstance().setScreen(pending.connection==Minecraft.getInstance().getConnection()?parent:null);}
    @Override public boolean isPauseScreen(){return false;}
    @Override public void onClose(){ClientHostCommands.cancel(pending);Minecraft.getInstance().setScreen(pending.connection==Minecraft.getInstance().getConnection()?parent:null);}
}
