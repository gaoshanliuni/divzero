package dev.mineagent.runtime.core.task;

import org.junit.jupiter.api.Test;
import java.util.*;
import static dev.mineagent.runtime.core.task.TerrainPathSearch.*;
import static org.junit.jupiter.api.Assertions.*;

class TerrainPathSearchTest {
    private static Result solve(World world,int materials){var search=new TerrainPathSearch(world,new Cell(0,0,0),materials,4);Result result;do{result=search.advance(256,()->true);}while(result.state().equals("SEARCHING"));return result;}
    @Test void narrowPitNeedsTwoRealSupportsAndAnExitNotAnEndlessColumn(){
        var result=solve(new World(){
            public Block block(Cell c){boolean solid=c.y()<0||c.y()<3&&(c.x()!=0||c.z()!=0);return new Block(true,!solid,solid,false,0,solid?"protected":"air");}
            public boolean canPlace(Cell c){return true;}
            public boolean exit(Cell c,Map<Cell,Kind> edits){return c.y()==3&&(c.x()!=0||c.z()!=0);}
            public double risk(Cell c){return 0;}
        },3);
        assertEquals("FOUND",result.state());assertEquals(2,result.steps().stream().flatMap(s->s.edits().stream()).filter(e->e.kind()==Kind.PLACE).count());
        assertEquals(3,result.steps().getLast().to().y());assertFalse(result.steps().getLast().jumpPlace());
    }
    @Test void ceilingForcesCombinedSideDigAndSupportInsteadOfPillaring(){
        var result=solve(new World(){
            public Block block(Cell c){boolean wall=c.equals(new Cell(1,0,0))||c.equals(new Cell(1,1,0));boolean solid=c.y()<0||c.y()==2&&c.x()<=0||wall;return new Block(true,!solid,solid,wall,4,solid?"terrain":"air");}
            public boolean canPlace(Cell c){return c.x()>0;}
            public boolean exit(Cell c,Map<Cell,Kind> edits){return c.equals(new Cell(2,1,0));}
            public double risk(Cell c){return c.z()!=0||c.x()<0?Double.POSITIVE_INFINITY:0;}
        },3);
        assertEquals("FOUND",result.state());var edits=result.steps().stream().flatMap(s->s.edits().stream()).toList();assertTrue(edits.stream().anyMatch(e->e.kind()==Kind.BREAK));assertTrue(edits.stream().anyMatch(e->e.kind()==Kind.PLACE));
    }
    @Test void unloadedAndProhibitedTerrainAreNotPermissionsToDig(){
        var result=solve(new World(){public Block block(Cell c){return new Block(false,false,true,false,0,"unknown");}public boolean canPlace(Cell c){return false;}public boolean exit(Cell c,Map<Cell,Kind> edits){return false;}public double risk(Cell c){return 0;}},64);
        assertEquals("WAITING_CHUNKS",result.state());assertTrue(result.steps().isEmpty());
    }
}
