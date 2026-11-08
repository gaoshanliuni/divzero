package dev.mineagent.runtime.core.packages;
import dev.mineagent.runtime.api.packages.*;
import dev.mineagent.runtime.core.crypto.IdentitySigner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Clock;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class RuntimeInstanceCacheTest {
    @TempDir Path root;
    @Test void persistedPartsOutliveCacheEvictionAndExceedFormerCountLimits()throws Exception{
        var world=UUID.randomUUID();var definition=UUID.randomUUID();var packageId=UUID.randomUUID();var id=ResourceIds.create();byte[] bytes="// fixture".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var resource=new RuntimeResourceRef("server/main.js",id,RuntimeResourceSide.SERVER,"application/javascript",bytes.length);
        var draft=new RuntimePackage(packageId,RuntimePackageType.CONTENT,"cache-fixture","1",ActivationMode.HOT_RUNTIME,Map.of(),Set.of("world.read"),Map.of("main",new RuntimeEntrypoint(resource.path(),resource.side(),id)),Map.of(definition,new RuntimeDefinition(definition,"visual",RuntimeDefinitionKind.OTHER,"main",Set.of(),Map.of(),1)),Map.of(resource.path(),resource),PackageOrigin.GENERATED,true,1,ResourceIds.create(),"",0);
        UUID instance;
        try(var signer=IdentitySigner.open(root.resolve("identity"));var library=RuntimePackageLibrary.open(root.resolve("library.db"),Clock.systemUTC(),signer.publicKeyEncoded())){
            assertTrue(library.install(PackageSignatures.signNew(draft,ignored->bytes,signer)).accepted());
            var parts=new LinkedHashMap<String,String>();for(int i=0;i<600;i++)parts.put("_object.part"+i,"saved-object-"+i);
            try(var service=RuntimeInstanceService.open(root.resolve("instances.db"),world,Clock.systemUTC(),library)){
                service.cacheBudget(()->1024);var created=service.create(packageId,definition,new RuntimeInstanceLocation("minecraft:overworld",0,64,0),parts);instance=created.instanceId();
                assertEquals(0,service.cacheObservation().get("entries"));assertEquals(600,service.get(instance).orElseThrow().state().size());
                assertEquals(instance,service.createIfAbsent(instance,packageId,definition,created.location(),Map.of()).instanceId());
                assertTrue(service.updateState(instance,1,parts).accepted());assertEquals("STALE_REVISION",service.updateState(instance,1,parts).errorCode());
            }
            try(var service=RuntimeInstanceService.open(root.resolve("instances.db"),world,Clock.systemUTC(),library)){
                assertEquals(0,service.cacheObservation().get("entries"));assertEquals(600,service.get(instance).orElseThrow().state().size());
            }
        }
    }
}
