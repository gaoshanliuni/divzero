package dev.mineagent.runtime.neoforge.client.nativeui;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextElement;
import com.lowdragmc.lowdraglib2.gui.ui.rendering.IGUIContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.*;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
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
    private final Screen host=mc().screen;
    private final Object connection=mc().getConnection();
    private final BooleanSupplier current;
    private final TextElement status;
    private final UIElement canvas;
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
        var scroll=WorkspacePanels.scroller(parent);
        canvas=new UIElement(){@Override protected void drawBackgroundAdditional(IGUIContext context){
            if(bridge==null||!valid())return;
            bridge.embedAt(Math.round(getPositionX()),Math.round(getPositionY()),host.width,host.height);
            int mx=(int)mc().mouseHandler.getScaledXPos(mc().getWindow()),my=(int)mc().mouseHandler.getScaledYPos(mc().getWindow());
            if(!hovered()){mx=my=-10000;}
            bridge.extractBackground(context.graphics,mx,my,0);bridge.extractRenderState(context.graphics,mx,my,0);bridge.embeddedTooltip(context.graphics,mx,my);
        }};
        canvas.setId("native-inventory-slots");canvas.getLayout().width(176).height(269).flexShrink(0).alignSelf(dev.vfyjxf.taffy.style.AlignItems.CENTER);
        scroll.addScrollViewChild(canvas);
    }
    private static String t(String value){return dev.mineagent.runtime.neoforge.client.language.ClientLanguage.t(value);}
    private boolean valid(){return current.getAsBoolean()&&mc().screen==host&&mc().getConnection()==connection;}
    private void request(){
        if(opening||!valid())return;opening=true;
        WorkspacePanels.request("agent.inventoryOpen",Map.of("agentId",agent)).whenComplete((receipt,error)->{
            opening=false;if(!valid())return;if(error!=null){WorkspacePanels.failure(status,error);return;}
            status.setText(Component.literal(t("可直接拖放、拆分和穿戴装备；战斗继续。")));
        });
    }
    @SubscribeEvent(priority=EventPriority.HIGHEST) public static void opening(ScreenEvent.Opening event){
        if(!(event.getNewScreen() instanceof AgentInventoryScreen screen))return;
        event.setCanceled(true);
        var panel=active;if(panel==null||!screen.getMenu().agentId.toString().equals(panel.agent)||!panel.valid()){
            if(mc().getConnection()!=null)mc().getConnection().send(new ServerboundContainerClosePacket(screen.getMenu().containerId));
            if(mc().player!=null&&mc().player.containerMenu==screen.getMenu())mc().player.containerMenu=mc().player.inventoryMenu;return;
        }
        panel.bridge=screen;screen.setEmbedded();screen.init(panel.host.width,panel.host.height);mc().player.containerMenu=screen.getMenu();event.setCanceled(true);
    }
    private boolean hovered(){
        if(!valid()||bridge==null||mc().player==null||mc().player.containerMenu!=bridge.getMenu()||!(host instanceof NativeInputScreen nativeScreen))return false;
        var ui=nativeScreen.modularUI;float x=(float)mc().mouseHandler.getScaledXPos(mc().getWindow()),y=(float)mc().mouseHandler.getScaledYPos(mc().getWindow());ui.refreshHoveredElementAtScreen(x,y);
        for(var hit=ui.getLastHoveredElement();hit!=null;hit=hit.getParent())if(hit==canvas)return true;return false;
    }
    static boolean click(Screen host,MouseButtonEvent event,boolean twice){var p=active;if(p==null||p.host!=host||!p.hovered())return false;p.pressed=true;p.bridge.mouseClicked(event,twice);return true;}
    static boolean drag(Screen host,MouseButtonEvent event,double dx,double dy){var p=active;if(p==null||p.host!=host||!p.pressed||!p.valid()||p.bridge==null)return false;p.bridge.mouseDragged(event,dx,dy);return true;}
    static boolean release(Screen host,MouseButtonEvent event){var p=active;if(p==null||p.host!=host||!p.pressed||p.bridge==null)return false;p.pressed=false;p.bridge.mouseReleased(event);return true;}
    static boolean key(Screen host,KeyEvent event){var p=active;if(p==null||p.host!=host||event.key()==org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE||!p.hovered())return false;return p.bridge.keyPressed(event);}
    public static void tick(){if(active!=null&&!active.valid())close();}
    static void removed(Screen screen){if(active!=null&&active.host==screen)close();}
    private static void close(){
        var p=active;active=null;if(p==null||p.bridge==null||mc().player==null||mc().getConnection()!=p.connection)return;
        if(mc().player.containerMenu==p.bridge.getMenu()){mc().getConnection().send(new ServerboundContainerClosePacket(p.bridge.getMenu().containerId));mc().player.containerMenu=mc().player.inventoryMenu;}
    }
    public static Screen activeScreen(){return active!=null&&active.valid()?active.bridge:null;}
}
