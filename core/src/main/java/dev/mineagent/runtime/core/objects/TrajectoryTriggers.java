package dev.mineagent.runtime.core.objects;
import java.util.List;
/** Geometric trigger query only. No score, object type or game-specific rule. */
public final class TrajectoryTriggers {
    private TrajectoryTriggers(){}
    public record Point(double x,double y,double z){}
    /** Whole AABB clears a horizontal circular aperture downwards along an actual motion segment. */
    public static boolean downwardCircle(List<Point> path,double cx,double y,double cz,double radius,RuntimeMesh.Collision box){
        for(double v:new double[]{cx,y,cz,radius})if(!Double.isFinite(v))throw new IllegalArgumentException("TRIGGER_FINITE");
        if(radius<=0||radius>32||path.size()>16)throw new IllegalArgumentException("TRIGGER_BOUNDS");
        for(int i=1;i<path.size();i++){
            var a=path.get(i-1);var b=path.get(i);double topA=a.y()+box.height(),topB=b.y()+box.height();
            if(topA<=y||topB>y||topB>=topA)continue;
            double t=(topA-y)/(topA-topB),x=a.x()+(b.x()-a.x())*t-cx,z=a.z()+(b.z()-a.z())*t-cz;
            double dx=Math.abs(x)+box.width()/2,dz=Math.abs(z)+box.depth()/2;
            if(dx*dx+dz*dz<radius*radius)return true;
        }
        return false;
    }
}
