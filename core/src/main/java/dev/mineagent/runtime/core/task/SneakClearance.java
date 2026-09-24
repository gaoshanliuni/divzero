package dev.mineagent.runtime.core.task;
import java.util.function.BiPredicate;
/** A floor/step collision alone is not a reason to crouch; crouching must actually clear the obstacle. */
public final class SneakClearance {
 public record Point(double x,double y,double z){}
 public static boolean required(Point from,Point to,BiPredicate<Point,Boolean> clear){
  double dx=to.x-from.x,dy=to.y-from.y,dz=to.z-from.z,length=Math.sqrt(dx*dx+dy*dy+dz*dz);
  if(length>1.5){double scale=1.5/length;dx*=scale;dy*=scale;dz*=scale;}
  for(int i=0;i<=8;i++){var p=i==0?from:new Point(from.x+dx*i/8,Math.max(from.y,from.y+dy),from.z+dz*i/8);if(!clear.test(p,false)&&clear.test(p,true))return true;}
  return false;
 }
 private SneakClearance(){}
}
