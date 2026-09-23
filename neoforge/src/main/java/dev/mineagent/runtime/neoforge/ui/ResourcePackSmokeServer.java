package dev.mineagent.runtime.neoforge.ui;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mineagent.runtime.api.packages.*;
import dev.mineagent.runtime.api.permission.PermissionAction;
import dev.mineagent.runtime.core.packages.RuntimePackageCanonicalizer;
import dev.mineagent.runtime.neoforge.MineAgentRuntimeServices;
import dev.mineagent.runtime.worker.generation.*;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Signed RESOURCE_RELOAD package published through the real owned-package path. No Provider is involved. */
@EventBusSubscriber(modid="mineagent_runtime")
public final class ResourcePackSmokeServer {
    public static final String PACKAGE_NAME="本机资源重载 · 正式验收包";
    public static final String RESOURCE_TEXT="MINEAGENT_RESOURCE_RELOAD_NATIVE_SMOKE_V1";
    public static volatile RuntimePackage runtimePackage;
    public static volatile boolean historicalCopyVerified;
    public static volatile String failure;
    private static final ObjectMapper JSON=new ObjectMapper();
    private static int ticks;

    private ResourcePackSmokeServer(){}
    public static boolean enabled(){return Boolean.getBoolean("mineagent.resourcePackSmoke");}
    private static Path root(net.minecraft.server.MinecraftServer server){return server.getServerDirectory().resolve("resource-pack-smoke");}
    private static GeneratedFile file(String path,String media,String value)throws Exception{return file(path,media,value.getBytes(StandardCharsets.UTF_8));}
    private static GeneratedFile file(String path,String media,byte[] bytes)throws Exception{return new GeneratedFile(path,RuntimeResourceSide.CLIENT,media,RuntimePackageCanonicalizer.sha256(bytes),bytes);}

    @SubscribeEvent public static void tick(ServerTickEvent.Post event)throws Exception{
        if(!enabled()||failure!=null||historicalCopyVerified)return;var server=event.getServer();ticks++;
        var viewer=server.getPlayerList().getPlayers().stream().filter(p->!(p instanceof dev.mineagent.runtime.neoforge.body.MineAgentPlayer)).findFirst().orElse(null);if(viewer==null)return;
        try{
            if(runtimePackage!=null){
                var runtime=ServerPackageRuntime.get(server);var library=runtime.worldLibrary();
                for(var candidate:runtime.ownedHeads(viewer.getUUID(),0,16).items())if(!candidate.packageId().equals(runtimePackage.packageId())){
                    var origin=library.derivation(viewer.getUUID(),candidate.packageId());if(origin==null||!origin.action().equals("COPY_VERSION")||!origin.source().equals(runtimePackage.packageId()))continue;
                    var current=library.get(runtimePackage.packageId()).orElseThrow();
                    if(current.revision()!=3||current.enabled()||candidate.activationMode()!=ActivationMode.RESOURCE_RELOAD||candidate.enabled()||origin.sourceRevision()!=1)throw new IllegalStateException("RESOURCE_HISTORY_COPY_STATE");
                    Files.writeString(root(server).resolve("history-copy.json"),JSON.writeValueAsString(Map.of("sourceUnchanged",true,"copy",candidate,"derivation",origin,"copiedNotExecuted",true)));historicalCopyVerified=true;
                }
                return;
            }
            if(ticks>1200)throw new IllegalStateException("RESOURCE_PACK_FIXTURE_TIMEOUT");
            server.getPlayerList().op(viewer.nameAndId());
            var metadata=file("resourcepack/pack.mcmeta","application/json","{\"pack\":{\"description\":\"MineAgent signed RESOURCE_RELOAD acceptance pack\",\"min_format\":[84,0],\"max_format\":[84,0]}}");
            var marker=file("resourcepack/assets/mineagent_runtime/native-resource-smoke.txt","text/plain",RESOURCE_TEXT);
            var entry=new RuntimeEntrypoint(metadata.path(),metadata.side(),metadata.sha256());
            var client=new NativeCompatibility.Target("26.1.2","neoforge","26.1.2.106","official",25,Map.of());
            var parsed=new ParsedRuntimePackage(PACKAGE_NAME,"1.0",RuntimePackageType.CONTENT,ActivationMode.RESOURCE_RELOAD,Map.of(),Set.of(),Map.of("resourcepack",entry),List.of(),List.of(metadata,marker),new NativeCompatibility(1,Map.of("CLIENT",client)));
            var permissions=MineAgentRuntimeServices.permissions(server);Set<PermissionAction> previous=permissions.trustedActions(viewer.getUUID());var temporary=new HashSet<>(previous);temporary.add(PermissionAction.MANAGE_PACKAGES);
            try{permissions.setTrustedActions(viewer.getUUID(),temporary);runtimePackage=ServerPackageRuntime.get(server).importOwned(viewer,UUID.randomUUID(),parsed);var library=ServerPackageRuntime.get(server).worldLibrary();if(!library.setEnabled(runtimePackage.packageId(),runtimePackage.revision(),true).accepted()||!library.setEnabled(runtimePackage.packageId(),2,false).accepted())throw new IllegalStateException("RESOURCE_HISTORY_FIXTURE");runtimePackage=library.get(runtimePackage.packageId()).orElseThrow();}
            finally{permissions.setTrustedActions(viewer.getUUID(),previous);}
            if(!permissions.trustedActions(viewer.getUUID()).equals(previous))throw new IllegalStateException("RESOURCE_PACK_FIXTURE_GRANT_NOT_RESTORED");
            Files.createDirectories(root(server));Files.writeString(root(server).resolve("fixture.json"),JSON.writeValueAsString(Map.of("package",runtimePackage,"viewer",viewer.getUUID(),"origin","EXPLICIT_IMPORT_SIGNED_BY_SERVER","temporaryManagePackagesRestored",true,"providerCalls",0,"systemInputInjected",false)));
        }catch(Exception error){failure=Objects.toString(error.getMessage(),error.getClass().getSimpleName());Files.createDirectories(root(server));Files.writeString(root(server).resolve("server-failure.json"),JSON.writeValueAsString(Map.of("error",error.toString(),"ticks",ticks)));}
    }
}
