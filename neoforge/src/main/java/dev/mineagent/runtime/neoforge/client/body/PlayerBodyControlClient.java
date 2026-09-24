package dev.mineagent.runtime.neoforge.client.body;

import dev.mineagent.runtime.core.task.*;
import dev.mineagent.runtime.neoforge.network.PlayerBodyPayloads;

import dev.mineagent.runtime.neoforge.mixin.client.PlayerControlKeyAccess;
import net.minecraft.client.*;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.*;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import org.lwjgl.glfw.GLFW;
import java.util.*;

/** Drives the actual LocalPlayer through vanilla input mappings. Never moves the OS cursor or sends world mutations directly. */
@EventBusSubscriber(modid="mineagent_runtime",value=Dist.CLIENT)
public final class PlayerBodyControlClient {
    private static final UUID NONE=new UUID(0,0);
    private static Flight flight;private static String lastReason="NONE";private static int lastSteps,lastTicks;
    private static final class Flight {
        final PlayerBodyPayloads.Offer offer;final PlayerControlPlan plan;final PlayerControlLease lease;final PlayerControlSequence input;
        final Object wire,connection,level;final net.minecraft.client.player.LocalPlayer player;final String hash;
        int frames,heartbeat;boolean lastFrame;String priorAction="";
        Flight(PlayerBodyPayloads.Offer o,PlayerControlPlan p,String hash){var mc=Minecraft.getInstance();offer=o;plan=p;this.hash=hash;wire=mc.getConnection().getConnection();connection=mc.getConnection();level=mc.level;player=mc.player;lease=new PlayerControlLease(o.operation(),UUID.randomUUID(),now());input=new PlayerControlSequence(p);}
    }
    private PlayerBodyControlClient(){}
    private static long now(){return System.nanoTime()/1_000_000;}
    private static List<KeyMapping> keys(){var o=Minecraft.getInstance().options;return List.of(o.keyUp,o.keyDown,o.keyLeft,o.keyRight,o.keyJump,o.keyShift,o.keySprint,o.keyAttack,o.keyUse);}
    private static void down(KeyMapping key,boolean value){((PlayerControlKeyAccess)key).mineagent$bodyDown(value);}
    private static void click(KeyMapping key){((PlayerControlKeyAccess)key).mineagent$bodyClicks(1);}
    private static void releaseKeys(){for(var key:keys()){down(key,false);((PlayerControlKeyAccess)key).mineagent$bodyClicks(0);}}
    private static void cancelInteraction(Flight f){var mc=Minecraft.getInstance();if(mc.player==f.player&&mc.getConnection()==f.connection&&mc.gameMode!=null){mc.gameMode.stopDestroyBlock();if(f.player.isUsingItem())mc.gameMode.releaseUsingItem(f.player);f.player.setSprinting(false);}}
    private static boolean context(Flight f){var mc=Minecraft.getInstance();return mc.getConnection()==f.connection&&mc.player==f.player&&mc.level==f.level&&mc.level!=null&&f.offer.dimension().equals(mc.level.dimension().identifier().toString())&&mc.player.isAlive()&&!mc.player.isSpectator()&&!mc.player.isPassenger()&&mc.screen==null&&mc.getOverlay()==null&&!mc.isPaused()&&mc.isWindowActive()&&mc.mouseHandler.isMouseGrabbed();}
    public static boolean active(){return flight!=null||AutonomousBodyClient.active();}
    public static String phase(){return flight==null?"IDLE":flight.lease.phase().name();}
    public static Map<String,Object> observe(){var f=flight;return Map.of("phase",phase(),"reason",lastReason,"completedInputSteps",f==null?lastSteps:f.input.completedSteps(),"elapsedTicks",f==null?lastTicks:f.input.elapsedTicks(),"allControlKeysReleased",keys().stream().allMatch(k->!((PlayerControlKeyAccess)k).mineagent$bodyDown()&&((PlayerControlKeyAccess)k).mineagent$bodyClicks()==0));}
    private static void send(Flight f,String action,String reason){var mc=Minecraft.getInstance();if(mc.getConnection()==f.connection)ClientPacketDistributor.sendToServer(new PlayerBodyPayloads.Decision(f.offer.operation(),f.lease.consent(),action,f.input.completedSteps(),reason));}
    public static void decline(PlayerBodyPayloads.Offer offer,String reason){var mc=Minecraft.getInstance();if(mc.getConnection()!=null&&mc.player!=null&&mc.player.getUUID().equals(offer.player()))ClientPacketDistributor.sendToServer(new PlayerBodyPayloads.Decision(offer.operation(),NONE,"STOP",0,reason));}
    public static void offer(PlayerBodyPayloads.Offer offer,Object wire){var mc=Minecraft.getInstance();if(mc.getConnection()==null||mc.getConnection().getConnection()!=wire||mc.player==null||!mc.player.getUUID().equals(offer.player())||mc.level==null||!mc.level.dimension().identifier().toString().equals(offer.dimension()))return;
        if(flight!=null||mc.getOverlay()!=null||mc.screen!=null&&!(mc.screen instanceof net.minecraft.client.gui.screens.ChatScreen)&&!(mc.screen instanceof PlayerBodyReviewScreen)){decline(offer,"SCREEN_BUSY");return;}
        try{var plan=PlayerControlPlan.parse(offer.plan());if(mc.screen instanceof PlayerBodyReviewScreen old&&old.operation().equals(offer.operation()))return;mc.setScreen(new PlayerBodyReviewScreen(offer,plan,wire));}
        catch(Exception malformed){decline(offer,"INVALID_PLAN");}
    }
    static void approve(PlayerBodyReviewScreen screen,PlayerBodyPayloads.Offer offer,PlayerControlPlan plan,Object wire){
        var mc=Minecraft.getInstance();if(flight!=null||mc.screen!=screen||!screen.localConsent()||mc.getConnection()==null||mc.getConnection().getConnection()!=wire||mc.level==null||mc.player==null||!mc.player.isAlive()||!mc.isWindowActive()||!mc.level.dimension().identifier().toString().equals(offer.dimension())){screen.reject(dev.mineagent.runtime.neoforge.client.language.ClientLanguage.t("当前客户端状态已变化，请取消并重新发起。"));return;}
        try{String hash=dev.mineagent.runtime.core.packages.RuntimePackageCanonicalizer.sha256(offer.plan().getBytes(java.nio.charset.StandardCharsets.UTF_8));var f=new Flight(offer,plan,hash);flight=f;lastReason="ARMING";screen.handedOff();mc.setScreen(null);KeyMapping.releaseAll();releaseKeys();
        send(f,"START","");}catch(Exception failure){stop("START_SEND_FAILED");screen.reject(dev.mineagent.runtime.neoforge.client.language.ClientLanguage.t("启动失败，未自动重试。"));}
    }
    public static void signal(PlayerBodyPayloads.Signal message,Object wire){var mc=Minecraft.getInstance();if(mc.getConnection()==null||mc.getConnection().getConnection()!=wire)return;
        var f=flight;if(message.action().equals("STOP")){if(f!=null&&f.offer.operation().equals(message.operation())&&f.lease.matches(message.operation(),message.consent()))stop(message.detail().matches("[A-Z0-9_]{1,64}")?message.detail():"SERVER_STOP");else if(mc.screen instanceof PlayerBodyReviewScreen screen&&screen.operation().equals(message.operation()))screen.serverStopped();return;}
        if(f==null||f.wire!=wire||!context(f))return;
        if(message.action().equals("START")&&message.detail().equals(f.hash)){if(f.lease.grant(message.operation(),message.consent(),message.sequence(),now())){lastReason="RUNNING";}}else if(message.action().equals("LEASE"))f.lease.renew(message.operation(),message.consent(),message.sequence(),now());
    }
    public static void stop(String reason){var f=flight;if(f==null)return;flight=null;f.lease.stop();f.input.stop();releaseKeys();cancelInteraction(f);resetMouse();lastReason=reason;lastSteps=f.input.completedSteps();lastTicks=f.input.elapsedTicks();try{send(f,reason.equals("INPUT_SEQUENCE_FINISHED")?"FINISH":"STOP",reason);}catch(Exception ignored){}Minecraft.getInstance().gui.setOverlayMessage(Component.literal(dev.mineagent.runtime.neoforge.client.language.ClientLanguage.t("AI 接管已停止 · ")+reason),false);}
    public static void contextBoundary(String reason){AutonomousBodyClient.boundary(reason);if(flight!=null)stop(reason);}
    private static void check(){var f=flight;if(f!=null&&!f.lease.valid(now(),context(f)))stop(context(f)?"SERVER_LEASE_LOST":"CLIENT_CONTEXT_CHANGED");}
    @SubscribeEvent public static void frame(RenderFrameEvent.Pre e){check();}
    @SubscribeEvent public static void opening(ScreenEvent.Opening e){if(e.getNewScreen()!=null)contextBoundary("SCREEN_OPENED");}
    @SubscribeEvent public static void tick(ClientTickEvent.Pre e){
        check();var f=flight;if(f==null)return;var mc=Minecraft.getInstance();if(f.lease.phase()!=PlayerControlLease.Phase.RUNNING)return;
        var next=f.input.next();if(next.isEmpty()){stop("INPUT_SEQUENCE_FINISHED");return;}var frame=next.orElseThrow();var step=frame.step();
        try{releaseKeys();if(!f.priorAction.equals(step.action())&&Set.of("ATTACK","USE").contains(f.priorAction))cancelInteraction(f);f.priorAction=step.action();
            switch(step.action()){
                case "FORWARD"->down(mc.options.keyUp,true);case "BACK"->down(mc.options.keyDown,true);case "LEFT"->down(mc.options.keyLeft,true);case "RIGHT"->down(mc.options.keyRight,true);
                case "SPRINT_FORWARD"->{down(mc.options.keyUp,true);down(mc.options.keySprint,true);}case "JUMP"->down(mc.options.keyJump,true);case "SNEAK"->down(mc.options.keyShift,true);
                case "ATTACK"->{down(mc.options.keyAttack,true);if(frame.first())click(mc.options.keyAttack);}case "USE"->{down(mc.options.keyUse,true);if(frame.first())click(mc.options.keyUse);}
                case "LOOK"->{f.player.setYRot(f.player.getYRot()+(float)(step.yaw()/step.ticks()));f.player.setXRot(net.minecraft.util.Mth.clamp(f.player.getXRot()+(float)(step.pitch()/step.ticks()),-90,90));}
                case "HOTBAR"->{if(frame.first())f.player.getInventory().setSelectedSlot(step.slot());}case "WAIT"->{}default->throw new IllegalStateException("INVALID_INPUT_ACTION");
            }
            f.lastFrame=frame.last();f.frames++;if(++f.heartbeat>=5){f.heartbeat=0;send(f,"HEARTBEAT","");}
            mc.gui.setOverlayMessage(Component.literal(dev.mineagent.runtime.neoforge.client.language.ClientLanguage.t("AI 接管本人 · ")+(frame.index()+1)+"/"+f.plan.steps().size()+" "+label(step)+dev.mineagent.runtime.neoforge.client.language.ClientLanguage.t(" · Esc 退出接管")),false);
        }catch(Exception failure){stop("INPUT_FAILED");}
    }
    @SubscribeEvent public static void afterTick(ClientTickEvent.Post e){if(flight!=null&&flight.lastFrame)stop("INPUT_SEQUENCE_FINISHED");}
    static void resetMouse(){var mc=Minecraft.getInstance();var m=(dev.mineagent.runtime.neoforge.mixin.client.WorkspaceMouseAccess)mc.mouseHandler;m.mineagent$left(false);m.mineagent$middle(false);m.mineagent$right(false);m.mineagent$activeButton(null);m.mineagent$fakeRight(0);mc.mouseHandler.setIgnoreFirstMove();}
    public static boolean physicalKey(long window,int action,KeyEvent event){var mc=Minecraft.getInstance();if(AutonomousBodyClient.active()){if(event.key()==256&&action==1&&window==mc.getWindow().handle()){AutonomousBodyClient.stop("USER_ESCAPE");return mc.screen==null;}if(AutonomousBodyClient.uiKey(event.key())||!AutonomousBodyClient.blocksPhysical())return false;return window==mc.getWindow().handle();}var result=PlayerControlInputPolicy.key(flight!=null,window==mc.getWindow().handle(),event.key(),action);if(result==PlayerControlInputPolicy.Result.STOP)stop("USER_ESCAPE");return result!=PlayerControlInputPolicy.Result.PASS;}
    @SubscribeEvent(priority=net.neoforged.bus.api.EventPriority.HIGHEST) public static void mouse(InputEvent.MouseButton.Pre e){if(flight!=null||AutonomousBodyClient.blocksPhysical())e.setCanceled(true);}
    @SubscribeEvent(priority=net.neoforged.bus.api.EventPriority.HIGHEST) public static void scroll(InputEvent.MouseScrollingEvent e){if(flight!=null||AutonomousBodyClient.blocksPhysical())e.setCanceled(true);}
    public static boolean blockMotion(long window){return (flight!=null||AutonomousBodyClient.blocksPhysical())&&window==Minecraft.getInstance().getWindow().handle();}
    static boolean physicalInputsReleased(){var mc=Minecraft.getInstance();long w=mc.getWindow().handle();for(int b=0;b<=GLFW.GLFW_MOUSE_BUTTON_LAST;b++)if(GLFW.glfwGetMouseButton(w,b)==GLFW.GLFW_PRESS)return false;for(var key:keys())if(key.getKey().getType()==com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM&&key.getKey().getValue()>=GLFW.GLFW_KEY_SPACE&&GLFW.glfwGetKey(w,key.getKey().getValue())==GLFW.GLFW_PRESS)return false;for(int key:new int[]{GLFW.GLFW_KEY_ENTER,GLFW.GLFW_KEY_KP_ENTER,GLFW.GLFW_KEY_SPACE,GLFW.GLFW_KEY_TAB,GLFW.GLFW_KEY_LEFT_SHIFT,GLFW.GLFW_KEY_RIGHT_SHIFT,GLFW.GLFW_KEY_LEFT_CONTROL,GLFW.GLFW_KEY_RIGHT_CONTROL,GLFW.GLFW_KEY_LEFT_ALT,GLFW.GLFW_KEY_RIGHT_ALT})if(GLFW.glfwGetKey(w,key)==GLFW.GLFW_PRESS)return false;return true;}
    public static String label(PlayerControlPlan.Step step){String name=switch(step.action()){case "FORWARD"->dev.mineagent.runtime.neoforge.client.language.ClientLanguage.t("前进");case "BACK"->dev.mineagent.runtime.neoforge.client.language.ClientLanguage.t("后退");case "LEFT"->dev.mineagent.runtime.neoforge.client.language.ClientLanguage.t("左移");case "RIGHT"->dev.mineagent.runtime.neoforge.client.language.ClientLanguage.t("右移");case "JUMP"->dev.mineagent.runtime.neoforge.client.language.ClientLanguage.t("跳跃");case "SNEAK"->dev.mineagent.runtime.neoforge.client.language.ClientLanguage.t("潜行");case "SPRINT_FORWARD"->dev.mineagent.runtime.neoforge.client.language.ClientLanguage.t("向前疾跑");case "ATTACK"->dev.mineagent.runtime.neoforge.client.language.ClientLanguage.t("攻击 / 挖掘");case "USE"->dev.mineagent.runtime.neoforge.client.language.ClientLanguage.t("使用 / 放置");case "LOOK"->dev.mineagent.runtime.neoforge.client.language.ClientLanguage.t("转视角 yaw ")+step.yaw()+"° / pitch "+step.pitch()+"°";case "HOTBAR"->dev.mineagent.runtime.neoforge.client.language.ClientLanguage.t("选择快捷栏 ")+(step.slot()+1);default->dev.mineagent.runtime.neoforge.client.language.ClientLanguage.t("等待");};return name+" · "+step.ticks()/20.0+dev.mineagent.runtime.neoforge.client.language.ClientLanguage.t(" 秒");}
}
