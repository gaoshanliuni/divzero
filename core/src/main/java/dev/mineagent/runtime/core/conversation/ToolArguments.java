package dev.mineagent.runtime.core.conversation;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Set;

/** Accept only unambiguous JSON transport wrappers; never evaluate or invent missing arguments. */
public final class ToolArguments {
    private static final ObjectMapper JSON=new ObjectMapper()
            .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private ToolArguments(){}
    public static ObjectNode parse(String tool,String input){
        try {
            String source=input.strip();
            if(source.startsWith("\uFEFF"))source=source.substring(1).strip();
            if(source.startsWith("```")){
                int lineBreak=source.indexOf('\n');String fence=lineBreak<0?source:source.substring(0,lineBreak).strip();
                if(!(fence.equalsIgnoreCase("```json")||fence.equals("```")))throw new IllegalArgumentException("AGENT_TOOL_JSON_INVALID");
                if(!source.endsWith("```"))throw new IllegalArgumentException("AGENT_TOOL_JSON_INVALID");
                source=source.substring(source.indexOf('\n')+1,source.length()-3).strip();
            }
            JsonNode value=JSON.readTree(source);
            if(value!=null&&value.isTextual())value=JSON.readTree(value.textValue());
            if(!(value instanceof ObjectNode object))throw new IllegalArgumentException("AGENT_TOOL_ARGUMENTS_OBJECT_REQUIRED");
            // These tools explicitly carry another JSON document as their source string.
            if(Set.of("plan_building","plan_world_geometry","validate_model_geometry").contains(tool)&&object.path("source").isObject())object.put("source",object.get("source").toString());
            return object;
        }catch(com.fasterxml.jackson.core.JsonProcessingException invalid){
            throw new IllegalArgumentException("AGENT_TOOL_JSON_INVALID",invalid);
        }
    }
}
