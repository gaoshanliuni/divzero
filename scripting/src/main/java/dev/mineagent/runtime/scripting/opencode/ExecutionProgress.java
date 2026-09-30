package dev.mineagent.runtime.scripting.opencode;

import com.fasterxml.jackson.databind.*;
import java.util.*;

/** OpenCode repeated-call predicate plus DivZero outcome/context checks; never a total-round budget. */
public final class ExecutionProgress {
    private static final ObjectMapper JSON=new ObjectMapper().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS,true);
    private static final Set<String> VOLATILE=Set.of("observedAt","observedGameTick","gameTime","timestamp","elapsedMs","record_id","operation_id","fetchedAt","retrievedAt");
    private static final class Repeat{String outcome="";int count;Map<String,Object> result=Map.of();final List<Map<String,Object>> parts=new ArrayList<>();}
    private final LinkedHashMap<String,Repeat> observations=new LinkedHashMap<>(128,.75f,true);
    public record Decision(boolean warn,boolean blocked,int repetitions){}
    /** Suppress another identical failed write, while leaving the assistant and all other tools running. */
    public synchronized Optional<Map<String,Object>> suppressedWrite(String name,String raw,String context){
        try{JsonNode args;try{args=JSON.readTree(raw);}catch(Exception malformed){args=JSON.getNodeFactory().textNode(raw);}var repeat=observations.get(hash(List.of(name,hash(canonical(args)),context)));
            if(repeat==null||repeat.count<4)return Optional.empty();
            return Optional.of(Map.of("status","REJECTED","error","UNCHANGED_CALL_RESULT","executionState","NOT_STARTED","repeatSuppressed",true,"repetitions",repeat.count,"diagnostic","The same tool arguments and observed context repeatedly produced the same failure. This duplicate call was not executed.","previousResult",repeat.result));
        }catch(Exception invalid){return Optional.empty();}
    }
    public synchronized Decision observe(String name,String raw,Map<String,Object> result,String context,boolean readOnly){
        if(Boolean.TRUE.equals(result.get("repeatSuppressed")))return new Decision(true,true,4);
        String status=Objects.toString(result.get("status"),"");
        boolean candidate=readOnly||result.containsKey("error")||Set.of("WAITING","NO_CHANGE","REJECTED","READ_FAILED","BLOCKED").contains(status);
        if(!candidate){observations.clear();return new Decision(false,false,0);}
        try{
            JsonNode args;try{args=JSON.readTree(raw);}catch(Exception malformed){args=JSON.getNodeFactory().textNode(raw);}
            String parameters=hash(canonical(args));var input=Map.<String,Object>of("canonicalArgumentsSha256",parameters);
            String key=hash(List.of(name,parameters,context)),outcome=hash(canonical(JSON.valueToTree(result)));
            var repeat=observations.computeIfAbsent(key,k->new Repeat());while(observations.size()>128)observations.remove(observations.keySet().iterator().next());
            if(!outcome.equals(repeat.outcome)){repeat.outcome=outcome;repeat.count=0;repeat.parts.clear();}
            repeat.result=readOnly?Map.of():dev.mineagent.runtime.core.conversation.ToolFailure.summary(result);repeat.count++;repeat.parts.add(Map.of("type","tool","tool",name,"state",Map.of("status","completed","input",input)));
            if(repeat.parts.size()>3)repeat.parts.removeFirst();
            boolean repeated=repeat.count>=3&&OpenCodeRuntime.repeatedCalls(repeat.parts,name,input);
            return new Decision(repeated,repeated&&repeat.count>=4,repeat.count);
        }catch(Exception error){throw new IllegalStateException("EXECUTION_PROGRESS_CHECK_FAILED",error);}
    }
    private static String hash(Object value)throws Exception{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(JSON.writeValueAsBytes(value)));}
    private static Object canonical(JsonNode node){
        if(node==null||node.isNull())return null;
        if(node.isObject()){var out=new TreeMap<String,Object>();for(var field:node.properties())if(!VOLATILE.contains(field.getKey()))out.put(field.getKey(),canonical(field.getValue()));return out;}
        if(node.isArray()){var out=new ArrayList<Object>();for(var child:node)out.add(canonical(child));return out;}
        return JSON.convertValue(node,Object.class);
    }
}
