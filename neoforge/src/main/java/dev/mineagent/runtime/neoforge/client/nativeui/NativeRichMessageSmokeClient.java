package dev.mineagent.runtime.neoforge.client.nativeui;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.*;
import dev.mineagent.runtime.neoforge.MineAgentRuntimeServices;
import dev.mineagent.runtime.neoforge.ui.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.*;
import net.minecraft.network.chat.*;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.*;
import org.lwjgl.glfw.GLFW;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Opt-in isolated real F2/RPC checks. No configured provider or model request is needed. */
@EventBusSubscriber(modid="mineagent_runtime",value=Dist.CLIENT)
public final class NativeRichMessageSmokeClient {
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON=new com.fasterxml.jackson.databind.ObjectMapper();
    private static final String COPY="可复制内容：石头×64", SUGGEST="/time query daytime", CHOICE="选择石屋，仅确认文本，不执行命令";
    private static UUID agent,conversation,other,message,nativeMessage;
    private static int ticks,stage,wait; private static boolean requested,accepted,busy,done;
    private static String clipboard="",nativeCommand="";
    private static final List<String> passed=new ArrayList<>();
    private static Minecraft mc(){return Minecraft.getInstance();}
    private static Path output() throws Exception{return Files.createDirectories(mc().gameDirectory.toPath().resolve("native-rich-message-smoke"));}
    private static void require(boolean value,String code){if(!value)throw new IllegalStateException(code);}
    @SubscribeEvent public static void message(ClientChatReceivedEvent.System event){
        if(!Boolean.getBoolean("mineagent.nativeRichMessageSmoke"))return;
        event.getMessage().visit((style,text)->{if(style.getClickEvent() instanceof ClickEvent.RunCommand click&&click.command().contains(" choice:"))nativeCommand=click.command();return Optional.empty();},Style.EMPTY);
    }
    private static com.fasterxml.jackson.databind.node.ObjectNode definition(){
        var data=JSON.createObjectNode().put("text","请选择下一步：确认、填入或复制。").put("color","#55FFFF");
        var buttons=data.putArray("buttons");
        buttons.addObject().put("label","建造石屋").put("action","confirm").put("value",CHOICE);
        buttons.addObject().put("label","查看时间（只填入）").put("action","suggest").put("value",SUGGEST);
        buttons.addObject().put("label","材料清单").put("action","copy").put("value",COPY);return data;
    }
    private static UIElement find(UIElement root,String id){if(id.equals(root.getId()))return root;for(var child:root.getChildren()){var found=find(child,id);if(found!=null)return found;}return null;}
    private static UIElement find(String id){return mc().screen instanceof NativeWorkspaceScreen screen?find(screen.getModularUI().ui.rootElement,id):null;}
    private static Button button(int index){return (Button)find("rich-"+message+"-"+index);}
    private static TextArea input(){return (TextArea)find("conversation-composer");}
    private static void click(int index){
        require(mc().isWindowActive(),"FOCUS_REQUIRED");var screen=(NativeWorkspaceScreen)mc().screen;var button=button(index);
        require(button!=null&&button.getSizeWidth()>0&&button.getSizeHeight()>0,"ACTION_NOT_LAID_OUT");
        float x=button.getPositionX()+button.getSizeWidth()/2,y=button.getPositionY()+button.getSizeHeight()/2;
        screen.getModularUI().refreshHoveredElementAtScreen(x,y);
        var hit=screen.getModularUI().getLastHoveredElement();boolean found=false;for(var n=hit;n!=null;n=n.getParent())if(n==button)found=true;require(found,"ACTION_NOT_HITTABLE");
        var event=new MouseButtonEvent(x,y,new MouseButtonInfo(0,0));screen.mouseClicked(event,false);screen.mouseReleased(event);
    }
    private static <T> void server(Callable<T> call,java.util.function.Consumer<T> result){busy=true;mc().getSingleplayerServer().submit(()->{try{return call.call();}catch(Exception error){throw new CompletionException(error);}}).whenComplete((value,error)->mc().execute(()->{busy=false;try{if(error!=null)throw new CompletionException(error);result.accept(value);}catch(Exception failure){fail(failure);}}));}
    private static void screenshot(String name) throws Exception{var path=output().resolve(name+".png");net.minecraft.client.Screenshot.takeScreenshot(mc().getMainRenderTarget(),image->{try(image){image.writeToFile(path);}catch(Exception error){fail(error);}});}
    @SubscribeEvent public static void tick(ClientTickEvent.Post event){
        if(!Boolean.getBoolean("mineagent.nativeRichMessageSmoke")||done)return;
        try{
            if(++ticks>2400)throw new IllegalStateException("RICH_MESSAGE_TIMEOUT_"+stage);if(mc().player==null||mc().level==null||busy)return;
            if(!requested){requested=true;clipboard=mc().keyboardHandler.getClipboard();net.neoforged.neoforge.client.network.ClientPacketDistributor.sendToServer(new dev.mineagent.runtime.neoforge.network.MineAgentPayloads.PanelRequest());return;}
            if(!accepted){var values=dev.mineagent.runtime.neoforge.network.PanelSnapshotInbox.snapshot().values();String fingerprint=values.getOrDefault("security.identityFingerprint","");if(fingerprint.isEmpty()||!dev.mineagent.runtime.neoforge.network.PanelSnapshotInbox.signatureValid())return;new dev.mineagent.runtime.client.trust.ServerTrustStore(mc().gameDirectory.toPath().resolve("config/mineagent-trusted-servers.properties")).confirm("local-integrated",fingerprint,Base64.getDecoder().decode(values.get("security.identityPublicKey")));mc().player.connection.sendCommand("ai accept");accepted=true;return;}
            if(++wait<25)return;wait=0;
            if(stage==0){var owner=mc().player.getUUID();server(()->{
                var s=mc().getSingleplayerServer();var p=s.getPlayerList().getPlayer(owner);s.getPlayerList().op(p.nameAndId());
                agent=MineAgentRuntimeServices.bodies(s).createPersistentAt("OptionsTest",owner,p.level(),p.position().add(2,0,0)).agentId();
                var runtime=ServerConversations.get(s);conversation=runtime.store().create(owner,agent,UUID.randomUUID(),"交互选项验收").conversationId();other=runtime.store().create(owner,agent,UUID.randomUUID(),"另一个会话").conversationId();
                return runtime.richMessage(p,agent,conversation,UUID.randomUUID(),definition());
            },value->{message=UUID.fromString(value.get("messageId").toString());NativeWorkspaceScreen.openConversation(agent.toString(),"OptionsTest",conversation.toString());GLFW.glfwFocusWindow(mc().getWindow().handle());stage=1;});return;}
            if(stage==1){if(button(2)==null)return;require(!((TextElement)find("workspace-status")).getText().getString().contains("VIEW_NOT_RENDERED"),"INITIAL_CONVERSATION_BEFORE_READY");require(button(0).isActive(),"CONFIRM_DISABLED");input().setValue(new String[]{"已有草稿"},true);screenshot("01-options");click(2);stage=2;return;}
            if(stage==2){require(COPY.equals(mc().keyboardHandler.getClipboard()),"COPY_FAILED");passed.add("copy-through-native-rpc");click(1);stage=3;return;}
            if(stage==3){require(String.join("\n",input().getValue()).equals("已有草稿\n"+SUGGEST),"SUGGEST_DRAFT_LOST");passed.add("suggest-keeps-draft-without-send");var owner=mc().player.getUUID();server(()->ServerConversations.get(mc().getSingleplayerServer()).store().get(owner,agent,conversation).messageCount(),count->{require(count==1,"LOCAL_ACTION_SENT_MESSAGE");click(0);stage=4;});return;}
            if(stage==4){var owner=mc().player.getUUID();server(()->{
                var runtime=ServerConversations.get(mc().getSingleplayerServer());var saved=runtime.store().richMessage(owner,agent,conversation,message);require(saved.state().equals("ACCEPTED"),"CHOICE_NOT_ACCEPTED");
                var users=runtime.store().messages(owner,agent,conversation,0,20).messages().stream().filter(m->m.role().equals("USER")).toList();require(users.size()==1,"CHOICE_USER_COUNT");var user=users.getFirst();require(runtime.store().chunk(owner,agent,conversation,user.messageId(),user.revision(),0,4096).text().equals("玩家点击选择："+CHOICE),"CHOICE_VALUE_CHANGED");return true;
            },value->{require(!button(0).isActive(),"USED_BUTTON_ACTIVE");require(String.join("\n",input().getValue()).equals("已有草稿\n"+SUGGEST),"CONFIRM_CLEARED_DRAFT");passed.add("confirm-submits-one-source-conversation-message");busy=true;WorkspacePanels.request("conversation.write",Map.of("kind","richConfirm","agentId",agent.toString(),"conversationId",conversation.toString(),"messageId",message.toString(),"index","0")).whenComplete((receipt,error)->{busy=false;try{if(error!=null)throw new CompletionException(error);require(WorkspacePanels.state(receipt).get("duplicate").getAsBoolean(),"CHOICE_REPLAY");passed.add("duplicate-click-no-resend");((NativeWorkspaceScreen)mc().screen).onClose();NativeWorkspaceScreen.openConversation(agent.toString(),"OptionsTest",conversation.toString());stage=5;}catch(Exception failure){fail(failure);}});});return;}
            if(stage==5){if(button(0)==null)return;require(!button(0).isActive()&&button(1).isActive()&&button(2).isActive(),"REOPEN_ACTION_STATE");screenshot("02-choice-persisted");passed.add("reopen-restores-selected-state");NativeWorkspaceScreen.openConversation(agent.toString(),"OptionsTest",other.toString());stage=6;return;}
            if(stage==6){var owner=mc().player.getUUID();server(()->ServerConversations.get(mc().getSingleplayerServer()).richMessage(mc().getSingleplayerServer().getPlayerList().getPlayer(owner),agent,conversation,UUID.randomUUID(),definition()),value->{nativeMessage=UUID.fromString(value.get("messageId").toString());stage=7;});return;}
            if(stage==7){if(!nativeCommand.contains(nativeMessage.toString()))return;mc().player.connection.sendCommand(nativeCommand.substring(1));stage=8;return;}
            if(stage==8){var owner=mc().player.getUUID();server(()->{var store=ServerConversations.get(mc().getSingleplayerServer()).store();require(store.get(owner,agent,other).messageCount()==0,"NATIVE_CHOICE_WRONG_CONVERSATION");require(store.richMessage(owner,agent,conversation,nativeMessage).state().equals("ACCEPTED"),"NATIVE_CHOICE_NOT_ACCEPTED");long users=store.messages(owner,agent,conversation,0,20).messages().stream().filter(m->m.role().equals("USER")).count();require(users==2,"DUPLICATE_OR_LOST_CHOICE");return true;},value->{passed.add("native-confirm-keeps-origin-after-f2-switch");stage=9;});return;}
            if(stage==9){Files.writeString(output().resolve("result.json"),JSON.writeValueAsString(Map.of("status","PASS","modelCalls",0,"checks",passed)));done=true;restoreClipboard();mc().stop();}
        }catch(Exception error){fail(error);}
    }
    private static void restoreClipboard(){if(COPY.equals(mc().keyboardHandler.getClipboard()))mc().keyboardHandler.setClipboard(clipboard);}
    private static void fail(Throwable error){if(done)return;done=true;try{Files.writeString(output().resolve("failure.json"),JSON.writeValueAsString(Map.of("stage",stage,"error",error.toString(),"checks",passed)));}catch(Exception ignored){}restoreClipboard();mc().stop();}
    private NativeRichMessageSmokeClient(){}
}
