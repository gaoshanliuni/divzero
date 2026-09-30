package dev.mineagent.runtime.worker.provider;

import dev.mineagent.runtime.core.conversation.*;
import dev.mineagent.runtime.scripting.opencode.OpenCodeRuntime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** The provider boundary receives only the task-selected schemas and loaded OpenCode-style Skill content. */
public final class ProgressiveDeclarations {
    private static final Map<String,String> GUIDES=new ConcurrentHashMap<>();
    private ProgressiveDeclarations(){}
    public static List<ToolDefinition> tools(Object requested){
        var names=strings(requested,CapabilityCatalog.RESIDENT);
        return CapabilityCatalog.selected(names).stream().map(t->new ToolDefinition(t.name(),t.description(),t.parameters())).toList();
    }
    private static List<String> strings(Object value,List<String> defaults){
        if(value==null)return defaults;if(!(value instanceof Collection<?> list))throw new IllegalArgumentException("CAPABILITY_SELECTION_FORMAT");
        var values=new LinkedHashSet<String>();for(Object name:list){if(!(name instanceof String text)||text.length()>64)throw new IllegalArgumentException("CAPABILITY_SELECTION_NAME");values.add(text);}
        return List.copyOf(values);
    }
    public static List<Map<String,Object>> messages(List<Map<String,Object>> base,Object loaded,String legacyPrompt){
        var result=new ArrayList<Map<String,Object>>();
        if(base.isEmpty()){result.add(Map.of("role","system","content",CapabilityCatalog.baseInstructions()));result.add(Map.of("role","user","content",legacyPrompt));}
        else result.addAll(base);
        int insert=0;while(insert<result.size()&&Set.of("system","developer").contains(result.get(insert).get("role")))insert++;
        for(String name:strings(loaded,List.of())){
            var group=CapabilityCatalog.require(name);
            String guide=GUIDES.computeIfAbsent(name,key->OpenCodeRuntime.skill(group.name(),group.guide(),"divzero:skills/"+group.name(),List.of()));
            result.add(insert++,Map.of("role","system","content","[已加载的应用操作说明；不授予新权限]\n"+guide));
        }
        return List.copyOf(result);
    }
}
