package dev.mineagent.runtime.core.task;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class SpatialNeighborsTest {
    @Test void bucketsMatchFullSearchIncludingNegativeCellsAndBoundaryDistances(){
        var random=new Random(817);var points=new ArrayList<SpatialNeighbors.Point>();
        for(int i=0;i<1200;i++)points.add(new SpatialNeighbors.Point(random.nextDouble()*80-40,random.nextDouble()*20-10,random.nextDouble()*80-40));
        var index=new SpatialNeighbors<>(points,4,p->p);
        for(var p:points){long expected=points.stream().filter(q->q!=p&&Math.pow(p.x()-q.x(),2)+Math.pow(p.y()-q.y(),2)+Math.pow(p.z()-q.z(),2)<16).count();assertEquals(expected,index.count(p,4,p));}
    }
    @Test void angularSearchMatchesPairComparisonWithoutQuadraticThreatWalk(){
        var random=new Random(291);double minimum=Math.acos(-.2);
        for(int iteration=0;iteration<300;iteration++){var angles=new ArrayList<Double>();angles.add(0D);for(int i=0;i<iteration%20;i++)angles.add(random.nextDouble()*Math.PI*2);
            boolean expected=false;for(int i=0;i<angles.size();i++)for(int j=i+1;j<angles.size();j++)expected|=Math.cos(angles.get(i)-angles.get(j))<-.2;
            assertEquals(expected,SpatialNeighbors.opposing(angles,minimum));}
    }
}
