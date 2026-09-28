package dev.mineagent.runtime.core.ui.dynamic;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import dev.mineagent.runtime.core.conversation.ConversationTools;
import java.util.*;

/** Explicit application callbacks reuse the existing scoped game-tool authority layer. */
public final class InterfaceHandlers {
    private static final Set<String> EXCLUDED=Set.of("python_execute","python_install_packages","read_host_output","set_native_ui","patch_native_ui_data","control_native_ui","request_player_control","control_player_session");
    public record Handler(String tool,JsonNode arguments,Map<String,JsonNode> bindings,String resultKey){
        public Handler{arguments=arguments.deepCopy();var copy=new LinkedHashMap<String,JsonNode>();bindings.forEach((k,v)->copy.put(k,v.deepCopy()));bindings=Collections.unmodifiableMap(copy);}
        @Override public JsonNode arguments(){return arguments.deepCopy();}
        @Override public Map<String,JsonNode> bindings(){var copy=new LinkedHashMap<String,JsonNode>();bindings.forEach((k,v)->copy.put(k,v.deepCopy()));return copy;}
        public JsonNode resolve(Map<String,JsonNode> data){var value=(ObjectNode)arguments.deepCopy();bindings.forEach((key,expression)->value.set(key,InterfaceExpression.evaluate(expression,data)));return value;}
    }
    public static Map<String,Handler> parse(JsonNode node){
        if(node.isMissingNode())return Map.of();if(!node.isObject()||node.size()>64)throw bad("OBJECT");var result=new LinkedHashMap<String,Handler>();
        for(var entry:node.properties()){
            id(entry.getKey());var n=entry.getValue();if(!n.isObject())throw bad("OBJECT");for(var field:n.properties())if(!Set.of("tool","arguments","bindings","resultKey").contains(field.getKey()))throw bad("FIELD");
            String tool=n.path("tool").asText();if(!ConversationTools.NAMES.contains(tool)||EXCLUDED.contains(tool))throw bad("TOOL");if(!n.path("arguments").isObject())throw bad("ARGUMENTS");String resultKey=n.path("resultKey").asText("actionResult");id(resultKey);
            var bindings=new LinkedHashMap<String,JsonNode>();if(n.has("bindings")){if(!n.get("bindings").isObject()||n.get("bindings").size()>64)throw bad("BINDINGS");for(var binding:n.get("bindings").properties()){id(binding.getKey());InterfaceExpression.validate(binding.getValue());bindings.put(binding.getKey(),binding.getValue());}}
            result.put(entry.getKey(),new Handler(tool,n.get("arguments"),bindings,resultKey));
        }return Map.copyOf(result);
    }
    private static void id(String value){if(value==null||!value.matches("[A-Za-z][A-Za-z0-9_-]{0,95}"))throw bad("KEY");}
    private static IllegalArgumentException bad(String value){return new IllegalArgumentException("INTERFACE_HANDLER_"+value);}
    private InterfaceHandlers(){}
}
