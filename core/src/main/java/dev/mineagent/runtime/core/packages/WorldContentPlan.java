package dev.mineagent.runtime.core.packages;
import dev.mineagent.runtime.api.packages.*;
import dev.mineagent.runtime.core.content.ContentAddressedStore;
import java.nio.charset.StandardCharsets;
import java.util.*;
/** Resolves existing signed package resources; never manufactures gameplay or replaces missing source. */
public record WorldContentPlan(String entrypoint,Map<String,String> modules,Map<UUID,RuntimeDefinition> definitions,Map<UUID,String> restoreEntrypoints) {
    public WorldContentPlan { modules=Map.copyOf(modules);definitions=Map.copyOf(definitions);restoreEntrypoints=Map.copyOf(restoreEntrypoints); }
    public boolean supportsRestore(UUID definition){return restoreEntrypoints.containsKey(definition);}
    public static WorldContentPlan resolve(RuntimePackage p,ContentAddressedStore content)throws Exception{
        var entry=p.entrypoints().get("server");
        if(entry==null||entry.side()!=RuntimeResourceSide.SERVER||p.definitions().isEmpty())throw new IllegalArgumentException("WORLD_ENTRYPOINT_REQUIRED");
        var modules=new LinkedHashMap<String,String>();
        for(var ref:p.resources().values())if(!ref.path().startsWith("ui/")&&ref.path().endsWith(".js")&&ref.side()!=RuntimeResourceSide.CLIENT){
            var bytes=content.read(ref.sha256());if(bytes.length!=ref.size())throw new IllegalArgumentException("WORLD_RESOURCE_SIZE");
            modules.put(ref.path(),new String(bytes,StandardCharsets.UTF_8));
        }
        if(!modules.containsKey(entry.path())||modules.size()>64)throw new IllegalArgumentException("WORLD_MODULES_INVALID");
        var restore=new LinkedHashMap<UUID,String>();
        for(var d:p.definitions().values()){
            var e=p.entrypoints().get(d.entrypointId());if(e==null||e.side()==RuntimeResourceSide.CLIENT||!modules.containsKey(e.path()))throw new IllegalArgumentException("WORLD_DEFINITION_ENTRYPOINT");
            var r=p.entrypoints().get(d.entrypointId()+".restore");
            if(r!=null){if(!r.equals(e))throw new IllegalArgumentException("RESTORE_ENTRYPOINT_MISMATCH");restore.put(d.definitionId(),r.path());}
        }
        return new WorldContentPlan(entry.path(),modules,p.definitions(),restore);
    }
}
