package dev.mineagent.runtime.core.ui.dynamic;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import java.util.*;

/** Signed package GUI contract. Rendering is native; data and actions use the existing scoped UI protocol. */
public record NativePackageDefinition(InterfaceDefinition view,Map<String,Request> reads,Map<String,Request> actions) {
    private static final ObjectMapper JSON=new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    public static final Set<String> READS=Set.of("scoreview.read","container.read","worldui.read","delivery.read","feedback.read","feedback.stateRead","state.get");
    public static final Set<String> ACTIONS=Set.of("scoreview.patch","container.act","worldui.action","delivery.dataRead","feedback.submit","state.put","state.remove");
    public record Request(String action,Map<String,JsonNode> arguments,String result,int intervalTicks) {
        public Request{arguments=Map.copyOf(arguments);}
        public Map<String,String> arguments(Map<String,JsonNode> data){var values=new LinkedHashMap<String,String>();arguments.forEach((key,expression)->{var value=InterfaceExpression.evaluate(expression,data);values.put(key,key.equals("valueJson")?value.toString():value.isTextual()?value.asText():value.toString());});return Map.copyOf(values);}
    }
    public NativePackageDefinition{reads=Map.copyOf(reads);actions=Map.copyOf(actions);}
    public static NativePackageDefinition parse(String source){
        try{
            if(source==null||source.length()>262144)throw new IllegalArgumentException("NATIVE_PACKAGE_SIZE");
            var document=JSON.readTree(source);fields(document,Set.of("format","view","reads","actions"));
            if(!document.path("format").asText().equals("divzero-native-ui/1"))throw new IllegalArgumentException("NATIVE_PACKAGE_FORMAT");
            var view=InterfaceDefinition.parse(document.path("view").toString());
            if(!view.handlers().isEmpty()||!view.sources().isEmpty())throw new IllegalArgumentException("NATIVE_PACKAGE_USE_SCOPED_READS_AND_ACTIONS");
            var reads=requests(document.path("reads"),true);var actions=requests(document.path("actions"),false);
            var keys=new HashSet<String>();for(var request:reads.values())if(!keys.add(request.result()))throw new IllegalArgumentException("NATIVE_PACKAGE_DUPLICATE_RESULT");
            var inputKeys=new HashSet<String>();view.inputBindings().values().forEach(binding->inputKeys.add(binding.substring(binding.indexOf(':')+1)));
            for(var request:actions.values())keys.add(request.result());
            if(keys.stream().anyMatch(inputKeys::contains))throw new IllegalArgumentException("NATIVE_PACKAGE_RESULT_IS_INPUT");
            InterfaceDefinition.walk(view.root(),node->{for(var event:node.path("events"))for(var action:event)if(action.path("op").asText().equals("emit")&&!actions.containsKey(action.path("action").asText()))throw new IllegalArgumentException("NATIVE_PACKAGE_ACTION_UNDECLARED: "+action.path("action").asText());});
            return new NativePackageDefinition(view,reads,actions);
        }catch(IllegalArgumentException failure){throw failure;}catch(Exception failure){throw new IllegalArgumentException("NATIVE_PACKAGE_JSON",failure);}
    }
    private static Map<String,Request> requests(JsonNode node,boolean read){
        if(node.isMissingNode())return Map.of();if(!node.isObject()||node.size()>64)throw new IllegalArgumentException("NATIVE_PACKAGE_REQUESTS");
        var out=new LinkedHashMap<String,Request>();
        for(var entry:node.properties()){
            id(entry.getKey());var value=entry.getValue();fields(value,Set.of("action","arguments","result","intervalTicks"));
            String action=value.path("action").asText();if(!(read?READS.contains(action):ACTIONS.contains(action)||READS.contains(action)))throw new IllegalArgumentException("NATIVE_PACKAGE_ACTION");
            var arguments=value.path("arguments");if(!arguments.isObject()||arguments.size()>32)throw new IllegalArgumentException("NATIVE_PACKAGE_ARGUMENTS");
            var expressions=new LinkedHashMap<String,JsonNode>();for(var argument:arguments.properties()){if(argument.getKey().isBlank()||argument.getKey().length()>128)throw new IllegalArgumentException("NATIVE_PACKAGE_ARGUMENT_NAME");InterfaceExpression.validate(argument.getValue());expressions.put(argument.getKey(),argument.getValue().deepCopy());}
            String result=value.path("result").asText(entry.getKey());id(result);int ticks=value.path("intervalTicks").asInt(0);
            if(value.has("intervalTicks")&&(!value.get("intervalTicks").isIntegralNumber()||!value.get("intervalTicks").canConvertToInt()||ticks<0||ticks>72000||ticks>0&&ticks<5)||!read&&ticks!=0)throw new IllegalArgumentException("NATIVE_PACKAGE_POLL_INTERVAL");
            out.put(entry.getKey(),new Request(action,expressions,result,ticks));
        }
        return out;
    }
    private static void fields(JsonNode node,Set<String> allowed){if(!node.isObject()||!allowed.containsAll(node.properties().stream().map(Map.Entry::getKey).toList()))throw new IllegalArgumentException("NATIVE_PACKAGE_FIELDS");}
    private static void id(String value){if(value==null||!value.matches("[A-Za-z][A-Za-z0-9_-]{0,95}"))throw new IllegalArgumentException("NATIVE_PACKAGE_ID");}
}
