package dev.mineagent.runtime.core.memory;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mineagent.runtime.core.persistence.SqliteRuntimeRepository;
import java.nio.file.Path;
import java.time.Clock;
import java.util.*;
/** Owner/agent/world scoped facts, with replacement by topic and explicit expiry. Use off the game thread. */
public final class DialogueMemoryStore implements AutoCloseable {
    public record Entry(UUID id,String kind,String topic,String value,long revision,long updatedAt,long expiresAt,boolean forgotten){}
    private final SqliteRuntimeRepository db;private final UUID world;private final String namespace;private final Clock clock;
    private static final ObjectMapper JSON=new ObjectMapper();
    public DialogueMemoryStore(Path file,UUID world,UUID owner,UUID agent,Clock clock)throws Exception{db=new SqliteRuntimeRepository(file);this.world=world;this.namespace="dialogue_memory_"+UUID.nameUUIDFromBytes((owner+"|"+agent).getBytes(java.nio.charset.StandardCharsets.UTF_8));this.clock=clock;}
    private List<Entry> all()throws Exception{var out=new ArrayList<Entry>();for(var r:db.list(world,namespace))out.add(JSON.readValue(r.payload(),Entry.class));return out;}
    public Map<String,Object> inspect(String query,int offset)throws Exception{
        if(query==null||query.length()>128||offset<0)throw new IllegalArgumentException("MEMORY_QUERY");long now=clock.millis();String q=query.toLowerCase(Locale.ROOT);
        var rows=all().stream().filter(e->!e.forgotten()&&(e.expiresAt()==0||e.expiresAt()>now)).filter(e->(e.topic()+" "+e.value()).toLowerCase(Locale.ROOT).contains(q)).sorted(Comparator.comparingLong(Entry::updatedAt).reversed().thenComparing(e->e.id().toString())).toList();
        return Map.of("status","OBSERVED","entries",rows.stream().skip(offset).limit(16).toList(),"total",rows.size(),"nextOffset",offset+16<rows.size()?offset+16:-1,"observedAt",now);
    }
    public Entry remember(String kind,String topic,String value,long ttlSeconds)throws Exception{
        if(!Set.of("FACT","PREFERENCE").contains(kind)||topic==null||topic.isBlank()||topic.length()>128||value==null||value.isBlank()||value.length()>4096||ttlSeconds<0||ttlSeconds>31536000)throw new IllegalArgumentException("MEMORY_VALUE");
        topic=topic.strip().toLowerCase(Locale.ROOT);UUID id=UUID.nameUUIDFromBytes((kind+"|"+topic).getBytes(java.nio.charset.StandardCharsets.UTF_8));var old=db.get(world,namespace,id.toString());long rev=old.map(r->r.revision()).orElse(0L),now=clock.millis();
        var entry=new Entry(id,kind,topic,value.strip(),rev+1,now,ttlSeconds==0?0:Math.addExact(now,Math.multiplyExact(ttlSeconds,1000)),false);save(entry,rev);return entry;
    }
    public Entry forget(UUID id,long expected)throws Exception{
        var r=db.get(world,namespace,id.toString()).orElseThrow(()->new IllegalArgumentException("MEMORY_NOT_FOUND"));var old=JSON.readValue(r.payload(),Entry.class);if(r.revision()!=expected)throw new IllegalStateException("MEMORY_REVISION_CHANGED");
        var entry=new Entry(id,old.kind(),old.topic(),"",expected+1,clock.millis(),0,true);save(entry,expected);return entry;
    }
    private void save(Entry e,long expected)throws Exception{if(!db.compareAndSet(world,namespace,e.id().toString(),expected,JSON.writeValueAsString(e),e.updatedAt()).accepted())throw new IllegalStateException("MEMORY_REVISION_CHANGED");}
    public void close()throws Exception{db.close();}
}
