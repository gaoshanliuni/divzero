package dev.mineagent.runtime.core.shared;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

public record SharedStateTransaction(long schemaVersion,Long expectedRevision,List<Condition> conditions,List<Write> writes){
    public record Condition(String key,String test,JsonNode value){}
    public record Write(String key,String op,JsonNode value){}
    public SharedStateTransaction{conditions=List.copyOf(conditions);writes=List.copyOf(writes);if(schemaVersion<1||schemaVersion>1_000_000||expectedRevision!=null&&(expectedRevision<0||expectedRevision>=SharedJson.SAFE_INTEGER)||conditions.size()>16||writes.isEmpty()||writes.size()>16)throw new IllegalArgumentException("SHARED_TRANSACTION_BUDGET");}
    public static SharedStateTransaction parse(String source){
        var n=SharedJson.parse(source,32768);SharedJson.keys(n,Set.of("schema_version","expected_revision","conditions","writes"),Set.of("schema_version","conditions","writes"));
        if(!n.get("conditions").isArray()||!n.get("writes").isArray())throw new IllegalArgumentException("SHARED_TRANSACTION_ARRAY");
        var conditions=new ArrayList<Condition>();var writes=new ArrayList<Write>();var keys=new HashSet<String>();
        for(var v:n.get("conditions")){SharedJson.keys(v,Set.of("key","test","value"),Set.of("key","test"));String test=SharedJson.text(v,"test","");if(!Set.of("ABSENT","EXISTS","EQ","LT","LTE","GT","GTE").contains(test)||v.has("value")==Set.of("ABSENT","EXISTS").contains(test))throw new IllegalArgumentException("SHARED_CONDITION");conditions.add(new Condition(SharedJson.name(SharedJson.text(v,"key","")),test,v.get("value")));}
        for(var v:n.get("writes")){SharedJson.keys(v,Set.of("key","op","value"),Set.of("key","op"));String op=SharedJson.text(v,"op",""),key=SharedJson.name(SharedJson.text(v,"key",""));if(!Set.of("PUT","PUT_IF_ABSENT","DELETE","ADD").contains(op)||v.has("value")==op.equals("DELETE")||!keys.add(key))throw new IllegalArgumentException("SHARED_WRITE");writes.add(new Write(key,op,v.get("value")));}
        return new SharedStateTransaction(SharedJson.integer(n,"schema_version",-1),n.has("expected_revision")?SharedJson.integer(n,"expected_revision",-1):null,conditions,writes);
    }
}
