package dev.mineagent.runtime.core.objects;
/** Bounded server-tick physics; Native collision resolution supplies the actual hit axes. */
public final class ObjectPhysics {
    public static final double MAX_SPEED=4;
    private ObjectPhysics(){}
    public record Motion(double x,double y,double z){public Motion{if(!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(z))throw new IllegalArgumentException("OBJECT_MOTION_INVALID");}}
    public static Motion limit(Motion v){double n=Math.sqrt(v.x*v.x+v.y*v.y+v.z*v.z);return n>MAX_SPEED?new Motion(v.x*MAX_SPEED/n,v.y*MAX_SPEED/n,v.z*MAX_SPEED/n):v;}
    public static Motion gravity(Motion v,RuntimeMesh.Physics p){return p.dynamic()?limit(new Motion(v.x,v.y-p.gravity(),v.z)):new Motion(0,0,0);}
    public static Motion bounce(Motion v,boolean x,boolean y,boolean z,RuntimeMesh.Physics p){return limit(new Motion(v.x*(x?-p.restitution():1)*p.drag(),v.y*(y?-p.restitution():1)*p.drag(),v.z*(z?-p.restitution():1)*p.drag()));}
    public static double settleVertical(double result,double incoming,boolean hit,RuntimeMesh.Physics p){if(!Double.isFinite(result)||!Double.isFinite(incoming))throw new IllegalArgumentException("OBJECT_MOTION_INVALID");return hit&&incoming<0&&Math.abs(incoming)<=Math.max(0.08,p.gravity()*1.5)?0:result;}
    public static Motion spring(Motion v,Motion offset,double stiffness,double damping,RuntimeMesh.Physics p){if(!Double.isFinite(stiffness)||stiffness<0||stiffness>1||!Double.isFinite(damping)||damping<0||damping>1)throw new IllegalArgumentException("OBJECT_SPRING_INVALID");return limit(new Motion(v.x+(offset.x*stiffness-v.x*damping)/p.mass(),v.y+(offset.y*stiffness-v.y*damping)/p.mass(),v.z+(offset.z*stiffness-v.z*damping)/p.mass()));}
}
