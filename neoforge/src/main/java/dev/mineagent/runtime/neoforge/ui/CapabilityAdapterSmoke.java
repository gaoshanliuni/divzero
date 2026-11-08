package dev.mineagent.runtime.neoforge.ui;

import dev.mineagent.runtime.core.creature.NativeEntityDefinition;
import dev.mineagent.runtime.neoforge.integration.NativeEntityCopyAdapters;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.*;
import java.util.*;

public final class CapabilityAdapterSmoke {
    public static Map<String,Object> run(ServerPlayer player)throws Exception{
        if(!Boolean.getBoolean("mineagent.skillSmoke")||!"capability_adapters".equals(System.getProperty("mineagent.skillSmokeMode")))throw new SecurityException("FIXTURE_DISABLED");
        var a=EntityType.PIG.create(player.level(),EntitySpawnReason.COMMAND);var b=EntityType.PIG.create(player.level(),EntitySpawnReason.COMMAND);if(a==null||b==null)throw new IllegalStateException("FIXTURE_PIG_CREATE");a.setNoAi(true);b.setNoAi(true);a.setSilent(true);a.setPos(player.getX(),player.getY(),player.getZ()+5);b.setPos(player.getX()+3,player.getY(),player.getZ()+5);
        String id="capability_fixture:copy";Map<String,Object> captured;
        try(var registered=NativeEntityCopyAdapters.register(new NativeEntityCopyAdapters.Adapter(){
            public String id(){return id;}public String version(){return "1";}public Set<String> entityTypes(){return Set.of("minecraft:pig");}public Map<String,Class<?>> fields(){return Map.of("silent",Boolean.class);}
            public Map<String,Object> capture(Mob source){return Map.of("silent",source.isSilent());}
            public Runnable prepare(Mob destination,Map<String,Object> state){return ()->{if(state.containsKey("silent"))destination.setSilent((Boolean)state.get("silent"));};}
        })){
            captured=NativeEntityCopyAdapters.capture(a,"minecraft:pig",NativeEntityDefinition.CopyPolicy.CUSTOM,id,"1",Set.of("silent"));
            var definition=NativeEntityDefinition.parse("{\"name\":\"适配器副本\",\"type\":\"minecraft:pig\",\"copy_policy\":\"CUSTOM\",\"copy_adapter\":\""+id+"\",\"adapter_version\":\"1\",\"copy_state\":{\"silent\":true}}");
            var apply=NativeEntityCopyAdapters.prepare(b,definition);if(b.isSilent())throw new IllegalStateException("COPY_PREPARE_MUTATED");apply.run();if(!b.isSilent()||b.getUUID().equals(a.getUUID()))throw new IllegalStateException("COPY_ADAPTER_NATIVE_STATE");
            boolean rejected=false;try{NativeEntityCopyAdapters.capture(a,"minecraft:pig",NativeEntityDefinition.CopyPolicy.CUSTOM,id,"1",Set.of("uuid"));}catch(IllegalArgumentException expected){rejected=true;}if(!rejected)throw new IllegalStateException("UNDECLARED_COPY_FIELD_ACCEPTED");
        }
        if(NativeEntityCopyAdapters.catalog().stream().anyMatch(row->row.get("id").equals(id)))throw new IllegalStateException("COPY_ADAPTER_UNREGISTER");
        if(!player.level().addFreshEntity(a)||!player.level().addFreshEntity(b))throw new IllegalStateException("COPY_NATIVE_SPAWN");
        return Map.of("source",a.getUUID(),"copy",b.getUUID(),"state",captured,"nativeCopy",true,"prepareDidNotMutate",true,"undeclaredFieldRejected",true,"unregistered",true,"modelCalls",0);
    }
    private CapabilityAdapterSmoke(){}
}
