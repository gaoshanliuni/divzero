package dev.mineagent.runtime.neoforge.skill;

import dev.mineagent.runtime.core.task.SurfacePathfinder.*;
import dev.mineagent.runtime.neoforge.body.*;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/** Bounded multi-exit exploration. Score whole routes and onward space, not only endpoints. */
final class CombatPositioning {
    record Route(Node node,List<PathStep> steps,double worstRisk){}
    private NativeTraversalEvaluator evaluator;private final Deque<Route> open=new ArrayDeque<>();private final Set<Node> seen=new HashSet<>();private final Set<Node> scored=new HashSet<>();private final List<Route> candidates=new ArrayList<>();
    private boolean legacy;
    private Vec3 origin;private int started;private String purpose="";private Vec3 selected;private double selectedDistance;private int candidateCursor;
    private Vec3 waypoint,heading,targetAtWaypoint;private Node waypointNode;private int waypointAt;private double waypointRisk;private String waypointPurpose="";
    private List<PathStep> chosenRoute=List.of();private final Map<Node,Integer> exposure=new HashMap<>();
    private PathStep attackExit;private int attackExitAt=-10000;private Object attackExitLevel;private java.util.UUID attackExitTarget;
    private boolean quickRetreat;
    boolean quickRetreat(){return quickRetreat;}
    /** Replan a short, physically traversable dodge against every concurrent damage channel. */
    Vec3 evadeStep(SkillWork work){
        var actor=work.player();if(!actor.onGround())return null;
        var check=new NativeTraversalEvaluator(actor);check.beginSlice();var current=check.closest(actor.position());if(current==null)return null;
        double speed=Math.max(.08,actor.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED)*2.2);
        double standing=work.combat.attacks.standingRisk(work,actor.position(),12);if(standing<=0)return null;
        double best=Double.POSITIVE_INFINITY;List<PathStep> route=null;Vec3 chosen=null;double[] features=null;var model=LocalPolicyRuntime.snapshot(actor);
        var owner=work.combat.protectedEntity;
        for(var first:check.neighbors(current)){
            if(!Set.of(Action.WALK,Action.STEP_UP,Action.CROUCH,Action.DROP).contains(first.action())||Math.abs(first.to().y()-current.y())>1.25||edgeExposure(work,first.to(),check)>0)continue;
            var paths=new ArrayList<List<PathStep>>();paths.add(List.of(first));
            for(var second:check.neighbors(first.to()))if(!second.to().equals(current)&&Set.of(Action.WALK,Action.CROUCH,Action.STEP_UP).contains(second.action())&&Math.abs(second.to().y()-current.y())<=1.25&&edgeExposure(work,second.to(),check)==0)paths.add(List.of(first,second));
            for(var path:paths){
                var end=NativeTraversalEvaluator.point(path.getLast().to());
                if(owner!=null&&owner!=actor&&owner!=work.combat.selected&&end.distanceTo(owner.position())<actor.distanceTo(owner)-.5)continue;
                if(check.neighbors(path.getLast().to()).stream().noneMatch(next->!next.to().equals(path.getLast().from())&&Math.abs(next.to().y()-path.getLast().to().y())<=1.25))continue;
                int elapsed=0;double worst=0,total=0;Vec3 previous=actor.position();
                for(var edge:path){var start=previous;var destination=NativeTraversalEvaluator.point(edge.to());int duration=Math.max(1,(int)Math.ceil(start.distanceTo(destination)/speed));
                    for(int t=1;t<=duration&&elapsed+t<=NativeAttackTimeline.HORIZON;t++){var at=start.lerp(destination,t/(double)duration);double damage=work.combat.attacks.risk(work,previous,at,elapsed+t);worst=Math.max(worst,damage);total+=damage;previous=at;}
                    elapsed+=duration;
                }
                for(int t=elapsed+1;t<=Math.min(NativeAttackTimeline.HORIZON,elapsed+5);t++){double damage=work.combat.attacks.risk(work,end,end,t);worst=Math.max(worst,damage);total+=damage;}
                if(worst>=standing||elapsed>NativeAttackTimeline.HORIZON)continue;
                double danger=work.combat.collisionRisk(work,end,Math.min(18,elapsed));var direction=end.subtract(actor.position()).multiply(1,0,1).normalize();
                var target=work.combat.selected;double distance=target==null?0:actor.distanceTo(target),progress=target==null?0:distance-end.distanceTo(target.position());
                var candidate=LocalPolicyRuntime.features(actor,distance,progress,total+danger,path.size(),end.subtract(actor.position()),0,actor.getAttackStrengthScale(.5f)>=.95,work.session.spec().kind().ordinal(),0);
                double score=worst*4+total+danger*1.5+elapsed*.4+(heading==null?0:(1-heading.dot(direction))*2)+(model==null?0:LocalPolicyRuntime.cost(actor,model,candidate)*8);
                if(score<best){best=score;route=path;chosen=end;features=candidate;}
            }
        }
        if(chosen==null)return null;chosenRoute=route;selectedDistance=actor.position().distanceTo(chosen);quickRetreat=true;heading=chosen.subtract(actor.position()).multiply(1,0,1).normalize();
        if(features!=null)LocalPolicyRuntime.chose(work,features,chosen);
        work.session.add("checkedDamageEvasionRoutes",1);return chosen;
    }
    /** A short verified exit can run immediately while the wider retreat search is deferred. */
    Vec3 retreatStep(SkillWork work){
        quickRetreat=false;var p=work.player();if(!p.onGround())return null;var live=work.combat.threats.stream().filter(t->t.entity().isAlive()).toList();if(live.isEmpty())return null;
        var check=new NativeTraversalEvaluator(p);check.beginSlice();var current=check.closest(p.position());if(current==null)return null;
        Vec3 center=Vec3.ZERO;for(var threat:live)center=center.add(threat.entity().position());center=center.scale(1d/live.size());final Vec3 dangerCenter=center;
        var owner=work.runtime.server.getPlayerList().getPlayer(work.session.owner());var protectedEntity=work.combat.protectedEntity!=null?work.combat.protectedEntity:owner;
        var model=LocalPolicyRuntime.snapshot(p);double best=Double.POSITIVE_INFINITY;PathStep selected=null;double[] features=null;
        for(var edge:check.neighbors(current)){
            if(!Set.of(Action.WALK,Action.STEP_UP,Action.CROUCH,Action.DROP).contains(edge.action())||Math.abs(edge.to().y()-current.y())>1.25||!NativeHumanDuel.pvpParticipant(p)&&edgeExposure(work,edge.to(),check)>0)continue;
            var point=NativeTraversalEvaluator.point(edge.to());
            if(!NativeHumanDuel.pvpParticipant(p)&&check.neighbors(edge.to()).stream().filter(next->!next.to().equals(current)&&Math.abs(next.to().y()-edge.to().y())<=1.25&&NativeTraversalEvaluator.point(next.to()).distanceTo(dangerCenter)>=point.distanceTo(dangerCenter)-.25).count()<2)continue;
            if(protectedEntity!=null&&protectedEntity!=p&&protectedEntity!=work.combat.selected&&protectedEntity.level()==p.level()&&!(protectedEntity instanceof net.minecraft.world.entity.player.Player other&&(other.isCreative()||other.isSpectator()))&&point.distanceTo(protectedEntity.position())<p.distanceTo(protectedEntity)-.25)continue;
            var middle=p.position().lerp(point,.5);double risk=work.combat.risk(work,point),future=Math.max(work.combat.collisionRisk(work,middle,2),work.combat.collisionRisk(work,point,4));
            if(work.combat.attacks.routeRisk(work,point,4,4)>0)continue;
            double separation=point.distanceTo(center)-p.position().distanceTo(center);if(separation<.15)continue;var delta=point.subtract(p.position());
            var candidate=LocalPolicyRuntime.features(p,p.position().distanceTo(center),-separation,risk+future,1,delta,0,false,work.session.spec().kind().ordinal(),0);
            double score=risk*2+future-separation*5+(heading==null?0:(1-heading.dot(delta.multiply(1,0,1).normalize()))*1.5)+(model==null?0:LocalPolicyRuntime.cost(p,model,candidate)*8);
            if(score<best){best=score;selected=edge;features=candidate;}
        }
        if(selected==null)return null;var point=NativeTraversalEvaluator.point(selected.to());chosenRoute=List.of(selected);selectedDistance=p.position().distanceTo(point);heading=point.subtract(p.position()).multiply(1,0,1).normalize();quickRetreat=true;
        if(features!=null)LocalPolicyRuntime.chose(work,features,point);work.session.add("checkedImmediateRetreats",1);return point;
    }
    /** Progress toward a visible target using one checked local step; a distant archer must not freeze pursuit in a region search. */
    Vec3 approachStep(SkillWork work,net.minecraft.world.entity.LivingEntity target,double desiredDistance){
        quickRetreat=false;
        var player=work.player();if(!player.onGround()||!dev.mineagent.runtime.neoforge.body.NativeTargetGeometry.canObserve(player,target)||player.distanceTo(target)<=desiredDistance)return null;
        var check=new NativeTraversalEvaluator(player);check.beginSlice();var current=check.closest(player.position());if(current==null)return null;
        var intercept=work.prediction.intercept(work,target,Math.min(8,player.distanceTo(target)/.3));
        double initial=player.position().distanceTo(intercept),initialRisk=work.combat.risk(work,player.position(),target),best=Double.POSITIVE_INFINITY;PathStep chosen=null;double[] features=null;var model=LocalPolicyRuntime.snapshot(player);
        for(var edge:check.neighbors(current)){
            if(!Set.of(Action.WALK,Action.STEP_UP,Action.JUMP,Action.CROUCH,Action.DROP).contains(edge.action())||edge.from().y()-edge.to().y()>1.25||!NativeHumanDuel.pvpParticipant(p)&&edgeExposure(work,edge.to(),check)>0)continue;
            var point=NativeTraversalEvaluator.point(edge.to());double progress=initial-point.distanceTo(intercept);if(progress<.15)continue;
            var rule=work.session.spec().combat();boolean assigned=rule.area()!=null&&rule.area().contains(new dev.mineagent.runtime.core.task.SkillSpec.Point(point.x,point.y,point.z));
            if(!dev.mineagent.runtime.core.task.CombatBounds.canAdvance(assigned,point.distanceTo(work.combat.center(work)),player.position().distanceTo(work.combat.center(work)),rule.leash()))continue;
            var middle=player.position().lerp(point,.5);double risk=work.combat.risk(work,point,target),future=Math.max(work.combat.collisionRisk(work,point,4,target),work.combat.collisionRisk(work,middle,2,target));if(risk>initialRisk+8||work.combat.attacks.routeRisk(work,point,4,2)>0)continue;
            var delta=point.subtract(player.position());var candidate=LocalPolicyRuntime.features(player,player.distanceTo(target),progress,risk+future,1,delta,0,player.getAttackStrengthScale(.5f)>=.95,work.session.spec().kind().ordinal(),0);
            double score=risk+future*.6-progress*8+(heading==null?0:(1-heading.dot(delta.multiply(1,0,1).normalize()))*.8)+(model==null?0:LocalPolicyRuntime.cost(player,model,candidate)*8);
            if(score<best){best=score;chosen=edge;features=candidate;}
        }
        if(chosen==null)return null;var point=NativeTraversalEvaluator.point(chosen.to());chosenRoute=List.of(chosen);selectedDistance=player.position().distanceTo(point);heading=point.subtract(player.position()).multiply(1,0,1).normalize();
        if(features!=null)LocalPolicyRuntime.chose(work,features,point);work.session.add("checkedImmediateApproaches",1);return point;
    }
    /** Constant-size native escape check for an immediate strike; a full region search must not stall a ready attack. */
    Vec3 attackExit(SkillWork work,net.minecraft.world.entity.LivingEntity target){
        quickRetreat=false;
        var player=work.player();double enemyReach=work.combat.threats.stream().filter(t->t.entity()==target).flatMap(t->t.state().attacks().stream()).filter(a->a.kind().equals("MELEE")).mapToDouble(NativeCombatStates.Attack::maxRange).max().orElse(0);if(enemyReach>player.getAttackRangeWith(player.getMainHandItem()).effectiveMaxRange(player)+.25)return null;var owner=work.runtime.server.getPlayerList().getPlayer(work.session.owner());var check=new NativeTraversalEvaluator(player);check.beginSlice();var current=check.closest(player.position());
        if(attackExit!=null&&target.getUUID().equals(attackExitTarget)&&attackExitLevel==player.level()&&work.tick()-attackExitAt<=16
                &&NativeTraversalEvaluator.point(attackExit.from()).subtract(player.position()).horizontalDistanceSqr()<2.25
                &&Math.abs(player.getY()-attackExit.from().y())<1.6&&check.transition(attackExit.from(),attackExit.to())!=null
                &&NativeTraversalEvaluator.point(attackExit.to()).subtract(target.position()).horizontalDistanceSqr()>player.position().subtract(target.position()).horizontalDistanceSqr()+.1
                &&(owner==null||owner==player||owner==target||owner.level()!=player.level()||owner.isCreative()||owner.isSpectator()||NativeTraversalEvaluator.point(attackExit.to()).distanceTo(owner.position())>=player.distanceTo(owner)-.5)
                &&work.combat.attacks.routeRisk(work,NativeTraversalEvaluator.point(attackExit.to()),5,3)==0&&edgeExposure(work,attackExit.to(),check)==0&&work.combat.risk(work,NativeTraversalEvaluator.point(attackExit.to()))<=work.combat.risk(work,player.position())+2){
            chosenRoute=List.of(attackExit);selectedDistance=player.position().distanceTo(NativeTraversalEvaluator.point(attackExit.to()));return NativeTraversalEvaluator.point(attackExit.to());
        }
        if(!player.onGround()||current==null)return null;
        var model=LocalPolicyRuntime.snapshot(player);double best=Double.POSITIVE_INFINITY;PathStep selectedEdge=null;double[] selectedFeatures=null;double initial=player.distanceTo(target),initialRisk=work.combat.risk(work,player.position());
        for(var edge:check.neighbors(current)){
            if(!Set.of(Action.WALK,Action.CROUCH).contains(edge.action())||Math.abs(edge.to().y()-current.y())>.25||check.transition(edge.to(),current)==null||edgeExposure(work,edge.to(),check)!=0)continue;
            var point=NativeTraversalEvaluator.point(edge.to());if(point.distanceTo(target.position())<initial+.15)continue;
            if(check.neighbors(edge.to()).stream().noneMatch(next->!next.to().equals(current)&&Math.abs(next.to().y()-edge.to().y())<=1.25))continue;
            if(owner!=null&&owner!=player&&owner!=target&&owner.level()==player.level()&&!owner.isCreative()&&!owner.isSpectator()&&point.distanceTo(owner.position())<player.distanceTo(owner)-.5)continue;
            double risk=work.combat.risk(work,point);if(risk>initialRisk+2||work.combat.attacks.routeRisk(work,point,5,3)>0)continue;double future=0;
            for(var threat:work.combat.threats)if(threat.entity().isAlive()&&NativeCombatStates.meleeAtAfter(threat.entity(),player,point,4))future+=threat.entity()==target?6:18;
            var delta=point.subtract(player.position());var features=LocalPolicyRuntime.features(player,initial,initial-point.distanceTo(target.position()),risk+future,1,delta,0,true,work.session.spec().kind().ordinal(),0);
            double score=risk*2+future-(point.distanceTo(target.position())-initial)*2+(model==null?0:LocalPolicyRuntime.cost(player,model,features)*8);
            if(score<best){best=score;selectedEdge=edge;selectedFeatures=features;}
        }
        if(selectedEdge==null)return null;attackExit=selectedEdge;attackExitAt=work.tick();attackExitLevel=player.level();attackExitTarget=target.getUUID();chosenRoute=List.of(selectedEdge);selectedDistance=player.position().distanceTo(NativeTraversalEvaluator.point(selectedEdge.to()));
        if(selectedFeatures!=null)LocalPolicyRuntime.chose(work,selectedFeatures,NativeTraversalEvaluator.point(selectedEdge.to()));work.session.add("checkedImmediateAttackExits",1);return NativeTraversalEvaluator.point(selectedEdge.to());
    }
    List<PathStep> route(){return chosenRoute;}
    boolean pending(){return !open.isEmpty()||!legacy&&scored.size()<candidates.size();}
    boolean longRetreat(){return selectedDistance>3;}
    Vec3 choose(SkillWork w,String intent,double desiredDistance){
        quickRetreat=false;
        legacy=w.legacyBaseline();exposure.clear();boolean withdrawal=Set.of("RETREAT","RECOVER","LURE","SPACE","SIDE_LEFT","SIDE_RIGHT","JUMP_TAP").contains(intent);
        var actor=w.player();boolean jumping=w.tick()-w.lastTacticalJump<=20&&!actor.onGround()&&actor.getY()-w.tacticalJumpY>=0&&actor.getY()-w.tacticalJumpY<1.6;var feet=jumping?new Vec3(actor.getX(),w.tacticalJumpY,actor.getZ()):actor.position();
        if(waypoint!=null){
            var p=w.player();boolean reached=(p.position().subtract(waypoint).horizontalDistanceSqr()<.10||heading!=null&&p.position().subtract(waypoint).dot(heading)>.12)&&Math.abs(feet.y-waypoint.y)<.5;
            boolean targetMoved=w.combat.selected!=null&&targetAtWaypoint!=null&&w.combat.selected.position().distanceToSqr(targetAtWaypoint)>4;
            evaluator.beginSlice();var current=evaluator.closest(feet);
            boolean traversable=current!=null&&(current.equals(waypointNode)||evaluator.neighbors(current).stream().anyMatch(edge->edge.to().equals(waypointNode)));
            boolean safe=traversable&&edgeExposure(w,waypointNode)==0&&w.combat.risk(w,waypoint)<=Math.max(waypointRisk+2,w.combat.risk(w,p.position())+1);
            if(!reached&&intent.equals(waypointPurpose)&&!targetMoved&&w.tick()-waypointAt<24&&safe)return waypoint;
            if(!reached)w.session.add("tacticalWaypointRechecks",1);
            waypoint=null;origin=null;chosenRoute=List.of();
        }
        if(origin==null||w.tick()-started>(legacy?8:24)||origin.distanceToSqr(w.player().position())>1||!purpose.equals(intent)){
            evaluator=new NativeTraversalEvaluator(w.player());origin=feet;started=w.tick();purpose=intent;selected=null;open.clear();seen.clear();scored.clear();candidates.clear();var node=evaluator.closest(origin);if(node!=null){seen.add(node);open.add(new Route(node,List.of(),w.combat.risk(w,origin)));}
        }
        var budget=NativeNavigationBudget.get(w.runtime.server);int allowed=budget.claim(w.token(),w.tick());evaluator.beginSlice();
        for(int i=0;i<allowed&&!open.isEmpty()&&budget.timeAvailable();i++){
            var route=open.removeFirst();var point=NativeTraversalEvaluator.point(route.node);if(route.steps.size()>=1&&evaluator.neighbors(route.node).stream().filter(edge->!route.steps.stream().anyMatch(step->step.from().equals(edge.to()))).count()>=1)candidates.add(route);
            if(route.steps.size()>=7)continue;
            for(var edge:evaluator.neighbors(route.node)){
                var next=NativeTraversalEvaluator.point(edge.to());if((!NativeHumanDuel.pvpParticipant(w.player())&&Math.abs(next.y-origin.y)>1.25)||next.distanceToSqr(origin)>49||!seen.add(edge.to()))continue;
                if(!NativeHumanDuel.pvpParticipant(w.player())&&edge.action()==Action.DROP&&(edge.from().y()-edge.to().y()>1.25||evaluator.transition(edge.to(),edge.from())==null))continue;
                var policy=w.session.spec().combat();boolean assigned=policy.area()!=null&&policy.area().contains(new dev.mineagent.runtime.core.task.SkillSpec.Point(next.x,next.y,next.z));
                if(!withdrawal&&!dev.mineagent.runtime.core.task.CombatBounds.canAdvance(assigned,next.distanceTo(w.combat.center(w)),origin.distanceTo(w.combat.center(w)),policy.leash()))continue;
                var steps=new ArrayList<>(route.steps);steps.add(edge);open.add(new Route(edge.to(),List.copyOf(steps),Math.max(route.worstRisk,w.combat.risk(w,next))));
            }
        }
        double best=Double.POSITIVE_INFINITY;double[] selectedFeatures=null;var localPolicy=legacy?null:LocalPolicyRuntime.snapshot(w.player());var target=w.combat.selected;
        var intercept=target==null?null:legacy?target.position():w.prediction.intercept(w,target,Math.min(12,origin.distanceTo(target.position())/Math.max(.15,w.player().getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED)*2.4)));

        // A ready strike necessarily enters the selected opponent's reach. Price that exposure,
        // but keep full risk for every other threat and keep all terrain/escape checks.
        var opportunity=(intent.equals("APPROACH")||intent.equals("COUNTER"))&&w.player().getAttackStrengthScale(.5f)>=.95f&&w.player().getHealth()>w.player().getMaxHealth()*.45&&!w.combat.incoming(w)?target:null;
        for(int index=0;index<candidates.size()&&budget.timeAvailable();index++){var route=candidates.get(Math.floorMod(candidateCursor++,candidates.size()));scored.add(route.node);var point=NativeTraversalEvaluator.point(route.node);double risk=w.combat.risk(w,point,opportunity);double routeRisk=route.steps.stream().mapToDouble(step->w.combat.risk(w,NativeTraversalEvaluator.point(step.to()),opportunity)).max().orElse(risk);double score=risk*2+routeRisk*.6+route.steps.size()*.15;
            score+=w.combat.collisionRisk(w,point,4,opportunity)*1.5+(legacy?0:w.prediction.routeRisk(w,route.steps,opportunity));
            if(target!=null&&(intent.equals("SIDE_LEFT")||intent.equals("SIDE_RIGHT"))){
                var forward=target.position().subtract(origin).multiply(1,0,1).normalize();var left=new Vec3(forward.z,0,-forward.x);var delta=point.subtract(origin);
                double lateral=delta.dot(left)*(intent.equals("SIDE_LEFT")?1:-1);
                if(lateral<.65||delta.horizontalDistanceSqr()>12||delta.dot(forward)>.7)continue;
                score+=Math.abs(lateral-1.8)*5;
            }
            if(intent.equals("JUMP_TAP")&&route.steps.size()<3)continue;
            // Avoid being knocked off an edge, even when a little melee damage is the cheaper exit.
            score+=route.steps.stream().mapToInt(step->edgeExposure(w,step.to())).max().orElse(0)*10000;
            double damage=0;for(var threat:w.combat.threats)if(threat.entity().isAlive()&&route.steps.stream().anyMatch(step->NativeCombatStates.meleeAt(threat.entity(),w.player(),NativeTraversalEvaluator.point(step.to())))){var attribute=threat.entity().getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE);damage+=legacy?(attribute==null?4:Math.max(0,attribute.getValue())):estimatedMeleeDamage(w.player(),threat.entity(),attribute==null?4:Math.max(0,attribute.getValue()));}
            double health=Math.max(1,w.player().getHealth()+w.player().getAbsorptionAmount());score+=damage/health*25;if(damage>=health)score+=1000;
            if(!NativeHumanDuel.pvpParticipant(w.player())&&edgeExposure(w,route.node)>0)continue;
            var onwards=evaluator.neighbors(route.node).stream().filter(edge->!route.steps.stream().anyMatch(step->step.from().equals(edge.to()))).toList();
            score+=Math.max(0,3-onwards.size())*2;
            int trapRisk=0;if(!legacy&&withdrawal&&target!=null){double distanceHere=point.distanceTo(target.position());long exits=onwards.stream().filter(edge->Math.abs(edge.to().y()-route.node.y())<=1.25&&NativeTraversalEvaluator.point(edge.to()).distanceTo(target.position())>=distanceHere-.1).count();trapRisk=exits==0?4:exits==1?2:0;score+=trapRisk*8;}

            if(onwards.stream().noneMatch(edge->evaluator.neighbors(edge.to()).stream().anyMatch(next->!next.to().equals(route.node)&&route.steps.stream().noneMatch(step->step.from().equals(next.to())))))score+=100;
            if(target!=null)score+=Math.abs(point.distanceTo(intent.equals("APPROACH")||intent.equals("COUNTER")?intercept:target.position())-desiredDistance)*(intent.equals("COUNTER")?14:intent.equals("SPACE")||intent.equals("APPROACH")?8:1.1);
            if(target!=null&&(intent.equals("COUNTER")||intent.equals("APPROACH"))&&origin.distanceTo(target.position())>desiredDistance+.25&&point.distanceTo(target.position())>=origin.distanceTo(target.position())-.15)continue;
            if(intent.equals("RETREAT")||intent.equals("RECOVER")||intent.equals("LURE")||intent.equals("JUMP_TAP")){
                score-=origin.distanceTo(point)*.45;
                if(w.combat.protectedEntity!=null&&point.distanceTo(w.combat.protectedEntity.position())<origin.distanceTo(w.combat.protectedEntity.position())-.5)score+=100;
                var owner=w.runtime.server.getPlayerList().getPlayer(w.session.owner());if(owner!=null&&owner!=w.player()&&owner.level()==w.player().level()&&(legacy||!owner.isCreative()&&!owner.isSpectator()&&point.distanceTo(owner.position())<w.session.spec().combat().awareness()+6)&&point.distanceTo(owner.position())<origin.distanceTo(owner.position())-.5)score+=100;
                var area=w.session.spec().area();if(w.session.spec().kind()==dev.mineagent.runtime.core.task.SkillSpec.Kind.GUARD&&area!=null){var center=new Vec3((area.min().x()+area.max().x())/2,(area.min().y()+area.max().y())/2,(area.min().z()+area.max().z())/2);if(point.distanceToSqr(center)<origin.distanceToSqr(center)-1)score+=30;}
            }
            if(target!=null&&intent.equals("LURE")){var a=origin.subtract(target.position()).normalize();var b=point.subtract(target.position()).normalize();score-=Math.abs(a.x*b.z-a.z*b.x)*2;}
            // Execute the first checked edge; do not hand an endpoint to a different route search.
            var next=NativeTraversalEvaluator.point(route.steps.getFirst().to());var direction=new Vec3(next.x-origin.x,0,next.z-origin.z).normalize();
            if(heading!=null)score+=(1-heading.dot(direction))*.8;
            double targetDistance=target==null?0:origin.distanceTo(target.position()),progress=target==null?origin.distanceTo(point):targetDistance-point.distanceTo(target.position());
            var features=LocalPolicyRuntime.features(w.player(),targetDistance,progress,risk+routeRisk,route.steps.size(),point.subtract(origin),edgeExposure(w,route.node)+trapRisk,opportunity!=null,w.session.spec().kind().ordinal(),0);
            if(localPolicy!=null)score+=LocalPolicyRuntime.cost(w.player(),localPolicy,features)*8;
            if(score<best){selectedFeatures=features;best=score;selectedDistance=origin.distanceTo(point);selected=next;waypointNode=route.steps.getFirst().to();chosenRoute=route.steps;}
        }
        if(selected!=null){if(!legacy&&selectedFeatures!=null)LocalPolicyRuntime.chose(w,selectedFeatures,selected);waypoint=selected;waypointAt=w.tick();waypointPurpose=intent;waypointRisk=w.combat.risk(w,waypoint);targetAtWaypoint=target==null?null:target.position();heading=new Vec3(waypoint.x-w.player().getX(),0,waypoint.z-w.player().getZ()).normalize();w.session.add("tacticalWaypoints",1);}
        return selected;
    }
    private static double estimatedMeleeDamage(net.minecraft.server.level.ServerPlayer defender,net.minecraft.world.entity.LivingEntity attacker,double raw){
        var type=net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(attacker.getType());var item=attacker.getMainHandItem();
        if(!type.getNamespace().equals("minecraft")||item.isEnchanted()||!net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item.getItem()).getNamespace().equals("minecraft"))return Math.max(1,raw);
        if(attacker instanceof net.minecraft.world.entity.player.Player player&&((dev.mineagent.runtime.neoforge.mixin.CombatCriticalAccess)player).divzero$canCriticalAttack(defender))raw*=1.5;
        return Math.max(.5,dev.mineagent.runtime.core.task.CombatDamageEstimate.afterArmor(raw,defender.getArmorValue(),defender.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.ARMOR_TOUGHNESS)));
    }
    private int edgeExposure(SkillWork w,Node node){return exposure.computeIfAbsent(node,n->edgeExposure(w,n,evaluator));}
    private int edgeExposure(SkillWork w,Node node,NativeTraversalEvaluator check){int danger=0;for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++){if(dx==0&&dz==0)continue;int x=node.x()+dx,z=node.z()+dz;var at=new Vec3(x+.5,node.y(),z+.5);var pos=net.minecraft.core.BlockPos.containing(at);if(!check.loaded(pos)){danger++;continue;}if(w.player().level().getFluidState(pos).is(net.minecraft.tags.FluidTags.LAVA)){danger++;continue;}if(!check.clear(at,net.minecraft.world.entity.Pose.STANDING,true)&&!check.clear(at,net.minecraft.world.entity.Pose.CROUCHING,true))continue;if(check.positions(x,z,node.y()).stream().noneMatch(floor->Math.abs(floor.y()-node.y())<=1.25))danger++;}return danger;}
    double sideRisk(SkillWork w,int side){
        var target=w.combat.selected;if(target==null)return Double.POSITIVE_INFINITY;
        var origin=w.player().position();var forward=target.position().subtract(origin).multiply(1,0,1).normalize();var lateral=new Vec3(forward.z,0,-forward.x).scale(side);
        if(evaluator==null)evaluator=new NativeTraversalEvaluator(w.player());evaluator.beginSlice();double risk=0;
        for(int i=1;i<=3;i++){
            var point=origin.add(lateral.scale(i*.55)).subtract(forward.scale(.2*i));var node=evaluator.closest(point);
            if(node==null||Math.abs(NativeTraversalEvaluator.point(node).y-origin.y)>.5||edgeExposure(w,node)>0||!w.player().level().noCollision(w.player(),w.player().getBoundingBox().move(point.subtract(origin))))return Double.POSITIVE_INFINITY;
            risk=Math.max(risk,w.combat.collisionRisk(w,point,i)+w.combat.risk(w,point)*.5);
        }return risk;
    }
    boolean jumpTapSafe(SkillWork w){
        var p=w.player();if(!jumpSafe(w)||p.getAbilities().flying||p.isFallFlying()||p.hasEffect(net.minecraft.world.effect.MobEffects.LEVITATION)||p.hasEffect(net.minecraft.world.effect.MobEffects.SLOW_FALLING)||w.combat.incoming(w))return false;
        double vertical=((dev.mineagent.runtime.neoforge.mixin.CombatJumpAccess)p).divzero$jumpPower(),gravity=p.getGravity();
        if(vertical<=0||vertical>.44||gravity<=0)return false;
        var start=p.position();var direction=NativeTraversalEvaluator.point(chosenRoute.get(2).to()).subtract(start).multiply(1,0,1).normalize();
        double speed=Math.min(.36,p.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED)*2.4);
        var momentum=p.getDeltaMovement().multiply(1,0,1);if(momentum.horizontalDistanceSqr()>.36)return false;
        var drift=Vec3.ZERO;double height=0;double startingRisk=w.combat.collisionRisk(w,start,0);
        for(int tick=1;tick<=20;tick++){
            height+=vertical;vertical=(vertical-gravity)*.98;
            drift=drift.add(momentum);momentum=momentum.scale(.91);
            var point=start.add(direction.scale(speed*tick)).add(drift).add(0,Math.max(0,height),0);
            if(!p.level().hasChunkAt(net.minecraft.core.BlockPos.containing(point))||!p.level().noCollision(p,p.getBoundingBox().move(point.subtract(start))))return false;
            // Jumping does not grant immunity: evaluate the same native melee/projectile boxes along the arc.
            if(w.combat.collisionRisk(w,point,tick)>Math.max(2,startingRisk)&&tick>=3)return false;
            if(height<=0){var floor=evaluator.closest(point);return floor!=null&&Math.abs(NativeTraversalEvaluator.point(floor).y-start.y)<.25&&edgeExposure(w,floor)==0&&w.combat.collisionRisk(w,point,4)<=2;}
        }return false;
    }
    boolean jumpSafe(SkillWork w){
        var p=w.player();boolean supported=p.onGround()||p.getDeltaMovement().y<=.01&&p.level().noCollision(p,p.getBoundingBox())&&!p.level().noCollision(p,p.getBoundingBox().move(0,-.04,0));if(!supported||p.isInWater()||p.isCrouching()||chosenRoute.size()<3)return false;
        var direction=NativeTraversalEvaluator.point(chosenRoute.get(2).to()).subtract(p.position());
        if(Math.abs(direction.y)>.1||direction.horizontalDistanceSqr()<3)return false;
        var unit=new Vec3(direction.x,0,direction.z).normalize();
        for(var step:chosenRoute.subList(0,3)){var d=NativeTraversalEvaluator.point(step.to()).subtract(NativeTraversalEvaluator.point(step.from()));if(Math.abs(d.y)>.1||new Vec3(d.x,0,d.z).normalize().dot(unit)<.9||edgeExposure(w,step.to())>0)return false;}
        return p.level().noCollision(p,p.getBoundingBox().move(0,1.3,0).expandTowards(unit.scale(2.5)));
    }
    void reset(){quickRetreat=false;attackExit=null;attackExitLevel=null;attackExitTarget=null;origin=null;selected=null;waypoint=null;waypointNode=null;heading=null;targetAtWaypoint=null;chosenRoute=List.of();exposure.clear();}
}
