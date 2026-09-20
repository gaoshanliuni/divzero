package dev.mineagent.runtime.core.delivery;
import java.util.*;

/** Covers every issued recipient and actual mutation, without treating a stored offer as a display. */
public final class DeliveryGoalCoverage {
    private DeliveryGoalCoverage(){}
    public record Effect(String tool,UUID deliveryId,long dataRevision,String dataSha256){
        public Effect{if(!Set.of("update_view","close_view","revoke_content").contains(tool)||deliveryId==null||tool.equals("update_view")&&(dataRevision<1||dataSha256==null||!dataSha256.matches("[a-f0-9]{64}")))throw new IllegalArgumentException("DELIVERY_GOAL_EFFECT");}
    }
    public static void require(ContentDeliveryStore.Context c,List<ContentDeliveryStore.Delivery> rows,List<DeliveryGoal> goals,List<Effect> effects){
        var index=new HashMap<UUID,ContentDeliveryStore.Delivery>();for(var d:rows)if(d.owner().equals(c.owner())&&d.agent().equals(c.agent()))index.put(d.id(),d);
        var checks=new HashMap<UUID,DeliveryGoal>();for(var g:goals)if(checks.put(g.deliveryId(),g)!=null||!index.containsKey(g.deliveryId()))throw new IllegalStateException("DELIVERY_GOAL_CONTEXT");
        for(var d:index.values())if(d.origin()!=null&&d.origin().world().equals(c.world())&&d.origin().task().equals(c.task())&&d.origin().intent()==c.intent()&&!checks.containsKey(d.id()))throw new IllegalStateException("DELIVERY_RECIPIENT_COVERAGE");
        var latest=new HashMap<UUID,Effect>();for(var effect:effects){
            var goal=checks.get(effect.deliveryId());var row=index.get(effect.deliveryId());if(goal==null||row==null)throw new IllegalStateException("DELIVERY_ACTION_COVERAGE");
            if(effect.tool().equals("update_view")){var old=latest.get(effect.deliveryId());if(old==null||effect.dataRevision()>old.dataRevision())latest.put(effect.deliveryId(),effect);else if(effect.dataRevision()==old.dataRevision()&&!effect.dataSha256().equals(old.dataSha256()))throw new IllegalStateException("DELIVERY_UPDATE_HISTORY_CONFLICT");}
            if(effect.tool().equals("close_view")){if(Set.of("OFFERED","OPENING","CLIENT_RECEIVED","RENDERED","INTERACTED","SUSPENDED","CLOSE_REQUESTED").contains(goal.status())||row.sessionId()!=null&&!goal.evidence().equals("CLOSE_ACK"))throw new IllegalStateException("DELIVERY_CLOSE_ACK_REQUIRED");}
            if(effect.tool().equals("revoke_content")&&!Set.of("REVOKED","CLOSED","REJECTED","EXPIRED","FAILED","UNAVAILABLE").contains(goal.status()))throw new IllegalStateException("DELIVERY_REVOKE_NOT_VERIFIED");
        }
        for(var effect:latest.values()){var goal=checks.get(effect.deliveryId());var row=index.get(effect.deliveryId());
            if(goal.dataRevision()!=effect.dataRevision()||!goal.dataSha256().equals(effect.dataSha256()))throw new IllegalStateException("DELIVERY_UPDATED_DATA_COVERAGE");
            if(Set.of("OPENING","CLIENT_RECEIVED","RENDERED","INTERACTED").contains(row.status())&&!goal.evidence().equals("DATA_PAINT"))throw new IllegalStateException("DELIVERY_UPDATED_PAINT_REQUIRED");
        }
    }
}
