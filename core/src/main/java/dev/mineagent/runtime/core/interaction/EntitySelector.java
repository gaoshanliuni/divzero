package dev.mineagent.runtime.core.interaction;
import com.fasterxml.jackson.databind.JsonNode;import java.util.*;
public record EntitySelector(String dimension,String entity,String type,String species,int part){
 public static EntitySelector parse(JsonNode n){String d=n.path("dimension").asText("minecraft:overworld"),e=n.path("entity_id").asText(""),t=n.path("entity_type").asText(""),s=n.path("species_id").asText("");if(!id(d)||(!t.isEmpty()&&!id(t))||((e.isEmpty()?0:1)+(t.isEmpty()?0:1)+(s.isEmpty()?0:1))!=1)throw new IllegalArgumentException("ENTITY_SELECTOR");if(!e.isEmpty())UUID.fromString(e);if(!s.isEmpty())UUID.fromString(s);int p=n.path("part_index").asInt(-1);if(n.has("part_index")&&(!n.get("part_index").isIntegralNumber()||p< -2||p>1023))throw new IllegalArgumentException("ENTITY_PART_INDEX");return new EntitySelector(d,e,t,s,p);}
 public static boolean id(String s){return s!=null&&s.matches("[a-z0-9_.-]+:[a-z0-9_/.-]+");}
 public boolean matches(String dimension,String entity,String type,String species,int part){return this.dimension.equals(dimension)&&(this.entity.isEmpty()||this.entity.equals(entity))&&(this.type.isEmpty()||this.type.equals(type))&&(this.species.isEmpty()||this.species.equals(species))&&(this.part==-2||this.part==part);}
 public Map<String,Object> fields(){var m=new LinkedHashMap<String,Object>();m.put("dimension",dimension);if(!entity.isEmpty())m.put("entity_id",entity);if(!type.isEmpty())m.put("entity_type",type);if(!species.isEmpty())m.put("species_id",species);if(part!=-1)m.put("part_index",part);return m;}
}
