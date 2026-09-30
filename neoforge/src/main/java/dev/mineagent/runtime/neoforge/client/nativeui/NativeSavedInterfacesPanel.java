package dev.mineagent.runtime.neoforge.client.nativeui;

import com.google.gson.*;
import com.lowdragmc.lowdraglib2.gui.ui.elements.*;
import dev.mineagent.runtime.neoforge.client.language.ClientLanguage;
import net.minecraft.network.chat.Component;
import java.util.*;

/** Persistent per-AI library; a closed SCREEN need not be recreated by a model after reconnecting. */
final class NativeSavedInterfacesPanel {
    private final NativeWorkspaceScreen host;private final WorkspaceWindow window;private final String agent;
    private final ScrollerView list;private final TextElement notice=WorkspacePanels.text("");private final Set<String> uncertain=new HashSet<>();
    private int offset,next;private boolean busy,more;
    static void open(NativeWorkspaceScreen host,String agent){UUID.fromString(agent);if(!host.revealWindow("saved-interfaces-"+agent))new NativeSavedInterfacesPanel(host,agent);}
    private NativeSavedInterfacesPanel(NativeWorkspaceScreen host,String agent){this.host=host;this.agent=agent;window=host.window("saved-interfaces-"+agent,host.agentName(agent)+" · "+t("已保存界面"),510,375);window.body.addChild(WorkspacePanels.text(t("查看已保存界面不会调用模型。显示动态界面需要 KubeJS。")));window.body.addChild(notice);list=WorkspacePanels.scroller(window.body);var tools=WorkspacePanels.row();tools.getLayout().height(25);window.body.addChild(tools);tools.addChild(button("上一页",()->{if(!busy){offset=Math.max(0,offset-16);load();}}));tools.addChild(button("下一页",()->{if(!busy&&more){offset=next;load();}}));tools.addChild(button("刷新",this::load));load();}
    private boolean current(){return host.activeContext()&&!window.closed();}
    private void load(){if(busy)return;busy=true;WorkspacePanels.request("interface.read",Map.of("agentId",agent,"offset",Integer.toString(offset))).whenComplete((receipt,error)->{busy=false;if(!current())return;if(error!=null){WorkspacePanels.failure(notice,error);return;}var value=WorkspacePanels.state(receipt);more=value.get("more").getAsBoolean();next=value.get("nextOffset").getAsInt();list.clearAllScrollViewChildren();for(var raw:value.getAsJsonArray("definitions"))draw(raw.getAsJsonObject());notice.setText(Component.literal(t("已保存界面")+" · "+value.get("total").getAsInt()));});}
    private void draw(JsonObject item){String id=item.get("id").getAsString();var card=WorkspacePanels.card(list,item.get("title").getAsString());card.addChild(WorkspacePanels.text(NativeUiTheme.option(item.get("surface").getAsString())+" · "+id+" · r"+item.get("revision").getAsLong()));card.addChild(WorkspacePanels.text(item.get("dimension").getAsString()));var controls=WorkspacePanels.row();controls.getLayout().height(25);card.addChild(controls);
        for(String action:List.of("show","hide")){var button=button(action.equals("show")?"显示":"隐藏",()->control(item,action));button.setActive(!uncertain.contains(id)&&net.neoforged.fml.ModList.get().isLoaded("kubejs"));controls.addChild(button);}
        controls.addChild(button("读取实际状态",()->inspect(id)));if(uncertain.contains(id))card.addChild(WorkspacePanels.text(t("结果待核对，请读取实际状态。")));
    }
    private void control(JsonObject item,String action){String id=item.get("id").getAsString();if(busy||uncertain.contains(id))return;busy=true;WorkspacePanels.request("interface.control",Map.of("agentId",agent,"id",id,"revision",item.get("revision").getAsString(),"action",action)).whenComplete((receipt,error)->{busy=false;if(error!=null)uncertain.add(id);if(!current())return;if(error!=null){WorkspacePanels.failure(notice,error);return;}var value=WorkspacePanels.state(receipt);if(!value.has("status")||!value.get("status").getAsString().equals("APPLIED")){uncertain.add(id);NativeReadout.show(host,"interface-outcome-"+agent+id,"界面操作回执",value);}load();});}
    private void inspect(String id){if(busy)return;busy=true;WorkspacePanels.request("interface.read",Map.of("agentId",agent,"id",id)).whenComplete((receipt,error)->{busy=false;if(!current())return;if(error!=null){WorkspacePanels.failure(notice,error);return;}var value=WorkspacePanels.state(receipt);if(!value.get("pending").getAsBoolean())uncertain.remove(id);NativeReadout.show(host,"saved-interface-state-"+agent+id,"界面实际状态",value);load();});}
    private static String t(String text){return ClientLanguage.t(text);}
    private static Button button(String text,Runnable action){return NativeUiTheme.button(t(text),action);}
}
