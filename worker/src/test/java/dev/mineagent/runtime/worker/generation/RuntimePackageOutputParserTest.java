package dev.mineagent.runtime.worker.generation;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mineagent.runtime.api.packages.RuntimeDefinitionKind;
import dev.mineagent.runtime.core.packages.RuntimePackageCanonicalizer;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class RuntimePackageOutputParserTest {
    private final RuntimePackageOutputParser parser = new RuntimePackageOutputParser();

    @Test
    void parsesDifferentUnseenObjectsThroughTheSameManifestPipeline() throws Exception {
        var windChime = parser.parse(output("wind-chime", "风铃", RuntimeDefinitionKind.BLOCK,
                "on('interact', function () { host.playSound('minecraft:block.amethyst_block.chime'); });"));
        var streetLamp = parser.parse(output("street-lamp", "街灯", RuntimeDefinitionKind.BLOCK,
                "on('tick', function () { host.setLight(15); });"));

        assertEquals("wind-chime", windChime.name());
        assertEquals("street-lamp", streetLamp.name());
        assertEquals(2, windChime.files().size());
        assertEquals(RuntimeDefinitionKind.BLOCK, streetLamp.definitions().getFirst().kind());
    }

    @Test
    void rejectsPathTraversalHashMismatchAndUnsafeJavascript() throws Exception {
        String valid = output("wind-chime", "风铃", RuntimeDefinitionKind.BLOCK, "'safe';");
        String traversal = valid.replace("server/main.js", "../escape.js");
        String mismatch = valid.replaceFirst("[0-9a-f]{64}", "0".repeat(64));
        String unsafe = output("unsafe", "不安全", RuntimeDefinitionKind.BLOCK, "while (true) {};");

        assertThrows(PackageOutputException.class, () -> parser.parse(traversal));
        assertThrows(PackageOutputException.class, () -> parser.parse(mismatch));
        assertEquals("PREFLIGHT_REJECTED",
                assertThrows(PackageOutputException.class, () -> parser.parse(unsafe)).code());
    }

    @Test void nativeTargetsAreAnAllowedSubsetNotBothRequired()throws Exception {
        var json=new ObjectMapper();var target=Map.of("minecraft","26.1.2","loader","neoforge","loaderVersion","26.1.2.106","namespace","official","javaFeature",25,"requiredMods",Map.of());
        for(String side:List.of("SERVER","CLIENT")){
            var root=json.readTree(output("item-test","item",RuntimeDefinitionKind.ITEM,"'safe';"));
            ((com.fasterxml.jackson.databind.node.ObjectNode)root.path("manifest")).set("nativeCompatibility",json.valueToTree(Map.of("schema",1,"targets",Map.of(side,target))));
            assertEquals(java.util.Set.of(side),parser.parse(root.toString()).nativeCompatibility().targets().keySet());
        }
        for(var targets:List.of(Map.of(),Map.of("OTHER",target))){
            var root=json.readTree(output("item-test","item",RuntimeDefinitionKind.ITEM,"'safe';"));
            ((com.fasterxml.jackson.databind.node.ObjectNode)root.path("manifest")).set("nativeCompatibility",json.valueToTree(Map.of("schema",1,"targets",targets)));
            assertThrows(PackageOutputException.class,()->parser.parse(root.toString()));
        }
    }

    public static String output(String name, String displayName, RuntimeDefinitionKind kind, String script)
            throws Exception {
        UUID definitionId = UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
        String scriptHash = RuntimePackageCanonicalizer.sha256(script);
        String modelJson = "{\"elements\":[]}";
        String modelHash = RuntimePackageCanonicalizer.sha256(modelJson);
        var root = Map.of(
                "manifest", Map.of(
                        "name", name,
                        "version", "1.0.0",
                        "type", "CONTENT",
                        "activationMode", "HOT_RUNTIME",
                        "permissions", List.of("world.read"),
                        "entrypoints", Map.of("server", Map.of(
                                "path", "server/main.js", "side", "SERVER", "sha256", scriptHash)),
                        "definitions", List.of(Map.of(
                                "definitionId", definitionId.toString(),
                                "name", displayName,
                                "kind", kind.name(),
                                "entrypointId", "server",
                                "resourcePaths", List.of("assets/model.json"),
                                "settingsSchema", Map.of(),
                                "revision", 1,
                                "stateSchemaVersion", 1)),
                        "dependencies", Map.of()),
                "files", List.of(
                        Map.of("path", "server/main.js", "side", "SERVER",
                                "mediaType", "application/javascript", "encoding", "utf8",
                                "content", script, "sha256", scriptHash),
                        Map.of("path", "assets/model.json", "side", "CLIENT",
                                "mediaType", "application/json", "encoding", "utf8",
                                "content", modelJson, "sha256", modelHash)));
        return new ObjectMapper().writeValueAsString(root);
    }
}
