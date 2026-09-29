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
    private String personaDraft;private long personaDraftRevision=-1;private boolean personaSaving;private JsonObject snapshot;private String tab="overview";private int inventoryOffset,contentOffset;private long nextRead;private boolean busy;private long personaRevision=-1,uiEpoch;
    private String conversationFilter="ACTIVE";private long conversationBefore;private final Deque<Long> conversationPages=new ArrayDeque<>();
    private AgentProfileScreen(UUID agent,String name){this(agent,name,new UIElement());}
    private AgentProfileScreen(UUID agent,String name,UIElement root){
        super(new ModularUI(NativeUiTheme.ui(root),Minecraft.getInstance().player),Component.literal(name));this.agent=agent;this.root=root;connection=Minecraft.getInstance().getConnection();
        root.getLayout().widthPercent(100).heightPercent(100).alignItems(AlignItems.CENTER).justifyContent(AlignContent.CENTER);
        var card=NativeUiTheme.card(new UIElement());card.getLayout().widthPercent(86).heightPercent(86).maxWidth(650).paddingAll(14);root.addChild(card);
        var top=WorkspacePanels.row();top.getLayout().height(30);card.addChild(top);title=NativeUiTheme.text(name,NativeUiTheme.TEXT,14);title.getLayout().flex(1);top.addChild(title);top.addChild(NativeUiTheme.iconButton("×",this::onClose));
        var identity=WorkspacePanels.row();identity.getLayout().height(76);card.addChild(identity);var portrait=NativeUiTheme.card(new UIElement());portrait.getLayout().width(56).height(72);portrait.getStyle().overlayTexture(com.lowdragmc.lowdraglib2.gui.texture.GuiTexture.of((context,x,y,w,h)->{var mc=Minecraft.getInstance();if(mc.level!=null&&mc.level.getEntity(agent) instanceof net.minecraft.world.entity.LivingEntity entity)net.minecraft.client.gui.screens.inventory.InventoryScreen.renderEntityInInventoryFollowsAngle(context.graphics,(int)x,(int)y,(int)(x+w),(int)(y+h),28,0,0,0,entity);}));identity.addChild(portrait);
        var metrics=new UIElement();metrics.getLayout().flex(1).paddingLeft(9).paddingTop(12);identity.addChild(metrics);summary=NativeUiTheme.text("",NativeUiTheme.MUTED,9);metrics.addChild(summary);health.getLayout().height(12).widthPercent(100).marginTop(6).marginBottom(10);health.label.setDisplay(false);health.barContainer.getLayout().paddingAll(2);metrics.addChild(health);
        var body=WorkspacePanels.row();body.getLayout().flex(1);card.addChild(body);var navigation=new UIElement();navigation.getLayout().width(108).heightPercent(100);body.addChild(navigation);content.getLayout().flex(1).heightPercent(100).paddingLeft(12);body.addChild(content);
        for(var item:List.of(new String[]{"overview","状态"},new String[]{"behavior","行为模式"},new String[]{"persona","人设"},new String[]{"chat","对话"},new String[]{"content","创建的内容"},new String[]{"inventory","背包"},new String[]{"appearance","外观"})){
            var button=NativeUiTheme.button(t(item[1]),()->{tab=item[0];draw();});button.getLayout().widthPercent(100).marginBottom(5);navigation.addChild(button);
        }
        status=NativeUiTheme.text(t("读取 AI…"),NativeUiTheme.MUTED,8);status.getLayout().height(18);card.addChild(status);draw();
    }
    public static void open(UUID agent,String name){Minecraft.getInstance().setScreen(new AgentProfileScreen(agent,name));NativeWorkspaceConnection.open();}
    public void smokeBehaviorTab(boolean release){NativeBehaviorPanel.smokeClickElement(root,t("行为模式"),release);}
    UIElement smokeRoot(){if(!Boolean.getBoolean("mineagent.skillSmoke"))throw new IllegalStateException("SMOKE_DISABLED");return root;}
    private static String t(String text){return ClientLanguage.t(text);}
    private boolean current(){return connection==Minecraft.getInstance().getConnection()&&Minecraft.getInstance().screen==this;}
    @Override public void tick(){super.tick();NativeUiTheme.controls(root);if(!current())return;long now=System.currentTimeMillis();if(busy||now<nextRead||!NativeWorkspaceConnection.ready())return;busy=true;nextRead=now+1000;
        WorkspacePanels.request("agent.panelRead",Map.of("agentId",agent.toString(),"inventoryOffset",Integer.toString(inventoryOffset),"contentOffset",Integer.toString(contentOffset))).whenComplete((receipt,error)->{
            busy=false;if(!current())return;if(error!=null){WorkspacePanels.failure(status,error);return;}var value=WorkspacePanels.state(receipt);boolean first=snapshot==null;boolean changed=first||tab.equals("content")&&!value.get("contents").equals(snapshot.get("contents"));snapshot=value;title.setText(Component.literal(value.get("name").getAsString()));float max=value.get("maxHealth").getAsFloat(),hp=value.get("health").getAsFloat();health.setProgress(max<=0?0:hp/max);summary.setText(Component.literal(NativeUiTheme.state(value.get("bodyState").getAsString())+"  ·  "+t("生命值")+" "+(int)hp+" / "+(int)max+"  ·  "+t("饱食度")+" "+value.get("food").getAsInt()));if(!tab.equals("persona"))status.setText(Component.literal(""));if(changed&&(!tab.equals("persona")||first))draw();
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
            default->{content.addChild(NativeUiTheme.text(t("AI 专属面板"),NativeUiTheme.ACCENT,12));content.addChild(WorkspacePanels.text(t("在这里查看该 AI 的状态、切换人设和管理对话。")));content.addChild(NativeUiTheme.button(t("选择对话"),()->NativeWorkspaceScreen.openForAgent(agent.toString(),title.getText().getString())));if(snapshot!=null)content.addChild(WorkspacePanels.text(t("模式")+" · "+NativeUiTheme.state(snapshot.get("mode").getAsString())));}
        }
    }
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
