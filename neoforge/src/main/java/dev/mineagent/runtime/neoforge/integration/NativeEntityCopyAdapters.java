package dev.mineagent.runtime.neoforge.integration;

import dev.mineagent.runtime.core.creature.NativeEntityDefinition;
import net.minecraft.world.entity.*;
import java.util.*;

/** Explicit installed Java integrations. Model-supplied state cannot name arbitrary NBT or private fields. */
public final class NativeEntityCopyAdapters {
    public interface Adapter {
        String id();String version();Set<String> entityTypes();Map<String,Class<?>> fields();
        Map<String,Object> capture(Mob source);
        /** Validate fully before returning the action; preparation must not mutate the entity. */
        Runnable prepare(Mob destination,Map<String,Object> state);
    }
    private static final Map<String,Adapter> ADAPTERS=new LinkedHashMap<>();
    public static synchronized void register(Adapter adapter){
        Objects.requireNonNull(adapter);if(!adapter.id().matches("[a-z0-9_.-]+:[a-z0-9_./-]+")||adapter.version().isBlank()||adapter.entityTypes().contains("minecraft:player"))throw new IllegalArgumentException("ENTITY_COPY_ADAPTER_ID");
        for(var field:adapter.fields().entrySet())if(!field.getKey().matches("[a-z][a-zA-Z0-9_]{0,63}")||!Set.of(String.class,Boolean.class,Number.class).contains(field.getValue()))throw new IllegalArgumentException("ENTITY_COPY_ADAPTER_FIELD");
        if(ADAPTERS.putIfAbsent(adapter.id(),adapter)!=null)throw new IllegalStateException("ENTITY_COPY_ADAPTER_EXISTS");
    }
    private static synchronized Adapter adapter(String type,String id,String version){var a=ADAPTERS.get(id);if(a==null||!a.entityTypes().contains(type)||!a.version().equals(version))throw new IllegalArgumentException("ENTITY_COPY_ADAPTER_UNAVAILABLE");return a;}
    public static synchronized List<Map<String,Object>> catalog(){return ADAPTERS.values().stream().map(a->Map.<String,Object>of("id",a.id(),"version",a.version(),"entityTypes",a.entityTypes(),"fields",a.fields().keySet())).toList();}
    public static Map<String,Object> capture(Mob source,String type,NativeEntityDefinition.CopyPolicy mode,String id,String version,Set<String> selected){
        if(mode==NativeEntityDefinition.CopyPolicy.BASIC)return Map.of();
        if(mode==NativeEntityDefinition.CopyPolicy.SAFE_STATE){var out=new LinkedHashMap<String,Object>();out.put("silent",source.isSilent());out.put("nameVisible",source.isCustomNameVisible());if(source instanceof AgeableMob ageable)out.put("age",ageable.getAge());return Map.copyOf(out);}
        var a=adapter(type,id,version);var observed=a.capture(source);var result=new LinkedHashMap<String,Object>();
        Set<String> keys=mode==NativeEntityDefinition.CopyPolicy.CUSTOM?selected:a.fields().keySet();if(!a.fields().keySet().containsAll(keys))throw new IllegalArgumentException("ENTITY_COPY_FIELD_NOT_DECLARED");
        for(String key:keys){if(!observed.containsKey(key))throw new IllegalArgumentException("ENTITY_COPY_FIELD_UNAVAILABLE");result.put(key,observed.get(key));}validate(type,mode,id,version,result);return Map.copyOf(result);
    }
    public static void validate(String type,NativeEntityDefinition.CopyPolicy mode,String id,String version,Map<String,Object> state){
        if(mode==NativeEntityDefinition.CopyPolicy.BASIC){if(!state.isEmpty())throw new IllegalArgumentException("ENTITY_COPY_BASIC_STATE");return;}
        Map<String,Class<?>> fields=mode==NativeEntityDefinition.CopyPolicy.SAFE_STATE?Map.of("silent",Boolean.class,"nameVisible",Boolean.class,"age",Number.class):adapter(type,id,version).fields();
        for(var field:state.entrySet()){var expected=fields.get(field.getKey());if(expected==null||!expected.isInstance(field.getValue()))throw new IllegalArgumentException("ENTITY_COPY_FIELD_"+field.getKey());}
    }
    public static Runnable prepare(Mob entity,NativeEntityDefinition definition){
        var state=definition.copyState();validate(definition.type(),definition.copyPolicy(),definition.copyAdapter(),definition.adapterVersion(),state);
        Runnable action;
        if(definition.copyPolicy()==NativeEntityDefinition.CopyPolicy.BASIC)action=()->{};
        else if(definition.copyPolicy()==NativeEntityDefinition.CopyPolicy.SAFE_STATE){
            if(state.containsKey("age")&&(!(entity instanceof AgeableMob)||((Number)state.get("age")).doubleValue()!=((Number)state.get("age")).intValue()))throw new IllegalArgumentException("ENTITY_COPY_AGE_UNSUPPORTED");
            action=()->{if(state.containsKey("silent"))entity.setSilent((Boolean)state.get("silent"));if(state.containsKey("nameVisible"))entity.setCustomNameVisible((Boolean)state.get("nameVisible"));if(state.containsKey("age"))((AgeableMob)entity).setAge(((Number)state.get("age")).intValue());};
        }else action=adapter(definition.type(),definition.copyAdapter(),definition.adapterVersion()).prepare(entity,state);
        var id=entity.getUUID();var level=entity.level();var target=entity.getTarget();var vehicle=entity.getVehicle();var passengers=List.copyOf(entity.getPassengers());
        return ()->{action.run();if(!entity.getUUID().equals(id)||entity.level()!=level||entity.getTarget()!=target||entity.getVehicle()!=vehicle||!entity.getPassengers().equals(passengers))throw new IllegalStateException("ENTITY_COPY_IDENTITY_CHANGED_OUTCOME_UNKNOWN");};
    }
    private NativeEntityCopyAdapters(){}
}
