package dev.mineagent.runtime.neoforge.ui;

import dev.mineagent.runtime.neoforge.MineAgentRuntimeServices;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import java.nio.file.*;
import java.util.*;

/** Opt-in isolated dedicated-server load/worker/shutdown acceptance, with no model or player actions. */
@EventBusSubscriber(modid="mineagent_runtime")
public final class NativeDedicatedSmoke {
    private static int ticks;private static boolean done;
    @SubscribeEvent public static void tick(ServerTickEvent.Post event){
        if(!Boolean.getBoolean("mineagent.nativeDedicatedSmoke")||done)return;var server=event.getServer();if(!server.isDedicatedServer())return;
        try{
            ticks++;boolean ready=MineAgentRuntimeServices.workerReady(server);if(ticks<40||!ready&&ticks<1200)return;
            var mods=net.neoforged.fml.ModList.get().getMods().stream().map(m->m.getModId()).sorted().toList();
            boolean nativeOnly=!mods.contains("mcef")&&!mods.contains("webgui")&&mods.contains("ldlib2");
            var result=Map.of("status",ready&&nativeOnly?"PASS":"FAIL","dedicatedServer",true,"workerReady",ready,"modelCalls",0,"mods",mods,"worldId",MineAgentRuntimeServices.worldId(server).toString(),"ticks",ticks);
            Files.writeString(server.getServerDirectory().resolve("native-dedicated-result.json"),new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(result));done=true;server.halt(false);
        }catch(Exception error){try{Files.writeString(server.getServerDirectory().resolve("native-dedicated-failure.txt"),error.toString());}catch(Exception ignored){}done=true;server.halt(false);}
    }
    private NativeDedicatedSmoke(){}
}
