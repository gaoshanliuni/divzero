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

/** Same-language CLIENT dependency fixtures; every package still uses normal signed import and local approval. */
@EventBusSubscriber(modid="mineagent_runtime")
public final class ClientDependencySmokeServer {
    public static final List<String> NAMES=List.of("Rhino Dependency","Rhino Consumer","Java Base Dependency","Java Middle Dependency","Java Consumer","Rhino to Java Consumer","Java to Rhino Consumer");public static final Map<String,RuntimePackage> PACKAGES=new LinkedHashMap<>();public static volatile String failure;private static final ObjectMapper JSON=new ObjectMapper();private static int ticks;
    private ClientDependencySmokeServer(){}public static boolean enabled(){return Boolean.getBoolean("mineagent.clientDependencySmoke");}private static Path root(net.minecraft.server.MinecraftServer s){return s.getServerDirectory().resolve("client-dependency-smoke");}
    private static GeneratedFile file(String path,String source)throws Exception{byte[] bytes=source.getBytes(StandardCharsets.UTF_8);return new GeneratedFile(path,RuntimeResourceSide.CLIENT,path.endsWith(".java")?"text/x-java-source":"application/javascript",RuntimePackageCanonicalizer.sha256(bytes),bytes);}
    private static NativeCompatibility compatibility(){return new NativeCompatibility(1,Map.of("CLIENT",new NativeCompatibility.Target("26.1.2","neoforge","26.1.2.106","official",25,Map.of())));}
    private static RuntimePackage install(net.minecraft.server.MinecraftServer server,net.minecraft.server.level.ServerPlayer viewer,UUID id,String name,String version,String entryId,GeneratedFile file,Map<UUID,String> dependencies)throws Exception{var entry=new RuntimeEntrypoint(file.path(),file.side(),file.sha256());return ServerPackageRuntime.get(server).importOwned(viewer,id,new ParsedRuntimePackage(name,version,RuntimePackageType.EXTENSION,ActivationMode.HOT_RUNTIME,dependencies,Set.of(),Map.of(entryId,entry),List.of(),List.of(file),compatibility()));}
    @SubscribeEvent public static void tick(ServerTickEvent.Post event)throws Exception{if(!enabled()||!PACKAGES.isEmpty()||failure!=null)return;var server=event.getServer();ticks++;var viewer=server.getPlayerList().getPlayers().stream().filter(p->!(p instanceof dev.mineagent.runtime.neoforge.body.MineAgentPlayer)).findFirst().orElse(null);if(viewer==null)return;try{if(ticks>1200)throw new IllegalStateException("CLIENT_DEPENDENCY_FIXTURE_TIMEOUT");UUID rhinoDep=UUID.randomUUID(),rhinoConsumer=UUID.randomUUID(),javaBase=UUID.randomUUID(),javaMid=UUID.randomUUID(),javaConsumer=UUID.randomUUID();var permissions=MineAgentRuntimeServices.permissions(server);Set<PermissionAction> previous=permissions.trustedActions(viewer.getUUID());var temporary=new HashSet<>(previous);temporary.add(PermissionAction.MANAGE_PACKAGES);try{permissions.setTrustedActions(viewer.getUUID(),temporary);
            PACKAGES.put(NAMES.get(0),install(server,viewer,rhinoDep,NAMES.get(0),"1.0", "client",file("client/rhino-dependency.js","client.status('RHINO_DEP_READY');track(client.cleanupStatus('RHINO_DEP_CLEANED'));({value:function(value){return 'RHINO_DEP:'+value;}});"),Map.of()));
            PACKAGES.put(NAMES.get(1),install(server,viewer,rhinoConsumer,NAMES.get(1),"1.0","client",file("client/rhino-consumer.js","var result=callPackage('"+rhinoDep+"','value',['OK']);client.status('RHINO_CONSUMER:'+result);track(client.cleanupStatus('RHINO_CONSUMER_CLEANED'));result;"),Map.of(rhinoDep,"1.0")));
            String base="""
                package dev.mineagent.dep; import java.util.*; import dev.mineagent.runtime.api.packages.*; import dev.mineagent.runtime.scripting.javaext.ClientRuntimeExtension;
                public final class BaseExtension implements ClientRuntimeExtension,ClientPackageExports { public Object start(Map<String,Object> b){var c=(ClientRuntimeHost)b.get("client");c.status("JAVA_BASE_READY");c.cleanupStatus("JAVA_BASE_CLEANED");return this;} public String value(){return "JAVA_BASE";} public Object call(String name,List<?> args){if(!name.equals("value"))throw new IllegalArgumentException("EXPORT");return "JAVA_BASE:"+args.getFirst();} }
                """;
            PACKAGES.put(NAMES.get(2),install(server,viewer,javaBase,NAMES.get(2),"1.0","client_java",file("client/dev/mineagent/dep/BaseExtension.java",base),Map.of()));
            String mid="""
                package dev.mineagent.mid; import java.util.*; import dev.mineagent.dep.BaseExtension; import dev.mineagent.runtime.api.packages.ClientRuntimeHost; import dev.mineagent.runtime.scripting.javaext.*;
                public final class MidExtension implements ClientRuntimeExtension { private String value; public Object start(Map<String,Object> b){var c=(ClientRuntimeHost)b.get("client"); var d=(Map<UUID,ClientRuntimeExtension>)b.get("dependencies"); value="JAVA_MID:"+((BaseExtension)d.values().iterator().next()).value();c.status(value);c.cleanupStatus("JAVA_MID_CLEANED");return this;} public String value(){return value;} }
                """;
            PACKAGES.put(NAMES.get(3),install(server,viewer,javaMid,NAMES.get(3),"1.0","client_java",file("client/dev/mineagent/mid/MidExtension.java",mid),Map.of(javaBase,"1.0")));
            String consumer="""
                package dev.mineagent.consumer; import java.util.*; import dev.mineagent.mid.MidExtension; import dev.mineagent.runtime.api.packages.ClientRuntimeHost; import dev.mineagent.runtime.scripting.javaext.*;
                public final class ConsumerExtension implements ClientRuntimeExtension { public Object start(Map<String,Object> b){var c=(ClientRuntimeHost)b.get("client");var d=(Map<UUID,ClientRuntimeExtension>)b.get("dependencies");String value="JAVA_CONSUMER:"+((MidExtension)d.values().iterator().next()).value();c.status(value);c.cleanupStatus("JAVA_CONSUMER_CLEANED");return value;} }
                """;
            PACKAGES.put(NAMES.get(4),install(server,viewer,javaConsumer,NAMES.get(4),"1.0","client_java",file("client/dev/mineagent/consumer/ConsumerExtension.java",consumer),Map.of(javaMid,"1.0")));
            PACKAGES.put(NAMES.get(5),install(server,viewer,UUID.randomUUID(),NAMES.get(5),"1.0","client",file("client/rhino-to-java.js","var result=callPackage('"+javaBase+"','value',['RHINO']);client.status('RHINO_TO_JAVA:'+result);track(client.cleanupStatus('RHINO_TO_JAVA_CLEANED'));result;"),Map.of(javaBase,"1.0")));
            String javaToRhino="""
                package dev.mineagent.cross; import java.util.*; import dev.mineagent.runtime.api.packages.*; import dev.mineagent.runtime.scripting.javaext.ClientRuntimeExtension;
                public final class JavaToRhinoExtension implements ClientRuntimeExtension { public Object start(Map<String,Object> b)throws Exception{var c=(ClientRuntimeHost)b.get("client");var p=(ClientPackageBridge)b.get("packages");Object result=p.call(UUID.fromString("%s"),"value",List.of("JAVA"));String value="JAVA_TO_RHINO:"+result;c.status(value);c.cleanupStatus("JAVA_TO_RHINO_CLEANED");return value;} }
                """.formatted(rhinoDep);
            PACKAGES.put(NAMES.get(6),install(server,viewer,UUID.randomUUID(),NAMES.get(6),"1.0","client_java",file("client/dev/mineagent/cross/JavaToRhinoExtension.java",javaToRhino),Map.of(rhinoDep,"1.0")));
        }finally{permissions.setTrustedActions(viewer.getUUID(),previous);}if(!permissions.trustedActions(viewer.getUUID()).equals(previous))throw new IllegalStateException("CLIENT_DEPENDENCY_GRANT_NOT_RESTORED");Files.createDirectories(root(server));Files.writeString(root(server).resolve("fixture.json"),JSON.writeValueAsString(Map.of("packages",PACKAGES,"viewer",viewer.getUUID(),"providerCalls",0,"systemInputInjected",false)));}catch(Exception error){failure=Objects.toString(error.getMessage(),error.getClass().getSimpleName());Files.createDirectories(root(server));Files.writeString(root(server).resolve("server-failure.json"),JSON.writeValueAsString(Map.of("error",error.toString(),"ticks",ticks)));}}
}
