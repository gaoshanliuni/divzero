package dev.mineagent.runtime.neoforge.ui;
import com.fasterxml.jackson.databind.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.core.registries.*;
import net.minecraft.resources.*;
import net.minecraft.world.effect.*;
import java.util.*;
/** Native effects on the actual chat player, not an agent's body or an arbitrary selector. */
public final class ConversationEffectTools {
    private static final ObjectMapper JSON=new ObjectMapper();private ConversationEffectTools(){}
    public static Map<String,Object> inspect(ServerPlayer p){return Map.of("player",p.getUUID(),"effects",p.getActiveEffects().stream().map(e->entry(p,e)).toList(),"canModify",p.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER),"gameTick",p.level().getGameTime());}
    private static Map<String,Object> entry(ServerPlayer p,MobEffectInstance e){try{var v=new LinkedHashMap<String,Object>();v.put("id",e.getEffect().unwrapKey().orElseThrow().identifier().toString());v.put("level",e.getAmplifier()+1);v.put("expiresAtTick",e.isInfiniteDuration()?-1:p.level().getGameTime()+e.getDuration());v.put("ambient",e.isAmbient());v.put("particles",e.isVisible());v.put("icon",e.showIcon());String hash=dev.mineagent.runtime.core.packages.RuntimePackageCanonicalizer.sha256(JSON.writeValueAsString(v));v.put("hash",hash);v.put("remainingTicks",e.getDuration());v.put("name",e.getEffect().value().getDisplayName().getString());return v;}catch(Exception failure){throw new IllegalStateException("EFFECT_OBSERVATION_FAILED",failure);}}
    public static Map<String,Object> change(ServerPlayer p,JsonNode a){if(!p.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))throw new SecurityException("EFFECT_PERMISSION_REQUIRED");String id=a.path("effect").asText();var key=Identifier.parse(id);if(!BuiltInRegistries.MOB_EFFECT.containsKey(key))throw new IllegalArgumentException("EFFECT_UNKNOWN");var effect=BuiltInRegistries.MOB_EFFECT.wrapAsHolder(BuiltInRegistries.MOB_EFFECT.getValue(key));var old=p.getEffect(effect);String hash=old==null?"ABSENT":entry(p,old).get("hash").toString();if(!hash.equals(a.path("expected_hash").asText()))throw new IllegalStateException("EFFECT_CHANGED_REOBSERVE");String action=a.path("action").asText();if(!Set.of("set","remove").contains(action))throw new IllegalArgumentException("EFFECT_ACTION");
        if(action.equals("remove")){boolean removed=old!=null&&p.removeEffect(effect);return Map.of("status",p.getEffect(effect)==null?"APPLIED":"REJECTED","removed",removed,"observed",inspect(p));}
        int level=a.path("level").asInt(0),seconds=a.path("duration_seconds").asInt(0);if(!a.path("level").isIntegralNumber()||!a.path("level").canConvertToInt()||!a.path("duration_seconds").isIntegralNumber()||!a.path("duration_seconds").canConvertToInt()||level<1||level>256||seconds<1||seconds>86400)throw new IllegalArgumentException("EFFECT_ARGUMENTS");boolean infinite=a.path("infinite").asBoolean(false),ambient=a.path("ambient").asBoolean(old!=null&&old.isAmbient()),visible=a.path("particles").asBoolean(old==null||old.isVisible()),icon=a.path("icon").asBoolean(old==null||old.showIcon());
        if(old!=null&&!p.removeEffect(effect))return Map.of("status","REJECTED","error","EFFECT_REMOVAL_DENIED","observed",inspect(p));boolean applied=p.addEffect(new MobEffectInstance(effect,infinite?-1:seconds*20,level-1,ambient,visible,icon),p);var actual=p.getEffect(effect);return Map.of("status",applied&&actual!=null&&actual.getAmplifier()==level-1?"APPLIED":"PARTIAL","observed",inspect(p));
    }
}
