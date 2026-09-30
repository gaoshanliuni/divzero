package dev.mineagent.runtime.core.conversation;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;

/** Representation-only repairs plus field-level feedback, always before execution. */
public final class ToolValidation {
    private static final ObjectMapper JSON=new ObjectMapper();
    public record Issue(String field,String problem,String expected){}
    public record Checked(ObjectNode arguments,List<String> normalized,List<Issue> issues){
        public Checked{normalized=List.copyOf(normalized);issues=List.copyOf(issues);}
        public Map<String,Object> rejection(){return Map.of("status","REJECTED","error","AGENT_TOOL_ARGUMENTS","category","VALIDATION","executionState","NOT_STARTED","worldModified",false,"issues",issues,"suggestedAction","Correct the listed fields and submit a new call; no action was executed.");}
    }
    private ToolValidation(){}
    public static Checked check(String tool,ObjectNode input){
        var arguments=input.deepCopy();var normalized=new ArrayList<String>();var issues=new ArrayList<Issue>();
        try{var schema=JSON.readTree(CapabilityCatalog.definition(tool).parameters());checkNode(schema,arguments,"$",normalized,issues,null,null);}
        catch(com.fasterxml.jackson.core.JsonProcessingException invalid){throw new IllegalStateException("TOOL_SCHEMA_INVALID",invalid);}
        return new Checked(arguments,normalized,issues);
    }
    private static void issue(List<Issue> issues,String path,String problem,String expected){if(issues.size()<16)issues.add(new Issue(path,problem,expected));}
    private static void checkNode(JsonNode schema,JsonNode value,String path,List<String> normalized,List<Issue> issues,JsonNode parent,String key){
        String type=schema.path("type").asText();JsonNode corrected=value;
        if(parent!=null&&value.isTextual()){
            String text=value.asText();
            try{
                if(type.equals("integer")&&text.matches("-?(?:0|[1-9][0-9]*)"))corrected=JSON.getNodeFactory().numberNode(new java.math.BigInteger(text));
                else if(type.equals("number")&&text.matches("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?"))corrected=JSON.getNodeFactory().numberNode(new java.math.BigDecimal(text));
                else if(type.equals("boolean")&&(text.equalsIgnoreCase("true")||text.equalsIgnoreCase("false")))corrected=BooleanNode.valueOf(Boolean.parseBoolean(text));
                else if(schema.has("enum")){for(var choice:schema.get("enum"))if(choice.isTextual()&&choice.asText().equalsIgnoreCase(text)){corrected=choice;break;}}
            }catch(NumberFormatException ignored){/* The ordinary validator returns the exact field below. */}
        }
        if(parent!=null&&type.equals("integer")&&value.isFloatingPointNumber()&&value.decimalValue().stripTrailingZeros().scale()<=0)corrected=JSON.getNodeFactory().numberNode(value.bigIntegerValue());
        if(!corrected.equals(value)){if(parent instanceof ObjectNode object)object.set(key,corrected);else if(parent instanceof ArrayNode array)array.set(Integer.parseInt(key),corrected);normalized.add(path);value=corrected;}
        boolean valid=switch(type){case "object"->value.isObject();case "array"->value.isArray();case "string"->value.isTextual();case "integer"->value.isIntegralNumber();case "number"->value.isNumber();case "boolean"->value.isBoolean();default->true;};
        if(!valid){issue(issues,path,"Invalid value type",type);return;}
        if(schema.has("const")&&!schema.get("const").equals(value)&&!(schema.get("const").isNumber()&&value.isNumber()&&schema.get("const").decimalValue().compareTo(value.decimalValue())==0))issue(issues,path,"Value differs from the required constant",schema.get("const").toString());
        if(schema.has("enum")){boolean found=false;for(var choice:schema.get("enum"))if(choice.equals(value)){found=true;break;}if(!found)issue(issues,path,"Value is not a supported choice",schema.get("enum").toString());}
        if(value.isObject()){
            for(var field:schema.path("required"))if(!value.has(field.asText()))issue(issues,path+"."+field.asText(),"Required field is missing","present");
            var properties=schema.path("properties");
            for(var entry:value.properties()){
                if(properties.has(entry.getKey()))checkNode(properties.get(entry.getKey()),entry.getValue(),path.equals("$")?entry.getKey():path+"."+entry.getKey(),normalized,issues,value,entry.getKey());
                else if(schema.has("additionalProperties")&&!schema.path("additionalProperties").asBoolean(true))issue(issues,path.equals("$")?entry.getKey():path+"."+entry.getKey(),"Unknown field",properties.properties().stream().map(Map.Entry::getKey).toList().toString());
            }
        }else if(value.isArray()){
            if(schema.has("minItems")&&value.size()<schema.get("minItems").asInt()||schema.has("maxItems")&&value.size()>schema.get("maxItems").asInt())issue(issues,path,"Array length does not match the contract","length "+schema.path("minItems").asText("0")+".."+schema.path("maxItems").asText("unbounded"));
            if(schema.has("items"))for(int i=0;i<value.size();i++)checkNode(schema.get("items"),value.get(i),path+"["+i+"]",normalized,issues,value,Integer.toString(i));
        }else if(value.isNumber()){
            if(schema.has("minimum")&&value.decimalValue().compareTo(schema.get("minimum").decimalValue())<0||schema.has("maximum")&&value.decimalValue().compareTo(schema.get("maximum").decimalValue())>0)issue(issues,path,"Number is outside the allowed range",schema.path("minimum").asText("-infinity")+".."+schema.path("maximum").asText("infinity"));
        }else if(value.isTextual()){
            String text=value.asText();
            if(schema.has("minLength")&&text.length()<schema.get("minLength").asInt()||schema.has("maxLength")&&text.length()>schema.get("maxLength").asInt())issue(issues,path,"Text length is outside the allowed range",schema.path("minLength").asText("0")+".."+schema.path("maxLength").asText("unbounded"));
            if(schema.has("pattern")&&!text.matches(schema.get("pattern").asText()))issue(issues,path,"Text does not match the required format",schema.get("pattern").asText());
            if(schema.path("format").asText().equals("uuid"))try{UUID.fromString(text);}catch(IllegalArgumentException invalid){issue(issues,path,"Invalid object identifier","UUID");}
        }
    }
}
