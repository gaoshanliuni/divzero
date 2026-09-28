package dev.mineagent.runtime.worker.generation;

import com.fasterxml.jackson.databind.*;
import dev.mineagent.runtime.api.packages.*;
import dev.mineagent.runtime.core.packages.*;
import dev.mineagent.runtime.core.content.ContentAddressedStore;
import dev.mineagent.runtime.core.crypto.IdentitySigner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class NativePackageMigrationTest {
    @TempDir Path root;
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final String NATIVE="""
        {"format":"divzero-native-ui/1","view":{"id":"shop","title":"Shop","surface":"SCREEN","root":{"id":"root","type":"column","children":[{"id":"search","type":"input","bind":"query"}]},"data":{"query":""}},"reads":{},"actions":{}}
        """;
    @Test void newPackagesRejectBrowserEntrypointsAndValidateNativeTrees()throws Exception{
        var parser=new RuntimePackageOutputParser();var accepted=parser.parse(output("ui/index.json","application/json",NATIVE));assertEquals("ui/index.json",accepted.entrypoints().get("ui").path());
        assertEquals("NATIVE_UI_REWRITE_REQUIRED",assertThrows(PackageOutputException.class,()->parser.parse(output("ui/index.html","text/html","<button>Buy</button>"))).code());
        assertThrows(PackageOutputException.class,()->parser.parse(output("ui/index.json","application/json",NATIVE.replace("\"column\"","\"browser\""))));
    }
    @Test void explicitEntryMigrationRetainsGameplayAndOriginalSources()throws Exception{
        var store=new ContentAddressedStore(root.resolve("content"));var signer=IdentitySigner.open(root.resolve("identity"));var resources=new LinkedHashMap<String,RuntimeResourceRef>();
        for(String path:List.of("ui/index.html","server/main.js")){byte[] bytes=(path.endsWith(".html")?"<input id='search'>":"'unchanged';").getBytes(StandardCharsets.UTF_8);var blob=store.put(bytes);resources.put(path,new RuntimeResourceRef(path,blob.sha256(),path.startsWith("ui/")?RuntimeResourceSide.CLIENT:RuntimeResourceSide.SERVER,path.endsWith("html")?"text/html":"text/javascript",bytes.length));}
        var entries=Map.of("ui",new RuntimeEntrypoint("ui/index.html",RuntimeResourceSide.CLIENT,resources.get("ui/index.html").sha256()),"server",new RuntimeEntrypoint("server/main.js",RuntimeResourceSide.SERVER,resources.get("server/main.js").sha256()));
        var base=new RuntimePackage(UUID.randomUUID(),RuntimePackageType.CONTENT,"Legacy","1.0.0",ActivationMode.HOT_RUNTIME,Map.of(),Set.of("RUN_CODE"),entries,Map.of(),resources,PackageOrigin.GENERATED,true,1,"0".repeat(64),"",0);
        var files=List.of(Map.of("path","ui/index.json","content",NATIVE,"encoding","utf8"));
        var next=UiPackagePatchBuilder.prepare(base,JSON.writeValueAsString(Map.of("files",files,"entries",Map.of("ui","ui/index.json"))),store,signer);
        assertEquals("ui/index.json",next.entrypoints().get("ui").path());assertEquals(base.resources().get("ui/index.html"),next.resources().get("ui/index.html"));assertEquals(base.resources().get("server/main.js"),next.resources().get("server/main.js"));assertEquals(base.permissions(),next.permissions());assertEquals(2,next.revision());
        assertThrows(IllegalArgumentException.class,()->UiPackagePatchBuilder.prepare(base,JSON.writeValueAsString(Map.of("files",files,"entries",Map.of("server","ui/index.json"))),store,signer));
    }
    private static String output(String path,String media,String body)throws Exception{String hash=RuntimePackageCanonicalizer.sha256(body);return JSON.writeValueAsString(Map.of("manifest",Map.of("name","Native","version","1.0.0","type","CONTENT","activationMode","HOT_RUNTIME","permissions",List.of(),"dependencies",Map.of(),"definitions",List.of(),"entrypoints",Map.of("ui",Map.of("path",path,"side","CLIENT","sha256",hash))),"files",List.of(Map.of("path",path,"side","CLIENT","mediaType",media,"encoding","utf8","content",body,"sha256",hash))));}
}
