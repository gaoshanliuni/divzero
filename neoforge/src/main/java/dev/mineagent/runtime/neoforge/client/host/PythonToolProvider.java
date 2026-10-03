package dev.mineagent.runtime.neoforge.client.host;

import com.fasterxml.jackson.databind.JsonNode;
import dev.mineagent.runtime.core.host.HostCommandRequest;
import dev.mineagent.runtime.neoforge.host.LocalHostCommands;
import dev.mineagent.runtime.neoforge.hostsupport.HostToolBridge;
import dev.mineagent.runtime.neoforge.task.ServerTaskStart;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

@EventBusSubscriber(modid="mineagent_runtime",value=net.neoforged.api.distmarker.Dist.CLIENT)
public final class PythonToolProvider implements HostToolBridge.Provider {
    static final PythonToolProvider INSTANCE=new PythonToolProvider();
    private record Running(ServerPlayer player,UUID agent,UUID operation,BooleanSupplier live,CompletableFuture<Map<String,Object>> result){}
    private static final List<Running> RUNNING=new ArrayList<>();
    public CompletableFuture<Map<String,Object>> execute(ServerPlayer p,UUID agent,UUID operation,String tool,JsonNode a,BooleanSupplier current){
        if(!p.level().getServer().isSameThread()||!current.getAsBoolean()||!ServerTaskStart.allowed(p,agent))return CompletableFuture.completedFuture(Map.of("status","REJECTED","error","HOST_PERMISSION_CHANGED","executionState","NOT_STARTED"));
        if(tool.equals("inspect_host"))return CompletableFuture.completedFuture(LocalHostCommands.inspect(p));
        if(tool.equals("read_host_output"))return LocalHostCommands.output(p,UUID.fromString(a.path("operation_id").asText()),a.path("stream").asText(),a.path("offset").asInt(0));
        HostCommandRequest request;if(tool.equals("python_execute"))request=new HostCommandRequest(operation,a.path("purpose").asText(),a.path("script").asText(),a.path("timeout_seconds").asInt());
        else if(tool.equals("python_install_packages")){var packages=new ArrayList<String>();for(var value:a.path("packages"))packages.add(value.asText());request=HostCommandRequest.install(operation,a.path("purpose").asText(),packages,a.path("timeout_seconds").asInt());}
        else throw new IllegalArgumentException("HOST_TOOL_UNKNOWN");
        var result=LocalHostCommands.request(p,request);synchronized(RUNNING){RUNNING.add(new Running(p,agent,operation,current,result));}return result;
    }
    public boolean legacySmokeTick(net.minecraft.server.MinecraftServer server)throws Exception{if(!dev.mineagent.runtime.neoforge.ui.PythonHostSmokeServer.active())return false;dev.mineagent.runtime.neoforge.ui.PythonHostSmokeServer.tick(server);return true;}
    public Map<String,Object> pending(){return ClientHostCommands.smokePending();}
    public void observe(String tool,JsonNode args,Map<String,Object> result){dev.mineagent.runtime.neoforge.ui.PythonHostSmokeServer.observe(tool,args,result);}
    @SubscribeEvent public static void tick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event){synchronized(RUNNING){RUNNING.removeIf(r->{if(r.player.level().getServer()!=event.getServer())return false;if(r.result.isDone())return true;String reason=event.getServer().getPlayerList().getPlayer(r.player.getUUID())!=r.player?"HOST_PLAYER_SESSION_CHANGED":!ServerTaskStart.allowed(r.player,r.agent)?"HOST_PERMISSION_CHANGED":!r.live.getAsBoolean()?"HOST_CONVERSATION_CANCELLED":"";if(!reason.isEmpty())LocalHostCommands.cancel(r.operation,reason);return r.result.isDone();});}}
    @SubscribeEvent public static void stop(net.neoforged.neoforge.event.server.ServerStoppingEvent event){synchronized(RUNNING){RUNNING.removeIf(r->{if(r.player.level().getServer()!=event.getServer())return false;LocalHostCommands.cancel(r.operation,"HOST_SERVER_STOPPED");return true;});}}
    private PythonToolProvider(){}
}
