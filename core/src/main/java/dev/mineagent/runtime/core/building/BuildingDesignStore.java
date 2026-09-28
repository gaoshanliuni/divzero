package dev.mineagent.runtime.core.building;

import dev.mineagent.runtime.core.persistence.SqliteRuntimeRepository;
import java.nio.file.Path;
import java.time.Clock;
import java.util.*;

/** Versioned definitions only. Saving a design never claims that world construction has completed. */
public final class BuildingDesignStore implements AutoCloseable {
    private static final String NAMESPACE="building_design";
    public record Scope(UUID world,UUID owner,UUID agent){public Scope{Objects.requireNonNull(world);Objects.requireNonNull(owner);Objects.requireNonNull(agent);}}
    public record Saved(long revision,BuildingDesign design,Set<String> changedComponents){}
    private final SqliteRuntimeRepository repository;
    private final Clock clock;
    public BuildingDesignStore(Path database,Clock clock)throws Exception{repository=new SqliteRuntimeRepository(database);this.clock=clock;}
    private static String key(Scope scope,String id){if(id==null||!id.matches("[A-Za-z][A-Za-z0-9_-]{0,95}"))throw new IllegalArgumentException("BUILDING_DESIGN_ID");return scope.owner+":"+scope.agent+":"+id;}
    public synchronized Optional<Saved> get(Scope scope,String id)throws Exception{
        var value=repository.get(scope.world,NAMESPACE,key(scope,id));
        if(value.isEmpty())return Optional.empty();var row=value.get();return Optional.of(new Saved(row.revision(),BuildingDesign.parse(row.payload()),Set.of()));
    }
    public synchronized Saved save(Scope scope,long expectedRevision,String source)throws Exception{
        if(expectedRevision<0||expectedRevision==Long.MAX_VALUE)throw new IllegalArgumentException("BUILDING_DESIGN_REVISION");
        var design=BuildingDesign.parse(source);var old=get(scope,design.id());if(old.map(Saved::revision).orElse(0L)!=expectedRevision)throw new IllegalStateException("BUILDING_DESIGN_STALE");
        var changed=design.changedComponents(old.map(Saved::design).orElse(null));
        var result=repository.compareAndSet(scope.world,NAMESPACE,key(scope,design.id()),expectedRevision,design.source(),clock.millis());
        if(!result.accepted())throw new IllegalStateException("BUILDING_DESIGN_STALE");return new Saved(result.record().revision(),design,changed);
    }
    @Override public void close()throws Exception{repository.close();}
}
