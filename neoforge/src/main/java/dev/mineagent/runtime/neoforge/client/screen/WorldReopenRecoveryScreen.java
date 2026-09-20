package dev.mineagent.runtime.neoforge.client.screen;

import dev.mineagent.runtime.core.packages.WorldReopenPlans;
import dev.mineagent.runtime.neoforge.content.WorldReopenBootstrap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.*;
import net.minecraft.network.chat.Component;
import java.nio.file.Path;
import java.util.*;

/** Native bootstrap UI: available before a server session exists, never exposes a local-operator action to a web page or Agent. */
public final class WorldReopenRecoveryScreen extends Screen {
    private final Screen parent;private int offset;private String message="仅管理本地 saves 内尚未生效的计划，不初始化 Worker。";
    public WorldReopenRecoveryScreen(Screen parent){super(Component.literal("MineAgent · 待重开恢复"));this.parent=parent;}
    private boolean offline(){var mc=Minecraft.getInstance();return mc.getConnection()==null&&mc.getSingleplayerServer()==null;}
    @Override protected void init(){
        int left=Math.max(12,width/2-240),w=Math.min(480,width-24);addRenderableWidget(new StringWidget(left,18,w,22,getTitle(),font));
        addRenderableWidget(new StringWidget(left,45,w,22,Component.literal(message),font));int rows=Math.max(1,Math.min(4,(height-138)/49));
        try{
            var plans=WorldReopenBootstrap.recoveryPlans(offset);int shown=0;Path base=Minecraft.getInstance().getLevelSource().getBaseDir().toAbsolutePath().normalize();
            for(var plan:plans.stream().limit(rows).toList()){
                int y=75+shown++*49;String name=Path.of(plan.savePath()).getFileName().toString();if(name.length()>26)name=name.substring(0,26);
                addRenderableWidget(new StringWidget(left,y,w-104,18,Component.literal(name+" · "+plan.state()),font));
                addRenderableWidget(new StringWidget(left,y+19,w-104,17,Component.literal(plan.input().operation().toString()),font));
                var button=addRenderableWidget(Button.builder(Component.literal("撤销计划…"),b->confirm(plan)).bounds(left+w-100,y,100,24).build());button.active=offline()&&!plan.nativeStarted()&&!plan.state().equals("CANCEL_REQUESTED")&&Path.of(plan.savePath()).toAbsolutePath().normalize().startsWith(base);
            }
            if(plans.isEmpty())addRenderableWidget(new StringWidget(left,82,w,25,Component.literal("没有待处理的世界重开计划。"),font));
            var previous=addRenderableWidget(Button.builder(Component.literal("上一页"),b->{offset=Math.max(0,offset-rows);rebuildWidgets();}).bounds(left,height-56,w/3-4,22).build());previous.active=offset>0;
            addRenderableWidget(Button.builder(Component.literal("只读刷新"),b->rebuildWidgets()).bounds(left+w/3,height-56,w/3-4,22).build());
            var next=addRenderableWidget(Button.builder(Component.literal("下一页"),b->{offset+=rows;rebuildWidgets();}).bounds(left+2*w/3,height-56,w/3-4,22).build());next.active=plans.size()>rows;
        }catch(Exception failure){message=code(failure);addRenderableWidget(new StringWidget(left,88,w,26,Component.literal(message),font));addRenderableWidget(Button.builder(Component.literal("重新读取"),b->rebuildWidgets()).bounds(left,height-56,w,22).build());}
        addRenderableWidget(Button.builder(Component.literal("返回"),b->onClose()).bounds(left,height-29,w,22).build());
    }
    private void confirm(WorldReopenPlans.Plan plan){if(!offline())return;Minecraft.getInstance().setScreen(new ConfirmScreen(answer->{
        if(answer)try{if(!offline())throw new IllegalStateException("WORLD_REOPEN_WORLD_IN_USE");WorldReopenBootstrap.requestOfflineCancel(plan,Minecraft.getInstance().getLevelSource().getBaseDir(),"LOCAL_CLIENT_OPERATOR");message="已请求撤销；正常打开原世界后保存并确认结果，不自动启动。";}catch(Exception failure){message=code(failure);}
        Minecraft.getInstance().setScreen(this);
    },Component.literal("撤销这个尚未生效的计划？"),Component.literal("operation "+plan.input().operation()+"。只恢复该计划前的数据包选择，不删除世界文件或卸载已装入维度；保存的世界生成数据已变化时会拒绝。")));}
    private static String code(Exception failure){String code=Objects.toString(failure.getMessage(),"");return code.matches("WORLD_REOPEN_[A-Z0-9_]{1,80}")?code:"WORLD_REOPEN_RECOVERY_UNAVAILABLE";}
    @Override public void onClose(){Minecraft.getInstance().setScreen(parent);}
}
