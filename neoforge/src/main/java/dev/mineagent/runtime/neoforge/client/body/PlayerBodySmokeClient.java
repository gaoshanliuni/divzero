package dev.mineagent.runtime.neoforge.client.body;
import com.google.gson.Gson;
import dev.mineagent.runtime.neoforge.task.PlayerBodySmokeServer;
import dev.mineagent.runtime.neoforge.network.PlayerBodyPayloads;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.input.KeyEvent;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.*;
import org.lwjgl.glfw.GLFW;
import java.nio.file.*;
import java.util.*;
/** Native local consent and real movement/use/mining; interruptions use real game callbacks, not OS input. */
@EventBusSubscriber(modid="mineagent_runtime",value=Dist.CLIENT)
public final class PlayerBodySmokeClient {
    private static int ticks,phase,scenario=1,after;private static boolean finished,waitingShot;private static String review="";private static PlayerBodyPayloads.Signal startSignal;private static Object wire;private static final List<Map<String,Object>> rows=new ArrayList<>();
    private static Path root(){return Minecraft.getInstance().gameDirectory.toPath().resolve("player-body-smoke");}
    public static void signal(PlayerBodyPayloads.Signal signal,Object source){if(Boolean.getBoolean("mineagent.playerBodySmoke")&&signal.action().equals("START")){startSignal=signal;wire=source;}}
    @SubscribeEvent public static void chat(ClientChatReceivedEvent.System event){if(!Boolean.getBoolean("mineagent.playerBodySmoke"))return;var click=event.getMessage().getStyle().getClickEvent();if(click instanceof net.minecraft.network.chat.ClickEvent.RunCommand run&&run.command().startsWith("/ai body review "))review=run.command();}
    private static void require(boolean v,String reason){if(!v)throw new IllegalStateException(reason);}
    private static void key(int key)throws Exception{var mc=Minecraft.getInstance();var m=net.minecraft.client.KeyboardHandler.class.getDeclaredMethod("keyPress",long.class,int.class,KeyEvent.class);m.setAccessible(true);m.invoke(mc.keyboardHandler,mc.getWindow().handle(),GLFW.GLFW_PRESS,new KeyEvent(key,GLFW.glfwGetKeyScancode(key),0));m.invoke(mc.keyboardHandler,mc.getWindow().handle(),GLFW.GLFW_RELEASE,new KeyEvent(key,GLFW.glfwGetKeyScancode(key),0));}
    private static void otherInputDoesNotExit()throws Exception{
        var mc=Minecraft.getInstance();int slot=mc.player.getInventory().getSelectedSlot();double x=mc.mouseHandler.xpos(),y=mc.mouseHandler.ypos();
        for(int key:new int[]{GLFW.GLFW_KEY_W,GLFW.GLFW_KEY_E,GLFW.GLFW_KEY_F2,GLFW.GLFW_KEY_F10})key(key);
        var motion=net.minecraft.client.MouseHandler.class.getDeclaredMethod("onMove",long.class,double.class,double.class);motion.setAccessible(true);motion.invoke(mc.mouseHandler,mc.getWindow().handle(),x+35,y+20);
        var scroll=net.minecraft.client.MouseHandler.class.getDeclaredMethod("onScroll",long.class,double.class,double.class);scroll.setAccessible(true);scroll.invoke(mc.mouseHandler,mc.getWindow().handle(),0d,1d);
        var button=net.minecraft.client.MouseHandler.class.getDeclaredMethod("onButton",long.class,net.minecraft.client.input.MouseButtonInfo.class,int.class);button.setAccessible(true);for(int n:new int[]{0,1}){button.invoke(mc.mouseHandler,mc.getWindow().handle(),new net.minecraft.client.input.MouseButtonInfo(n,0),GLFW.GLFW_PRESS);button.invoke(mc.mouseHandler,mc.getWindow().handle(),new net.minecraft.client.input.MouseButtonInfo(n,0),GLFW.GLFW_RELEASE);}
        require(PlayerBodyControlClient.phase().equals("RUNNING")&&mc.screen==null&&mc.options.keyUp.isDown()&&mc.player.getInventory().getSelectedSlot()==slot&&mc.mouseHandler.xpos()==x&&mc.mouseHandler.ypos()==y,"BODY_NON_ESCAPE_INPUT_INTERRUPTED");
    }
    private static void focus(boolean value)throws Exception{var w=Minecraft.getInstance().getWindow();var m=w.getClass().getDeclaredMethod("onFocus",long.class,boolean.class);m.setAccessible(true);m.invoke(w,w.handle(),value);}
    private static void snap(String name){waitingShot=true;net.minecraft.client.Screenshot.takeScreenshot(Minecraft.getInstance().getMainRenderTarget(),img->{try(img){img.writeToFile(root().resolve(name+".png"));}catch(Exception e){PlayerBodySmokeServer.failure=e.toString();}finally{waitingShot=false;}});}
    @SubscribeEvent public static void tick(ClientTickEvent.Post event)throws Exception{
        if(!Boolean.getBoolean("mineagent.playerBodySmoke")||finished)return;var mc=Minecraft.getInstance();ticks++;
        try{Files.createDirectories(root());if(ticks>4000||!PlayerBodySmokeServer.failure.isEmpty())throw new IllegalStateException("BODY_TIMEOUT_"+scenario+"_"+phase+" "+PlayerBodySmokeServer.failure);if(ticks%40==0)Files.writeString(root().resolve("progress.json"),new Gson().toJson(Map.of("scenario",scenario,"phase",phase,"ticks",ticks,"client",PlayerBodyControlClient.observe(),"serverVerified",PlayerBodySmokeServer.verified)));
            if(phase==30){require(!PlayerBodyControlClient.active()&&Boolean.TRUE.equals(PlayerBodyControlClient.observe().get("allControlKeysReleased")),"BODY_DISCONNECT_KEYS");if(mc.player!=null||ticks<after)return;Files.writeString(root().resolve("client.json"),new Gson().toJson(rows));Files.writeString(root().resolve("result.json"),new Gson().toJson(Map.of("status","REAL_PLAYER_BODY_CONTROL_VERIFIED","nativeConsent",true,"realMovementLookJumpUseMineHotbar",true,"escapeFocusScreenDeathDisconnectRelease",true,"nonEscapePhysicalInputBlocked",true,"duplicateGrantIgnored",true,"paidCalls",0,"osInputInjected",false)));finished=true;dev.mineagent.runtime.neoforge.MineAgentRuntimeMod.LOGGER.info("MINEAGENT_PLAYER_BODY_OK");mc.stop();return;}
            if(mc.player==null)return;
            if(phase==20){if(scenario==5){if(!mc.player.isAlive()){mc.player.respawn();mc.setScreen(null);return;}}scenario++;PlayerBodySmokeServer.requested=scenario;review="";startSignal=null;wire=null;phase=0;after=ticks+30;return;}
            if(phase==0){if(PlayerBodySmokeServer.prepared!=scenario||ticks<after||mc.player.position().distanceToSqr(new net.minecraft.world.phys.Vec3(400.5,181,400.5))>.15||!mc.player.isAlive())return;GLFW.glfwFocusWindow(mc.getWindow().handle());mc.setScreen(null);if(!mc.isWindowActive())return;mc.player.connection.sendChat("@身体助手 接管 BODY_CONTROL_FIXTURE_"+scenario);phase=1;}
            if(phase==1&&!review.isEmpty()){require(!PlayerBodyControlClient.active(),"BODY_STARTED_WITHOUT_CONSENT");mc.player.connection.sendCommand(review.substring(1));phase=2;after=ticks+20;}
            if(phase==2&&mc.screen instanceof PlayerBodyReviewScreen screen&&ticks>=after&&!waitingShot){if(scenario==1){snap("local-consent");}require(!PlayerBodyControlClient.active(),"BODY_REVIEW_ALREADY_RUNNING");var button=screen.children().stream().filter(Button.class::isInstance).map(Button.class::cast).filter(b->b.getMessage().getString().equals("确认并开始接管本人")).findFirst().orElseThrow();require(button.active,"BODY_CONSENT_NOT_ENABLED");button.onPress(new KeyEvent(GLFW.GLFW_KEY_ENTER,0,0));phase=3;after=ticks+180;}
            if(phase==3){if(PlayerBodyControlClient.phase().equals("RUNNING")){phase=4;after=ticks+12;}else if(ticks>after)throw new IllegalStateException("BODY_NOT_RUNNING:"+PlayerBodyControlClient.observe());}
            if(phase==4){
                if(scenario==1){if(PlayerBodyControlClient.active())return;require(PlayerBodyControlClient.observe().get("reason").equals("INPUT_SEQUENCE_FINISHED"),"BODY_POSITIVE_INTERRUPTED:"+PlayerBodyControlClient.observe());phase=5;}
                else if(ticks>=after){require(PlayerBodyControlClient.active(),"BODY_STOPPED_BEFORE_INTERRUPT:"+PlayerBodyControlClient.observe());
                    switch(scenario){case 2->{otherInputDoesNotExit();key(GLFW.GLFW_KEY_ESCAPE);}case 3->{focus(false);require(!PlayerBodyControlClient.active(),"BODY_FOCUS_NOT_IMMEDIATE");focus(true);}case 4->mc.setScreen(new net.minecraft.client.gui.screens.inventory.InventoryScreen(mc.player));case 5->PlayerBodySmokeServer.killRequested=true;case 6->{mc.disconnectFromWorld(net.minecraft.network.chat.Component.literal("Body control disconnect fixture"));rows.add(Map.of("scenario",6,"client",PlayerBodyControlClient.observe()));phase=30;after=ticks+30;return;}}
                    phase=5;
                }
            }
            if(phase==5&&!PlayerBodyControlClient.active()&&PlayerBodySmokeServer.verified>=scenario){require(Boolean.TRUE.equals(PlayerBodyControlClient.observe().get("allControlKeysReleased")),"BODY_HELD_KEYS_AFTER_STOP");if(scenario==2)require(PlayerBodyControlClient.observe().get("reason").equals("USER_ESCAPE"),"BODY_ESCAPE_NOT_ROUTED");if(startSignal!=null){PlayerBodyControlClient.signal(startSignal,wire);require(!PlayerBodyControlClient.active(),"BODY_DUPLICATE_GRANT_REPLAYED");}rows.add(Map.of("scenario",scenario,"client",PlayerBodyControlClient.observe()));Files.writeString(root().resolve("client-partial.json"),new Gson().toJson(rows));if(scenario==1)snap("after-real-actions");if(scenario!=5)mc.setScreen(null);phase=20;}
        }catch(Exception failure){finished=true;Files.writeString(root().resolve("failure.json"),new Gson().toJson(Map.of("scenario",scenario,"phase",phase,"error",failure.toString(),"client",PlayerBodyControlClient.observe())));PlayerBodyControlClient.stop("FIXTURE_FAILED");mc.stop();}
    }
}
