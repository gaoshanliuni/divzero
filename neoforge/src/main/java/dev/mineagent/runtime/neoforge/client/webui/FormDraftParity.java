package dev.mineagent.runtime.neoforge.client.webui;
import com.google.gson.*;
import java.util.*;

/** Stable form keys, not DOM order: layout changes/additional fields must not discard an existing draft. */
public final class FormDraftParity {
    private FormDraftParity(){}
    public static boolean matches(JsonObject expected,JsonObject actual){
        try{var a=controls(expected);var b=controls(actual);return a.entrySet().stream().allMatch(e->e.getValue().equals(b.get(e.getKey())));}
        catch(RuntimeException invalid){return false;}
    }
    private static Map<String,JsonObject> controls(JsonObject draft){
        if(!"DRAFT_CAPTURED".equals(draft.get("status").getAsString()))throw new IllegalArgumentException("DRAFT_STATUS");
        var result=new HashMap<String,JsonObject>();
        for(var e:draft.getAsJsonArray("controls")){
            var c=e.getAsJsonObject().deepCopy();String key=c.get("attribute").getAsString()+"\0"+c.get("locator").getAsString();
            var selected=new ArrayList<String>();c.getAsJsonArray("selected").forEach(v->selected.add(v.getAsString()));Collections.sort(selected);var sorted=new JsonArray();selected.forEach(sorted::add);c.add("selected",sorted);
            if("select-multiple".equals(c.get("type").getAsString()))c.remove("value");
            if(result.putIfAbsent(key,c)!=null)throw new IllegalArgumentException("DRAFT_AMBIGUOUS_TARGET");
        }
        return result;
    }
}
