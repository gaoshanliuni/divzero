package dev.mineagent.runtime.core.ui.dynamic;

import com.fasterxml.jackson.databind.*;
import dev.mineagent.runtime.core.persistence.SqliteRuntimeRepository;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** A callback is durably reserved once. Unknown/running records are inspected, never replayed. */
public final class NativeUiEventStore implements AutoCloseable {
    private static final ObjectMapper JSON=new ObjectMapper();private static final String NS="native_ui_events";
    public record Event(NativeUiStore.Scope scope,String view,long viewRevision,UUID id,UUID toolOperation,String fingerprint,String tool,String state,String result){}
    public record Claim(boolean dispatch,Event event){}
    private final SqliteRuntimeRepository db;
    public NativeUiEventStore(Path path)throws Exception{db=new SqliteRuntimeRepository(path);}
    private static String key(NativeUiStore.Scope scope,UUID id){return scope.owner()+":"+id;}
    public Claim begin(NativeUiStore.Scope scope,String view,long revision,UUID id,String node,String action,String tool,String arguments)throws Exception{
        if(revision<1||view==null||!view.matches("[A-Za-z][A-Za-z0-9_-]{0,95}")||arguments==null||arguments.length()>65536)throw new IllegalArgumentException("NATIVE_EVENT_INPUT");
        String material=JSON.writeValueAsString(List.of(scope,view,revision,node,action,tool,arguments));String fingerprint=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8)));
        UUID operation=UUID.nameUUIDFromBytes((scope.world()+"|native-ui-event|"+scope.owner()+"|"+id).getBytes(StandardCharsets.UTF_8));var next=new Event(scope,view,revision,id,operation,fingerprint,tool,"DISPATCHING","");
        var row=db.compareAndSet(scope.world(),NS,key(scope,id),0,JSON.writeValueAsString(next),System.currentTimeMillis());if(row.accepted())return new Claim(true,next);
        var previous=JSON.readValue(row.record().payload(),Event.class);if(!previous.scope.equals(scope)||!previous.fingerprint.equals(fingerprint))throw new IllegalStateException("NATIVE_EVENT_ID_REUSED");return new Claim(false,previous);
    }
    public Event complete(Event event,String result)throws Exception{
        if(result==null||result.length()>65536)throw new IllegalArgumentException("NATIVE_EVENT_RECEIPT_SIZE");JSON.readTree(result);var existing=db.get(event.scope.world(),NS,key(event.scope,event.id)).orElseThrow(()->new IllegalStateException("NATIVE_EVENT_NOT_RESERVED"));var reserved=JSON.readValue(existing.payload(),Event.class);if(!reserved.scope.equals(event.scope)||!reserved.fingerprint.equals(event.fingerprint))throw new IllegalStateException("NATIVE_EVENT_ID_REUSED");var next=new Event(event.scope,event.view,event.viewRevision,event.id,event.toolOperation,event.fingerprint,event.tool,"COMPLETED",result);
        var row=db.compareAndSet(event.scope.world(),NS,key(event.scope,event.id),1,JSON.writeValueAsString(next),System.currentTimeMillis());if(!row.accepted()){var old=JSON.readValue(row.record().payload(),Event.class);if(!old.equals(next))throw new IllegalStateException("NATIVE_EVENT_OUTCOME_CONFLICT");return old;}return next;
    }
    public List<Event> list(NativeUiStore.Scope scope,String view)throws Exception{
        var result=new ArrayList<Map.Entry<Long,Event>>();for(var row:db.list(scope.world(),NS))if(row.recordId().startsWith(scope.owner()+":")){var event=JSON.readValue(row.payload(),Event.class);if(event.scope.equals(scope)&&event.view.equals(view))result.add(Map.entry(row.updatedAtEpochMillis(),event));}return result.stream().sorted(Map.Entry.<Long,Event>comparingByKey().reversed()).limit(64).map(Map.Entry::getValue).toList();
    }
    @Override public void close()throws Exception{db.close();}
}
