package dev.mineagent.runtime.scripting.studio;

import dev.mineagent.runtime.api.packages.CodeDraft;
import dev.mineagent.runtime.core.persistence.SqliteRuntimeRepository;
import dev.mineagent.runtime.core.packages.RuntimePackageCanonicalizer;
import dev.mineagent.runtime.core.packages.ScriptDependencyGraph;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.*;

/** Source-bound at-most-once top-level script execution, with explicit cleanup and no restart replay. */
public final class StudioScriptJournal {
    private static final String NS="studio_script_runs_v1";
    private static final Set<String> ACTIVE=Set.of("STARTING","START_RETURNED","PUBLISHED","STOPPING");
    private static final Set<String> STATES=Set.of("STARTING","START_RETURNED","PUBLISHED","REJECTED_BEFORE_START","OUTCOME_UNKNOWN","STOPPING","STOP_RETURNED","STOP_UNKNOWN","NOT_LOADED_THIS_PROCESS");
    public record Record(UUID id,UUID world,UUID owner,String ownerName,UUID draft,long draftRevision,UUID task,long taskRevision,UUID packageId,long packageRevision,String packageHash,String sourceHash,String state,String error,long revision,long updatedAt,boolean uncertain,boolean legacyStop,String source,int line,int column,@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) ScriptDependencyGraph dependencies){
        public Record(UUID id,UUID world,UUID owner,String ownerName,UUID draft,long draftRevision,UUID task,long taskRevision,UUID packageId,long packageRevision,String packageHash,String sourceHash,String state,String error,long revision,long updatedAt,boolean uncertain,boolean legacyStop,String source,int line,int column){this(id,world,owner,ownerName,draft,draftRevision,task,taskRevision,packageId,packageRevision,packageHash,sourceHash,state,error,revision,updatedAt,uncertain,legacyStop,source,line,column,null);}
        public Record{if(dependencies!=null&&(!dependencies.root().equals(packageId)||legacyStop))throw new IllegalArgumentException("STUDIO_SCRIPT_DEPENDENCY_RECORD");if(id==null||world==null||owner==null||draft==null||task==null||packageId==null||ownerName==null||ownerName.isBlank()||ownerName.length()>128||draftRevision<1||taskRevision<0||packageRevision<1||state==null||!STATES.contains(state)||sourceHash==null||!sourceHash.matches("[a-f0-9]{64}")||packageHash==null||!packageHash.matches("(?:[a-f0-9]{64})?")||error==null||!error.matches("[A-Z0-9_]{0,100}")||source==null||source.length()>256||line<0||column<0||revision<1)throw new IllegalArgumentException("STUDIO_SCRIPT_RECORD");uncertain=uncertain||Set.of("OUTCOME_UNKNOWN","STOP_UNKNOWN").contains(state);}}
    public record Prepared(Record record,boolean dispatch){}
    private final SqliteRuntimeRepository repo;private final UUID world;private final Clock clock;private final ObjectMapper json=new ObjectMapper();private final Map<UUID,Record> records=new LinkedHashMap<>();
    public StudioScriptJournal(SqliteRuntimeRepository repo,UUID world,Clock clock)throws Exception {this.repo=repo;this.world=world;this.clock=clock;for(var row:repo.list(world,NS)){var value=json.readValue(row.payload(),Record.class);if(!value.world().equals(world)||value.revision()!=row.revision()||!value.id().toString().equals(row.recordId()))throw new IllegalStateException("STUDIO_SCRIPT_RECORD");records.put(value.id(),value);}for(var value:List.copyOf(records.values()))if(ACTIVE.contains(value.state()))finish(value.id(),value.state().equals("STOPPING")?"STOP_UNKNOWN":"OUTCOME_UNKNOWN","STUDIO_SCRIPT_RESTART_UNKNOWN","",0,0);}
    public synchronized Record get(UUID id){return Optional.ofNullable(records.get(id)).orElseThrow(()->new IllegalStateException("STUDIO_SCRIPT_RECORD_MISSING"));}
    /** Read committed state after any uncertain CAS result; this never dispatches code or cleanup. */
    public synchronized Optional<Record> refresh(UUID id)throws Exception {
        var row=repo.get(world,NS,id.toString()).orElse(null);if(row==null){if(records.containsKey(id))throw new IllegalStateException("STUDIO_SCRIPT_RECORD_MISSING");return Optional.empty();}
        var value=json.readValue(row.payload(),Record.class);if(!value.id().equals(id)||!value.world().equals(world)||value.revision()!=row.revision())throw new IllegalStateException("STUDIO_SCRIPT_RECORD");records.put(id,value);return Optional.of(value);
    }
    public synchronized List<Record> forPackage(UUID owner,UUID pkg){return records.values().stream().filter(r->r.owner().equals(owner)&&r.packageId().equals(pkg)).sorted(Comparator.comparingLong(Record::updatedAt).reversed()).toList();}
    public synchronized Prepared begin(CodeDraft draft,String name,long packageRevision,String packageHash,boolean legacyStop)throws Exception {return begin(draft,name,packageRevision,packageHash,legacyStop,null);}
    public synchronized Prepared begin(CodeDraft draft,String name,long packageRevision,String packageHash,boolean legacyStop,ScriptDependencyGraph dependencies)throws Exception {
        if(dependencies==null?!draft.dependencies().isEmpty():!dependencies.root().equals(draft.packageId())||!dependencies.required().equals(draft.dependencies())||legacyStop)throw new IllegalStateException("STUDIO_SCRIPT_DEPENDENCY_CONTEXT");
        if(!draft.worldId().equals(world)||name==null||name.length()>128)throw new IllegalArgumentException("STUDIO_SCRIPT_CONTEXT");UUID id=UUID.nameUUIDFromBytes(((legacyStop?"legacy-script-stop|":"studio-script-run|")+world+"|"+draft.draftId()+"|"+draft.revision()+"|"+draft.taskRevision()).getBytes(java.nio.charset.StandardCharsets.UTF_8));String sourceHash=dev.mineagent.runtime.core.packages.CodeDraftSources.fingerprint(draft);
        var old=refresh(id).orElse(null);if(old!=null){if(!old.owner().equals(draft.ownerPlayerId())||!old.packageId().equals(draft.packageId())||!old.task().equals(draft.taskId())||old.packageRevision()!=packageRevision||!old.sourceHash().equals(sourceHash)||!old.packageHash().equals(packageHash)||old.legacyStop()!=legacyStop||!Objects.equals(old.dependencies(),dependencies))throw new IllegalStateException("STUDIO_SCRIPT_OPERATION_REUSED");return new Prepared(old,false);}
        if(records.size()>=4096)throw new IllegalStateException("STUDIO_SCRIPT_LEDGER_FULL");if(!legacyStop&&records.values().stream().anyMatch(r->r.packageId().equals(draft.packageId())&&(r.uncertain()||ACTIVE.contains(r.state()))))throw new IllegalStateException("STUDIO_SCRIPT_PRIOR_ACTIVE_OR_UNKNOWN");
        var value=new Record(id,world,draft.ownerPlayerId(),name,draft.draftId(),draft.revision(),draft.taskId(),draft.taskRevision(),draft.packageId(),packageRevision,packageHash,sourceHash,legacyStop?"STOPPING":"STARTING","",1,clock.millis(),false,legacyStop,"",0,0,dependencies);
        if(!repo.compareAndSet(world,NS,id.toString(),0,json.writeValueAsString(value),clock.millis()).accepted())throw new IllegalStateException("STUDIO_SCRIPT_CAS");records.put(id,value);return new Prepared(value,true);
    }
    public synchronized Record finish(UUID id,String state,String code,String source,int line,int column)throws Exception {
        var old=refresh(id).orElseThrow(()->new IllegalStateException("STUDIO_SCRIPT_RECORD_MISSING"));if(!STATES.contains(state)||!code.matches("[A-Z0-9_]{0,100}")||source==null||source.length()>256)throw new IllegalArgumentException("STUDIO_SCRIPT_OUTCOME");
        if(old.state().equals(state))return old;
        if(!ACTIVE.contains(old.state())&&!(state.equals("STOPPING")&&old.state().equals("OUTCOME_UNKNOWN")))return old;
        boolean valid=switch(state){case "START_RETURNED"->old.state().equals("STARTING");case "PUBLISHED"->old.state().equals("START_RETURNED");case "STOPPING"->!old.state().equals("STOPPING");case "STOP_RETURNED","STOP_UNKNOWN","NOT_LOADED_THIS_PROCESS"->old.state().equals("STOPPING");case "REJECTED_BEFORE_START"->old.state().equals("STARTING");case "OUTCOME_UNKNOWN"->true;default->false;};if(!valid)throw new IllegalStateException("STUDIO_SCRIPT_TRANSITION");
        var next=new Record(old.id(),world,old.owner(),old.ownerName(),old.draft(),old.draftRevision(),old.task(),old.taskRevision(),old.packageId(),old.packageRevision(),old.packageHash(),old.sourceHash(),state,code,old.revision()+1,clock.millis(),old.uncertain(),old.legacyStop(),source,line,column,old.dependencies());
        if(!repo.compareAndSet(world,NS,id.toString(),old.revision(),json.writeValueAsString(next),clock.millis()).accepted())throw new IllegalStateException("STUDIO_SCRIPT_CAS");records.put(id,next);return next;
    }
}
