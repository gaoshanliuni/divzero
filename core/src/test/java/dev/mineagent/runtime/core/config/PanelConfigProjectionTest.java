package dev.mineagent.runtime.core.config;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PanelConfigProjectionTest {
    @Test void thousandsOfSavedBodyPreferencesDoNotGrowTheGlobalWireSnapshot(){
        var config=new LinkedHashMap<String,String>();config.put("provider.openai.model","example-model");config.put("autonomy.boost.allowed","true");
        for(int i=0;i<2000;i++)for(String field:List.of("learning","boost","neural","revision"))config.put("enhancements.world."+i+"."+field,"false");
        var projected=PanelConfigProjection.global(config);
        assertEquals(Map.of("provider.openai.model","example-model","autonomy.boost.allowed","true"),projected);
        assertEquals(8002,config.size()); // Projection must not remove the saved preferences.
    }
}
