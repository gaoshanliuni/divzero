package dev.mineagent.runtime.core.shared;

import java.util.*;

public record SharedStateToolRequest(String tool,SharedStateTarget target,Set<String> keys,long cursor,int offset,String transaction,String canonical){
    public static final Set<String> TOOLS=Set.of("list_shared_namespaces","read_shared_state","transact_shared_state","watch_shared_state");
    public SharedStateToolRequest{keys=Set.copyOf(keys);}
    public static SharedStateToolRequest parse(String tool,String source){try{
        if(!TOOLS.contains(tool))throw new IllegalArgumentException();var n=SharedJson.parse(source,32768);boolean list=tool.equals("list_shared_namespaces");var fields=new HashSet<>(SharedStateTarget.FIELDS);if(list)fields.remove("namespace");
        String extra=switch(tool){case "list_shared_namespaces"->"offset";case "read_shared_state"->"keys";case "watch_shared_state"->"after_revision";default->"transaction";};fields.add(extra);var required=new HashSet<>(SharedStateTarget.FIELDS);if(list)required.remove("namespace");if(!list)required.add(extra);SharedJson.keys(n,fields,required);
        var target=SharedStateTarget.parse(n,!list);var keys=new TreeSet<String>();long cursor=0;int offset=0;String transaction="";var wire=new LinkedHashMap<>(target.wire());
        if(tool.equals("read_shared_state")){var values=n.get("keys");if(!values.isArray()||values.isEmpty()||values.size()>8)throw new IllegalArgumentException();for(var value:values)if(!value.isTextual()||!keys.add(SharedJson.name(value.asText())))throw new IllegalArgumentException();wire.put("keys",keys);}
        else if(list){offset=Math.toIntExact(SharedJson.integer(n,"offset",0));if(offset<0||offset>256)throw new IllegalArgumentException();wire.put("offset",offset);}
        else if(tool.equals("watch_shared_state")){cursor=SharedJson.integer(n,"after_revision",-1);if(cursor<0)throw new IllegalArgumentException();wire.put("after_revision",cursor);}
        else{if(!n.get("transaction").isObject())throw new IllegalArgumentException();transaction=n.get("transaction").toString();SharedStateTransaction.parse(transaction);wire.put("transaction",n.get("transaction"));}
        return new SharedStateToolRequest(tool,target,keys,cursor,offset,transaction,SharedJson.JSON.writeValueAsString(wire));
    }catch(Exception e){throw new IllegalArgumentException("SHARED_TOOL_ARGUMENTS",e);}}
}
