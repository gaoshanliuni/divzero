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
    @Test void creationHandlerDiscoveryDoesNotRequireARestoreSubscription()throws Exception{
        var id=UUID.randomUUID();var values=new ArrayList<Integer>();try(var runtime=new ManagedScriptRuntime()){
            runtime.load(id,1,Map.of("main","on('instance.create',function(){host.accept(1);});"),"main",Map.of("host",new Host(values,new TrackedResource())));
            assertTrue(runtime.hasHandlers(id,"instance.create"));assertFalse(runtime.hasHandlers(id,"instance.restore"));
            assertTrue(runtime.fireLifecycle(id,"instance.create",0,java.time.Duration.ofMillis(100)));assertEquals(List.of(1),values);
            runtime.unload(id);assertFalse(runtime.hasHandlers(id,"instance.create"));
        }
    }
    private static dev.mineagent.runtime.core.config.ScriptResourceBudget budget(int callbacks,int pending){
        return new dev.mineagent.runtime.core.config.ScriptResourceBudget(1,1,1,1,1,callbacks,1_000_000_000L,pending);
    }
    @Test void retainsCallbacksFairlyAcrossTicksAndDoesNotApplyFormerPackageCounts()throws Exception{
        var values=new ArrayList<Integer>();var host=new Host(values,new TrackedResource());
        try(var runtime=new ManagedScriptRuntime(java.time.Duration.ofSeconds(1))){
            runtime.configure(budget(2,1000));
            for(int i=0;i<140;i++)runtime.load(UUID.randomUUID(),1,Map.of("main","on('probe',function(){host.accept("+i+");});"),"main",Map.of("host",host));
            runtime.fire("probe",0);assertEquals(2,values.size());
            for(int tick=1;tick<70;tick++)runtime.tick(tick);
            assertEquals(java.util.stream.IntStream.range(0,140).boxed().toList(),values);
            assertEquals(0L,runtime.resourceObservation().get("pendingCallbacks"));
            assertTrue(runtime.resourceObservation().get("softBudgetWarnings").toString().contains("packages"));
        }
    }
    @Test void timerAndEventQueuesAlternateAndStaleCallbacksNeverReachReloadedScope()throws Exception{
        var values=new ArrayList<Integer>();var id=UUID.randomUUID();var host=new Host(values,new TrackedResource());
        try(var runtime=new ManagedScriptRuntime(java.time.Duration.ofSeconds(1))){
            runtime.configure(budget(1,100));
            runtime.load(id,1,Map.of("main","on('probe',function(){host.accept(1);});on('probe',function(){host.accept(2);});schedule(1,function(){host.accept(3);});"),"main",Map.of("host",host));
            runtime.fire("probe",0);runtime.tick(1);runtime.tick(2);assertEquals(List.of(1,3,2),values);
            runtime.fire("probe",0);runtime.load(id,2,Map.of("main","on('probe',function(){host.accept(9);});"),"main",Map.of("host",host));
            runtime.tick(3);assertEquals(List.of(1,3,2),values);runtime.fire("probe",0);assertEquals(List.of(1,3,2,9),values);
        }
    }
    @Test void backpressureIsAtomicAndTickCoalescingDoesNotGrowTheQueue()throws Exception{
        var values=new ArrayList<Integer>();var id=UUID.randomUUID();var host=new Host(values,new TrackedResource());
        try(var runtime=new ManagedScriptRuntime(java.time.Duration.ofSeconds(1))){
            runtime.configure(budget(0,2));
            runtime.load(id,1,Map.of("main","on('probe',function(v){host.accept(v);});on('tick',function(v){host.accept(v);});"),"main",Map.of("host",host));
            runtime.fire("probe",7);for(int i=1;i<=20;i++)runtime.fire("tick",i);
            var error=org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,()->runtime.fire("probe",8));
            assertEquals("SCRIPT_QUEUE_PRESSURE_NOT_ENQUEUED",error.getMessage());assertEquals(2L,runtime.resourceObservation().get("pendingCallbacks"));
            runtime.configure(budget(10,2));runtime.tick(21);assertEquals(List.of(7,20),values);
        }
    }
    @Test void scriptsCanReleaseResourcesTimersHandlersAndModuleCaches()throws Exception{
        var values=new ArrayList<Integer>();var resource=new TrackedResource();
        try(var runtime=new ManagedScriptRuntime(java.time.Duration.ofSeconds(1))){
            runtime.configure(budget(1000,1000));
            runtime.load(UUID.randomUUID(),1,Map.of("helper","host.accept(2);({ok:true});","main","""
                var r=track(host.resource());releaseResource(r);
                var f=on('probe',function(){host.accept(99);});off('probe',f);
                var timer=schedule(1,function(){host.accept(98);});cancelSchedule(timer);
                require('helper');unloadModule('helper');require('helper');
                for(var i=0;i<140;i++){on('noop',function(){});schedule(2,function(){host.accept(1);});}
                """),"main",Map.of("host",new Host(values,resource)));
            assertTrue(resource.closed);runtime.fire("probe",0);runtime.tick(1);assertEquals(List.of(2,2),values);
            runtime.tick(2);assertEquals(142,values.size());
        }
    }
    @Test void oneFailingScriptDoesNotStarveAnotherAndAuthorityIsRecheckedAfterDeferral()throws Exception{
        var values=new ArrayList<Integer>();var allowed=new java.util.concurrent.atomic.AtomicBoolean(true);
        try(var runtime=new ManagedScriptRuntime(java.time.Duration.ofSeconds(1))){
            runtime.configure(budget(1,100));
            var a=UUID.randomUUID();var b=UUID.randomUUID();var c=UUID.randomUUID();
            runtime.load(a,1,Map.of("main","on('probe',function(){throw new Error('failure');});"),"main",Map.of());
            runtime.load(b,1,Map.of("main","on('probe',function(){host.accept(2);});"),"main",Map.of("host",new Host(values,new TrackedResource())),allowed::get,0);
            runtime.load(c,1,Map.of("main","on('probe',function(){host.accept(3);});"),"main",Map.of("host",new Host(values,new TrackedResource())));
            runtime.fire("probe",0);allowed.set(false);runtime.tick(1);runtime.tick(2);
            assertEquals(List.of(3),values);assertFalse(runtime.isLoaded(a));assertFalse(runtime.isLoaded(b));assertTrue(runtime.isLoaded(c));
        }
    }
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
