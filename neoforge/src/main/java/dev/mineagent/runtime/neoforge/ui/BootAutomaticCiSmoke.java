package dev.mineagent.runtime.neoforge.ui;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mineagent.runtime.api.packages.*;
import dev.mineagent.runtime.api.permission.PermissionAction;
import dev.mineagent.runtime.core.packages.*;
import dev.mineagent.runtime.neoforge.*;
import dev.mineagent.runtime.worker.generation.*;
import net.minecraft.server.level.*;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Runs only on CI: actual Worker compilation, native install and a second JVM Loader startup. */
@EventBusSubscriber(modid="mineagent_runtime")
public final class BootAutomaticCiSmoke {
    private static final ObjectMapper JSON=new ObjectMapper();private static final String MOD="divzero_boot_ci";
    private static boolean done;private static int ticks;private static CompletableFuture<?> initialization;private static ServerPlayer actor;private static UUID operation;
    @SubscribeEvent public static void tick(ServerTickEvent.Post event){
        if(!Boolean.getBoolean("mineagent.bootAutomaticCiSmoke")||done)return;
        var server=event.getServer();String stage=System.getProperty("mineagent.bootAutomaticCiStage","prepare");Path root=server.getServerDirectory().resolve("boot-automatic-ci");
        try{
            if(!"true".equalsIgnoreCase(System.getenv("CI"))||!server.isDedicatedServer())throw new IllegalStateException("FIXTURE_REQUIRES_GITHUB_ACTIONS_DEDICATED_SERVER");
            Files.createDirectories(root);if(++ticks>18000)throw new IllegalStateException("BOOT_CI_TIMEOUT");
            if(stage.equals("restart")){
                var proof=dev.mineagent.runtime.neoforge.boot.NativeBootProof.get(MOD).orElseThrow(()->new IllegalStateException("BOOT_CI_LOADER_PROOF_MISSING"));
                var before=JSON.readTree(Files.readString(root.resolve("prepare.json")));var marker=Files.readAllLines(server.getServerDirectory().resolve("boot-ci-marker.txt"));
                require(marker.size()==1&&marker.getFirst().equals(proof.canonical()),"BOOT_CI_CONSTRUCTOR_MARKER");require(proof.packageId().toString().equals(before.path("packageId").asText()),"BOOT_CI_PACKAGE_IDENTITY");
                Files.writeString(root.resolve("restart.json"),JSON.writeValueAsString(Map.of("status","PASS","realLoaderConstructor",true,"proofPackage",proof.packageId(),"artifact",proof.archiveHash(),"markerCount",marker.size(),"modelCalls",0)));done=true;server.halt(false);return;
            }
            if(!WorldIdentityRuntime.ready(server)){
                if(initialization==null)initialization=WorldIdentityRuntime.accept(server.createCommandSourceStack());
                if(initialization.isCompletedExceptionally())initialization.join();return;
            }
            if(!MineAgentRuntimeServices.workerReady(server))return;
            if(actor==null){
                var profile=new com.mojang.authlib.GameProfile(UUID.randomUUID(),"BootCiOwner");actor=new ServerPlayer(server,server.overworld(),profile,ClientInformation.createDefault());actor.snapTo(0.5,100,0.5,0,0);
                server.getPlayerList().placeNewPlayer(new dev.mineagent.runtime.neoforge.body.MineAgentConnection(),actor,net.minecraft.server.network.CommonListenerCookie.createInitial(profile,false));actor.connection.markClientLoaded();
                var permissions=MineAgentRuntimeServices.permissions(server);permissions.setPlayerEnabled(actor.getUUID(),true);var config=MineAgentRuntimeServices.config(server);
                require(dev.mineagent.runtime.core.config.PermissionConfig.apply(config,permissions,config.revision(),actor.getUUID(),Set.of(PermissionAction.RUN_CODE,PermissionAction.MANAGE_PACKAGES),true).accepted(),"BOOT_CI_GRANT");
                UUID agent=MineAgentRuntimeServices.bodies(server).createPersistentAt("BOOT_CI",actor.getUUID(),actor.level(),actor.position().add(3,0,0)).agentId();publish(actor,agent);
            }
            var runtime=ServerPackageRuntime.get(server);var job=runtime.generation(actor.getUUID(),operation).orElseThrow();var receipt=runtime.generatedActivation(actor,job);String status=receipt.get("status").toString();
            if(Set.of("PUBLISHED_BOOT_QUEUED","PUBLISHED_BOOT_BUILD_SUBMITTED","PUBLISHED_BOOT_INSTALL_SUBMITTED").contains(status))return;
            require(status.equals("PUBLISHED_BOOT_READY_AT_SERVER_RESTART"),"BOOT_CI_ACTIVATION_"+JSON.writeValueAsString(receipt));
            require(!Files.exists(server.getServerDirectory().resolve("boot-ci-marker.txt")),"BOOT_CI_EXECUTED_BEFORE_RESTART");
            Files.writeString(root.resolve("prepare.json"),JSON.writeValueAsString(Map.of("status","PASS","packageId",job.packageId(),"receipt",receipt,"workerCompiled",true,"installedNotHotLoaded",true,"actor","CONNECTED_NATIVE_FIXTURE_NOT_HUMAN_TEST","modelCalls",0)));done=true;server.halt(false);
        }catch(Exception error){try{Files.createDirectories(root);Files.writeString(root.resolve(stage+"-failure.json"),JSON.writeValueAsString(Map.of("error",error.toString(),"ticks",ticks)));}catch(Exception ignored){}done=true;server.halt(false);}
    }
    private static void publish(ServerPlayer player,UUID agent)throws Exception{
        var runtime=ServerPackageRuntime.get(player.level().getServer());var field=ServerPackageRuntime.class.getDeclaredField("jobs");field.setAccessible(true);var jobs=(PackageGenerationService)field.get(runtime);
        operation=UUID.randomUUID();var job=jobs.submit(player.getUUID(),agent,operation,"FIXTURE_ONLY generated BOOT lifecycle",true,"WORLD_CONTENT").job();
        var activation=ServerPackageRuntime.class.getDeclaredField("generatedActivation");activation.setAccessible(true);((GeneratedContentActivation)activation.get(runtime)).capture(player,job);
        var descriptor=file("boot/extension.json","application/json","{\"schema\":1,\"modId\":\""+MOD+"\",\"entrypoint\":\"fixture.boot.AutomaticExtension\",\"mixins\":[]}");
        var code=file("boot/src/fixture/boot/AutomaticExtension.java","text/x-java-source","package fixture.boot; public final class AutomaticExtension implements dev.mineagent.runtime.api.extension.BootExtension { public void initialize(java.util.Map<String,Object> context)throws Exception { java.nio.file.Files.writeString(java.nio.file.Path.of(\"boot-ci-marker.txt\"), context.get(\"packageHash\")+\"\\n\", java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND); } }");
        var env=dev.mineagent.runtime.neoforge.content.NativePackageCompatibility.observe();var contract=new NativeCompatibility(1,Map.of("SERVER",new NativeCompatibility.Target(env.minecraft(),env.loader(),env.loaderVersion(),env.namespace(),25,Map.of())));
        var parsed=new ParsedRuntimePackage("BOOT automatic CI","1.0.0",RuntimePackageType.EXTENSION,ActivationMode.BOOT_EXTENSION,Map.of(),Set.of("RUN_CODE"),Map.of("boot",new RuntimeEntrypoint(descriptor.path(),descriptor.side(),descriptor.sha256())),List.of(),List.of(descriptor,code),contract);
        for(var file:parsed.files())runtime.worldContent().put(file.sha256(),file.content());var candidate=RuntimePackagePublisher.prepare(parsed,job.packageId(),MineAgentRuntimeServices.identity(player.level().getServer()));
        var method=ServerPackageRuntime.class.getDeclaredMethod("publishGenerated",PackageGenerationJob.class,RuntimePackage.class,String.class,String.class);method.setAccessible(true);method.invoke(runtime,job,candidate,"fixture",runtime.worldContent().put("FIXTURE_ONLY_BOOT".getBytes(StandardCharsets.UTF_8)).sha256());
    }
    private static GeneratedFile file(String path,String media,String source){return new GeneratedFile(path,RuntimeResourceSide.SERVER,media,ResourceIds.create(),source.getBytes(StandardCharsets.UTF_8));}
    private static void require(boolean value,String code){if(!value)throw new IllegalStateException(code);}
    private BootAutomaticCiSmoke(){}
}
