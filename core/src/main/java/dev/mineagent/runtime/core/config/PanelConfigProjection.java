package dev.mineagent.runtime.core.config;

import java.util.LinkedHashMap;
import java.util.Map;

/** Global settings exclude growing per-actor data, which is fetched by its authorized detail endpoint. */
public final class PanelConfigProjection {
    public static Map<String,String> global(Map<String,String> values){
        var result=new LinkedHashMap<>(values);
        result.keySet().removeIf(key->key.startsWith("chat.")||key.startsWith("ai.thinking.")
                ||key.startsWith("interaction.rules.")||key.startsWith("enhancements.")||key.startsWith("behavior.region."));
        return result;
    }
    private PanelConfigProjection(){}
}
