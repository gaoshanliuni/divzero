package dev.mineagent.runtime.core.decision;
import com.fasterxml.jackson.databind.*;
import dev.mineagent.runtime.api.decision.*;
import java.util.*;
/** Model-authored ordinary questions are rendered by the trusted DecisionService frontend, never generated permission UI. */
public record TaskDecisionSpec(String title,String question,List<DecisionOption> options,SelectionMode mode,int min,int max) {
    public TaskDecisionSpec{options=List.copyOf(options);}
    public static Set<String> blockedNodes(dev.mineagent.runtime.api.task.ManagedTask task){
        var nodes=task.runnableStepIds().stream().filter(s->Set.of("plan","replan","execute").contains(s)).collect(java.util.stream.Collectors.toSet());
        if(nodes.isEmpty())throw new IllegalStateException("TASK_NOT_PLANNING");return Set.copyOf(nodes);
    }
    public static TaskDecisionSpec parse(String source){
        try{
            if(source==null||source.length()>16384)throw new IllegalArgumentException();
            var args=new ObjectMapper(com.fasterxml.jackson.core.JsonFactory.builder().enable(com.fasterxml.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION).build()).readTree(source);
            exact(args,Set.of("title","question","options","selection_mode","min_selections","max_selections"));
            String title=text(args,"title",128),question=text(args,"question",4096);var options=new ArrayList<DecisionOption>();
            if(!args.path("options").isArray()||args.path("options").size()>8)throw new IllegalArgumentException();
            for(var item:args.path("options")){exact(item,Set.of("id","title","description"));String id=text(item,"id",80);if(!id.matches("[A-Za-z0-9_.:-]{1,80}"))throw new IllegalArgumentException();options.add(new DecisionOption(id,text(item,"title",128),text(item,"description",1024)));}
            for(String key:List.of("min_selections","max_selections"))if(!args.path(key).isIntegralNumber()||!args.path(key).canConvertToInt())throw new IllegalArgumentException();
            var spec=new TaskDecisionSpec(title,question,options,SelectionMode.valueOf(text(args,"selection_mode",16)),args.path("min_selections").intValue(),args.path("max_selections").intValue());
            spec.request(new UUID(0,0),new UUID(0,0),0);return spec;
        }catch(Exception invalid){throw new IllegalArgumentException("TASK_DECISION_ARGUMENTS",invalid);}
    }
    public DecisionRequest request(UUID id,UUID viewer,long revision){return new DecisionRequest(id,1,viewer,revision,DecisionKind.DESIGN,title,question,options,mode,min,max,true,DecisionStatus.OPEN);}
    private static void exact(JsonNode node,Set<String> wanted){var keys=new HashSet<String>();node.fieldNames().forEachRemaining(keys::add);if(!node.isObject()||!keys.equals(wanted))throw new IllegalArgumentException();}
    private static String text(JsonNode node,String key,int limit){var value=node.path(key);if(!value.isTextual()||value.textValue().isBlank()||value.textValue().length()>limit)throw new IllegalArgumentException();return value.textValue();}
}
