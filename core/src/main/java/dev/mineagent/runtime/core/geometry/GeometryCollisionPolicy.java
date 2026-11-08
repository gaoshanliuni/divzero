package dev.mineagent.runtime.core.geometry;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

/** Immutable plan policy. Broad exceptions never implicitly permit trapping a real player. */
public record GeometryCollisionPolicy(Mode mode,Set<String> types,Set<UUID> entities,List<Region> regions) {
    public enum Mode { SAFE, PLAYER_SAFE, CREATIVE, EXPLICIT }
    public record Region(double minX,double minY,double minZ,double maxX,double maxY,double maxZ) {
        public Region {for(double v:new double[]{minX,minY,minZ,maxX,maxY,maxZ})if(!Double.isFinite(v))throw new IllegalArgumentException("GEOMETRY_COLLISION_REGION");if(minX>maxX||minY>maxY||minZ>maxZ)throw new IllegalArgumentException("GEOMETRY_COLLISION_REGION");}
        boolean contains(double x,double y,double z){return x>=minX&&x<=maxX&&y>=minY&&y<=maxY&&z>=minZ&&z<=maxZ;}
    }
    public static final GeometryCollisionPolicy SAFE=new GeometryCollisionPolicy(Mode.SAFE,Set.of(),Set.of(),List.of());
    public GeometryCollisionPolicy {Objects.requireNonNull(mode);types=Set.copyOf(types);entities=Set.copyOf(entities);regions=List.copyOf(regions);}
    public boolean blocks(boolean realPlayer,String type,UUID id,double x,double y,double z){
        if(realPlayer)return mode!=Mode.EXPLICIT||!entities.contains(id);
        return switch(mode){case SAFE->true;case PLAYER_SAFE,CREATIVE->false;case EXPLICIT->!entities.contains(id)&&!types.contains(type)&&regions.stream().noneMatch(r->r.contains(x,y,z));};
    }
    public static GeometryCollisionPolicy parse(JsonNode value){
        if(value==null||value.isMissingNode())return SAFE;
        if(!value.isObject()||!Set.of("mode","allow_types","allow_entities","allow_regions").containsAll(value.properties().stream().map(Map.Entry::getKey).toList()))throw new IllegalArgumentException("GEOMETRY_COLLISION_FIELDS");
        Mode mode;try{mode=Mode.valueOf(value.path("mode").asText("SAFE"));}catch(IllegalArgumentException invalid){throw new IllegalArgumentException("GEOMETRY_COLLISION_MODE");}
        var types=new HashSet<String>();var ids=new HashSet<UUID>();var regions=new ArrayList<Region>();
        for(String field:List.of("allow_types","allow_entities","allow_regions"))if(value.has(field)&&!value.get(field).isArray())throw new IllegalArgumentException("GEOMETRY_COLLISION_"+field.toUpperCase(Locale.ROOT));
        for(var type:value.path("allow_types")){if(!type.isTextual()||!type.asText().matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))throw new IllegalArgumentException("GEOMETRY_COLLISION_TYPE");types.add(type.asText());}
        for(var id:value.path("allow_entities")){if(!id.isTextual())throw new IllegalArgumentException("GEOMETRY_COLLISION_ENTITY");ids.add(UUID.fromString(id.asText()));}
        for(var region:value.path("allow_regions")){if(!region.isArray()||region.size()!=6)throw new IllegalArgumentException("GEOMETRY_COLLISION_REGION");for(var n:region)if(!n.isNumber())throw new IllegalArgumentException("GEOMETRY_COLLISION_REGION");regions.add(new Region(region.get(0).asDouble(),region.get(1).asDouble(),region.get(2).asDouble(),region.get(3).asDouble(),region.get(4).asDouble(),region.get(5).asDouble()));}
        if(mode!=Mode.EXPLICIT&&(!types.isEmpty()||!ids.isEmpty()||!regions.isEmpty()))throw new IllegalArgumentException("GEOMETRY_COLLISION_EXPLICIT_REQUIRED");
        return new GeometryCollisionPolicy(mode,types,ids,regions);
    }
}
