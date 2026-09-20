package dev.mineagent.runtime.api.packages;

import java.util.*;

/** Signed exact-version requirements. Matching them is not proof that native code or a lifecycle is implemented. */
public record NativeCompatibility(int schema,Map<String,Target> targets) {
    public record Target(String minecraft,String loader,String loaderVersion,String namespace,int javaFeature,Map<String,String> requiredMods){
        public Target{
            version(minecraft);version(loaderVersion);
            if(loader==null||!loader.matches("[a-z][a-z0-9_-]{0,31}")||namespace==null||!namespace.matches("[a-z][a-z0-9_.-]{0,63}")||javaFeature<17||javaFeature>99||requiredMods==null||requiredMods.size()>64)throw new IllegalArgumentException("NATIVE_COMPATIBILITY_INVALID");
            var mods=new TreeMap<String,String>();requiredMods.forEach((id,value)->{if(id==null||!id.matches("[a-z][a-z0-9_]{1,63}")||Set.of("minecraft","neoforge").contains(id))throw new IllegalArgumentException("NATIVE_COMPATIBILITY_MOD_ID");version(value);mods.put(id,value);});requiredMods=Collections.unmodifiableMap(mods);
        }
        public Map<String,Object> wire(){return Map.of("minecraft",minecraft,"loader",loader,"loaderVersion",loaderVersion,"namespace",namespace,"javaFeature",javaFeature,"requiredMods",requiredMods);}
    }
    public NativeCompatibility{
        if(schema!=1||targets==null||targets.isEmpty()||!Set.of("SERVER","CLIENT").containsAll(targets.keySet())||targets.values().stream().anyMatch(Objects::isNull))throw new IllegalArgumentException("NATIVE_COMPATIBILITY_INVALID");
        targets=Collections.unmodifiableMap(new TreeMap<>(targets));
    }
    public Map<String,Object> wire(){var result=new TreeMap<String,Object>();targets.forEach((key,value)->result.put(key,value.wire()));return Map.of("schema",schema,"targets",result);}
    private static void version(String value){if(value==null||!value.matches("[0-9A-Za-z][0-9A-Za-z._+\\-]{0,127}")||Set.of("latest","any").contains(value.toLowerCase(Locale.ROOT)))throw new IllegalArgumentException("NATIVE_COMPATIBILITY_EXACT_VERSION_REQUIRED");}
}
