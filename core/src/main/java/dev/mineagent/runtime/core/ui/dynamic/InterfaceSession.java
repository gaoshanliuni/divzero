package dev.mineagent.runtime.core.ui.dynamic;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

/** One owner/world/agent/view session. Callers serialize this on their UI thread. */
public final class InterfaceSession<T extends AutoCloseable> implements AutoCloseable {
    public record Scope(UUID world,UUID owner,UUID agent,UUID connection,String view){public Scope{Objects.requireNonNull(world);Objects.requireNonNull(owner);Objects.requireNonNull(agent);Objects.requireNonNull(connection);if(view==null||!view.matches("[A-Za-z][A-Za-z0-9_-]{0,95}"))throw new IllegalArgumentException("INTERFACE_VIEW_ID");}}
    public interface Builder<T>{T build(InterfaceDefinition definition,Map<String,JsonNode> data) throws Exception;}
    public record Receipt(boolean applied,long revision,long dataRevision,String error){}
    private final Scope scope;
    private InterfaceDefinition definition;
    private T rendered;
    private long revision,dataRevision;
    private Map<String,JsonNode> data=new LinkedHashMap<>();
    private final Set<String> dirtyInputs=new HashSet<>();
    private boolean closed,interactive,visible=true;
    public InterfaceSession(Scope scope){this.scope=Objects.requireNonNull(scope);}
    public Scope scope(){return scope;}
    public long revision(){return revision;}
    public long dataRevision(){return dataRevision;}
    public T rendered(){return rendered;}
    public InterfaceDefinition definition(){return definition;}
    public boolean visible(){return visible&&!closed;}
    public boolean interactive(){return interactive&&!closed;}
    public Map<String,JsonNode> data(){return copy(data);}
    /** Recreate renderer ownership after the enclosing workspace closes, without changing the document/data revision. */
    public Receipt remount(Builder<T> builder){
        requireOpen();if(definition==null)throw new IllegalStateException("INTERFACE_NOT_BUILT");
        long expected=revision,expectedData=dataRevision;T candidate=null;
        try{candidate=Objects.requireNonNull(builder.build(definition,copy(data)));require(scope,expected);
            if(dataRevision!=expectedData)throw new IllegalStateException("INTERFACE_DATA_CHANGED_DURING_BUILD");
            T previous=rendered;rendered=candidate;candidate=null;dispose(previous);return new Receipt(true,revision,dataRevision,"");
        }catch(Exception failure){dispose(candidate);return new Receipt(false,revision,dataRevision,String.valueOf(failure.getMessage()));}
    }
    public void interactive(boolean value){requireOpen();interactive=value;}
    public void visible(boolean value){requireOpen();visible=value;}

