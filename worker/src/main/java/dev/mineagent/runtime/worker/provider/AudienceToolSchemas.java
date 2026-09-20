package dev.mineagent.runtime.worker.provider;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
public final class AudienceToolSchemas {
    private AudienceToolSchemas(){}
    public static final String CREATE=create();
    public static final String RESOLVE="""
        {"type":"object","properties":{"audience_id":{"type":"string","format":"uuid"},"expected_revision":{"type":"integer","minimum":1,"maximum":9007199254740991}},"required":["audience_id","expected_revision"],"additionalProperties":false}
        """;
    public static final String INSPECT="""
        {"type":"object","properties":{"kind":{"enum":["DEFINITION","SNAPSHOT","OPERATION"]},"id":{"type":"string","format":"uuid"}},"required":["kind","id"],"additionalProperties":false}
        """;
    private static String create(){try{
        var json=new ObjectMapper();var query=(ObjectNode)json.readTree(ObjectDirectoryToolSchemas.QUERY);var properties=(ObjectNode)query.get("properties");properties.set("kind",json.createObjectNode().put("const","PLAYER"));properties.set("cursor",json.createObjectNode().put("type","string").put("maxLength",0));
        var schema=json.createObjectNode().put("type","object").put("additionalProperties",false);var p=schema.putObject("properties");p.putObject("mode").putArray("enum").add("SNAPSHOT").add("LIVE_AT_TRIGGER");p.set("query",query);p.putObject("ttl_seconds").put("type","integer").put("minimum",1).put("maximum",604800);schema.putArray("required").add("mode").add("query");return json.writeValueAsString(schema);
    }catch(Exception e){throw new IllegalStateException("AUDIENCE_TOOL_SCHEMA",e);}}
}
