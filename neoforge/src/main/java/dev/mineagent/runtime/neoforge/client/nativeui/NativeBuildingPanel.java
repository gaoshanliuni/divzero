package dev.mineagent.runtime.neoforge.client.nativeui;

import com.google.gson.*;
import com.lowdragmc.lowdraglib2.gui.ui.elements.*;
import dev.mineagent.runtime.neoforge.client.language.ClientLanguage;
import net.minecraft.network.chat.Component;
import java.util.*;

/** Addressable construction plans and step controls in the same MC-styled workspace. */
final class NativeBuildingPanel {
    private static final Set<NativeBuildingPanel> OPEN=new HashSet<>();
    private final NativeWorkspaceScreen host;private final WorkspaceWindow window;private final String agent;
    private final ScrollerView content;private final TextElement notice=WorkspacePanels.text("");
    private String id="";private int offset;private long epoch,nextPoll;private boolean busy;private JsonObject state;
    static void open(NativeWorkspaceScreen host,String agent){if(agent.isBlank()){NativeWorkspaceScreen.notice(t("请先选择 AI"));return;}if(host.revealWindow("buildings-"+agent))return;new NativeBuildingPanel(host,agent);}
    private NativeBuildingPanel(NativeWorkspaceScreen host,String agent){
        this.host=host;this.agent=agent;window=host.window("buildings-"+agent,t("建筑计划"),530,390);OPEN.add(this);
        var tools=WorkspacePanels.row();tools.getLayout().height(25);window.body.addChild(tools);
        tools.addChild(NativeUiTheme.button(t("建筑列表"),()->{id="";offset=0;read();}));tools.addChild(NativeUiTheme.button(t("刷新"),this::read));tools.addChild(NativeUiTheme.button(t("对话"),()->host.conversationWith(agent)));window.body.addChild(notice);content=WorkspacePanels.scroller(window.body);read();
    }
    private boolean live(){return host.activeContext()&&!window.closed();}
    private Map<String,String> args(String kind){var values=new LinkedHashMap<String,String>();values.put("kind",kind);values.put("agentId",agent);values.put("offset",Integer.toString(offset));if(!id.isBlank())values.put("id",id);return values;}
    private void read(){if(!live()||busy)return;busy=true;long ticket=++epoch;WorkspacePanels.request("building.read",args("inspect")).whenComplete((receipt,error)->{
        busy=false;if(!live()||ticket!=epoch)return;if(error!=null){WorkspacePanels.failure(notice,error);return;}state=WorkspacePanels.state(receipt);draw();nextPoll=System.currentTimeMillis()+1000;
    });}
    private void draw(){
        content.clearAllScrollViewChildren();notice.setText(Component.literal(""));
        if(id.isBlank()){
            if(state.getAsJsonArray("buildings").isEmpty())content.addScrollViewChild(WorkspacePanels.text(t("在对话中描述建筑需求，AI 会创建可继续修改的构件计划。")));
            for(var row:state.getAsJsonArray("buildings")){var item=row.getAsJsonObject();var card=WorkspacePanels.card(content,item.get("name").getAsString());card.addChild(WorkspacePanels.text(t(item.get("status").getAsString())+" · r"+item.get("revision").getAsString()));card.addChild(NativeUiTheme.button(t("查看构件"),()->{id=item.get("id").getAsString();offset=0;read();}));}
            var pages=WorkspacePanels.row();pages.getLayout().height(25);var previous=NativeUiTheme.button(t("上一页"),()->{offset=Math.max(0,offset-16);read();});previous.setActive(offset>0);pages.addChild(previous);var next=NativeUiTheme.button(t("下一页"),()->{offset=state.get("nextOffset").getAsInt();read();});next.setActive(state.has("more")&&state.get("more").getAsBoolean());pages.addChild(next);content.addScrollViewChild(pages);return;
        }
        if(!state.has("design")){content.addScrollViewChild(WorkspacePanels.text(t("没有找到建筑计划")));return;}
        String status=state.get("status").getAsString();var header=WorkspacePanels.card(content,state.getAsJsonObject("design").get("name").getAsString());header.addChild(WorkspacePanels.text(t(status)+" · r"+state.get("revision").getAsString()));
        var actions=WorkspacePanels.row();actions.getLayout().height(25);header.addChild(actions);
        action(actions,"开始施工","apply",status.equals("PLANNED"));action(actions,"暂停","pause",Set.of("PREPARING","APPLYING").contains(status));action(actions,"继续","resume",status.equals("PAUSED"));action(actions,"核对中断","recover",Set.of("UNKNOWN","PARTIAL").contains(status));
        var history=WorkspacePanels.row();history.getLayout().height(25);header.addChild(history);action(history,"撤销上步","undo",Set.of("VERIFIED","UNVERIFIED","PARTIAL","CONFLICT").contains(status));action(history,"重做","redo",Set.of("UNDONE","CONFLICT").contains(status));
        var verify=NativeUiTheme.button(t("实地验证"),this::verify);verify.setActive(Set.of("VERIFIED","UNVERIFIED").contains(status));history.addChild(verify);
        var edit=NativeUiTheme.button(t("编辑计划"),this::edit);edit.setActive(Set.of("PLANNED","VERIFIED","UNVERIFIED","UNDONE","CONFLICT","REJECTED").contains(status));history.addChild(edit);
        for(var item:state.getAsJsonArray("steps")){var step=item.getAsJsonObject();var card=WorkspacePanels.card(content,componentName(step.get("component").getAsString()));card.addChild(WorkspacePanels.text(t("目标方块")+" "+step.get("cells").getAsString()+" · "+t("变化方块")+" "+step.get("changes").getAsString()+" · "+t("已写入")+" "+step.get("written").getAsString()));}
        if(state.has("report")&&!state.get("report").getAsString().isBlank()){
            var report=JsonParser.parseString(state.get("report").getAsString()).getAsJsonObject();for(var item:report.getAsJsonObject("evidence").getAsJsonArray("checks")){var check=item.getAsJsonObject();var card=WorkspacePanels.card(content,check.get("id").getAsString());card.addChild(WorkspacePanels.text(check.get("passed").getAsBoolean()?t("验证通过"):t("验证未通过")));if(!check.get("detail").getAsString().isBlank())card.addChild(WorkspacePanels.text(check.get("detail").getAsString()));}
            if(!report.getAsJsonArray("mismatches").isEmpty())content.addScrollViewChild(WorkspacePanels.text(t("方块差异")+"\n"+new GsonBuilder().setPrettyPrinting().create().toJson(report.get("mismatches"))));
        }
        for(var item:state.getAsJsonArray("history")){var entry=item.getAsJsonObject();var card=WorkspacePanels.card(content,t(entry.get("mode").getAsString())+" · r"+entry.get("revision").getAsString());card.addChild(WorkspacePanels.text(t(entry.get("status").getAsString())));if(!entry.get("detail").getAsString().isBlank()&&!entry.get("detail").getAsString().startsWith("{"))card.addChild(WorkspacePanels.text(entry.get("detail").getAsString()));}
    }
    private String componentName(String id){for(var raw:state.getAsJsonObject("design").getAsJsonArray("components")){var component=raw.getAsJsonObject();if(component.get("id").getAsString().equals(id)&&component.has("name"))return component.get("name").getAsString();}return id;}
    private void action(com.lowdragmc.lowdraglib2.gui.ui.UIElement row,String label,String action,boolean allowed){var button=NativeUiTheme.button(t(label),()->write(action,null));button.setActive(allowed);row.addChild(button);}
    private void write(String kind,String source){write(kind,source,state.get("revision").getAsString());}
    private void write(String kind,String source,String revision){if(busy||!live())return;busy=true;var args=args(kind);args.remove("offset");args.put("revision",revision);if(source!=null){args.remove("id");args.put("source",source);}WorkspacePanels.request("building.write",args).whenComplete((receipt,error)->{busy=false;if(!live())return;if(error!=null){WorkspacePanels.failure(notice,error);return;}notice.setText(Component.literal(t("已受理，正在读取实际进度。")));read();});}
    private void verify(){if(busy)return;busy=true;var args=args("verify");args.remove("offset");args.put("revision",state.get("revision").getAsString());WorkspacePanels.request("building.read",args).whenComplete((receipt,error)->{busy=false;if(!live())return;if(error!=null)WorkspacePanels.failure(notice,error);else read();});}
    private void edit(){String base=state.get("revision").getAsString();var editor=host.window("building-source-"+agent+"-"+id,t("编辑建筑计划"),550,370);editor.body.addChild(WorkspacePanels.text(t("保留构件 ID。保存只更新计划，施工前仍会检查实际差异。")));var input=new TextArea();input.getLayout().widthPercent(100).flex(1);String[] source={new GsonBuilder().setPrettyPrinting().create().toJson(state.get("design"))};input.setValue(source[0].split("\n"),false);input.registerValueListener(lines->source[0]=String.join("\n",lines));editor.body.addChild(input);editor.body.addChild(NativeUiTheme.button(t("保存计划"),()->write("plan",source[0],base)));}
    static void changed(JsonObject event){for(var panel:List.copyOf(OPEN))if(panel.live()){if(event.has("errorCode"))panel.notice.setText(Component.literal(event.get("errorCode").getAsString()));else panel.read();}}
    static void tick(){OPEN.removeIf(p->!p.live());if(!NativeWorkspaceScreen.visible())return;for(var panel:OPEN)if(!panel.id.isBlank()&&panel.state!=null&&Set.of("PREPARING","APPLYING","PAUSED").contains(panel.state.get("status").getAsString())&&System.currentTimeMillis()>=panel.nextPoll)panel.read();}
    private static String t(String value){return ClientLanguage.t(value);}
}
