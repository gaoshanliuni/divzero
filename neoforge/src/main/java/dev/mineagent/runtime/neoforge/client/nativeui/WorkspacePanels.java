package dev.mineagent.runtime.neoforge.client.nativeui;

import com.google.gson.*;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.*;
import dev.mineagent.runtime.api.ui.UiProtocol.*;
import dev.mineagent.runtime.neoforge.client.language.ClientLanguage;
import dev.mineagent.runtime.neoforge.client.screen.NativeSecretScreen;
import dev.mineagent.runtime.neoforge.client.screen.ProviderModelScreen;
import dev.vfyjxf.taffy.style.FlexDirection;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Native desktop pages use the existing authority APIs, without the old control-center navigation. */
final class WorkspacePanels {
    private static final Gson JSON=new Gson();
    private WorkspacePanels(){}
    private static String t(String text){return ClientLanguage.t(text);}
    static CompletableFuture<Receipt> request(String action,Map<String,String> args){return request(action,args,UUID.randomUUID());}
    static CompletableFuture<Receipt> request(String action,Map<String,String> args,UUID operation){return NativeWorkspaceConnection.command(action,args,operation).thenApply(receipt->{
        if(!Set.of(Code.OBSERVED,Code.APPLIED,Code.ACCEPTED,Code.OK).contains(receipt.code())||!receipt.values().getOrDefault("errorCode","").isBlank())throw new IllegalStateException(receipt.values().getOrDefault("errorCode",receipt.code().name()));return receipt;
    });}
    static JsonObject state(Receipt receipt){return JsonParser.parseString(receipt.values().getOrDefault("state","{}")).getAsJsonObject();}
    static UIElement row(){var r=new UIElement();r.getLayout().flexDirection(FlexDirection.ROW).widthPercent(100).gapAll(5);return r;}
    static TextElement text(String value){return NativeUiTheme.text(value,NativeUiTheme.TEXT,9);}
    static ScrollerView scroller(UIElement parent){var list=new ScrollerView();list.getLayout().widthPercent(100).flex(1);parent.addChild(list);return list;}
    static UIElement card(ScrollerView parent,String title){var card=NativeUiTheme.card(new UIElement());card.getLayout().widthPercent(100).marginBottom(7);card.addChild(NativeUiTheme.text(title,NativeUiTheme.TEXT,11));parent.addScrollViewChild(card);return card;}
    static void failure(TextElement notice,Throwable error){notice.setText(Component.literal(error.getCause()==null?Objects.toString(error.getMessage(),t("操作失败")):Objects.toString(error.getCause().getMessage(),t("操作失败"))));}

