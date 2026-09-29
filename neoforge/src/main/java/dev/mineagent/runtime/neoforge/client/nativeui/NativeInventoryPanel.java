package dev.mineagent.runtime.neoforge.client.nativeui;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import java.util.Map;
import java.util.function.BooleanSupplier;

/** Both workspace and profile open the same real server-backed vanilla container. */
public final class NativeInventoryPanel {
    public static void open(NativeWorkspaceScreen host,String agent){open(agent);}
    private static void open(String agent){WorkspacePanels.request("agent.inventoryOpen",Map.of("agentId",agent)).whenComplete((receipt,error)->{if(error!=null)NativeWorkspaceScreen.notice(error.getMessage());});}
    public static void attach(UIElement parent,String agent,BooleanSupplier current){
        parent.addChild(NativeUiTheme.text(dev.mineagent.runtime.neoforge.client.language.ClientLanguage.t("直接管理 AI 的背包、盔甲和双手物品。"),NativeUiTheme.TEXT,9));
        parent.addChild(NativeUiTheme.button(dev.mineagent.runtime.neoforge.client.language.ClientLanguage.t("打开背包"),()->{if(current.getAsBoolean())open(agent);}));
        if(current.getAsBoolean())open(agent);
    }
    public static void tick(){}
    private NativeInventoryPanel(){}
}
