package dev.mineagent.runtime.client.webui;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;
class ShellAssetCatalogTest {
    @Test void everyTrustedShellImportIsActuallyServed()throws Exception{
        assertTrue(ShellAssetCatalog.NAMES.contains("delivery-recovery.mjs"));ShellAssetCatalog.verifyImports(name->{try(var in=getClass().getResourceAsStream("/assets/mineagent_runtime/webui/"+name)){return in==null?null:new String(in.readAllBytes(),StandardCharsets.UTF_8);}});
    }
    @Test void missingOrUnservedImportsFailBeforeLaunchingAClient(){
        assertThrows(IllegalStateException.class,()->ShellAssetCatalog.verifyImports(name->null));
        assertThrows(IllegalStateException.class,()->ShellAssetCatalog.verifyImports(name->name.equals("shell.mjs")?"import {x} from './missing.mjs';":""));
    }
}
