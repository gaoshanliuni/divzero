package dev.mineagent.runtime.neoforge.ui;

import dev.mineagent.runtime.core.conversation.ToolValidation;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.ICancellableEvent;
import net.neoforged.neoforge.common.NeoForge;
import java.util.*;

/** Extension events carry immutable data. A hook may reject; it cannot grant authority or rewrite a receipt. */
public final class ToolLifecycleEvents {
    public record Invocation(UUID world,UUID owner,UUID agent,UUID operation,String tool,String arguments){}
    public static final class Before extends Event implements ICancellableEvent {
        private final Invocation invocation;private final List<ToolValidation.Issue> issues=new ArrayList<>();
        Before(Invocation invocation){this.invocation=invocation;}
        public Invocation invocation(){return invocation;}
        public void reject(String field,String problem,String expected){if(issues.size()<16)issues.add(new ToolValidation.Issue(field,problem,expected));setCanceled(true);}
        public List<ToolValidation.Issue> issues(){return List.copyOf(issues);}
    }
    public static final class After extends Event {
        private final Invocation invocation;private final String receipt;
        After(Invocation invocation,String receipt){this.invocation=invocation;this.receipt=receipt;}
        public Invocation invocation(){return invocation;}public String receipt(){return receipt;}
    }
    public static final class Session extends Event {
        private final UUID owner,agent,operation;private final String state,reason;
        Session(UUID owner,UUID agent,UUID operation,String state,String reason){this.owner=owner;this.agent=agent;this.operation=operation;this.state=state;this.reason=reason;}
        public UUID owner(){return owner;}public UUID agent(){return agent;}public UUID operation(){return operation;}public String state(){return state;}public String reason(){return reason;}
    }
    static Optional<Map<String,Object>> before(Invocation invocation){
        if(invocation.tool.equals("stop_actions"))return Optional.empty();
        var event=new Before(invocation);
        try{NeoForge.EVENT_BUS.post(event);}catch(Exception failed){event.reject("$","Extension validation failed: "+failed.getClass().getSimpleName(),"A completed validation hook");}
        return (event.isCanceled()||!event.issues.isEmpty())?Optional.of(Map.of("status","REJECTED","error","TOOL_DOMAIN_VALIDATION","category","VALIDATION","executionState","NOT_STARTED","worldModified",false,"issues",event.issues())):Optional.empty();
    }
    static void after(Invocation invocation,Map<String,Object> receipt){
        try{NeoForge.EVENT_BUS.post(new After(invocation,new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(receipt)));}
        catch(Exception failure){dev.mineagent.runtime.neoforge.MineAgentRuntimeMod.LOGGER.warn("Tool result hook failed after receipt, operation={}",invocation.operation);}
    }
    static void session(UUID owner,UUID agent,UUID operation,String state,String reason){
        try{NeoForge.EVENT_BUS.post(new Session(owner,agent,operation,state,reason));}catch(Exception failure){dev.mineagent.runtime.neoforge.MineAgentRuntimeMod.LOGGER.warn("Session hook failed, operation={}",operation);}
    }
    private ToolLifecycleEvents(){}
}
