package dev.mineagent.runtime.core.memory;
import org.junit.jupiter.api.Test;import org.junit.jupiter.api.io.TempDir;import java.nio.file.Path;import java.time.*;import java.util.*;import static org.junit.jupiter.api.Assertions.*;
class DialogueMemoryStoreTest {
 @TempDir Path root;
 @Test void scopeExpiryReplacementAndForgetSurviveReopen()throws Exception{var world=UUID.randomUUID();var player=UUID.randomUUID();var agent=UUID.randomUUID();Path file=root.resolve("test.db");Clock c=Clock.fixed(Instant.ofEpochMilli(10000),ZoneOffset.UTC);UUID id;
  try(var s=new DialogueMemoryStore(file,world,player,agent,c)){var first=s.remember("FACT","坐标","旧位置",3);id=first.id();assertEquals(1,s.inspect("",0).get("total"));var second=s.remember("FACT","坐标","新位置",4);assertEquals(id,second.id());assertEquals(2,second.revision());assertEquals(1,s.inspect("新位置",0).get("total"));assertEquals(0,s.inspect("旧位置",0).get("total"));}
  try(var s=new DialogueMemoryStore(file,world,UUID.randomUUID(),agent,c)){assertEquals(0,s.inspect("",0).get("total"));}
  try(var s=new DialogueMemoryStore(file,world,player,agent,Clock.offset(c,Duration.ofSeconds(5)))){assertEquals(0,s.inspect("",0).get("total"));var e=s.remember("FACT","坐标","再次更新",0);assertEquals(3,e.revision());assertThrows(IllegalStateException.class,()->s.forget(id,1));s.forget(id,3);assertEquals(0,s.inspect("",0).get("total"));assertEquals(5,s.remember("FACT","坐标","忘记后重新记住",0).revision());}
 }
}
