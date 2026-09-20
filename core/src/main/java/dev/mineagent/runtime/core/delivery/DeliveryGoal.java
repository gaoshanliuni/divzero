package dev.mineagent.runtime.core.delivery;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

/** Exact lifecycle facts, not human reading or arbitrary application correctness. */
public record DeliveryGoal(UUID deliveryId,long revision,long dataRevision,String dataSha256,String status,String evidence){
    public static final Set<String> EVIDENCE=Set.of("RECORD","ASSET_PAINT","DATA_PAINT","CLOSE_ACK","CODE_STATE");
    public static final Set<String> STATES=Set.of("OFFERED","OPENING","CLIENT_RECEIVED","RENDERED","INTERACTED","SUSPENDED","UNAVAILABLE","REJECTED","CLOSE_REQUESTED","CLOSED","REVOKED","EXPIRED","FAILED","CODE_DOWNLOADING","CODE_DOWNLOADED","CODE_RUNNING","CODE_STOPPED","CODE_SUSPENDED","CODE_FAILED");
    public DeliveryGoal{if(deliveryId==null||revision<1||dataRevision<1||revision>9007199254740991L||dataRevision>9007199254740991L||dataSha256==null||!dataSha256.matches("[a-f0-9]{64}")||!STATES.contains(status)||!EVIDENCE.contains(evidence))throw new IllegalArgumentException("DELIVERY_GOAL");}
    public static DeliveryGoal parse(JsonNode n){try{
        var keys=new HashSet<String>();n.fieldNames().forEachRemaining(keys::add);if(!keys.equals(Set.of("kind","delivery_id","expected_revision","data_revision","data_sha256","status","evidence"))||!n.path("kind").asText().equals("content_delivery"))throw new IllegalArgumentException();
        for(String key:List.of("delivery_id","data_sha256","status","evidence"))if(!n.path(key).isTextual())throw new IllegalArgumentException();
        for(String key:List.of("expected_revision","data_revision"))if(!n.path(key).isIntegralNumber()||!n.path(key).canConvertToLong())throw new IllegalArgumentException();
        return new DeliveryGoal(UUID.fromString(n.path("delivery_id").asText()),n.path("expected_revision").asLong(),n.path("data_revision").asLong(),n.path("data_sha256").asText(),n.path("status").asText(),n.path("evidence").asText());
    }catch(Exception invalid){throw new IllegalArgumentException("DELIVERY_GOAL_ARGUMENTS",invalid);}}
    public Map<String,Object> wire(){return Map.of("kind","content_delivery","delivery_id",deliveryId,"expected_revision",revision,"data_revision",dataRevision,"data_sha256",dataSha256,"status",status,"evidence",evidence);}
    public boolean matches(ContentDeliveryStore.Delivery d,boolean liveVisible){
        if(d==null||!deliveryId.equals(d.id())||revision!=d.revision()||dataRevision!=d.dataRevision()||!dataSha256.equals(ContentDeliveryStore.dataSha256(d.data()))||!status.equals(d.status()))return false;
        if(evidence.equals("RECORD"))return true;
        if(evidence.equals("CODE_STATE"))return d.asset().mode().startsWith("CLIENT_")&&d.status().startsWith("CODE_");
        if(evidence.equals("CLOSE_ACK"))return d.closeConfirmed()&&Set.of("CLOSED","REVOKED","EXPIRED","FAILED").contains(d.status());
        if(!liveVisible||!Set.of("RENDERED","INTERACTED").contains(d.status())||d.sessionId()==null||d.leaseId()==null||d.paintedAt()<1||d.paintSequence()<1||d.documentId().isBlank()||!d.layoutHash().matches("[a-f0-9]{64}"))return false;
        if(evidence.equals("ASSET_PAINT"))return true;
        var proof=d.dataReceipt();return proof!=null&&proof.revision()==dataRevision&&proof.sha256().equals(dataSha256)&&proof.documentId().equals(d.documentId())&&proof.paintSequence()==d.paintSequence()&&proof.layoutHash().equals(d.layoutHash());
    }
}
