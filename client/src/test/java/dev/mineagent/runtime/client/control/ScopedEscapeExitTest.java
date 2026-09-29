package dev.mineagent.runtime.client.control;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static dev.mineagent.runtime.client.control.ScopedEscapeExit.Result.*;

class ScopedEscapeExitTest {
    @Test void needsTwoDistinctPressesAndConsumesFirstWithoutOpeningPause(){
        var keys=new ScopedEscapeExit();
        assertEquals(ARMED,keys.handle(true,true,true,1,100));
        assertEquals(CONSUME,keys.handle(true,true,true,2,150));
        assertEquals(CONSUME,keys.handle(true,true,true,1,160));
        assertEquals(CONSUME,keys.handle(true,true,true,0,180));
        assertEquals(EXIT,keys.handle(true,true,true,1,300));
    }
    @Test void menuEscapeDoesNotCountAfterReturningToGame(){
        var keys=new ScopedEscapeExit();
        assertEquals(PASS,keys.handle(true,false,true,1,100));
        assertEquals(CONSUME,keys.handle(true,true,true,2,150));
        keys.handle(true,true,true,0,160);
        assertEquals(ARMED,keys.handle(true,true,true,1,200));
        assertEquals(PASS,keys.handle(true,false,true,0,210));
        assertEquals(ARMED,keys.handle(true,true,true,1,300));
    }
    @Test void expirationFocusAndNewSessionCannotCompleteAnOldGesture(){
        var keys=new ScopedEscapeExit();
        assertEquals(ARMED,keys.handle(true,true,true,1,0));keys.handle(true,true,true,0,1);
        assertEquals(ARMED,keys.handle(true,true,true,1,601));
        assertEquals(PASS,keys.handle(true,true,false,0,620));
        assertEquals(ARMED,keys.handle(true,true,true,1,650));
        assertEquals(PASS,keys.handle(false,true,true,0,670));
        assertEquals(ARMED,keys.handle(true,true,true,1,690));
    }
}
