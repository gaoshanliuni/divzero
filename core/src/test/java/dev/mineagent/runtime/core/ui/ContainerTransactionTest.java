package dev.mineagent.runtime.core.ui;
import dev.mineagent.runtime.api.ui.ContainerProtocol.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class ContainerTransactionTest {
    static class Menu implements ContainerTransaction.Port {
        String digest="before";int calls,closed;boolean valid=true;
        public boolean valid(){return valid;}public Raw capture(){return new Raw(4,22,"minecraft:generic_9x3",UUID.randomUUID().toString(),List.of(),Item.empty(),digest);}
        public void execute(Action action){calls++;digest="after";}
        public void close(){closed++;valid=false;}
    }
    @Test void staleRequestsDoNotTouchNativeRulesAndCompletedOperationIsNotReplayed(){
        var menu=new Menu();var tx=new ContainerTransaction(menu,8);var first=tx.read();assertEquals(1,first.revision());
        var action=new Action("CLICK",0,0,"QUICK_MOVE",List.of());var id=UUID.randomUUID();
        assertThrows(IllegalStateException.class,()->tx.apply(id,0,action));assertEquals(0,menu.calls);
        var result=tx.apply(id,1,action);assertTrue(result.changed());assertEquals(2,result.state().revision());
        assertEquals(result,tx.apply(id,1,action));assertEquals(1,menu.calls);
        assertThrows(IllegalStateException.class,()->tx.apply(id,2,action));assertEquals(1,menu.calls);
        menu.digest="external change";assertEquals(3,tx.read().revision());assertThrows(IllegalStateException.class,()->tx.apply(UUID.randomUUID(),2,action));
        tx.close();tx.close();assertEquals(1,menu.closed);assertThrows(IllegalStateException.class,tx::read);
    }
    @Test void authorityIsRecheckedBeforeReceiptReplayAndInventoryVersionIgnoresUnchangedNativeStateId(){
        var menu=new Menu();var tx=new ContainerTransaction(menu,2);assertEquals(tx.read().revision(),tx.read().revision());
        var id=UUID.randomUUID();var action=new Action("DRAG",-999,0,"QUICK_CRAFT",List.of(1,2,3));tx.apply(id,1,action);menu.valid=false;
        assertThrows(IllegalStateException.class,()->tx.apply(id,1,action));assertEquals(1,menu.calls);
    }
    @Test void failedPostMutationReadClosesMenuSoAnUnknownResultCannotBeReplayed(){
        var menu=new Menu(){public Raw capture(){if(calls>0)throw new IllegalStateException("codec failure");return super.capture();}};
        var tx=new ContainerTransaction(menu,4);var action=new Action("CLICK",0,0,"PICKUP",List.of());
        assertThrows(IllegalStateException.class,()->tx.apply(UUID.randomUUID(),1,action));assertEquals(1,menu.closed);
        assertThrows(IllegalStateException.class,()->tx.apply(UUID.randomUUID(),1,action));assertEquals(1,menu.calls);
    }
}
