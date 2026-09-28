package dev.mineagent.runtime.neoforge.ui;

import com.fasterxml.jackson.databind.*;
import dev.mineagent.runtime.core.building.*;
import dev.mineagent.runtime.neoforge.MineAgentRuntimeServices;
import dev.mineagent.runtime.neoforge.task.ServerTaskStart;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.*;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.AABB;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/** Actual loaded-block checks. Screenshots and unrelated inspections never count as these receipts. */
final class BuildingVerifier implements ServerBuildings.VerificationWork {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final ExecutorService IO=Executors.newVirtualThreadPerTaskExecutor();
    private final ServerPlayer player;private final UUID world,owner,agent;private final String id;private final ServerLevel level;
    private final ConstructionLedger ledger;private final ConstructionLedger.Head head;private final BuildingDesign design;
    private final int[] bounds;private final String hash;private final long expectedCells;private final BooleanSupplier permit;
    private final CompletableFuture<Map<String,Object>> result;private final List<ConstructionVerification.Check> checks=new ArrayList<>();
    private final List<Map<String,Object>> mismatches=new ArrayList<>();private final CompletableFuture<Map<String,int[]>> componentBounds;
    private CompletableFuture<List<ConstructionLedger.Cell>> rows;private long covered,observedTick;private int checkIndex;private long checkCursor;private boolean checkPassed=true,blocksMatch=true,done,paused;private final List<String> checkErrors=new ArrayList<>();
    private static <T> CompletableFuture<T> io(Callable<T> body){return CompletableFuture.supplyAsync(()->{try{return body.call();}catch(Exception error){throw new CompletionException(error);}},IO);}
    BuildingVerifier(ServerPlayer p,UUID agent,String id,ConstructionLedger ledger,ConstructionLedger.Head head,BuildingDesign design,int[] bounds,String hash,long count,BooleanSupplier permit,CompletableFuture<Map<String,Object>> result){
        player=p;this.agent=agent;this.id=id;level=p.level();world=MineAgentRuntimeServices.worldId(level.getServer());owner=p.getUUID();this.ledger=ledger;this.head=head;this.design=design;this.bounds=bounds;this.hash=hash;expectedCells=count;this.permit=permit;this.result=result;
        componentBounds=io(()->{var out=new HashMap<String,int[]>();for(String component:design.componentIds())out.put(component,ledger.bounds(head.revision(),component));return out;});
        rows=io(()->ledger.page(head.revision(),0,true));
    }
    public void pause(){paused=true;}
    public CompletableFuture<?> pending(){return done?result:CompletableFuture.allOf(rows,componentBounds);}
    public boolean tick(){
        if(done)return result.isDone();if(!rows.isDone()||!componentBounds.isDone()||level.getGameTime()<=head.mutationTick())return false;
        try {
            if(paused||!permit.getAsBoolean()||player.level()!=level||level.getServer().getPlayerList().getPlayer(player.getUUID())!=player||!ServerTaskStart.allowed(player,agent))throw new IllegalStateException("BUILDING_VERIFICATION_CONTEXT_CHANGED");
            if(!design.dimension().equals(level.dimension().identifier().toString()))throw new IllegalStateException("BUILDING_VERIFICATION_DIMENSION");
            if(observedTick==0)observedTick=level.getGameTime();
            var batch=rows.join();
            if(!batch.isEmpty()){
                for(var cell:batch){var pos=new BlockPos(cell.x(),cell.y(),cell.z());ConversationWorldGeometry.location(level,pos);String observed=BlockStateParser.serialize(level.getBlockState(pos));covered++;if(!observed.equals(cell.after())||level.getBlockEntity(pos)!=null){blocksMatch=false;if(mismatches.size()<16)mismatches.add(Map.of("position",List.of(cell.x(),cell.y(),cell.z()),"component",cell.component(),"expected",cell.after(),"observed",observed));}}
                long next=batch.getLast().sequence();rows=io(()->ledger.page(head.revision(),next,true));return false;
            }
            int budget=256;
            while(checkIndex<design.checks().size()&&budget-->0){
                var check=design.checks().get(checkIndex);String kind=check.path("kind").asText();
                try {
                    if(kind.equals("bounds")){
                        int[] min=position(check.path("min")),max=position(check.path("max"));var actual=componentBounds.join().get(check.path("component").asText());for(int i=0;i<3;i++)if(min[i]!=actual[i*2]||max[i]!=actual[i*2+1])throw new IllegalStateException("COMPONENT_DIMENSIONS_MISMATCH");complete(check);continue;
                    }
                    if(kind.equals("path")){
                        if(checkCursor>=check.path("path").size()){complete(check);continue;}
                        var pos=block(position(check.path("path").get((int)checkCursor)));int headroom=check.path("headroom").asInt(2);inside(pos,headroom);
                        if(checkCursor>0){var prior=block(position(check.path("path").get((int)checkCursor-1)));int horizontal=Math.abs(pos.getX()-prior.getX())+Math.abs(pos.getZ()-prior.getZ()),vertical=Math.abs(pos.getY()-prior.getY());boolean climbing=level.getBlockState(pos).is(BlockTags.CLIMBABLE)&&level.getBlockState(prior).is(BlockTags.CLIMBABLE);if(horizontal>1||vertical>1||horizontal==0&&!climbing)throw new IllegalStateException("PATH_POINTS_NOT_CONNECTED");}
                        boolean climbing=level.getBlockState(pos).is(BlockTags.CLIMBABLE);if(!climbing&&level.getBlockState(pos.below()).getCollisionShape(level,pos.below()).isEmpty())throw new IllegalStateException("PATH_HAS_NO_FLOOR");
                        if(!clear(pos,headroom,true))throw new IllegalStateException("PATH_BLOCKED_OR_LOW_HEADROOM");
                        checkCursor++;continue;
                    }
                    int[] min=position(check.path("min")),max=position(check.path("max"));long width=(long)max[0]-min[0]+1,depth=(long)max[2]-min[2]+1,height=(long)max[1]-min[1]+1,total=Math.multiplyExact(Math.multiplyExact(width,depth),height);
                    if(checkCursor>=total){complete(check);continue;}
                    long index=checkCursor++;var pos=new BlockPos(min[0]+(int)(index%width),min[1]+(int)(index/(width*depth)),min[2]+(int)((index/width)%depth));inside(pos,check.path("headroom").asInt(1));
                    switch(kind){
                        case "states"->{var expected=ConversationWorldGeometry.parse(player,check.path("expected").asText(),false);if(level.getBlockState(pos)!=expected)throw new IllegalStateException("EXPECTED_STATE_MISMATCH");}
                        case "clearance"->{if(!clear(pos,check.path("headroom").asInt(1),false))throw new IllegalStateException("CLEARANCE_BLOCKED");}
                        case "support"->{if(!check.path("allow_floating").asBoolean(false)&&!level.getBlockState(pos).isAir()&&level.getBlockState(pos.below()).getCollisionShape(level,pos.below()).isEmpty())throw new IllegalStateException("SUPPORT_MISSING");}
                        default->throw new IllegalStateException("CHECK_KIND_UNSUPPORTED");
                    }
                }catch(Exception failure){checkPassed=false;if(checkErrors.size()<16)checkErrors.add(Objects.toString(failure.getMessage(),failure.getClass().getSimpleName())+" @ "+checkCursor);complete(check);}
            }
            if(checkIndex<design.checks().size())return false;
            done=true;long completed=level.getGameTime();long started=observedTick;
            io(()->{
                var scope=new ConstructionVerification.Scope(world,owner,agent,design.dimension(),id,head.revision(),hash);
                long changed=ledger.steps(head.revision()).stream().mapToLong(s->((Number)s.get("changes")).longValue()).sum();
                var verification=new ConstructionVerification(scope,changed,head.mutationTick(),design.checks().findValuesAsText("id").stream().collect(java.util.stream.Collectors.toSet()));
                var report=new ConstructionVerification.Report(scope,started,completed,changed,blocksMatch&&covered==expectedCells,checks);boolean passed=verification.accept(report);
                String encoded=JSON.writeValueAsString(Map.of("evidence",report,"coveredTargetCells",covered,"mismatches",mismatches));ledger.verified(head.operation(),head.revision(),passed,encoded);return ServerBuildings.finishedView(id,ledger);
            }).whenComplete((value,error)->{if(error!=null)result.completeExceptionally(error);else result.complete(value);});return false;
        }catch(Exception failure){done=true;result.complete(Map.of("id",id,"revision",head.revision(),"status","UNVERIFIED","verification","UNVERIFIED","error",Objects.toString(failure.getMessage(),"VERIFICATION_FAILED")));return true;}
    }
    private void complete(JsonNode check){checks.add(new ConstructionVerification.Check(check.path("id").asText(),checkPassed,String.join("; ",checkErrors)));checkIndex++;checkCursor=0;checkPassed=true;checkErrors.clear();}
    private int[] position(JsonNode point){var origin=design.origin();return new int[]{Math.addExact(origin.get(0),point.get(0).intValue()),Math.addExact(origin.get(1),point.get(1).intValue()),Math.addExact(origin.get(2),point.get(2).intValue())};}
    private static BlockPos block(int[] point){return new BlockPos(point[0],point[1],point[2]);}
    private void inside(BlockPos pos,int headroom){for(int i=0;i<3;i++){int coordinate=i==0?pos.getX():i==1?pos.getY():pos.getZ();if(coordinate<bounds[i*2]-1||coordinate>bounds[i*2+1]+(i==1?headroom:1))throw new IllegalStateException("CHECK_OUTSIDE_BUILDING");}ConversationWorldGeometry.location(level,pos);}
    private boolean clear(BlockPos feet,int height,boolean doors){
        var box=new AABB(feet.getX()+.2,feet.getY()+.01,feet.getZ()+.2,feet.getX()+.8,feet.getY()+height-.01,feet.getZ()+.8);
        for(int y=0;y<height;y++){var pos=feet.above(y);ConversationWorldGeometry.location(level,pos);var state=level.getBlockState(pos);
            if(doors&&state.is(BlockTags.WOODEN_DOORS)){var other=state.getValue(DoorBlock.HALF)==DoubleBlockHalf.LOWER?pos.above():pos.below();var paired=level.getBlockState(other);if(paired.getBlock()!=state.getBlock()||paired.getValue(DoorBlock.HALF)==state.getValue(DoorBlock.HALF)||paired.getValue(DoorBlock.FACING)!=state.getValue(DoorBlock.FACING)||paired.getValue(DoorBlock.HINGE)!=state.getValue(DoorBlock.HINGE))return false;continue;}
            for(var shape:state.getCollisionShape(level,pos).toAabbs())if(shape.move(pos).intersects(box))return false;
        }return true;
    }
}
