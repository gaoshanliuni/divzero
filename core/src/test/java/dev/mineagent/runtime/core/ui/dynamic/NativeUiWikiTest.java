package dev.mineagent.runtime.core.ui.dynamic;
import org.junit.jupiter.api.Test;
import dev.mineagent.runtime.core.conversation.*;
import static org.junit.jupiter.api.Assertions.*;
class NativeUiWikiTest {
    @Test void docsAreDiscoverableAndOnlyDisclosedForRelevantTasks(){
        var session=new CapabilitySession();assertFalse(session.definitions().stream().anyMatch(t->t.name().equals("read_ui_wiki")));
        session.preload("查阅 KubeJS 和 LDLib2 文档",java.util.List.of());assertTrue(session.definitions().stream().anyMatch(t->t.name().equals("read_ui_wiki")));
        assertTrue(((Integer)NativeUiWiki.read("","",0,4096).get("total"))>50);
        assertTrue(NativeUiWiki.read("divzero/hud","",0,12000).get("content").toString().contains("height: 54"));
        assertEquals(InterfaceDefinition.CONTRACT.substring(0,200),NativeUiWiki.read("divzero/contract","",0,200).get("content"));
        assertThrows(IllegalArgumentException.class,()->NativeUiWiki.read("../../secret","",0,4096));
    }
    @Test void candidateHudRequiresPositiveExplicitHeightButLegacyRemainsReadable(){
        String source="{\"id\":\"hud\",\"title\":\"HUD\",\"surface\":\"HUD\",\"root\":{\"id\":\"root\",\"type\":\"column\"}}";
        var legacy=InterfaceDefinition.parse(source);assertThrows(IllegalArgumentException.class,legacy::requireHudHeight);
        for(String style:java.util.List.of("height: 0;","min-height: 54;","/*height:54;*/width:160;"))assertThrows(IllegalArgumentException.class,()->InterfaceDefinition.parse(source.replace("\"type\":\"column\"","\"type\":\"column\",\"style\":\""+style+"\"")).requireHudHeight());
        assertDoesNotThrow(()->InterfaceDefinition.parse(source.replace("\"type\":\"column\"","\"type\":\"column\",\"style\":\"width: 160; height: 54; flex-grow: 0;\"")).requireHudHeight());
    }
}
