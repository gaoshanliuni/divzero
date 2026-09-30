package dev.mineagent.runtime.neoforge.client.nativeui;

import com.google.gson.*;
import com.lowdragmc.lowdraglib2.gui.ui.elements.*;
import dev.mineagent.runtime.neoforge.client.language.ClientLanguage;
import net.minecraft.network.chat.Component;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Package management includes standalone creations and overrides, backed by their original stores. */
final class NativeContentCatalogPanel {
    private record Choice(String id,String label){@Override public String toString(){return label;}}
    private static final Set<NativeContentCatalogPanel> OPEN=new LinkedHashSet<>();
    private final NativeWorkspaceScreen host;private final WorkspaceWindow window;private final ScrollerView list;private final TextField search=new TextField();private final TextElement notice=WorkspacePanels.text("");
    private final Selector<Choice> types=new Selector<>();private String type="all",signature="";private int offset,next=-1;private boolean busy;private long poll;
    static void open(NativeWorkspaceScreen host){if(host.revealWindow("packages")){for(var panel:OPEN)if(panel.host==host)panel.poll=0;return;}new NativeContentCatalogPanel(host);}
    private NativeContentCatalogPanel(NativeWorkspaceScreen host){
        this.host=host;window=host.window("packages",t("包管理"),550,400);OPEN.add(this);
        var query=WorkspacePanels.row();query.getLayout().height(25);window.body.addChild(query);types.getLayout().width(150);var choices=new ArrayList<Choice>();for(String value:List.of("all","packages","creatures","native_entities","entity_rules","interaction_rules","block_textures"))choices.add(new Choice(value,label(value)));types.setCandidates(choices);types.setValue(choices.getFirst(),false);types.setOnValueChanged(value->{type=value.id;offset=0;signature="";load();});query.addChild(types);search.getLayout().flex(1).minWidth(30);search.textFieldStyle(style->style.placeholder(Component.literal(t("搜索包、物品、生物或修改名称"))));query.addChild(search);query.addChild(button("搜索",()->{offset=0;signature="";load();}));
        var tools=WorkspacePanels.row();tools.getLayout().height(25);window.body.addChild(tools);tools.addChild(button("刷新",()->{signature="";load();}));tools.addChild(button("创建与改版",()->NativeGenerationPanel.open(host)));tools.addChild(button("跨世界资产",()->NativeAssetsPanel.open(host,null)));tools.addChild(button("收件箱",()->NativeDeliveryPanel.open(host)));
        window.body.addChild(notice);list=WorkspacePanels.scroller(window.body);var nav=WorkspacePanels.row();nav.getLayout().height(25);window.body.addChild(nav);nav.addChild(button("上一页",()->{offset=Math.max(0,offset-8);signature="";load();}));nav.addChild(button("下一页",()->{if(next>=0){offset=next;signature="";load();}}));load();
    }
    private boolean live(){return host.activeContext()&&!window.closed();}
    private void load(){
        if(busy||!live())return;busy=true;String requestedType=type,query=search.getValue();int requestedOffset=offset;
        if(type.equals("block_textures")){busy=false;drawTextures(query);poll=System.currentTimeMillis()+2000;return;}
        WorkspacePanels.request("workspace.read",Map.of("module","contents","kind","list","type",type,"query",query,"offset",Integer.toString(offset))).whenComplete((reply,error)->{
            busy=false;poll=System.currentTimeMillis()+2000;if(!live())return;if(!requestedType.equals(type)||requestedOffset!=offset||!query.equals(search.getValue())){poll=0;return;}if(error!=null){WorkspacePanels.failure(notice,error);return;}var data=WorkspacePanels.state(reply);String current=data.toString()+(type.equals("all")?dev.mineagent.runtime.neoforge.client.resources.BlockTextureClient.catalog(query,0).toString():"");if(current.equals(signature))return;signature=current;list.clearAllScrollViewChildren();
            if(type.equals("all")){next=-1;for(var raw:data.getAsJsonArray("sections")){var section=raw.getAsJsonObject();String source=text(section,"type");list.addScrollViewChild(NativeUiTheme.text(label(source)+" · "+text(section,"total"),NativeUiTheme.ACCENT,10));for(var item:section.getAsJsonArray("items"))entry(source,item.getAsJsonObject());if(section.get("total").getAsInt()>2)list.addScrollViewChild(button("查看全部",()->select(source)));}textureSection(query,true);}
            else{next=data.get("nextOffset").getAsInt();if(data.has("more")&&!data.get("more").getAsBoolean())next=-1;for(var item:data.getAsJsonArray("items"))entry(type,item.getAsJsonObject());if(data.getAsJsonArray("items").isEmpty())list.addScrollViewChild(WorkspacePanels.text(t("暂无记录")));}
            notice.setText(Component.literal(t("新创建或修改的内容会自动刷新；操作仍由原有权限与版本检查。")));
        });
    }
    private void select(String source){type=source;offset=0;types.setValue(new Choice(source,label(source)),false);signature="";load();}
    private void entry(String source,JsonObject item){
        var card=WorkspacePanels.card(list,displayName(source,item));String id=text(item,source.equals("packages")?"packageId":"id");card.setId("content-"+source+"-"+id);card.addChild(WorkspacePanels.text(label(source)+" · "+t("版本")+" "+text(item,"revision")));
        if(source.equals("packages")){
            if(item.has("definitions"))for(var raw:item.getAsJsonArray("definitions")){var definition=raw.getAsJsonObject();card.addChild(WorkspacePanels.text(text(definition,"name")+" · "+NativeUiTheme.option(text(definition,"kind"))));}
            if(item.has("revision"))card.addChild(button("查看详情",()->WorkspacePanels.packageDetail(host,item)));else card.addChild(WorkspacePanels.text(text(item,"reason")));return;
        }
        if(item.has("dimension")){String dimension=text(item,"dimension");card.addChild(WorkspacePanels.text(t("维度")+" · "+t(switch(dimension){case "minecraft:overworld"->"主世界";case "minecraft:the_nether"->"下界";case "minecraft:the_end"->"末地";default->dimension;})));}if(item.has("target")){String target=text(item,"target");var key=net.minecraft.resources.Identifier.tryParse(target);if(key!=null&&net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.containsKey(key))target=net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getValue(key).getDescription().getString();card.addChild(WorkspacePanels.text(target));}var actions=WorkspacePanels.row();actions.getLayout().height(25);card.addChild(actions);actions.addChild(button("查看详情",()->details(source,item)));
        if(source.equals("creatures"))actions.addChild(button("预览",()->change(source,item,"preview",Map.of(),notice,()->{})));
        if(Set.of("entity_rules","interaction_rules").contains(source))actions.addChild(button("删除规则",()->Dialog.showCheckBox(t("删除规则"),t("删除后停止此规则的后续影响，已发生的世界变化不会被回放。"),yes->{if(yes)change(source,item,"delete",Map.of(),notice,()->{signature="";poll=0;});}).show(window.body)));
    }
    private Map<String,String> target(String source,JsonObject item,String kind){return Map.of("module","contents","kind",kind,"type",source,"id",text(item,"id"),"revision",text(item,"revision"));}
    private void details(String source,JsonObject item){
        var panel=host.window("content-detail-"+source+text(item,"id"),displayName(source,item),500,365);panel.body.clearAllChildren();var notice=WorkspacePanels.text(t("正在读取…"));panel.body.addChild(notice);var list=WorkspacePanels.scroller(panel.body);list.getLayout().minHeight(0);
        source(source,item,0,new StringBuilder()).whenComplete((value,error)->{if(panel.closed()||!live())return;if(error!=null){WorkspacePanels.failure(notice,error);return;}var definition=JsonParser.parseString(value).getAsJsonObject();var summary=new JsonObject();summary.addProperty("name",displayName(source,item));summary.add("revision",item.get("revision"));
            for(var entry:definition.entrySet()){if(entry.getKey().equals("name"))continue;if(Set.of("model","mesh","geometry","parts","animations","root","replacements").contains(entry.getKey())){summary.addProperty(t(entry.getKey().equals("animations")?"动画数据":"模型与外观数据"),t("已保存，可通过预览或高级编辑查看。"));}else summary.add(entry.getKey(),entry.getValue());}
            NativeEvidenceView.add(host,list,summary);notice.setText(Component.literal(label(source)));
        });
        var actions=WorkspacePanels.row();actions.getLayout().heightAuto().minHeight(25).flexWrap(dev.vfyjxf.taffy.style.FlexWrap.WRAP);panel.body.addChild(actions);
        if(!source.equals("entity_rules")||!text(item,"ruleKind").equals("visual"))actions.addChild(button("重命名",()->Dialog.stringEditorDialog(t("重命名"),text(item,"name"),value->!value.isBlank()&&value.length()<=(source.equals("creatures")?48:80),name->change(source,item,"rename",Map.of("name",name),notice,()->{panel.close();signature="";poll=0;})).show(panel.body)));
        if(source.equals("creatures"))actions.addChild(button("预览",()->change(source,item,"preview",Map.of(),notice,()->{})));
        actions.addChild(button("高级源码编辑",()->editor(source,item)));
    }
    private void editor(String source,JsonObject item){
        var panel=host.window("content-edit-"+source+text(item,"id"),text(item,"name"),520,390);panel.body.clearAllChildren();var status=WorkspacePanels.text(t("正在读取…"));panel.body.addChild(status);var input=new TextArea();input.setId("content-source-editor");input.getLayout().flex(1).minHeight(0).widthPercent(100);panel.body.addChild(input);final boolean[] ready={false};source(source,item,0,new StringBuilder()).whenComplete((value,error)->{if(panel.closed()||!live())return;if(error!=null){WorkspacePanels.failure(status,error);return;}input.setValue(value.split("\n",-1),false);ready[0]=true;status.setText(Component.literal(t("修改内容保留原 ID；保存前检查实际版本。")));});
        panel.body.addChild(button("保存修改",()->{if(!ready[0])return;ready[0]=false;change(source,item,"save",Map.of("source",String.join("\n",input.getValue())),status,()->{panel.close();signature="";poll=0;}).whenComplete((r,e)->{if(e!=null)ready[0]=true;});}));
    }
    private CompletableFuture<String> source(String type,JsonObject item,int offset,StringBuilder data){var args=new LinkedHashMap<>(target(type,item,"source"));args.put("offset",Integer.toString(offset));return WorkspacePanels.request("workspace.read",args).thenCompose(reply->{var value=WorkspacePanels.state(reply);data.append(text(value,"text"));if(data.length()>4*1024*1024)throw new IllegalStateException("CONTENT_SOURCE_LIMIT");return value.get("more").getAsBoolean()?source(type,item,value.get("nextOffset").getAsInt(),data):CompletableFuture.completedFuture(data.toString());});}
    private CompletableFuture<?> change(String type,JsonObject item,String action,Map<String,String> edited,TextElement status,Runnable after){var args=new LinkedHashMap<>(target(type,item,action));args.putAll(edited);return WorkspacePanels.request("workspace.write",args).thenAccept(reply->{var result=WorkspacePanels.state(reply);String state=text(result,"status");if(Set.of("UNKNOWN","REJECTED","FAILED").contains(state))throw new IllegalStateException(result.has("error")?text(result,"error"):state);after.run();}).whenComplete((r,e)->{if(e!=null)WorkspacePanels.failure(status,e);});}
    private void drawTextures(String query){list.clearAllScrollViewChildren();textureSection(query,false);}
    private void textureSection(String query,boolean overview){
        var data=dev.mineagent.runtime.neoforge.client.resources.BlockTextureClient.catalog(query,overview?0:offset);var rows=data.getAsJsonArray("items");if(overview)list.addScrollViewChild(NativeUiTheme.text(label("block_textures")+" · "+text(data,"total"),NativeUiTheme.ACCENT,10));else next=data.get("nextOffset").getAsInt();
        int count=0;for(var raw:rows){if(overview&&count++>=2)break;var row=raw.getAsJsonObject();var card=WorkspacePanels.card(list,text(row,"id"));card.addChild(WorkspacePanels.text(text(row,"sourceUrl")));card.addChild(button("恢复原贴图",()->Dialog.showCheckBox(t("恢复原贴图"),text(row,"id"),yes->{if(yes)dev.mineagent.runtime.neoforge.client.resources.BlockTextureClient.clearFromCatalog(text(row,"id"),data.get("revision").getAsLong()).whenComplete((r,e)->{if(e!=null)WorkspacePanels.failure(notice,e);else{signature="";poll=0;load();}});}).show(window.body)));}
        if(overview&&data.get("total").getAsInt()>2)list.addScrollViewChild(button("查看全部",()->select("block_textures")));
    }
    static void tick(){OPEN.removeIf(p->!p.live());if(!NativeWorkspaceScreen.visible())return;long now=System.currentTimeMillis();for(var p:List.copyOf(OPEN))if(p.window.visible()&&now>=p.poll)p.load();}
    private static String displayName(String type,JsonObject item){String name=text(item,"name"),id=text(item,"id");return name.isBlank()||name.equals(id)?label(type)+(id.isBlank()?"":" · "+id.substring(0,Math.min(8,id.length()))):name;}
    private static String text(JsonObject item,String key){return item.has(key)&&!item.get(key).isJsonNull()?item.get(key).getAsString():"";}
    private static String label(String type){return t(switch(type){case "all"->"所有内容";case "packages"->"内容包与物品";case "creatures"->"自定义生物";case "native_entities"->"原生生物定义";case "entity_rules"->"实体行为与外观修改";case "interaction_rules"->"方块与交互修改";case "block_textures"->"方块贴图";default->type;});}
    private static String t(String value){return ClientLanguage.t(value);}
    private static Button button(String value,Runnable action){return NativeUiTheme.button(t(value),action);}
}
