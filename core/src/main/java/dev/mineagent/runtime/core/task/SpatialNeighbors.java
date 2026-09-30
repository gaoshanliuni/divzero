package dev.mineagent.runtime.core.task;
import java.util.*;
import java.util.function.*;

/** Exact spherical neighbor counts using local buckets; no candidate truncation or scan delay. */
public final class SpatialNeighbors<T> {
    public record Point(double x,double y,double z){}
    private record Cell(int x,int y,int z){}
    private record Entry<T>(T value,Point point){}
    private final double width;private final Map<Cell,List<Entry<T>>> buckets=new HashMap<>();
    public SpatialNeighbors(Collection<T> values,double width,Function<T,Point> position){
        if(!Double.isFinite(width)||width<=0)throw new IllegalArgumentException("SPATIAL_CELL_SIZE");this.width=width;
        for(T value:values){Point p=position.apply(value);buckets.computeIfAbsent(cell(p),k->new ArrayList<>()).add(new Entry<>(value,p));}
    }
    private Cell cell(Point p){return new Cell((int)Math.floor(p.x/width),(int)Math.floor(p.y/width),(int)Math.floor(p.z/width));}
    public int count(Point p,double radius,T excluded){
        int n=0;Cell center=cell(p);int reach=(int)Math.ceil(radius/width);double squared=radius*radius;
        for(int x=center.x-reach;x<=center.x+reach;x++)for(int y=center.y-reach;y<=center.y+reach;y++)for(int z=center.z-reach;z<=center.z+reach;z++)
            for(var other:buckets.getOrDefault(new Cell(x,y,z),List.of()))if(other.value!=excluded){double dx=p.x-other.point.x,dy=p.y-other.point.y,dz=p.z-other.point.z;if(dx*dx+dy*dy+dz*dz<squared)n++;}
        return n;
    }
    /** Detect opposing horizontal approach bearings by binary searching the opposite arc. */
    public static boolean opposing(List<Double> directions,double minimumAngle){
        if(directions.size()<2)return false;double circle=Math.PI*2;double[] values=directions.stream().mapToDouble(a->(a%circle+circle)%circle).sorted().toArray();
        for(double angle:values){double lower=(angle+minimumAngle)%circle,upper=(angle+circle-minimumAngle)%circle;
            if(lower<=upper){if(inRange(values,lower,upper))return true;}
            else if(inRange(values,lower,circle)||inRange(values,-Double.MIN_VALUE,upper))return true;
        }return false;
    }
    private static boolean inRange(double[] values,double lower,double upper){int i=Arrays.binarySearch(values,lower);if(i<0)i=-i-1;else while(i<values.length&&values[i]<=lower)i++;return i<values.length&&values[i]<upper;}
}
