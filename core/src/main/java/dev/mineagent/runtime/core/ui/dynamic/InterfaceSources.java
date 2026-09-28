package dev.mineagent.runtime.core.ui.dynamic;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

/** Server-owned live data. A declaration never grants world writes or another player's identity. */
public final class InterfaceSources {
    public record Source(String kind,String objective,String holder,String taskId,String field,List<Integer> relative,int radius,long offset){
        public Source{relative=List.copyOf(relative);}
        public Source(String kind,String objective,String holder,String taskId,String field){this(kind,objective,holder,taskId,field,List.of(0,0,0),0,0);}
    }
    public static Map<String,Source> parse(JsonNode root){
        if(root.isMissingNode())return Map.of();if(!root.isObject()||root.size()>64)throw bad("OBJECT");var result=new LinkedHashMap<String,Source>();int blockBudget=0;
        for(var entry:root.properties()){
            if(!entry.getKey().matches("[A-Za-z][A-Za-z0-9_-]{0,95}"))throw bad("KEY");var n=entry.getValue();String kind=n.path("kind").asText();if(!n.isObject())throw bad("OBJECT");
            var fields=switch(kind){case "score"->Set.of("kind","objective","holder");case "agent","player","world","menu"->Set.of("kind","field");case "block"->Set.of("kind","field","offset");case "blocks"->Set.of("kind","radius","offset");case "task"->Set.of("kind","task_id","field");default->throw bad("KIND");};
            for(var field:n.properties())if(!fields.contains(field.getKey()))throw bad("FIELD");
            String objective="",holder="",task="",field="";var relative=List.of(0,0,0);int radius=0;long offset=0;
            switch(kind){
                case "score"->{objective=text(n,"objective",128);holder=text(n,"holder",256);}
                case "agent"->{field=text(n,"field",24);if(!Set.of("health","max_health","food","name").contains(field))throw bad("AGENT_FIELD");}
                case "player"->{field=text(n,"field",24);if(!Set.of("health","max_health","food","name","x","y","z","yaw","pitch","experience").contains(field))throw bad("PLAYER_FIELD");}
                case "menu"->{field=text(n,"field",40);if(!Set.of("type","furnace_remaining_seconds","furnace_progress","furnace_lit_seconds","furnace_lit","brewing_seconds","brewing_fuel").contains(field))throw bad("MENU_FIELD");}
                case "world"->{field=text(n,"field",24);if(!Set.of("game_time","dimension","raining","thundering").contains(field))throw bad("WORLD_FIELD");}
                case "block"->{field=text(n,"field",24);if(!Set.of("id","state","solid","fluid","position","loaded").contains(field))throw bad("BLOCK_FIELD");if(n.has("offset")){var a=n.get("offset");if(!a.isArray()||a.size()!=3)throw bad("BLOCK_OFFSET");var values=new ArrayList<Integer>();for(var value:a){if(!value.isIntegralNumber()||!value.canConvertToInt()||Math.abs((long)value.intValue())>2047)throw bad("BLOCK_OFFSET");values.add(value.intValue());}relative=List.copyOf(values);}blockBudget++;}
                case "blocks"->{radius=(int)number(n,"radius",1,2047);offset=n.has("offset")?number(n,"offset",0,Long.MAX_VALUE):0;long edge=radius*2L+1,volume=edge*edge*edge;if(offset>=volume)throw bad("BLOCK_CURSOR");blockBudget+=Math.toIntExact(Math.min(4096,volume-offset));}
                case "task"->{field=text(n,"field",24);task=UUID.fromString(text(n,"task_id",36)).toString();if(!Set.of("status","title","revision","completed_steps","total_steps").contains(field))throw bad("TASK_FIELD");}
                default->throw bad("KIND");
            }
            if(blockBudget>4096)throw bad("BLOCK_READ_BUDGET");result.put(entry.getKey(),new Source(kind,objective,holder,task,field,relative,radius,offset));
        }return Map.copyOf(result);
    }
    public static int blockReadCost(InterfaceDefinition definition){int cost=0;for(var source:definition.sources().values())if(source.kind().equals("blocks")){long edge=source.radius()*2L+1;cost+=Math.toIntExact(Math.min(4096,edge*edge*edge-source.offset()));}else if(source.kind().equals("block"))cost++;return cost;}
    private static long number(JsonNode n,String key,long min,long max){var v=n.path(key);if(!v.isIntegralNumber()||!v.canConvertToLong()||v.longValue()<min||v.longValue()>max)throw bad("VALUE");return v.longValue();}
    private static String text(JsonNode n,String key,int max){var v=n.path(key);if(!v.isTextual()||v.asText().isBlank()||v.asText().length()>max||v.asText().codePoints().anyMatch(Character::isISOControl))throw bad("VALUE");return v.asText();}
    private static IllegalArgumentException bad(String code){return new IllegalArgumentException("INTERFACE_SOURCE_"+code);}
    private InterfaceSources(){}
}
