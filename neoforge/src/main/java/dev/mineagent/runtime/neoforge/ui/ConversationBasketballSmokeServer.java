package dev.mineagent.runtime.neoforge.ui;
import com.fasterxml.jackson.databind.*;
import dev.mineagent.runtime.neoforge.content.*;
import dev.mineagent.runtime.neoforge.MineAgentRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.DisplaySlot;
import java.nio.file.*;
import java.util.*;
/** Test player input only; never constructs models, sets scores or moves/creates flying balls. */
public final class ConversationBasketballSmokeServer {
    private static final ObjectMapper JSON=new ObjectMapper();
    public static volatile int request,observedUseTicks,expectedScore;public static volatile boolean capture;
    private static UUID instance,activation;private static Vec3 origin;private static double cx,cy,cz,radius,rimRadius;private static String objective,binding;
    private static int phase,deadline,seenAt,shot,initialScore;private static RuntimeThrownItemEntity flight;private static boolean downward;
    private static final List<Map<String,Object>> shots=new ArrayList<>();private static final List<Map<String,Object>> path=new ArrayList<>();
    private static void save(MinecraftServer s,String n,Object v)throws Exception{Files.writeString(Files.createDirectories(s.getServerDirectory().resolve("runtime-item-smoke")).resolve(n+".json"),JSON.writeValueAsString(v));}
    private static int inventory(ServerPlayer p){int n=0;for(int i=0;i<p.getInventory().getContainerSize();i++)if(p.getInventory().getItem(i).is(MineAgentRegistries.RUNTIME_ITEM.get()))n+=p.getInventory().getItem(i).getCount();return n;}
    private static List<RuntimeThrownItemEntity> flights(ServerPlayer p){var out=new ArrayList<RuntimeThrownItemEntity>();for(var e:p.level().getAllEntities())if(e instanceof RuntimeThrownItemEntity f&&!f.isRemoved())out.add(f);return out;}
    private static int score(MinecraftServer s){var board=s.getScoreboard();var o=board.getObjective(objective);if(o==null||board.getDisplayObjective(DisplaySlot.SIDEBAR)!=o)throw new IllegalStateException("BASKETBALL_SIDEBAR_MISSING");int sum=0;for(var info:board.listPlayerScores(o))sum+=info.value();return sum;}
    public static void begin(MinecraftServer s,ServerPlayer p,UUID selected,UUID approved)throws Exception{
        instance=selected;activation=approved;origin=p.position();binding=p.getMainHandItem().get(MineAgentRegistries.RUNTIME_ITEM_BINDING.get());var b=RuntimeItem.binding(p.getMainHandItem());if(b==null||b.chargeTicks()!=40||inventory(p)!=1)throw new IllegalStateException("BASKETBALL_ITEM_MISSING");var quality=dev.mineagent.runtime.core.objects.ModelGeometryTools.inspect(b.modelSource(),"item");var report=JSON.valueToTree(quality);if(report.path("spheres").isEmpty())throw new IllegalStateException("BASKETBALL_NOT_SPHERICAL");for(var sphere:report.path("spheres"))if(sphere.path("maxFaceInsetRelativeToRadius").asDouble()>.003)throw new IllegalStateException("BASKETBALL_SPHERE_QUALITY");save(s,"basketball-ball-quality",quality);deadline=s.getTickCount()+1400;phase=0;
    }
    public static void tick(MinecraftServer s,ServerPlayer p)throws Exception{
        var runtime=WorldContentRuntime.get(s);observedUseTicks=p.isUsingItem()?p.getTicksUsingItem():0;if(s.getTickCount()>deadline)throw new IllegalStateException("BASKETBALL_TIMEOUT_"+phase);
        if(phase==0){
            int solids=0,visuals=0;RuntimeObjectEntity ring=null;
            for(var e:p.level().getAllEntities())if(e instanceof RuntimeObjectEntity o&&o.header()!=null&&o.header().instance().equals(instance)){
                if(o.header().collision().nonSolid()){visuals++;byte[] bytes=runtime.objectBundle(o).bytes();int length=java.nio.ByteBuffer.wrap(bytes).getInt();var model=JSON.readTree(new String(bytes,4,length,java.nio.charset.StandardCharsets.UTF_8));for(var prim:model.path("primitives"))if(prim.path("type").asText().equals("torus")&&prim.path("axis").asText("y").equals("y")&&prim.path("majorRadius").asDouble()>=.3){if(ring!=null)throw new IllegalStateException("BASKETBALL_AMBIGUOUS_RING");ring=o;cx=o.getX()+prim.path("center").get(0).asDouble();cy=o.getY()+prim.path("center").get(1).asDouble();cz=o.getZ()+prim.path("center").get(2).asDouble();rimRadius=prim.path("majorRadius").asDouble();radius=rimRadius-prim.path("minorRadius").asDouble();}}
                else solids++;
            }
            if(ring==null){return;}if(solids<4||radius<.3)throw new IllegalStateException("BASKETBALL_HOLLOW_FRAME_MISSING");var state=runtime.instance(instance).orElseThrow().state();var keys=state.keySet().stream().filter(k->k.startsWith("_score.")).toList();if(keys.size()!=1)throw new IllegalStateException("BASKETBALL_SCORE_NOT_CREATED");objective=state.get(keys.getFirst());initialScore=score(s);if(initialScore!=0)throw new IllegalStateException("BASKETBALL_INITIAL_SCORE");
            if(!p.level().noCollision(null,new net.minecraft.world.phys.AABB(cx-.22,cy-.5,cz-.22,cx+.22,cy+.5,cz+.22)))throw new IllegalStateException("BASKETBALL_HOLE_BLOCKED");
            for(int i=0;i<12;i++){double angle=i*Math.PI/6,rx=cx+rimRadius*Math.cos(angle),rz=cz+rimRadius*Math.sin(angle);if(p.level().noCollision(null,new net.minecraft.world.phys.AABB(rx-.2,cy-.2,rz-.2,rx+.2,cy+.2,rz+.2)))throw new IllegalStateException("BASKETBALL_RIM_COLLISION_GAP_"+i);}
            save(s,"basketball-frame",Map.of("center",List.of(cx,cy,cz),"innerRadius",radius,"solidParts",solids,"visualParts",visuals,"objective",objective,"holeNativeCollisionFree",true));shot=1;positionShot(p);phase=1;return;
        }
        if(phase==1){var f=flights(p);if(f.isEmpty())return;if(f.size()!=1||inventory(p)!=0)throw new IllegalStateException("BASKETBALL_STACK_NOT_TRANSFERRED");flight=f.getFirst();downward=false;path.clear();seenAt=s.getTickCount();phase=2;p.connection.teleport(cx+3,origin.y,cz-4,35,0);return;}
        if(phase==2){
            if(flight.isRemoved())throw new IllegalStateException("BASKETBALL_FLIGHT_LOST");var box=flight.getBoundingBox();path.add(Map.of("tick",flight.tickCount,"position",List.of(flight.getX(),flight.getY(),flight.getZ()),"velocityY",flight.getDeltaMovement().y,"score",score(s),"collisions",flight.collisions()));
            double a=flight.previousY()+box.getYsize(),b=box.maxY;
            if(a>cy&&b<=cy&&b<a){double t=(a-cy)/(a-b),x=flight.previousX()+(flight.getX()-flight.previousX())*t-cx,z=flight.previousZ()+(flight.getZ()-flight.previousZ())*t-cz;downward|=Math.pow(Math.abs(x)+box.getXsize()/2,2)+Math.pow(Math.abs(z)+box.getZsize()/2,2)<radius*radius;}
            if(!downward&&score(s)!=expectedScore)throw new IllegalStateException("BASKETBALL_SCORED_WITHOUT_DOWNWARD_CROSSING");
            if(s.getTickCount()-seenAt<200)return;int expected=shot==1?0:(shot-1)*2;if(score(s)!=expected||shot>1&&!downward||shot==1&&downward)throw new IllegalStateException("BASKETBALL_SCORE_TRAJECTORY_MISMATCH_"+shot);
            if(!binding.equals(flight.item().get(MineAgentRegistries.RUNTIME_ITEM_BINDING.get()))||flight.item().getCount()!=1)throw new IllegalStateException("BASKETBALL_STACK_CHANGED");
            expectedScore=expected;shots.add(Map.of("shot",shot,"usedTicks",flight.usedTicks(),"speed",flight.launchSpeed(),"downwardCrossing",downward,"score",expected,"uuid",flight.getUUID(),"collisions",flight.collisions()));save(s,"basketball-trajectory-"+shot,path);save(s,"basketball-shots",shots);phase=3;
        }
        if(phase==3){if(!flight.isRemoved()){p.connection.teleport(flight.getX(),flight.getY(),flight.getZ(),0,0);return;}if(inventory(p)!=1||!flights(p).isEmpty())throw new IllegalStateException("BASKETBALL_PICKUP_FAILED");if(shot<3){shot++;positionShot(p);phase=1;deadline=s.getTickCount()+1400;return;}for(var actor:s.getPlayerList().getPlayers())if(actor instanceof dev.mineagent.runtime.neoforge.body.MineAgentPlayer)actor.connection.teleport(origin.x-12,origin.y,origin.z-12,0,0);p.connection.teleport(cx+3,origin.y+.1,cz+4,143,-10);capture=true;phase=4;seenAt=s.getTickCount();return;}
        if(phase==4&&ConversationRuntimeItemSmokeServer.clientCaptured&&s.getTickCount()-seenAt>40){if(score(s)!=4)throw new IllegalStateException("BASKETBALL_DUPLICATE_SCORE");runtime.disable(p,activation);if(runtime.itemActive(p,p.getMainHandItem()))throw new IllegalStateException("BASKETBALL_DISABLED_ITEM_ACTIVE");save(s,"result",Map.of("status","DEEPSEEK_GENERATED_BASKETBALL_NATIVE_VERIFIED","ordinaryChat",!ConversationRuntimeItemSmokeServer.saved(),"shots",shots,"missDoesNotScore",true,"upwardRejectionCoverage","OFFLINE_TRAJECTORY_TEST","downwardScoresOncePerThrow",true,"originalStackPickup",true,"fixtureMovesPlayerForAimAndPickup",true,"fixtureSetsScoreOrMovesFlight",false));request=4;ConversationRuntimeItemSmokeServer.verified=true;}
    }
    private static void positionShot(ServerPlayer p){double x=cx+(shot==1?radius+1.1:0);p.connection.teleport(x,origin.y,cz,0,-90);request=shot;}
}
