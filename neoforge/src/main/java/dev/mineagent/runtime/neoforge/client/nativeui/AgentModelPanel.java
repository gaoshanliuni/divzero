package dev.mineagent.runtime.neoforge.client.nativeui;

import com.google.gson.*;
import com.lowdragmc.lowdraglib2.gui.ui.*;
import com.lowdragmc.lowdraglib2.gui.ui.elements.*;
import dev.mineagent.runtime.neoforge.client.language.ClientLanguage;
import net.minecraft.network.chat.Component;
import java.util.*;

/** Per-agent routing uses existing server permissions and revision checks. */
final class AgentModelPanel {
    private final WorkspaceWindow window;private final String agent;
    private final TextElement status=WorkspacePanels.text("");private final Selector<Choice> mode=new Selector<>(),models=new Selector<>();
    private final TextField custom=new TextField(),search=new TextField();private final UIElement specific=new UIElement();
    private JsonObject saved;private boolean saving,loading;private int offset;private long epoch;private long pollAt;
    private record Choice(String id,String label){@Override public String toString(){return label;}}
    static void open(NativeWorkspaceScreen host,String id,String name){if(host.revealWindow("model-"+id))return;new AgentModelPanel(host.window("model-"+id,name+" · "+t("模型"),450,345),id);}
    private AgentModelPanel(WorkspaceWindow window,String agent){
        this.window=window;this.agent=agent;var body=window.body;body.addChild(WorkspacePanels.text(t("仅影响这个 AI；使用现有 API URL / Key。")));body.addChild(status);
        mode.setCandidates(List.of(new Choice("DEFAULT",t("使用默认模型")),new Choice("CUSTOM",t("指定这个 AI 的模型"))));mode.setValue(mode.getCandidates().getFirst(),false);mode.setOnValueChanged(value->specific.setDisplay(value.id().equals("CUSTOM")));mode.getLayout().height(25).widthPercent(100);body.addChild(mode);
        specific.getLayout().flex(1).widthPercent(100).gapAll(6);body.addChild(specific);models.getLayout().height(26).widthPercent(100);specific.addChild(models);models.setOnValueChanged(value->{custom.setDisplay(value.id().isEmpty());if(!value.id().isEmpty())custom.setText(value.id(),false);});
        custom.getLayout().height(24).widthPercent(100);custom.textFieldStyle(s->s.placeholder(Component.literal(t("自定义模型名称"))));specific.addChild(custom);custom.setDisplay(false);
        var tools=WorkspacePanels.row();tools.getLayout().height(24);specific.addChild(tools);search.textFieldStyle(s->s.placeholder(Component.literal(t("搜索模型"))));search.getLayout().flex(1);tools.addChild(search);tools.addChild(NativeUiTheme.button(t("搜索"),()->{offset=0;catalog(false);}));tools.addChild(NativeUiTheme.button(t("刷新列表"),()->catalog(true)));
        var paging=WorkspacePanels.row();paging.getLayout().height(24);specific.addChild(paging);paging.addChild(NativeUiTheme.button(t("上一页"),()->{offset=Math.max(0,offset-48);catalog(false);}));paging.addChild(NativeUiTheme.button(t("下一页"),()->{offset+=48;catalog(false);}));
        var actions=WorkspacePanels.row();actions.getLayout().height(25);body.addChild(actions);actions.addChild(NativeUiTheme.button(t("保存"),this::save));actions.addChild(NativeUiTheme.button(t("重新读取"),this::read));specific.setDisplay(false);read();
        body.addEventListener(com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents.TICK,event->{if(pollAt>0&&System.currentTimeMillis()>=pollAt){pollAt=0;catalog(false);}});
    }
    private void read(){if(saving)return;long ticket=++epoch;WorkspacePanels.request("agent.modelRead",Map.of("agentId",agent)).whenComplete((receipt,error)->{if(window.closed()||ticket!=epoch)return;if(error!=null){WorkspacePanels.failure(status,error);return;}saved=WorkspacePanels.state(receipt);String selection=saved.get("model").getAsString();if(selection.isEmpty())selection=saved.get("defaultModel").getAsString();custom.setText(selection,false);String selectedMode=saved.get("mode").getAsString();mode.setValue(new Choice(selectedMode,t(selectedMode.equals("CUSTOM")?"指定这个 AI 的模型":"使用默认模型")),false);specific.setDisplay(selectedMode.equals("CUSTOM"));status.setText(Component.literal(saved.get("bindingValid").getAsBoolean()?t("默认：")+saved.get("defaultModel").getAsString():t("API 地址已改变，请重新选择模型或恢复默认。")));catalog(false);});}
    private void catalog(boolean refresh){if(saved==null||loading||window.closed())return;loading=true;long ticket=epoch;WorkspacePanels.request("agent.modelModels",Map.of("agentId",agent,"refresh",Boolean.toString(refresh),"offset",Integer.toString(offset),"query",search.getValue())).whenComplete((receipt,error)->{loading=false;if(window.closed()||ticket!=epoch)return;if(error!=null){WorkspacePanels.failure(status,error);return;}var data=WorkspacePanels.state(receipt);String selected=custom.getValue();var choices=new ArrayList<Choice>();if(!selected.isBlank())choices.add(new Choice(selected,selected));for(var value:data.getAsJsonArray("models")){String id=value.getAsString();if(!id.equals(selected))choices.add(new Choice(id,id));}choices.add(new Choice("",t("使用自定义模型")));models.setCandidates(choices);models.setValue(selected.isBlank()?choices.getLast():choices.getFirst(),false);if(data.get("status").getAsString().equals("LOADING")){status.setText(Component.literal(t("获取模型列表…")));pollAt=System.currentTimeMillis()+800;}else if(!data.get("error").getAsString().isBlank())status.setText(Component.literal(data.get("error").getAsString()));});}
    private void save(){if(saving||saved==null)return;String value=custom.getValue().strip();if(mode.getValue().id().equals("CUSTOM")&&(value.isEmpty()||value.length()>256))return;saving=true;WorkspacePanels.request("agent.modelSave",Map.of("agentId",agent,"expectedRevision",saved.get("revision").getAsString(),"mode",mode.getValue().id(),"model",mode.getValue().id().equals("CUSTOM")?value:"","baseUrl",saved.get("baseUrl").getAsString())).whenComplete((receipt,error)->{saving=false;if(window.closed())return;if(error!=null){WorkspacePanels.failure(status,error);return;}saved=WorkspacePanels.state(receipt);status.setText(Component.literal(t("已保存，下次模型请求生效。")));});}
    private static String t(String value){return ClientLanguage.t(value);}
}
