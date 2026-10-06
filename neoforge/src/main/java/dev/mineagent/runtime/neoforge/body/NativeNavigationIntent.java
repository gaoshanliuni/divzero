package dev.mineagent.runtime.neoforge.body;

import dev.mineagent.runtime.core.task.*;
import dev.mineagent.runtime.core.task.SurfacePathfinder.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/** One navigation intent, usable by an AI body or the real-player input adapter. */
public final class NativeNavigationIntent {
    private final NativeTerrainRecovery recovery=new NativeTerrainRecovery();
    public NativeTerrainRecovery recovery(){return recovery;}
    public void recheckAfterTerrain(){search=null;steps=List.of();index=stuck=0;plannedTarget=null;retry.reset(0);status="MOVING";reason="TERRAIN_STEP_VERIFIED";}
    private final UUID budgetId=UUID.randomUUID();private final NavigationRetry retry=new NavigationRetry();
    private Vec3 target,plannedTarget,lastPosition;private Object level;private NativeTraversalEvaluator evaluator;private SurfacePathfinder.Search search;
    private List<PathStep> steps=List.of();private int index,stuck,searchStarted,plans,expanded;private double tolerance=.8;private String status="IDLE",reason="";
    private boolean checkedRoute;
    private long maxSliceNanos,totalSearchNanos;private int searchSlices,directPlans,issuedTick=-1,firstMoveTick=-1,arrivalTick=-1;private Vec3 issuedPosition;
    private int tacticalJumpUntil;private double tacticalJumpFloor;private boolean tacticalJumpAirborne;
    public void tacticalJump(ServerPlayer player){if(checkedRoute){tacticalJumpUntil=player.level().getServer().getTickCount()+20;tacticalJumpFloor=player.getY();tacticalJumpAirborne=false;}}
    /** Reuse the tactical search's typed route; execution still rechecks each native transition. */
    public boolean followCheckedRoute(ServerPlayer p,List<PathStep> route){
        if(route.isEmpty()||NativeTraversalEvaluator.point(route.getFirst().from()).distanceToSqr(p.position())>16)return false;
        for(int i=1;i<route.size();i++)if(!route.get(i-1).to().equals(route.get(i).from()))return false;
        var check=new NativeTraversalEvaluator(p);var first=route.getFirst();
        if(!first.from().equals(first.to())&&check.transition(first.from(),first.to())==null)return false;
        level=p.level();steps=List.copyOf(route);index=stuck=0;search=null;target=NativeTraversalEvaluator.point(route.getLast().to());plannedTarget=target;lastPosition=p.position();tolerance=.2;checkedRoute=true;status="MOVING";reason="CHECKED_TACTICAL_ROUTE";retry.reset(p.level().getServer().getTickCount());return true;
    }
    public void start(Vec3 target,double tolerance){recovery.resetStatistics();if(target==null||!Double.isFinite(target.x)||!Double.isFinite(target.y)||!Double.isFinite(target.z))throw new IllegalArgumentException("NAVIGATION_TARGET");checkedRoute=false;issuedTick=firstMoveTick=arrivalTick=-1;issuedPosition=null;maxSliceNanos=totalSearchNanos=0;searchSlices=directPlans=0;this.target=target;this.tolerance=tolerance;plannedTarget=null;lastPosition=null;steps=List.of();index=stuck=plans=expanded=0;search=null;level=null;retry.reset(0);status="MOVING";reason="";}
    public void update(Vec3 at){if(target==null){start(at,tolerance);return;}if(!Double.isFinite(at.x)||!Double.isFinite(at.y)||!Double.isFinite(at.z))throw new IllegalArgumentException("NAVIGATION_TARGET");target=at;}
    public void stop(String status){recovery.cancel();checkedRoute=false;tacticalJumpUntil=0;target=null;steps=List.of();search=null;index=0;this.status=status;}
    public Optional<Vec3> target(){return Optional.ofNullable(target);}
    public String status(){return status;}
    public String reason(){return reason;}
    public double tolerance(){return tolerance;}
    public List<PathStep> remaining(){return steps.subList(Math.min(index,steps.size()),steps.size());}
    public Vec3 waypoint(PathStep step){var n=step.to();return target!=null&&index==steps.size()-1&&n.x()==(int)Math.floor(target.x)&&n.z()==(int)Math.floor(target.z)?new Vec3(target.x,n.y(),target.z):NativeTraversalEvaluator.point(n);}
    public Map<String,Object> evidence(){var out=new LinkedHashMap<String,Object>();out.put("plans",plans);out.put("expanded",expanded);out.put("reason",reason);out.put("nextSearchTick",retry.next());out.put("failedSearches",retry.failures());out.put("terrainRecovery",recovery.evidence());out.put("directPlans",directPlans);out.put("searchSlices",searchSlices);out.put("searchMillis",totalSearchNanos/1e6);out.put("maxSliceMillis",maxSliceNanos/1e6);out.put("firstMovementTicks",firstMoveTick<0?-1:firstMoveTick-issuedTick);out.put("arrivalTicks",arrivalTick<0?-1:arrivalTick-issuedTick);return out;}
    private double intermediateTolerance(PathStep edge){
        if(index+1<steps.size()&&edge.action()==Action.WALK&&steps.get(index+1).action()==Action.WALK){
            var a=NativeTraversalEvaluator.point(edge.to()).subtract(NativeTraversalEvaluator.point(edge.from()));
            var b=NativeTraversalEvaluator.point(steps.get(index+1).to()).subtract(NativeTraversalEvaluator.point(edge.to()));
            if(Math.abs(a.y)<.01&&Math.abs(b.y)<.01&&a.normalize().dot(b.normalize())>.99)return .36;
        }return checkedRoute&&index<steps.size()-1?.1:Math.min(.1,tolerance*tolerance);
    }
    public PathStep tick(ServerPlayer p){
        if(target==null||recovery.active())return null;int tick=p.level().getServer().getTickCount();
        if(!p.onGround()&&p.getY()>tacticalJumpFloor+.1)tacticalJumpAirborne=true;
        if(tacticalJumpAirborne&&p.onGround()||tick>tacticalJumpUntil)tacticalJumpUntil=0;
        if(level!=null&&level!=p.level()){stop("DIMENSION_CHANGED");return null;}level=p.level();
        if(issuedTick<0){issuedTick=tick;issuedPosition=p.position();}else if(firstMoveTick<0&&p.position().distanceToSqr(issuedPosition)>.0001)firstMoveTick=tick;
        var offset=target.subtract(p.position());if(offset.horizontalDistanceSqr()<tolerance*tolerance&&Math.abs(offset.y)<.26){arrivalTick=tick;stop("ARRIVED");return null;}
        if(lastPosition==null||lastPosition.distanceToSqr(p.position())>=.01){lastPosition=p.position();stuck=0;}else if(!remaining().isEmpty())stuck++;
        boolean moved=plannedTarget!=null&&plannedTarget.distanceToSqr(target)>2.25;
        if(stuck>=12&&p.onGround()&&recovery.request(p,target,"REPEATED_ROUTE_OBSTRUCTION")){search=null;steps=List.of();index=stuck=0;status="RECOVERING";return null;}
        if(moved||stuck>=12){if(moved)retry.reset(tick);search=null;steps=List.of();index=0;if(stuck>=12){reason="TEMPORARY_CONGESTION";retry.waitUntil(tick+3);}stuck=0;}
        while(index<steps.size()){var edge=steps.get(index);var next=waypoint(edge);boolean flatJump=tacticalJumpUntil>0&&Math.abs(next.y-tacticalJumpFloor)<.1&&Math.abs(edge.from().y()-edge.to().y())<.1&&p.getY()-tacticalJumpFloor<1.6;double dy=next.y-(flatJump?tacticalJumpFloor:p.getY());boolean vertical=edge.action()==Action.CLIMB?(edge.to().y()>edge.from().y()?dy<=.02&&dy> -1.25:dy>= -.02&&dy<1.25):Math.abs(dy)<.26;var direction=next.subtract(NativeTraversalEvaluator.point(edge.from()));boolean passed=flatJump&&index<steps.size()-1&&p.position().subtract(next).dot(direction)>.02;if((passed||next.subtract(p.position()).horizontalDistanceSqr()<intermediateTolerance(edge))&&vertical)index++;else break;}
        if(index>=steps.size()&&search==null&&retry.ready(tick)){
            evaluator=new NativeTraversalEvaluator(p);Node start=evaluator.closest(p.position());Vec3 routeTarget=target;boolean segment=target.distanceToSqr(p.position())>16*16;if(segment)routeTarget=p.position().add(target.subtract(p.position()).normalize().scale(16));Node goal=evaluator.closest(routeTarget);plans++;plannedTarget=target;
            if(segment&&goal==null){for(int radius=1;radius<=3&&goal==null;radius++)for(int dx=-radius;dx<=radius&&goal==null;dx++)for(int dz=-radius;dz<=radius;dz++){var candidate=evaluator.closest(routeTarget.add(dx,0,dz));if(candidate!=null&&NativeTraversalEvaluator.point(candidate).distanceToSqr(p.position())>4){goal=candidate;break;}}}
            if(start==null||goal==null){reason=evaluator.encounteredUnloaded()?"WAITING_CHUNKS":"INVALID_TARGET";if(evaluator.encounteredUnloaded())retry.waitUntil(tick+20);else {retry.failed(tick,4,20);if(start!=null&&recovery.request(p,target,"INVALID_APPROACH_TARGET"))status="RECOVERING";}return null;}
            if(goal.x()==(int)Math.floor(target.x)&&goal.z()==(int)Math.floor(target.z))target=new Vec3(target.x,goal.y(),target.z);
            var budget=NativeNavigationBudget.get(p.level().getServer());int reserved=budget.claim(budgetId,tick);
            long began=System.nanoTime();var direct=reserved>0?evaluator.direct(start,goal,budget::timeAvailable):null;
            totalSearchNanos+=System.nanoTime()-began;maxSliceNanos=Math.max(maxSliceNanos,System.nanoTime()-began);
            if(direct!=null&&!direct.isEmpty()){steps=direct;index=0;directPlans++;retry.reset(tick);reason="DIRECT_CHECKED_ROUTE";}
            else {final Node destination=goal;search=new SurfacePathfinder.Search(start,destination::equals,n->Math.hypot(n.x()-destination.x(),n.z()-destination.z())+Math.abs(n.y()-destination.y())*.12,evaluator);searchStarted=tick;}
        }
        if(search!=null){
            if(!evaluator.current()){search=null;retry.waitUntil(tick+1);reason="SEARCH_STALE";return null;}
            var budget=NativeNavigationBudget.get(p.level().getServer());int count=budget.claim(budgetId,tick);if(count==0){reason="BUDGET_EXHAUSTED";return null;}evaluator.beginSlice();long began=System.nanoTime();var result=search.advance(count,budget::timeAvailable);long elapsed=System.nanoTime()-began;totalSearchNanos+=elapsed;maxSliceNanos=Math.max(maxSliceNanos,elapsed);searchSlices++;expanded=result.expanded();reason=result.status().name();
            if(result.status()==Status.FOUND){steps=result.steps();index=0;search=null;retry.reset(tick);if(steps.isEmpty()){var here=evaluator.closest(p.position());if(here!=null){boolean wet=evaluator.water(here),crouch=!wet&&NativeSurfaceNavigation.requiresSneaking(p,target);steps=List.of(new PathStep(here,here,wet?Action.SWIM:crouch?Action.CROUCH:Action.WALK,wet?Posture.SWIMMING:crouch?Posture.CROUCHING:Posture.STANDING,1));}else return null;}}
            else if(result.status()!=Status.BUDGET_EXHAUSTED){search=null;if(result.status()==Status.SEARCH_LIMIT){stop("SEARCH_LIMIT");return null;}if(evaluator.encounteredUnloaded()){reason="WAITING_CHUNKS";retry.waitUntil(tick+20);return null;}retry.failed(tick,4,20);if(result.status()==Status.NO_PATH&&recovery.request(p,target,"NO_ORDINARY_PATH")){status="RECOVERING";}else if(result.status()==Status.NO_PATH&&retry.failures()>=3)stop("UNREACHABLE");return null;}
            else return null;
        }
        if(index>=steps.size())return null;var step=steps.get(index);var check=new NativeTraversalEvaluator(p);
        var pose=step.posture()==Posture.SWIMMING?net.minecraft.world.entity.Pose.SWIMMING:step.posture()==Posture.CROUCHING?net.minecraft.world.entity.Pose.CROUCHING:net.minecraft.world.entity.Pose.STANDING;
        if(!step.from().equals(step.to())&&check.transition(step.from(),step.to())==null||!check.clear(waypoint(step),pose,check.water(step.from())||check.water(step.to()))){steps=List.of();index=0;reason=check.encounteredUnloaded()?"WAITING_CHUNKS":"ROUTE_CHANGED";retry.waitUntil(tick+5);return null;}
        return step;
    }
}
