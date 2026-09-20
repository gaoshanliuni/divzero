package dev.mineagent.runtime.neoforge.task;

import java.util.*;

/** Acceptance fixture only: resolve scene roles from observed carriers, not generated names. */
final class WorldObjectSmokeRoles {
    record Part(UUID entityId,String partKey,boolean dynamic,double localX){}
    private WorldObjectSmokeRoles(){}
    static Map<String,Part> resolve(List<Part> parts){
        if(parts==null||parts.size()!=3||parts.stream().anyMatch(p->p==null||p.entityId()==null||p.partKey()==null||p.partKey().isBlank()||!Double.isFinite(p.localX()))
                ||parts.stream().map(Part::entityId).distinct().count()!=3||parts.stream().map(Part::partKey).distinct().count()!=3)throw new IllegalArgumentException("GENERATED_OBJECT_ROLE_AMBIGUOUS");
        var bases=parts.stream().filter(p->!p.dynamic()).toList();
        var pendants=parts.stream().filter(Part::dynamic).sorted(Comparator.comparingDouble(Part::localX)).toList();
        if(bases.size()!=1||pendants.size()!=2||pendants.getFirst().localX()>=bases.getFirst().localX()||pendants.getLast().localX()<=bases.getFirst().localX())throw new IllegalArgumentException("GENERATED_OBJECT_ROLE_AMBIGUOUS");
        return Map.of("base",bases.getFirst(),"left",pendants.getFirst(),"right",pendants.getLast());
    }
}
