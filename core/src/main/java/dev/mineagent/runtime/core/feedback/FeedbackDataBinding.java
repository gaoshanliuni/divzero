package dev.mineagent.runtime.core.feedback;

import com.fasterxml.jackson.databind.JsonNode;
import dev.mineagent.runtime.core.shared.SharedStateTransaction;
import java.util.*;

/** Explicit data scope for an independently authorized package consumer, never a page-selected actor. */
public record FeedbackDataBinding(UUID instanceId,String namespace,long schemaVersion,Set<String> readKeys,Set<String> writeKeys) {
    public FeedbackDataBinding {
        Objects.requireNonNull(instanceId);readKeys=Collections.unmodifiableSet(new TreeSet<>(readKeys));writeKeys=Collections.unmodifiableSet(new TreeSet<>(writeKeys));
        if(namespace==null||!namespace.matches("[A-Za-z][A-Za-z0-9_.-]{0,63}")||schemaVersion<1||schemaVersion>1_000_000||readKeys.isEmpty()||readKeys.size()>8||writeKeys.isEmpty()||writeKeys.size()>8||!readKeys.containsAll(writeKeys))throw new IllegalArgumentException("FEEDBACK_DATA_BINDING");
        for(String key:readKeys)if(!key.matches("[A-Za-z][A-Za-z0-9_.-]{0,63}"))throw new IllegalArgumentException("FEEDBACK_DATA_KEY");
    }
    public Map<String,Object> wire(){return Map.of("instance_id",instanceId.toString(),"namespace",namespace,"schema_version",schemaVersion,"read_keys",readKeys.stream().toList(),"write_keys",writeKeys.stream().toList());}
    public void validate(SharedStateTransaction transaction){if(transaction.schemaVersion()!=schemaVersion||transaction.conditions().stream().anyMatch(c->!readKeys.contains(c.key()))||transaction.writes().stream().anyMatch(w->!writeKeys.contains(w.key())))throw new IllegalArgumentException("FEEDBACK_TRANSACTION_OUTSIDE_BINDING");}
    public static FeedbackDataBinding parse(JsonNode n){try{
        if(n==null||!n.isObject()||n.size()!=5||!n.path("instance_id").isTextual()||!n.path("namespace").isTextual()||!n.path("schema_version").isIntegralNumber()||!n.get("schema_version").canConvertToLong())throw new IllegalArgumentException();
        return new FeedbackDataBinding(UUID.fromString(n.get("instance_id").asText()),n.get("namespace").asText(),n.get("schema_version").longValue(),keys(n.get("read_keys")),keys(n.get("write_keys")));
    }catch(Exception e){throw new IllegalArgumentException("FEEDBACK_DATA_BINDING",e);}}
    private static Set<String> keys(JsonNode n){if(n==null||!n.isArray()||n.isEmpty()||n.size()>8)throw new IllegalArgumentException();var keys=new TreeSet<String>();for(var value:n)if(!value.isTextual()||!keys.add(value.asText()))throw new IllegalArgumentException();return keys;}
}
