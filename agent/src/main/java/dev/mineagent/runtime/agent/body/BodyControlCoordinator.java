package dev.mineagent.runtime.agent.body;

import dev.mineagent.runtime.api.agent.BodyDomain;
import dev.mineagent.runtime.core.task.ActionControlLease;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Domain leases own intent; the existing action lease owns at most one cancellable child. */
public final class BodyControlCoordinator {
    private record Control(Set<BodyDomain> domains,BooleanSupplier current,Runnable interrupted){}
    private final BehaviorArbiter arbiter=new BehaviorArbiter();private final ActionControlLease child=new ActionControlLease();
    private final Map<UUID,Control> controls=new LinkedHashMap<>();private UUID childParent;
    public boolean acquire(UUID id,Set<BodyDomain> domains,int priority,BooleanSupplier current,Runnable interrupted){
        validate();if(controls.containsKey(id))return owns(id,domains);
        var result=arbiter.acquire(id,domains,priority);if(!result.acquired())return false;
        for(var old:result.preemptedTaskIds())interrupt(old);
        controls.put(id,new Control(Set.copyOf(domains),current,interrupted));return true;
    }
    public boolean owns(UUID id,Set<BodyDomain> domains){return controls.containsKey(id)&&domains.stream().allMatch(d->arbiter.ownerOf(d).filter(id::equals).isPresent());}
    public boolean owns(UUID id,BodyDomain domain){return arbiter.ownerOf(domain).filter(id::equals).isPresent();}
    public boolean occupied(){return !controls.isEmpty();}
    public Optional<UUID> owner(BodyDomain domain){return arbiter.ownerOf(domain);}
    public boolean claimChild(UUID parent,UUID operation,BooleanSupplier current,Runnable cancel){
        if(!controls.containsKey(parent)||!valid(controls.get(parent)))return false;
        if(!child.claim(operation,()->controls.containsKey(parent)&&valid(controls.get(parent))&&current.getAsBoolean(),cancel))return false;childParent=parent;return true;
    }
    public void releaseChild(UUID operation){child.release(operation);if(!child.owned())childParent=null;}
    public void release(UUID id){if(id.equals(childParent)){child.cancel();childParent=null;}controls.remove(id);arbiter.release(id);}
    private void interrupt(UUID id){var c=controls.remove(id);if(id.equals(childParent)){child.cancel();childParent=null;}arbiter.release(id);if(c!=null)c.interrupted.run();}
    private static boolean valid(Control c){try{return c.current.getAsBoolean();}catch(RuntimeException failure){return false;}}
    public boolean validate(){boolean ok=true;for(var e:List.copyOf(controls.entrySet()))if(!valid(e.getValue())){interrupt(e.getKey());ok=false;}return child.validate()&&ok;}
    public void cancel(){for(var id:List.copyOf(controls.keySet()))interrupt(id);child.cancel();childParent=null;}
    public boolean claimLegacy(UUID token,BooleanSupplier current,Runnable cancel){
        var domains=EnumSet.allOf(BodyDomain.class);if(!acquire(token,domains,50,current,cancel))return false;
        if(!claimChild(token,token,current,()->{})){release(token);return false;}return true;
    }
}
