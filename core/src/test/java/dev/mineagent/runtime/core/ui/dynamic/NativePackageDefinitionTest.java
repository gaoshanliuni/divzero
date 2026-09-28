package dev.mineagent.runtime.core.ui.dynamic;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class NativePackageDefinitionTest {
    private static final String SOURCE="""
        {"format":"divzero-native-ui/1","view":{"id":"shop","title":"Shop","surface":"SCREEN","data":{"query":""},
        "root":{"id":"root","type":"column","children":[{"id":"search","type":"input","bind":"query"}]}},
        "reads":{"stock":{"action":"worldui.read","arguments":{"action":"stock"},"result":"stock","intervalTicks":20}},
        "actions":{"buy":{"action":"worldui.action","arguments":{"action":"buy","query":{"data":"query"}},"result":"purchase"}}}
        """;
    @Test void editableInputsAndScopedRequestsDoNotBecomeShellAuthority()throws Exception {
        var definition=NativePackageDefinition.parse(SOURCE);
        assertEquals("input:query",definition.view().inputBindings().get("search"));
        var value=new ObjectMapper().getNodeFactory().textNode("oak");
        assertEquals(Map.of("action","buy","query","oak"),definition.actions().get("buy").arguments(Map.of("query",value)));
        assertThrows(IllegalArgumentException.class,()->NativePackageDefinition.parse(SOURCE.replace("worldui.action","settings.write")));
        assertThrows(IllegalArgumentException.class,()->NativePackageDefinition.parse(SOURCE.replace("worldui.read","run_game_command")));
    }
    @Test void pollingIsReadOnlyAndCannotWrapItsInterval(){
        assertThrows(IllegalArgumentException.class,()->NativePackageDefinition.parse(SOURCE.replace("\"intervalTicks\":20","\"intervalTicks\":4294967296")));
        assertThrows(IllegalArgumentException.class,()->NativePackageDefinition.parse(SOURCE.replace("\"result\":\"purchase\"","\"result\":\"purchase\",\"intervalTicks\":20")));
        assertThrows(IllegalArgumentException.class,()->NativePackageDefinition.parse(SOURCE.replace("\"intervalTicks\":20","\"intervalTicks\":1")));
    }
    @Test void executableOrAmbiguousDocumentsFailClosed(){
        assertThrows(IllegalArgumentException.class,()->NativePackageDefinition.parse(SOURCE.replace("\"format\":","\"script\":\"Java.loadClass('System')\",\"format\":")));
        assertThrows(IllegalArgumentException.class,()->NativePackageDefinition.parse(SOURCE.replace("\"title\":\"Shop\"","\"title\":\"Shop\",\"title\":\"Other\"")));
    }
}
