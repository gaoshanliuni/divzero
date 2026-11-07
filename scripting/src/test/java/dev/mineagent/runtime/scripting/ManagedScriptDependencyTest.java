package dev.mineagent.runtime.scripting;

import dev.mineagent.runtime.core.packages.*;
import dev.mineagent.runtime.api.packages.ClientPackageBridge;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real pinned Rhino engine, isolated from Minecraft/Provider/input devices. */
class ManagedScriptDependencyTest {
    private static final String ENTRY="main.js";
    private final Map<String,String> ids=new HashMap<>();private String hash(String source,Map<UUID,String> required){return ids.computeIfAbsent(source+new TreeMap<>(required),key->dev.mineagent.runtime.api.packages.ResourceIds.create());}
    private Object load(ManagedScriptRuntime runtime,UUID scope,ScriptDependencyGraph graph,String source,Object host)throws Exception {
        runtime.pin(scope,graph);return runtime.load(scope,1,Map.of(ENTRY,source),ENTRY,host==null?Map.of():Map.of("host",host),()->true,0,new ManagedScriptRuntime.Link(graph,hash(source,graph.required()),ENTRY,Map.of(ENTRY,source)));
    }
    private ScriptDependencyGraph graph(UUID root,UUID dependency,UUID scope,String source)throws Exception {
        var node=new ScriptDependencyGraph.Node(dependency,"1.0.0",1,"a".repeat(64),scope,3,hash(source,Map.of()),ENTRY,Map.of());
        return new ScriptDependencyGraph(1,root,Map.of(dependency,"1.0.0"),List.of(node));
    }
    @Test void sharesOneRunningDependencyButCopiesDataAndBlocksWrongStopOrder()throws Exception {
        UUID b=UUID.randomUUID(),bs=UUID.randomUUID(),a=UUID.randomUUID(),as=UUID.randomUUID(),c=UUID.randomUUID(),cs=UUID.randomUUID();
        String source="var n=0;({add:function(v){n+=v.amount;v.amount=99;return {n:n,changed:v};}});";
        try(var runtime=new ManagedScriptRuntime(Duration.ofSeconds(1))){
            load(runtime,bs,ScriptDependencyGraph.empty(b),source,null);
            String call="var original={amount:2};var result=callPackage('"+b+"','add',[original]);if(original.amount!==2||result.changed.amount!==99)throw new Error('shared input');result.n;";
            assertEquals(2,((Number)load(runtime,as,graph(a,b,bs,source),call,null)).intValue());
            assertEquals(4,((Number)load(runtime,cs,graph(c,b,bs,source),call,null)).intValue());
            assertEquals(2,runtime.consumers(bs).size());assertThrows(IllegalStateException.class,()->runtime.unload(bs));
            runtime.unload(as);runtime.unload(cs);assertTrue(runtime.unload(bs));
        }
    }
    @Test void suspensionRejectsOldConsumersWithoutReevaluatingTheDependency()throws Exception {
        UUID b=UUID.randomUUID(),bs=UUID.randomUUID(),a=UUID.randomUUID(),as=UUID.randomUUID();String source="({read:function(){return 7;}});";
        try(var runtime=new ManagedScriptRuntime(Duration.ofSeconds(1))){
            load(runtime,bs,ScriptDependencyGraph.empty(b),source,null);
            load(runtime,as,graph(a,b,bs,source),"on('use',function(){callPackage('"+b+"','read',[]);});",null);
            runtime.suspend(bs);assertTrue(runtime.isSuspended(as));assertFalse(runtime.fireTo(as,"use",0));runtime.unload(as);runtime.unload(bs);
        }
    }
    public static final class Waiter { public void pause(int millis)throws InterruptedException{Thread.sleep(millis);} }
    @Test void dependencyCallDoesNotResetTheCallersDeadline()throws Exception {
        UUID b=UUID.randomUUID(),bs=UUID.randomUUID(),a=UUID.randomUUID(),as=UUID.randomUUID();String source="({waitFor:function(){host.pause(80);return 1;}});";
        try(var runtime=new ManagedScriptRuntime(Duration.ofMillis(120))){
            load(runtime,bs,ScriptDependencyGraph.empty(b),source,new Waiter());
            load(runtime,as,graph(a,b,bs,source),"on('use',function(){host.pause(80);callPackage('"+b+"','waitFor',[]);});",new Waiter());
            assertFalse(runtime.fireTo(as,"use",0));assertEquals("SCRIPT_TIMEOUT",runtime.failure(as).orElseThrow().code());assertTrue(runtime.isLoaded(bs));assertTrue(runtime.consumers(bs).isEmpty());
        }
    }
    @Test void rhinoCanUseNeutralExternalBridgeWithoutReceivingTheJavaObject()throws Exception {UUID root=UUID.randomUUID(),dependency=UUID.randomUUID();String source="var input={amount:2};var value=callPackage('"+dependency+"','add',[input]);if(input.amount!==2)value=-1;value;";ClientPackageBridge bridge=(id,member,args)->{assertEquals(dependency,id);assertEquals("add",member);@SuppressWarnings("unchecked")var value=(Map<String,Object>)args.getFirst();return ((Number)value.get("amount")).doubleValue()+3;};try(var runtime=new ManagedScriptRuntime(Duration.ofSeconds(1))){var graph=ScriptDependencyGraph.empty(root);runtime.pin(root,graph);assertEquals(5D,((Number)runtime.load(root,1,Map.of(ENTRY,source),ENTRY,Map.of("packages",bridge),()->true,0,new ManagedScriptRuntime.Link(graph,hash(source,Map.of()),ENTRY,Map.of(ENTRY,source)))).doubleValue());}}
    @Test void javaCanCallExistingRhinoExportThroughPortableData()throws Exception {UUID dependency=UUID.randomUUID();String source="({add:function(v){v.amount=99;return {answer:v.amount+1};}});";try(var runtime=new ManagedScriptRuntime(Duration.ofSeconds(1))){load(runtime,dependency,ScriptDependencyGraph.empty(dependency),source,null);var input=new LinkedHashMap<String,Object>();input.put("amount",2);@SuppressWarnings("unchecked")var result=(Map<String,Object>)runtime.callExternal(dependency,"add",List.of(input));assertEquals(100D,result.get("answer"));assertEquals(2,input.get("amount"));}}
}
