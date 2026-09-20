package dev.mineagent.runtime.core.config;

import dev.mineagent.runtime.api.permission.PermissionAction;
import java.util.*;

/** Durable per-action grant history, separate from public settings and process-local revocation epochs. */
public final class PermissionGenerations {
    private PermissionGenerations(){}
    public static String key(UUID player,PermissionAction action){return player+"|"+action.name();}
    public static Map<String,Long> advance(Map<String,Long> generations,Map<String,String> before,Map<String,String> after){
        var result=new TreeMap<>(generations);var keys=new HashSet<>(before.keySet());keys.addAll(after.keySet());
        for(var key:keys)if(key.startsWith("permission.player.")){UUID player;try{player=UUID.fromString(key.substring("permission.player.".length()));}catch(IllegalArgumentException malformed){continue;}
            var old=actions(before.get(key));var next=actions(after.get(key));for(var action:PermissionAction.values())if(old.contains(action)!=next.contains(action)){String id=key(player,action);result.put(id,Math.addExact(result.getOrDefault(id,0L),1));}
        }return Collections.unmodifiableMap(result);
    }
    private static Set<PermissionAction> actions(String value){try{if(value==null)return Set.of();var result=EnumSet.noneOf(PermissionAction.class);for(var part:value.split(","))if(!part.isBlank())result.add(PermissionAction.valueOf(part.strip()));return result;}catch(IllegalArgumentException invalid){return Set.of();}}
}
