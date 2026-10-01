package dev.mineagent.runtime.neoforge.client.nativeui;

import com.google.gson.*;
import com.lowdragmc.lowdraglib2.gui.ui.*;
import com.lowdragmc.lowdraglib2.gui.ui.elements.*;
import com.lowdragmc.lowdraglib2.gui.texture.ItemStackTexture;
import dev.mineagent.runtime.neoforge.client.language.ClientLanguage;
import dev.vfyjxf.taffy.style.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.network.chat.Component;
import java.util.*;

/** Passive MC-themed LDLib2 score HUD and two independent graphical equipment loadouts. */
public final class PvpMapClient {
    private static JsonObject state;private static Object connection,level;
    private static int fixtureStep,fixtureTicks;private static boolean capturedHud;
    private static ModularUI hud;private static TextElement score;private static UIElement panel;
    private static String t(String value){return ClientLanguage.t(value);}
    public static void accept(JsonObject message){
        var mc=Minecraft.getInstance();if(connection!=mc.getConnection()||level!=mc.level)clear();
        state=message;connection=mc.getConnection();level=mc.level;
        if(message.get("open").getAsBoolean())mc.setScreen(new Loadouts(message));
    }
    private static void clear(){if(hud!=null&&!hud.isRemoved())hud.onRemoved();hud=null;state=null;panel=null;score=null;}
    public static void render(GuiGraphicsExtractor graphics){
        var mc=Minecraft.getInstance();if(connection!=mc.getConnection()||level!=mc.level||mc.player==null){clear();return;}
        if(state==null||mc.options.hideGui||mc.screen instanceof Loadouts)return;
        if(hud==null){var root=new UIElement();root.getLayout().widthPercent(100).heightPercent(100);panel=NativeUiTheme.card(new UIElement());panel.getLayout().positionType(TaffyPosition.ABSOLUTE).right(8).top(12).width(170).gapAll(3);root.addChild(panel);
            panel.addChild(NativeUiTheme.text(t("PvP 训练场"),NativeUiTheme.ACCENT,10));score=NativeUiTheme.text("",NativeUiTheme.TEXT,9);panel.addChild(score);hud=new ModularUI(NativeUiTheme.ui(root),mc.player);hud.setTickWhileRending(true);hud.init(mc.getWindow().getGuiScaledWidth(),mc.getWindow().getGuiScaledHeight());NativeUiTheme.controls(root);}
        int w=mc.getWindow().getGuiScaledWidth(),h=mc.getWindow().getGuiScaledHeight();if(hud.getScreenWidth()!=w||hud.getScreenHeight()!=h)hud.init(w,h);panel.getLayout().width(Math.min(170,w-16));
        var p=state.getAsJsonObject("profile");String phase=state.get("phase").getAsString();String phaseText=switch(phase){case "FIGHTING"->t("本局剩余")+" "+state.get("seconds").getAsLong()+" "+t("秒");case "COUNTDOWN","STARTING"->t("准备开始");default->t("按 T 选择装备并开始下一局");};
        score.setText(Component.literal(t("总局数")+"："+p.get("rounds").getAsInt()+"\n"+t("当前胜率")+"："+String.format(Locale.ROOT,"%.1f%%",state.get("winRate").getAsDouble())+"\n"+t("平均击杀时间")+"："+average("averageKill")+"\n"+t("平均被击杀时间")+"："+average("averageDeath")+"\n"+t("胜 / 负 / 平")+"："+p.get("wins")+" / "+p.get("losses")+" / "+p.get("draws")+"\n"+phaseText));
        ModularUIClientAccess.getWidget(hud).extractRenderState(graphics,-1,-1,0);
    }
    private static String average(String key){return state.get(key).isJsonNull()?"—":String.format(Locale.ROOT,"%.1f",state.get(key).getAsDouble())+" "+t("秒");}
    private static ItemStack item(String id){var value=BuiltInRegistries.ITEM.getValue(Identifier.parse(id));return value==null?ItemStack.EMPTY:new ItemStack(value);}
    private static String itemName(String id){var stack=item(id);return stack.isEmpty()?t("不装备"):stack.getHoverName().getString();}
    private static String slotName(String slot){return t(switch(slot){case "head"->"头盔";case "chest"->"胸甲";case "legs"->"护腿";case "feet"->"靴子";case "mainhand"->"主手武器";default->"副手装备";});}
    private static void command(String text){var connection=Minecraft.getInstance().getConnection();if(connection!=null)connection.sendCommand(text);}
    /** Actual LDLib2 press/release and server round trips, enabled only in the isolated fixture. */
    public static void fixtureTick(){
        if(Boolean.getBoolean("mineagent.pvpMapReentryFixture")){
            if(state!=null&&++fixtureTicks==30){var mc=Minecraft.getInstance();try{var p=state.getAsJsonObject("profile");if(p.get("rounds").getAsInt()!=0||!p.getAsJsonObject("human").get("chest").getAsString().equals("minecraft:iron_chestplate")||!p.getAsJsonObject("ai").get("mainhand").getAsString().equals("minecraft:diamond_sword"))throw new IllegalStateException("MAP_REENTRY_DID_NOT_RESET");screenshot("pvp-map-reentry.png");java.nio.file.Files.writeString(mc.gameDirectory.toPath().resolve("pvp-map-reentry.json"),"{\"status\":\"PASS\",\"worldMarkerWithoutJvmFlag\":true,\"scoreReset\":true,\"loadoutsReset\":true}");}catch(Exception e){try{java.nio.file.Files.writeString(mc.gameDirectory.toPath().resolve("pvp-map-reentry.json"),new Gson().toJson(Map.of("status","FAILED","error",e.toString())));}catch(Exception ignored){}}mc.stop();}return;
        }
        if(!Boolean.getBoolean("mineagent.humanDuelFixture")||!Boolean.getBoolean("mineagent.pvpMap")||++fixtureTicks%10!=0)return;
        var mc=Minecraft.getInstance();
        try{
            if(fixtureTicks>2400)throw new IllegalStateException("PVP_MAP_UI_TIMEOUT_"+fixtureStep);
            if(fixtureStep==0&&mc.screen instanceof Loadouts s&&click(s,"pvp-human-chest")){fixtureStep=1;}
            else if(fixtureStep==1&&mc.screen instanceof Loadouts s&&click(s,"pvp-item-minecraft-diamond_chestplate")){fixtureStep=2;}
            else if(fixtureStep==2&&mc.screen instanceof Loadouts s&&s.data.getAsJsonObject("profile").getAsJsonObject("human").get("chest").getAsString().equals("minecraft:diamond_chestplate")&&click(s,"pvp-ai-mainhand")){fixtureStep=3;}
            else if(fixtureStep==3&&mc.screen instanceof Loadouts s&&click(s,"pvp-item-minecraft-iron_sword")){fixtureStep=4;}
            else if(fixtureStep==4&&mc.screen instanceof Loadouts s&&s.data.getAsJsonObject("profile").getAsJsonObject("ai").get("mainhand").getAsString().equals("minecraft:iron_sword")){screenshot("pvp-map-loadouts.png");if(click(s,"pvp-ready"))fixtureStep=5;}
            else if(fixtureStep==5&&state!=null&&state.getAsJsonObject("profile").get("rounds").getAsInt()==1&&!capturedHud){screenshot("pvp-map-score.png");capturedHud=true;java.nio.file.Files.writeString(mc.gameDirectory.toPath().resolve("pvp-map-ui.json"),"{\"status\":\"PASS\",\"realReleaseClicks\":true,\"independentEquipment\":true,\"scoreHud\":true}");}
        }catch(Exception error){try{java.nio.file.Files.writeString(mc.gameDirectory.toPath().resolve("human-duel-fixture.json"),new Gson().toJson(Map.of("status","FAILED","error",error.toString())));}catch(Exception ignored){}}
    }
    private static UIElement find(UIElement node,String id){if(id.equals(node.getId()))return node;for(var child:node.getChildren()){var found=find(child,id);if(found!=null)return found;}return null;}
    private static boolean click(Loadouts screen,String id){
        var node=find(screen.card,id);if(node==null||node.getSizeWidth()<=0||node.getSizeHeight()<=0)return false;
        float x=node.getPositionX()+node.getSizeWidth()/2,y=node.getPositionY()+node.getSizeHeight()/2;
        for(var parent=node.getParent();parent!=null;parent=parent.getParent())if(parent instanceof ScrollerView scroll){float low=scroll.viewPort.getContentY(),high=low+scroll.viewPort.getContentHeight(),range=scroll.getContainerHeight()-scroll.viewPort.getContentHeight();if(range>0&&(y-node.getSizeHeight()/2<low||y+node.getSizeHeight()/2>high)){scroll.verticalScroller.setNormalizedValue(Math.clamp(scroll.verticalScroller.getNormalizedValue()+(y-(low+high)/2)/range,0,1));return false;}}
        if(x<0||y<0||x>=screen.width||y>=screen.height)return false;screen.modularUI.refreshHoveredElementAtScreen(x,y);
        boolean hovered=false;for(var current=screen.modularUI.getLastHoveredElement();current!=null;current=current.getParent())if(current==node)hovered=true;if(!hovered)return false;
        var widget=ModularUIClientAccess.getWidget(screen.modularUI);var event=new net.minecraft.client.input.MouseButtonEvent(x,y,new net.minecraft.client.input.MouseButtonInfo(0,0));widget.mouseClicked(event,false);widget.mouseReleased(event);return true;
    }
    private static void screenshot(String name){var mc=Minecraft.getInstance();net.minecraft.client.Screenshot.takeScreenshot(mc.getMainRenderTarget(),image->{try(image){image.writeToFile(mc.gameDirectory.toPath().resolve(name));}catch(Exception e){throw new IllegalStateException(e);}});}
    private static final class Loadouts extends NativeInputScreen {
        final JsonObject data;final UIElement card,content;final Object source;
        Loadouts(JsonObject data){this(data,new UIElement());}
        private Loadouts(JsonObject data,UIElement root){
            super(new ModularUI(NativeUiTheme.ui(root),Minecraft.getInstance().player),Component.literal(t("PvP 训练场")));this.data=data;source=Minecraft.getInstance().getConnection();
            root.getLayout().widthPercent(100).heightPercent(100).alignItems(AlignItems.CENTER).justifyContent(AlignContent.CENTER);
            card=NativeUiTheme.card(new UIElement());card.getLayout().width(Math.min(560,Minecraft.getInstance().getWindow().getGuiScaledWidth()-12)).height(Math.min(350,Minecraft.getInstance().getWindow().getGuiScaledHeight()-12)).gapAll(5);root.addChild(card);
            card.addChild(NativeUiTheme.text(t("PvP 训练场")+" · "+t("不限局数"),NativeUiTheme.ACCENT,12));
            content=new UIElement();content.getLayout().flex(1).minHeight(0).widthPercent(100);card.addChild(content);home();
        }
        private void home(){
            content.clearAllChildren();var scroll=WorkspacePanels.scroller(content);var columns=WorkspacePanels.row();columns.getLayout().alignItems(AlignItems.FLEX_START);scroll.addScrollViewChild(columns);
            for(String actor:List.of("human","ai")){var column=new UIElement();column.getLayout().flex(1).minWidth(0).gapAll(4);columns.addChild(column);column.addChild(NativeUiTheme.text(t(actor.equals("human")?"我的装备":"AI 的装备"),NativeUiTheme.ACCENT,10));
                for(String slot:List.of("head","chest","legs","feet","mainhand","offhand")){String id=data.getAsJsonObject("profile").getAsJsonObject(actor).get(slot).getAsString();var button=NativeUiTheme.button(slotName(slot)+" · "+itemName(id),()->pick(actor,slot));button.setId("pvp-"+actor+"-"+slot);button.getLayout().widthPercent(100).minHeight(24).height(24).marginAll(0).paddingHorizontal(3);column.addChild(button);}
            }
            content.addChild(NativeUiTheme.text(t("双方独立选装，弓和弩自动配发箭矢。"),NativeUiTheme.MUTED,8));
            var buttons=WorkspacePanels.row();buttons.getLayout().height(25).flexShrink(0);content.addChild(buttons);
            var ready=NativeUiTheme.button(t("准备并开始"),()->{command("ai duel ready");onClose();});ready.setId("pvp-ready");buttons.addChild(ready);buttons.addChild(NativeUiTheme.button(t("停止本局"),()->{command("ai duel stop");onClose();}));buttons.addChild(NativeUiTheme.button(t("关闭"),this::onClose));NativeUiTheme.controls(card);
        }
        private void pick(String actor,String slot){
            content.clearAllChildren();content.addChild(NativeUiTheme.text(t(actor.equals("human")?"我的装备":"AI 的装备")+" / "+slotName(slot),NativeUiTheme.ACCENT,10));
            var scroll=WorkspacePanels.scroller(content);var list=new UIElement();list.getLayout().widthPercent(100).gapAll(3);scroll.addScrollViewChild(list);
            for(var raw:data.getAsJsonObject("catalog").getAsJsonArray(slot)){String id=raw.getAsString();var row=WorkspacePanels.row();row.getLayout().height(25).flexShrink(0);var icon=new UIElement();icon.getLayout().width(20).height(20).flexShrink(0);icon.getStyle().backgroundTexture(new ItemStackTexture(item(id)));row.addChild(icon);
                var select=NativeUiTheme.button(itemName(id),()->command("ai duel loadout "+data.getAsJsonObject("profile").get("revision").getAsLong()+" "+actor+" "+slot+" "+id));select.setId("pvp-item-"+id.replace(':','-'));select.getLayout().flex(1).marginAll(0);row.addChild(select);list.addChild(row);}
            content.addChild(NativeUiTheme.button(t("返回"),this::home));NativeUiTheme.controls(card);
        }
        @Override public boolean isPauseScreen(){return false;}
        @Override public void tick(){super.tick();if(Minecraft.getInstance().getConnection()!=source)onClose();}
    }
    private PvpMapClient(){}
}
