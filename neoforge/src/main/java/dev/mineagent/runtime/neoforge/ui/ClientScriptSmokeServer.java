package dev.mineagent.runtime.neoforge.ui;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mineagent.runtime.api.packages.*;
import dev.mineagent.runtime.api.permission.PermissionAction;
import dev.mineagent.runtime.core.packages.RuntimePackageCanonicalizer;
import dev.mineagent.runtime.neoforge.MineAgentRuntimeServices;
import dev.mineagent.runtime.worker.generation.*;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Signed CLIENT Rhino package fixture; production download and local consent paths remain unchanged. */
@EventBusSubscriber(modid="mineagent_runtime")
public final class ClientScriptSmokeServer {
    public static final String PACKAGE_NAME="CLIENT Rhino · 正式本机信任验收";
    public static final String SOURCE="""
        client.status('STARTED:'+client.packageId());
        client.message('MineAgent CLIENT Rhino 已由本机明确启动');
        track(client.cleanupStatus('CLEANED'));
        var clientTicks=0;
        on('client.tick',function(tick){clientTicks++;if(clientTicks%10===0)client.status('TICK:'+clientTicks+':'+tick);});
        """;
    public static volatile RuntimePackage runtimePackage;public static volatile String failure;
    private static final ObjectMapper JSON=new ObjectMapper();private static int ticks;
    private ClientScriptSmokeServer(){}
    public static boolean enabled(){return Boolean.getBoolean("mineagent.clientScriptSmoke");}
    public static String stage(){return System.getProperty("mineagent.clientScriptStage","prepare");}
    private static Path root(net.minecraft.server.MinecraftServer server){return server.getServerDirectory().resolve("client-script-smoke");}
    @SubscribeEvent public static void tick(ServerTickEvent.Post event)throws Exception{
        if(!enabled()||runtimePackage!=null||failure!=null)return;var server=event.getServer();ticks++;var viewer=server.getPlayerList().getPlayers().stream().filter(p->!(p instanceof dev.mineagent.runtime.neoforge.body.MineAgentPlayer)).findFirst().orElse(null);if(viewer==null)return;
        try{
            Files.createDirectories(root(server));Path fixture=root(server).resolve("fixture.json");
            if(stage().equals("resume")){var prior=JSON.readTree(fixture.toFile());UUID id=UUID.fromString(prior.path("packageId").asText());runtimePackage=ServerPackageRuntime.get(server).worldLibrary().get(id).orElseThrow(()->new IllegalStateException("CLIENT_SCRIPT_FIXTURE_PACKAGE_MISSING"));if(!ServerPackageRuntime.get(server).ownedPackage(viewer.getUUID(),id,runtimePackage.revision()).isPresent())throw new IllegalStateException("CLIENT_SCRIPT_FIXTURE_OWNER_MISSING");return;}
            if(!stage().equals("prepare"))throw new IllegalArgumentException("CLIENT_SCRIPT_FIXTURE_STAGE");if(ticks>1200)throw new IllegalStateException("CLIENT_SCRIPT_FIXTURE_TIMEOUT");
            byte[] bytes=SOURCE.getBytes(StandardCharsets.UTF_8);String hash=RuntimePackageCanonicalizer.sha256(bytes),path="client/main.js";var file=new GeneratedFile(path,RuntimeResourceSide.CLIENT,"application/javascript",hash,bytes);var entry=new RuntimeEntrypoint(path,RuntimeResourceSide.CLIENT,hash);var compatibility=new NativeCompatibility(1,Map.of("CLIENT",new NativeCompatibility.Target("26.1.2","neoforge","26.1.2.106","official",25,Map.of())));
            var parsed=new ParsedRuntimePackage(PACKAGE_NAME,"1.0",RuntimePackageType.CONTENT,ActivationMode.HOT_RUNTIME,Map.of(),Set.of(),Map.of("client",entry),List.of(),List.of(file),compatibility);var permissions=MineAgentRuntimeServices.permissions(server);Set<PermissionAction> previous=permissions.trustedActions(viewer.getUUID());var temporary=new HashSet<>(previous);temporary.add(PermissionAction.MANAGE_PACKAGES);
            try{permissions.setTrustedActions(viewer.getUUID(),temporary);runtimePackage=ServerPackageRuntime.get(server).importOwned(viewer,UUID.randomUUID(),parsed);}finally{permissions.setTrustedActions(viewer.getUUID(),previous);}
            if(!permissions.trustedActions(viewer.getUUID()).equals(previous))throw new IllegalStateException("CLIENT_SCRIPT_FIXTURE_GRANT_NOT_RESTORED");Files.writeString(fixture,JSON.writeValueAsString(Map.of("packageId",runtimePackage.packageId(),"canonical",runtimePackage.canonicalSha256(),"viewer",viewer.getUUID(),"sourceHash",hash,"temporaryManagePackagesRestored",true,"providerCalls",0,"systemInputInjected",false)));
        }catch(Exception error){failure=Objects.toString(error.getMessage(),error.getClass().getSimpleName());Files.createDirectories(root(server));Files.writeString(root(server).resolve("server-failure-"+stage()+".json"),JSON.writeValueAsString(Map.of("error",error.toString(),"ticks",ticks)));}
    }
}
