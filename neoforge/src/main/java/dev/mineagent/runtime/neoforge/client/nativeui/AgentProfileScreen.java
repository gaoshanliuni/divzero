package dev.mineagent.runtime.neoforge.client.nativeui;

import com.google.gson.*;
import com.lowdragmc.lowdraglib2.gui.holder.ModularUIScreen;
import com.lowdragmc.lowdraglib2.gui.texture.ItemStackTexture;
import com.lowdragmc.lowdraglib2.gui.ui.*;
import com.lowdragmc.lowdraglib2.gui.ui.elements.*;
import dev.mineagent.runtime.neoforge.client.language.ClientLanguage;
import dev.vfyjxf.taffy.style.*;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import java.util.*;

/** Right-click profile for one immutable AI id; no global control-center menu is exposed. */
public final class AgentProfileScreen extends NativeInputScreen {
    private final UUID agent;private final Object connection;private UIElement root;private final UIElement content=new UIElement();
    private final TextElement title,summary,status;private final ProgressBar health=new ProgressBar();
    private TextElement overviewStats;private UIElement effectList;private Button respawnToggle,respawnNow,gameModeButton,teleportButton;private boolean respawnSaving;
    private String personaDraft;private long personaDraftRevision=-1;private boolean personaSaving;private JsonObject snapshot;private String tab="overview";private int inventoryOffset,contentOffset;private long nextRead;private boolean busy;private long personaRevision=-1,uiEpoch;
    private String conversationFilter="ACTIVE";private long conversationBefore;private final Deque<Long> conversationPages=new ArrayDeque<>();
    private AgentProfileScreen(UUID agent,String name){this(agent,name,new UIElement());}
    private AgentProfileScreen(UUID agent,String name,UIElement root){
        super(new ModularUI(NativeUiTheme.ui(root),Minecraft.getInstance().player),Component.literal(name));this.agent=agent;this.root=root;connection=Minecraft.getInstance().getConnection();
        root.getLayout().widthPercent(100).heightPercent(100).alignItems(AlignItems.CENTER).justifyContent(AlignContent.CENTER);
        boolean compact=Minecraft.getInstance().getWindow().getGuiScaledWidth()<540;
        var card=NativeUiTheme.card(new UIElement());card.getLayout().widthPercent(compact?98:86).heightPercent(compact?98:86).maxWidth(650).paddingAll(compact?6:14);root.addChild(card);
        var top=WorkspacePanels.row();top.getLayout().height(compact?25:30).flexShrink(0);card.addChild(top);title=NativeUiTheme.text(name,NativeUiTheme.TEXT,compact?10:14);title.getLayout().flex(1);top.addChild(title);top.addChild(NativeUiTheme.iconButton("×",this::onClose));
        var identity=WorkspacePanels.row();identity.getLayout().height(compact?45:76).flexShrink(0);card.addChild(identity);var portrait=NativeUiTheme.card(new UIElement());portrait.getLayout().width(compact?36:56).height(compact?43:72);portrait.getStyle().overlayTexture(com.lowdragmc.lowdraglib2.gui.texture.GuiTexture.of((context,x,y,w,h)->{var mc=Minecraft.getInstance();if(mc.level!=null&&mc.level.getEntity(agent) instanceof net.minecraft.world.entity.LivingEntity entity)net.minecraft.client.gui.screens.inventory.InventoryScreen.renderEntityInInventoryFollowsAngle(context.graphics,(int)x,(int)y,(int)(x+w),(int)(y+h),28,0,0,0,entity);}));identity.addChild(portrait);
        var metrics=new UIElement();metrics.getLayout().flex(1).minWidth(0).paddingLeft(compact?5:9).paddingTop(compact?2:12);identity.addChild(metrics);summary=NativeUiTheme.text("",NativeUiTheme.MUTED,9);metrics.addChild(summary);health.getLayout().height(12).widthPercent(100).marginTop(6).marginBottom(10);health.label.setDisplay(false);health.barContainer.getLayout().paddingAll(2);metrics.addChild(health);
        var body=WorkspacePanels.row();body.getLayout().flex(1).minHeight(0);card.addChild(body);var navigation=new UIElement();navigation.getLayout().width(compact?78:108).flexShrink(0).heightPercent(100);body.addChild(navigation);content.getLayout().flex(1).minWidth(0).minHeight(0).heightPercent(100).paddingLeft(compact?6:12);body.addChild(content);
        for(var item:List.of(new String[]{"overview","状态"},new String[]{"behavior","行为模式"},new String[]{"persona","人设"},new String[]{"chat","对话"},new String[]{"content","创建的内容"},new String[]{"inventory","背包"},new String[]{"appearance","外观"})){
            var button=NativeUiTheme.button(t(item[1]),()->{tab=item[0];draw();});button.getLayout().widthPercent(100).height(compact?18:23).minHeight(compact?18:23).flexShrink(0).marginBottom(compact?1:5);if(compact)button.text.textStyle(style->style.fontSize(8));navigation.addChild(button);
        }
        status=NativeUiTheme.text(t("读取 AI…"),NativeUiTheme.MUTED,8);status.getLayout().height(compact?12:18).flexShrink(0);card.addChild(status);draw();
    }
    public static void open(UUID agent,String name){if(!dev.mineagent.runtime.neoforge.client.MineAgentClientTrustPrompt.enabled()){Minecraft.getInstance().setScreen(new net.minecraft.client.gui.screens.ChatScreen("",false));dev.mineagent.runtime.neoforge.client.MineAgentClientTrustPrompt.showChoice(true);return;}Minecraft.getInstance().setScreen(new AgentProfileScreen(agent,name));NativeWorkspaceConnection.open();}
    public void smokeInventoryTab(){if(!Boolean.getBoolean("mineagent.skillSmoke"))throw new IllegalStateException("SMOKE_DISABLED");tab="inventory";draw();}
    public void smokeBehaviorTab(boolean release){NativeBehaviorPanel.smokeClickElement(root,t("行为模式"),release);}
    UIElement smokeRoot(){if(!Boolean.getBoolean("mineagent.skillSmoke"))throw new IllegalStateException("SMOKE_DISABLED");return root;}
    private static String t(String text){return ClientLanguage.t(text);}
    private boolean current(){return connection==Minecraft.getInstance().getConnection()&&Minecraft.getInstance().screen==this;}
    @Override public void tick(){super.tick();NativeUiTheme.controls(root);if(!current())return;long now=System.currentTimeMillis();if(busy||now<nextRead||!NativeWorkspaceConnection.ready())return;busy=true;nextRead=now+1000;
        WorkspacePanels.request("agent.panelRead",Map.of("agentId",agent.toString(),"inventoryOffset",Integer.toString(inventoryOffset),"contentOffset",Integer.toString(contentOffset))).whenComplete((receipt,error)->{
            busy=false;if(!current())return;if(error!=null){WorkspacePanels.failure(status,error);return;}var value=WorkspacePanels.state(receipt);boolean first=snapshot==null;boolean changed=first||tab.equals("content")&&!value.get("contents").equals(snapshot.get("contents"));snapshot=value;title.setText(Component.literal(value.get("name").getAsString()));float max=value.get("maxHealth").getAsFloat(),hp=value.get("health").getAsFloat();health.setProgress(max<=0?0:hp/max);summary.setText(Component.literal(NativeUiTheme.state(value.get("bodyState").getAsString())+"  ·  "+t("生命值")+" "+(int)hp+" / "+(int)max+"  ·  "+t("饱食度")+" "+value.get("food").getAsInt()));if(!tab.equals("persona"))status.setText(Component.literal(""));if(changed&&!tab.equals("inventory")&&(!tab.equals("persona")||first))draw();if(tab.equals("overview"))updateOverview();
        });
    }
    private void draw(){
        uiEpoch++;content.clearAllChildren();
        switch(tab){
            case "behavior"->{long epoch=uiEpoch;NativeBehaviorPanel.attach(content,agent.toString(),()->current()&&uiEpoch==epoch&&tab.equals("behavior"));}
            case "persona"->persona();
            case "chat"->conversations();
            case "inventory"->inventory();
            case "content"->contents();
            case "appearance"->{NativeWorkspaceScreen.openForAgent(agent.toString(),title.getText().getString());NativeAppearancePanel.open((NativeWorkspaceScreen)Minecraft.getInstance().screen,agent.toString());}
            default->{var scroll=WorkspacePanels.scroller(content);var pane=new UIElement();pane.getLayout().widthPercent(100).gapAll(6);scroll.addScrollViewChild(pane);overviewStats=WorkspacePanels.text("");pane.addChild(overviewStats);respawnToggle=NativeUiTheme.button(t("自动重生：开启"),()->changeRespawn(false));respawnToggle.setId("agent-auto-respawn");pane.addChild(respawnToggle);respawnNow=NativeUiTheme.button(t("立即重生"),()->changeRespawn(true));respawnNow.setId("agent-respawn-now");pane.addChild(respawnNow);gameModeButton=NativeUiTheme.button(t("切换游戏模式"),this::changeGameMode);gameModeButton.setId("agent-game-mode");pane.addChild(gameModeButton);teleportButton=NativeUiTheme.button(t("传送到我"),()->profileAction("teleport_to_owner",new JsonObject()));teleportButton.setId("agent-teleport-owner");pane.addChild(teleportButton);pane.addChild(NativeUiTheme.text(t("状态效果"),NativeUiTheme.ACCENT,10));effectList=new UIElement();effectList.setId("agent-status-effects");effectList.getLayout().widthPercent(100).gapAll(3);pane.addChild(effectList);pane.addChild(NativeUiTheme.button(t("选择对话"),()->NativeWorkspaceScreen.openForAgent(agent.toString(),title.getText().getString())));updateOverview();}
        }
    }
    private void updateOverview(){if(snapshot==null||overviewStats==null||effectList==null)return;
        var p=snapshot.getAsJsonArray("position");String location=p.size()==3?String.format(java.util.Locale.ROOT,"%.1f, %.1f, %.1f",p.get(0).getAsDouble(),p.get(1).getAsDouble(),p.get(2).getAsDouble()):t("身体未加载");String dimension=snapshot.get("dimension").getAsString();dimension=switch(dimension){case "minecraft:overworld"->t("主世界");case "minecraft:the_nether"->t("下界");case "minecraft:the_end"->t("末地");default->dimension;};
        overviewStats.setText(Component.literal(t("模式")+" · "+NativeUiTheme.state(snapshot.get("mode").getAsString())+"\n"+t("护甲")+" · "+snapshot.get("armor").getAsInt()+"  "+t("吸收生命")+" · "+String.format(java.util.Locale.ROOT,"%.1f",snapshot.get("absorption").getAsFloat())+"\n"+t("经验等级")+" · "+snapshot.get("xpLevel").getAsInt()+"  "+t("氧气")+" · "+snapshot.get("air").getAsInt()+" / "+snapshot.get("maxAir").getAsInt()+"\n"+dimension+" · "+location));
        var policy=snapshot.getAsJsonObject("respawn");respawnToggle.setText(Component.literal(t(policy.get("enabled").getAsBoolean()?"自动重生：开启":"自动重生：关闭")));respawnToggle.setActive(snapshot.get("canManage").getAsBoolean()&&!respawnSaving);respawnNow.setDisplay(snapshot.get("health").getAsFloat()<=0);respawnNow.setActive(snapshot.get("canManage").getAsBoolean()&&!respawnSaving);
        gameModeButton.setText(Component.literal(t("切换游戏模式")+" · "+NativeUiTheme.state(snapshot.get("mode").getAsString())));gameModeButton.setActive(snapshot.get("canManage").getAsBoolean()&&!respawnSaving&&snapshot.get("health").getAsFloat()>0);teleportButton.setActive(gameModeButton.isActive());
        effectList.clearAllChildren();var effects=snapshot.getAsJsonArray("effects");if(effects.isEmpty())effectList.addChild(WorkspacePanels.text(t("没有状态效果")));for(var raw:effects){var effect=raw.getAsJsonObject();int seconds=Math.max(0,effect.get("remainingTicks").getAsInt()/20);String duration=effect.get("infinite").getAsBoolean()?"∞":String.format(java.util.Locale.ROOT,"%d:%02d",seconds/60,seconds%60);var label=WorkspacePanels.text("");label.setText(Component.translatableWithFallback(effect.get("translationKey").getAsString(),effect.get("name").getAsString()).append(Component.literal(" "+effect.get("level").getAsInt()+" · "+duration)));effectList.addChild(label);}
    }
    private void changeGameMode(){
        if(snapshot==null||!snapshot.get("canManage").getAsBoolean()||respawnSaving)return;
        String currentMode=snapshot.get("mode").getAsString();var choices=List.of("SURVIVAL","CREATIVE","ADVENTURE","SPECTATOR");int next=(choices.indexOf(currentMode)+1)%choices.size();
        var input=new JsonObject();input.addProperty("expected_mode",currentMode);input.addProperty("mode",choices.get(next));profileAction("set_game_mode",input);
    }
    private void profileAction(String tool,JsonObject source){
        if(snapshot==null||respawnSaving||!snapshot.get("canManage").getAsBoolean())return;respawnSaving=true;
        WorkspacePanels.request("behavior.write",Map.of("agentId",agent.toString(),"tool",tool,"source",source.toString())).whenComplete((receipt,error)->{respawnSaving=false;if(!current())return;if(error!=null)WorkspacePanels.failure(status,error);else status.setText(Component.literal(t("已完成")));nextRead=0;});
    }
    private void changeRespawn(boolean now){if(snapshot==null||respawnSaving||!snapshot.get("canManage").getAsBoolean())return;respawnSaving=true;var source=new JsonObject();if(!now){var policy=snapshot.getAsJsonObject("respawn");source.addProperty("enabled",!policy.get("enabled").getAsBoolean());source.add("expected_revision",policy.get("revision"));}WorkspacePanels.request("behavior.write",Map.of("agentId",agent.toString(),"tool",now?"respawn_now":"set_respawn_policy","source",source.toString())).whenComplete((receipt,error)->{respawnSaving=false;if(!current())return;if(error!=null)WorkspacePanels.failure(status,error);nextRead=0;});}
    private void persona(){
        if(snapshot!=null&&!snapshot.get("canEditPersona").getAsBoolean()){content.addChild(WorkspacePanels.text(t("没有权限")));return;}
        long epoch=uiEpoch;var editor=new TextArea();editor.getLayout().flex(1).widthPercent(100);PersonaLibraryBar.add(content,agent.toString(),editor,status,()->current()&&uiEpoch==epoch,value->personaDraft=value);content.addChild(editor);personaRevision=personaDraftRevision;if(personaDraft!=null)editor.setValue(personaDraft.split("\n",-1),false);editor.registerValueListener(value->personaDraft=String.join("\n",value));
        WorkspacePanels.request("persona.read",Map.of("agentId",agent.toString())).whenComplete((receipt,error)->{if(!current()||uiEpoch!=epoch||!tab.equals("persona"))return;if(error!=null){WorkspacePanels.failure(status,error);return;}var value=WorkspacePanels.state(receipt);if(personaDraftRevision<0)personaDraftRevision=personaRevision=value.get("revision").getAsLong();if(personaDraft==null){personaDraft=value.get("text").getAsString();editor.setValue(personaDraft.split("\n",-1),false);}});
        content.addChild(NativeUiTheme.button(t("应用人设"),()->{if(personaRevision<0||personaSaving)return;personaSaving=true;WorkspacePanels.request("persona.save",Map.of("agentId",agent.toString(),"expectedRevision",Long.toString(personaRevision),"text",String.join("\n",editor.getValue()))).whenComplete((receipt,error)->{personaSaving=false;if(error==null)personaDraftRevision=WorkspacePanels.state(receipt).get("appliedRevision").getAsLong();if(!current()||uiEpoch!=epoch)return;if(error!=null)WorkspacePanels.failure(status,error);else{status.setText(Component.literal(t("已保存")));personaRevision=personaDraftRevision;}});}));
    }
    void smokeTab(String value){if(!Boolean.getBoolean("mineagent.nativeUiSmoke"))throw new IllegalStateException("SMOKE_DISABLED");tab=value;draw();}
    private void conversations(){
        long epoch=uiEpoch;content.addChild(WorkspacePanels.text(t("选择已有对话，或让 AI 为新对话生成简称。")));var list=WorkspacePanels.scroller(content);
        var filters=WorkspacePanels.row();filters.getLayout().height(25);content.addChild(filters);
        for(var choice:List.of(new String[]{"ACTIVE","进行中"},new String[]{"ARCHIVED","已归档"},new String[]{"DELETED","已删除"}))filters.addChild(NativeUiTheme.button((choice[0].equals(conversationFilter)?"● ":"")+t(choice[1]),()->{conversationFilter=choice[0];conversationBefore=0;conversationPages.clear();draw();}));
        content.addChild(NativeUiTheme.button(t("新建对话"),()->WorkspacePanels.request("conversation.write",Map.of("kind","create","agentId",agent.toString(),"title",t("新的对话"),"autoTitle","true")).whenComplete((receipt,error)->{if(!current())return;if(error!=null)WorkspacePanels.failure(status,error);else NativeWorkspaceScreen.openConversation(agent.toString(),title.getText().getString(),WorkspacePanels.state(receipt).get("conversationId").getAsString());})));
        WorkspacePanels.request("conversation.read",Map.of("kind","list","agentId",agent.toString(),"state",conversationFilter,"search","","before",Long.toString(conversationBefore))).whenComplete((receipt,error)->{if(!current()||uiEpoch!=epoch)return;if(error!=null){WorkspacePanels.failure(status,error);return;}var data=WorkspacePanels.state(receipt);for(var raw:data.getAsJsonArray("conversations")){var value=raw.getAsJsonObject();var button=NativeUiTheme.button(value.get("title").getAsString(),()->NativeWorkspaceScreen.openConversation(agent.toString(),title.getText().getString(),value.get("conversationId").getAsString()));button.setId("profile-conversation-"+value.get("conversationId").getAsString());list.addScrollViewChild(button);}var pager=WorkspacePanels.row();pager.getLayout().height(25);content.addChild(pager);if(!conversationPages.isEmpty())pager.addChild(NativeUiTheme.button(t("上一页"),()->{conversationBefore=conversationPages.pop();draw();}));long next=data.get("nextBefore").getAsLong();if(next>0)pager.addChild(NativeUiTheme.button(t("下一页"),()->{conversationPages.push(conversationBefore);conversationBefore=next;draw();}));});
    }
    private void inventory(){long epoch=uiEpoch;NativeInventoryPanel.attach(content,agent.toString(),()->current()&&uiEpoch==epoch&&tab.equals("inventory"));}
    private void contents(){
        if(snapshot!=null&&!snapshot.getAsJsonObject("contents").get("private").getAsBoolean())content.addChild(NativeUiTheme.button(t("建筑计划"),()->{NativeWorkspaceScreen.openForAgent(agent.toString(),title.getText().getString());NativeBuildingPanel.open((NativeWorkspaceScreen)Minecraft.getInstance().screen,agent.toString());}));
        if(snapshot!=null&&!snapshot.getAsJsonObject("contents").get("private").getAsBoolean())content.addChild(NativeUiTheme.button(t("该 AI 的已保存界面"),()->{NativeWorkspaceScreen.openForAgent(agent.toString(),title.getText().getString());NativeSavedInterfacesPanel.open((NativeWorkspaceScreen)Minecraft.getInstance().screen,agent.toString());}));
        if(snapshot==null)return;var data=snapshot.getAsJsonObject("contents");if(data.get("private").getAsBoolean()){content.addChild(WorkspacePanels.text(t("创建的内容仅对所有者可见。")));return;}
        var list=WorkspacePanels.scroller(content);for(var raw:data.getAsJsonArray("items")){var item=raw.getAsJsonObject();var card=WorkspacePanels.card(list,item.get("name").getAsString());card.addChild(WorkspacePanels.text(item.get("version").getAsString()));card.addChild(NativeUiTheme.button(t("在工作区查看"),()->NativeWorkspaceScreen.openPackage(item)));}
        var pager=WorkspacePanels.row();pager.getLayout().height(26);content.addChild(pager);pager.addChild(NativeUiTheme.button(t("上一页"),()->{contentOffset=Math.max(0,contentOffset-8);nextRead=0;}));if(data.get("nextOffset").getAsInt()>=0)pager.addChild(NativeUiTheme.button(t("下一页"),()->{contentOffset=data.get("nextOffset").getAsInt();nextRead=0;}));
    }
}
