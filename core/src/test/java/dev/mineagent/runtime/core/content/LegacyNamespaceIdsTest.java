package dev.mineagent.runtime.core.content;
import org.junit.jupiter.api.Test;import org.junit.jupiter.api.io.TempDir;import java.nio.file.*;import java.util.*;import static org.junit.jupiter.api.Assertions.*;
class LegacyNamespaceIdsTest {
 @TempDir Path root;
 @Test void emptyNewScopesDoNotRunLegacyDerivationOrLookup()throws Exception{
  String id=LegacyNamespaceIds.resolve(root,LegacyNamespaceIds.Kind.UI_STATE_SCOPE,"new-world",false,old->{throw new AssertionError("no legacy candidates exist");});assertTrue(id.startsWith("scope-"));assertEquals(id,LegacyNamespaceIds.resolve(root,LegacyNamespaceIds.Kind.UI_STATE_SCOPE,"new-world",true,old->{throw new AssertionError("persisted mapping must be reused");}));
 }
 @Test void persistsMigrationDecisionAndDoesNotProbeAgain()throws Exception{
  var count=new java.util.concurrent.atomic.AtomicInteger();String first=LegacyNamespaceIds.resolve(root,LegacyNamespaceIds.Kind.DIALOGUE_MEMORY,"owner|agent",id->{count.incrementAndGet();return true;});
  String before=Files.readString(root.resolve("namespace-map-v2.json"));assertTrue(first.startsWith("dialogue_memory_"));assertEquals(first,LegacyNamespaceIds.resolve(root,LegacyNamespaceIds.Kind.DIALOGUE_MEMORY,"owner|agent",id->{throw new AssertionError("legacy derivation/probe repeated");}));assertEquals(1,count.get());assertEquals(before,Files.readString(root.resolve("namespace-map-v2.json")));
 }
 @Test void missingFirstWorldDoesNotHideSecondWorldLegacyMemory()throws Exception{
  UUID owner=UUID.randomUUID(),agent=UUID.randomUUID(),empty=UUID.randomUUID(),legacyWorld=UUID.randomUUID();
  String legacy=LegacyNamespaceIds.resolve(root.resolve("fixture-legacy"),LegacyNamespaceIds.Kind.DIALOGUE_MEMORY,owner+"|"+agent,id->true);Path file=root.resolve("runtime.db");UUID id=UUID.randomUUID();
  var entry=new dev.mineagent.runtime.core.memory.DialogueMemoryStore.Entry(id,"FACT","home","world-two home",1,1000,0,false,"player",0,List.of());
  try(var db=new dev.mineagent.runtime.core.persistence.SqliteRuntimeRepository(file)){assertTrue(db.compareAndSet(legacyWorld,legacy,id.toString(),0,new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(entry),1000).accepted());}
  try(var memory=new dev.mineagent.runtime.core.memory.DialogueMemoryStore(file,empty,owner,agent,java.time.Clock.systemUTC())){assertEquals(0,memory.inspect("",0).get("total"));}
  try(var memory=new dev.mineagent.runtime.core.memory.DialogueMemoryStore(file,legacyWorld,owner,agent,java.time.Clock.systemUTC())){assertEquals(1,memory.inspect("",0).get("total"));assertEquals(id,memory.remember("FACT","home","updated home",0).id());}
 }
}
