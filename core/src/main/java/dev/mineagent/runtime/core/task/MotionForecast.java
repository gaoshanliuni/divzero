package dev.mineagent.runtime.core.task;

import java.util.*;

/** Short-horizon branching prediction from observations, never from an opponent's future input. */
public final class MotionForecast {
    public record Point(double x,double y,double z){
        public Point add(Point p){return new Point(x+p.x,y+p.y,z+p.z);}
        public Point scale(double v){return new Point(x*v,y*v,z*v);}
        public Point subtract(Point p){return new Point(x-p.x,y-p.y,z-p.z);}
        public double length(){return Math.sqrt(x*x+y*y+z*z);}
    }
    public record Sample(int tick,Point position,Point velocity,boolean grounded,double uncertainty){}
    public record Input(Point position,Point velocity,boolean grounded,double gravity,double acceleration,int age){}
    public interface Collision {Point move(Point from,Point displacement);boolean supported(Point at);}
    public static List<List<Sample>> predict(Input input,Collision collision,int horizon){
        if(horizon<1||horizon>40||!Double.isFinite(input.gravity)||input.gravity<0)throw new IllegalArgumentException("PREDICTION_INPUT");
        var branches=new ArrayList<List<Sample>>();
        for(double turn:new double[]{0,-.6,.6,-1.2,1.2}){
            var path=new ArrayList<Sample>();Point at=input.position,velocity=input.velocity;boolean ground=input.grounded;
            for(int tick=1;tick<=horizon;tick++){
                double angle=turn*Math.min(1,tick/8d),speed=Math.hypot(input.velocity.x,input.velocity.z);
                double vx=input.velocity.x*Math.cos(angle)-input.velocity.z*Math.sin(angle),vz=input.velocity.z*Math.cos(angle)+input.velocity.x*Math.sin(angle);
                double blend=Math.min(1,input.acceleration/Math.max(.01,speed));
                velocity=new Point(velocity.x+(vx-velocity.x)*blend,ground?0:velocity.y,velocity.z+(vz-velocity.z)*blend);
                var next=collision.move(at,velocity);var actual=next.subtract(at);
                ground=velocity.y<=0&&collision.supported(next);
                velocity=new Point(actual.x,ground?0:(Math.abs(actual.y-velocity.y)>.001?0:velocity.y-input.gravity)*.98,actual.z);
                at=next;path.add(new Sample(tick,at,velocity,ground,.08+Math.min(.65,input.age*.03+tick*.015)));
            }branches.add(List.copyOf(path));
        }return List.copyOf(branches);
    }
    private MotionForecast(){}
}
