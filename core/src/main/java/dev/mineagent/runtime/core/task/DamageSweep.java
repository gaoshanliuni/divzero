package dev.mineagent.runtime.core.task;

/** Continuous relative collision: a fast shot and a moving body must coincide in time. */
public final class DamageSweep {
    public record Box(double x, double y, double z, double hx, double hy, double hz) {
        public Box {
            if (!Double.isFinite(x+y+z+hx+hy+hz) || hx<0 || hy<0 || hz<0) throw new IllegalArgumentException("DAMAGE_BOX");
        }
    }

    public static boolean intersects(Box bodyFrom, Box bodyTo, Box attackFrom, Box attackTo) {
        double[] start={bodyFrom.x-attackFrom.x,bodyFrom.y-attackFrom.y,bodyFrom.z-attackFrom.z};
        double[] end={bodyTo.x-attackTo.x,bodyTo.y-attackTo.y,bodyTo.z-attackTo.z};
        double[] radius={Math.max(bodyFrom.hx,bodyTo.hx)+Math.max(attackFrom.hx,attackTo.hx),
                Math.max(bodyFrom.hy,bodyTo.hy)+Math.max(attackFrom.hy,attackTo.hy),
                Math.max(bodyFrom.hz,bodyTo.hz)+Math.max(attackFrom.hz,attackTo.hz)};
        double enter=0,leave=1;
        for(int axis=0;axis<3;axis++) {
            double speed=end[axis]-start[axis];
            if(Math.abs(speed)<1e-9) { if(Math.abs(start[axis])>radius[axis])return false; continue; }
            double a=(-radius[axis]-start[axis])/speed,b=(radius[axis]-start[axis])/speed;
            enter=Math.max(enter,Math.min(a,b));leave=Math.min(leave,Math.max(a,b));
            if(enter>leave)return false;
        }
        return true;
    }
    private DamageSweep() {}
}
