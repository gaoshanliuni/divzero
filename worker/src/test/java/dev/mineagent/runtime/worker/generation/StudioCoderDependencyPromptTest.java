package dev.mineagent.runtime.worker.generation;

import dev.mineagent.runtime.api.worker.WorkerEnvelope;
import dev.mineagent.runtime.core.compile.NativeCoderContext;
import dev.mineagent.runtime.core.content.ContentAddressedStore;
import dev.mineagent.runtime.core.packages.*;
import dev.mineagent.runtime.scripting.studio.StudioCoderJournal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class StudioCoderDependencyPromptTest {
    @TempDir Path temporary;
    @Test void promptUsesTheFullFrozenSourceAndCandidateKeepsTheDependencyHash()throws Exception {
        var store=new ContentAddressedStore(temporary);UUID root=UUID.randomUUID(),dependency=UUID.randomUUID();
        String body="var privateCounter=0;({add:function(x){privateCounter+=x;return privateCounter;}});";
        var ref=CodeDraftSources.store(store,body);
        var node=new ScriptDependencyGraph.Node(dependency,"1.0.0",1,"a".repeat(64),UUID.randomUUID(),3,dev.mineagent.runtime.api.packages.ResourceIds.create(),"main.js",Map.of(),Map.of("main.js",ref));
        var context=new ScriptDependencySources(new ScriptDependencyGraph(1,root,Map.of(dependency,"1.0.0"),List.of(node)),Map.of(dependency,Map.of("main.js",ref)));
        var snapshot=context.capture(store,()->true);String text=context.read(snapshot,store);
        var env=new NativeCompatibilityPolicy.Environment("26.1.2","neoforge","26.1.2.106","mojang",25,"UNIT_TEST_NOT_NATIVE",Map.of());
        var in=new StudioCoderJournal.Input(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),1,1,root,0,"",null,0,dev.mineagent.runtime.api.packages.ResourceIds.create(),0,"main.js","call the existing add function",1,"",0,env,"",0,null,false,Map.of(),null,true,context,true);
        assertThrows(IllegalStateException.class,()->StudioCoderResult.promptContext(in,"","",""));
        String prompt=StudioCoderResult.promptContext(in,"","","",text);assertTrue(prompt.contains("privateCounter"));assertTrue(prompt.contains(dependency.toString()));assertTrue(prompt.contains("callPackage"));assertTrue(prompt.contains("NOT_LIVE_EXPORTS"));assertFalse(prompt.contains("dependencies-v2:"),"The validated exact graph receipt must not be duplicated in model context");
        var changed=new com.fasterxml.jackson.databind.ObjectMapper().readTree(text);((com.fasterxml.jackson.databind.node.ObjectNode)changed).put("graphHash","b".repeat(64));assertThrows(IllegalStateException.class,()->StudioCoderResult.promptContext(in,"","","",changed.toString()));
        String source="callPackage('"+dependency+"','add',[1]);";UUID request=UUID.randomUUID();
        String raw=new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(Map.of("source",source));
        var result=StudioCoderResult.prepare(request,1,false,new WorkerEnvelope(1,request,"model.result",Map.of("text",raw)),store).withScriptDependencySources(snapshot);
        assertEquals("ACCEPTED",result.attempt().state());assertEquals(snapshot,result.uncertain("STUDIO_CODER_TRANSPORT_UNKNOWN").attempt().scriptDependencySources());
        assertEquals(in.dependencies(),result.attempt().sourceSet(in).dependencies());assertEquals(result.attempt().files(in.path()),result.attempt().sourceSet(in).files());assertEquals(result.attempt().candidateHash(in),result.attempt().sourceSet(in).version());
        String invalid=new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(Map.of("source",source,"dependencies",Map.of()));
        var rejected=StudioCoderResult.prepare(request,1,false,new WorkerEnvelope(1,request,"model.result",Map.of("text",invalid)),store);assertEquals("REJECTED",rejected.attempt().state());assertTrue(rejected.retryable());
    }

    @Test void selectedNativeDeclarationsAreExplicitBoundedContextAndSurviveAttemptReceipts()throws Exception {
        var env=new NativeCompatibilityPolicy.Environment("26.1.2","neoforge","26.1.2.106","official",25,"UNIT_TEST_NOT_NATIVE",Map.of());
        var selection=new NativeCoderContext("c".repeat(64),env.fingerprint(),"SERVER","RAW_MODULE_RESOURCES_NOT_LIVE_TRANSFORMS",List.of(new NativeCoderContext.Type("minecraft","a".repeat(64),"net.minecraft.server.MinecraftServer")));
        var in=new StudioCoderJournal.Input(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),1,1,UUID.randomUUID(),0,"",null,0,dev.mineagent.runtime.api.packages.ResourceIds.create(),0,"Extension.java","use the selected server API",1,"",0,env,"",0,null,false,Map.of(),null,false,null,false,selection,true);
        String nativeJson=new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(Map.of("schema",1,"kind","SELECTED_RAW_CLASS_DECLARATIONS_NO_BODIES_OR_INITIALIZATION","selection",selection,"classes",List.of(Map.of("module","minecraft","symbols",Map.of("name","net.minecraft.server.MinecraftServer","members",List.of(Map.of("kind","METHOD","name","getPlayerList","descriptor","()Lnet/minecraft/server/players/PlayerList;")))))));
        assertThrows(IllegalStateException.class,()->StudioCoderResult.promptContext(in,"","","",""));
        String prompt=StudioCoderResult.promptContext(in,"","","","",nativeJson);assertTrue(prompt.contains("getPlayerList"));assertTrue(prompt.contains(selection.snapshot()));assertTrue(prompt.contains("physicalSide"));
        UUID request=UUID.randomUUID();String raw="{\"source\":\"public class Extension implements dev.mineagent.runtime.scripting.javaext.RuntimeExtension { public Object start(java.util.Map<String,Object> b){ return ((net.minecraft.server.MinecraftServer)b.get(\\\"server\\\")).getPlayerList(); } }\"}";
        var result=StudioCoderResult.prepare(request,1,true,new WorkerEnvelope(1,request,"model.result",Map.of("text",raw)),new ContentAddressedStore(temporary));
        var reference=new NativeCoderContext.Snapshot(selection.fingerprint(),"d".repeat(64),nativeJson.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);result=result.withNativeContext(reference);
        assertEquals(reference,result.attempt().nativeContext());assertEquals(reference,result.uncertain("STUDIO_CODER_TRANSPORT_UNKNOWN").attempt().nativeContext());
        var changed=nativeJson.replace(selection.snapshot(),"e".repeat(64));assertThrows(IllegalStateException.class,()->StudioCoderResult.promptContext(in,"","","","",changed));
    }
}
