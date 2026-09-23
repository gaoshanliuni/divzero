package dev.mineagent.runtime.client.control;
import org.junit.jupiter.api.Test;import org.junit.jupiter.api.io.TempDir;import java.nio.file.*;import static org.junit.jupiter.api.Assertions.*;
class NativeChatPreferencesTest {
 @TempDir Path root;
 @Test void defaultPersistsAndStaleUiCannotOverwrite()throws Exception{Path file=root.resolve("settings.properties");var prefs=new NativeChatPreferences(file);assertTrue(prefs.state().showThinking());var off=prefs.save(0,false);assertEquals(1,off.revision());assertFalse(new NativeChatPreferences(file).state().showThinking());assertThrows(IllegalStateException.class,()->prefs.save(0,true));assertTrue(prefs.save(1,true).showThinking());}
}
