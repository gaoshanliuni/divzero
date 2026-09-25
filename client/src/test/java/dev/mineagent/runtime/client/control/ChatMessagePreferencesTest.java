package dev.mineagent.runtime.client.control;
import org.junit.jupiter.api.Test;import org.junit.jupiter.api.io.TempDir;import java.nio.file.*;import static org.junit.jupiter.api.Assertions.*;
class ChatMessagePreferencesTest {
 @TempDir Path directory;
 @Test void defaultsPersistAndPatchWithoutOverwritingOtherFields()throws Exception{
  var file=directory.resolve("messages.properties");var p=new ChatMessagePreferences(file);assertEquals(1024,p.state().limit());assertEquals("time",p.state().mark());
  var first=p.save(0L,16384,null);var second=p.save(first.revision(),null,"消息时间：{yyyy-MM-dd HH:mm:ss}");assertEquals(16384,second.limit());assertEquals(second,new ChatMessagePreferences(file).state());
  assertThrows(IllegalStateException.class,()->p.save(0L,1,"off"));assertEquals(second,p.state());assertThrows(IllegalArgumentException.class,()->p.save(null,16385,null));assertEquals(second,new ChatMessagePreferences(file).state());
  assertEquals("off",p.save(null,null,"off").mark());
 }
 @Test void thinkingTailDefaultPersistsAndDoesNotChangeRetentionOrMark()throws Exception{
  var file=directory.resolve("stream.properties");var p=new ChatMessagePreferences(file);assertEquals("tail",p.state().thinking());
  var full=p.save(0L,null,null,"full");assertEquals("full",full.thinking());assertEquals(1024,full.limit());assertEquals("time",full.mark());
  assertEquals(full,new ChatMessagePreferences(file).state());assertEquals("full",p.save(full.revision(),4096,null).thinking());
  assertThrows(IllegalArgumentException.class,()->p.save(null,null,null,"invalid"));
 }
}
