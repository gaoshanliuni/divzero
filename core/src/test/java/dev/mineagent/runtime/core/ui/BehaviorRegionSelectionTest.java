package dev.mineagent.runtime.core.ui;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class BehaviorRegionSelectionTest {
    @Test void sourceRadiusConcreteBoundsAndRevisionRoundTrip()throws Exception{
        var mapper=new ObjectMapper();var n=mapper.readTree("{\"actor\":\"ai\",\"source\":\"CURRENT\",\"radius\":8,\"dimension\":\"minecraft:overworld\",\"min\":[2,63,-10],\"max\":[18,65,6],\"revision\":0}");
        var first=BehaviorRegionSelection.parse(n);var saved=BehaviorRegionSelection.parse(mapper.valueToTree(first.values(1)));assertEquals(first.area(),saved.area());assertEquals(8,saved.radius());assertEquals("CURRENT",saved.source());assertEquals(1,saved.revision());
        ((com.fasterxml.jackson.databind.node.ObjectNode)n).put("radius",-1);assertThrows(IllegalArgumentException.class,()->BehaviorRegionSelection.parse(n));
        assertFalse(dev.mineagent.runtime.core.config.PanelConfigProjection.global(java.util.Map.of("behavior.region.private-ai","position")).containsKey("behavior.region.private-ai"));
    }
}
