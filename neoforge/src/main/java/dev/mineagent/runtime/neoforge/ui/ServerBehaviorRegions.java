package dev.mineagent.runtime.neoforge.ui;
import com.fasterxml.jackson.databind.*;
import dev.mineagent.runtime.core.ui.BehaviorRegionSelection;
import dev.mineagent.runtime.neoforge.MineAgentRuntimeServices;
import net.minecraft.server.level.ServerPlayer;
import java.util.*;

final class ServerBehaviorRegions {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static String key(ServerPlayer p,UUID agent,String actor){if(!Set.of("ai","player").contains(actor))throw new IllegalArgumentException("REGION_ACTOR");return "behavior.region."+MineAgentRuntimeServices.worldId(p.level().getServer())+"."+p.getUUID()+"."+agent+"."+actor;}
    static Map<String,Object> read(ServerPlayer p,UUID agent,String actor){String raw=MineAgentRuntimeServices.config(p.level().getServer()).snapshot().values().get(key(p,agent,actor));if(raw==null)return Map.of();try{var saved=BehaviorRegionSelection.parse(JSON.readTree(raw));return saved.values(saved.revision());}catch(Exception e){throw new IllegalStateException("REGION_PREFERENCES_INVALID",e);}}
    static BehaviorRegionSelection prepare(ServerPlayer p,UUID agent,JsonNode n){var next=BehaviorRegionSelection.parse(n);if(!next.dimension().equals(p.level().dimension().identifier().toString()))throw new IllegalStateException("REGION_DIMENSION_CHANGED");var old=read(p,agent,next.actor());if(((Number)old.getOrDefault("revision",0L)).longValue()!=next.revision())throw new IllegalStateException("REGION_REVISION_CHANGED");return next;}
    static void save(ServerPlayer p,UUID agent,BehaviorRegionSelection next)throws Exception{
        var old=read(p,agent,next.actor());if(((Number)old.getOrDefault("revision",0L)).longValue()!=next.revision())throw new IllegalStateException("REGION_REVISION_CHANGED");
        var config=MineAgentRuntimeServices.config(p.level().getServer());var result=config.apply(new dev.mineagent.runtime.api.config.ConfigPatch(config.revision(),Map.of(key(p,agent,next.actor()),JSON.writeValueAsString(next.values(next.revision()+1)))),true);if(!result.accepted())throw new IllegalStateException(result.errorCode());
    }
    private ServerBehaviorRegions(){}
}
