package dev.mineagent.runtime.core.ui;
import com.fasterxml.jackson.databind.*;
import dev.mineagent.runtime.core.persistence.SqliteRuntimeRepository;
import dev.mineagent.runtime.core.packages.RuntimePackageCanonicalizer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Client-owned UI state only. Callers supply the scope from trusted native bindings, never page arguments. */
public final class PackageUiStateStore implements AutoCloseable {
    private static final String NS="client_package_ui_state";
    private static final ObjectMapper JSON=new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    public record Scope(String server,UUID world,UUID viewer,UUID packageId,String entry,String target){
        public Scope{Objects.requireNonNull(world);Objects.requireNonNull(viewer);Objects.requireNonNull(packageId);
            if(server==null||server.isBlank()||server.length()>512||entry==null||!entry.startsWith("ui/")||entry.length()>256||entry.contains("..")||entry.contains("\\")||target==null||target.length()>256)throw new IllegalArgumentException("UI_STATE_SCOPE");}
        String key()throws Exception{return RuntimePackageCanonicalizer.sha256(JSON.writeValueAsBytes(List.of(server,viewer.toString(),packageId.toString(),entry,target)));}
    }
    public record Snapshot(long revision,boolean exists,String valueJson,long packageRevision){}
    private record Data(int schema,long packageRevision,Map<String,String> entries){}
    private record Loaded(long revision,Data data){}
    private final SqliteRuntimeRepository repo;private final Clock clock;
    public PackageUiStateStore(Path database,Clock clock)throws Exception{this.repo=new SqliteRuntimeRepository(database);this.clock=Objects.requireNonNull(clock);}
    public synchronized Snapshot get(Scope scope,String key)throws Exception{key(key);return snapshot(load(scope),key);}
    public synchronized Snapshot put(Scope scope,String key,long expected,long packageRevision,String valueJson,BooleanSupplier current)throws Exception{
        key(key);if(valueJson==null||valueJson.getBytes(StandardCharsets.UTF_8).length>32768)throw new IllegalArgumentException("UI_STATE_VALUE_BUDGET");
        String value;try{var tree=JSON.readTree(valueJson);if(tree==null)throw new IllegalArgumentException();value=JSON.writeValueAsString(tree);}catch(Exception invalid){throw new IllegalArgumentException("UI_STATE_JSON");}
        return write(scope,key,expected,packageRevision,value,current);
    }
    public synchronized Snapshot remove(Scope scope,String key,long expected,long packageRevision,BooleanSupplier current)throws Exception{key(key);return write(scope,key,expected,packageRevision,null,current);}
    private Snapshot write(Scope scope,String key,long expected,long packageRevision,String value,BooleanSupplier current)throws Exception{
        if(expected<0||expected==Long.MAX_VALUE||packageRevision<1)throw new IllegalArgumentException("UI_STATE_REVISION");
        var old=load(scope);if(old.revision()!=expected)throw new IllegalStateException("UI_STATE_CONFLICT");
        if(packageRevision<old.data().packageRevision())throw new IllegalStateException("UI_STATE_STALE_PACKAGE");
        var entries=new TreeMap<>(old.data().entries());if(value==null)entries.remove(key);else entries.put(key,value);
        if(entries.size()>64)throw new IllegalArgumentException("UI_STATE_KEY_BUDGET");
        var data=new Data(1,packageRevision,entries);String encoded=JSON.writeValueAsString(data);
        if(encoded.getBytes(StandardCharsets.UTF_8).length>262144)throw new IllegalArgumentException("UI_STATE_TOTAL_BUDGET");
        if(!current.getAsBoolean())throw new IllegalStateException("UI_STATE_STALE_VIEW");
        var result=repo.compareAndSet(scope.world(),NS,scope.key(),expected,encoded,clock.millis());
        if(!result.accepted())throw new IllegalStateException("UI_STATE_CONFLICT");return snapshot(new Loaded(result.record().revision(),data),key);
    }
    private Loaded load(Scope scope)throws Exception{
        var row=repo.get(scope.world(),NS,scope.key());if(row.isEmpty())return new Loaded(0,new Data(1,0,Map.of()));
        try{var data=JSON.readValue(row.get().payload(),Data.class);
            if(data.schema()!=1||data.packageRevision()<1||data.entries()==null||data.entries().size()>64||row.get().payload().getBytes(StandardCharsets.UTF_8).length>262144)throw new IllegalArgumentException();
            for(var e:data.entries().entrySet()){key(e.getKey());if(e.getValue()==null||e.getValue().getBytes(StandardCharsets.UTF_8).length>32768||JSON.readTree(e.getValue())==null)throw new IllegalArgumentException();}
            return new Loaded(row.get().revision(),data);
        }catch(Exception invalid){throw new IllegalStateException("UI_STATE_CORRUPT");}
    }
    private static Snapshot snapshot(Loaded state,String key){return new Snapshot(state.revision(),state.data().entries().containsKey(key),state.data().entries().getOrDefault(key,"null"),state.data().packageRevision());}
    private static void key(String key){if(key==null||!key.matches("[A-Za-z0-9_.-]{1,64}")||key.equals(".")||key.contains(".."))throw new IllegalArgumentException("UI_STATE_KEY");}
    @Override public synchronized void close()throws Exception{repo.close();}
}
