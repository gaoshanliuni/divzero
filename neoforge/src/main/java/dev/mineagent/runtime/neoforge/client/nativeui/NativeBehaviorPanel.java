package dev.mineagent.runtime.neoforge.client.nativeui;

import com.google.gson.*;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.*;
import dev.mineagent.runtime.neoforge.client.language.ClientLanguage;
import net.minecraft.network.chat.Component;
import java.util.*;
import java.util.function.BooleanSupplier;

/** One editor shared by the AI's own panel and the integrated F2 workspace. */
public final class NativeBehaviorPanel {
    private record Choice(String id,String label){@Override public String toString(){return t(label);}}
    private static final List<NativeBehaviorPanel> OPEN=new ArrayList<>();
    private final String agent;private final BooleanSupplier current;private final TextElement status,progress;
    private final Selector<Choice> mode,strategy,engagement,target,actor,region,routeMode;
    private final Selector<Integer> radius;private final JsonArray route=new JsonArray();
    private JsonObject state,active;private boolean loading,saving,initialized;private long next;
    private static String t(String s){return ClientLanguage.t(s);}
    public static void open(NativeWorkspaceScreen host,String id,String name){if(host.revealWindow("behavior-"+id))return;var window=host.window("behavior-"+id,name+" · "+t("行为模式"),490,420);attach(window.body,id,()->!window.closed());}
    public static void attach(UIElement parent,String agent,BooleanSupplier current){OPEN.add(new NativeBehaviorPanel(parent,agent,current));}
    public static void tick(){for(var panel:List.copyOf(OPEN)){if(!panel.current.getAsBoolean()){OPEN.remove(panel);continue;}panel.read();}}
    private NativeBehaviorPanel(UIElement parent,String agent,BooleanSupplier current){
        this.agent=agent;this.current=current;var scroll=WorkspacePanels.scroller(parent);var body=new UIElement();body.getLayout().widthPercent(100).gapAll(5);scroll.addScrollViewChild(body);
        status=NativeUiTheme.text(t("读取行为状态…"),NativeUiTheme.ACCENT,10);body.addChild(status);progress=NativeUiTheme.text("",NativeUiTheme.MUTED,9);body.addChild(progress);
        actor=selector(body,"执行身体",new String[][]{{"ai","AI 自己"},{"player","接管我的身体"}});actor.setOnValueChanged(value->{active=null;initialized=false;next=0;});
        mode=selector(body,"主工作",new String[][]{{"IDLE","待命"},{"WANDER","自由活动"},{"FOLLOW","跟随"},{"PATROL","巡逻"},{"GUARD","警戒"},{"FARM","农务"},{"FISH","钓鱼"},{"COMBAT","战斗"}});
        strategy=selector(body,"战斗方式",new String[][]{{"AUTO","自动选择"},{"HIT_AND_RUN","近战跑打"},{"RANGED_KITE","远程控距"},{"HOLD_POSITION","守住阵地"},{"DISENGAGE","脱离交战"}});
        engagement=selector(body,"交战规则",new String[][]{{"SELF_DEFENSE","只自卫"},{"PROTECT","保护对象"},{"CLEAR_AREA","清理防区"},{"SPECIFIED","只攻击指定对象"},{"NONE","不攻击"}});
        target=selector(body,"目标 / 保护对象",new String[][]{{"$owner","主人"}});
        region=selector(body,"区域来源",new String[][]{{"LOOK","看向的位置"},{"CURRENT","当前位置"},{"PREVIOUS","沿用当前工作区域"}});
        var rangeRow=WorkspacePanels.row();rangeRow.getLayout().height(25);body.addChild(rangeRow);rangeRow.addChild(WorkspacePanels.text(t("区域半径")));radius=new Selector<>();radius.setCandidates(List.of(4,8,16));radius.setValue(4,false);rangeRow.addChild(radius);
        routeMode=selector(body,"巡逻方式",new String[][]{{"LOOP","循环"},{"PING_PONG","往返"},{"ONCE","一次"}});
        var points=WorkspacePanels.row();points.getLayout().height(25);body.addChild(points);points.addChild(NativeUiTheme.button(t("添加当前位置为路标"),()->{if(state!=null){route.add(state.getAsJsonObject("context").get("position").deepCopy());progress.setText(Component.literal(t("路标数量")+": "+route.size()));}}));points.addChild(NativeUiTheme.button(t("清空路标"),()->{while(!route.isEmpty())route.remove(0);}));
        var actions=WorkspacePanels.row();actions.getLayout().height(27);body.addChild(actions);actions.addChild(NativeUiTheme.button(t("切换主工作"),this::start));actions.addChild(NativeUiTheme.button(t("只改战斗规则"),this::rules));
        var controls=WorkspacePanels.row();controls.getLayout().height(27);body.addChild(controls);controls.addChild(NativeUiTheme.button(t("暂停"),()->control("pause")));controls.addChild(NativeUiTheme.button(t("继续"),()->control("resume")));controls.addChild(NativeUiTheme.button(t("全部停止"),()->send("stop_all",new JsonObject())));
        body.addChild(NativeUiTheme.text(t("战斗方式与主工作独立。缺少目标或范围时会说明原因。"),NativeUiTheme.MUTED,8));read();
    }
    private static Selector<Choice> selector(UIElement body,String label,String[][] choices){var row=WorkspacePanels.row();row.getLayout().height(25);body.addChild(row);var title=WorkspacePanels.text(t(label));title.getLayout().width(128);row.addChild(title);var select=new Selector<Choice>();select.getLayout().flex(1);var values=Arrays.stream(choices).map(p->new Choice(p[0],p[1])).toList();select.setCandidates(values);select.setValue(values.getFirst(),false);row.addChild(select);return select;}
    private static void choose(Selector<Choice> selector,String id){for(var value:selector.getCandidates())if(value.id.equals(id)){selector.setValue(value,false);return;}}
    private void read(){if(loading||saving||!NativeWorkspaceConnection.ready()||System.currentTimeMillis()<next)return;loading=true;next=System.currentTimeMillis()+1000;
        WorkspacePanels.request("behavior.read",Map.of("agentId",agent)).whenComplete((receipt,error)->{loading=false;if(!current.getAsBoolean())return;if(error!=null){WorkspacePanels.failure(status,error);return;}state=WorkspacePanels.state(receipt);active=null;
            for(var row:state.getAsJsonArray("skills")){var session=row.getAsJsonObject().getAsJsonObject("session");if(session.getAsJsonObject("spec").get("actor").getAsString().equals(actor.getValue().id)&&!Set.of("COMPLETED","CANCELLED","FAILED").contains(session.get("state").getAsString())&&!session.get("reason").getAsString().equals("TEMPORARY_WORK"))active=session;}
            if(active==null){status.setText(Component.literal(t("待命")));progress.setText(Component.literal(""));}else{var spec=active.getAsJsonObject("spec");status.setText(Component.literal(t(label(spec.get("kind").getAsString()))+" · "+NativeUiTheme.state(active.get("state").getAsString())));progress.setText(Component.literal(t(active.get("reason").getAsString())+" · "+progress(active.getAsJsonObject("counters"))));if(!initialized){choose(mode,spec.get("kind").getAsString());choose(strategy,spec.getAsJsonObject("combat").get("strategy").getAsString());choose(engagement,spec.getAsJsonObject("combat").get("engagement").getAsString());}}
            if(!initialized){var options=new ArrayList<Choice>();options.add(new Choice("$owner","主人"));for(var row:state.getAsJsonObject("context").getAsJsonArray("entities")){var e=row.getAsJsonObject();options.add(new Choice(e.get("id").getAsString(),e.get("name").getAsString()));}target.setCandidates(options);target.setValue(options.getFirst(),false);initialized=true;}
        });
    }
    private static String progress(JsonObject c){return t("已收获")+": "+count(c,"harvested")+" · "+t("已补种")+": "+count(c,"planted")+" · "+t("渔获")+": "+count(c,"fishingCatches");}
    private static long count(JsonObject c,String key){return c.has(key)?c.get(key).getAsLong():0;}
    private String label(String key){return switch(key){case "IDLE"->"待命";case "WANDER"->"自由活动";case "FOLLOW"->"跟随";case "PATROL"->"巡逻";case "GUARD"->"警戒";case "FARM"->"农务";case "FISH"->"钓鱼";default->"战斗";};}
    private JsonObject base(){if(state==null)throw new IllegalStateException(t("等待状态读取"));var n=new JsonObject();n.addProperty("id","mode_"+UUID.randomUUID().toString().substring(0,8));n.addProperty("actor",actor.getValue().id);n.addProperty("kind",mode.getValue().id);n.addProperty("dimension",state.getAsJsonObject("context").get("dimension").getAsString());n.addProperty("expected_revision",0);return n;}
    private void area(JsonObject n){
        if(region.getValue().id.equals("PREVIOUS")){if(active==null||active.getAsJsonObject("spec").get("area").isJsonNull())throw new IllegalArgumentException(t("当前没有工作区域"));var a=active.getAsJsonObject("spec").getAsJsonObject("area");for(String key:List.of("min","max")){var point=a.getAsJsonObject(key);var list=new JsonArray();for(String axis:List.of("x","y","z"))list.add(point.get(axis));n.add(key,list);}return;}
        var p=state.getAsJsonObject("context").getAsJsonArray(region.getValue().id.equals("LOOK")?"look":"position");if(p.size()!=3)throw new IllegalArgumentException(t("请先看向目标区域，或选择当前位置"));int radius=this.radius.getValue();var min=new JsonArray();var max=new JsonArray();for(int i=0;i<3;i++){int v=(int)Math.floor(p.get(i).getAsDouble());min.add(v-(i==1?1:radius));max.add(v+(i==1?1:radius));}n.add("min",min);n.add("max",max);
    }
    private JsonObject combat(){var n=new JsonObject();n.addProperty("strategy",strategy.getValue().id);n.addProperty("engagement",engagement.getValue().id);switch(engagement.getValue().id){case "PROTECT"->n.addProperty("protect",target.getValue().id);case "SPECIFIED"->{if(target.getValue().id.equals("$owner"))throw new IllegalArgumentException(t("请选择可交战的目标"));n.addProperty("target",target.getValue().id);}case "CLEAR_AREA"->area(n);}return n;}
    private void start(){try{var n=base();String kind=mode.getValue().id;if(Set.of("WANDER","GUARD","FARM","FISH").contains(kind))area(n);if(kind.equals("FOLLOW"))n.addProperty("target",target.getValue().id);if(kind.equals("PATROL")){if(route.isEmpty())throw new IllegalArgumentException(t("请先添加路标"));n.add("route",route.deepCopy());n.addProperty("repeat",!routeMode.getValue().id.equals("ONCE"));n.addProperty("ping_pong",routeMode.getValue().id.equals("PING_PONG"));}n.add("combat",combat());send("set_behavior_mode",n);}catch(Exception e){WorkspacePanels.failure(status,e);}}
    private void rules(){try{if(active==null){var n=base();n.addProperty("kind","IDLE");n.add("combat",combat());send("set_behavior_mode",n);return;}var n=identity();n.add("combat",combat());send("set_combat_policy",n);}catch(Exception e){WorkspacePanels.failure(status,e);}}
    private JsonObject identity(){if(active==null)throw new IllegalStateException(t("当前没有运行任务"));var n=new JsonObject();n.addProperty("id",active.getAsJsonObject("spec").get("id").getAsString());n.add("expected_revision",active.get("revision"));return n;}
    private void control(String action){try{var n=identity();n.addProperty("action",action);send("control_behavior",n);}catch(Exception e){WorkspacePanels.failure(status,e);}}
    private void send(String tool,JsonObject n){if(saving)return;saving=true;WorkspacePanels.request("behavior.write",Map.of("agentId",agent,"tool",tool,"source",n.toString())).whenComplete((receipt,error)->{saving=false;if(!current.getAsBoolean())return;if(error!=null)WorkspacePanels.failure(status,error);else{status.setText(Component.literal(t("已应用")));next=0;read();}});}
    private NativeBehaviorPanel(){throw new AssertionError();}
}
