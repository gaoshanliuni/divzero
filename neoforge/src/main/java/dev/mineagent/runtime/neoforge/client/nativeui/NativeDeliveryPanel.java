package dev.mineagent.runtime.neoforge.client.nativeui;

import com.google.gson.*;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.*;
import dev.mineagent.runtime.api.ui.UiProtocol.*;
import dev.mineagent.runtime.neoforge.client.language.ClientLanguage;
import dev.mineagent.runtime.neoforge.client.webui.*;
import net.minecraft.network.chat.Component;
import java.util.*;
import java.util.concurrent.*;

/** Native inbox/outbox/history. Invites remain inert until the recipient chooses to open them. */
final class NativeDeliveryPanel {
    private static final Gson JSON=new Gson();private static final Set<NativeDeliveryPanel> PANELS=new HashSet<>();
    private final NativeWorkspaceScreen host;private final WorkspaceWindow window;private final ScrollerView list;private final TextElement notice=WorkspacePanels.text("");
    private String mode="INBOX",archive="HOT";private int offset,next;private boolean more,busy;private long epoch,nextPoll;private final Map<String,UUID> pending=new HashMap<>();
    static void open(NativeWorkspaceScreen host){if(!host.revealWindow("deliveries"))new NativeDeliveryPanel(host);}
    private NativeDeliveryPanel(NativeWorkspaceScreen host){this.host=host;window=host.window("deliveries",t("内容与反馈"),570,385);PANELS.add(this);
        var tabs=WorkspacePanels.row();tabs.getLayout().height(25);window.body.addChild(tabs);tabs.addChild(button("收件箱",()->select("INBOX")));tabs.addChild(button("已发送",()->select("OUTBOX")));tabs.addChild(button("反馈记录",()->select("FEEDBACK")));tabs.addChild(button("刷新",this::load));
        var filter=new Selector<String>();NativeUiTheme.options(filter);filter.setCandidates(List.of("HOT","ARCHIVED","ALL"));filter.setValue(archive,false);filter.setOnValueChanged(value->{if(busy){filter.setValue(archive,false);return;}archive=value;offset=0;load();});filter.getLayout().width(105);tabs.addChild(filter);
        window.body.addChild(notice);list=WorkspacePanels.scroller(window.body);var pager=WorkspacePanels.row();pager.getLayout().height(25);window.body.addChild(pager);pager.addChild(button("上一页",()->{offset=Math.max(0,offset-16);load();}));pager.addChild(button("下一页",()->{if(more){offset=next;load();}}));load();
    }
    private void select(String value){if(busy)return;mode=value;offset=0;load();}
    private boolean current(long token){return host.activeContext()&&!window.closed()&&token==epoch;}
    private void load(){
        if(busy)return;busy=true;long token=++epoch;nextPoll=System.currentTimeMillis()+5000;
        String action=mode.equals("OUTBOX")?"delivery.manageRead":mode.equals("FEEDBACK")?"feedback.history":"delivery.list";
        Map<String,String> args=mode.equals("OUTBOX")?Map.of("kind","list","status","ALL","archive",archive,"offset",Integer.toString(offset)):mode.equals("FEEDBACK")?Map.of("state","ALL","offset",Integer.toString(offset)):Map.of("offset",Integer.toString(offset));
        WorkspacePanels.request(action,args).whenComplete((receipt,error)->{busy=false;if(!current(token))return;if(error!=null){WorkspacePanels.failure(notice,error);return;}
            var state=mode.equals("OUTBOX")?WorkspacePanels.state(receipt):JSON.toJsonTree(receipt.values()).getAsJsonObject();var rows=mode.equals("OUTBOX")?state.getAsJsonArray("items"):JsonParser.parseString(receipt.values().get(mode.equals("FEEDBACK")?"feedback":"deliveries")).getAsJsonArray();
            more=state.get("more").getAsBoolean();next=state.get("nextOffset").getAsInt();list.clearAllScrollViewChildren();for(var raw:rows)draw(raw.getAsJsonObject());notice.setText(Component.literal(rows.isEmpty()?t("暂无记录"):t("记录")+" · "+(offset+1)+"–"+(offset+rows.size())));
        });
    }
    private void draw(JsonObject item){
        boolean feedback=mode.equals("FEEDBACK");String id=text(item,feedback?"feedbackId":"deliveryId");var card=WorkspacePanels.card(list,feedback?text(item,"event"):text(item,"title"));
        card.addChild(WorkspacePanels.text(t(text(item,feedback?"state":"status"))));if(!text(item,"error").isBlank())card.addChild(WorkspacePanels.text(text(item,"error")));
        if(!feedback&&item.has("dataPaintMatchesLatest"))card.addChild(WorkspacePanels.text(item.get("dataPaintMatchesLatest").getAsBoolean()?t("最新数据已绘制"):t("尚无最新数据绘制回执")));
        var actions=WorkspacePanels.row();actions.getLayout().height(25);card.addChild(actions);
        actions.addChild(button("详情",()->{String action=feedback?"feedback.read":mode.equals("OUTBOX")?"delivery.manageRead":"";if(action.isEmpty())NativeReadout.show(host,"delivery-detail-"+id,"内容详情",item);else WorkspacePanels.request(action,feedback?Map.of("feedbackId",id):Map.of("kind","detail","deliveryId",id)).whenComplete((r,e)->{if(e!=null)WorkspacePanels.failure(notice,e);else NativeReadout.show(host,"delivery-detail-"+id,"内容详情",r.values().containsKey("state")?WorkspacePanels.state(r):JSON.toJsonTree(r.values()));});}));
        if(mode.equals("INBOX")){
            if(text(item,"status").equals("OFFERED")){actions.addChild(button(item.has("clientCode")&&item.get("clientCode").getAsBoolean()?"下载源码":"打开",()->accept(item)));actions.addChild(button("拒绝",()->reject(id)));}
            actions.addChild(button("反馈记录",()->NativeReadout.open(host,"delivery-feedback-"+id,"反馈记录","feedback.list",index->Map.of("deliveryId",id,"offset",Integer.toString(index)),16)));
        }else if(mode.equals("OUTBOX"))for(var control:Map.of("canClose","CLOSE","canRevoke","REVOKE","canArchive","ARCHIVE").entrySet())if(item.has(control.getKey())&&item.get(control.getKey()).getAsBoolean())actions.addChild(button(switch(control.getValue()){case "CLOSE"->"请求关闭";case "REVOKE"->"撤回";default->"归档";},()->manage(item,control.getValue())));
    }
    private void accept(JsonObject item){
        String id=text(item,"deliveryId");if(pending.containsKey(id))return;pending.put(id,UUID.randomUUID());
        if(item.has("clientCode")&&item.get("clientCode").getAsBoolean()){
            try{var result=ClientScriptPackages.startDeliveryDownload(UUID.fromString(id),UUID.fromString(text(item,"packageId")),item.get("packageRevision").getAsLong(),text(item,"canonicalSha256"),text(item,"mode").equals("CLIENT_JAVA"));notice.setText(Component.literal(t(Objects.toString(result.get("state"),result.get("code").toString()))));NativeLifecyclePanel.open(host,null,NativeLifecyclePanel.Kind.CLIENT);}catch(Exception error){WorkspacePanels.failure(notice,error);}return;
        }
        ContentDeliveryClient.open(UUID.fromString(id),UUID.fromString(text(item,"packageId")),item.get("packageRevision").getAsLong()).whenComplete((receipt,error)->{if(error!=null)WorkspacePanels.failure(notice,error);else{pending.remove(id);load();}});
    }
    private void reject(String id){if(pending.containsKey(id))return;UUID op=UUID.randomUUID();pending.put(id,op);WorkspacePanels.request("delivery.reject",Map.of("deliveryId",id,"operationId",op.toString()),op).whenComplete((r,e)->{if(e!=null)WorkspacePanels.failure(notice,e);else{pending.remove(id);load();}});}
    private void manage(JsonObject item,String action){String id=text(item,"deliveryId");if(pending.containsKey(id))return;Dialog.showCheckBox(t("确认操作"),t(action)+" · "+text(item,"title"),yes->{if(!yes||pending.containsKey(id))return;UUID operation=UUID.randomUUID();pending.put(id,operation);WorkspacePanels.request("delivery.manageWrite",Map.of("kind","write","deliveryId",id,"expectedRevision",text(item,"revision"),"action",action,"confirmed","true"),operation).whenComplete((r,e)->{if(e!=null)WorkspacePanels.failure(notice,e);else{pending.remove(id);load();}});}).show(window.body);}
    static void tick(){for(var panel:List.copyOf(PANELS)){if(panel.window.closed()||!panel.host.activeContext()){PANELS.remove(panel);continue;}if(System.currentTimeMillis()>=panel.nextPoll)panel.load();}}
    static void changed(){for(var panel:PANELS)panel.nextPoll=0;}
    private static Button button(String label,Runnable action){return NativeUiTheme.button(t(label),action);}
    private static String text(JsonObject value,String key){return value.has(key)&&!value.get(key).isJsonNull()?value.get(key).getAsString():"";}
    private static String t(String value){return ClientLanguage.t(value);}
}
