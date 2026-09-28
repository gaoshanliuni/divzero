package dev.mineagent.runtime.core.ui.dynamic;

import com.fasterxml.jackson.databind.*;
import dev.mineagent.runtime.core.persistence.SqliteRuntimeRepository;
import java.nio.file.Path;
import java.util.*;

/** Durable owner/agent definitions. Render acknowledgements remain a separate client lifecycle. */
public final class NativeUiStore implements AutoCloseable {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final String NS="native_ui";
    public record Scope(UUID world,UUID owner,UUID agent){}
    public record Saved(long revision,String dimension,String source,Map<String,JsonNode> data,boolean visible){
        public Saved{data=Map.copyOf(data);}
    }
    private final SqliteRuntimeRepository repository;
    public NativeUiStore(Path path)throws Exception{repository=new SqliteRuntimeRepository(path);}
    private static String key(Scope scope,String id){Objects.requireNonNull(scope.world);Objects.requireNonNull(scope.owner);Objects.requireNonNull(scope.agent);if(id==null||!id.matches("[A-Za-z][A-Za-z0-9_-]{0,95}"))throw new IllegalArgumentException("NATIVE_UI_ID");return scope.owner+":"+scope.agent+":"+id;}
    public Optional<Saved> get(Scope scope,String id)throws Exception{var row=repository.get(scope.world,NS,key(scope,id));return row.isEmpty()?Optional.empty():Optional.of(JSON.readValue(row.get().payload(),Saved.class));}
    public List<Map<String,Object>> list(Scope scope)throws Exception{
        var result=new ArrayList<Map<String,Object>>();String prefix=scope.owner+":"+scope.agent+":";
        for(var row:repository.list(scope.world,NS))if(row.recordId().startsWith(prefix)){
            var v=JSON.readValue(row.payload(),Saved.class);var definition=InterfaceDefinition.parse(v.source);
            result.add(Map.of("id",definition.id(),"title",definition.title(),"surface",definition.surface().name(),"revision",v.revision,"dimension",v.dimension,"visible",v.visible));
        }
        return List.copyOf(result);
    }
    public Saved save(Scope scope,String id,long expected,String dimension,String source,Map<String,JsonNode> data,boolean visible)throws Exception{
        if(expected<0||expected==Long.MAX_VALUE||!InterfaceDefinition.parse(source).id().equals(id))throw new IllegalArgumentException("NATIVE_UI_DEFINITION");
        if(dimension==null||!dimension.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")||data.size()>InterfaceDefinition.MAX_NODES||JSON.writeValueAsBytes(data).length>65536)throw new IllegalArgumentException("NATIVE_UI_STATE");
        var next=new Saved(expected+1,dimension,source,data,visible);
        var result=repository.compareAndSet(scope.world,NS,key(scope,id),expected,JSON.writeValueAsString(next),System.currentTimeMillis());
        if(!result.accepted())throw new IllegalStateException("NATIVE_UI_STALE_REVISION");return next;
    }
    @Override public void close()throws Exception{repository.close();}
}
