package dev.mineagent.runtime.neoforge.client.objects;
import org.joml.Matrix4f;import java.util.*;
/** Opt-in observation of the exact matrices submitted by the native renderer. No simulation or model calls. */
public final class CreatureRigTelemetry {
 public static boolean enabled(){return Boolean.getBoolean("mineagent.nativeAcceptanceSmoke");}
 private static final Map<UUID,Map<String,Trace>> traces=new HashMap<>();
 private static final class Trace{int draws;String event;float[] first,last,min,max;}
 public static void record(UUID entity,String bone,String event,Matrix4f rootInverse,Matrix4f submitted){if(!enabled())return;var values=new Matrix4f(rootInverse).mul(submitted).get(new float[16]);var t=traces.computeIfAbsent(entity,k->new LinkedHashMap<>()).computeIfAbsent(bone,k->new Trace());if(t.first==null){t.first=values.clone();t.min=values.clone();t.max=values.clone();}for(int i=0;i<16;i++){t.min[i]=Math.min(t.min[i],values[i]);t.max[i]=Math.max(t.max[i],values[i]);}t.last=values;t.event=event;t.draws++;}
 public static Map<String,Object> snapshot(UUID entity){var out=new LinkedHashMap<String,Object>();for(var e:traces.getOrDefault(entity,Map.of()).entrySet()){var t=e.getValue();out.put(e.getKey(),Map.of("draws",t.draws,"event",t.event,"first",t.first,"last",t.last,"min",t.min,"max",t.max));}return out;}
 public static double variation(UUID entity,String bone,boolean translation){var t=traces.getOrDefault(entity,Map.of()).get(bone);if(t==null)return 0;double n=0;for(int i=translation?12:0;i<(translation?15:16);i++)n=Math.max(n,t.max[i]-t.min[i]);return n;}
 public static void clear(){traces.clear();}
 private CreatureRigTelemetry(){}
}
