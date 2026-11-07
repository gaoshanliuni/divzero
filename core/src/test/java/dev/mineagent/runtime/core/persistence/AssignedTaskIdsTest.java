package dev.mineagent.runtime.core.persistence;
import org.junit.jupiter.api.Test;import org.junit.jupiter.api.io.TempDir;import java.nio.file.*;import java.util.*;import java.time.Clock;import static org.junit.jupiter.api.Assertions.*;
class AssignedTaskIdsTest {
 @TempDir Path root;
 @Test void newTaskBindingsSurviveReopenAndSeparateOwnersAndWorlds()throws Exception{
  Path file=root.resolve("runtime.db");UUID world=UUID.randomUUID(),owner=UUID.randomUUID(),agent=UUID.randomUUID(),operation=UUID.randomUUID(),task;
  try(var manager=dev.mineagent.runtime.core.task.TaskManager.open(file,world,Clock.systemUTC())){task=manager.startTaskId(owner,operation);var started=manager.createIdempotent(operation,agent,owner,"gather",1,List.of(new dev.mineagent.runtime.core.task.TaskStepSpec("collect",Set.of())));assertEquals(task,started.taskId());assertEquals(task,manager.createIdempotent(operation,agent,owner,"gather",1,List.of(new dev.mineagent.runtime.core.task.TaskStepSpec("collect",Set.of()))).taskId());assertThrows(IllegalArgumentException.class,()->manager.createIdempotent(operation,agent,owner,"different",1,List.of(new dev.mineagent.runtime.core.task.TaskStepSpec("collect",Set.of()))));}
  try(var manager=dev.mineagent.runtime.core.task.TaskManager.open(file,world,Clock.systemUTC())){assertEquals(task,manager.startTaskId(owner,operation));assertNotEquals(task,manager.startTaskId(UUID.randomUUID(),operation));}
  assertNotEquals(task,AssignedTaskIds.resolve(file,UUID.randomUUID(),owner,operation));
 }
 @Test void canonicalRequestComparisonIsExactAndPreservesNullableContext()throws Exception{
  assertEquals(ExactRequestSnapshot.encode(Map.of("b",2,"a",1)),ExactRequestSnapshot.encode(Map.of("a",1,"b",2)));assertNotEquals(ExactRequestSnapshot.encode(List.of("ab","c")),ExactRequestSnapshot.encode(List.of("a","bc")));assertTrue(ExactRequestSnapshot.encode(Arrays.asList(null,"内容")).contains("null"));
 }
}
