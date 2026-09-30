package dev.mineagent.runtime.core.conversation;

import com.fasterxml.jackson.databind.*;
import java.util.*;

/** Atomic provider groups and loss-explicit observations. Full originals live in ExecutionRecords. */
public final class ExecutionContext {
    private static final ObjectMapper JSON=new ObjectMapper();
    private ExecutionContext(){}
    public static List<List<Map<String,Object>>> groups(List<Map<String,Object>> history){
        var groups=new ArrayList<List<Map<String,Object>>>();var current=new ArrayList<Map<String,Object>>();var pending=new HashSet<String>();
        for(var message:history){
            String role=Objects.toString(message.get("role"));
            if(pending.isEmpty()&&!current.isEmpty()){groups.add(List.copyOf(current));current.clear();}
            current.add(message);
            if(role.equals("assistant")&&message.get("tool_calls") instanceof Collection<?> calls)
                for(var call:calls){var node=JSON.valueToTree(call);if(!pending.add(node.path("id").asText()))throw new IllegalArgumentException("EXECUTION_DUPLICATE_TOOL_ID");}
            if(role.equals("tool")&&!pending.remove(Objects.toString(message.get("tool_call_id"))))throw new IllegalArgumentException("EXECUTION_ORPHAN_TOOL_RESULT");
        }
        if(!pending.isEmpty())throw new IllegalArgumentException("EXECUTION_INCOMPLETE_TOOL_GROUP");
        if(!current.isEmpty())groups.add(List.copyOf(current));return List.copyOf(groups);
    }
    /** Keep every nested status/identity/version/error verbatim, including unknown and unverified. */
    public static Map<String,Object> critical(Object value){
        var out=new LinkedHashMap<String,Object>();collect(JSON.valueToTree(value),"$",out);return out;
    }
    private static void collect(JsonNode node,String path,Map<String,Object> out){
        if(node.isObject())for(var field:node.properties()){
            String name=field.getKey().toLowerCase(Locale.ROOT),child=path+"."+field.getKey();JsonNode v=field.getValue();
            if(v.isValueNode()&&(name.equals("id")||name.endsWith("_id")||name.endsWith("id")||Set.of("session","target","entity","dimension").contains(name)||name.contains("operation")||name.contains("record")||name.contains("revision")||name.contains("version")||name.contains("hash")||name.contains("status")||name.contains("state")||name.contains("error")||name.contains("reason")||name.contains("verified")||name.equals("expected")||name.equals("field")||name.equals("problem")||name.contains("next")||name.contains("pending")))out.put(child,JSON.convertValue(v,Object.class));
            else if(v.isContainerNode())collect(v,child,out);
        }
        else if(node.isArray())for(int i=0;i<node.size();i++)collect(node.get(i),path+"["+i+"]",out);
    }
    public static Map<String,Object> observation(Map<String,Object> full,UUID record){
        var result=new LinkedHashMap<String,Object>(full);result.put("record_id",record.toString());
        try{String raw=JSON.writeValueAsString(result);if(raw.length()<=6000)return result;
            var compact=new LinkedHashMap<String,Object>();compact.put("record_id",record.toString());compact.put("detailsArchived",true);compact.put("critical",critical(result));
            compact.put("preview",raw.substring(0,Math.min(2400,raw.length())));compact.put("originalCharacters",raw.length());
            compact.put("readMore","Use read_execution_record with record_id and nextOffset; this preview is incomplete, not a complete scan or file.");return compact;
        }catch(Exception error){throw new IllegalArgumentException("EXECUTION_RESULT_ENCODING",error);}
    }
}
