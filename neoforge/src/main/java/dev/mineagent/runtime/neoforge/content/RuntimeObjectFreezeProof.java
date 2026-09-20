package dev.mineagent.runtime.neoforge.content;

import java.util.*;

/** Acceptance fixture only. Server positions are exact; clients use the pinned vanilla wire precision. */
public final class RuntimeObjectFreezeProof {
    // Minecraft 26.1.2 VecDeltaCodec.encode/decode and Mth.packDegrees/unpackDegrees.
    public static final double POSITION_QUANTUM=1.0/4096.0,ROTATION_QUANTUM=360.0/256.0;
    public record Pose(UUID id,double x,double y,double z,float yaw,String asset){
        public Pose{if(id==null||!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(z)||!Float.isFinite(yaw)||asset==null||!asset.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("OBJECT_POSE_INVALID");}
    }
    private RuntimeObjectFreezeProof(){}
    private static boolean distinct(List<Pose> poses){return poses!=null&&!poses.isEmpty()&&poses.stream().noneMatch(Objects::isNull)&&poses.stream().map(Pose::id).distinct().count()==poses.size();}
    public static boolean sameServer(List<Pose> before,List<Pose> after){return distinct(before)&&distinct(after)&&new HashSet<>(before).equals(new HashSet<>(after));}
    public static boolean aligned(List<Pose> client,List<Pose> server){
        if(!distinct(client)||!distinct(server)||client.size()!=server.size())return false;
        var expected=new HashMap<UUID,Pose>();server.forEach(p->expected.put(p.id(),p));
        return client.stream().allMatch(p->{var s=expected.get(p.id());if(s==null||!p.asset().equals(s.asset()))return false;
            double yaw=Math.abs(Math.IEEEremainder((double)p.yaw()-s.yaw(),360));
            return Math.abs(p.x()-s.x())<=POSITION_QUANTUM+1e-9&&Math.abs(p.y()-s.y())<=POSITION_QUANTUM+1e-9&&Math.abs(p.z()-s.z())<=POSITION_QUANTUM+1e-9&&yaw<=ROTATION_QUANTUM+1e-6;
        });
    }
    public static Pose sample(RuntimeObjectEntity entity){return new Pose(entity.getUUID(),entity.getX(),entity.getY(),entity.getZ(),entity.getYRot(),entity.header().asset());}
}
