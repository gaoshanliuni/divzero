package dev.mineagent.runtime.neoforge.ui;
import dev.mineagent.runtime.neoforge.MineAgentRuntimeServices;
import dev.mineagent.runtime.neoforge.task.AutonomousPlayerAgent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import java.util.*;
import java.nio.file.*;
public final class ConversationAutonomySmokeServer {
    public static volatile boolean ready,blocked,arrived,verified;public static volatile UUID agent;public static volatile String failure="";public static volatile int aliveTicks;private static BlockPos center,gold;private static int started,arrivedAt;private static double deviation;private static final List<Object> positions=new ArrayList<>();
    public static boolean active(){return Boolean.getBoolean("mineagent.conversationAgentReal")&&System.getProperty("mineagent.conversationAgentScenario","").equals("autonomy");}
    private static void save(MinecraftServer s,String name,Object v)throws Exception{Files.writeString(Files.createDirectories(s.getServerDirectory().resolve("autonomy-smoke")).resolve(name+".json"),new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(v));}
    public static void tick(MinecraftServer s){if(verified||!failure.isEmpty())return;var p=s.getPlayerList().getPlayers().stream().filter(v->!(v instanceof dev.mineagent.runtime.neoforge.body.MineAgentPlayer)).findFirst().orElse(null);if(p==null)return;try{
        if(!ready){center=p.blockPosition();for(var pos:BlockPos.betweenClosed(center.offset(-12,-1,-12),center.offset(12,5,12)))p.level().setBlockAndUpdate(pos,pos.getY()<center.getY()?Blocks.STONE.defaultBlockState():Blocks.AIR.defaultBlockState());p.connection.teleport(center.getX()+.5,center.getY(),center.getZ()+.5,0,0);gold=center.offset(7,-1,0);p.level().setBlockAndUpdate(gold,Blocks.GOLD_BLOCK.defaultBlockState());p.level().setBlockAndUpdate(center.offset(7,1,-4),Blocks.QUARTZ_BLOCK.defaultBlockState());agent=MineAgentRuntimeServices.bodies(s).createPersistentAt("工具助手",p.getUUID(),p.level(),p.position().add(-6,0,3)).agentId();save(s,"fixture",Map.of("origin",List.of(p.getX(),p.getY(),p.getZ()),"gold",List.of(gold.getX(),gold.getY(),gold.getZ()),"teleportsDuringControl",false));ready=true;return;}
        var state=AutonomousPlayerAgent.inspect(p);if(!Boolean.TRUE.equals(state.get("active"))){if(arrived){save(s,"result",Map.of("status","REAL_CONTINUOUS_PLAYER_CONTROL_VERIFIED","server",state,"dynamicObstacle",blocked,"deviation",deviation,"activeTicks",aliveTicks,"positions",positions,"fixtureMovesPlayerAfterStart",false));verified=true;}return;}
        if(state.get("state").equals("REVIEW"))return;if(started==0)started=s.getTickCount();aliveTicks=s.getTickCount()-started;double dx=p.getX()-(center.getX()+.5);deviation=Math.max(deviation,Math.abs(p.getZ()-(center.getZ()+.5)));
        if(s.getTickCount()%10==0){positions.add(Map.of("tick",s.getTickCount(),"position",List.of(p.getX(),p.getY(),p.getZ()),"yaw",p.getYRot(),"pitch",p.getXRot(),"state",state.get("state")));save(s,"progress",Map.of("state",state,"position",List.of(p.getX(),p.getY(),p.getZ()),"blocked",blocked,"deviation",deviation,"activeTicks",aliveTicks));}
        if(!blocked&&dx>1&&dx<3){for(int y=0;y<2;y++)p.level().setBlockAndUpdate(center.offset(3,y,0),Blocks.STONE.defaultBlockState());blocked=true;save(s,"obstacle",Map.of("addedAtTick",s.getTickCount(),"player",List.of(p.getX(),p.getY(),p.getZ()),"block",List.of(center.getX()+3,center.getY(),center.getZ())));}
        if(state.get("state").equals("WAITING_FOR_INSTRUCTION"))throw new IllegalStateException("AUTONOMY_PLANNER_REJECTED_"+state.get("lastResult"));
        boolean atGold=Math.hypot(p.getX()-(gold.getX()+.5),p.getZ()-(gold.getZ()+.5))<1&&Math.abs(p.getY()-(gold.getY()+1))<.6;
        if(atGold&&state.get("state").equals("IDLE")){if(!blocked||deviation<.6||((Number)state.get("reroutes")).intValue()<2)throw new IllegalStateException("AUTONOMY_DID_NOT_DYNAMICALLY_REROUTE");if(Math.abs(net.minecraft.util.Mth.wrapDegrees(p.getYRot()-180))>35)throw new IllegalStateException("AUTONOMY_VIEW_NOT_AT_QUARTZ");if(arrivedAt==0){arrivedAt=s.getTickCount();save(s,"at-target",Map.of("state",state,"yaw",p.getYRot(),"pitch",p.getXRot(),"position",List.of(p.getX(),p.getY(),p.getZ())));}if(aliveTicks>1050&&s.getTickCount()-arrivedAt>80)arrived=true;}
        if(aliveTicks>8000)throw new IllegalStateException("AUTONOMY_NATIVE_TIMEOUT");
    }catch(Exception e){failure=e.toString();try{save(s,"failure",Map.of("error",failure,"positions",positions));}catch(Exception ignored){}}}
}
