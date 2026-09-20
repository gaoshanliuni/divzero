package dev.mineagent.runtime.core.feedback;

import com.fasterxml.jackson.databind.JsonNode;
import dev.mineagent.runtime.core.events.RuntimeEventStore;
import java.util.*;

/** Exact signed resource/event selector; the acceptance watermark is bound by Native, not by the model. */
public record FeedbackSubscriptionFilter(UUID packageId,long packageRevision,String canonicalSha256,String entry,String policySha256,Set<String> events,long afterSequence) {
    public static final Set<String> FIELDS=Set.of("package_id","package_revision","canonical_sha256","entry_path","policy_sha256","events");
    public FeedbackSubscriptionFilter {
        Objects.requireNonNull(packageId);events=Collections.unmodifiableSet(new TreeSet<>(events));
        if(packageRevision<1||packageRevision>9007199254740991L||canonicalSha256==null||!canonicalSha256.matches("[a-f0-9]{64}")||policySha256==null||!policySha256.matches("[a-f0-9]{64}")||entry==null||!entry.matches("ui/[A-Za-z0-9_./-]+\\.html")||entry.contains("..")||events.isEmpty()||events.size()>8||events.stream().anyMatch(e->!e.matches("[A-Za-z][A-Za-z0-9_-]{0,63}")||Set.of("prototype","constructor").contains(e))||afterSequence<0)throw new IllegalArgumentException("FEEDBACK_SUBSCRIPTION_FILTER");
    }
    public FeedbackSubscriptionFilter after(long sequence){return new FeedbackSubscriptionFilter(packageId,packageRevision,canonicalSha256,entry,policySha256,events,sequence);}
    public Map<String,Object> wire(){return Map.of("package_id",packageId.toString(),"package_revision",packageRevision,"canonical_sha256",canonicalSha256,"entry_path",entry,"policy_sha256",policySha256,"events",events.stream().sorted().toList());}
    public boolean overlaps(FeedbackSubscriptionFilter other){return other!=null&&packageId.equals(other.packageId)&&packageRevision==other.packageRevision&&canonicalSha256.equals(other.canonicalSha256)&&entry.equals(other.entry)&&policySha256.equals(other.policySha256)&&!Collections.disjoint(events,other.events);}
    public boolean matches(RuntimeEventStore.Context context,RuntimeEventStore.Event event){
        if(event.feedback()==null||event.feedback().sequence()<=afterSequence)return false;var scope=event.feedback().scope();
        return context.scope().worldId().equals(scope.worldId())&&context.scope().ownerId().equals(scope.ownerId())&&context.scope().agentId().equals(scope.agentId())&&packageId.equals(scope.packageId())&&packageRevision==scope.packageRevision()&&canonicalSha256.equals(scope.canonicalSha256())&&entry.equals(scope.entry())&&policySha256.equals(scope.policySha256())&&events.contains(scope.eventName());
    }
    public static FeedbackSubscriptionFilter parse(JsonNode n){try{
        for(String key:List.of("package_id","canonical_sha256","entry_path","policy_sha256"))if(!n.path(key).isTextual())throw new IllegalArgumentException();
        if(!n.path("package_revision").isIntegralNumber()||!n.get("package_revision").canConvertToLong()||!n.path("events").isArray()||n.get("events").size()>8)throw new IllegalArgumentException();
        var names=new TreeSet<String>();for(var value:n.get("events"))if(!value.isTextual()||!names.add(value.asText()))throw new IllegalArgumentException();
        return new FeedbackSubscriptionFilter(UUID.fromString(n.get("package_id").asText()),n.get("package_revision").longValue(),n.get("canonical_sha256").asText(),n.get("entry_path").asText(),n.get("policy_sha256").asText(),names,0);
    }catch(Exception e){throw new IllegalArgumentException("FEEDBACK_SUBSCRIPTION_FILTER",e);}}
}
