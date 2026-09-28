package dev.mineagent.runtime.core.ui.dynamic;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class InterfaceAttachmentTest {
    @Test void menuAttachmentKeepsArbitraryTreeAndActualTimeSource(){
        var value=InterfaceDefinition.parse("""
            {"id":"timer","title":"Furnace","surface":"SCREEN_OVERLAY","attachment":{"menu":"furnace","anchor":"top","y":-4},"sources":{"remaining":{"kind":"menu","field":"furnace_remaining_seconds"}},"root":{"id":"root","type":"row","children":[{"id":"value","type":"label","bind":"remaining"}]}}
            """);
        assertEquals("furnace",value.attachment().menu());assertEquals(-4,value.attachment().y());assertEquals("menu",value.sources().get("remaining").kind());
        assertThrows(IllegalArgumentException.class,()->InterfaceDefinition.parse("{\"id\":\"x\",\"title\":\"\",\"surface\":\"SCREEN_OVERLAY\",\"root\":{\"id\":\"r\",\"type\":\"panel\"}}"));
    }
    @Test void entityBarsArePassiveAndHaveInitialTypedData(){
        String source="{\"id\":\"health\",\"title\":\"\",\"surface\":\"ENTITY_HUD\",\"attachment\":{\"range\":96},\"root\":{\"id\":\"root\",\"type\":\"progress\"}}";
        var value=InterfaceDefinition.parse(source);assertEquals(1,value.data().get("entity").get("maxHealth").asInt());assertEquals(96,value.attachment().range());
        assertThrows(IllegalArgumentException.class,()->InterfaceDefinition.parse(source.replace("\"type\":\"progress\"","\"type\":\"button\",\"events\":{\"click\":[]}")));
    }
    @Test void desktopWindowAcceptsNewControlsInsteadOfFixedTemplate(){
        var value=InterfaceDefinition.parse("{\"id\":\"desktop\",\"title\":\"Desktop\",\"surface\":\"SCREEN\",\"root\":{\"id\":\"root\",\"type\":\"panel\",\"children\":[{\"id\":\"notes\",\"type\":\"window\",\"text\":\"Notes\",\"children\":[{\"id\":\"draft\",\"type\":\"input\",\"bind\":\"text\"}]}]}}");
        assertEquals("input:text",value.inputBindings().get("draft"));assertTrue(value.node("notes").isPresent());
    }
}
