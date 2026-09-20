package dev.mineagent.runtime.worker.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;

/** Reuses the exact offer schema, without pretending a future audience snapshot already exists. */
final class ScheduleDeliverySchemas {
    private ScheduleDeliverySchemas(){}
    static String create(String source){try{
        var json=new ObjectMapper();var root=(ObjectNode)json.readTree(source);var properties=(ObjectNode)root.get("properties");((ArrayNode)properties.path("mode").path("enum")).add("CONTENT_DELIVERY");
        var offer=(ObjectNode)json.readTree(DeliveryToolSchemas.OFFER);((ObjectNode)offer.get("properties")).remove("audience_snapshot_id");var required=json.createArrayNode();offer.path("required").forEach(n->{if(!n.asText().equals("audience_snapshot_id"))required.add(n);});offer.set("required",required);
        var delivery=properties.putObject("delivery").put("type","object").put("additionalProperties",false);var fields=delivery.putObject("properties");fields.putObject("audience_id").put("type","string").put("format","uuid");fields.putObject("audience_revision").put("type","integer").put("minimum",1);fields.set("offer",offer);delivery.putArray("required").add("audience_id").add("audience_revision").add("offer");return json.writeValueAsString(root);
    }catch(Exception invalid){throw new IllegalStateException("SCHEDULE_DELIVERY_SCHEMA",invalid);}}
}
