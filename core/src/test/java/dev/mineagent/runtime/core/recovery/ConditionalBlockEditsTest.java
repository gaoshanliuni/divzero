package dev.mineagent.runtime.core.recovery;
import dev.mineagent.runtime.api.recovery.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ConditionalBlockEditsTest {
    private static BlockSnapshot block(int x,String state){return new BlockSnapshot("minecraft:overworld",x,80,0,"minecraft:"+state);}
    private static final List<BlockChange> CHANGES=List.of(new BlockChange(block(0,"air"),block(0,"stone")),new BlockChange(block(1,"air"),block(1,"stone")));
    private static class World implements ConditionalBlockEdits.World {
        final Map<Integer,BlockSnapshot> blocks=new HashMap<>(Map.of(0,block(0,"stone"),1,block(1,"stone")));int writes;
        public BlockSnapshot read(BlockSnapshot at){return blocks.get(at.x());}
        public void write(BlockSnapshot value){blocks.put(value.x(),value);writes++;}
    }
    @Test void laterPlayerChangeRejectsEntireUndoWithoutWriting(){var world=new World();world.blocks.put(1,block(1,"diamond_block"));var result=ConditionalBlockEdits.execute(CHANGES,true,world);assertEquals("CONFLICT",result.status());assertEquals(0,world.writes);assertEquals("minecraft:diamond_block",world.blocks.get(1).state());}
    @Test void containerContentsArePartOfConflictCheck(){var world=new World();world.blocks.put(1,new BlockSnapshot("minecraft:overworld",1,80,0,"minecraft:stone","{Items:[{id:'minecraft:diamond'}]}"));assertEquals("CONFLICT",ConditionalBlockEdits.execute(CHANGES,true,world).status());assertEquals(0,world.writes);}
    @Test void neighborCallbackCannotBeOverwrittenAfterPreflight(){var world=new World(){@Override public void write(BlockSnapshot value){super.write(value);blocks.put(1,block(1,"gold_block"));}};var result=ConditionalBlockEdits.execute(CHANGES,true,world);assertEquals("PARTIAL",result.status());assertEquals(1,world.writes);assertEquals(block(1,"gold_block"),world.blocks.get(1));}
    @Test void redoChecksThePostUndoWorld(){var world=new World();assertEquals("APPLIED",ConditionalBlockEdits.execute(CHANGES,true,world).status());assertEquals("APPLIED",ConditionalBlockEdits.execute(CHANGES,false,world).status());assertEquals(block(0,"stone"),world.blocks.get(0));ConditionalBlockEdits.execute(CHANGES,true,world);world.blocks.put(0,block(0,"oak_planks"));assertEquals("CONFLICT",ConditionalBlockEdits.execute(CHANGES,false,world).status());assertEquals(block(0,"oak_planks"),world.blocks.get(0));}
    @Test void writeFailureWithUnreadableOutcomeIsUnknown(){var world=new World(){boolean failed;@Override public void write(BlockSnapshot value){super.write(value);failed=true;throw new IllegalStateException();}@Override public BlockSnapshot read(BlockSnapshot value){if(failed)throw new IllegalStateException();return super.read(value);}};assertEquals("UNKNOWN",ConditionalBlockEdits.execute(CHANGES,true,world).status());}
    @Test void noOpHistoryDoesNotClaimOwnershipOfUnchangedBlocks(){var world=new World();world.blocks.put(0,block(0,"gold_block"));assertEquals("APPLIED",ConditionalBlockEdits.execute(List.of(new BlockChange(block(0,"stone"),block(0,"stone"))),true,world).status());assertEquals(0,world.writes);}
}
