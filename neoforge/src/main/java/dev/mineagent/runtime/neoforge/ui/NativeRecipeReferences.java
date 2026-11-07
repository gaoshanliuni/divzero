package dev.mineagent.runtime.neoforge.ui;

import net.minecraft.server.MinecraftServer;
import java.util.*;

/** Bounded references for recipes actually exposed to a viewer; the live registry is rechecked on preview. */
final class NativeRecipeReferences {
    private static final Map<MinecraftServer,LinkedHashMap<String,String>> SCOPES=new WeakHashMap<>();
    private NativeRecipeReferences(){}
    static synchronized String reference(MinecraftServer server,String key){
        var values=SCOPES.computeIfAbsent(server,ignored->new LinkedHashMap<>(16,.75f,true));
        String previous=null;for(var entry:values.entrySet())if(entry.getValue().equals(key)){previous=entry.getKey();break;}
        if(previous!=null){values.get(previous);return previous;}
        String id=UUID.randomUUID().toString();values.put(id,key);while(values.size()>4096)values.remove(values.keySet().iterator().next());return id;
    }
    static synchronized String resolve(MinecraftServer server,String id){
        var values=SCOPES.get(server);String key=values==null?null:values.get(id);
        if(key==null)throw new IllegalArgumentException("PREVIEW_RECIPE_REFERENCE_EXPIRED: read inspect_brewing_recipes again");return key;
    }
}
