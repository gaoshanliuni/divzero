package dev.mineagent.runtime.neoforge.ui;

import dev.mineagent.runtime.api.packages.*;
import dev.mineagent.runtime.core.packages.*;
import dev.mineagent.runtime.neoforge.MineAgentRuntimeServices;
import dev.mineagent.runtime.worker.generation.*;
import net.minecraft.server.level.ServerPlayer;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Zero-model signed resource candidate, consumed by the normal client automatic lifecycle. */
public final class CapabilityDeliverySmoke {
    public static final String MARKER="DIVZERO_AUTOMATIC_RESOURCE_ACTUAL_READBACK";
    private static UUID generation;
    private static void guard(){if(!Boolean.getBoolean("mineagent.skillSmoke")||!System.getProperty("mineagent.skillSmokeMode","").equals("capability_delivery"))throw new SecurityException("FIXTURE_DISABLED");}
    public static Map<String,Object> publish(ServerPlayer player,UUID agent)throws Exception{
        guard();var runtime=ServerPackageRuntime.get(player.level().getServer());
        var field=ServerPackageRuntime.class.getDeclaredField("jobs");field.setAccessible(true);var jobs=(PackageGenerationService)field.get(runtime);
        generation=UUID.randomUUID();var job=jobs.submit(player.getUUID(),agent,generation,"FIXTURE_ONLY own generated resource pack",true,"WORLD_CONTENT").job();
        var activation=ServerPackageRuntime.class.getDeclaredField("generatedActivation");activation.setAccessible(true);((GeneratedContentActivation)activation.get(runtime)).capture(player,job);
        var metadata=new GeneratedFile("resourcepack/pack.mcmeta",RuntimeResourceSide.CLIENT,"application/json",ResourceIds.create(),"{\"pack\":{\"description\":\"Automatic lifecycle fixture\",\"min_format\":[84,0],\"max_format\":[84,0]}}".getBytes(StandardCharsets.UTF_8));
        var marker=new GeneratedFile("resourcepack/assets/capability_fixture/auto.txt",RuntimeResourceSide.CLIENT,"text/plain",ResourceIds.create(),MARKER.getBytes(StandardCharsets.UTF_8));
        var env=dev.mineagent.runtime.neoforge.content.NativePackageCompatibility.observe();var contract=new NativeCompatibility(1,Map.of("CLIENT",new NativeCompatibility.Target(env.minecraft(),env.loader(),env.loaderVersion(),env.namespace(),env.javaFeature(),Map.of())));
        var parsed=new ParsedRuntimePackage("自动资源重载验收","1.0",RuntimePackageType.CONTENT,ActivationMode.RESOURCE_RELOAD,Map.of(),Set.of(),Map.of("resourcepack",new RuntimeEntrypoint(metadata.path(),metadata.side(),metadata.sha256())),List.of(),List.of(metadata,marker),contract);
        for(var file:parsed.files())runtime.worldContent().put(file.sha256(),file.content());
        var candidate=RuntimePackagePublisher.prepare(parsed,job.packageId(),MineAgentRuntimeServices.identity(player.level().getServer()));
        var publish=ServerPackageRuntime.class.getDeclaredMethod("publishGenerated",PackageGenerationJob.class,RuntimePackage.class,String.class,String.class);publish.setAccessible(true);
        publish.invoke(runtime,job,candidate,"fixture",runtime.worldContent().put("FIXTURE_ONLY_RESOURCE_CANDIDATE".getBytes(StandardCharsets.UTF_8)).sha256());
        return Map.of("operation",generation,"candidate",job.packageId(),"receipt",runtime.generatedActivation(player,runtime.generation(player.getUUID(),generation).orElseThrow()),"modelCalls",0);
    }
    public static Map<String,Object> receipt(ServerPlayer player)throws Exception{guard();var runtime=ServerPackageRuntime.get(player.level().getServer());return runtime.generatedActivation(player,runtime.generation(player.getUUID(),generation).orElseThrow());}
    private CapabilityDeliverySmoke(){}
}
