package dev.mineagent.runtime.core.task;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
/** Data-only IDs/revision from model tool calls; actor identity is always supplied by the task, never JSON. */
public final class AppearanceToolArguments {
    private AppearanceToolArguments(){}
    public static Map<String,String> parse(JsonNode args,String revisionKey){
        var keys=new HashSet<String>();args.fieldNames().forEachRemaining(keys::add);
        if(!args.isObject()||!keys.equals(Set.of("model","texture","animation",revisionKey)))throw new IllegalArgumentException("APPEARANCE_ARGUMENTS");
        for(String field:List.of("model","texture","animation"))if(!args.path(field).isTextual()||!validId(args.path(field).textValue(),!field.equals("model")))throw new IllegalArgumentException("APPEARANCE_ID");
        var revision=args.path(revisionKey);if(!revision.isIntegralNumber()||!revision.canConvertToLong()||revision.longValue()<0)throw new IllegalArgumentException("APPEARANCE_REVISION");
        return Map.of("model",args.path("model").textValue(),"texture",args.path("texture").textValue(),"animation",args.path("animation").textValue(),revisionKey,Long.toString(revision.longValue()));
    }
    public static boolean validId(String value,boolean optional){return value!=null&&(optional&&value.isEmpty()||value.length()<=128&&value.matches("[A-Za-z0-9_.:-]+(?:/[A-Za-z0-9_.:-]+)*")&&Arrays.stream(value.split("/")).noneMatch(s->s.equals(".")||s.equals("..")));}
}
