package dev.mineagent.runtime.core.building;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ConstructionLedgerTest {
    @TempDir Path directory;
    private static final String SOURCE="""
        {"id":"house","name":"House","dimension":"minecraft:overworld","origin":[0,80,0],"components":[
        {"id":"floor","parts":[{"kind":"box","min":[0,0,0],"max":[2,0,2],"material":"minecraft:stone"}]},
        {"id":"roof","depends_on":["floor"],"parts":[{"kind":"box","min":[0,3,0],"max":[2,3,2],"material":"minecraft:oak_planks"}]}],
        "checks":[{"id":"entry","component":"floor","kind":"clearance","min":[1,1,0],"max":[1,2,0]}]}
        """;
    private static void sampled(ConstructionLedger ledger,long revision)throws Exception {
        var samples=new ArrayList<ConstructionLedger.Sample>();for(var cell:ledger.page(revision,0,false)){String before=cell.expected()==null?"minecraft:dirt":cell.expected();samples.add(new ConstructionLedger.Sample(cell.sequence(),before,cell.material(),cell.baseline()==null?before:cell.baseline()));}ledger.samples(samples);
    }
    private static void finish(ConstructionLedger ledger,long tick)throws Exception {ledger.progress("APPLYING","VERIFY",0,tick);ledger.finish(tick);}
    @Test void compactFilesKeepFullWorldOwnerAgentBinding()throws Exception {
        var scope=new ConstructionCatalog.Scope(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID());var other=new ConstructionCatalog.Scope(UUID.randomUUID(),scope.owner(),scope.agent());
        var file=ConstructionCatalog.register(directory,scope,"home");assertTrue(file.getFileName().toString().length()<64);assertEquals(file,ConstructionCatalog.register(directory,scope,"home"));assertFalse(file.equals(ConstructionCatalog.register(directory,other,"home")));
        assertEquals(1,ConstructionCatalog.list(directory,scope,0).size());try(var ledger=new ConstructionLedger(file)){ledger.bind(scope,"home");assertThrows(SecurityException.class,()->ledger.bind(other,"home"));}
    }
    @Test void documentReferencesAreStableForDesignsAndRotateForNewReports()throws Exception{
        Path file=directory.resolve("references.db");String designId,footprint;
        try(var ledger=new ConstructionLedger(file)){ledger.plan(0,SOURCE,()->true);designId=ledger.documentId("design",1);footprint=ledger.footprintHash(1);assertEquals(footprint,ledger.footprintHash(1));String before=ledger.documentId("report",1);ledger.start(1,"APPLY");assertNotEquals(before,ledger.documentId("report",1));sampled(ledger,1);finish(ledger,5);String operation=ledger.head().operation(),report=ledger.documentId("report",1);ledger.verified(operation,1,true,"checked actual positions");assertNotEquals(report,ledger.documentId("report",1));assertEquals(designId,ledger.documentId("design",1));}
        try(var ledger=new ConstructionLedger(file)){assertEquals(designId,ledger.documentId("design",1));assertEquals(footprint,ledger.footprintHash(1));}
    }
    @Test void localRoofChangeKeepsFloorAndRestoresOriginalWorldInsteadOfAir()throws Exception {
        try(var ledger=new ConstructionLedger(directory.resolve("house.db"))){
            ledger.plan(0,SOURCE,()->true);assertEquals(18,ledger.count(1));ledger.start(1,"APPLY");sampled(ledger,1);finish(ledger,100);
            ledger.plan(1,SOURCE.replace("[0,3,0]","[0,5,0]").replace("[2,3,2]","[2,5,2]"),()->true);sampled(ledger,2);
            var rows=ledger.page(2,0,false);assertEquals(27,rows.size());assertEquals(9,rows.stream().filter(c->c.component().equals("floor")&&!c.changed()).count());
            var removed=rows.stream().filter(c->!c.present()).toList();assertEquals(9,removed.size());assertTrue(removed.stream().allMatch(c->c.after().equals("minecraft:dirt")));
            assertTrue(rows.stream().noneMatch(c->c.y()>80&&c.y()<83));
        }
    }
    @Test void pauseAndDurableReservationDoNotAllowNewRevisionsOrBlindReplay()throws Exception {
        Path db=directory.resolve("house.db");try(var ledger=new ConstructionLedger(db)){ledger.plan(0,SOURCE,()->true);ledger.start(1,"APPLY");sampled(ledger,1);ledger.progress("APPLYING","WRITE",0,0);ledger.pause();assertEquals("PAUSED",ledger.head().status());assertThrows(IllegalStateException.class,()->ledger.plan(1,SOURCE,()->true));ledger.resume();ledger.reserve(0);}
        try(var ledger=new ConstructionLedger(db)){assertEquals("RESERVED",ledger.head().phase());ledger.fail("UNKNOWN","crash");assertThrows(IllegalStateException.class,()->ledger.start(1,"APPLY"));assertThrows(IllegalStateException.class,ledger::resume);assertThrows(IllegalStateException.class,()->ledger.plan(1,SOURCE,()->true));}
    }
    @Test void undoRedoVersionsAndVerificationAreOperationBound()throws Exception {
        try(var ledger=new ConstructionLedger(directory.resolve("house.db"))){ledger.plan(0,SOURCE,()->true);ledger.start(1,"APPLY");sampled(ledger,1);finish(ledger,5);String operation=ledger.head().operation();ledger.verified(operation,1,true,"verified");assertEquals("VERIFIED",ledger.head().status());ledger.start(1,"UNDO");finish(ledger,8);assertEquals(0,ledger.head().activeRevision());ledger.start(1,"REDO");finish(ledger,11);assertEquals(1,ledger.head().activeRevision());assertThrows(IllegalStateException.class,()->ledger.verified(operation,1,true,"stale"));assertEquals("UNVERIFIED",ledger.head().status());assertEquals(3,ledger.history(0).size());}
    }
    @Test void failedRasterLeavesLastDesignIntact()throws Exception {try(var ledger=new ConstructionLedger(directory.resolve("house.db"))){ledger.plan(0,SOURCE,()->true);assertThrows(Exception.class,()->ledger.plan(1,SOURCE,()->false));assertEquals(1,ledger.head().revision());assertEquals(18,ledger.count(1));}}
    @Test void nativeLeaseSurvivesSafeRestartButNeverReplaysReservedEffects()throws Exception {
        var session=UUID.randomUUID();var operation=UUID.randomUUID();Path file=directory.resolve("native.db");long cell;
        try(var ledger=new ConstructionLedger(file)){
            ledger.plan(0,SOURCE,()->true);ledger.claimNative(session,1);sampled(ledger,1);cell=ledger.page(1,0,false).getFirst().sequence();
            ledger.progress("APPLYING","WRITE",0,0);
            assertThrows(IllegalStateException.class,()->ledger.claimNative(UUID.randomUUID(),1));
            assertThrows(IllegalStateException.class,()->ledger.nativePrepared(UUID.randomUUID(),operation,cell,"not authorized"));
        }
        try(var ledger=new ConstructionLedger(file)){
            assertEquals("WRITE",ledger.claimNative(session,1).phase());
            ledger.nativePrepared(session,operation,cell,"durable before native use");ledger.reserve(0);
        }
        try(var ledger=new ConstructionLedger(file)){
            assertThrows(IllegalStateException.class,()->ledger.claimNative(session,1));
            assertThrows(IllegalStateException.class,()->ledger.claimNative(UUID.randomUUID(),1));
            ledger.nativeObserved(session,operation,"VERIFIED_NATIVE_PLACEMENT","actual state and inventory");
            assertThrows(IllegalStateException.class,()->ledger.nativeObserved(session,operation,"VERIFIED_NATIVE_PLACEMENT","duplicate"));
            ledger.applied(List.of(cell),cell,30);assertEquals(cell,ledger.claimNative(session,1).cursor());
        }
    }
    @Test void unrelatedInspectionOrRevisionCannotCompleteTheConversation(){var gate=new BuildingCompletionGate();gate.observe("apply_building",Map.of("id","home","revision",2,"operation","op","status","UNVERIFIED"));for(var result:List.of(Map.<String,Object>of("id","other","revision",2,"operation","op","verification","VERIFIED"),Map.<String,Object>of("id","home","revision",1,"operation","op","verification","VERIFIED"),Map.<String,Object>of("id","home","revision",2,"operation","old","verification","VERIFIED"))){gate.observe("verify_building",result);assertTrue(gate.waiting());}var correct=Map.<String,Object>of("id","home","revision",2,"operation","op","verification","VERIFIED");gate.observe("inspect_buildings",correct);assertTrue(gate.waiting());gate.observe("verify_building",correct);assertFalse(gate.waiting());}
    @Test void knownRejectedNativeUseKeepsCursorAndResumesWithoutReplayingTheOldOperation()throws Exception{
        Path file=directory.resolve("rejected-native.db");UUID session=UUID.randomUUID(),operation=UUID.randomUUID();long cell;
        try(var ledger=new ConstructionLedger(file)){
            ledger.plan(0,SOURCE,()->true);ledger.claimNative(session,1);sampled(ledger,1);ledger.progress("APPLYING","WRITE",0,0);cell=ledger.page(1,0,false).getFirst().sequence();
            ledger.nativePrepared(session,operation,cell,"before native use");ledger.reserve(0);
            assertThrows(IllegalStateException.class,()->ledger.nativeNotApplied(UUID.randomUUID(),operation,"not applied","wrong lease"));assertEquals("RESERVED",ledger.head().phase());
            ledger.nativeNotApplied(session,operation,"actual=air; materialsConsumed=0; nativeAccepted=false","PLACE_REJECTED_NO_CHANGE component=floor position=[0,80,0]");
            assertEquals("PARTIAL",ledger.head().status());assertEquals("WRITE",ledger.head().phase());assertEquals(0,ledger.head().cursor());assertEquals(0,ledger.head().mutationTick());
        }
        try(var ledger=new ConstructionLedger(file)){
            UUID next=UUID.randomUUID();assertEquals("WRITE",ledger.claimNative(next,1).phase());assertEquals(0,ledger.head().cursor());
            assertThrows(Exception.class,()->ledger.nativePrepared(next,operation,cell,"cannot reuse settled operation"));
            ledger.nativePrepared(next,UUID.randomUUID(),cell,"new observation and explicit retry");
        }
    }

}
