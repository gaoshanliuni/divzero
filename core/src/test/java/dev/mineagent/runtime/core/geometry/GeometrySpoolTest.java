package dev.mineagent.runtime.core.geometry;
import org.junit.jupiter.api.Test;import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;import java.util.*;import static org.junit.jupiter.api.Assertions.*;
class GeometrySpoolTest {
 @TempDir Path root;
 @Test void streamsBeyondOldCellLimitWithBoundedPages()throws Exception{
  try(var spool=GeometrySpool.create(root.resolve("large.db"))){var summary=spool.generate("{\"origin\":[0,0,0],\"parts\":[{\"kind\":\"box\",\"min\":[0,0,0],\"max\":[2047,0,128],\"material\":\"minecraft:stone\"}]}",()->true);assertEquals(264192,spool.size());assertEquals(2047,summary.max().x());long cursor=0,count=0;while(true){var page=spool.page(cursor,512,false);if(page.isEmpty())break;assertTrue(page.size()<=512);count+=page.size();cursor=page.getLast().cursor();}assertEquals(264192,count);}
 }
 @Test void exactExtent2048AndLargerRejected(){var cells=new ArrayList<WorldGeometry.Cell>();WorldGeometry.stream("{\"origin\":[0,0,0],\"parts\":[{\"kind\":\"line\",\"points\":[[0,0,0],[2047,2047,2047]],\"material\":\"minecraft:stone\"}]}",cells::add);assertTrue(cells.stream().anyMatch(c->c.pos().equals(new WorldGeometry.Pos(2047,2047,2047))));assertThrows(IllegalArgumentException.class,()->WorldGeometry.stream("{\"origin\":[0,0,0],\"parts\":[{\"kind\":\"line\",\"points\":[[0,0,0],[2048,0,0]],\"material\":\"minecraft:stone\"}]}",c->{}));}
 @Test void overlapMaskSnapshotAndCursorStayStable()throws Exception{
  try(var spool=GeometrySpool.create(root.resolve("overlap.db"))){String p="{\"kind\":\"box\",\"min\":[0,0,0],\"max\":[2,0,0],\"material\":\"minecraft:stone\"}";spool.generate("{\"origin\":[0,0,0],\"parts\":["+p+","+p.replace("stone","gold_block")+"]}",()->true);assertEquals(3,spool.size());var page=spool.page(0,512,false);assertEquals("minecraft:gold_block",page.getFirst().state());spool.snapshots(List.of(new GeometrySpool.Snapshot(page.get(1).cursor(),"minecraft:air","minecraft:gold_block",true)));assertEquals(1,spool.page(0,512,true).size());assertEquals("minecraft:air",spool.page(0,512,true).getFirst().before());assertTrue(spool.page(page.getLast().cursor(),512,true).isEmpty());}
 }
 @Test void streamingMatchesInMemoryAcrossAllTransforms()throws Exception{
  String src="{\"origin\":[0,0,0],\"parts\":[{\"kind\":\"curve\",\"points\":[[0,0,0],[0,4,0],[4,4,0],[4,0,0]],\"material\":{\"mode\":\"checker\",\"states\":[\"minecraft:stone\",\"minecraft:andesite\"]},\"transforms\":[{\"op\":\"mirror\",\"axis\":\"x\"},{\"op\":\"array\",\"count\":[2,1,1],\"step\":[2,0,0]}]}]}";var expected=new LinkedHashMap<WorldGeometry.Pos,WorldGeometry.Cell>();for(var c:WorldGeometry.parse(src).cells())expected.put(c.pos(),c);var actual=new LinkedHashMap<WorldGeometry.Pos,WorldGeometry.Cell>();WorldGeometry.stream(src,c->actual.put(c.pos(),c));assertEquals(expected,actual);
 }
 @Test void streamingArrayHasNoOldInstanceOrCellCap(){long[] count={0};var r=WorldGeometry.stream("{\"origin\":[0,0,0],\"parts\":[{\"kind\":\"box\",\"min\":[0,0,0],\"max\":[0,0,0],\"material\":\"minecraft:stone\",\"transforms\":[{\"op\":\"array\",\"count\":[65,65,64],\"step\":[1,1,1]}]}]}",c->count[0]++);assertEquals(270400,count[0]);assertEquals(new WorldGeometry.Pos(64,64,63),r.max());}
 @Test void cancellationIsNotReportedAsComplete()throws Exception{try(var spool=GeometrySpool.create(root.resolve("cancel.db"))){assertThrows(RuntimeException.class,()->spool.generate("{\"origin\":[0,0,0],\"parts\":[{\"kind\":\"box\",\"min\":[0,0,0],\"max\":[3,3,3],\"material\":\"minecraft:stone\"}]}",()->false));assertEquals(0,spool.size());}}
}
