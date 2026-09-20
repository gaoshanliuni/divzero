package dev.mineagent.runtime.core.scheduling;

import com.fasterxml.jackson.databind.*;
import dev.mineagent.runtime.core.delivery.DeliveryToolRequest;
import java.util.*;

/** Static authored offer + standing audience. A real per-occurrence resolution supplies the later snapshot. */
public record ScheduleDeliveryConsumer(UUID audienceId,long audienceRevision,String offerJson){
    private static final ObjectMapper JSON=new ObjectMapper();
    public ScheduleDeliveryConsumer{Objects.requireNonNull(audienceId);if(audienceRevision<1)throw new IllegalArgumentException("SCHEDULE_DELIVERY_AUDIENCE");try{offerJson=JSON.writeValueAsString(sorted(JSON.readTree(DeliveryToolRequest.offerTemplate(offerJson).canonicalRequest())));}catch(Exception invalid){throw new IllegalArgumentException("SCHEDULE_DELIVERY_TEMPLATE",invalid);}}
    private static JsonNode sorted(JsonNode value){if(value.isObject()){var out=JSON.createObjectNode();var names=new TreeSet<String>();value.fieldNames().forEachRemaining(names::add);for(var name:names)out.set(name,sorted(value.get(name)));return out;}if(value.isArray()){var out=JSON.createArrayNode();value.forEach(v->out.add(sorted(v)));return out;}return value;}
    public static ScheduleDeliveryConsumer optional(JsonNode request){
        if(!request.has("delivery"))return null;var node=request.get("delivery");var keys=new HashSet<String>();if(!node.isObject())throw new IllegalArgumentException("SCHEDULE_DELIVERY_OBJECT");node.fieldNames().forEachRemaining(keys::add);
        if(!keys.equals(Set.of("audience_id","audience_revision","offer"))||!node.path("audience_id").isTextual()||!node.path("audience_revision").isIntegralNumber()||!node.path("audience_revision").canConvertToLong()||!node.path("offer").isObject())throw new IllegalArgumentException("SCHEDULE_DELIVERY_FIELDS");
        return new ScheduleDeliveryConsumer(UUID.fromString(node.get("audience_id").asText()),node.get("audience_revision").longValue(),node.get("offer").toString());
    }
    public DeliveryToolRequest template(){return DeliveryToolRequest.offerTemplate(offerJson);}
    public String offerRequest(UUID snapshot)throws Exception{Objects.requireNonNull(snapshot);var node=(com.fasterxml.jackson.databind.node.ObjectNode)JSON.readTree(offerJson);node.put("audience_snapshot_id",snapshot.toString());return DeliveryToolRequest.parse("offer_content",JSON.writeValueAsString(node)).canonicalRequest();}
    public Map<String,Object> wire(){try{return new TreeMap<>(Map.of("audience_id",audienceId,"audience_revision",audienceRevision,"offer",JSON.readTree(offerJson)));}catch(Exception invalid){throw new IllegalArgumentException("SCHEDULE_DELIVERY_ENCODING",invalid);}}
}
