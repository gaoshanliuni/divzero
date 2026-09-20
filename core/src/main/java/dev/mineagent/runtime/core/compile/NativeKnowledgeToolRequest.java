package dev.mineagent.runtime.core.compile;

import com.fasterxml.jackson.databind.*;
import java.util.*;

/** Strict Agent arguments for durable, owner/Agent-scoped Native knowledge. */
public record NativeKnowledgeToolRequest(String tool,UUID observation,UUID knowledge,String label,String query,int offset){
    public static final Set<String> TOOLS=Set.of("remember_native_api","search_native_knowledge","inspect_native_knowledge","revalidate_native_knowledge","forget_native_knowledge");
    private static final ObjectMapper JSON=new ObjectMapper().enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    public NativeKnowledgeToolRequest{
        if(!TOOLS.contains(tool)||label==null||query==null||label.length()>128||query.length()>128||offset<0||offset>4096)throw new IllegalArgumentException("NATIVE_KNOWLEDGE_ARGUMENTS");
        if(tool.equals("remember_native_api")&&(observation==null||knowledge!=null||label.isBlank()||!query.isEmpty()||offset!=0))throw new IllegalArgumentException("NATIVE_KNOWLEDGE_ARGUMENTS");
        if(tool.equals("search_native_knowledge")&&(observation!=null||knowledge!=null||!label.isEmpty()))throw new IllegalArgumentException("NATIVE_KNOWLEDGE_ARGUMENTS");
        if(Set.of("inspect_native_knowledge","revalidate_native_knowledge","forget_native_knowledge").contains(tool)&&(observation!=null||knowledge==null||!label.isEmpty()||!query.isEmpty()||offset!=0))throw new IllegalArgumentException("NATIVE_KNOWLEDGE_ARGUMENTS");
    }
    public static NativeKnowledgeToolRequest parse(String tool,String source){
        try{if(source==null||source.length()>4096)throw new IllegalArgumentException();var n=JSON.readTree(source);if(!n.isObject())throw new IllegalArgumentException();var keys=new HashSet<String>();n.fieldNames().forEachRemaining(keys::add);Set<String> expected=switch(tool){case "remember_native_api"->Set.of("observation_id","label");case "search_native_knowledge"->n.has("offset")?Set.of("query","offset"):Set.of("query");case "inspect_native_knowledge","revalidate_native_knowledge","forget_native_knowledge"->Set.of("knowledge_id");default->throw new IllegalArgumentException();};if(!keys.equals(expected))throw new IllegalArgumentException();for(String key:keys)if(!key.equals("offset")&&!n.path(key).isTextual())throw new IllegalArgumentException();int offset=0;if(n.has("offset")){if(!n.path("offset").isIntegralNumber()||!n.path("offset").canConvertToInt())throw new IllegalArgumentException();offset=n.path("offset").intValue();}return new NativeKnowledgeToolRequest(tool,uuid(n,"observation_id"),uuid(n,"knowledge_id"),text(n,"label"),text(n,"query"),offset);}catch(Exception invalid){throw new IllegalArgumentException("NATIVE_KNOWLEDGE_ARGUMENTS",invalid);}
    }
    private static UUID uuid(JsonNode n,String key){return n.has(key)?UUID.fromString(n.path(key).textValue()):null;}
    private static String text(JsonNode n,String key){return n.has(key)?n.path(key).textValue():"";}
    public String canonical(){try{var value=new LinkedHashMap<String,Object>();switch(tool){case "remember_native_api"->{value.put("observation_id",observation.toString());value.put("label",label);}case "search_native_knowledge"->{value.put("query",query);value.put("offset",offset);}default->value.put("knowledge_id",knowledge.toString());}return JSON.writeValueAsString(value);}catch(Exception failure){throw new IllegalStateException("NATIVE_KNOWLEDGE_ENCODING",failure);}}
}
