package dev.mineagent.runtime.core.compile;

import com.fasterxml.jackson.databind.*;
import dev.mineagent.runtime.core.content.ContentAddressedStore;
import dev.mineagent.runtime.core.content.ObservationTokens;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Exact canonical metadata of selected declarations and implementations; never computes a content digest. */
public final class NativeKnowledgeArtifact {
    private static final ObjectMapper JSON=new ObjectMapper().enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    private static final ObservationTokens OBSERVATIONS=new ObservationTokens(4096,8L*1024*1024);
    private NativeKnowledgeArtifact(){}
    public static String semanticHash(String context){return OBSERVATIONS.token(semanticContents(context));}
    public static String semanticHash(String context,ContentAddressedStore content)throws java.io.IOException{return content.namedKeyId("native_knowledge_semantics",List.of(semanticContents(context)));}
    public static boolean sameContents(String first,String second){return semanticContents(first).equals(semanticContents(second));}
    private static String semanticContents(String context){
        try{if(context==null||context.getBytes(StandardCharsets.UTF_8).length>NativeCoderContext.MAX_BYTES)throw new IllegalArgumentException("NATIVE_KNOWLEDGE_CONTEXT_LIMIT");var root=JSON.readTree(context);if(!root.isObject()||root.path("schema").asInt()!=1||!root.path("classes").isArray())throw new IllegalArgumentException("NATIVE_KNOWLEDGE_CONTEXT");var classes=new ArrayList<Object>();for(var value:root.path("classes")){if(!value.path("module").isTextual()||!value.path("classHash").asText().matches("[a-f0-9]{64}")||value.path("symbols").isMissingNode())throw new IllegalArgumentException("NATIVE_KNOWLEDGE_CONTEXT");classes.add(Map.of("module",value.path("module").asText(),"classHash",value.path("classHash").asText(),"symbols",canonical(value.path("symbols"))));}var methods=new ArrayList<Object>();if(root.has("methodImplementations")){if(!root.path("methodImplementations").isArray())throw new IllegalArgumentException("NATIVE_KNOWLEDGE_CONTEXT");for(var value:root.path("methodImplementations")){if(!value.path("module").isTextual()||!value.path("class").isTextual()||!value.path("classHash").asText().matches("[a-f0-9]{64}")||value.path("method").isMissingNode())throw new IllegalArgumentException("NATIVE_KNOWLEDGE_CONTEXT");methods.add(Map.of("module",value.path("module").asText(),"class",value.path("class").asText(),"classHash",value.path("classHash").asText(),"method",canonical(value.path("method"))));}}var sources=new ArrayList<Object>();if(root.has("sourceMappings")){if(!root.path("sourceMappings").isArray())throw new IllegalArgumentException("NATIVE_KNOWLEDGE_CONTEXT");for(var value:root.path("sourceMappings"))sources.add(canonical(value));}var fingerprint=new LinkedHashMap<String,Object>();fingerprint.put("classes",classes);fingerprint.put("methods",methods);if(!sources.isEmpty())fingerprint.put("sources",sources);return JSON.writeValueAsString(fingerprint);}catch(RuntimeException failure){throw failure;}catch(Exception failure){throw new IllegalArgumentException("NATIVE_KNOWLEDGE_CONTEXT",failure);}
    }
    private static Object canonical(JsonNode value){if(value.isObject()){var result=new TreeMap<String,Object>();value.properties().forEach(e->result.put(e.getKey(),canonical(e.getValue())));return result;}if(value.isArray()){var result=new ArrayList<Object>();value.forEach(v->result.add(canonical(v)));return result;}if(value.isTextual())return value.asText();if(value.isIntegralNumber())return value.bigIntegerValue();if(value.isFloatingPointNumber())return value.decimalValue();if(value.isBoolean())return value.asBoolean();if(value.isNull())return null;throw new IllegalArgumentException("NATIVE_KNOWLEDGE_CONTEXT");}
}
