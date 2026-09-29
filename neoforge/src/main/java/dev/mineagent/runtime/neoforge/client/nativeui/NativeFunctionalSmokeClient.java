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
    private static CompletableFuture<Map<String,Object>> result;private static UUID agent,a,b;
    private static int stage,ticks,pressedAt;private static boolean pending,chatRevealed;private static final List<String> checked=new ArrayList<>();
    public static CompletableFuture<Map<String,Object>> run(UUID ai,UUID first,UUID second){
        if(!Boolean.getBoolean("mineagent.skillSmoke"))throw new IllegalStateException("SMOKE_DISABLED");
        agent=ai;a=first;b=second;stage=ticks=pressedAt=0;pending=chatRevealed=false;checked.clear();return result=new CompletableFuture<>();
    }
    private static Minecraft mc(){return Minecraft.getInstance();}
    private static UIElement root(){return mc().screen instanceof NativeWorkspaceScreen s?s.smokeRoot():mc().screen instanceof AgentProfileScreen s?s.smokeRoot():null;}
    private static Button find(UIElement element,String value){if(element==null)return null;if(element instanceof Button button&&(value.equals(button.getId())||value.equals(button.text.getText().getString())))return button;for(var child:element.getChildren()){var found=find(child,value);if(found!=null)return found;}return null;}
    private static boolean click(String value){return gesture(value,true)&&gesture(value,false);}
    private static boolean gesture(String value,boolean down){var button=find(root(),value);if(button==null||button.getSizeWidth()<=0||button.getSizeHeight()<=0)return false;var screen=(com.lowdragmc.lowdraglib2.gui.holder.ModularUIScreen)mc().screen;float x=button.getPositionX()+button.getSizeWidth()/2,y=button.getPositionY()+button.getSizeHeight()/2;require(x>=0&&x<mc().getWindow().getGuiScaledWidth()&&y>=0&&y<mc().getWindow().getGuiScaledHeight(),"UI_BUTTON_OUTSIDE_SCREEN_"+value);screen.modularUI.refreshHoveredElementAtScreen(x,y);var widget=com.lowdragmc.lowdraglib2.gui.ui.ModularUIClientAccess.getWidget(screen.modularUI);var event=new net.minecraft.client.input.MouseButtonEvent(x,y,new net.minecraft.client.input.MouseButtonInfo(0,0));if(down)widget.mouseClicked(event,false);else widget.mouseReleased(event);return true;}
    private static boolean conversation(UUID id,String marker){return mc().screen instanceof NativeWorkspaceScreen screen&&screen.smokeState().get("conversation").equals(id.toString())&&screen.smokeHistory().equals(marker);}
    private static void require(boolean value,String error){if(!value)throw new IllegalStateException(error);}
    private static void advance(String name){checked.add(name);stage++;}
    private static void readInventory(java.util.function.Predicate<com.google.gson.JsonObject> predicate,String label){pending=true;WorkspacePanels.request("agent.inventoryRead",Map.of("agentId",agent.toString(),"offset","0")).whenComplete((r,e)->{pending=false;if(e!=null){result.completeExceptionally(e);return;}if(predicate.test(WorkspacePanels.state(r)))advance(label);});}
    private static com.google.gson.JsonObject slot(com.google.gson.JsonObject state,String side,int id){for(var v:state.getAsJsonArray(side+"Slots"))if(v.getAsJsonObject().get("slot").getAsInt()==id)return v.getAsJsonObject();throw new IllegalArgumentException();}
    @SubscribeEvent public static void tick(ClientTickEvent.Post event){
        if(result==null||result.isDone())return;try{
            if(++ticks>700)throw new IllegalStateException("FUNCTIONAL_UI_TIMEOUT_STAGE_"+stage+" checked="+checked);
            if(pending||ticks%6!=0)return;if(!mc().isWindowActive()){org.lwjgl.glfw.GLFW.glfwFocusWindow(mc().getWindow().handle());return;}
            switch(stage){
                case 0->{NativeWorkspaceScreen.openConversation(agent.toString(),"界面验收",a.toString());advance("open-first-conversation");}
                case 1->{if(conversation(a,"HISTORY_A_ONLY")){((NativeWorkspaceScreen)mc().screen).smokeDraft("draft-A");if(pressedAt==0){if(gesture("conversation-"+b,true)){pressedAt=ticks;NativeWorkspaceScreen.push("conversationChanged",new com.google.gson.JsonObject());}return;}if(ticks-pressedAt>=30&&gesture("conversation-"+b,false))advance("release-second-row-after-refresh");}}
                case 2->{if(conversation(b,"HISTORY_B_ONLY")){require(((NativeWorkspaceScreen)mc().screen).smokeState().get("draft").equals(""),"DRAFT_LEAKED_TO_B");((NativeWorkspaceScreen)mc().screen).smokeDraft("draft-B");if(click("conversation-"+a))advance("click-first-row");}}
                case 3->{if(conversation(a,"HISTORY_A_ONLY")){require(((NativeWorkspaceScreen)mc().screen).smokeState().get("draft").equals("draft-A"),"DRAFT_A_LOST");if(click("conversation-"+b)&&click("conversation-"+a))advance("rapid-switch-with-pending-reads");}}
                case 4->{if(conversation(a,"HISTORY_A_ONLY")&&click("文件"))advance("open-files");}
                case 5->{if(!chatRevealed){chatRevealed=click("对话");return;}if(click("conversation-"+b))advance("return-chat-and-select");}
                case 6->{if(conversation(b,"HISTORY_B_ONLY")){require(((NativeWorkspaceScreen)mc().screen).smokeState().get("draft").equals("draft-B"),"DRAFT_B_LOST");AgentProfileScreen.open(agent,"界面验收");advance("open-profile");}}
                case 7->{if(mc().screen instanceof AgentProfileScreen&&click("对话"))advance("profile-conversations");}
                case 8->{if(click("profile-conversation-"+a))advance("profile-select-existing");}
                case 9->{if(conversation(a,"HISTORY_A_ONLY")){AgentProfileScreen.open(agent,"界面验收");advance("profile-open-inventory");}}
                case 10->{if(click("背包"))advance("inventory-tab");}
                case 11->{if(click("inventory-player-1"))advance("select-player-diamonds");}
                case 12->{if(click("inventory-agent-1"))advance("move-to-agent-slot");}
                case 13->readInventory(s->slot(s,"agent",1).get("count").getAsInt()==16&&slot(s,"player",1).get("empty").getAsBoolean(),"server-confirmed-transfer");
                case 14->{if(click("inventory-player-0"))advance("select-helmet");}
                case 15->{if(click("背包 / 装备"))advance("switch-equipment-view");}
                case 16->{if(click("inventory-agent-39"))advance("equip-real-helmet");}
                case 17->readInventory(s->slot(s,"agent",39).get("item").getAsString().equals("minecraft:diamond_helmet")&&slot(s,"player",0).get("empty").getAsBoolean(),"server-confirmed-equipment");
                case 18->{net.minecraft.client.Screenshot.takeScreenshot(mc().getMainRenderTarget(),image->{try(image){image.writeToFile(mc().gameDirectory.toPath().resolve("persistent-skill-smoke/inventory-live.png"));}catch(Exception e){result.completeExceptionally(e);}});NativeWorkspaceScreen.openForAgent(agent.toString(),"界面验收");advance("f2-return-preserves-conversation");}
                case 19->{if(conversation(a,"HISTORY_A_ONLY")&&click("AI 玩家"))advance("f2-ai-manager");}
                case 20->{if(click("背包"))advance("f2-open-shared-inventory");}
                case 21->{if(find(root(),"inventory-agent-1")!=null){result.complete(Map.of("status","PASS","checks",List.copyOf(checked),"modelCalls",0));}}
            }
        }catch(Throwable error){result.completeExceptionally(error);}
    }
    private NativeFunctionalSmokeClient(){}
}
