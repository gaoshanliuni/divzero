package dev.mineagent.runtime.scripting;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManagedScriptRuntimeTest {
    public record ObjectEvent(String part) {}
    @Test void possibleExitStillHasRuntimeDeadlineAndKeepsPreviousValidScope()throws Exception{
        var id=UUID.randomUUID();var values=new ArrayList<Integer>();
        try(var runtime=new ManagedScriptRuntime(java.time.Duration.ofMillis(100))){
            runtime.load(id,1,Map.of("main","on('probe',function(){host.accept(7);});"),"main",Map.of("host",new Host(values,new TrackedResource())));
            org.junit.jupiter.api.Assertions.assertThrows(ScriptTimeoutException.class,()->runtime.load(id,2,Map.of("main","var stop=false;while(true){if(stop)break;}"),"main",Map.of()));
            runtime.fireTo(id,"probe",0);assertEquals(List.of(7),values);
        }
    }

    @Test void rebindingOneExistingHostValueKeepsHandlersTimersAndOtherScopesIntact()throws Exception{
        var a=UUID.randomUUID();var b=UUID.randomUUID();var values=new ArrayList<Integer>();var resource=new TrackedResource();var host=new Host(values,resource);
        try(var runtime=new ManagedScriptRuntime()){
            String code="host.accept(100);track(host.resource());on('observe',function(){host.accept(origin);});schedule(5,function(){host.accept(origin);});";
            runtime.load(a,1,Map.of("main",code),"main",Map.of("host",host,"origin",1));runtime.load(b,1,Map.of("main",code),"main",Map.of("host",host,"origin",2));
            runtime.replaceBinding(a,1,"origin",7);runtime.fireTo(a,"observe",0);runtime.fireTo(b,"observe",0);runtime.tick(5);
            assertEquals(List.of(100,100,7,2,7,2),values);
            assertFalse(resource.closed);
            org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,()->runtime.replaceBinding(a,2,"origin",9));
            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,()->runtime.replaceBinding(a,1,"on",9));
            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,()->runtime.replaceBinding(a,1,"newGlobal",9));
        }
    }
    @Test void javaStringEventPartsSupportStrictComparisonInThePinnedRuntime()throws Exception{
        var values=new ArrayList<Integer>();var id=UUID.randomUUID();
        try(var runtime=new ManagedScriptRuntime()){
            runtime.load(id,1,Map.of("main","var BASE='prism_pendulum_base';on('object.interact',function(event){if(event.part()!==BASE)return;host.accept(1);});"),"main",Map.of("host",new Host(values,new TrackedResource())));
            assertTrue(runtime.fireTo(id,"object.interact",new ObjectEvent("prism_pendant_left")));
            assertEquals(List.of(),values);
            assertTrue(runtime.fireTo(id,"object.interact",new ObjectEvent(new String("prism_pendulum_base"))));
            assertEquals(List.of(1),values);
        }
    }
    public static final class SlowInitialization { public void persist()throws InterruptedException{Thread.sleep(60);} }
    @Test void lifecycleHandlersShareOneTotalBudget()throws Exception{
        var id=UUID.randomUUID();try(var runtime=new ManagedScriptRuntime()){
            runtime.load(id,1,Map.of("main","on('instance.create',function(){host.persist();});on('instance.create',function(){host.persist();});"),"main",Map.of("host",new SlowInitialization()));
            assertFalse(runtime.fireLifecycle(id,"instance.create",0,java.time.Duration.ofMillis(90)));
            assertEquals("SCRIPT_TIMEOUT",runtime.failure(id).orElseThrow().code());
        }
    }
    @Test void initializationHasASeparateBoundedBudgetWithoutRelaxingPeriodicCallbacks()throws Exception{
        var id=UUID.randomUUID();try(var runtime=new ManagedScriptRuntime()){
            String work="host.persist();var x=0;for(var i=0;i<6000;i++){x+=i;}";
            runtime.load(id,1,Map.of("main","on('instance.create',function(){"+work+"});on('tick',function(){"+work+"});"),"main",Map.of("host",new SlowInitialization()));
            assertTrue(runtime.fireLifecycle(id,"instance.create",0,java.time.Duration.ofMillis(200)));assertTrue(runtime.isLoaded(id));
            runtime.fire("tick",1);assertFalse(runtime.isLoaded(id));assertEquals("SCRIPT_TIMEOUT",runtime.failure(id).orElseThrow().code());
        }
    }
    @Test void lifecycleBudgetStillStopsAnInfiniteLoopAndCannotBeUsedForOrdinaryEvents()throws Exception{
        var id=UUID.randomUUID();try(var runtime=new ManagedScriptRuntime()){
            runtime.load(id,1,Map.of("main","var stop=false;on('instance.create',function(){while(true){if(stop)break;}});"),"main",Map.of());
            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,()->runtime.fireLifecycle(id,"tick",1,java.time.Duration.ofMillis(200)));
            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,()->runtime.fireLifecycle(id,"instance.create",1,java.time.Duration.ofSeconds(1)));
            assertFalse(runtime.fireLifecycle(id,"instance.create",1,java.time.Duration.ofMillis(10)));
            assertEquals("SCRIPT_TIMEOUT",runtime.failure(id).orElseThrow().code());
        }
    }
    @Test void lifecycleDispatchTargetsOneInstanceAndPropagatesItsActualFailure()throws Exception{
        var a=UUID.randomUUID();var b=UUID.randomUUID();var values=new ArrayList<Integer>();var host=new Host(values,new TrackedResource());
        try(var runtime=new ManagedScriptRuntime()){
            runtime.load(a,1,Map.of("main","on('instance.create',function(){host.accept(1);}); on('instance.restore',function(){throw new Error('broken restore');});"),"main",Map.of("host",host));
            runtime.load(b,1,Map.of("main","on('instance.create',function(){host.accept(2);});"),"main",Map.of("host",host));
            assertTrue(runtime.fireTo(a,"instance.create",0));assertEquals(List.of(1),values);
            assertFalse(runtime.fireTo(a,"instance.restore",0));assertFalse(runtime.isLoaded(a));assertTrue(runtime.isLoaded(b));
            assertFalse(runtime.fireTo(UUID.randomUUID(),"instance.create",0));
        }
    }
    @Test void eachTopLevelCallbackGetsAFreshDeadlineInsteadOfExpiringAtPackageAge()throws Exception{
        var id=UUID.randomUUID();try(var runtime=new ManagedScriptRuntime(java.time.Duration.ofMillis(100))){
            runtime.load(id,1,Map.of("server/main.js","on('pulse',function(){var x=0;for(var i=0;i<3000;i++){x+=i;}});"),"server/main.js",Map.of());
            Thread.sleep(150);
            runtime.fire("pulse",1);assertTrue(runtime.isLoaded(id));
            Thread.sleep(150);
            runtime.fire("pulse",2);assertTrue(runtime.isLoaded(id));
        }
    }
    @Test void failuresKeepTheActualScopeRevisionEventAndSourceLocation()throws Exception{
        var id=UUID.randomUUID();try(var runtime=new ManagedScriptRuntime()){
            runtime.load(id,7,Map.of("server/main.js","on('broken',function(){\nthrow new Error('boom');\n});"),"server/main.js",Map.of());
            runtime.fire("broken",0);var failure=runtime.failure(id).orElseThrow();
            assertEquals(7,failure.revision());assertEquals("broken",failure.event());assertEquals("server/main.js",failure.source());assertEquals(2,failure.line());
        }
    }
    @Test void aSingleExcessiveCallbackStillTimesOutAndIsDisabled()throws Exception{
        var id=UUID.randomUUID();try(var runtime=new ManagedScriptRuntime(java.time.Duration.ofMillis(25))){
            runtime.load(id,1,Map.of("main","on('slow',function(){var x=0;for(var i=0;i<1000000000;i++){x+=i;}});"),"main",Map.of());
            runtime.fire("slow",0);assertFalse(runtime.isLoaded(id));assertEquals("SCRIPT_TIMEOUT",runtime.failure(id).orElseThrow().code());
        }
    }
    @Test
    void supportsModulesEventsSchedulingAndHotReloadCleanup() throws Exception {
        var values = new ArrayList<Integer>();
        var resource = new TrackedResource();
        var host = new Host(values, resource);
        UUID packageId = UUID.randomUUID();
        try (var runtime = new ManagedScriptRuntime()) {
            var loaded = runtime.load(packageId, 1, Map.of(
                    "math", "({base: 7})",
                    "main", """
                            var math = require('math');
                            track(host.resource());
                            on('ping', function(value) { host.accept(math.base + value); });
                            schedule(2, function() { host.accept(20); });
                            'loaded';
                            """), "main", Map.of("host", host));

            runtime.fire("ping", 3);
            runtime.tick(1);
            runtime.tick(2);

            assertEquals("loaded", loaded);
            assertEquals(List.of(10, 20), values);
            assertFalse(resource.closed);

            runtime.load(packageId, 2, Map.of("main", """
                    on('ping', function(value) { host.accept(100 + value); });
                    'reloaded';
                    """), "main", Map.of("host", host));
            assertTrue(resource.closed);
            runtime.fire("ping", 1);
            assertEquals(List.of(10, 20, 101), values);
        }
    }

    @Test
    void isolatesAndUnloadsPackageWhoseEventHandlerFails() throws Exception {
        UUID packageId = UUID.randomUUID();
        try (var runtime = new ManagedScriptRuntime()) {
            runtime.load(packageId, 1, Map.of("main", """
                    on('broken', function() { throw new Error('boom'); });
                    'ready';
                    """), "main", Map.of());

            runtime.fire("broken", 1);

            assertFalse(runtime.isLoaded(packageId));
        }
    }

    public static final class Host {
        private final List<Integer> values;
        private final TrackedResource resource;

        private Host(List<Integer> values, TrackedResource resource) {
            this.values = values;
            this.resource = resource;
        }

        public void accept(int value) {
            values.add(value);
        }

        public TrackedResource resource() {
            return resource;
        }
    }

    public static final class TrackedResource implements AutoCloseable {
        private boolean closed;

        @Override
        public void close() {
            closed = true;
        }
    }
}
