package dev.mineagent.runtime.core.conversation;

import com.fasterxml.jackson.databind.*;
import dev.mineagent.runtime.core.persistence.SqliteRuntimeRepository;
import java.nio.file.Path;
import java.util.*;

/** Complete task records, separate from the bounded model view. Access stays world/player/AI scoped. */
public final class ExecutionRecords {
    private static final String NAMESPACE="conversation_execution_v1";
    private static final ObjectMapper JSON=new ObjectMapper().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS,true);
    public record Scope(UUID world,UUID owner,UUID agent){}
    private ExecutionRecords(){}
    public static void write(Path database,Scope scope,UUID record,UUID request,UUID conversation,long ordinal,String tool,JsonNode arguments,Map<String,Object> result,long at)throws Exception{
        write(database,scope,record,request,conversation,ordinal,tool,arguments,arguments.toString(),result,at);
    }
    public static void write(Path database,Scope scope,UUID record,UUID request,UUID conversation,long ordinal,String tool,JsonNode arguments,String rawArguments,Map<String,Object> result,long at)throws Exception{
        var value=new LinkedHashMap<String,Object>();value.put("owner",scope.owner);value.put("agent",scope.agent);value.put("world",scope.world);value.put("recordId",record);
        value.put("requestId",request);value.put("conversation",conversation);value.put("ordinal",ordinal);value.put("tool",tool);value.put("arguments",arguments);
        value.put("rawArguments",rawArguments);value.put("result",result);value.put("observedAt",at);value.put("replayAllowed",false);
        String source=JSON.writeValueAsString(value);
        try(var db=new SqliteRuntimeRepository(database)){
            var old=db.get(scope.world,NAMESPACE,record.toString());
            if(old.isPresent()){if(!old.get().payload().equals(source))throw new IllegalStateException("EXECUTION_RECORD_REUSED");return;}
            if(!db.compareAndSet(scope.world,NAMESPACE,record.toString(),0,source,at).accepted())throw new IllegalStateException("EXECUTION_RECORD_WRITE_CONFLICT");
        }
    }
    public static Map<String,Object> read(Path database,Scope scope,UUID record,int offset,int length)throws Exception{
        if(offset<0||length<1||length>8192)throw new IllegalArgumentException("EXECUTION_RECORD_RANGE");
        try(var db=new SqliteRuntimeRepository(database)){
            var row=db.get(scope.world,NAMESPACE,record.toString()).orElseThrow(()->new IllegalArgumentException("EXECUTION_RECORD_NOT_FOUND"));
            var value=JSON.readTree(row.payload());
            if(!scope.owner.toString().equals(value.path("owner").asText())||!scope.agent.toString().equals(value.path("agent").asText()))throw new SecurityException("EXECUTION_RECORD_SCOPE");
            String text=row.payload();if(offset>text.length()||offset>0&&offset<text.length()&&Character.isLowSurrogate(text.charAt(offset)))throw new IllegalArgumentException("EXECUTION_RECORD_RANGE");
            int end=(int)Math.min(text.length(),(long)offset+length);if(end<text.length()&&end>offset&&Character.isHighSurrogate(text.charAt(end-1)))end--;
            return Map.of("status","OBSERVED","record_id",record,"recordRevision",row.revision(),"text",text.substring(offset,end),"offset",offset,"nextOffset",end<text.length()?end:-1,"totalLength",text.length(),"historical",true,"replayAllowed",false);
        }
    }
}
