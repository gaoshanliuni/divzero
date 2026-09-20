package dev.mineagent.runtime.neoforge.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mineagent.runtime.api.permission.PermissionAction;
import dev.mineagent.runtime.core.events.*;
import dev.mineagent.runtime.neoforge.MineAgentRuntimeServices;
import dev.mineagent.runtime.neoforge.scoreboard.ScoreboardReadWindow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.scores.*;
import java.util.*;
import java.util.function.*;

/** Bounded, read-only Native scores. Never creates an objective/holder, infers a modifier, or requests a model. */
public final class NativeScoreEventSource implements AutoCloseable {
    private record Resource(Objective objective,ScoreboardReadWindow.Guard guard){}
    private final MinecraftServer server;private final RuntimeEventStore store;private final Function<RuntimeEventStore.Subscription,String> authority,baseSecurity;
    private final ScoreEventSampler sampler=new ScoreEventSampler();private final UUID epoch=UUID.randomUUID();private final ObjectMapper json=new ObjectMapper();private volatile boolean closed;private int cursor;
    NativeScoreEventSource(MinecraftServer server,RuntimeEventStore store,Function<RuntimeEventStore.Subscription,String> authority,Function<RuntimeEventStore.Subscription,String> baseSecurity){this.server=server;this.store=store;this.authority=authority;this.baseSecurity=baseSecurity;}
    private void thread(){if(closed||!server.isSameThread())throw new IllegalStateException("SCORE_EVENT_THREAD");}
    public long permissionRevision(UUID owner){return MineAgentRuntimeServices.permissions(server).actionRevision(owner,PermissionAction.MANAGE_SCOREBOARD);}
    public boolean granted(UUID owner){return !closed&&MineAgentRuntimeServices.permissions(server).trustedActions(owner).contains(PermissionAction.MANAGE_SCOREBOARD);}
    public void requirePermission(UUID owner){thread();if(!granted(owner))throw new SecurityException("SCORE_EVENT_PERMISSION_REQUIRED");}
    private ScoreboardReadWindow window(){if(!(server.getScoreboard() instanceof ScoreboardReadWindow access))throw new IllegalStateException("SCORE_OBSERVER_UNAVAILABLE");return access;}
    private Resource resource(String name,String criteria){
        thread();var objective=server.getScoreboard().getObjective(ScoreEventFilter.name(name,16));if(objective==null)throw new IllegalStateException("SCORE_OBJECTIVE_MISSING");
        if(!objective.getCriteria().getName().equals(criteria))throw new IllegalStateException("SCORE_CRITERIA_CHANGED");return new Resource(objective,window().mineagent$objectiveGuard(objective));
    }
    public void require(UUID owner,ScoreEventFilter filter){requirePermission(owner);resource(filter.objective(),filter.criteria());}
    public String token(UUID owner,ScoreEventFilter filter){try{requirePermission(owner);var r=resource(filter.objective(),filter.criteria());return epoch+"|"+permissionRevision(owner)+"|"+filter.objective()+"|"+filter.criteria()+"|"+r.guard().generation();}catch(RuntimeException unavailable){return null;}}
    public boolean waiting(RuntimeEventStore.Subscription sub){return sub.request().score()!=null&&granted(sub.creator().scope().ownerId())&&sub.request().score().replacementPolicy().equals("REBASE")&&server.getScoreboard().getObjective(sub.request().score().objective())==null;}
    private String security(RuntimeEventStore.Subscription sub){var base=baseSecurity.apply(sub);return base==null||!granted(sub.creator().scope().ownerId())?null:base+"|score-permission|"+permissionRevision(sub.creator().scope().ownerId());}
    private Map<String,Integer> values(Objective objective,List<String> selected,int maximumEntries){
        var holders=selected.isEmpty()?window().mineagent$scoreHolderNames(4096):selected;var values=new TreeMap<String,Integer>();
        for(var holder:holders){var info=server.getScoreboard().getPlayerScoreInfo(ScoreHolder.forNameOnly(holder),objective);if(info==null)continue;ScoreEventFilter.name(holder,40);values.put(holder,info.value());if(values.size()>maximumEntries)throw new IllegalStateException("SCORE_ENTRY_BUDGET");}
        return Collections.unmodifiableMap(values);
    }
    private Map<String,Object> metadata(Objective objective){String name=ScoreEventFilter.name(objective.getName(),16),criteria=ScoreEventFilter.name(objective.getCriteria().getName(),128);String title=objective.getDisplayName().getString();int characters=title.codePointCount(0,title.length());
        return Map.of("objective",name,"criteria",criteria,"nativeReadOnly",objective.getCriteria().isReadOnly(),"displayName",characters<=160?title:title.substring(0,title.offsetByCodePoints(0,160)),"displayNameTruncated",characters>160,"incarnation",window().mineagent$objectiveGuard(objective).generation());
    }
    public Map<String,Object> inspect(UUID owner,String objectiveName,int offset)throws Exception{
        requirePermission(owner);long permission=permissionRevision(owner);var items=new ArrayList<Object>();int position=offset,total;var result=new LinkedHashMap<String,Object>();result.put("objective",objectiveName);result.put("offset",offset);result.put("items",items);
        if(objectiveName.isEmpty()){
            var nativeObjectives=server.getScoreboard().getObjectives();if(nativeObjectives.size()>16384)throw new IllegalStateException("SCORE_OBJECTIVE_CATALOG_BUDGET");var objectives=nativeObjectives.stream().sorted(Comparator.comparing(Objective::getName)).toList();total=objectives.size();
            while(position<total&&items.size()<16){items.add(metadata(objectives.get(position)));if(json.writeValueAsBytes(items).length>12000){items.removeLast();break;}position++;}result.put("kind","OBJECTIVES");
        }else{
            var nativeObjective=server.getScoreboard().getObjective(objectiveName);if(nativeObjective==null)throw new IllegalStateException("SCORE_OBJECTIVE_MISSING");result.put("target",metadata(nativeObjective));var rows=new ArrayList<>(values(nativeObjective,List.of(),4096).entrySet());total=rows.size();
            while(position<total&&items.size()<32){var row=rows.get(position);items.add(Map.of("holder",row.getKey(),"value",row.getValue()));position++;}result.put("kind","ENTRIES");
        }
        if(items.isEmpty()&&position<total)throw new IllegalStateException("SCORE_INSPECTION_BUDGET");result.put("nextOffset",position);result.put("more",position<total);result.put("readOnly",true);result.put("sourceEpoch",epoch);
        requirePermission(owner);if(permission!=permissionRevision(owner))throw new SecurityException("SCORE_EVENT_AUTHORITY_CHANGED");return result;
    }
    /** This is observation polling, not model polling. More subscriptions/backpressure increase each subscription's interval. */
    public void tick()throws Exception{
        thread();if(server.getTickCount()%10!=0)return;var active=store.subscriptionsForRuntime().stream().filter(s->s.state().equals("ACTIVE")&&s.request().score()!=null).sorted(Comparator.comparing(s->s.id().toString())).toList();
        sampler.retain(active.stream().map(RuntimeEventStore.Subscription::id).collect(java.util.stream.Collectors.toSet()));if(active.isEmpty()){cursor=0;return;}
        int start=Math.floorMod(cursor,active.size()),count=Math.min(4,active.size());cursor=(start+count)%active.size();
        for(int i=0;i<count;i++){var sub=active.get((start+i)%active.size());String security=security(sub);
            if(security==null||System.currentTimeMillis()>=sub.expiresAt()){store.pauseScoreSource(sub.id(),sub.revision(),security==null?"SCORE_EVENT_AUTHORITY_CHANGED":"SCORE_EVENT_EXPIRED");sampler.forget(sub.id());continue;}
            if(waiting(sub)||store.waitingForSourceForRuntime(sub)){sampler.forget(sub.id());continue;}
            try{var filter=sub.request().score();var resource=resource(filter.objective(),filter.criteria());String grant=authority.apply(sub);if(grant==null)throw new SecurityException("SCORE_EVENT_AUTHORITY_CHANGED");
                if(sampler.needsObservation(sub)){var values=values(resource.objective(),filter.holders(),256);if(!resource.guard().current().getAsBoolean()||!grant.equals(authority.apply(sub))||!security.equals(security(sub)))throw new SecurityException("SCORE_EVENT_AUTHORITY_CHANGED");sampler.observe(sub,security,grant,epoch,resource.guard().generation(),values,System.currentTimeMillis());}
                sampler.drain(sub,security,grant,resource.guard().generation(),8,event->{var current=store.subscriptionForRuntime(sub.id());if(current==null||!current.state().equals("ACTIVE")||current.revision()!=sub.revision()||!matches(current,event))throw new SecurityException("SCORE_EVENT_AUTHORITY_CHANGED");store.ingest(event);});
            }catch(Exception failure){var sampled=sampler.status(sub);if(!(failure instanceof SecurityException)&&sampled.failures()>0&&sampled.failures()<3)continue;
                String message=failure.getMessage()==null?"":failure.getMessage();String code=failure instanceof SecurityException?"SCORE_EVENT_AUTHORITY_CHANGED":sampled.failures()>=3?"SCORE_EVENT_COMMIT_FAILED":Set.of("SCORE_OBJECTIVE_MISSING","SCORE_OBJECTIVE_REPLACED","SCORE_CRITERIA_CHANGED","SCORE_HOLDER_SCAN_BUDGET","SCORE_ENTRY_BUDGET").contains(message)?message:"SCORE_EVENT_SOURCE_UNAVAILABLE";
                store.pauseScoreSource(sub.id(),sub.revision(),code);sampler.forget(sub.id());}
        }
    }
    public boolean matches(RuntimeEventStore.Subscription sub,RuntimeEventStore.Event event){if(!ScoreEventFilter.matches(sub,event))return false;String current=authority.apply(sub);return current!=null&&event.sourceEpoch().equals(epoch)&&ScoreEventSampler.hash(current).equals(event.score().authorityHash());}
    public BooleanSupplier permit(UUID owner,ScoreEventFilter filter){try{requirePermission(owner);var guard=resource(filter.objective(),filter.criteria()).guard();long permission=permissionRevision(owner);return ()->!closed&&granted(owner)&&permissionRevision(owner)==permission&&guard.current().getAsBoolean();}catch(RuntimeException invalid){return ()->false;}}
    public BooleanSupplier inspectionPermit(UUID owner,com.fasterxml.jackson.databind.JsonNode observation){
        try{requirePermission(owner);if(!epoch.toString().equals(observation.path("sourceEpoch").asText()))return ()->false;long permission=permissionRevision(owner);var guards=new ArrayList<ScoreboardReadWindow.Guard>();
            var targets=observation.path("kind").asText().equals("OBJECTIVES")?observation.path("items"):json.createArrayNode().add(observation.path("target"));if(!targets.isArray()||targets.size()>16)return ()->false;
            for(var target:targets){var r=resource(target.path("objective").asText(),target.path("criteria").asText());if(!target.path("incarnation").isIntegralNumber()||target.path("incarnation").longValue()!=r.guard().generation())return ()->false;guards.add(r.guard());}
            return ()->!closed&&granted(owner)&&permissionRevision(owner)==permission&&guards.stream().allMatch(g->g.current().getAsBoolean());
        }catch(RuntimeException invalid){return ()->false;}
    }
    public Map<String,Object> state(RuntimeEventStore.Subscription sub){if(!granted(sub.creator().scope().ownerId()))return Map.of("phase","READ_PERMISSION_REQUIRED","observationRedacted",true);String phase=!sub.state().equals("ACTIVE")?sub.state():System.currentTimeMillis()>=sub.expiresAt()?"EXPIRED":waiting(sub)?"WAITING_OBJECTIVE_REBASE":token(sub.creator().scope().ownerId(),sub.request().score())==null?"SOURCE_UNAVAILABLE":sampler.status(sub).phase();
        return Map.of("phase",phase,"sampling",sampler.status(sub),"pollEveryServerTicks",10,"subscriptionsPerPoll",4,"eventsPerSubscriptionPoll",8,"maximumTrackedEntries",256,"maximumPendingEvents",512,"modifierKnown",false);
    }
    public Map<String,Object> forModel(RuntimeEventStore.Subscription sub,RuntimeEventStore.Event event){if(!granted(sub.creator().scope().ownerId())||token(sub.creator().scope().ownerId(),sub.request().score())==null)return Map.of("eventId",event.id(),"source",event.source(),"observationRedacted",true,"reason","CURRENT_SCORE_AUTHORITY_REQUIRED");
        var change=event.score();var result=new LinkedHashMap<String,Object>();result.put("eventId",event.id());result.put("source",event.source());result.put("objective",change.filter().objective());result.put("criteria",change.filter().criteria());result.put("holder",change.holder());result.put("before",change.before());result.put("after",change.after());result.put("observedBefore",change.observedBefore());result.put("observedAfter",change.observedAfter());result.put("incarnation",change.incarnation());result.put("sourceEpoch",event.sourceEpoch());result.put("modifierKnown",false);result.put("sampled",true);return result;
    }
    @Override public void close(){closed=true;sampler.clear();}
}
