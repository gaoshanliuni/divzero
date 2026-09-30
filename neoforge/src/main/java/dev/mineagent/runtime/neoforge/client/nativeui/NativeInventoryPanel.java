package dev.mineagent.runtime.neoforge.client.nativeui;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** F2 and the AI profile open the same independent native inventory screen. */
public final class NativeInventoryPanel {
    private static UUID pending;private static String pendingAgent;private static Object pendingConnection;
    public static void open(NativeWorkspaceScreen host,String agent){open(agent);}
    private static void open(String agent){
        var mc=Minecraft.getInstance();if(mc.screen instanceof AgentInventoryScreen screen&&screen.getMenu().agentId.toString().equals(agent))return;
        if(pending!=null&&agent.equals(pendingAgent)&&pendingConnection==mc.getConnection())return;
        var operation=UUID.randomUUID();pending=operation;pendingAgent=agent;pendingConnection=mc.getConnection();
        WorkspacePanels.request("agent.inventoryOpen",Map.of("agentId",agent)).whenComplete((receipt,error)->{if(operation.equals(pending))pending=null;if(error!=null)NativeWorkspaceScreen.notice(error.getMessage());});
    }
    public static void attach(UIElement parent,String agent,BooleanSupplier current){
        parent.addChild(NativeUiTheme.text(dev.mineagent.runtime.neoforge.client.language.ClientLanguage.t("直接管理 AI 的背包、盔甲和双手物品。"),NativeUiTheme.TEXT,9));
        parent.addChild(NativeUiTheme.button(dev.mineagent.runtime.neoforge.client.language.ClientLanguage.t("打开背包"),()->{if(current.getAsBoolean())open(agent);}));
        if(current.getAsBoolean())open(agent);
    }
    public static void tick(){}
    /** Ignore a delayed close for an older menu, while normal server closes remain vanilla. */
    public static boolean serverClosed(int containerId){var player=Minecraft.getInstance().player;return player!=null&&player.containerMenu instanceof dev.mineagent.runtime.neoforge.ui.AgentInventoryMenu menu&&menu.containerId!=containerId;}
    public static Screen activeScreen(){return Minecraft.getInstance().screen instanceof AgentInventoryScreen screen?screen:null;}
    public static double[] smokeSlotViewport(int index){
        if(!Boolean.getBoolean("mineagent.skillSmoke")||!(activeScreen() instanceof AgentInventoryScreen screen))throw new IllegalStateException("SMOKE_INVENTORY_NOT_OPEN");var slot=screen.getMenu().getSlot(index);
        return new double[]{screen.getGuiLeft()+slot.x+8,screen.getGuiTop()+slot.y+8,0,0,screen.width,screen.height};
    }
    private NativeInventoryPanel(){}
}
