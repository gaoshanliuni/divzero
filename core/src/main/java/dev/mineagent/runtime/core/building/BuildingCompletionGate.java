package dev.mineagent.runtime.core.building;

import java.util.*;

/** A conversation cannot report completion until its exact building operation/version has evidence. */
public final class BuildingCompletionGate {
    public record Pending(String id,long revision,String operation){}
    private final Map<String,Pending> pending=new LinkedHashMap<>();
    public synchronized boolean waiting(){return !pending.isEmpty();}
    public synchronized List<Pending> pending(){return List.copyOf(pending.values());}
    public synchronized void observe(String tool,Map<String,Object> result){
        String id=Objects.toString(result.get("id"),"");Object revision=result.get("revision");
        if(id.isBlank()||!(revision instanceof Number number))return;
        String operation=Objects.toString(result.get("operation"),"");var next=new Pending(id,number.longValue(),operation);
        if(Set.of("apply_building","control_building").contains(tool)){
            if("UNDONE".equals(result.get("status"))){var old=pending.get(id);if(old!=null&&old.revision==next.revision)pending.remove(id);}
            else if(!operation.isBlank())pending.put(id,next);
        }else if(tool.equals("verify_building")&&"VERIFIED".equals(result.get("verification"))&&next.equals(pending.get(id)))pending.remove(id);
    }
}
