package dev.mineagent.runtime.worker.generation;

import dev.mineagent.runtime.api.packages.*;
import dev.mineagent.runtime.core.content.ContentAddressedStore;
import dev.mineagent.runtime.core.crypto.IdentitySigner;
import dev.mineagent.runtime.core.packages.PackageSignatures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SignedResourcePromptTest {
    @TempDir Path root;
    @Test void readsAssignedResourceIdsAndRejectsEqualLengthSubstitution()throws Exception{
        var store=new ContentAddressedStore(root.resolve("content"));byte[] bytes="const value = 1;".getBytes(StandardCharsets.UTF_8);var stored=store.put(bytes);var ref=new RuntimeResourceRef("server/main.js",stored.sha256(),RuntimeResourceSide.SERVER,"application/javascript",bytes.length);
        var def=new RuntimeDefinition(UUID.randomUUID(),"fixture",RuntimeDefinitionKind.RULE,"server",Set.of(ref.path()),Map.of(),1);
        var unsigned=new RuntimePackage(UUID.randomUUID(),RuntimePackageType.CONTENT,"fixture","1",ActivationMode.HOT_RUNTIME,Map.of(),Set.of("RUN_CODE"),Map.of("server",new RuntimeEntrypoint(ref.path(),ref.side(),ref.sha256())),Map.of(def.definitionId(),def),Map.of(ref.path(),ref),PackageOrigin.GENERATED,false,1,ResourceIds.create(),"",0);
        try(var signer=IdentitySigner.open(root.resolve("signer"))){
            var pkg=PackageSignatures.signNew(unsigned,store::read,signer);
            assertTrue(WorldPatchPrompt.build(pkg,"change value",store::read).contains("const value = 1;"));
            byte[] other="const value = 2;".getBytes(StandardCharsets.UTF_8);
            assertThrows(SecurityException.class,()->WorldPatchPrompt.build(pkg,"change value",id->other));
        }
    }
}
