package dev.mineagent.runtime.neoforge.client.nativeui;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Executes the visible LDLib2 buttons, then checks server state; no model or UI callback shortcuts. */
@EventBusSubscriber(modid="mineagent_runtime",value=Dist.CLIENT)
public final class NativeFunctionalSmokeClient {
    private static net.minecraft.world.level.block.state.BlockState buildingBefore;
    private static CompletableFuture<Map<String,Object>> result;private static UUID agent,a,b,old,geometryPlan;
    private static int stage,ticks,pressedAt,pagerStage,editorAt,focusChanges;private static float editorX,editorY;private static boolean pending,chatRevealed,uiApplySubmitted;private static final List<String> checked=new ArrayList<>();
    public static CompletableFuture<Map<String,Object>> run(UUID ai,UUID first,UUID second,UUID oldest){
        if(!Boolean.getBoolean("mineagent.skillSmoke"))throw new IllegalStateException("SMOKE_DISABLED");
        org.lwjgl.glfw.GLFW.glfwFocusWindow(Minecraft.getInstance().getWindow().handle());
        agent=ai;a=first;b=second;old=oldest;stage=ticks=pressedAt=pagerStage=0;pending=chatRevealed=uiApplySubmitted=false;checked.clear();return result=new CompletableFuture<>();
    }
    public static CompletableFuture<Map<String,Object>> runBuilding(UUID ai){
        if(!Boolean.getBoolean("mineagent.skillSmoke"))throw new IllegalStateException("SMOKE_DISABLED");agent=ai;stage=47;ticks=0;pending=uiApplySubmitted=false;buildingBefore=null;checked.clear();result=new CompletableFuture<>();NativeWorkspaceScreen.openForAgent(ai.toString(),"建筑验收");return result;
    }
    public static CompletableFuture<Map<String,Object>> runGeometry(UUID ai,UUID plan){
        if(!Boolean.getBoolean("mineagent.skillSmoke"))throw new IllegalStateException("SMOKE_DISABLED");agent=ai;geometryPlan=plan;stage=60;ticks=0;pending=false;checked.clear();result=new CompletableFuture<>();NativeWorkspaceScreen.openForAgent(ai.toString(),"几何验收");return result;
    }
    private static Minecraft mc(){return Minecraft.getInstance();}
    private static UIElement root(){return mc().screen instanceof NativeWorkspaceScreen s?s.smokeRoot():mc().screen instanceof AgentProfileScreen s?s.smokeRoot():null;}
    private static Button find(UIElement element,String value){if(element==null)return null;if(element instanceof Button button&&(value.equals(button.getId())||value.equals(button.text.getText().getString())))return button;for(var child:element.getChildren()){var found=find(child,value);if(found!=null)return found;}return null;}
    private static boolean click(String value){return gesture(value,true)&&gesture(value,false);}
    private static boolean gesture(String value,boolean down){var button=find(root(),value);if(button==null||button.getSizeWidth()<=0||button.getSizeHeight()<=0)return false;var screen=(com.lowdragmc.lowdraglib2.gui.holder.ModularUIScreen)mc().screen;float x=button.getPositionX()+button.getSizeWidth()/2,y=button.getPositionY()+button.getSizeHeight()/2;require(x>=0&&x<mc().getWindow().getGuiScaledWidth()&&y>=0&&y<mc().getWindow().getGuiScaledHeight(),"UI_BUTTON_OUTSIDE_SCREEN_"+value);screen.modularUI.refreshHoveredElementAtScreen(x,y);if(!hoveredWithin(screen,button))return false;var widget=com.lowdragmc.lowdraglib2.gui.ui.ModularUIClientAccess.getWidget(screen.modularUI);var event=new net.minecraft.client.input.MouseButtonEvent(x,y,new net.minecraft.client.input.MouseButtonInfo(0,0));if(down)widget.mouseClicked(event,false);else widget.mouseReleased(event);return true;}
    private static boolean hoveredWithin(com.lowdragmc.lowdraglib2.gui.holder.ModularUIScreen screen,UIElement expected){for(var node=screen.modularUI.getLastHoveredElement();node!=null;node=node.getParent())if(node==expected)return true;return false;}
    private static boolean slotClick(int index){return slotClick(index,0,0);}
    private static boolean slotClick(int index,int button,int modifiers){
        if(!(mc().screen instanceof AgentInventoryScreen screen)||screen.getMenu().slots.size()<=index)return false;
        var slot=screen.getMenu().getSlot(index);double x=screen.getGuiLeft()+slot.x+8,y=screen.getGuiTop()+slot.y+8;
        var mouse=new net.minecraft.client.input.MouseButtonEvent(x,y,new net.minecraft.client.input.MouseButtonInfo(button,modifiers));screen.mouseClicked(mouse,false);screen.mouseReleased(mouse);return true;
    }
    private static com.lowdragmc.lowdraglib2.gui.ui.elements.Selector<?> findSelector(UIElement root){if(root==null)return null;if(root instanceof com.lowdragmc.lowdraglib2.gui.ui.elements.Selector<?> selector&&selector.isVisible()&&selector.getId().equals("conversation-filter"))return selector;for(var child:root.getChildren()){var found=findSelector(child);if(found!=null)return found;}return null;}
    private static boolean pointer(UIElement element,boolean down){
        if(element.getSizeWidth()<=0||element.getSizeHeight()<=0)return false;var screen=(com.lowdragmc.lowdraglib2.gui.holder.ModularUIScreen)mc().screen;
        float x=element.getPositionX()+element.getSizeWidth()/2,y=element.getPositionY()+element.getSizeHeight()/2;require(x>=0&&y>=0&&x<screen.width&&y<screen.height,"DROPDOWN_OUTSIDE_SCREEN");screen.modularUI.refreshHoveredElementAtScreen(x,y);if(!hoveredWithin(screen,element))return false;
        var mouse=new net.minecraft.client.input.MouseButtonEvent(x,y,new net.minecraft.client.input.MouseButtonInfo(0,0));var widget=com.lowdragmc.lowdraglib2.gui.ui.ModularUIClientAccess.getWidget(screen.modularUI);if(down)widget.mouseClicked(mouse,false);else widget.mouseReleased(mouse);return true;
    }
    private static net.minecraft.client.input.MouseButtonEvent slotEvent(AgentInventoryScreen screen,int index){var slot=screen.getMenu().getSlot(index);return new net.minecraft.client.input.MouseButtonEvent(screen.getGuiLeft()+slot.x+8,screen.getGuiTop()+slot.y+8,new net.minecraft.client.input.MouseButtonInfo(0,0));}
    private static UIElement findElement(UIElement root,String id){if(root==null)return null;if(id.equals(root.getId()))return root;for(var child:root.getChildren()){var found=findElement(child,id);if(found!=null)return found;}return null;}
    private static boolean conversation(UUID id,String marker){return mc().screen instanceof NativeWorkspaceScreen screen&&screen.smokeState().get("conversation").equals(id.toString())&&screen.smokeHistory().equals(marker);}
    private static void require(boolean value,String error){if(!value)throw new IllegalStateException(error);}
    private static void advance(String name){checked.add(name);stage++;}
    private static void readInventory(java.util.function.Predicate<com.google.gson.JsonObject> predicate,String label){pending=true;WorkspacePanels.request("agent.inventoryRead",Map.of("agentId",agent.toString(),"offset","0")).whenComplete((r,e)->{pending=false;if(e!=null){result.completeExceptionally(e);return;}if(predicate.test(WorkspacePanels.state(r)))advance(label);});}
    private static com.google.gson.JsonObject slot(com.google.gson.JsonObject state,String side,int id){for(var v:state.getAsJsonArray(side+"Slots"))if(v.getAsJsonObject().get("slot").getAsInt()==id)return v.getAsJsonObject();throw new IllegalArgumentException();}
    @SubscribeEvent public static void tick(ClientTickEvent.Post event){
        if(result==null||result.isDone())return;try{
            if(++ticks>1400)throw new IllegalStateException("FUNCTIONAL_UI_TIMEOUT_STAGE_"+stage+" checked="+checked+" connection="+NativeWorkspaceConnection.diagnostic()+" ui="+(mc().screen instanceof NativeWorkspaceScreen screen?screen.smokeState():mc().screen==null?"NONE":mc().screen.getClass().getName()));
            if(pending||ticks%6!=0)return;if(!mc().isWindowActive()){org.lwjgl.glfw.GLFW.glfwFocusWindow(mc().getWindow().handle());return;}
            switch(stage){
                case 0->{NativeWorkspaceScreen.openConversation(agent.toString(),"界面验收",a.toString());advance("open-first-conversation");}
                case 1->{if(conversation(a,"HISTORY_A_ONLY")){((NativeWorkspaceScreen)mc().screen).smokeDraft("draft-A");if(pressedAt==0){if(gesture("conversation-"+b,true)){pressedAt=ticks;NativeWorkspaceScreen.push("conversationChanged",new com.google.gson.JsonObject());}return;}if(ticks-pressedAt>=30&&gesture("conversation-"+b,false))advance("release-second-row-after-refresh");}}
                case 2->{if(conversation(b,"HISTORY_B_ONLY")){require(((NativeWorkspaceScreen)mc().screen).smokeState().get("draft").equals(""),"DRAFT_LEAKED_TO_B");((NativeWorkspaceScreen)mc().screen).smokeDraft("draft-B");if(click("conversation-"+a))advance("click-first-row");}}
                case 3->{if(conversation(a,"HISTORY_A_ONLY")){require(((NativeWorkspaceScreen)mc().screen).smokeState().get("draft").equals("draft-A"),"DRAFT_A_LOST");if(click("conversation-"+b)&&click("conversation-"+a))advance("rapid-switch-with-pending-reads");}}
                case 4->{switch(pagerStage){case 0->{if(conversation(a,"HISTORY_A_ONLY")&&click("下一页"))pagerStage++;}case 1->{if(click("conversation-"+old))pagerStage++;}case 2->{if(conversation(old,"HISTORY_OLD_ONLY")&&click("上一页")){checked.add("older-conversation-page-and-back");pagerStage++;}}case 3->{if(click("conversation-"+a))pagerStage++;}default->{if(conversation(a,"HISTORY_A_ONLY")&&click("文件"))advance("open-files");}}}
                case 5->{if(!chatRevealed){chatRevealed=click("对话");return;}if(click("conversation-"+b))advance("return-chat-and-select");}
                case 6->{if(conversation(b,"HISTORY_B_ONLY")){require(((NativeWorkspaceScreen)mc().screen).smokeState().get("draft").equals("draft-B"),"DRAFT_B_LOST");AgentProfileScreen.open(agent,"界面验收");advance("open-profile");}}
                case 7->{if(mc().screen instanceof AgentProfileScreen&&click("对话"))advance("profile-conversations");}
                case 8->{if(click("profile-conversation-"+a))advance("profile-select-existing");}
                case 9->{if(conversation(a,"HISTORY_A_ONLY")){AgentProfileScreen.open(agent,"界面验收");advance("profile-open-inventory");}}
                case 10->{if(click("背包"))advance("inventory-tab");}
                case 11->{if(slotClick(69))advance("select-player-diamonds");}
                case 12->{if(slotClick(33))advance("move-to-agent-slot");}
                case 13->readInventory(s->slot(s,"agent",1).get("count").getAsInt()==16&&slot(s,"player",1).get("empty").getAsBoolean(),"server-confirmed-transfer");
                case 14->{if(slotClick(68))advance("select-helmet");}
                case 15->{if(mc().screen instanceof AgentInventoryScreen)advance("native-equipment-slots-visible");}
                case 16->{if(slotClick(0))advance("equip-real-helmet");}
                case 17->readInventory(s->slot(s,"agent",39).get("item").getAsString().equals("minecraft:diamond_helmet")&&slot(s,"player",0).get("empty").getAsBoolean(),"server-confirmed-equipment");
                case 18->{net.minecraft.client.Screenshot.takeScreenshot(mc().getMainRenderTarget(),image->{try(image){image.writeToFile(mc().gameDirectory.toPath().resolve("persistent-skill-smoke/inventory-live.png"));}catch(Exception e){result.completeExceptionally(e);}});mc().player.closeContainer();NativeWorkspaceScreen.openForAgent(agent.toString(),"界面验收");advance("f2-return-preserves-conversation");}
                case 19->{if(conversation(a,"HISTORY_A_ONLY")&&click("AI 玩家"))advance("f2-ai-manager");}
                case 20->{if(click("背包"))advance("f2-open-shared-inventory");}
                case 21->{if(mc().screen instanceof AgentInventoryScreen){mc().player.closeContainer();advance("f2-native-container-entry");}}
                case 22->{NativeWorkspaceScreen.openConversation(agent.toString(),"界面验收",a.toString());advance("open-scale-controls");}
                case 23->{if(find(root(),"界面尺寸")==null){click("设置");}else if(click("界面尺寸"))advance("scale-menu");}
                case 24->{if(click("gui-scale-4"))advance("scale-four-native-option");}
                case 25->{if(conversation(a,"HISTORY_A_ONLY")){require(mc().options.guiScale().get()==4,"GUI_SCALE_NOT_SAVED");require(((NativeWorkspaceScreen)mc().screen).smokeState().get("draft").equals("draft-A"),"SCALE_DRAFT_LOST");if(click("会话"))advance("compact-conversation-directory");}}
                case 26->{var selector=findSelector(root());if(selector!=null&&pointer(selector,true)&&pointer(selector,false))advance("dropdown-opened-by-pointer");}
                case 27->{var selector=findSelector(root());if(selector!=null&&selector.isOpen()){var option=find(selector.listView.getChildren().get(1),"selector#overlayButton");if(option!=null&&pointer(option,true)){require(selector.isOpen(),"DROPDOWN_CLOSED_ON_PRESS");advance("dropdown-stays-open-until-release");}}}
                case 28->{var selector=findSelector(root());if(selector!=null&&selector.isOpen()){var option=find(selector.listView.getChildren().get(1),"selector#overlayButton");if(option!=null&&pointer(option,false)){require(!selector.isOpen()&&selector.getValue().toString().equals("已归档"),"DROPDOWN_DID_NOT_SELECT");advance("dropdown-released-selection");}}}
                case 29->{net.minecraft.client.Screenshot.takeScreenshot(mc().getMainRenderTarget(),image->{try(image){image.writeToFile(mc().gameDirectory.toPath().resolve("persistent-skill-smoke/gui-scale-four.png"));}catch(Exception e){result.completeExceptionally(e);}});NativeInventoryPanel.open((NativeWorkspaceScreen)mc().screen,agent.toString());advance("open-native-advanced-inputs");}
                case 30->{if(slotClick(33,0,org.lwjgl.glfw.GLFW.GLFW_MOD_SHIFT))advance("native-shift-click");}
                case 31->readInventory(s->slot(s,"player",8).get("count").getAsInt()==16&&slot(s,"agent",1).get("empty").getAsBoolean(),"shift-transfer-confirmed");
                case 32->{if(mc().screen instanceof AgentInventoryScreen screen){var mouse=slotEvent(screen,32);org.lwjgl.glfw.GLFW.glfwSetCursorPos(mc().getWindow().handle(),mouse.x()*mc().getWindow().getGuiScale(),mouse.y()*mc().getWindow().getGuiScale());pressedAt=ticks;advance("hover-native-hotbar-slot");}}
                case 33->{if(mc().screen instanceof AgentInventoryScreen screen&&mc().isWindowActive()&&ticks-pressedAt>=3&&screen.smokeHoveredSlot()==32){screen.keyPressed(new net.minecraft.client.input.KeyEvent(org.lwjgl.glfw.GLFW.GLFW_KEY_9,0,0));advance("native-number-key-swap");}}
                case 34->readInventory(s->slot(s,"agent",0).get("item").getAsString().equals("minecraft:diamond")&&slot(s,"player",8).get("item").getAsString().equals("minecraft:bread"),"number-key-swap-confirmed");
                case 35->{if(slotClick(32,1,0))advance("native-right-click-half-stack");}
                case 36->{if(mc().screen instanceof AgentInventoryScreen screen){require(screen.getMenu().getCarried().getCount()==8,"NATIVE_SPLIT_COUNT");screen.mouseClicked(slotEvent(screen,34),false);screen.mouseDragged(slotEvent(screen,34),0,0);screen.mouseDragged(slotEvent(screen,35),18,0);screen.mouseReleased(slotEvent(screen,35));advance("native-drag-distribute");}}
                case 37->readInventory(s->slot(s,"agent",0).get("count").getAsInt()==8&&slot(s,"agent",2).get("count").getAsInt()==4&&slot(s,"agent",3).get("count").getAsInt()==4,"drag-distribution-confirmed");
                case 38->{if(mc().screen instanceof AgentInventoryScreen screen){require(screen.getMenu().getCarried().isEmpty(),"DRAG_LEFT_UNEXPECTED_CURSOR");net.minecraft.client.Screenshot.takeScreenshot(mc().getMainRenderTarget(),image->{try(image){image.writeToFile(mc().gameDirectory.toPath().resolve("persistent-skill-smoke/inventory-scale-four.png"));}catch(Exception e){result.completeExceptionally(e);}});mc().player.closeContainer();NativeWorkspaceScreen.openConversation(agent.toString(),"界面验收",a.toString());advance("return-to-input-under-refresh");}}
                case 39->{if(conversation(a,"HISTORY_A_ONLY")&&mc().screen instanceof NativeWorkspaceScreen screen){var editor=findElement(root(),"conversation-composer");if(editor!=null&&pointer(editor,true)&&pointer(editor,false)){editorAt=ticks;advance("focus-native-composer");}}}
                case 40->{if(ticks-editorAt<12)return;var screen=(NativeWorkspaceScreen)mc().screen;var editor=findElement(root(),"conversation-composer");require(screen.smokeInputState().get("editor").equals("conversation-composer"),"IME_EDITOR_FOCUS_MISSING");focusChanges=(Integer)screen.smokeInputState().get("focusChanges");editorX=editor.getPositionX();editorY=editor.getPositionY();editorAt=ticks;advance("start-stream-layout-stability-check");}
                case 41->{var screen=(NativeWorkspaceScreen)mc().screen;var editor=findElement(root(),"conversation-composer");require(screen.smokeInputState().get("focusChanges").equals(focusChanges)&&editor.getPositionX()==editorX&&editor.getPositionY()==editorY,"COMPOSER_FOCUS_OR_LAYOUT_FLICKER");NativeWorkspaceScreen.push("conversationChanged",new com.google.gson.JsonObject());if(ticks-editorAt>=45)advance("stable-editor-across-polling");}
                case 42->{var screen=(NativeWorkspaceScreen)mc().screen;var ime=screen.smokeIme();checked.add("preedit-kept-separate-and-committed-once");require(ime.get("status").equals("PASS"),"IME_CALLBACK_FAILED");AgentProfileScreen.open(agent,"界面验收");advance("open-profile-scale-four");}
                case 43->{if(mc().screen instanceof AgentProfileScreen&&click("对话"))advance("profile-scale-four-chat");}
                case 44->{if(mc().screen instanceof AgentProfileScreen&&click("背包"))advance("profile-scale-four-inventory");}
                case 45->{if(mc().screen instanceof AgentInventoryScreen){mc().player.closeContainer();mc().setScreen(new net.minecraft.client.gui.screens.ChatScreen("/give ",false));advance("open-native-command-completion");}}
                case 46->{var names=mc().getConnection().getSuggestionsProvider().getOnlinePlayerNames();if(names.contains(com.mojang.brigadier.arguments.StringArgumentType.escapeIfRequired("持续技能搭档"))){checked.add("online-ai-alias-in-native-tab-completion");NativeWorkspaceScreen.openForAgent(agent.toString(),"界面验收");advance("open-building-plan-workspace");}}
                case 47->{if(click("建筑")||click("建筑计划"))advance("open-visible-building-plans");}
                case 48->{if(click("building-open-example"))advance("open-real-component-plan");}
                case 49->{var apply=find(root(),"building-action-apply");if(apply!=null&&apply.isActive()){if(!uiApplySubmitted&&click("building-action-apply")){uiApplySubmitted=true;checked.add("start-construction-through-visible-control");}return;}var button=find(root(),"building-action-undo");if(button!=null&&button.isActive()){pending=true;mc().getSingleplayerServer().submit(()->{buildingBefore=mc().getSingleplayerServer().overworld().getBlockState(new net.minecraft.core.BlockPos(12,101,12));require(!buildingBefore.isAir(),"BUILDING_UI_FIXTURE_NOT_BUILT_AT_EXPECTED_LOCATION");return true;}).whenComplete((v,e)->mc().execute(()->{pending=false;if(e!=null)result.completeExceptionally(e);else advance("capture-built-state-before-ui-undo");}));}}
                case 50->{if(click("building-action-undo"))advance("undo-through-visible-building-control");}
                case 51->buildingState("UNDONE",true,"ui-undo-verified-in-world");
                case 52->{if(click("building-action-redo"))advance("redo-through-visible-building-control");}
                case 53->buildingState("UNVERIFIED",false,"ui-redo-restored-original-block");
                case 54->{if(click("building-verify"))advance("verify-through-visible-building-control");}
                case 55->{pending=true;WorkspacePanels.request("building.read",Map.of("kind","inspect","agentId",agent.toString(),"id","example","offset","0")).whenComplete((receipt,error)->{pending=false;if(error!=null){result.completeExceptionally(error);return;}if(WorkspacePanels.state(receipt).get("status").getAsString().equals("VERIFIED")){checked.add("ui-building-exact-revision-verified");result.complete(Map.of("status","PASS","checks",List.copyOf(checked),"modelCalls",0));}});}

                case 60->{if(click("建筑计划")||click("建筑"))advance("geometry-plan-workspace");}
                case 61->{if(click("geometry-apply-"+geometryPlan))advance("apply-real-geometry-through-visible-control");}
                case 62->{pending=true;WorkspacePanels.request("building.read",Map.of("kind","geometryInspect","agentId",agent.toString(),"planId",geometryPlan.toString(),"offset","0")).whenComplete((receipt,error)->{
                    if(error!=null){pending=false;result.completeExceptionally(error);return;}var data=WorkspacePanels.state(receipt);String state=data.get("status").getAsString();if(Set.of("REJECTED","PARTIAL").contains(state)){pending=false;result.completeExceptionally(new IllegalStateException("GEOMETRY_UI_EFFECT_FAILED_"+data));return;}if(!state.equals("APPLIED")){pending=false;return;}
                    mc().getSingleplayerServer().submit(()->{require(mc().getSingleplayerServer().overworld().getBlockState(new net.minecraft.core.BlockPos(20,106,20)).is(net.minecraft.world.level.block.Blocks.STONE),"GEOMETRY_UI_NO_NATIVE_WORLD_CHANGE");return true;}).whenComplete((v,e)->mc().execute(()->{pending=false;if(e!=null)result.completeExceptionally(e);else{checked.add("geometry-ui-native-block-readback");result.complete(Map.of("status","PASS","checks",List.copyOf(checked),"modelCalls",0));}}));
                });}

            }
        }catch(Throwable error){result.completeExceptionally(error);}
    }
    private static void buildingState(String expected,boolean air,String label){
        pending=true;WorkspacePanels.request("building.read",Map.of("kind","inspect","agentId",agent.toString(),"id","example","offset","0")).whenComplete((receipt,error)->{
            if(error!=null){pending=false;result.completeExceptionally(error);return;}var state=WorkspacePanels.state(receipt);if(!state.get("status").getAsString().equals(expected)){pending=false;return;}
            mc().getSingleplayerServer().submit(()->{var actual=mc().getSingleplayerServer().overworld().getBlockState(new net.minecraft.core.BlockPos(12,101,12));require(air?actual.isAir():actual.equals(buildingBefore),"UI_BUILDING_WORLD_EFFECT_MISSING_"+expected);return true;}).whenComplete((v,e)->mc().execute(()->{pending=false;if(e!=null)result.completeExceptionally(e);else advance(label);}));
        });
    }
    private NativeFunctionalSmokeClient(){}
}
