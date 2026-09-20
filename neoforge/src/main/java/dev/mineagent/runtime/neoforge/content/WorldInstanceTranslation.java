package dev.mineagent.runtime.neoforge.content;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mineagent.runtime.api.packages.RuntimeInstance;
import dev.mineagent.runtime.api.packages.RuntimeInstanceLocation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.*;
import java.util.*;

/** Ephemeral Native plan. Only attributed carriers/solid blocks move; no destructive fallback for unsupported data. */
final class WorldInstanceTranslation {
    private record MovingObject(RuntimeObjectEntity entity,Vec3 before,AABB target){}
    private record MovingBlock(String key,BlockPos source,BlockPos target,BlockState state){}
    private final ServerLevel level;private final Vec3 delta;private final List<MovingObject> objects;private final List<MovingBlock> blocks;
    private final Map<String,String> state;
    private WorldInstanceTranslation(ServerLevel level,Vec3 delta,List<MovingObject> objects,List<MovingBlock> blocks,Map<String,String> state){this.level=level;this.delta=delta;this.objects=List.copyOf(objects);this.blocks=List.copyOf(blocks);this.state=Map.copyOf(state);}
    static WorldInstanceTranslation prepare(ServerLevel level,RuntimeInstance instance,RuntimeInstanceLocation target,List<RuntimeObjectEntity> entities)throws Exception{
        var from=instance.location();var delta=new Vec3(target.x()-from.x(),target.y()-from.y(),target.z()-from.z());var objects=new ArrayList<MovingObject>();var blocks=new ArrayList<MovingBlock>();var state=new LinkedHashMap<>(instance.state());var json=new ObjectMapper();
        for(var e:entities){var box=e.getBoundingBox().move(delta);check(level,box);objects.add(new MovingObject(e,e.position(),box));}
        for(var entry:instance.state().entrySet())if(entry.getKey().startsWith("_block.")){
            if(delta.x!=Math.rint(delta.x)||delta.y!=Math.rint(delta.y)||delta.z!=Math.rint(delta.z))throw new IllegalStateException("INSTANCE_MOVE_BLOCK_GRID_REQUIRED");
            var data=json.readTree(entry.getValue());
            for(String key:List.of("x","y","z"))if(!data.path(key).isIntegralNumber()||!data.path(key).canConvertToInt())throw new IllegalStateException("INSTANCE_MOVE_PART_CHANGED");
            var pos=new BlockPos(data.path("x").asInt(),data.path("y").asInt(),data.path("z").asInt());var origin=BlockPos.containing(from.x(),from.y(),from.z());
            if(Math.abs((long)pos.getX()-origin.getX())>32||Math.abs((long)pos.getY()-origin.getY())>32||Math.abs((long)pos.getZ()-origin.getZ())>32)throw new IllegalStateException("INSTANCE_MOVE_PART_CHANGED");
            var next=pos.offset((int)delta.x,(int)delta.y,(int)delta.z);check(level,new AABB(pos));check(level,new AABB(next));
            var block=level.getBlockState(pos);if(!BuiltInRegistries.BLOCK.getKey(block.getBlock()).toString().equals(data.path("block").asText()))throw new IllegalStateException("INSTANCE_MOVE_PART_CHANGED");
            if(block.hasBlockEntity()||level.getBlockEntity(pos)!=null||!block.getFluidState().isEmpty()||!block.isCollisionShapeFullBlock(level,pos))throw new IllegalStateException("INSTANCE_MOVE_BLOCK_ADAPTER_REQUIRED");
            blocks.add(new MovingBlock(entry.getKey(),pos,next,block));
            var translated=((com.fasterxml.jackson.databind.node.ObjectNode)data).deepCopy();translated.put("x",next.getX());translated.put("y",next.getY());translated.put("z",next.getZ());state.put(entry.getKey(),json.writeValueAsString(translated));
        }
        if(objects.size()>32||blocks.size()>128||objects.isEmpty()&&blocks.isEmpty())throw new IllegalStateException("INSTANCE_MOVE_PART_BUDGET");
        var ownIds=entities.stream().map(RuntimeObjectEntity::getUUID).collect(java.util.stream.Collectors.toSet());var sourceBlocks=blocks.stream().map(MovingBlock::source).collect(java.util.stream.Collectors.toSet());
        var oldCubes=sourceBlocks.stream().map(AABB::new).toList();
        for(var block:blocks){
            if(!sourceBlocks.contains(block.target)&&!level.getBlockState(block.target).isAir())throw new IllegalStateException("INSTANCE_MOVE_OCCUPIED");
            if(!level.getEntities((net.minecraft.world.entity.Entity)null,new AABB(block.target),e->!ownIds.contains(e.getUUID())&&e.isAlive()&&!e.isSpectator()).isEmpty())throw new IllegalStateException("INSTANCE_MOVE_OCCUPIED");
            if(objects.stream().anyMatch(o->o.target.intersects(new AABB(block.target))))throw new IllegalStateException("INSTANCE_MOVE_SELF_COLLISION");
        }
        double cells=0;
        for(int n=0;n<objects.size();n++){
            var object=objects.get(n);var box=object.target;cells+=(Math.ceil(box.getXsize())+2)*(Math.ceil(box.getYsize())+2)*(Math.ceil(box.getZsize())+2);if(cells>32768)throw new IllegalStateException("INSTANCE_MOVE_COLLISION_BUDGET");
            for(int m=0;m<n;m++)if(box.intersects(objects.get(m).target))throw new IllegalStateException("INSTANCE_MOVE_SELF_COLLISION");
            for(var shape:level.getBlockCollisions(object.entity,box))if(!shape.isEmpty()&&!oldCubes.contains(shape.bounds()))throw new IllegalStateException("INSTANCE_MOVE_OCCUPIED");
            if(!level.getEntities(object.entity,box,e->!ownIds.contains(e.getUUID())&&e.isAlive()&&!e.isSpectator()).isEmpty())throw new IllegalStateException("INSTANCE_MOVE_OCCUPIED");
        }
        return new WorldInstanceTranslation(level,delta,objects,blocks,state);
    }
    Map<String,String> translatedState(){return state;}
    void apply(){
        // Every source is rechecked before the first mutation. The persisted journal precedes this method.
        for(var object:objects)if(object.entity.isRemoved()||!object.entity.position().equals(object.before))throw new IllegalStateException("INSTANCE_MOVE_PART_CHANGED");
        for(var block:blocks)if(level.getBlockState(block.source)!=block.state||level.getBlockEntity(block.source)!=null)throw new IllegalStateException("INSTANCE_MOVE_PART_CHANGED");
        for(var block:blocks)if(!level.setBlock(block.source,Blocks.AIR.defaultBlockState(),18))throw new IllegalStateException("INSTANCE_MOVE_NATIVE_FAILED");
        for(var block:blocks)if(!level.getBlockState(block.target).isAir()||!level.setBlock(block.target,block.state,18))throw new IllegalStateException("INSTANCE_MOVE_NATIVE_FAILED");
        for(var object:objects)object.entity.translateManaged(delta);
        for(var block:blocks){level.updateNeighborsAt(block.source,block.state.getBlock());level.updateNeighborsAt(block.target,block.state.getBlock());}
        for(var block:blocks)if(level.getBlockState(block.target)!=block.state)throw new IllegalStateException("INSTANCE_MOVE_NATIVE_MISMATCH");
        var targets=blocks.stream().map(MovingBlock::target).collect(java.util.stream.Collectors.toSet());
        for(var block:blocks)if(!targets.contains(block.source)&&!level.getBlockState(block.source).isAir())throw new IllegalStateException("INSTANCE_MOVE_NATIVE_MISMATCH");
        for(var object:objects)if(object.entity.position().distanceToSqr(object.before.add(delta))>1e-12||!level.noCollision(object.entity))throw new IllegalStateException("INSTANCE_MOVE_NATIVE_MISMATCH");
    }
    private static void check(ServerLevel level,AABB bounds){
        var min=BlockPos.containing(bounds.minX,bounds.minY,bounds.minZ);var max=BlockPos.containing(bounds.maxX,bounds.maxY,bounds.maxZ);
        if(!level.isInWorldBounds(min)||!level.isInWorldBounds(max)||!level.getWorldBorder().isWithinBounds(min)||!level.getWorldBorder().isWithinBounds(max))throw new IllegalStateException("INSTANCE_MOVE_OUT_OF_WORLD");
        for(int x=min.getX()>>4;x<=max.getX()>>4;x++)for(int z=min.getZ()>>4;z<=max.getZ()>>4;z++)if(!level.getChunkSource().hasChunk(x,z))throw new IllegalStateException("INSTANCE_MOVE_CHUNK_UNLOADED");
    }
}
