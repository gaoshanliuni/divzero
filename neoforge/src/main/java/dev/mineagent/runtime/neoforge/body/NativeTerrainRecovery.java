package dev.mineagent.runtime.neoforge.body;

import dev.mineagent.runtime.core.task.TerrainPathSearch;
import dev.mineagent.runtime.core.task.SurfacePathfinder.*;
import net.minecraft.core.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.*;
import java.util.*;

/** One short native edit/movement, then normal routing rechecks the original goal. Shared by both bodies. */
public final class NativeTerrainRecovery {
    public interface Actions {
        boolean current();boolean select(int slot);void aim(Vec3 point);void jump(UUID operation);
        void mine(UUID operation,BlockPos at);void place(UUID operation,BlockHitResult hit);
        void cancel(UUID operation);
    }
    public record Tick(boolean busy,PathStep move,boolean changed){}
    private TerrainPathSearch search;
    private TerrainPathSearch.Step planned;
    private ServerPlayer player;
    private Object level;
    private Vec3 goal,origin;
    private TerrainPathSearch.Edit edit;
    private Actions actions;
    private UUID operation;
    private final Set<String> rejected=new HashSet<>();
    private String state="IDLE",reason="";
    private int started,at,settled,changes,expanded,attempts;
    private boolean sent,jumped;private double[] decisionFeatures;private float healthBefore;
    private String before="";
    public boolean active(){return search!=null||planned!=null;}
    public String state(){return state;}
    public Map<String,Object> evidence(){return Map.of("state",state,"reason",reason,"changes",changes,"attempts",attempts,"expanded",expanded,"operation",operation==null?"":operation.toString(),"sourcePolicy","UNKNOWN_NATURAL_MATERIALS_IN_LOCAL_AUTHORIZED_CORRIDOR");}
    public void cancel(){if(actions!=null&&operation!=null)actions.cancel(operation);search=null;planned=null;operation=null;edit=null;actions=null;state="IDLE";}
    public boolean request(ServerPlayer p,Vec3 target,String cause){
        if(active())return true;
        if(!NativeTerrainPolicy.allowed(p)||!p.onGround()||Math.abs(p.getY()-Math.rint(p.getY()))>.06)return false;
        if(player!=p||level!=p.level()||goal==null||goal.distanceToSqr(target)>4){rejected.clear();attempts=changes=0;}
        player=p;level=p.level();goal=target;origin=p.position();started=at=p.level().getServer().getTickCount();reason=cause;state="SEARCHING_ESCAPE";
        var originCell=cell(BlockPos.containing(origin));
        var nearby=p.level().getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class,p.getBoundingBox().inflate(12),e->e!=p&&e.isAlive()&&(e instanceof net.minecraft.world.entity.monster.Enemy||e==p.getLastHurtByMob()));
        var risks=new HashMap<TerrainPathSearch.Cell,Double>();
        var policy=dev.mineagent.runtime.neoforge.skill.LocalPolicyRuntime.snapshot(p);
        var world=new TerrainPathSearch.World(){
            public double learnedCost(TerrainPathSearch.Step step,double risk){var delta=new Vec3(step.to().x()-step.from().x(),step.to().y()-step.from().y(),step.to().z()-step.from().z());var features=dev.mineagent.runtime.neoforge.skill.LocalPolicyRuntime.features(p,origin.distanceTo(target),delta.length(),risk,1,delta,0,false,7,step.edits().stream().filter(e->e.kind()==TerrainPathSearch.Kind.PLACE).count());return policy==null?0:policy.cost(features)*5;}

            public TerrainPathSearch.Block block(TerrainPathSearch.Cell c){
                var pos=pos(c);if(!NativeTerrainPolicy.loaded(p,pos))return new TerrainPathSearch.Block(false,false,false,false,0,"unloaded");
                var value=p.level().getBlockState(pos);String signature=signature(pos);
                boolean fluid=!value.getFluidState().isEmpty(),hazard=value.is(net.minecraft.world.level.block.Blocks.FIRE)||value.is(net.minecraft.world.level.block.Blocks.SOUL_FIRE)||value.is(net.minecraft.world.level.block.Blocks.POWDER_SNOW)||value.is(net.minecraft.world.level.block.Blocks.SWEET_BERRY_BUSH)||value.is(net.minecraft.world.level.block.Blocks.WITHER_ROSE)||value.is(net.minecraft.world.level.block.Blocks.CACTUS);boolean clear=!fluid&&!hazard&&value.getCollisionShape(p.level(),pos).isEmpty();
                boolean support=!fluid&&!hazard&&!value.is(net.minecraft.world.level.block.Blocks.MAGMA_BLOCK)&&value.getCollisionShape(p.level(),pos).toAabbs().stream().anyMatch(box->box.maxY>=.875&&box.minX<=.2&&box.maxX>=.8&&box.minZ<=.2&&box.maxZ>=.8);
                return new TerrainPathSearch.Block(true,clear,support,!rejected.contains(signature)&&NativeTerrainPolicy.mayBreak(p,pos),NativeTerrainPolicy.breakTicks(p,pos),signature);
            }
            public boolean canPlace(TerrainPathSearch.Cell c){return !rejected.contains(signature(pos(c)))&&NativeTerrainPolicy.mayPlace(p,pos(c));}
            public boolean exit(TerrainPathSearch.Cell c,Map<TerrainPathSearch.Cell,TerrainPathSearch.Kind> edits){
                if(Math.abs(c.x()-originCell.x())+Math.abs(c.z()-originCell.z())<1)return false;
                var below=c.add(0,-1,0);if(edits.containsKey(below)||!block(below).supports())return false;
                // Exit candidates must rejoin existing traversable terrain, not end on a new pillar.
                var at=new Vec3(c.x()+.5,c.y(),c.z()+.5);var evaluator=new NativeTraversalEvaluator(p);var node=evaluator.closest(at);
                if(node==null||Math.abs(node.y()-at.y)>.251||!evaluator.clear(NativeTraversalEvaluator.point(node),Pose.STANDING,false))return false;
                if(evaluator.neighbors(node).stream().filter(edge->Math.abs(edge.to().y()-node.y())<=1.25).count()<2)return false;
                Vec3 nextGoal=target.distanceToSqr(at)>16*16?at.add(target.subtract(at).normalize().scale(16)):target;
                var targetNode=evaluator.closest(nextGoal);if(targetNode==null)return false;
                return new dev.mineagent.runtime.core.task.SurfacePathfinder.Search(node,targetNode,evaluator).advance(96).status()==Status.FOUND;
            }
            public double risk(TerrainPathSearch.Cell c){
                if(risks.containsKey(c))return risks.get(c);var point=new Vec3(c.x()+.5,c.y(),c.z()+.5);double risk=0;
                for(var enemy:nearby){double distance=enemy.position().distanceTo(point);risk+=Math.max(0,4-distance)*2;}
                if(p.getHealth()<6)risk*=2;risks.put(c,risk);return risk;
            }
        };
        search=new TerrainPathSearch(world,originCell,NativeTerrainPolicy.materialCount(p),6);return true;
    }
    public Tick tick(Actions controls){
        actions=controls;if(!active())return new Tick(false,null,false);
        if(player==null||player.level()!=level||!NativeTerrainPolicy.allowed(player)||!controls.current()){cancel();return new Tick(false,null,false);}
        int now=player.level().getServer().getTickCount();
        if(now-started>300){fail("ESCAPE_CONTEXT_RECHECK_TIMEOUT");return new Tick(false,null,false);}
        if(search!=null){
            var budget=NativeNavigationBudget.get(player.level().getServer());int allowed=budget.claim(player.getUUID(),now);if(allowed==0)return new Tick(true,null,false);
            var found=search.advance(Math.min(allowed,96),budget::timeAvailable);expanded=found.expanded();
            if(found.state().equals("SEARCHING"))return new Tick(true,null,false);
            search=null;if(!found.state().equals("FOUND")){state=found.state();return new Tick(false,null,false);}
            planned=found.steps().getFirst();healthBefore=player.getHealth();var displacement=new Vec3(planned.to().x()-planned.from().x(),planned.to().y()-planned.from().y(),planned.to().z()-planned.from().z());decisionFeatures=dev.mineagent.runtime.neoforge.skill.LocalPolicyRuntime.features(player,origin.distanceTo(goal),displacement.length(),0,1,displacement,0,false,7,planned.edits().stream().filter(e->e.kind()==TerrainPathSearch.Kind.PLACE).count());edit=planned.edits().isEmpty()?null:planned.edits().getFirst();operation=UUID.randomUUID();at=now;sent=jumped=false;settled=0;attempts++;
            state=edit==null?"ESCAPE_WALK":edit.kind()==TerrainPathSearch.Kind.BREAK?"ESCAPE_MINE":"ESCAPE_PLACE";
            if(edit!=null)before=signature(pos(edit.cell()));
        }
        if(edit==null){
            var target=planned.to();var destination=new Vec3(target.x()+.5,target.y(),target.z()+.5);
            if(player.position().distanceToSqr(destination)<.16&&player.onGround())return complete(false);
            if(now-at>50){fail("ESCAPE_MOVE_BLOCKED");return new Tick(false,null,false);}
            return new Tick(true,new PathStep(node(planned.from()),node(target),target.y()>planned.from().y()?Action.JUMP:Action.WALK,Posture.STANDING,1),false);
        }
        BlockPos target=pos(edit.cell());
        if(!NativeTerrainPolicy.loaded(player,target)){fail("WAITING_CHUNKS");return new Tick(false,null,false);}
        boolean changed=!signature(target).equals(before);
        if(changed){
            boolean verified=edit.kind()==TerrainPathSearch.Kind.BREAK?player.level().getBlockState(target).getCollisionShape(player.level(),target).isEmpty():player.level().getBlockState(target).isCollisionShapeFullBlock(player.level(),target);
            if(!verified){fail("TERRAIN_TARGET_CHANGED");return new Tick(false,null,false);}
            if(edit.kind()==TerrainPathSearch.Kind.PLACE&&planned.jumpPlace()){
                if(!player.onGround()||Math.abs(player.getY()-(target.getY()+1))>.12){if(now-at>35){fail("SUPPORT_STANDING_NOT_CONFIRMED");return new Tick(false,null,false);}return new Tick(true,null,false);}
                if(++settled<2)return new Tick(true,null,false);
            }
            changes++;return complete(true);
        }
        if(edit.kind()==TerrainPathSearch.Kind.BREAK){
            if(!NativeTerrainPolicy.mayBreak(player,target)){fail("TERRAIN_BREAK_REVOKED");return new Tick(false,null,false);}
            int slot=NativeTerrainPolicy.toolSlot(player,target);if(!controls.select(slot))return new Tick(true,null,false);
            var visible=visibleMiningPoint(player,target);if(visible==null){fail("MINING_TARGET_OCCLUDED");return new Tick(false,null,false);}controls.aim(visible);
            int duration=NativeTerrainPolicy.breakTicks(player,target);if(duration>240||now-at>Math.max(30,duration+30)){fail("MINING_NO_CONFIRMED_PROGRESS");return new Tick(false,null,false);}
            controls.mine(operation,target);sent=true;return new Tick(true,null,false);
        }
        if(!NativeTerrainPolicy.mayPlace(player,target)){fail("TERRAIN_PLACE_REVOKED");return new Tick(false,null,false);}
        int slot=NativeTerrainPolicy.materialSlot(player);if(slot<0){fail("BUILDING_MATERIAL_REQUIRED");return new Tick(false,null,false);}if(!controls.select(slot))return new Tick(true,null,false);
        if(planned.jumpPlace()){
            if(!jumped){if(!player.onGround())return new Tick(true,null,false);
                double centerX=target.getX()+.5,centerZ=target.getZ()+.5;
                if(Math.hypot(player.getX()-centerX,player.getZ()-centerZ)>.10){var centered=node(planned.from());return new Tick(true,new PathStep(centered,centered,Action.WALK,Posture.STANDING,1),false);}
                if(!player.level().noCollision(player,player.getBoundingBox().expandTowards(0,1.3,0))){fail("JUMP_COLUMN_HEADROOM_BLOCKED");return new Tick(false,null,false);}
                var support=Vec3.atCenterOf(target.below()).add(0,.49,0);controls.aim(support);
                if(player.getLookAngle().dot(support.subtract(player.getEyePosition()).normalize())<.99)return new Tick(true,null,false);
                controls.jump(operation);jumped=true;at=now;return new Tick(true,null,false);}
            if(player.getY()<target.getY()+1.02){if(now-at>15){fail("JUMP_PLACE_WINDOW_MISSED");return new Tick(false,null,false);}return new Tick(true,null,false);}
        }
        if(!sent){var hit=placementHit(player,target);if(hit==null){fail("SUPPORT_FACE_NOT_REACHABLE");return new Tick(false,null,false);}controls.aim(hit.getLocation());controls.place(operation,hit);sent=true;at=now;}
        if(now-at>25){fail("PLACEMENT_NOT_CONFIRMED_CHECK_WORLD");return new Tick(false,null,false);}return new Tick(true,null,false);
    }
    private Tick complete(boolean edited){learn(false);if(actions!=null&&operation!=null)actions.cancel(operation);search=null;planned=null;edit=null;operation=null;state="RECHECK_ORIGINAL_ROUTE";return new Tick(false,null,true);}
    private void fail(String why){learn(true);if(edit!=null)rejected.add(signature(pos(edit.cell())));if(actions!=null&&operation!=null)actions.cancel(operation);search=null;planned=null;edit=null;operation=null;state="BLOCKED";reason=why;}
    private void learn(boolean failed){if(decisionFeatures==null||player==null)return;double cost=(player.level().getServer().getTickCount()-at)/120d+Math.max(0,healthBefore-player.getHealth())/Math.max(1,player.getMaxHealth())+(failed?.6:0);dev.mineagent.runtime.neoforge.skill.LocalPolicyRuntime.outcome(player,decisionFeatures,cost);decisionFeatures=null;}
    public static Vec3 visibleMiningPoint(ServerPlayer p,BlockPos target){
        for(double y:new double[]{.5,.05,.95})for(double x:new double[]{.5,.05,.95})for(double z:new double[]{.5,.05,.95}){
            var at=new Vec3(target.getX()+x,target.getY()+y,target.getZ()+z);
            var hit=p.level().clip(new ClipContext(p.getEyePosition(),at,ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,p));
            if(hit.getType()==HitResult.Type.BLOCK&&hit.getBlockPos().equals(target))return at;
        }return null;
    }
    public static BlockHitResult placementHit(ServerPlayer p,BlockPos target){
        for(var face:Direction.values()){
            var anchor=target.relative(face.getOpposite());if(!NativeTerrainPolicy.loaded(p,anchor)||p.level().getBlockState(anchor).getCollisionShape(p.level(),anchor).isEmpty())continue;
            var hit=Vec3.atCenterOf(anchor).add(new Vec3(face.getStepX(),face.getStepY(),face.getStepZ()).scale(.5));
            if(!p.isWithinBlockInteractionRange(anchor,0))continue;
            var clip=p.level().clip(new ClipContext(p.getEyePosition(),hit,ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,p));
            if(clip.getType()!=HitResult.Type.MISS&&!clip.getBlockPos().equals(anchor))continue;
            return new BlockHitResult(hit,face,anchor,false);
        }return null;
    }
    private String signature(BlockPos p){return p.asLong()+":"+net.minecraft.commands.arguments.blocks.BlockStateParser.serialize(player.level().getBlockState(p));}
    private static TerrainPathSearch.Cell cell(BlockPos pos){return new TerrainPathSearch.Cell(pos.getX(),pos.getY(),pos.getZ());}
    private static BlockPos pos(TerrainPathSearch.Cell c){return new BlockPos(c.x(),c.y(),c.z());}
    private static Node node(TerrainPathSearch.Cell c){return new Node(c.x(),c.y()*16,c.z());}
}
