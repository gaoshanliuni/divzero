package dev.mineagent.runtime.legacy189.bridge;

import dev.mineagent.runtime.legacy189.navigation.*;
import dev.mineagent.runtime.legacy189.navigation.TerrainPathSearch.*;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

/** Same upstream navigation scenarios, run against the generated Java 8 classes. */
public class NavigationPortTest {
    private static Result solve(World world,int materials){TerrainPathSearch search=new TerrainPathSearch(world,new Cell(0,0,0),materials,4);Result result;do{result=search.advance(96,()->true);}while(result.state().equals("SEARCHING"));return result;}
    @Test public void narrowPitNeedsTwoSupportsAndAnExistingExit(){
        Result result=solve(new World(){
            public Block block(Cell c){boolean solid=c.y()<0||c.y()<3&&(c.x()!=0||c.z()!=0);return new Block(true,!solid,solid,false,0,solid?"protected":"air");}
            public boolean canPlace(Cell c){return true;}
            public boolean exit(Cell c,Map<Cell,Kind> edits){return c.y()==3&&(c.x()!=0||c.z()!=0);}
            public double risk(Cell c){return 0;}
        },3);
        assertEquals("FOUND",result.state());assertEquals(2,result.steps().stream().flatMap(s->s.edits().stream()).filter(e->e.kind()==Kind.PLACE).count());
        assertEquals(3,result.steps().get(result.steps().size()-1).to().y());assertFalse(result.steps().get(result.steps().size()-1).jumpPlace());
    }
    @Test public void ceilingRequiresSideDigAndSupport(){
        Result result=solve(new World(){
            public Block block(Cell c){boolean wall=c.equals(new Cell(1,0,0))||c.equals(new Cell(1,1,0));boolean solid=c.y()<0||c.y()==2&&c.x()<=0||wall;return new Block(true,!solid,solid,wall,4,solid?"terrain":"air");}
            public boolean canPlace(Cell c){return c.x()>0;}
            public boolean exit(Cell c,Map<Cell,Kind> edits){return c.equals(new Cell(2,1,0));}
            public double risk(Cell c){return c.z()!=0||c.x()<0?Double.POSITIVE_INFINITY:0;}
        },3);
        assertEquals("FOUND",result.state());assertTrue(result.steps().stream().flatMap(s->s.edits().stream()).anyMatch(e->e.kind()==Kind.BREAK));assertTrue(result.steps().stream().flatMap(s->s.edits().stream()).anyMatch(e->e.kind()==Kind.PLACE));
    }
    @Test public void unloadedTerrainCannotBeEdited(){
        Result result=solve(new World(){public Block block(Cell c){return new Block(false,false,true,false,0,"unknown");}public boolean canPlace(Cell c){return false;}public boolean exit(Cell c,Map<Cell,Kind> edits){return false;}public double risk(Cell c){return 0;}},64);
        assertEquals("WAITING_CHUNKS",result.state());assertTrue(result.steps().isEmpty());
    }
    @Test public void rejectedEditRequiresAChangedContext(){Rejections<String> denied=new Rejections<String>();Cell at=new Cell(1,0,0);denied.reject(at,Kind.BREAK,"stone:hand:denied");for(int i=0;i<1000;i++)assertTrue(denied.contains(at,Kind.BREAK,()->"stone:hand:denied"));assertFalse(denied.contains(at,Kind.PLACE,()->"stone:hand:denied"));assertFalse(denied.contains(at,Kind.BREAK,()->"stone:pickaxe:allowed"));}
    @Test public void slabAndCarpetFloorsRemainDistinct(){
        SurfacePathfinder.Node a=new SurfacePathfinder.Node(0,160,0),b=new SurfacePathfinder.Node(1,168,0),c=new SurfacePathfinder.Node(2,169,0),d=new SurfacePathfinder.Node(3,185,0);
        Map<SurfacePathfinder.Node,List<SurfacePathfinder.Node>> edges=new HashMap<>();edges.put(a,Arrays.asList(b));edges.put(b,Arrays.asList(c));edges.put(c,Arrays.asList(d));edges.put(d,Collections.emptyList());
        assertEquals(Arrays.asList(a,b,c,d),SurfacePathfinder.find(a,d,20,n->edges.getOrDefault(n,Collections.emptyList())));
    }
    @Test public void fenceHeightCannotBeJumped(){SurfacePathfinder.Node a=new SurfacePathfinder.Node(0,0,0),fence=new SurfacePathfinder.Node(1,24,0);assertTrue(SurfacePathfinder.find(a,fence,20,n->Arrays.asList(fence)).isEmpty());}
    @Test public void unfinishedSliceResumesInsteadOfReportingNoRoute(){
        SurfacePathfinder.Node a=new SurfacePathfinder.Node(0,0,0),goal=new SurfacePathfinder.Node(12,0,0);
        SurfacePathfinder.Search search=new SurfacePathfinder.Search(a,goal,n->n.x()>=12?Collections.emptyList():Arrays.asList(new SurfacePathfinder.PathStep(n,new SurfacePathfinder.Node(n.x()+1,0,0),SurfacePathfinder.Action.WALK,SurfacePathfinder.Posture.STANDING,1)));
        assertEquals(SurfacePathfinder.Status.BUDGET_EXHAUSTED,search.advance(1).status());assertEquals(SurfacePathfinder.Status.FOUND,search.advance(20).status());
    }
}
