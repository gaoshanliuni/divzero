package dev.mineagent.runtime.core.ui;
import com.fasterxml.jackson.databind.JsonNode;
import dev.mineagent.runtime.core.task.SkillSpec;
import java.util.*;

/** Persist the concrete selected box as well as the controls that produced it. */
public record BehaviorRegionSelection(String actor,String source,int radius,String dimension,SkillSpec.Area area,long revision) {
    public static BehaviorRegionSelection parse(JsonNode n){
        for(var field:n.properties())if(!Set.of("actor","source","radius","dimension","min","max","revision").contains(field.getKey()))throw new IllegalArgumentException("REGION_FIELD");
        String actor=n.path("actor").asText(),source=n.path("source").asText(),dimension=n.path("dimension").asText();
        if(!Set.of("ai","player").contains(actor)||!Set.of("CURRENT","LOOK","PREVIOUS","COMBAT").contains(source)||!dimension.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")||!n.path("radius").isIntegralNumber()||n.path("radius").asInt()<1||n.path("radius").asInt()>1024||!n.path("revision").isIntegralNumber()||n.path("revision").asLong()<0)throw new IllegalArgumentException("REGION_ARGUMENTS");
        return new BehaviorRegionSelection(actor,source,n.get("radius").asInt(),dimension,new SkillSpec.Area(SkillSpec.point(n.path("min")),SkillSpec.point(n.path("max"))),n.get("revision").asLong());
    }
    public Map<String,Object> values(long revision){return Map.of("actor",actor,"source",source,"radius",radius,"dimension",dimension,"min",List.of(area.min().x(),area.min().y(),area.min().z()),"max",List.of(area.max().x(),area.max().y(),area.max().z()),"revision",revision);}
}
