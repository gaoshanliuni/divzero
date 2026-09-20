package dev.mineagent.runtime.core.feedback;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import dev.mineagent.runtime.core.shared.SharedStateTransaction;
import java.nio.charset.StandardCharsets;
import java.util.TreeSet;

/** A bounded, data-only plan returned by package code; no SQL, actor override or executable validator. */
public record FeedbackTransactionPlan(SharedStateTransaction transaction,String canonical,String sha256) {
    private static final ObjectMapper JSON=new ObjectMapper(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build()).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    public FeedbackTransactionPlan {
        try {
            if(transaction==null||canonical==null||canonical.getBytes(StandardCharsets.UTF_8).length>32768||sha256==null||!sha256.matches("[a-f0-9]{64}"))throw new IllegalArgumentException();
            var parsed=SharedStateTransaction.parse(canonical);
            // Jackson reconstructs nullable JsonNode components as NullNode. Compare
            // their serialized data shape, then retain only the strict canonical parse.
            if(!JSON.valueToTree(parsed).equals(JSON.valueToTree(transaction))||!sorted(JSON.readTree(canonical)).toString().equals(canonical)
                    ||!dev.mineagent.runtime.core.packages.RuntimePackageCanonicalizer.sha256(canonical.getBytes(StandardCharsets.UTF_8)).equals(sha256))throw new IllegalArgumentException();
            // Own the parsed tree; callers must not mutate a reserved plan through JsonNode values.
            transaction=parsed;
        }catch(Exception e){throw new IllegalArgumentException("FEEDBACK_TRANSACTION_PLAN",e);}
    }
    @Override public SharedStateTransaction transaction(){return SharedStateTransaction.parse(canonical);}
    public static FeedbackTransactionPlan parse(FeedbackDataBinding binding,String source){try{
        if(binding==null||source==null||source.getBytes(StandardCharsets.UTF_8).length>32768)throw new IllegalArgumentException();var n=JSON.readTree(source);
        if(!n.isObject()||n.size()!=1||!n.path("transaction").isObject())throw new IllegalArgumentException();String canonical=sorted(n.get("transaction")).toString();var transaction=SharedStateTransaction.parse(canonical);binding.validate(transaction);
        return new FeedbackTransactionPlan(transaction,canonical,dev.mineagent.runtime.core.packages.RuntimePackageCanonicalizer.sha256(canonical.getBytes(StandardCharsets.UTF_8)));
    }catch(Exception e){throw new IllegalArgumentException("FEEDBACK_TRANSACTION_PLAN",e);}}
    private static JsonNode sorted(JsonNode value){if(value.isObject()){var output=JSON.createObjectNode();var keys=new TreeSet<String>();value.fieldNames().forEachRemaining(keys::add);for(String key:keys)output.set(key,sorted(value.get(key)));return output;}if(value.isArray()){var output=JSON.createArrayNode();value.forEach(v->output.add(sorted(v)));return output;}return value.deepCopy();}
}
