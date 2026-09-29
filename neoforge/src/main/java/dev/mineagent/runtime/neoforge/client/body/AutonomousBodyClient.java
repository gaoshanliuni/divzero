package dev.mineagent.runtime.neoforge.client.body;
import com.fasterxml.jackson.databind.*;
import dev.mineagent.runtime.neoforge.network.AutonomyPayloads;
import dev.mineagent.runtime.neoforge.mixin.client.PlayerControlKeyAccess;
import net.minecraft.client.*;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.*;
import net.minecraft.core.BlockPos;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.*;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import java.util.*;

/** Persistent local consent with short, server-leased objectives. No OS input or player teleport. */
@EventBusSubscriber(modid="mineagent_runtime",value=Dist.CLIENT)
public final class AutonomousBodyClient {
    private static final ObjectMapper JSON=new ObjectMapper();private static Flight flight;private static String last="NONE";public static int localApprovals,hudDraws;
    private static final class Flight {final AutonomyPayloads.Offer offer;final UUID consent=UUID.randomUUID();final Object wire,level,player;final boolean pauseOnLostFocus;long sequence,updated=now(),revision=-1;JsonNode state=JSON.createObjectNode();boolean paused,grant,resuming,clicked;int beats;Flight(AutonomyPayloads.Offer offer,Object wire){this.offer=offer;this.wire=wire;var mc=Minecraft.getInstance();level=mc.level;player=mc.player;pauseOnLostFocus=mc.options.pauseOnLostFocus;mc.options.pauseOnLostFocus=false;}}
    private AutonomousBodyClient(){}
    private static long now(){return System.nanoTime()/1000000;}
    private static List<KeyMapping> keys(){var o=Minecraft.getInstance().options;return List.of(o.keyUp,o.keyDown,o.keyLeft,o.keyRight,o.keyJump,o.keyShift,o.keySprint,o.keyAttack,o.keyUse);}
    private static void key(KeyMapping k,boolean value){((PlayerControlKeyAccess)k).mineagent$bodyDown(value);}
    private static void release(){AutonomyVirtualInput.clear();for(var k:keys()){key(k,false);((PlayerControlKeyAccess)k).mineagent$bodyClicks(0);}}
    private static void cancelUse(){var mc=Minecraft.getInstance();if(mc.gameMode!=null&&mc.player!=null){mc.gameMode.stopDestroyBlock();if(mc.player.isUsingItem())mc.player.stopUsingItem();mc.player.setSprinting(false);}}
    public static boolean active(){return flight!=null;}
    static Object cameraSession(){return flight;}
    private static boolean identity(Flight f){var mc=Minecraft.getInstance();return mc.getConnection()!=null&&mc.getConnection().getConnection()==f.wire&&mc.level==f.level&&mc.player==f.player&&mc.player.isAlive()&&!mc.player.isSpectator()&&f.offer.dimension().equals(mc.level.dimension().identifier().toString());}
    private static boolean usable(){var mc=Minecraft.getInstance();return mc.player!=null&&mc.level!=null&&!mc.isPaused()&&!mc.player.isPassenger();}
    public static boolean blocksPhysical(){return active()&&Minecraft.getInstance().screen==null;}
    public static boolean uiKey(int code){var mc=Minecraft.getInstance();return code==291||code==mc.options.keyChat.getKey().getValue()||code==mc.options.keyCommand.getKey().getValue();}
    public static Map<String,Object> observe(){return Map.of("active",active(),"state",flight==null?last:flight.state.path("state").asText("ARMING"),"localApprovals",localApprovals,"hudDraws",hudDraws,"paused",flight!=null&&flight.paused,"sequence",flight==null?0:flight.sequence,"reroutes",flight==null?0:flight.state.path("reroutes").asInt(),"rounds",flight==null?0:flight.state.path("round").asInt(),"route",flight==null?"[]":flight.state.path("route").toString());}
    public static void offer(AutonomyPayloads.Offer o,Object wire){var mc=Minecraft.getInstance();if(mc.getConnection()==null||mc.getConnection().getConnection()!=wire||mc.player==null||!mc.player.getUUID().equals(o.player())||mc.level==null||!mc.level.dimension().identifier().toString().equals(o.dimension()))return;if(!dev.mineagent.runtime.neoforge.client.MineAgentClientTrustPrompt.enabled()||flight!=null||PlayerBodyControlClient.active()||mc.getOverlay()!=null||mc.screen!=null&&!(mc.screen instanceof net.minecraft.client.gui.screens.ChatScreen)&&!(mc.screen instanceof dev.mineagent.runtime.neoforge.client.nativeui.NativeWorkspaceScreen)&&!(mc.screen instanceof dev.mineagent.runtime.neoforge.client.nativeui.AgentProfileScreen)){ClientPacketDistributor.sendToServer(new AutonomyPayloads.Input(o.session(),new UUID(0,0),"STOP",true));return;}var f=new Flight(o,wire);flight=f;PlayerBodyControlClient.resetEscapeGesture();last="AUTOMATIC_USER_REQUEST";mc.mouseHandler.releaseMouse();KeyMapping.releaseAll();release();send(f,"START",false);}
    private static void send(Flight f,String kind,boolean paused){var mc=Minecraft.getInstance();if(mc.getConnection()!=null&&mc.getConnection().getConnection()==f.wire)ClientPacketDistributor.sendToServer(new AutonomyPayloads.Input(f.offer.session(),f.consent,kind,paused));}
    public static void frame(AutonomyPayloads.Frame value,Object wire){var f=flight;if(f==null||wire!=f.wire||!f.offer.session().equals(value.session())||!f.consent.equals(value.consent())||value.sequence()<=f.sequence)return;try{var n=JSON.readTree(value.data());if(n.path("state").asText().equals("STOPPED")){stop("SERVER_STOP");return;}if(!n.isObject()||!n.path("route").isArray()||n.path("route").size()>16||!Set.of("WAIT","IDLE","MOVE","LOOK","ATTACK","USE","HOTBAR","JUMP","SNEAK").contains(n.path("mode").asText())||n.path("goal").asText().length()>4096||n.path("summary").asText().length()>240)throw new IllegalArgumentException();vector(n.path("target"));for(var point:n.path("route"))vector(point);if(n.has("skill"))NativeSkillInput.validate(n.get("skill"));if(!f.grant)Minecraft.getInstance().gui.setOverlayMessage(Component.empty(),false);f.state=n;f.paused=n.path("manualPaused").asBoolean(false);f.sequence=value.sequence();f.updated=now();f.grant=true;f.resuming=false;}catch(Exception invalid){stop("INVALID_SERVER_FRAME");}}
    public static void boundary(String reason){if(flight==null)return;if(Set.of("FOCUS_LOST","SCREEN_OPENED").contains(reason))return;stop(reason);}
    private static void pause(){var f=flight;if(f==null)return;release();cancelUse();if(!f.paused){f.paused=true;send(f,"HEARTBEAT",true);}}
    public static void stop(String reason){PlayerBodyControlClient.resetEscapeGesture();var f=flight;if(f==null)return;release();cancelUse();flight=null;NativeSkillInput.reset();dev.mineagent.runtime.neoforge.client.nativeui.AutonomyControlPanel.clear();Minecraft.getInstance().options.pauseOnLostFocus=f.pauseOnLostFocus;PlayerBodyControlClient.resetMouse();if(Minecraft.getInstance().screen==null&&Minecraft.getInstance().isWindowActive())Minecraft.getInstance().mouseHandler.grabMouse();last=reason;try{send(f,"STOP",false);}catch(Exception ignored){}Minecraft.getInstance().gui.setOverlayMessage(endedMessage(reason),false);}
    public static Component endedMessage(String reason){return Component.literal(dev.mineagent.runtime.neoforge.client.language.ClientLanguage.t(reason.equals("INPUT_SEQUENCE_FINISHED")?"本次控制已完成":reason.startsWith("USER_")||reason.equals("SERVER_STOP")?"已退出托管":"托管已结束"));}
    public static void togglePause(){var f=flight;if(f==null)return;f.paused=!f.paused;release();cancelUse();send(f,f.paused?"PAUSE":"RESUME",false);}
    public static void appendCommand(){var f=flight;if(f!=null)Minecraft.getInstance().setScreen(new net.minecraft.client.gui.screens.ChatScreen("@"+f.offer.agent()+" ",false));}
    public static String panelAgent(){return flight==null?"":flight.offer.agent();}
    public static String panelGoal(){return flight==null?"":flight.state.path("goal").asText(flight.offer.goal());}
    public static String panelSummary(){return flight==null?"":flight.state.path("summary").asText("正在启动");}
    public static boolean manuallyPaused(){return flight!=null&&flight.paused;}
    @SubscribeEvent public static void tick(ClientTickEvent.Pre event){var f=flight;if(f==null)return;if(!identity(f)){stop("CLIENT_CONTEXT_CHANGED");return;}var mc=Minecraft.getInstance();
        if(++f.beats>=5){f.beats=0;send(f,"HEARTBEAT",false);}if(now()-f.updated>(f.grant?3000:8000)){stop("SERVER_LEASE_LOST");return;}release();if(!usable()||!f.grant||f.paused)return;
        var n=f.state;if(n.has("skill")){NativeSkillInput.tick(n.get("skill"));return;}
        String mode=n.path("mode").asText("WAIT");var command=JSON.createObjectNode().put("operation",UUID.nameUUIDFromBytes((f.offer.session()+"/"+n.path("actionRevision").asLong()).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString()).put("slot",n.path("slot").asInt(0)).put("sneaking",n.path("sneaking").asBoolean());
        String action=switch(mode){case "WAIT","IDLE"->"HALT";case "ATTACK"->"BREAK";case "USE"->"USE_BLOCK";default->mode;};command.put("action",action);command.set("target",n.path("target"));
        if(mode.equals("MOVE")){var route=n.path("route");int index=0;while(index<route.size()&&vector(route.get(index)).distanceToSqr(mc.player.position())<.28)index++;if(index>=route.size())return;command.set("target",route.get(index));}
        NativeSkillInput.tick(command);
    }
    private static Vec3 vector(JsonNode n){if(!n.isArray()||n.size()!=3)throw new IllegalArgumentException("AUTONOMY_VECTOR");double x=n.get(0).asDouble(Double.NaN),y=n.get(1).asDouble(Double.NaN),z=n.get(2).asDouble(Double.NaN);if(!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(z))throw new IllegalArgumentException("AUTONOMY_VECTOR");return new Vec3(x,y,z);}
    private static double angle(double dx,double dz){return Math.toDegrees(Math.atan2(-dx,dz));}
    private static void turn(float yaw,float pitch){var p=Minecraft.getInstance().player;p.setYRot(p.getYRot()+Mth.clamp(Mth.wrapDegrees(yaw-p.getYRot()),-12,12));p.setXRot(Mth.clamp(p.getXRot()+Mth.clamp(pitch-p.getXRot(),-9,9),-90,90));}
    @SubscribeEvent public static void hud(RenderGuiEvent.Post event){if(flight!=null&&Minecraft.getInstance().screen==null){dev.mineagent.runtime.neoforge.client.nativeui.AutonomyControlPanel.render(event.getGuiGraphics());hudDraws++;}}
    private static String stateLabel(String state){return switch(state){case "ACTING"->dev.mineagent.runtime.neoforge.client.language.ClientLanguage.t("执行中");case "PLANNING"->dev.mineagent.runtime.neoforge.client.language.ClientLanguage.t("规划中");case "OBSERVING"->dev.mineagent.runtime.neoforge.client.language.ClientLanguage.t("观察中");case "IDLE"->dev.mineagent.runtime.neoforge.client.language.ClientLanguage.t("待命");case "WAITING_FOR_INSTRUCTION"->dev.mineagent.runtime.neoforge.client.language.ClientLanguage.t("等待新指令");case "PAUSED"->dev.mineagent.runtime.neoforge.client.language.ClientLanguage.t("暂停");default->dev.mineagent.runtime.neoforge.client.language.ClientLanguage.t("连接中");};}
    private static String coords(Vec3 p){return String.format(java.util.Locale.ROOT,"%.1f, %.2f, %.1f",p.x,p.y,p.z);}
    public static final class StartScreen extends Screen {
        private final AutonomyPayloads.Offer offer;private final Object wire;private boolean requested,handed;private int quiet;private Button start;
        StartScreen(AutonomyPayloads.Offer offer,Object wire){super(Component.literal(dev.mineagent.runtime.neoforge.client.language.ClientLanguage.t("开始持续接管本人")));this.offer=offer;this.wire=wire;}
        @Override protected void init(){int x=Math.max(12,width/2-250),w=Math.min(500,width-24);addRenderableWidget(new StringWidget(x,30,w,20,getTitle(),font));int y=60;for(var line:font.split(Component.literal(offer.goal()),w)){var text=new StringBuilder();line.accept((i,style,c)->{text.appendCodePoint(c);return true;});addRenderableWidget(new StringWidget(x,y,w,13,Component.literal(text.toString()),font));y+=14;if(y>height-150)break;}addRenderableWidget(new StringWidget(x,height-116,w,18,Component.literal(dev.mineagent.runtime.neoforge.client.language.ClientLanguage.t("持续观察和规划；后续不再逐次确认，会继续使用模型服务。")),font));addRenderableWidget(new StringWidget(x,height-94,w,18,Component.literal(dev.mineagent.runtime.neoforge.client.language.ClientLanguage.t("到达目标保持待命；界面和切窗不中断，游戏画面双击 Esc 退出。")),font));start=addRenderableWidget(Button.builder(Component.literal(dev.mineagent.runtime.neoforge.client.language.ClientLanguage.t("确认，直到我主动停止")),b->requested=true).bounds(x,height-58,w/2-4,22).build());var cancel=addRenderableWidget(Button.builder(Component.literal(dev.mineagent.runtime.neoforge.client.language.ClientLanguage.t("取消")),b->onClose()).bounds(x+w/2+4,height-58,w/2-4,22).build());setInitialFocus(cancel);}
        @Override public void tick(){var mc=Minecraft.getInstance();if(mc.getConnection()==null||mc.getConnection().getConnection()!=wire||mc.player==null||!mc.player.getUUID().equals(offer.player())){onClose();return;}start.active=!requested&&mc.isWindowActive();if(requested){if(!mc.isWindowActive()){onClose();return;}quiet=PlayerBodyControlClient.physicalInputsReleased()?quiet+1:0;if(quiet>=3){var f=new Flight(offer,wire);flight=f;localApprovals++;handed=true;dev.mineagent.runtime.neoforge.client.webui.WebGuiHostAdapter.INSTANCE.hideWorkspace();mc.setScreen(null);KeyMapping.releaseAll();release();send(f,"START",false);}}}
        @Override public void onClose(){if(!handed&&Minecraft.getInstance().getConnection()!=null)ClientPacketDistributor.sendToServer(new AutonomyPayloads.Input(offer.session(),new UUID(0,0),"STOP",true));Minecraft.getInstance().setScreen(null);}
        @Override public void removed(){if(!handed&&Minecraft.getInstance().getConnection()!=null)ClientPacketDistributor.sendToServer(new AutonomyPayloads.Input(offer.session(),new UUID(0,0),"STOP",true));super.removed();}
        @Override public boolean isPauseScreen(){return false;}
    }
}
