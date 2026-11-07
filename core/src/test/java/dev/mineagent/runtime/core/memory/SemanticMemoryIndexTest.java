package dev.mineagent.runtime.core.memory;
import org.junit.jupiter.api.Test;import org.junit.jupiter.api.io.TempDir;import java.nio.file.Path;import java.util.*;import static org.junit.jupiter.api.Assertions.*;
class SemanticMemoryIndexTest {
 @TempDir Path root;
 @Test void vectorsRequireExactOwnerAgentWorldModelAndRevision()throws Exception{
  UUID world=UUID.randomUUID(),owner=UUID.randomUUID(),agent=UUID.randomUUID(),id=UUID.randomUUID();Path file=root.resolve("memory.db");var doc=new SemanticMemoryIndex.Document("dialogue",id,1,"preferred equipment",1000,"player");
  try(var index=new SemanticMemoryIndex(file,world,owner,agent)){index.save(doc,"embedding-a",new double[]{3,4});var vectors=index.vectors(List.of(doc),"embedding-a");assertEquals(1,vectors.size());assertEquals(1,SemanticMemoryIndex.similarities(List.of(doc),vectors,new double[]{6,8}).get("dialogue_"+id+"_r1"),1e-9);assertTrue(index.vectors(List.of(doc),"embedding-b").isEmpty());var updated=new SemanticMemoryIndex.Document("dialogue",id,2,"new equipment",2000,"player");assertTrue(index.vectors(List.of(updated),"embedding-a").isEmpty());assertTrue(SemanticMemoryIndex.similarities(List.of(updated),vectors,new double[]{6,8}).isEmpty());}
  for(var scope:List.of(List.of(UUID.randomUUID(),owner,agent),List.of(world,UUID.randomUUID(),agent),List.of(world,owner,UUID.randomUUID())))try(var index=new SemanticMemoryIndex(file,scope.get(0),scope.get(1),scope.get(2))){assertTrue(index.vectors(List.of(doc),"embedding-a").isEmpty());}
 }
 @Test void semanticMatchCanRecallParaphraseButUnrelatedEntriesStayOut(){assertTrue(SemanticMemoryIndex.hybrid(0,.91,0,0,"player")>0);assertEquals(0,SemanticMemoryIndex.hybrid(0,.2,0,0,"player"));assertEquals(0,SemanticMemoryIndex.hybrid(0,null,0,0,"tool"));}
}
