package dev.mineagent.runtime.core.creature;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
class CreatureDefinitionTest {
 @TempDir Path temp;
 @Test void catalogSearchAndPagingUseActualOwnedDefinitions()throws Exception{
  UUID world=UUID.randomUUID(),owner=UUID.randomUUID();try(var store=new CreatureStore(temp.resolve("catalog.db"),world)){
   for(int i=0;i<10;i++)store.put(owner,UUID.randomUUID(),0,"{\"name\":\"新生物 "+i+"\"}");store.put(UUID.randomUUID(),UUID.randomUUID(),0,"{\"name\":\"其他人的新生物\"}");
   var first=store.catalog(owner,"新生物",0);assertEquals(10,first.get("total"));assertEquals(8,((java.util.List<?>)first.get("items")).size());assertEquals(8,first.get("nextOffset"));
   var second=store.catalog(owner,"新生物",8);assertEquals(2,((java.util.List<?>)second.get("items")).size());assertEquals(-1,second.get("nextOffset"));assertEquals(0,store.catalog(owner,"不存在",0).get("total"));
  }
 }
 @Test void parsesSevenBehaviourDefinition(){var d=CreatureDefinition.parse("""
 {"name":"星球伙伴","disposition":"neutral","food":"minecraft:wheat","rideable":true,"companion":true,
 "trades":[{"input":"minecraft:emerald","output":"minecraft:apple","outputCount":2}],
 "barter":{"input":"minecraft:gold_ingot","output":"minecraft:quartz"},
 "proximity":{"radius":3,"message":"欢迎","cooldown":100}}
 """);assertEquals("neutral",d.disposition());assertTrue(d.rideable()&&d.companion());assertEquals(2,d.trades().getFirst().outputCount());assertNotNull(d.barter());assertTrue(d.proximityRadius()>0);assertEquals(d,CreatureDefinition.parse(d.source()));}
 @Test void rejectsMalformedUnsafeOrAmbiguousFields(){for(String source:new String[]{"{}","{\"name\":\"x\",\"name\":\"y\"}","{\"name\":\"x\",\"rideable\":1}","{\"name\":\"x\",\"damage\":-1}","{\"name\":\"x\",\"disposition\":\"evil\"}","{\"name\":\"x\",\"host_script\":\"test\"}","{\"name\":\"x\",\"barter\":{\"input\":\"minecraft:apple\",\"output\":\"minecraft:apple\"}}","{\"name\":\"x\"} {}"})assertThrows(IllegalArgumentException.class,()->CreatureDefinition.parse(source),source);}
 @Test void persistsAndScopesWithCompareAndSwap()throws Exception{var path=temp.resolve("state.db");var world=UUID.randomUUID();var owner=UUID.randomUUID();var id=UUID.randomUUID();try(var s=new CreatureStore(path,world)){var v=s.put(owner,id,0,"{\"name\":\"one\"}");assertEquals(1,v.revision());assertTrue(s.get(UUID.randomUUID(),id).isEmpty());assertThrows(IllegalStateException.class,()->s.put(owner,id,0,v.source()));s.put(owner,id,1,"{\"name\":\"two\"}");assertThrows(IllegalStateException.class,()->s.put(owner,id,1,v.source()));assertEquals(1,s.list(owner,0).size());}try(var s=new CreatureStore(path,world)){assertEquals("two",s.get(owner,id).orElseThrow().definition().name());}try(var s=new CreatureStore(path,UUID.randomUUID())){assertTrue(s.get(owner,id).isEmpty());}}
 @Test void proximityNativeActionsAreStrictAndLegacyStillWorks(){
  var d=CreatureDefinition.parse("""
  {"name":"礼物伙伴","proximity":{"radius":3,"actions":[{"type":"effect","id":"minecraft:speed","level":2,"duration_ticks":100},{"type":"give_item","id":"minecraft:apple","count":2},{"type":"sound","id":"minecraft:entity.experience_orb.pickup"},{"type":"message","text":"欢迎"},{"type":"explosion","power":2,"fuse_ticks":30,"break_blocks":false}]}}
  """);assertEquals(5,d.proximityActions().size());assertEquals(2,d.proximityActions().getFirst().level());assertFalse(d.proximityActions().getLast().breakBlocks());assertEquals(d,CreatureDefinition.parse(d.source()));
  for(String action:new String[]{"{\"type\":\"command\",\"command\":\"op test\"}","{\"type\":\"explosion\",\"power\":100}","{\"type\":\"effect\",\"id\":\"minecraft:speed\",\"level\":1.5}","{\"type\":\"sound\",\"id\":\"minecraft:x\",\"count\":1}","{\"type\":\"give_item\",\"id\":\"minecraft:apple\",\"count\":65}"})assertThrows(IllegalArgumentException.class,()->CreatureDefinition.parse("{\"name\":\"x\",\"proximity\":{\"actions\":["+action+"]}}"));
  assertThrows(IllegalArgumentException.class,()->CreatureDefinition.parse("{\"name\":\"x\",\"proximity\":{\"actions\":[{\"type\":\"explosion\"},{\"type\":\"message\",\"text\":\"x\"}]}}"));
 }
}
