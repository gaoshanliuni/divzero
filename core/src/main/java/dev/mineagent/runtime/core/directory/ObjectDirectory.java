package dev.mineagent.runtime.core.directory;

import dev.mineagent.runtime.api.directory.ObjectRef;
import java.util.*;
import java.util.function.LongSupplier;

/** Bounded live directory with scoped, short-lived membership snapshots. Protocol operations use the caller's durable journal. */
public final class ObjectDirectory {
    public record Scope(UUID worldId,UUID agentId,UUID ownerId,UUID taskId,long intentRevision,long authorityRevision){
        public Scope{Objects.requireNonNull(worldId);Objects.requireNonNull(agentId);Objects.requireNonNull(ownerId);Objects.requireNonNull(taskId);if(intentRevision<1||authorityRevision<0)throw new IllegalArgumentException("DIRECTORY_SCOPE");}
    }
    public record Entry(ObjectRef ref,String name,String dimension,ObjectQuery.Point position,String team,boolean online){
        public Entry{Objects.requireNonNull(ref);Objects.requireNonNull(name);Objects.requireNonNull(dimension);Objects.requireNonNull(team);if(name.length()>256||dimension.length()>128||team.length()>128||position!=null&&!position.dimension().equals(dimension))throw new IllegalArgumentException("DIRECTORY_ENTRY");}
    }
    public record Page(UUID operationId,Scope scope,long snapshotAt,long observedAt,List<Entry> items,String nextCursor,int skippedStale,boolean discoveryOnly){public Page{items=List.copyOf(items);}}
    public record Validation(ObjectRef requested,String status,Entry entry){}
    public interface Port {
        void authorize(Scope scope);
        Iterable<Entry> scan(Scope scope,ObjectRef.Kind kind);
        Optional<Entry> current(Scope scope,ObjectRef ref);
        boolean visible(Scope scope,Entry entry);
        ObjectQuery.Point actor(Scope scope);
    }
    private record Snapshot(Scope scope,ObjectQuery query,ObjectQuery.Point origin,long created,long expires,List<ObjectRef> refs){}
    private final Port port;private final LongSupplier clock;private final Map<UUID,Snapshot> snapshots=new LinkedHashMap<>();
    public ObjectDirectory(Port port,LongSupplier clock){this.port=Objects.requireNonNull(port);this.clock=Objects.requireNonNull(clock);}
    public void authorize(Scope scope){port.authorize(scope);}
    public List<Entry> selectCurrent(Scope scope,ObjectQuery query,int maximum){
        port.authorize(scope);if(!query.cursor().isEmpty())throw new IllegalArgumentException("DIRECTORY_SELECTOR_CURSOR");
        var values=scan(scope,query,origin(scope,query),maximum);port.authorize(scope);return List.copyOf(values);
    }
    public Optional<Entry> lookup(Scope scope,ObjectRef.Kind kind,String id){
        port.authorize(scope);var request=new ObjectRef(scope.worldId(),kind,id,new UUID(0,0),0);var entry=port.current(scope,request).orElse(null);
        if(entry==null||!port.visible(scope,entry))return Optional.empty();
        if(!entry.ref().worldId().equals(scope.worldId())||entry.ref().kind()!=kind||!entry.ref().id().equals(request.id()))throw new IllegalStateException("DIRECTORY_SOURCE_SCOPE");
        port.authorize(scope);return Optional.of(entry);
    }
    private List<Entry> scan(Scope scope,ObjectQuery query,ObjectQuery.Point origin,int maximum){
        if(maximum<1||maximum>256)throw new IllegalArgumentException("DIRECTORY_RESULT_BUDGET");var items=new ArrayList<Entry>();var seen=new HashSet<String>();int scanned=0;
        for(var e:port.scan(scope,query.kind())){
            if(++scanned>4096)throw new IllegalStateException("DIRECTORY_SCAN_BUDGET");if(!port.visible(scope,e))continue;
            if(!e.ref().worldId().equals(scope.worldId())||e.ref().kind()!=query.kind())throw new IllegalStateException("DIRECTORY_SOURCE_SCOPE");
            if(!seen.add(e.ref().id()))throw new IllegalStateException("DIRECTORY_DUPLICATE_ID");
            if(matches(query,e,origin)){items.add(e);if(items.size()>maximum)throw new IllegalStateException("DIRECTORY_RESULT_BUDGET");}
        }
        items.sort(Comparator.comparing(Entry::name).thenComparing(e->e.ref().id()));return items;
    }
    public Page query(Scope scope,UUID operation,ObjectQuery query){
        Objects.requireNonNull(operation);port.authorize(scope);long now=clock.getAsLong();snapshots.entrySet().removeIf(e->e.getValue().expires()<=now);
        UUID snapshotId;Snapshot snapshot;int offset=0;var base=query.withCursor("");
        if(query.cursor().isEmpty()){
            if(snapshots.size()>=64)throw new IllegalStateException("DIRECTORY_SNAPSHOT_BUDGET");
            var origin=origin(scope,query);var items=scan(scope,query,origin,256);snapshotId=UUID.randomUUID();
            snapshot=new Snapshot(scope,base,origin,now,Math.addExact(now,60000),items.stream().map(Entry::ref).toList());snapshots.put(snapshotId,snapshot);
        }else{
            var cursor=query.cursor().split(":",-1);if(cursor.length!=2)throw new IllegalArgumentException("DIRECTORY_CURSOR");
            try{snapshotId=UUID.fromString(cursor[0]);offset=Integer.parseInt(cursor[1]);}catch(RuntimeException invalid){throw new IllegalArgumentException("DIRECTORY_CURSOR",invalid);}
            snapshot=snapshots.get(snapshotId);if(snapshot==null)throw new IllegalStateException("DIRECTORY_CURSOR_EXPIRED");
            if(!snapshot.scope().equals(scope)||!snapshot.query().equals(base)||offset<0||offset>snapshot.refs().size())throw new IllegalArgumentException("DIRECTORY_CURSOR_SCOPE");
        }
        var values=new ArrayList<Entry>();int end=Math.min(snapshot.refs().size(),offset+query.limit()),skipped=0;
        for(int i=offset;i<end;i++){var ref=snapshot.refs().get(i);var current=port.current(scope,ref).orElse(null);if(current!=null&&port.visible(scope,current)&&current.ref().equals(ref)&&matches(base,current,snapshot.origin()))values.add(current);else skipped++;}
        port.authorize(scope);String next=end<snapshot.refs().size()?snapshotId+":"+end:"";
        return new Page(operation,scope,snapshot.created(),now,values,next,skipped,true);
    }
    public Validation revalidate(Scope scope,ObjectRef ref,ObjectQuery query){
        port.authorize(scope);if(!ref.worldId().equals(scope.worldId())||ref.kind()!=query.kind())return new Validation(ref,"REFERENCE_SCOPE_MISMATCH",null);
        var current=port.current(scope,ref).orElse(null);
        if(current==null||!port.visible(scope,current))return new Validation(ref,"UNAVAILABLE_IN_AUTHORIZED_LOADED_DIRECTORY",null);
        if(!current.ref().worldId().equals(ref.worldId())||current.ref().kind()!=ref.kind()||!current.ref().id().equals(ref.id()))return new Validation(ref,"REFERENCE_SCOPE_MISMATCH",null);
        if(!current.ref().generation().equals(ref.generation()))return new Validation(ref,"STALE_GENERATION",null);
        if(current.ref().revision()!=ref.revision())return new Validation(ref,"OBJECT_CHANGED",null);
        if(!matches(query,current,origin(scope,query)))return new Validation(ref,"FILTER_CHANGED",null);
        port.authorize(scope);return new Validation(ref,"CURRENT",current);
    }
    private ObjectQuery.Point origin(Scope scope,ObjectQuery q){if(q.near()==null)return null;var p=q.near().reference().equals("ACTOR")?port.actor(scope):q.near().position();if(p==null)throw new IllegalStateException("DIRECTORY_ACTOR_UNAVAILABLE");return p;}
    /** Pure filtering only; callers must establish visibility and authority before using a captured Native event. */
    public static boolean matches(ObjectQuery q,Entry e,ObjectQuery.Point origin){
        if(!e.online()||!q.ids().isEmpty()&&!q.ids().contains(e.ref().id())||!q.team().isEmpty()&&!q.team().equals(e.team())||!q.dimension().isEmpty()&&!q.dimension().equals(e.dimension()))return false;
        if(!q.name().isEmpty()){String value=e.name().toLowerCase(Locale.ROOT),name=q.name().toLowerCase(Locale.ROOT);if(q.match().equals("EXACT")?!value.equals(name):!value.startsWith(name))return false;}
        var p=e.position();if(q.near()!=null){if(p==null||!p.dimension().equals(origin.dimension()))return false;double dx=p.x()-origin.x(),dy=p.y()-origin.y(),dz=p.z()-origin.z();if(dx*dx+dy*dy+dz*dz>q.near().radius()*q.near().radius())return false;}
        var r=q.region();return r==null||p!=null&&p.x()>=r.minX()&&p.x()<=r.maxX()&&p.y()>=r.minY()&&p.y()<=r.maxY()&&p.z()>=r.minZ()&&p.z()<=r.maxZ();
    }
    public void clear(){snapshots.clear();}
}
