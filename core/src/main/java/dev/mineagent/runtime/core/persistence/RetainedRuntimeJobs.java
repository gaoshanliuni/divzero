package dev.mineagent.runtime.core.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.function.*;

/** Active records plus a bounded terminal LRU. Point lookups always consult retained IDs, not only the hot cache. */
public final class RetainedRuntimeJobs<T> {
    private final SqliteRuntimeRepository repo;private final UUID world;private final String ns;private final Class<T> type;
    private final Predicate<T> active;private final Function<T,UUID> id,worldOf;private final ToLongFunction<T> revision;
    private final ObjectMapper json=new ObjectMapper();private final Map<UUID,T> hot=new LinkedHashMap<>();
    private record Cached<T>(T value,long bytes){}
    private final Map<UUID,Cached<T>> cold=new LinkedHashMap<>(128,.75f,true);private long coldBytes;
    public RetainedRuntimeJobs(SqliteRuntimeRepository repo,UUID world,String ns,Class<T> type,Predicate<T> active,Function<T,UUID> id,Function<T,UUID> worldOf,ToLongFunction<T> revision){this.repo=repo;this.world=world;this.ns=ns;this.type=type;this.active=active;this.id=id;this.worldOf=worldOf;this.revision=revision;}
    public void loadActive()throws Exception{for(int offset=0;;offset+=256){var rows=repo.packageJobRows(world,ns,PackageJobRetention.Query.active(),offset,256);for(var row:rows)decode(row);if(rows.size()<256)break;}}
    public T get(UUID key){var current=hot.get(key);if(current!=null)return current;var cached=cold.get(key);if(cached!=null)return cached.value();try{var row=repo.getIncludingDeleted(world,ns,key.toString());if(row.isEmpty())return null;if(row.get().deleted())throw new IllegalStateException("PACKAGE_JOB_REMOVED");return decode(row.get());}catch(RuntimeException failure){throw failure;}catch(Exception failure){throw new IllegalStateException("PACKAGE_JOB_READ_UNAVAILABLE",failure);}}
    public void put(UUID key,T value){if(!key.equals(id.apply(value))||!world.equals(worldOf.apply(value)))throw new IllegalArgumentException("PACKAGE_JOB_CONTEXT");try{cache(value,json.writeValueAsBytes(value).length);}catch(Exception failure){throw new IllegalStateException("PACKAGE_JOB_CACHE_ENCODING",failure);}}
    public Collection<T> values(){return List.copyOf(hot.values());}
    public int size(){return hot.size();}
    public record Page<T>(List<T> items,long total,int offset,int nextOffset,boolean more,PackageJobRetention.Usage usage){}
    public Page<T> history(UUID owner,String state,String archive,int offset,int limit){if(limit<1||limit>16)throw new IllegalArgumentException("PACKAGE_HISTORY_PAGE");var query=new PackageJobRetention.Query(Objects.requireNonNull(owner),state,archive,null,"",null,null);try{var items=page(query,offset,limit);long total=repo.packageJobCount(world,ns,query);return new Page<>(items,total,offset,offset+items.size(),offset+items.size()<total,repo.packageJobUsage(world,ns,owner));}catch(java.sql.SQLException failure){throw new IllegalStateException("PACKAGE_JOB_READ_UNAVAILABLE",failure);}}
    public List<T> page(PackageJobRetention.Query query,int offset,int limit){try{var result=new ArrayList<T>();for(var row:repo.packageJobRows(world,ns,query,offset,limit))result.add(decode(row));return List.copyOf(result);}catch(RuntimeException failure){throw failure;}catch(Exception failure){throw new IllegalStateException("PACKAGE_JOB_READ_UNAVAILABLE",failure);}}
    /** Expensive compatibility API. Production ticks must use values() or indexed point lookups. */
    public List<T> all(){var result=new ArrayList<T>();for(int offset=0;;offset+=256){var page=page(PackageJobRetention.Query.all(null),offset,256);result.addAll(page);if(page.size()<256)return List.copyOf(result);}}
    private T decode(RuntimeRecord row)throws Exception{T value=json.readValue(row.payload(),type);if(!world.equals(worldOf.apply(value))||!row.recordId().equals(id.apply(value).toString())||row.revision()!=revision.applyAsLong(value))throw new IllegalStateException("PACKAGE_JOB_RECORD_CONTEXT");cache(value,row.payload().getBytes(java.nio.charset.StandardCharsets.UTF_8).length);return value;}
    private void cache(T value,long bytes){UUID key=id.apply(value);var prior=cold.remove(key);if(prior!=null)coldBytes-=prior.bytes();if(active.test(value)){hot.put(key,value);return;}hot.remove(key);if(bytes>16L*1024*1024)return;cold.put(key,new Cached<>(value,bytes));coldBytes+=bytes;while(cold.size()>128||coldBytes>16L*1024*1024){var first=cold.remove(cold.keySet().iterator().next());coldBytes-=first.bytes();}}
}
