package dev.mineagent.runtime.core.feedback;

import java.nio.charset.StandardCharsets;
import java.util.*;

/** Cooperative, bounded consumer. An unknown in-flight stage is never reconstructed or replayed. */
public final class UiFeedbackDataPump {
    public interface Port {
        FeedbackTransactionPlan plan(UiFeedbackStore.Item item)throws Exception;
        UiFeedbackStore.Completion apply(UiFeedbackStore.Item item)throws Exception;
        default void failed(UUID id,Exception failure){}
    }
    private record Work(UUID operation,String phase,UiFeedbackStore.Completion proof){}
    private final UiFeedbackStore store;private final Port port;
    private final LinkedHashMap<UUID,Work> active=new LinkedHashMap<>();
    public UiFeedbackDataPump(UiFeedbackStore store,Port port){this.store=Objects.requireNonNull(store);this.port=Objects.requireNonNull(port);}
    public synchronized Map<UUID,String> phases(){var result=new LinkedHashMap<UUID,String>();active.forEach((id,w)->result.put(id,w.phase()));return Collections.unmodifiableMap(result);}
    public synchronized int pump(int limit)throws Exception{
        if(limit<1||limit>4)throw new IllegalArgumentException("FEEDBACK_DATA_STEP_BUDGET");store.reconcile();int steps=0;
        // Snapshot the queue so a phase can advance at most once in this pump.
        for(UUID id:List.copyOf(active.keySet())){
            if(steps>=limit)break;steps++;var work=active.remove(id);
            try{
                var item=store.processing(id,work.operation());
                switch(work.phase()){
                    case "PLAN"->{store.reserveDataPlan(id,work.operation(),port.plan(item));active.put(id,new Work(work.operation(),"APPLY",null));}
                    case "APPLY"->{if(item.dataPlan()==null)throw new IllegalStateException("FEEDBACK_DATA_PLAN_MISSING");var proof=Objects.requireNonNull(port.apply(item));active.put(id,new Work(work.operation(),"VERIFY",proof));}
                    case "VERIFY"->store.dataResult(id,work.proof());
                    default->throw new IllegalStateException("FEEDBACK_DATA_PHASE");
                }
            }catch(Exception failure){store.interruptConsumer(id,"FEEDBACK_DATA_PROCESSING_INTERRUPTED");port.failed(id,failure);}
        }
        for(var item:store.readyConsumers("DETERMINISTIC",limit)){
            if(steps>=limit||active.size()>=4)break;
            if(!item.scope().policy().mode().equals("DETERMINISTIC")||!item.state().equals("ACCEPTED")||!item.exported())continue;
            steps++;UUID operation=UUID.nameUUIDFromBytes((item.scope().worldId()+"|feedback-data|"+item.id()).getBytes(StandardCharsets.UTF_8));
            try{store.processing(item.id(),operation);active.put(item.id(),new Work(operation,"PLAN",null));}
            catch(Exception failure){store.interruptConsumer(item.id(),"FEEDBACK_DATA_PROCESSING_INTERRUPTED");port.failed(item.id(),failure);}
        }
        return steps;
    }
}
