package dev.mineagent.runtime.core.shared;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

/** Bounded structural transforms. No JS, SQL, caller-selected subjects or implicit value coercion. */
public record SharedStateMigration(long fromSchemaVersion,boolean confirmDrop,List<Step> steps){
    public record Step(String op,String key,String to,JsonNode value){}
    public SharedStateMigration{
        steps=List.copyOf(steps);
        if(fromSchemaVersion<1||fromSchemaVersion>=1_000_000||steps.isEmpty()||steps.size()>64)throw new IllegalArgumentException("SHARED_MIGRATION_BUDGET");
    }
    public static SharedStateMigration parse(String source){
        var node=SharedJson.parse(source,32768);SharedJson.keys(node,Set.of("from_schema_version","confirm_drop","steps"),Set.of("from_schema_version","steps"));
        if(!node.get("steps").isArray()||node.has("confirm_drop")&&!node.get("confirm_drop").isBoolean())throw new IllegalArgumentException("SHARED_MIGRATION_INPUT");
        boolean confirmed=node.path("confirm_drop").asBoolean(false);var steps=new ArrayList<Step>();
        for(var value:node.get("steps")){
            String op=SharedJson.text(value,"op","");
            switch(op){
                case "RENAME"->{SharedJson.keys(value,Set.of("op","key","to"),Set.of("op","key","to"));String key=SharedJson.name(SharedJson.text(value,"key","")),to=SharedJson.name(SharedJson.text(value,"to",""));if(key.equals(to))throw new IllegalArgumentException("SHARED_MIGRATION_SAME_KEY");steps.add(new Step(op,key,to,null));}
                case "DROP"->{SharedJson.keys(value,Set.of("op","key"),Set.of("op","key"));if(!confirmed)throw new IllegalArgumentException("SHARED_MIGRATION_DROP_CONFIRM_REQUIRED");steps.add(new Step(op,SharedJson.name(SharedJson.text(value,"key","")),"",null));}
                case "RESET_TTL"->{SharedJson.keys(value,Set.of("op","key"),Set.of("op","key"));steps.add(new Step(op,SharedJson.name(SharedJson.text(value,"key","")),"",null));}
                case "DEFAULT_SHARED","DEFAULT_ACTORS"->{SharedJson.keys(value,Set.of("op","key","value"),Set.of("op","key","value"));steps.add(new Step(op,SharedJson.name(SharedJson.text(value,"key","")),"",value.get("value").deepCopy()));}
                default->throw new IllegalArgumentException("SHARED_MIGRATION_STEP_UNSUPPORTED");
            }
        }
        return new SharedStateMigration(SharedJson.integer(node,"from_schema_version",-1),confirmed,steps);
    }
}
