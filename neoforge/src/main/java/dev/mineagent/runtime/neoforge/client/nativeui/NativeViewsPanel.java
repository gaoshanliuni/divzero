package dev.mineagent.runtime.neoforge.client.nativeui;

import com.google.gson.*;
import com.lowdragmc.lowdraglib2.gui.ui.elements.*;
import dev.mineagent.runtime.neoforge.client.language.ClientLanguage;
import dev.mineagent.runtime.neoforge.client.webui.*;
import net.minecraft.network.chat.Component;
import java.util.*;

/** Player-facing controls for independent native windows/HUDs and explicit delegation. */
final class NativeViewsPanel {
    private record Choice(String id,String name){@Override public String toString(){return name;}}
    static void open(NativeWorkspaceScreen host){
        var window=host.window("native-views",t("界面与 HUD"),520,355);window.body.clearAllChildren();var notice=WorkspacePanels.text("");window.body.addChild(notice);var list=WorkspacePanels.scroller(window.body);
        if(NativePackageViews.ids().isEmpty())list.addScrollViewChild(WorkspacePanels.text(t("暂无打开的内容界面")));
        for(String id:NativePackageViews.ids()){
            var asset=NativePackageViews.asset(id);boolean hud=NativePackageViews.passive(id);var card=WorkspacePanels.card(list,asset.runtimePackage().name()+" · "+(hud?"HUD":t("窗口")));card.addChild(WorkspacePanels.text(asset.entry()));var row=WorkspacePanels.row();row.getLayout().height(25);card.addChild(row);
            row.addChild(button("显示",()->NativePackageViews.show(id)));row.addChild(button("隐藏",()->NativePackageViews.hide(id)));row.addChild(button("关闭",()->{NativePackageViews.close(id);HudPersistenceClient.closed(id);open(host);}));
            if(hud){row.addChild(button("进入交互",()->{try{NativePackageViews.interact(id);}catch(Exception e){WorkspacePanels.failure(notice,e);}}));var restore=new Toggle().setText(t("下次进入恢复"));restore.setOn(HudPersistenceClient.entries().stream().anyMatch(e->e.packageId().equals(asset.runtimePackage().packageId())&&e.entryPath().equals(asset.entry())),false);restore.registerValueListener(enabled->{try{var b=NativePackageViews.layout(id).getAsJsonObject("bounds");HudPersistenceClient.remember(id,enabled,new dev.mineagent.runtime.client.webui.HudRestoreEntry.Layout(b.get("x").getAsDouble(),b.get("y").getAsDouble(),b.get("width").getAsDouble(),b.get("height").getAsDouble(),false)).whenComplete((r,e)->{if(e!=null)WorkspacePanels.failure(notice,e);});}catch(Exception e){WorkspacePanels.failure(notice,e);}});card.addChild(restore);}
            else{
                var session=PackageContentClient.rawSession(id);boolean agent=session!=null&&session.binding().actorKind()==dev.mineagent.runtime.api.ui.UiProtocol.ActorKind.AGENT;
                if(!agent)row.addChild(button("委派 AI",()->{if(session==null)PageControlClient.prepare(id).whenComplete((r,e)->{if(e!=null)WorkspacePanels.failure(notice,e);else delegate(host,id);});else delegate(host,id);}));
                if(agent){row.addChild(button("停止 AI",()->UiAgentClient.stop(id,true,"PLAYER_STOP")));if(!dev.mineagent.runtime.api.ui.ContainerProtocol.bound(session.binding())&&!dev.mineagent.runtime.api.ui.WorldUiProtocol.bound(session.binding())&&!dev.mineagent.runtime.api.ui.DeliveryProtocol.bound(session.binding()))row.addChild(button("接管并恢复草稿",()->ContentTakeoverClient.begin(id).whenComplete((r,e)->{if(e!=null)WorkspacePanels.failure(notice,e);else open(host);})));}
                if(session!=null&&!agent&&session.binding().capabilities().equals(Set.of("scoreview.read")))row.addChild(button("启用已恢复界面",()->ContentTakeoverClient.activate(id,UUID.randomUUID()).whenComplete((r,e)->{if(e!=null)WorkspacePanels.failure(notice,e);else open(host);})));
                if(Set.of("AVAILABLE","FAILED").contains(DeliveryDraftClient.status(id))){var drafts=WorkspacePanels.row();drafts.getLayout().height(25);card.addChild(drafts);drafts.addChild(button("恢复投递草稿",()->DeliveryDraftClient.action(id,"restore").whenComplete((r,e)->{if(e!=null)WorkspacePanels.failure(notice,e);})));drafts.addChild(button("使用本次输入",()->DeliveryDraftClient.action(id,"continue").whenComplete((r,e)->{if(e!=null)WorkspacePanels.failure(notice,e);})));}
            }
        }
        window.body.addChild(button("刷新",()->open(host)));
    }
    static void bind(NativeWorkspaceScreen host,JsonObject head){
        String pkg=head.get("packageId").getAsString(),revision=head.get("revision").getAsString();var window=host.window("bind-score-"+pkg,t("计分数据与HUD"),490,330);window.body.clearAllChildren();var notice=WorkspacePanels.text(t("读取已有计分数据…"));window.body.addChild(notice);var sources=new Selector<Choice>();sources.getLayout().height(25).widthPercent(100);window.body.addChild(sources);var title=new TextField().setText(head.get("name").getAsString(),false);title.getLayout().height(25).widthPercent(100);window.body.addChild(title);var hud=new Toggle().setText(t("常驻HUD"));hud.setOn(head.get("hudAvailable").getAsBoolean(),false);hud.setActive(head.get("hudAvailable").getAsBoolean());window.body.addChild(hud);final UUID[] pending={null};
        WorkspacePanels.request("shell.read",Map.of()).whenComplete((r,e)->{if(window.closed()||!host.activeContext())return;if(e!=null){WorkspacePanels.failure(notice,e);return;}var choices=new ArrayList<Choice>();for(var raw:JsonParser.parseString(r.values().get("scoreSources")).getAsJsonArray()){var value=raw.getAsJsonObject();choices.add(new Choice(value.get("id").getAsString(),value.get("reference").getAsString()));}sources.setCandidates(choices);if(!choices.isEmpty())sources.setValue(choices.getFirst(),false);notice.setText(Component.literal(choices.isEmpty()?t("没有可绑定的计分源或缺少权限"):t("选择真实计分源，界面只展示服务器读数。")));});
        window.body.addChild(button("绑定并打开",()->{if(pending[0]!=null||sources.getValue()==null)return;pending[0]=UUID.randomUUID();WorkspacePanels.request("scoreview.bind",Map.of("packageId",pkg,"packageRevision",revision,"sourceId",sources.getValue().id(),"title",title.getValue()),pending[0]).thenCompose(r->{UUID target=UUID.fromString(r.values().get("viewId"));return hud.isOn()?PackagePreviewClient.openHud(UUID.fromString(pkg),Long.parseLong(revision),target):PackagePreviewClient.open(UUID.fromString(pkg),Long.parseLong(revision),target);}).whenComplete((r,e)->{if(e!=null)WorkspacePanels.failure(notice,e);else{window.close();open(host);}});}));
    }
    private static void delegate(NativeWorkspaceScreen host,String view){
        var window=host.window("delegate-"+view,t("委派 AI 操作此界面"),470,310);window.body.clearAllChildren();var notice=WorkspacePanels.text(t("委派只允许此界面与已有权限，随时可以停止。"));window.body.addChild(notice);var agents=new Selector<Choice>();agents.getLayout().height(25).widthPercent(100);window.body.addChild(agents);var prompt=new TextArea();prompt.getLayout().flex(1).widthPercent(100);window.body.addChild(prompt);var expected=new TextField();expected.textFieldStyle(style->style.placeholder(Component.literal(t("预期结果或目标计分标题"))));expected.getLayout().height(25).widthPercent(100);window.body.addChild(expected);final UUID[] pending={null};
        WorkspacePanels.request("shell.read",Map.of()).whenComplete((r,e)->{if(window.closed())return;if(e!=null){WorkspacePanels.failure(notice,e);return;}var choices=new ArrayList<Choice>();for(var raw:JsonParser.parseString(r.values().get("agents")).getAsJsonArray()){var a=raw.getAsJsonObject();choices.add(new Choice(a.get("id").getAsString(),a.get("name").getAsString()));}agents.setCandidates(choices);if(!choices.isEmpty())agents.setValue(choices.getFirst(),false);});
        window.body.addChild(button("确认委派",()->{if(pending[0]!=null||agents.getValue()==null)return;String goal=String.join("\n",prompt.getValue());if(goal.isBlank()||goal.length()>8192||expected.getValue().isBlank())return;pending[0]=UUID.randomUUID();UiAgentClient.delegate(view,UUID.fromString(agents.getValue().id()),goal,expected.getValue(),pending[0]).whenComplete((r,e)->{if(e!=null)WorkspacePanels.failure(notice,e);else window.close();});}));
    }
    private static Button button(String value,Runnable action){return NativeUiTheme.button(t(value),action);}
    private static String t(String value){return ClientLanguage.t(value);}
    private NativeViewsPanel(){}
}
