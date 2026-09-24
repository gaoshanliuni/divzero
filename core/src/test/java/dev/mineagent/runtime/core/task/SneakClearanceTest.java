package dev.mineagent.runtime.core.task;
import org.junit.jupiter.api.Test;import static org.junit.jupiter.api.Assertions.*;
class SneakClearanceTest {
 @Test void openStepsDoNotCrouchAndLowCeilingDoes(){
  var start=new SneakClearance.Point(0,0,0);var up=new SneakClearance.Point(1,1,0);
  assertFalse(SneakClearance.required(start,up,(p,c)->p.x()<.4||p.y()>=1));
  assertFalse(SneakClearance.required(start,new SneakClearance.Point(1,0,0),(p,c)->p.x()<.4));
  assertTrue(SneakClearance.required(start,new SneakClearance.Point(1,0,0),(p,c)->p.x()<.4||c));
  assertFalse(SneakClearance.required(new SneakClearance.Point(0,1,0),start,(p,c)->true));
 }
}
