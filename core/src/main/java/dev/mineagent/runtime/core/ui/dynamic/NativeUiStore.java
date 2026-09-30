package dev.mineagent.runtime.core.ui.dynamic;

import com.fasterxml.jackson.databind.*;
import dev.mineagent.runtime.core.persistence.SqliteRuntimeRepository;
import java.nio.file.Path;
import java.util.*;

/** Durable owner/agent definitions. Render acknowledgements remain a separate client lifecycle. */
public final class NativeUiStore implements AutoCloseable {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final String NS="native_ui";
    private static final String CANDIDATES="native_ui_candidates";
    public record Scope(UUID world,UUID owner,UUID agent){}
    public record Candidate(UUID token,long expectedRevision,String dimension,String source){}
    public record FailedDraft(UUID token,String id,long baseRevision,String source,String diagnostic,long createdAt){}
    public record Owned(UUID agent,String id,Saved saved){}
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
    public List<Owned> owned(UUID world,UUID owner)throws Exception{
        var result=new ArrayList<Owned>();String prefix=owner+":";for(var row:repository.list(world,NS))if(row.recordId().startsWith(prefix)){
            String[] parts=row.recordId().split(":",3);if(parts.length!=3)throw new IllegalStateException("NATIVE_UI_STORED_SCOPE");result.add(new Owned(UUID.fromString(parts[1]),parts[2],JSON.readValue(row.payload(),Saved.class)));
        }return List.copyOf(result);
    }
    public Saved save(Scope scope,String id,long expected,String dimension,String source,Map<String,JsonNode> data,boolean visible)throws Exception{
        if(expected<0||expected==Long.MAX_VALUE||!InterfaceDefinition.parse(source).id().equals(id))throw new IllegalArgumentException("NATIVE_UI_DEFINITION");
        if(dimension==null||!dimension.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")||data.size()>InterfaceDefinition.MAX_NODES||JSON.writeValueAsBytes(data).length>65536)throw new IllegalArgumentException("NATIVE_UI_STATE");
        var next=new Saved(expected+1,dimension,source,data,visible);
        var result=repository.compareAndSet(scope.world,NS,key(scope,id),expected,JSON.writeValueAsString(next),System.currentTimeMillis());
        if(!result.accepted())throw new IllegalStateException("NATIVE_UI_STALE_REVISION");return next;
    }
    public Optional<Candidate> pending(Scope scope,String id)throws Exception{
        var row=repository.get(scope.world,CANDIDATES,key(scope,id));return row.isEmpty()?Optional.empty():Optional.of(JSON.readValue(row.get().payload(),Candidate.class));
    }
    public FailedDraft failed(Scope scope,String id,UUID token,long baseRevision,String source,String diagnostic)throws Exception{
        if(source==null||source.length()>65536||baseRevision<0)throw new IllegalArgumentException("NATIVE_UI_DRAFT_SIZE");
        String message=diagnostic==null?"":diagnostic.substring(0,Math.min(8192,diagnostic.length()));
        var draft=new FailedDraft(token,id,baseRevision,source,message,System.currentTimeMillis());
        String key=key(scope,id)+":"+token;var prior=repository.get(scope.world,"native_ui_failed_drafts",key);
        if(prior.isPresent())return JSON.readValue(prior.get().payload(),FailedDraft.class);
        if(!repository.compareAndSet(scope.world,"native_ui_failed_drafts",key,0,JSON.writeValueAsString(draft),draft.createdAt).accepted())throw new IllegalStateException("NATIVE_UI_DRAFT_CHANGED");return draft;
    }
    public Optional<FailedDraft> failed(Scope scope,String id,UUID token)throws Exception{var row=repository.get(scope.world,"native_ui_failed_drafts",key(scope,id)+":"+token);return row.isEmpty()?Optional.empty():Optional.of(JSON.readValue(row.get().payload(),FailedDraft.class));}
    public List<Map<String,Object>> failed(Scope scope,String id)throws Exception{
        var drafts=new ArrayList<FailedDraft>();String prefix=key(scope,id)+":";
        for(var row:repository.list(scope.world,"native_ui_failed_drafts"))if(row.recordId().startsWith(prefix))drafts.add(JSON.readValue(row.payload(),FailedDraft.class));
        return drafts.stream().sorted(Comparator.comparingLong(FailedDraft::createdAt).reversed()).limit(16).map(d->Map.<String,Object>of("candidate_id",d.token,"base_revision",d.baseRevision,"diagnostic",d.diagnostic,"createdAt",d.createdAt)).toList();
    }
    /** Durable intent precedes any client side effect. Unknown outcomes keep this record. */
    public Candidate stage(Scope scope,String id,long expected,String dimension,String source)throws Exception{
        if(get(scope,id).map(Saved::revision).orElse(0L)!=expected)throw new IllegalStateException("NATIVE_UI_STALE_REVISION");
        if(!InterfaceDefinition.parse(source).id().equals(id)||expected==Long.MAX_VALUE||expected<0||dimension==null||!dimension.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))throw new IllegalArgumentException("NATIVE_UI_CANDIDATE");
        var row=repository.getIncludingDeleted(scope.world,CANDIDATES,key(scope,id));if(row.isPresent()&&!row.get().deleted())throw new IllegalStateException("NATIVE_UI_RECONCILE_REQUIRED");
        var candidate=new Candidate(UUID.randomUUID(),expected,dimension,source);var result=repository.compareAndSet(scope.world,CANDIDATES,key(scope,id),row.map(v->v.revision()).orElse(0L),JSON.writeValueAsString(candidate),System.currentTimeMillis());
        if(!result.accepted())throw new IllegalStateException("NATIVE_UI_RECONCILE_REQUIRED");return candidate;
    }
    /** Only a matching observed activation may advance the durable head; never replays a client operation. */
    public Saved acknowledge(Scope scope,String id,UUID token,long revision,Map<String,JsonNode> data,boolean visible)throws Exception{
        var candidate=pending(scope,id).orElseThrow(()->new IllegalStateException("NATIVE_UI_NO_CANDIDATE"));
        if(!candidate.token.equals(token)||revision!=candidate.expectedRevision+1)throw new IllegalStateException("NATIVE_UI_CANDIDATE_MISMATCH");
        var current=get(scope,id).orElse(null);Saved saved;
        if(current!=null&&current.revision==revision){if(!current.source.equals(candidate.source)||!current.dimension.equals(candidate.dimension))throw new IllegalStateException("NATIVE_UI_CANDIDATE_MISMATCH");saved=current;}
        else saved=save(scope,id,candidate.expectedRevision,candidate.dimension,candidate.source,data,visible);
        discard(scope,id,token);return saved;
    }
    public void discard(Scope scope,String id,UUID token)throws Exception{
        var row=repository.get(scope.world,CANDIDATES,key(scope,id));if(row.isEmpty())return;
        if(!JSON.readValue(row.get().payload(),Candidate.class).token.equals(token))throw new IllegalStateException("NATIVE_UI_CANDIDATE_MISMATCH");
        if(!repository.delete(scope.world,CANDIDATES,key(scope,id),row.get().revision(),System.currentTimeMillis()).accepted())throw new IllegalStateException("NATIVE_UI_CANDIDATE_MISMATCH");
    }
    @Override public void close()throws Exception{repository.close();}
}
