package dev.mineagent.runtime.core.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mineagent.runtime.core.persistence.SqliteRuntimeRepository;
import java.nio.file.Path;
import java.util.*;

/** Directed, actually observed transitions. No reverse edge, coordinate scale or teleport is inferred. */
public final class KnownPortalRoutes implements AutoCloseable {
    public record Scope(UUID world,UUID owner,UUID agent){}
    public record Location(String dimension,SkillSpec.Point position){public Location{if(dimension==null||!dimension.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))throw new IllegalArgumentException("ROUTE_DIMENSION");Objects.requireNonNull(position);}}
    public record Link(String id,long revision,Location entry,Location exit,String portalBlock,UUID actor,long observedAt){}
    public record Leg(String action,Location from,Location to,String portalId,long portalRevision){}
    public record Plan(List<Leg> legs,double estimatedDistance,boolean navigationVerified){public Plan{legs=List.copyOf(legs);}}
    private static final String NS="observed_portal_routes_v1";private static final ObjectMapper JSON=new ObjectMapper();
    private final SqliteRuntimeRepository repository;
    public KnownPortalRoutes(Path path)throws Exception{repository=new SqliteRuntimeRepository(path);}
    private static String prefix(Scope scope){return scope.owner+":"+scope.agent+":";}
    public Link observe(Scope scope,Location entry,Location exit,String block,UUID actor,long at)throws Exception{
        if(entry.dimension.equals(exit.dimension)||actor==null||at<0||block==null||!block.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))throw new IllegalArgumentException("ROUTE_OBSERVATION");
        String id=UUID.nameUUIDFromBytes(JSON.writeValueAsBytes(entry)).toString(),key=prefix(scope)+id;
        var previous=repository.get(scope.world,NS,key);long revision=previous.map(v->v.revision()).orElse(0L);
        var old=previous.isEmpty()?null:JSON.readValue(previous.orElseThrow().payload(),Link.class);
        long topology=old==null?1:old.entry.equals(entry)&&old.exit.equals(exit)&&old.portalBlock.equals(block)?old.revision:old.revision+1;
        var link=new Link(id,topology,entry,exit,block,actor,at);
        if(!repository.compareAndSet(scope.world,NS,key,revision,JSON.writeValueAsString(link),at).accepted())throw new IllegalStateException("ROUTE_OBSERVATION_CHANGED");return link;
    }
    public List<Link> list(Scope scope)throws Exception{
        var out=new ArrayList<Link>();for(var row:repository.list(scope.world,NS))if(row.recordId().startsWith(prefix(scope)))out.add(JSON.readValue(row.payload(),Link.class));
        out.sort(Comparator.comparing(Link::id));return List.copyOf(out);
    }
    private static double distance(Location a,Location b){if(!a.dimension.equals(b.dimension))return Double.POSITIVE_INFINITY;var p=a.position;var q=b.position;return Math.sqrt(Math.pow(p.x()-q.x(),2)+Math.pow(p.y()-q.y(),2)+Math.pow(p.z()-q.z(),2));}
    /** Walking distances are estimates only. Each actual leg must still pass native loaded-terrain navigation. */
    public static Plan plan(Location origin,Location target,List<Link> observed){
        record Search(Location at,double cost,List<Leg> legs,Set<String> crossed){}
        var queue=new PriorityQueue<Search>(Comparator.comparingDouble(Search::cost));queue.add(new Search(origin,0,List.of(),Set.of()));
        var cheapest=new HashMap<String,Double>();Plan best=null;
        while(!queue.isEmpty()){
            var current=queue.remove();if(best!=null&&current.cost>=best.estimatedDistance)continue;
            if(current.at.dimension.equals(target.dimension)){var legs=new ArrayList<>(current.legs);legs.add(new Leg("WALK",current.at,target,"",0));double cost=current.cost+distance(current.at,target);if(best==null||cost<best.estimatedDistance)best=new Plan(legs,cost,false);}
            for(var link:observed){if(!current.at.dimension.equals(link.entry.dimension)||current.crossed.contains(link.id))continue;
                double cost=current.cost+distance(current.at,link.entry)+8;String key=link.id+"@"+link.revision;if(cost>=cheapest.getOrDefault(key,Double.POSITIVE_INFINITY))continue;cheapest.put(key,cost);
                var legs=new ArrayList<>(current.legs);legs.add(new Leg("WALK",current.at,link.entry,link.id,link.revision));legs.add(new Leg("NATIVE_PORTAL",link.entry,link.exit,link.id,link.revision));var crossed=new HashSet<>(current.crossed);crossed.add(link.id);queue.add(new Search(link.exit,cost,legs,Set.copyOf(crossed)));
            }
        }
        if(best==null)throw new IllegalStateException("NO_OBSERVED_PORTAL_ROUTE");return best;
    }
    @Override public void close()throws Exception{repository.close();}
}
