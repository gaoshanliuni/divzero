package dev.mineagent.runtime.neoforge.client.body;
import dev.mineagent.runtime.core.task.PlayerControlPlan;
import dev.mineagent.runtime.neoforge.network.PlayerBodyPayloads;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.*;
import net.minecraft.network.chat.Component;
import java.util.*;

/** Actual local consent UI. Mouse/key release is awaited before a server grant can begin input. */
public final class PlayerBodyReviewScreen extends Screen {
    private final PlayerBodyPayloads.Offer offer;private final PlayerControlPlan plan;private final Object wire,player,level;
    private final long expires=System.nanoTime()+120_000_000_000L;private boolean requested,handedOff,closed;private int quiet,page;private Button start;private StringWidget status;
    public PlayerBodyReviewScreen(PlayerBodyPayloads.Offer offer,PlayerControlPlan plan,Object wire){super(Component.literal("AI 接管本人 · 审核完整操作计划"));this.offer=offer;this.plan=plan;this.wire=wire;player=Minecraft.getInstance().player;level=Minecraft.getInstance().level;}
    public UUID operation(){return offer.operation();}
    boolean localConsent(){return requested&&!closed;}
    void handedOff(){handedOff=true;}
    void reject(String reason){requested=false;quiet=0;if(status!=null)status.setMessage(Component.literal(reason));}
    private boolean current(){var mc=Minecraft.getInstance();return !closed&&mc.getConnection()!=null&&mc.getConnection().getConnection()==wire&&mc.player==player&&mc.level==level&&System.nanoTime()<expires;}
    @Override protected void init(){int left=Math.max(8,width/2-260),w=Math.min(520,width-16),rows=Math.max(1,(height-155)/15);page=Math.max(0,Math.min(page,(plan.steps().size()-1)/rows));
        addRenderableWidget(new StringWidget(left,10,w,18,getTitle(),font));addRenderableWidget(new StringWidget(left,30,w,16,Component.literal(offer.agent()+" · 控制你当前真实角色，不是独立 AI 身体"),font));
        for(int i=0;i<rows&&page*rows+i<plan.steps().size();i++){int n=page*rows+i;addRenderableWidget(new StringWidget(left,52+i*15,w,14,Component.literal((n+1)+". "+PlayerBodyControlClient.label(plan.steps().get(n))),font));}
        if(plan.steps().size()>rows){addRenderableWidget(Button.builder(Component.literal("上一页"),b->{page=Math.max(0,page-1);rebuildWidgets();}).bounds(left,height-97,75,20).build());addRenderableWidget(Button.builder(Component.literal("下一页"),b->{page=Math.min((plan.steps().size()-1)/rows,page+1);rebuildWidgets();}).bounds(left+80,height-97,75,20).build());}
        addRenderableWidget(new StringWidget(left,height-74,w,14,Component.literal("仅 Esc 手动退出；其它游戏键鼠忽略。失焦、死亡、断线自动释放。"),font));
        status=addRenderableWidget(new StringWidget(left,height-56,w,14,Component.literal("会发生真实移动和交互；不保证寻路成功。请先逐项核对。"),font));
        start=addRenderableWidget(Button.builder(Component.literal("确认并开始接管本人"),b->{requested=true;quiet=0;status.setMessage(Component.literal("请松开键鼠；等待本机释放输入和服务器批准…"));}).bounds(left,height-33,w/2-4,22).build());
        var cancel=addRenderableWidget(Button.builder(Component.literal("取消，不接管"),b->onClose()).bounds(left+w/2+4,height-33,w/2-4,22).build());setInitialFocus(cancel);
    }
    @Override public void tick(){if(!current()){serverStopped();return;}var mc=Minecraft.getInstance();start.active=!requested&&mc.isWindowActive();if(requested){if(!mc.isWindowActive()){onClose();return;}if(PlayerBodyControlClient.physicalInputsReleased())quiet++;else quiet=0;if(quiet>=3)PlayerBodyControlClient.approve(this,offer,plan,wire);}}
    public void serverStopped(){closed=true;requested=false;if(Minecraft.getInstance().screen==this)Minecraft.getInstance().setScreen(null);}
    @Override public void onClose(){if(!closed){closed=true;PlayerBodyControlClient.decline(offer,"REVIEW_CANCELLED");}Minecraft.getInstance().setScreen(null);}
    @Override public void removed(){if(!handedOff&&!closed){closed=true;PlayerBodyControlClient.decline(offer,"REVIEW_CLOSED");}super.removed();}
    @Override public boolean isPauseScreen(){return false;}
}
