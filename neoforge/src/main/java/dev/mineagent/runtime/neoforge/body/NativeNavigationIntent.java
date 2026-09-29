package dev.mineagent.runtime.neoforge.body;

import dev.mineagent.runtime.core.task.*;
import dev.mineagent.runtime.core.task.SurfacePathfinder.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/** One navigation intent, usable by an AI body or the real-player input adapter. */
public final class NativeNavigationIntent {
    private final UUID budgetId=UUID.randomUUID();private final NavigationRetry retry=new NavigationRetry();
    private Vec3 target,plannedTarget,lastPosition;private Object level;private NativeTraversalEvaluator evaluator;private SurfacePathfinder.Search search;
    private List<PathStep> steps=List.of();private int index,stuck,searchStarted,plans,expanded;private double tolerance=.8;private String status="IDLE",reason="";
    public void start(Vec3 target,double tolerance){if(target==null||!Double.isFinite(target.x)||!Double.isFinite(target.y)||!Double.isFinite(target.z))throw new IllegalArgumentException("NAVIGATION_TARGET");this.target=target;this.tolerance=tolerance;plannedTarget=null;lastPosition=null;steps=List.of();index=stuck=plans=expanded=0;search=null;level=null;retry.reset(0);status="MOVING";reason="";}
    public void update(Vec3 at){if(target==null){start(at,tolerance);return;}if(!Double.isFinite(at.x)||!Double.isFinite(at.y)||!Double.isFinite(at.z))throw new IllegalArgumentException("NAVIGATION_TARGET");target=at;}
    public void stop(String status){target=null;steps=List.of();search=null;index=0;this.status=status;}
    public Optional<Vec3> target(){return Optional.ofNullable(target);}
    public String status(){return status;}
    public String reason(){return reason;}
    public double tolerance(){return tolerance;}
    public List<PathStep> remaining(){return steps.subList(Math.min(index,steps.size()),steps.size());}
    public Map<String,Object> evidence(){return Map.of("plans",plans,"expanded",expanded,"reason",reason,"nextSearchTick",retry.next(),"failedSearches",retry.failures());}
    public PathStep tick(ServerPlayer p){
        if(target==null)return null;int tick=p.level().getServer().getTickCount();
        if(level!=null&&level!=p.level()){stop("DIMENSION_CHANGED");return null;}level=p.level();
        var offset=target.subtract(p.position());if(offset.horizontalDistanceSqr()<tolerance*tolerance&&Math.abs(offset.y)<.26){stop("ARRIVED");return null;}
        if(lastPosition==null||lastPosition.distanceToSqr(p.position())>=.01){lastPosition=p.position();stuck=0;}else if(!remaining().isEmpty())stuck++;
        boolean moved=plannedTarget!=null&&plannedTarget.distanceToSqr(target)>2.25;
        if(moved||stuck>=30){search=null;steps=List.of();index=0;stuck=0;}
        while(index<steps.size()){var next=NativeTraversalEvaluator.point(steps.get(index).to());if(next.subtract(p.position()).horizontalDistanceSqr()<Math.min(.1,tolerance*tolerance)&&Math.abs(next.y-p.getY())<.26)index++;else break;}
        if(index>=steps.size()&&search==null&&retry.ready(tick)){
            evaluator=new NativeTraversalEvaluator(p);Node start=evaluator.closest(p.position());Vec3 routeTarget=target;if(target.distanceToSqr(p.position())>128*128)routeTarget=p.position().add(target.subtract(p.position()).normalize().scale(128));Node goal=evaluator.closest(routeTarget);plans++;plannedTarget=target;
            if(start==null||goal==null){reason=evaluator.encounteredUnloaded()?"WAITING_CHUNKS":"INVALID_TARGET";retry.failed(tick);return null;}
            if(goal.x()==(int)Math.floor(target.x)&&goal.z()==(int)Math.floor(target.z))target=new Vec3(target.x,goal.y(),target.z);
            search=new SurfacePathfinder.Search(start,goal,evaluator);searchStarted=tick;
        }
        if(search!=null){
            if(!evaluator.current()){search=null;retry.waitUntil(tick+1);reason="SEARCH_STALE";return null;}
            var budget=NativeNavigationBudget.get(p.level().getServer());int count=budget.claim(budgetId,tick);if(count==0){reason="BUDGET_EXHAUSTED";return null;}evaluator.beginSlice();var result=search.advance(count,budget::timeAvailable);expanded=result.expanded();reason=result.status().name();
            if(result.status()==Status.FOUND){steps=result.steps();index=0;search=null;retry.succeeded(tick);if(steps.isEmpty())return null;}
            else if(result.status()!=Status.BUDGET_EXHAUSTED){search=null;retry.failed(tick);if(result.status()==Status.NO_PATH&&retry.failures()>=3)stop("UNREACHABLE");return null;}
            else return null;
        }
        if(index>=steps.size())return null;var step=steps.get(index);var check=new NativeTraversalEvaluator(p);
        if(check.transition(step.from(),step.to())==null){steps=List.of();index=0;reason=check.encounteredUnloaded()?"WAITING_CHUNKS":"ROUTE_CHANGED";retry.waitUntil(tick+5);return null;}
        return step;
    }
}
