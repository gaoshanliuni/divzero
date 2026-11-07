package dev.mineagent.runtime.core.ui;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class UiViewSettingsTest {
    @Test void signedOpacityDefaultsAreOptionalAndNeverCoerced(){
        assertNull(UiViewSettings.parse(VALID,Set.of("ui/index.html")).get("ui/index.html").opacity());
        assertEquals(.35,UiViewSettings.parse(VALID.replace("\"width\":560","\"opacity\":0.35,\"width\":560"),Set.of("ui/index.html")).get("ui/index.html").opacity());
        for(String alpha:List.of("null","\"0.35\"","1.01","-1"))assertThrows(IllegalArgumentException.class,()->UiViewSettings.parse(VALID.replace("\"width\":560","\"opacity\":"+alpha+",\"width\":560"),Set.of("ui/index.html")));
    }
    private static final String VALID="{\"schema\":1,\"entries\":{\"ui/index.html\":{\"anchor\":\"TOP_RIGHT\",\"width\":560,\"height\":420,\"offsetX\":-24,\"offsetY\":12,\"appearance\":\"PACKAGE\"}}}";
    @Test void exactHtmlEntryGetsScopedDefaultsWithoutChangingThePackageManifest(){
        var data=UiViewSettings.parse(VALID,Set.of("ui/index.html","ui/hud.html"));var value=data.get("ui/index.html");assertEquals("TOP_RIGHT",value.anchor());assertEquals("PACKAGE",value.appearance());assertEquals(-24,value.offsetX());assertNull(data.get("ui/hud.html"));
    }
    @Test void ambiguousUnsupportedAndOutOfRangeDeclarationsFailBeforeRendering(){
        assertThrows(IllegalArgumentException.class,()->UiViewSettings.parse(VALID.replace("\"schema\":1","\"schema\":4294967297"),Set.of("ui/index.html")));
        for(String source:List.of(VALID.replace("\"schema\":1","\"schema\":1,\"schema\":1"),VALID.replace("560","0"),VALID.replace("560","1e99"),VALID.replace("TOP_RIGHT","bogus"),VALID.replace("\"width\":560","\"cssOpacity\":0.5,\"width\":560"),VALID.replace("ui/index.html","../index.html")))assertThrows(IllegalArgumentException.class,()->UiViewSettings.parse(source,Set.of("ui/index.html")));
        assertThrows(IllegalArgumentException.class,()->UiViewSettings.parse(VALID,Set.of("ui/other.html")));
        assertThrows(IllegalArgumentException.class,()->UiViewSettings.parse(VALID+"{}",Set.of("ui/index.html")));
    }
    @Test void stableLocalLayoutKeySeparatesPackageEntryTargetAndCandidateButNotTransientViewOrPackageRevision(){
        UUID p=UUID.randomUUID();String key=UiViewSettings.layoutKey(p,"ui/index.html","instance-a",false);
        assertEquals(key,UiViewSettings.layoutKey(p,"ui/index.html","instance-a",false));
        assertNotEquals(key,UiViewSettings.layoutKey(p,"ui/index.html","instance-b",false));assertNotEquals(key,UiViewSettings.layoutKey(p,"ui/hud.html","instance-a",false));assertNotEquals(key,UiViewSettings.layoutKey(UUID.randomUUID(),"ui/index.html","instance-a",false));assertNotEquals(key,UiViewSettings.layoutKey(p,"ui/index.html","instance-a",true));assertTrue(key.startsWith("package-v2:"));assertTrue(new String(java.util.Base64.getUrlDecoder().decode(key.substring("package-v2:".length())),java.nio.charset.StandardCharsets.UTF_8).contains("instance-a"));
    }
}