    static void settings(NativeWorkspaceScreen host){
        if(host.revealWindow("settings"))return;
        var window=host.window("settings",t("设置"),470,380);var body=window.body;body.clearAllChildren();
        var tabs=row();tabs.getLayout().height(25);body.addChild(tabs);var notice=text(t("读取配置…"));body.addChild(notice);var fields=scroller(body);var footer=row();footer.getLayout().height(25);body.addChild(footer);
        var values=new LinkedHashMap<String,String>();final JsonObject[] snapshot={null};final String[] group={"Provider"};final boolean[] busy={false};
        Runnable[] draw={null},load={null};
        draw[0]=()->{
            fields.clearAllScrollViewChildren();tabs.clearAllChildren();if(snapshot[0]==null)return;
            var groups=new LinkedHashSet<String>();for(var raw:snapshot[0].getAsJsonArray("fields"))groups.add(raw.getAsJsonObject().get("group").getAsString());
            var groupSelect=new Selector<String>();groupSelect.setCandidates(List.copyOf(groups));groupSelect.setValue(group[0],false);groupSelect.setOnValueChanged(value->{group[0]=value;draw[0].run();});groupSelect.getLayout().flex(1);tabs.addChild(groupSelect);
            tabs.addChild(NativeUiTheme.button(t("刷新"),load[0]));
            for(var raw:snapshot[0].getAsJsonArray("fields")){
                var field=raw.getAsJsonObject();if(!field.get("group").getAsString().equals(group[0]))continue;String key=field.get("key").getAsString(),value=values.getOrDefault(key,field.get("value").isJsonNull()?"":field.get("value").getAsString());
                var card=card(fields,t(field.get("label").getAsString()));values.put(key,value);String type=field.get("type").getAsString();
                if(type.equals("boolean")){var toggle=new Toggle().setText(t("启用"));toggle.setOn(Boolean.parseBoolean(value),false);toggle.registerValueListener(v->values.put(key,v.toString()));card.addChild(toggle);}
                else if(type.equals("providerOrder")){var order=new Selector<String>();order.setCandidates(List.of("openai-compatible,ollama","ollama,openai-compatible"));order.setValue(value,false);order.setOnValueChanged(v->values.put(key,v));card.addChild(order);}
                else{var input=new TextField().setText(value,false);input.getLayout().height(23).widthPercent(100);input.registerValueListener(v->values.put(key,v));card.addChild(input);}
                if(!field.get("warning").getAsString().isBlank())card.addChild(text(t(field.get("warning").getAsString())));
            }
        };
        load[0]=()->{if(busy[0])return;busy[0]=true;request("settings.read",Map.of()).whenComplete((receipt,error)->{busy[0]=false;if(window.closed())return;if(error!=null){failure(notice,error);return;}snapshot[0]=state(receipt);values.clear();draw[0].run();notice.setText(Component.literal(t("设置仅在点击保存后生效。")));});};
        footer.addChild(NativeUiTheme.button(t("保存"),()->{if(busy[0]||snapshot[0]==null)return;var changed=new LinkedHashMap<String,String>();for(var raw:snapshot[0].getAsJsonArray("fields")){var f=raw.getAsJsonObject();String key=f.get("key").getAsString();if(values.containsKey(key)&&!values.get(key).equals(f.get("value").isJsonNull()?"":f.get("value").getAsString()))changed.put(key,values.get(key));}if(changed.isEmpty())return;
            Runnable save=()->{busy[0]=true;request("settings.write",Map.of("kind","save","revision",snapshot[0].get("revision").getAsString(),"values",JSON.toJson(changed),"providerChangeConfirmed","true")).whenComplete((receipt,error)->{busy[0]=false;if(window.closed())return;if(error!=null){failure(notice,error);return;}snapshot[0]=state(receipt);notice.setText(Component.literal(t("已保存")));});};
            if(changed.keySet().stream().anyMatch(k->k.endsWith("baseUrl"))&&snapshot[0].get("keyConfigured").getAsBoolean())Dialog.showCheckBox(t("修改 API 地址"),t("已有密钥会用于新地址，请确认该地址可信。"),yes->{if(yes)save.run();}).show(body);else save.run();
        }));
        footer.addChild(NativeUiTheme.button(t("API Key"),()->Minecraft.getInstance().setScreen(new NativeSecretScreen(host))));footer.addChild(NativeUiTheme.button(t("选择模型"),()->Minecraft.getInstance().setScreen(new ProviderModelScreen(host))));footer.addChild(NativeUiTheme.button(t("权限"),()->permissions(host)));footer.addChild(NativeUiTheme.button(t("语言"),()->Minecraft.getInstance().setScreen(new dev.mineagent.runtime.neoforge.client.screen.LanguageScreen(host))));footer.addChild(NativeUiTheme.button(t("关于"),()->Minecraft.getInstance().setScreen(new dev.mineagent.runtime.neoforge.client.screen.AboutScreen(host))));load[0].run();
    }
    static void permissions(NativeWorkspaceScreen host){
        if(host.revealWindow("permissions"))return;
        var window=host.window("permissions",t("权限与信任"),440,350);window.body.clearAllChildren();var notice=text(t("读取配置…"));window.body.addChild(notice);var list=scroller(window.body);
        request("settings.read",Map.of()).whenComplete((receipt,error)->{if(window.closed())return;if(error!=null){failure(notice,error);return;}var data=state(receipt);if(!data.get("canPermissions").getAsBoolean()){notice.setText(Component.literal(t("没有权限")));return;}
            for(var playerRaw:data.getAsJsonArray("players")){var player=playerRaw.getAsJsonObject();String id=player.get("id").getAsString();var card=card(list,player.get("name").getAsString());var selected=new LinkedHashSet<String>();for(var grantRaw:data.getAsJsonArray("grants")){var grant=grantRaw.getAsJsonObject();if(grant.get("playerId").getAsString().equals(id))for(var action:grant.getAsJsonArray("actions"))selected.add(action.getAsString());}
                for(var actionRaw:data.getAsJsonArray("permissionActions")){String action=actionRaw.getAsString();var toggle=new Toggle().setText(t(action));toggle.setOn(selected.contains(action),false);toggle.registerValueListener(on->{if(on)selected.add(action);else selected.remove(action);});card.addChild(toggle);}
                card.addChild(NativeUiTheme.button(t("保存权限"),()->Dialog.showCheckBox(t("保存权限"),player.get("name").getAsString(),yes->{if(yes)request("settings.write",Map.of("kind","permissions","revision",data.get("revision").getAsString(),"playerId",id,"actions",JSON.toJson(selected),"confirmed","true")).whenComplete((r,e)->{if(e!=null)failure(notice,e);else{window.close();permissions(host);}});}).show(window.body)));
            }
            notice.setText(Component.literal(t("权限由服务器检查。")));
        });
    }
    static void agents(NativeWorkspaceScreen host){
        if(host.revealWindow("agents"))return;
        var window=host.window("agents",t("AI 玩家"),455,355);window.body.clearAllChildren();var notice=text(t("读取 AI…"));window.body.addChild(notice);var tools=row();tools.getLayout().height(26);window.body.addChild(tools);var name=new TextField();name.textFieldStyle(s->s.placeholder(Component.literal(t("AI 名称"))));name.getLayout().flex(1);tools.addChild(name);var mode=new Selector<String>();mode.setCandidates(List.of("CREATOR","SURVIVAL"));mode.setValue("CREATOR",false);tools.addChild(mode);var list=scroller(window.body);
        Runnable[] load={null};final JsonObject[] state={null};
        load[0]=()->request("shell.read",Map.of()).whenComplete((receipt,error)->{if(window.closed())return;if(error!=null){failure(notice,error);return;}state[0]=JsonParser.parseString(receipt.values().get("agentManagement")).getAsJsonObject();list.clearAllScrollViewChildren();
            for(var raw:state[0].getAsJsonArray("agents")){var agent=raw.getAsJsonObject();String id=agent.get("id").getAsString(),label=agent.get("name").getAsString();var card=card(list,label);card.addChild(NativeUiTheme.text(t(agent.get("bodyState").getAsString()),NativeUiTheme.MUTED,8));if(!agent.get("health").isJsonNull())card.addChild(text(t("生命值")+"  "+agent.get("health").getAsString()+"   "+t("饱食度")+"  "+agent.get("food").getAsString()));
                var actions=row();actions.getLayout().height(25);card.addChild(actions);actions.addChild(NativeUiTheme.button(t("对话"),()->{host.rememberAgent(id,label);host.conversationWith(id);}));actions.addChild(NativeUiTheme.button(t("人设"),()->persona(host,id,label)));actions.addChild(NativeUiTheme.button(t("模型"),()->AgentModelPanel.open(host,id,label)));
                if(agent.get("canManage").getAsBoolean()){actions.addChild(NativeUiTheme.button(t("重命名"),()->Dialog.stringEditorDialog(t("重命名"),label,v->!v.isBlank()&&v.length()<=32,value->request("agent.manage",Map.of("kind","rename","agentId",id,"expectedRevision",agent.get("revision").getAsString(),"name",value)).whenComplete((r,e)->{if(e!=null)failure(notice,e);else load[0].run();})).show(window.body)));actions.addChild(NativeUiTheme.button(t("删除"),()->Dialog.showCheckBox(t("删除 AI"),label,yes->{if(yes)request("agent.manage",Map.of("kind","delete","agentId",id,"expectedRevision",agent.get("revision").getAsString(),"confirmed","true")).whenComplete((r,e)->{if(e!=null)failure(notice,e);else load[0].run();});}).show(window.body)));}
            }
            var pager=row();pager.getLayout().height(24);int offset=state[0].get("offset").getAsInt(),next=state[0].get("nextOffset").getAsInt();pager.addChild(NativeUiTheme.button(t("上一页"),()->request("agent.manage",Map.of("kind","page","offset",Integer.toString(Math.max(0,offset-16)))).thenRun(load[0])));if(next>=0)pager.addChild(NativeUiTheme.button(t("下一页"),()->request("agent.manage",Map.of("kind","page","offset",Integer.toString(next))).thenRun(load[0])));list.addScrollViewChild(pager);notice.setText(Component.literal(t("右键 AI 可打开专属面板。")));
        });
        tools.addChild(NativeUiTheme.button(t("创建 AI"),()->{if(name.getValue().isBlank())return;request("agent.manage",Map.of("kind","create","name",name.getValue(),"mode",mode.getValue())).whenComplete((r,e)->{if(e!=null)failure(notice,e);else{name.setText("",false);load[0].run();}});}));tools.addChild(NativeUiTheme.button(t("刷新"),load[0]));load[0].run();
    }
    static void persona(NativeWorkspaceScreen host,String agent,String name){
        if(host.revealWindow("persona-"+agent))return;
        var window=host.window("persona-"+agent,name+" · "+t("人设"),420,300);window.body.clearAllChildren();var notice=text(t("读取人设…"));window.body.addChild(notice);var editor=new TextArea();editor.getLayout().flex(1).widthPercent(100);PersonaLibraryBar.add(window.body,agent,editor,notice,()->!window.closed(),value->{});window.body.addChild(editor);final long[] revision={-1};
        request("persona.read",Map.of("agentId",agent)).whenComplete((receipt,error)->{if(window.closed())return;if(error!=null){failure(notice,error);return;}var value=state(receipt);revision[0]=value.get("revision").getAsLong();editor.setValue(value.get("text").getAsString().split("\n",-1),false);notice.setText(Component.literal(name));});
        window.body.addChild(NativeUiTheme.button(t("应用人设"),()->{if(revision[0]<0)return;request("persona.save",Map.of("agentId",agent,"text",String.join("\n",editor.getValue()),"expectedRevision",Long.toString(revision[0]))).whenComplete((receipt,error)->{if(error!=null)failure(notice,error);else{notice.setText(Component.literal(t("已保存")));revision[0]=state(receipt).get("appliedRevision").getAsLong();}});}));
    }
    static void packages(NativeWorkspaceScreen host){
        if(host.revealWindow("packages"))return;
        var window=host.window("packages",t("包管理"),500,370);window.body.clearAllChildren();var tools=row();tools.getLayout().height(26);window.body.addChild(tools);var search=new TextField();search.getLayout().flex(1);tools.addChild(search);var notice=text("");window.body.addChild(notice);var list=scroller(window.body);final int[] offset={0};Runnable[] load={null};
        load[0]=()->request("task.historyRead",Map.of("kind","packages","search",search.getValue(),"offset",Integer.toString(offset[0]))).whenComplete((receipt,error)->{if(window.closed())return;if(error!=null){failure(notice,error);return;}var data=state(receipt);list.clearAllScrollViewChildren();for(var raw:data.getAsJsonArray("items")){var item=raw.getAsJsonObject();var card=card(list,item.has("name")?item.get("name").getAsString():t("不可用的内容包"));if(item.has("version"))card.addChild(text(item.get("version").getAsString()));if(item.has("revision"))card.addChild(NativeUiTheme.button(t("查看详情"),()->packageDetail(host,item)));else card.addChild(text(item.has("reason")?item.get("reason").getAsString():t("不可用")));}int count=data.has("total")?data.get("total").getAsInt():0;notice.setText(Component.literal(t("包管理")+" · "+count));});
        tools.addChild(NativeUiTheme.button(t("搜索"),()->{offset[0]=0;load[0].run();}));var pager=row();pager.getLayout().height(25);pager.addChild(NativeUiTheme.button(t("上一页"),()->{offset[0]=Math.max(0,offset[0]-8);load[0].run();}));pager.addChild(NativeUiTheme.button(t("下一页"),()->{offset[0]+=8;load[0].run();}));window.body.addChild(pager);load[0].run();
    }
    static void packageDetail(NativeWorkspaceScreen host,JsonObject item){NativePackagePanel.open(host,item);}
}