    public Receipt replace(Scope expected,long expectedRevision,String source,Builder<T> builder){
        require(expected,expectedRevision);long expectedDataRevision=dataRevision;T candidate=null;
        try{
            var next=InterfaceDefinition.parse(source);if(!scope.view.equals(next.id()))throw InterfaceDefinition.error("$.id","VIEW_MISMATCH");
            var values=new LinkedHashMap<>(next.data());
            if(definition!=null){var priorDefaults=definition.data();for(var entry:data.entrySet())if(!dirtyInputs.contains(entry.getKey())&&values.containsKey(entry.getKey())&&Objects.equals(priorDefaults.get(entry.getKey()),values.get(entry.getKey())))values.put(entry.getKey(),entry.getValue().deepCopy());}
            if(definition!=null)for(var entry:next.handlers().entrySet()){var handler=entry.getValue();if(handler.equals(definition.handlers().get(entry.getKey()))&&!values.containsKey(handler.resultKey())&&data.containsKey(handler.resultKey()))values.put(handler.resultKey(),data.get(handler.resultKey()).deepCopy());}
            // Only matching stable input identities inherit drafts. A renamed/type-changed binding is a new control.
            var kept=new HashSet<String>();
            if(definition!=null){var old=definition.inputBindings();for(var entry:next.inputBindings().entrySet())
                if(entry.getValue().equals(old.get(entry.getKey()))){String key=entry.getValue().substring(entry.getValue().indexOf(':')+1);if(dirtyInputs.contains(key)&&data.containsKey(key)){values.put(key,data.get(key).deepCopy());kept.add(key);}}}
            candidate=Objects.requireNonNull(builder.build(next,copy(values)),"INTERFACE_NULL_CANDIDATE");
            // Candidate construction can call user code. Recheck after it returns.
            require(expected,expectedRevision);
            if(dataRevision!=expectedDataRevision)throw new IllegalStateException("INTERFACE_DATA_CHANGED_DURING_BUILD");
            boolean changedSurface=definition==null||definition.surface()!=next.surface();
            T previous=rendered;rendered=candidate;candidate=null;definition=next;data=values;revision++;dataRevision++;
            dirtyInputs.retainAll(kept);
            if(changedSurface)interactive=next.surface()==InterfaceDefinition.Surface.SCREEN;
            dispose(previous);return new Receipt(true,revision,dataRevision,"");
        }catch(Exception failure){dispose(candidate);return new Receipt(false,revision,dataRevision,String.valueOf(failure.getMessage()));}
    }
    /** Compute candidate bindings first; a failing sink leaves data/version unchanged. */
    public Receipt patch(Scope expected,long expectedRevision,long expectedDataRevision,Map<String,JsonNode> patch,
                         java.util.function.Consumer<Map<String,JsonNode>> apply){
        require(expected,expectedRevision);if(dataRevision!=expectedDataRevision)throw new IllegalStateException("INTERFACE_STALE_DATA");
        var next=copy(data);for(var e:patch.entrySet()){
            if(!e.getKey().matches("[A-Za-z][A-Za-z0-9_-]{0,95}")||e.getValue()==null)throw InterfaceDefinition.error("$.data","INVALID_PATCH");
            if(!dirtyInputs.contains(e.getKey()))next.put(e.getKey(),e.getValue().deepCopy());
        }
        if(next.size()>InterfaceDefinition.MAX_NODES)throw InterfaceDefinition.error("$.data","DATA_SIZE");
        try{apply.accept(Collections.unmodifiableMap(copy(next)));require(expected,expectedRevision);if(dataRevision!=expectedDataRevision)throw new IllegalStateException("INTERFACE_STALE_DATA");data=next;dataRevision++;return new Receipt(true,revision,dataRevision,"");}
        catch(Exception e){
            try{apply.accept(Collections.unmodifiableMap(copy(data)));}
            catch(Exception rollback){return new Receipt(false,revision,dataRevision,"INTERFACE_DATA_ROLLBACK_FAILED: "+e.getMessage());}
            return new Receipt(false,revision,dataRevision,String.valueOf(e.getMessage()));
        }
    }
    public void input(Scope expected,long expectedRevision,String nodeId,JsonNode value){
        input(expected,expectedRevision,nodeId,value,ignored->{});
    }
    /** Validate an entire restored form before applying it. Restoration never dispatches actions. */
    public Receipt restoreInputs(Scope expected,long expectedRevision,Map<String,JsonNode> inputs,java.util.function.Consumer<Map<String,JsonNode>> apply){
        require(expected,expectedRevision);if(!interactive||!visible)throw new IllegalStateException("INTERFACE_PASSIVE");
        var values=new LinkedHashMap<String,JsonNode>();
        for(var entry:inputs.entrySet()){
            var binding=definition.inputBindings().get(entry.getKey());var value=entry.getValue();
            if(binding==null||!definition.interactiveNode(entry.getKey(),data))throw new IllegalArgumentException("INTERFACE_INPUT_NODE");
            if(value==null||binding.startsWith("toggle:")&&!value.isBoolean()||binding.startsWith("input:")&&(!value.isTextual()||value.textValue().length()>16384))throw new IllegalArgumentException("INTERFACE_INPUT_VALUE");
            String key=binding.substring(binding.indexOf(':')+1);if(definition.sources().containsKey(key))throw new IllegalStateException("INTERFACE_SOURCE_READ_ONLY");
            if(values.putIfAbsent(key,value)!=null)throw new IllegalArgumentException("INTERFACE_DUPLICATE_INPUT_BINDING");
        }
        var receipt=localData(expected,expectedRevision,values,apply);if(receipt.applied())dirtyInputs.addAll(values.keySet());return receipt;
    }
    public void input(Scope expected,long expectedRevision,String nodeId,JsonNode value,java.util.function.Consumer<Map<String,JsonNode>> apply){
        require(expected,expectedRevision);if(!interactive||!visible)throw new IllegalStateException("INTERFACE_PASSIVE");
        String binding=definition.inputBindings().get(nodeId);if(binding==null)throw new IllegalArgumentException("INTERFACE_INPUT_NODE");
        if(!definition.interactiveNode(nodeId,data))throw new IllegalStateException("INTERFACE_DISABLED");
        if(value==null||binding.startsWith("toggle:")&&!value.isBoolean()||binding.startsWith("input:")&&(!value.isTextual()||value.textValue().length()>16384))throw new IllegalArgumentException("INTERFACE_INPUT_VALUE");
        String key=binding.substring(binding.indexOf(':')+1);if(definition.sources().containsKey(key))throw new IllegalStateException("INTERFACE_SOURCE_READ_ONLY");var next=copy(data);next.put(key,value.deepCopy());long expectedData=dataRevision;
        try{apply.accept(Collections.unmodifiableMap(copy(next)));require(expected,expectedRevision);if(dataRevision!=expectedData)throw new IllegalStateException("INTERFACE_STALE_DATA");data=next;dirtyInputs.add(key);dataRevision++;}
        catch(RuntimeException error){apply.accept(Collections.unmodifiableMap(copy(data)));throw error;}
    }
    /** Explicit local script actions may update a draft; unsolicited server patches may not. */
    public Receipt localData(Scope expected,long expectedRevision,Map<String,JsonNode> values,java.util.function.Consumer<Map<String,JsonNode>> apply){
        require(expected,expectedRevision);if(!interactive||!visible)throw new IllegalStateException("INTERFACE_PASSIVE");
        if(values.keySet().stream().anyMatch(definition.sources()::containsKey))throw new IllegalStateException("INTERFACE_SOURCE_READ_ONLY");
        var dirty=new HashSet<>(dirtyInputs);dirtyInputs.removeAll(values.keySet());
        try{return patch(expected,expectedRevision,dataRevision,values,apply);}finally{dirtyInputs.addAll(dirty);}
    }
    public JsonNode actions(Scope expected,long expectedRevision,String node,String event){
        require(expected,expectedRevision);if(!interactive||!visible)throw new IllegalStateException("INTERFACE_PASSIVE");
        var element=definition.node(node).orElseThrow(()->new IllegalArgumentException("INTERFACE_UNKNOWN_NODE"));
        if(!definition.interactiveNode(node,data))throw new IllegalStateException("INTERFACE_DISABLED");
        return element.path("events").path(event).deepCopy();
    }
    private void require(Scope expected,long rev){requireOpen();if(!scope.equals(expected))throw new SecurityException("INTERFACE_SCOPE_CHANGED");if(revision!=rev)throw new IllegalStateException("INTERFACE_STALE_REVISION");}
    private void requireOpen(){if(closed)throw new IllegalStateException("INTERFACE_CLOSED");}
    private static Map<String,JsonNode> copy(Map<String,JsonNode> source){var result=new LinkedHashMap<String,JsonNode>();source.forEach((k,v)->result.put(k,v.deepCopy()));return result;}
    private static void dispose(AutoCloseable value){if(value!=null)try{value.close();}catch(Exception ignored){/* The old UI has already been detached; never roll back a new revision because cleanup failed. */}}
    @Override public void close(){if(!closed){closed=true;dispose(rendered);rendered=null;data.clear();dirtyInputs.clear();}}
}
