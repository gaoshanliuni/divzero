package dev.mineagent.runtime.scripting.opencode;

import com.fasterxml.jackson.databind.*;
import java.util.*;

/** OpenCode repeated-call predicate plus DivZero outcome/context checks; never a total-round budget. */
public final class ExecutionProgress {
    private static final ObjectMapper JSON=new ObjectMapper().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS,true);
    private static final Set<String> VOLATILE=Set.of("observedAt","observedGameTick","gameTime","timestamp","elapsedMs","record_id");
    private String last="";private int unchanged;private final List<Map<String,Object>> parts=new ArrayList<>();
    public record Decision(boolean warn,boolean blocked,int repetitions){}
    public synchronized Decision observe(String name,String raw,Map<String,Object> result,String context,boolean readOnly){
        String status=Objects.toString(result.get("status"),"");
        boolean candidate=readOnly||result.containsKey("error")||Set.of("WAITING","NO_CHANGE","REJECTED","READ_FAILED","BLOCKED").contains(status);
        if(!candidate){reset();return new Decision(false,false,0);}
        try{
            JsonNode args;try{args=JSON.readTree(raw);}catch(Exception malformed){args=JSON.getNodeFactory().textNode(raw);}
            var input=Map.<String,Object>of("arguments",canonical(args));
            String signature=JSON.writeValueAsString(List.of(name,input,canonical(JSON.valueToTree(result)),context));
            if(!signature.equals(last)){reset();last=signature;}
            unchanged++;parts.add(Map.of("type","tool","tool",name,"state",Map.of("status","completed","input",input)));
            if(parts.size()>3)parts.removeFirst();
            boolean repeated=unchanged>=3&&OpenCodeRuntime.repeatedCalls(parts,name,input);
            return new Decision(repeated,repeated&&unchanged>=4,unchanged);
        }catch(Exception error){throw new IllegalStateException("EXECUTION_PROGRESS_CHECK_FAILED",error);}
    }
    private void reset(){last="";unchanged=0;parts.clear();}
    private static Object canonical(JsonNode node){
        if(node==null||node.isNull())return null;
        if(node.isObject()){var out=new TreeMap<String,Object>();for(var field:node.properties())if(!VOLATILE.contains(field.getKey()))out.put(field.getKey(),canonical(field.getValue()));return out;}
        if(node.isArray()){var out=new ArrayList<Object>();for(var child:node)out.add(canonical(child));return out;}
        return JSON.convertValue(node,Object.class);
    }
}
