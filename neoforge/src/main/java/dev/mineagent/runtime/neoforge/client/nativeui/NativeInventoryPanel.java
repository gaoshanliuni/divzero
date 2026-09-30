package dev.mineagent.runtime.neoforge.client.nativeui;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextElement;
import com.lowdragmc.lowdraglib2.gui.ui.rendering.IGUIContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.*;
import net.minecraft.network.chat.Component;
import dev.mineagent.runtime.neoforge.network.MineAgentPayloads;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;
import java.util.Map;
import java.util.function.BooleanSupplier;

/** Native container viewport in an existing LDLib2 page; packets and slot rules remain vanilla. */
@EventBusSubscriber(modid="mineagent_runtime",value=Dist.CLIENT)
public final class NativeInventoryPanel {
    private static NativeInventoryPanel active;
    private final String agent;
    private final java.util.UUID viewToken=java.util.UUID.randomUUID();
    private final Screen host=mc().screen;
    private final Object connection=mc().getConnection();
    private final BooleanSupplier current;
    private final TextElement status;
    private final UIElement canvas;
    private final com.lowdragmc.lowdraglib2.gui.ui.elements.ScrollerView scroll;
    private AgentInventoryScreen bridge;
    private boolean pressed,opening;
    private static Minecraft mc(){return Minecraft.getInstance();}
    public static void open(NativeWorkspaceScreen host,String agent){
        var window=host.window("inventory-"+agent,t("背包"),390,390);
        attach(window.body,agent,()->!window.closed()&&mc().screen==host);
    }
    public static void attach(UIElement parent,String agent,BooleanSupplier current){close();active=new NativeInventoryPanel(parent,agent,current);active.request();}
    private NativeInventoryPanel(UIElement parent,String agent,BooleanSupplier current){
        this.agent=agent;this.current=current;
        status=NativeUiTheme.text(t("读取背包…"),NativeUiTheme.MUTED,9);parent.addChild(status);
        scroll=WorkspacePanels.scroller(parent);
        canvas=new UIElement(){@Override protected void drawBackgroundAdditional(IGUIContext context){
            if(bridge==null||!valid()||!(context instanceof com.lowdragmc.lowdraglib2.gui.ui.rendering.GUIContext nativeContext))return;
            bridge.embedAt(Math.round(getPositionX()),Math.round(getPositionY()),host.width,host.height);
            int mx=(int)mc().mouseHandler.getScaledXPos(mc().getWindow()),my=(int)mc().mouseHandler.getScaledYPos(mc().getWindow());
            if(!hovered()){mx=my=-10000;}
            var graphics=nativeContext.graphics;graphics.pose().pushMatrix();
            try{graphics.pose().identity();bridge.extractBackground(graphics,mx,my,0);bridge.extractRenderState(graphics,mx,my,0);}finally{graphics.pose().popMatrix();}
        }};
        canvas.setId("native-inventory-slots");canvas.getLayout().width(176).height(269).flexShrink(0).alignSelf(dev.vfyjxf.taffy.style.AlignItems.CENTER);
        scroll.addScrollViewChild(canvas);
    }
    private static String t(String value){return dev.mineagent.runtime.neoforge.client.language.ClientLanguage.t(value);}
    private boolean valid(){return current.getAsBoolean()&&mc().screen==host&&mc().getConnection()==connection;}
    private void request(){
        if(opening||!valid())return;opening=true;
        WorkspacePanels.request("agent.inventoryOpen",Map.of("agentId",agent,"viewToken",viewToken.toString())).whenComplete((receipt,error)->{
            opening=false;if(!valid())return;if(error!=null){WorkspacePanels.failure(status,error);return;}
            status.setText(Component.literal(t("可直接拖放、拆分和穿戴装备；战斗继续。")));
        });
    }
    @SubscribeEvent(priority=EventPriority.HIGHEST) public static void opening(ScreenEvent.Opening event){
        if(!(event.getNewScreen() instanceof AgentInventoryScreen screen))return;
        event.setCanceled(true);
        var panel=active;if(panel==null||!screen.getMenu().agentId.toString().equals(panel.agent)||!screen.getMenu().viewToken.equals(panel.viewToken)||!panel.valid()){
            if(mc().getConnection()!=null)detach(screen);
            if(mc().player!=null&&mc().player.containerMenu==screen.getMenu())mc().player.containerMenu=mc().player.inventoryMenu;return;
        }
        panel.bridge=screen;screen.setEmbedded();screen.init(panel.host.width,panel.host.height);mc().player.containerMenu=screen.getMenu();event.setCanceled(true);
    }
    private boolean hovered(){
        if(!valid()||bridge==null||mc().player==null||mc().player.containerMenu!=bridge.getMenu()||!(host instanceof NativeInputScreen nativeScreen))return false;
        var ui=nativeScreen.modularUI;float x=(float)mc().mouseHandler.getScaledXPos(mc().getWindow()),y=(float)mc().mouseHandler.getScaledYPos(mc().getWindow());ui.refreshHoveredElementAtScreen(x,y);
        for(var hit=ui.getLastHoveredElement();hit!=null;hit=hit.getParent())if(hit==canvas)return true;return false;
    }
    static boolean click(Screen host,MouseButtonEvent event,boolean twice){var p=active;if(p==null||p.host!=host||!p.hovered())return false;((NativeInputScreen)host).modularUI.clearFocus();p.pressed=true;p.bridge.mouseClicked(event,twice);return true;}
    static boolean drag(Screen host,MouseButtonEvent event,double dx,double dy){var p=active;if(p==null||p.host!=host||!p.pressed||!p.valid()||p.bridge==null)return false;p.bridge.mouseDragged(event,dx,dy);return true;}
    static boolean release(Screen host,MouseButtonEvent event){var p=active;if(p==null||p.host!=host||!p.pressed||p.bridge==null)return false;p.pressed=false;p.bridge.mouseReleased(event);return true;}
    static boolean key(Screen host,KeyEvent event){var p=active;if(p==null||p.host!=host||event.key()==org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE||!p.hovered())return false;for(var e=((NativeInputScreen)host).modularUI.getFocusedElement();e!=null;e=e.getParent())if(e instanceof com.lowdragmc.lowdraglib2.gui.ui.elements.TextField||e instanceof com.lowdragmc.lowdraglib2.gui.ui.elements.TextArea)return false;return p.bridge.keyPressed(event);}
    public static void tick(){if(active!=null&&!active.valid())close();}
    static void removed(Screen screen){if(active!=null&&active.host==screen)close();}
    private static void close(){
        var p=active;active=null;if(p==null||p.bridge==null||mc().player==null||mc().getConnection()!=p.connection)return;
        if(mc().player.containerMenu==p.bridge.getMenu()){detach(p.bridge);mc().player.containerMenu=mc().player.inventoryMenu;}
    }
    private static void detach(AgentInventoryScreen screen){ClientPacketDistributor.sendToServer(new MineAgentPayloads.InventoryDetach(screen.getMenu().containerId,screen.getMenu().viewToken));}
    /** Called after vanilla's packet-thread check; keep the workspace when the server closes its embedded menu. */
    public static boolean serverClosed(int containerId){
        if(mc().player==null||!(mc().player.containerMenu instanceof dev.mineagent.runtime.neoforge.ui.AgentInventoryMenu menu))return false;
        if(menu.containerId!=containerId)return true;
        var panel=active;if(panel==null||panel.bridge==null||panel.bridge.getMenu()!=menu)return false;
        mc().player.containerMenu=mc().player.inventoryMenu;panel.bridge=null;panel.pressed=false;
        panel.status.setText(Component.literal(t("背包已关闭；请重新打开背包标签。")));return true;
    }
    static void tooltip(Screen host,net.minecraft.client.gui.GuiGraphicsExtractor graphics,int x,int y){var p=active;if(p!=null&&p.host==host&&p.hovered())p.bridge.embeddedTooltip(graphics,x,y);}
    public static double[] smokeSlotViewport(int index){
        if(!Boolean.getBoolean("mineagent.skillSmoke")||active==null||active.bridge==null)throw new IllegalStateException("SMOKE_INVENTORY_NOT_OPEN");
        var p=active;var slot=p.bridge.getMenu().getSlot(index);var viewport=p.scroll.viewPort;
        return new double[]{p.bridge.getGuiLeft()+slot.x+8,p.bridge.getGuiTop()+slot.y+8,viewport.getContentX(),viewport.getContentY(),viewport.getContentWidth(),viewport.getContentHeight()};
    }
    public static Screen activeScreen(){return active!=null&&active.valid()?active.bridge:null;}
}
