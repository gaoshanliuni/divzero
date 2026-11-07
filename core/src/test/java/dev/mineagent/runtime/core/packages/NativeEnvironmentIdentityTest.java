package dev.mineagent.runtime.core.packages;
import java.util.*;import org.junit.jupiter.api.Test;import static org.junit.jupiter.api.Assertions.*;
class NativeEnvironmentIdentityTest {
    @Test void environmentIdentitySurvivesTransportAndStillComparesActualVersions()throws Exception{
        var id=dev.mineagent.runtime.api.packages.ResourceIds.create();var environment=new NativeCompatibilityPolicy.Environment("26.1.2","neoforge","26.1.2.106","official",25,"fixture",Map.of("example","1"),id);var json=new com.fasterxml.jackson.databind.ObjectMapper();var restored=json.readValue(json.writeValueAsString(environment),NativeCompatibilityPolicy.Environment.class);assertEquals(environment,restored);assertEquals(id,restored.fingerprint());
        var contract=new dev.mineagent.runtime.api.packages.NativeCompatibility(1,Map.of("SERVER",new dev.mineagent.runtime.api.packages.NativeCompatibility.Target("26.1.2","neoforge","26.1.2.106","official",25,Map.of("example","2"))));assertFalse(NativeCompatibilityPolicy.check(contract,restored,"SERVER",true).allowed());
        var legacy=(com.fasterxml.jackson.databind.node.ObjectNode)json.valueToTree(environment);legacy.remove("identity");assertEquals(json.treeToValue(legacy,NativeCompatibilityPolicy.Environment.class).fingerprint(),json.treeToValue(legacy,NativeCompatibilityPolicy.Environment.class).fingerprint());
    }
}
