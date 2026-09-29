package dev.mineagent.runtime.core.task;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

/** Combat choices are independent of the durable work intent. No attribute bonuses. */
public record CombatPolicy(Strategy strategy, Engagement engagement, String protect,
                           Set<UUID> excluded, double leash, double awareness,String target,SkillSpec.Area area) {
    public CombatPolicy(Strategy strategy,Engagement engagement,String protect,Set<UUID> excluded,double leash,double awareness){this(strategy,engagement,protect,excluded,leash,awareness,"",null);}
    public enum Strategy { AUTO, HIT_AND_RUN, RANGED_KITE, HOLD_POSITION, DISENGAGE }
    public enum Engagement { NONE, SELF_DEFENSE, PROTECT, CLEAR_AREA, SPECIFIED }
    public CombatPolicy {
        Objects.requireNonNull(strategy);Objects.requireNonNull(engagement);
        protect=protect==null?"":protect;target=target==null?"":target;excluded=excluded==null?Set.of():Set.copyOf(excluded);
        if(!target.isBlank())UUID.fromString(target);
        if(!protect.isEmpty()&&!protect.equals("$owner"))UUID.fromString(protect);
        if(engagement==Engagement.PROTECT&&protect.isEmpty())throw new IllegalArgumentException("COMBAT_PROTECTED_TARGET_REQUIRED");
        if(!Double.isFinite(leash)||leash<4||leash>64||!Double.isFinite(awareness)||awareness<4||awareness>32)throw new IllegalArgumentException("COMBAT_POLICY_RANGE");
    }
    public static CombatPolicy defaults(SkillSpec.Kind kind,boolean defend,String target){
        return new CombatPolicy(Strategy.AUTO,kind==SkillSpec.Kind.COMBAT&&!target.isBlank()?Engagement.SPECIFIED:!defend?Engagement.NONE:kind==SkillSpec.Kind.GUARD?target.isBlank()?Engagement.CLEAR_AREA:Engagement.PROTECT:Engagement.SELF_DEFENSE,kind==SkillSpec.Kind.GUARD?target:"",Set.of(),24,16,kind==SkillSpec.Kind.COMBAT?target:"",null);
    }
    public CombatPolicy withArea(SkillSpec.Area region){return new CombatPolicy(strategy,engagement,protect,excluded,leash,awareness,target,region);}
    public static CombatPolicy parse(JsonNode n,CombatPolicy prior){
        if(n==null||n.isNull())return prior;if(!n.isObject())throw new IllegalArgumentException("COMBAT_POLICY_OBJECT");
        for(var entry:n.properties())if(!Set.of("strategy","engagement","protect","excluded","leash","awareness","target","min","max").contains(entry.getKey()))throw new IllegalArgumentException("COMBAT_POLICY_FIELD");
        Set<UUID> exclusions=prior.excluded;
        if(n.has("excluded")){if(!n.get("excluded").isArray())throw new IllegalArgumentException("COMBAT_EXCLUSIONS");exclusions=new LinkedHashSet<>();for(var id:n.get("excluded")){if(!id.isTextual())throw new IllegalArgumentException("COMBAT_EXCLUSIONS");exclusions.add(UUID.fromString(id.asText()));}}
        for(String key:List.of("strategy","engagement","protect","target"))if(n.has(key)&&!n.get(key).isTextual())throw new IllegalArgumentException("COMBAT_POLICY_TEXT");
        for(String key:List.of("leash","awareness"))if(n.has(key)&&!n.get(key).isNumber())throw new IllegalArgumentException("COMBAT_POLICY_NUMBER");
        SkillSpec.Area region=n.has("min")||n.has("max")?new SkillSpec.Area(SkillSpec.point(n.path("min")),SkillSpec.point(n.path("max"))):prior.area;
        return new CombatPolicy(n.has("strategy")?Strategy.valueOf(n.get("strategy").asText().toUpperCase(Locale.ROOT)):prior.strategy,n.has("engagement")?Engagement.valueOf(n.get("engagement").asText().toUpperCase(Locale.ROOT)):prior.engagement,n.path("protect").asText(prior.protect),exclusions,n.path("leash").asDouble(prior.leash),n.path("awareness").asDouble(prior.awareness),n.path("target").asText(prior.target),region);
    }
}
