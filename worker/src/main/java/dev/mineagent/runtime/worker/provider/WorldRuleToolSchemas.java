package dev.mineagent.runtime.worker.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;

public final class WorldRuleToolSchemas {
    private WorldRuleToolSchemas() {}
    public static final String WAIT="""
        {"type":"object","properties":{"package_id":{"type":"string","format":"uuid"},"canonical_sha256":{"type":"string","pattern":"^[a-f0-9]{64}$"},"definition_id":{"type":"string","format":"uuid"},"minimum_blocks":{"type":"integer","minimum":0,"maximum":128},"minimum_objects":{"type":"integer","minimum":0,"maximum":32}},"required":["package_id","canonical_sha256"],"oneOf":[{"required":["definition_id"],"not":{"anyOf":[{"required":["minimum_blocks"]},{"required":["minimum_objects"]}]}},{"anyOf":[{"required":["minimum_blocks"]},{"required":["minimum_objects"]}],"not":{"required":["definition_id"]}}],"additionalProperties":false}
        """;
    public static String finishSchema(String source){try{
        var json=new ObjectMapper();var root=(ObjectNode)json.readTree(source);var rule=(ObjectNode)json.readTree(SharedStateToolSchemas.READ);
        var properties=(ObjectNode)rule.get("properties");properties.remove("keys");properties.putObject("kind").put("const","world_rule");properties.putObject("definition_id").put("type","string").put("format","uuid");
        var required=rule.putArray("required");for(String name:new String[]{"kind","package_id","instance_id","package_revision","canonical_sha256","namespace","definition_id"})required.add(name);
        ((ArrayNode)root.path("properties").path("checks").path("items").path("oneOf")).add(rule);
        return json.writeValueAsString(root);
    }catch(Exception invalid){throw new IllegalStateException("WORLD_RULE_FINISH_SCHEMA",invalid);}}
}
