package dev.mineagent.runtime.scripting;

import dev.latvian.mods.rhino.BaseFunction;
import dev.latvian.mods.rhino.Context;
import dev.latvian.mods.rhino.ContextFactory;
import dev.latvian.mods.rhino.Function;
import dev.latvian.mods.rhino.Scriptable;
import dev.latvian.mods.rhino.ScriptableObject;
import dev.latvian.mods.rhino.Wrapper;
import dev.mineagent.runtime.scripting.preflight.ScriptPreflight;
import dev.mineagent.runtime.core.packages.ScriptDependencyGraph;
import dev.mineagent.runtime.core.packages.CodeDraftSources;
import dev.mineagent.runtime.api.packages.ClientPackageBridge;
import dev.mineagent.runtime.api.packages.ClientPackageData;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.UUID;

public final class ManagedScriptRuntime implements AutoCloseable {
    private static final int MAX_PACKAGES = 128;
    private static final int MAX_MODULES = 64;
    private static final int MAX_HANDLERS = 128;
    private static final int MAX_SCHEDULED = 128;
    private static final int MAX_TRACKED_RESOURCES = 256;
    private static final long MAX_DISPATCH_NANOS = Duration.ofMillis(50).toNanos();

    private final Duration executionTimeout;
    private final ScriptPreflight preflight = new ScriptPreflight();
    private final Map<UUID, Environment> environments = new LinkedHashMap<>();
    public record Link(ScriptDependencyGraph graph,String sourceHash){public Link{java.util.Objects.requireNonNull(graph);if(sourceHash==null||!sourceHash.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("STUDIO_SCRIPT_DEPENDENCY_LINK");}}
    private volatile Map<UUID,ScriptDependencyGraph> reservations=Map.of();
    private volatile Map<UUID,List<UUID>> consumerIndex=Map.of();
    private final Set<UUID> attempted=new HashSet<>();
    public synchronized void pin(UUID consumer,ScriptDependencyGraph graph){
        var old=reservations.get(consumer);if(old!=null&&!old.equals(graph))throw new IllegalStateException("STUDIO_SCRIPT_DEPENDENCY_CHANGED");
        for(var node:graph.nodes()){var e=requireLinked(node);e.requireDispatch();graph.requireEmbedded(node,e.link.graph());}
        if(old==null){if(reservations.size()>=MAX_PACKAGES)throw new IllegalStateException("STUDIO_SCRIPT_DEPENDENCY_RESERVATION_LIMIT");var next=new LinkedHashMap<>(reservations);next.put(consumer,graph);setReservations(next);}
    }
    private Environment requireLinked(ScriptDependencyGraph.Node node){
        var e=environments.get(node.publication());
        if(e==null||e.suspended||e.link==null||e.revision!=node.packageRevision()||!e.link.graph().root().equals(node.packageId())||!e.link.sourceHash().equals(node.sourceHash())||!e.entryModule.equals(node.entry()))throw new IllegalStateException("STUDIO_SCRIPT_DEPENDENCY_NOT_LOADED");return e;
    }
    public synchronized void requireLinked(ScriptDependencyGraph graph){for(var node:graph.nodes()){var e=requireLinked(node);graph.requireEmbedded(node,e.link.graph());}}
    public List<UUID> consumers(UUID scope){return consumerIndex.getOrDefault(scope,List.of());}
    public void requirePackageMutable(UUID pkg){if(reservations.values().stream().anyMatch(g->g.nodes().stream().anyMatch(n->n.packageId().equals(pkg))))throw new IllegalStateException("STUDIO_SCRIPT_DEPENDENCY_IN_USE");}
    public synchronized void requireCanUnload(UUID scope){if(!consumers(scope).isEmpty())throw new IllegalStateException("STUDIO_SCRIPT_DEPENDENCY_IN_USE");}
    public synchronized void releaseBeforeLoad(UUID scope){if(!attempted.contains(scope)&&!environments.containsKey(scope))release(scope);}
    private void release(UUID scope){var next=new LinkedHashMap<>(reservations);next.remove(scope);setReservations(next);attempted.remove(scope);}

    public synchronized boolean attempted(UUID scope){return attempted.contains(scope);}
    private void setReservations(Map<UUID,ScriptDependencyGraph> values){
        var index=new LinkedHashMap<UUID,ArrayList<UUID>>();for(var entry:values.entrySet())for(var dependency:entry.getValue().scopes())index.computeIfAbsent(dependency,k->new ArrayList<>()).add(entry.getKey());
        var result=new LinkedHashMap<UUID,List<UUID>>();index.forEach((id,list)->result.put(id,list.stream().sorted().toList()));reservations=Map.copyOf(values);consumerIndex=Map.copyOf(result);
    }
    public record Failure(UUID scopeId,long revision,String event,String code,String source,int line,int column){}
    private final Map<UUID,Failure> failures=new LinkedHashMap<>();
    public synchronized java.util.Optional<Failure> failure(UUID scopeId){return java.util.Optional.ofNullable(failures.get(scopeId));}
    private long currentTick;

    public ManagedScriptRuntime() {
        this(Duration.ofMillis(25));
    }

    public ManagedScriptRuntime(Duration executionTimeout) {
        if (executionTimeout == null || executionTimeout.isZero() || executionTimeout.isNegative()) {
            throw new IllegalArgumentException("execution timeout must be positive");
        }
        this.executionTimeout = executionTimeout;
    }

    public synchronized Object load(
            UUID packageId,
            long revision,
            Map<String, String> modules,
            String entryModule,
            Map<String, Object> bindings
    ) throws Exception {
        return load(packageId, revision, modules, entryModule, bindings, () -> true, currentTick);
    }

    /** Optional authority checked at top-level and before each callback. Existing callers retain their contract. */
    public synchronized Object load(UUID packageId, long revision, Map<String, String> modules,
            String entryModule, Map<String, Object> bindings,
            java.util.function.BooleanSupplier authority, long initialTick) throws Exception {
        return load(packageId,revision,modules,entryModule,bindings,authority,initialTick,null);
    }
    public synchronized Object load(UUID packageId,long revision,Map<String,String> modules,String entryModule,Map<String,Object> bindings,
            java.util.function.BooleanSupplier authority,long initialTick,Link link)throws Exception {
        requireCanUnload(packageId);
        if(link==null&&reservations.containsKey(packageId))throw new IllegalStateException("STUDIO_SCRIPT_DEPENDENCY_CONTEXT_REQUIRED");
        if(link!=null){
            if(!link.graph().equals(reservations.get(packageId)))throw new IllegalStateException("STUDIO_SCRIPT_DEPENDENCY_RESERVATION");
            var refs=new java.util.TreeMap<String,dev.mineagent.runtime.api.packages.CodeDraft.SourceRef>();for(var file:modules.entrySet())refs.put(file.getKey(),CodeDraftSources.ref(file.getValue()));
            if(!link.sourceHash().equals(CodeDraftSources.fingerprint(entryModule,refs,link.graph().required())))throw new IllegalStateException("STUDIO_SCRIPT_DEPENDENCY_SOURCE_CHANGED");
            pin(packageId,link.graph());
        }
        if (authority == null || initialTick < currentTick) throw new IllegalArgumentException("invalid script dispatch context");
        if (packageId == null || revision < 1 || modules == null || modules.isEmpty()
                || modules.size() > MAX_MODULES || bindings == null || entryModule == null
                || !entryModule.matches("[A-Za-z0-9_./-]{1,128}") || !modules.containsKey(entryModule)) {
            throw new IllegalArgumentException("invalid managed script package");
        }
        Environment previous = environments.get(packageId);
        if (previous == null && environments.size() >= MAX_PACKAGES) {
            throw new IllegalStateException("managed script package limit reached");
        }
        if (previous != null && revision <= previous.revision) {
            throw new IllegalArgumentException("script package revision is stale");
        }
        var verifiedModules = new LinkedHashMap<String, String>();
        for (var module : modules.entrySet()) {
            if (module.getKey() == null || !module.getKey().matches("[A-Za-z0-9_./-]{1,128}")
                    || module.getValue() == null || module.getValue().isBlank()
                    || module.getValue().length() > 1_000_000) {
                throw new IllegalArgumentException("invalid script module");
            }
            var inspected = preflight.inspect(module.getValue());
            if (!inspected.accepted()) {
                throw new ScriptRejectedException(inspected);
            }
            verifiedModules.put(module.getKey(), module.getValue());
        }
        var environment = new Environment(this,packageId, revision, Map.copyOf(verifiedModules), executionTimeout, authority,entryModule,link);
        if(link!=null)attempted.add(packageId);
        try {
            Object result = environment.initialize(entryModule, Map.copyOf(bindings), initialTick);
            environments.put(packageId, environment);
            failures.remove(packageId);
            if (previous != null) {
                previous.close();
            }
            return result;
        } catch (Exception | LinkageError failure) {
            recordFailure(environment,"load",failure);
            environment.close();
            throw failure;
        }
    }

    public synchronized void fire(String event, Object payload) {
        if (event == null || !event.matches("[A-Za-z0-9_.:-]{1,128}")) {
            throw new IllegalArgumentException("invalid script event");
        }
        long deadline = System.nanoTime() + MAX_DISPATCH_NANOS;
        for (Environment environment : List.copyOf(environments.values())) {
            if (environment.suspended) continue;
            if (System.nanoTime() >= deadline) {
                break;
            }
            try {
                environment.fire(event, payload);
            } catch (RuntimeException | LinkageError failure) {
                recordFailure(environment,event,failure);disable(environment);
            }
        }
    }
    /** Instance lifecycle events must never broadcast to unrelated scopes. */
    public synchronized void replaceBinding(UUID scopeId,long revision,String name,Object value){
        var e=environments.get(scopeId);if(e==null||e.revision!=revision)throw new IllegalStateException("SCRIPT_BINDING_STALE");
        if(name==null||value==null||Set.of("on","require","schedule","track","callPackage").contains(name)||!e.bindingNames.contains(name))throw new IllegalArgumentException("SCRIPT_BINDING_UNKNOWN");
        try{e.contexts.beginExecution().addToScope(e.scope,name,value);}catch(RuntimeException|LinkageError failure){recordFailure(e,"binding:"+name,failure);disable(e);throw failure;}
    }
    /** Read-only discovery of registered consumer names; no code execution or lifecycle event exposure. */
    public synchronized List<String> eventHandlers(UUID scopeId){var environment=environments.get(scopeId);if(environment==null)return List.of();return environment.handlers.keySet().stream().filter(name->name.matches("runtime\\.event\\.[A-Za-z][A-Za-z0-9_.:-]{0,63}")).sorted().toList();}
    public synchronized List<String> scheduleHandlers(UUID scopeId){var environment=environments.get(scopeId);if(environment==null)return List.of();return environment.handlers.keySet().stream().filter(name->name.matches("runtime\\.schedule\\.[A-Za-z][A-Za-z0-9_.:-]{0,63}")).sorted().toList();}
    public synchronized int eventHandlerRevision(UUID scopeId,String name){var environment=environments.get(scopeId);return environment==null?0:environment.handlers.getOrDefault(name,List.of()).size();}
    /** A consumer event shares one normal deadline across every registered callback; no lifecycle budget promotion. */
    public synchronized boolean fireConsumer(UUID scopeId,String event,Object payload){
        if(!eventHandlers(scopeId).contains(event))throw new IllegalArgumentException("SCRIPT_CONSUMER_HANDLER_MISSING");return invokeConsumer(scopeId,event,payload);
    }
    public synchronized boolean fireScheduleConsumer(UUID scopeId,String event,Object payload){
        if(!scheduleHandlers(scopeId).contains(event))throw new IllegalArgumentException("SCHEDULE_SCRIPT_HANDLER_MISSING");return invokeConsumer(scopeId,event,payload);
    }
    private boolean invokeConsumer(UUID scopeId,String event,Object payload){var environment=environments.get(scopeId);
        if(environment==null||environment.suspended)return false;
        try{long deadline=System.nanoTime()+Math.min(executionTimeout.toNanos(),Duration.ofMillis(25).toNanos());for(var callback:List.copyOf(environment.handlers.get(event)))environment.invokeUntil(callback,new Object[]{payload},deadline);return environments.get(scopeId)==environment;}
        catch(RuntimeException|LinkageError failure){recordFailure(environment,event,failure);disable(environment);return false;}
    }
    public synchronized boolean fireTo(UUID scopeId,String event,Object payload){
        if(scopeId==null||event==null||!event.matches("[A-Za-z0-9_.:-]{1,128}"))throw new IllegalArgumentException("invalid targeted event");
        var environment=environments.get(scopeId);
        if(environment==null||environment.suspended||environment.handlers.getOrDefault(event,List.of()).isEmpty())return false;
        try{environment.fire(event,payload);return environments.get(scopeId)==environment;}
        catch(RuntimeException|LinkageError failure){recordFailure(environment,event,failure);disable(environment);return false;}
    }
    /** Explicit one-off initialization budget, shared by the entire targeted lifecycle event. Tick/schedule limits stay unchanged. */
    public synchronized boolean fireLifecycle(UUID scopeId,String event,Object payload,Duration budget){
        if(scopeId==null||event==null||!Set.of("instance.create","instance.restore").contains(event)||budget==null||budget.isNegative()||budget.isZero()||budget.compareTo(Duration.ofMillis(250))>0)throw new IllegalArgumentException("invalid lifecycle budget");
        var environment=environments.get(scopeId);if(environment==null||environment.suspended||environment.handlers.getOrDefault(event,List.of()).isEmpty())return false;
        try{
            long deadline=System.nanoTime()+budget.toNanos();
            for(var callback:List.copyOf(environment.handlers.get(event)))environment.invokeUntil(callback,new Object[]{payload},deadline);
            return environments.get(scopeId)==environment;
        }catch(RuntimeException|LinkageError failure){recordFailure(environment,event,failure);disable(environment);return false;}
    }

    public synchronized void tick(long tick) {
        if (tick < currentTick) {
            throw new IllegalArgumentException("script tick cannot move backwards");
        }
        currentTick = tick;
        long deadline = System.nanoTime() + MAX_DISPATCH_NANOS;
        for (Environment environment : List.copyOf(environments.values())) {
            if (environment.suspended) continue;
            if (System.nanoTime() >= deadline) {
                break;
            }
            try {
                environment.tick(tick);
            } catch (RuntimeException | LinkageError failure) {
                recordFailure(environment,"schedule",failure);disable(environment);
            }
        }
    }

    public synchronized boolean isLoaded(UUID packageId) {
        return environments.containsKey(packageId);
    }

    /** One-way quiescence: retain cleanup resources, but dispatch no further script callbacks. */
    public synchronized void suspend(UUID scopeId) {
        var environment = environments.get(scopeId);
        if (environment != null) environment.suspended = true;
        for(var consumer:consumers(scopeId)){var other=environments.get(consumer);if(other!=null)other.suspended=true;}
    }

    public synchronized boolean isSuspended(UUID scopeId) {
        var environment = environments.get(scopeId);
        return environment != null && environment.suspended;
    }

    public synchronized boolean unload(UUID packageId) throws Exception {
        requireCanUnload(packageId);
        Environment removed = environments.remove(packageId);
        if (removed == null) {
            return false;
        }
        removed.close();
        release(packageId);
        return true;
    }

    private void disable(Environment environment) {
        if(!consumers(environment.packageId).isEmpty()){suspend(environment.packageId);return;}
        environments.remove(environment.packageId, environment);
        try {
            environment.close();
            release(environment.packageId);
        } catch (Exception | LinkageError ignored) {
        }
    }
    private void recordFailure(Environment e,String event,Throwable error){
        var location=error instanceof dev.latvian.mods.rhino.RhinoException r?r:null;
        String code=error instanceof ScriptTimeoutException?"SCRIPT_TIMEOUT":"SCRIPT_CALLBACK_FAILED";
        failures.put(e.packageId,new Failure(e.packageId,e.revision,event,code,location==null?"":location.sourceName(),location==null?0:location.lineNumber(),location==null?0:location.columnNumber()));
        while(failures.size()>128)failures.remove(failures.keySet().iterator().next());
    }

    @Override
    public synchronized void close() throws Exception {
        Exception first = null;
        for(var id:List.copyOf(reservations.keySet()))releaseBeforeLoad(id);
        while(!environments.isEmpty()){
            var id=environments.keySet().stream().filter(key->consumers(key).isEmpty()).findFirst().orElse(null);
            if(id==null){if(first==null)first=new IllegalStateException("STUDIO_SCRIPT_DEPENDENCY_CLEANUP_BLOCKED");break;}
            try{unload(id);}catch(Exception|LinkageError failure){if(first==null)first=failure instanceof Exception e?e:new IllegalStateException("STUDIO_SCRIPT_CLEANUP_UNKNOWN",failure);}
        }
        failures.clear();
        if (first != null) {
            throw first;
        }
    }

    /** Executes in the existing dependency realm with a shared caller deadline, copying data both ways. */
    private synchronized Object callPackage(Environment caller,Context cx,UUID dependency,String member,Object arguments){
        caller.requireDispatch();if(!(cx instanceof DeadlineContext callerContext)||caller.link==null||!caller.link.graph().required().containsKey(dependency))throw new SecurityException("STUDIO_SCRIPT_DEPENDENCY_UNDECLARED");
        member(member);
        if(System.nanoTime()>=callerContext.deadline)throw new ScriptTimeoutException();
        var values=ScriptExchange.arguments(cx,caller.scope,arguments);var target=requireLinked(caller.link.graph().node(dependency));target.requireDispatch();
        target.currentTick=Math.max(target.currentTick,Math.max(currentTick,caller.currentTick));
        var context=(DeadlineContext)target.contexts.enter();long saved=context.deadline;context.deadline=Math.min(callerContext.deadline,System.nanoTime()+target.contexts.timeout.toNanos());
        Object copied;
        try{
            if(System.nanoTime()>=context.deadline)throw new ScriptTimeoutException();
            if(!target.moduleCache.containsKey(target.entryModule))throw new IllegalStateException("STUDIO_SCRIPT_DEPENDENCY_EXPORT_NOT_READY");
            Object exported=target.moduleCache.get(target.entryModule),method;Scriptable self;
            if(!(exported instanceof Scriptable objectScope)||!ScriptExchange.belongs(objectScope,target.scope))throw new IllegalArgumentException("STUDIO_SCRIPT_DEPENDENCY_EXPORT_REALM");
            if(member.isEmpty()&&exported instanceof Function function){method=function;self=target.scope;}
            else if(exported instanceof dev.latvian.mods.rhino.NativeObject object&&!member.isEmpty()&&object.has(context,member,object)){method=object.get(context,member,object);self=object;}
            else throw new IllegalArgumentException("STUDIO_SCRIPT_DEPENDENCY_EXPORT_MISSING");
            if(!(method instanceof Function function))throw new IllegalArgumentException("STUDIO_SCRIPT_DEPENDENCY_EXPORT_NOT_CALLABLE");
            if(!ScriptExchange.belongs(function,target.scope))throw new IllegalArgumentException("STUDIO_SCRIPT_DEPENDENCY_EXPORT_REALM");
            Object[] args=new Object[values.size()];for(int i=0;i<args.length;i++)args[i]=ScriptExchange.into(context,target.scope,values.get(i));
            caller.requireDispatch();target.requireDispatch();Object result=function.call(context,target.scope,self,args);
            copied=ScriptExchange.copy(context,target.scope,result);if(System.nanoTime()>=context.deadline)throw new ScriptTimeoutException();
        }finally{context.deadline=saved;}
        caller.requireDispatch();target.requireDispatch();Object result=ScriptExchange.into(cx,caller.scope,copied);
        if(System.nanoTime()>=callerContext.deadline)throw new ScriptTimeoutException();return result;
    }
    /** Calls an existing Rhino package from a non-Rhino CLIENT dependant using only neutral copied data. */
    public synchronized Object callExternal(UUID dependency,String member,List<?> arguments){Objects.requireNonNull(dependency);member(member);var values=ClientPackageData.arguments(arguments);var target=environments.get(dependency);if(target==null||target.suspended)throw new IllegalStateException("CLIENT_SCRIPT_DEPENDENCY_NOT_LOADED");target.requireDispatch();target.currentTick=Math.max(target.currentTick,currentTick);var context=(DeadlineContext)target.contexts.beginExecution();if(!target.moduleCache.containsKey(target.entryModule))throw new IllegalStateException("CLIENT_SCRIPT_DEPENDENCY_EXPORT_NOT_READY");Object exported=target.moduleCache.get(target.entryModule),method;Scriptable self;if(!(exported instanceof Scriptable objectScope)||!ScriptExchange.belongs(objectScope,target.scope))throw new IllegalArgumentException("CLIENT_SCRIPT_DEPENDENCY_EXPORT_REALM");if(member.isEmpty()&&exported instanceof Function function){method=function;self=target.scope;}else if(exported instanceof dev.latvian.mods.rhino.NativeObject object&&!member.isEmpty()&&object.has(context,member,object)){method=object.get(context,member,object);self=object;}else throw new IllegalArgumentException("CLIENT_SCRIPT_DEPENDENCY_EXPORT_MISSING");if(!(method instanceof Function function)||!ScriptExchange.belongs(function,target.scope))throw new IllegalArgumentException("CLIENT_SCRIPT_DEPENDENCY_EXPORT_NOT_CALLABLE");Object[] args=new Object[values.size()];for(int i=0;i<args.length;i++)args[i]=ScriptExchange.into(context,target.scope,values.get(i));Object result=function.call(context,target.scope,self,args);Object copied=ScriptExchange.portable(context,target.scope,result);if(System.nanoTime()>=context.deadline)throw new ScriptTimeoutException();target.requireDispatch();return ClientPackageData.copy(copied);}
    private static void member(String member){if(member==null||member.length()>128||!member.isEmpty()&&(!member.matches("[A-Za-z_$][A-Za-z0-9_$]{0,127}")||Set.of("__proto__","prototype","constructor").contains(member)))throw new IllegalArgumentException("CLIENT_PACKAGE_EXPORT");}

    private static final class Environment implements AutoCloseable {
        private final ManagedScriptRuntime runtime;
        private final Link link;
        private final String entryModule;
        private final UUID packageId;
        private final long revision;
        private final Map<String, String> modules;
        private final DeadlineContextFactory contexts;
        private final java.util.function.BooleanSupplier authority;
        private boolean suspended;
        private final Map<String, Object> moduleCache = new HashMap<>();
        private final Set<String> loadingModules = new HashSet<>();
        private final Map<String, List<Function>> handlers = new LinkedHashMap<>();
        private final PriorityQueue<Scheduled> scheduled = new PriorityQueue<>(Comparator
                .comparingLong(Scheduled::dueTick).thenComparingLong(Scheduled::sequence));
        private final List<AutoCloseable> resources = new ArrayList<>();
        private ScriptableObject scope;
        private Set<String> bindingNames=Set.of();
        private long currentTick;
        private long sequence;
        private int handlerCount;

        private Environment(ManagedScriptRuntime runtime,UUID packageId, long revision, Map<String, String> modules, Duration timeout,
                java.util.function.BooleanSupplier authority,String entryModule,Link link) {
            this.runtime=runtime;this.entryModule=entryModule;this.link=link;
            this.packageId = packageId;
            this.revision = revision;
            this.modules = modules;
            this.contexts = new DeadlineContextFactory(timeout);
            this.authority = authority;
        }

        private Object initialize(String entryModule, Map<String, Object> bindings, long tick) {
            requireDispatch();
            Object bridgeValue=bindings.get("packages");if(bridgeValue!=null&&!(bridgeValue instanceof ClientPackageBridge))throw new IllegalArgumentException("CLIENT_PACKAGE_BRIDGE");ClientPackageBridge bridge=(ClientPackageBridge)bridgeValue;
            bindingNames=bindings.keySet().stream().filter(name->!name.equals("packages")).collect(java.util.stream.Collectors.toUnmodifiableSet());
            currentTick = tick;
            Context context = contexts.beginExecution();
            scope = context.initStandardObjects();
            bindings.forEach((name, value) -> {
                if(name.equals("packages"))return;
                if (name == null || !name.matches("[A-Za-z_$][A-Za-z0-9_$]{0,127}")) {
                    throw new IllegalArgumentException("invalid script binding");
                }
                context.addToScope(scope, name, value);
            });
            context.addToScope(scope, "require", function(scope, context, "require", (cx, args) -> {
                if (args.length != 1) {
                    throw new IllegalArgumentException("require expects one module name");
                }
                return require(cx, String.valueOf(Wrapper.unwrapped(args[0])));
            }));
            context.addToScope(scope, "on", function(scope, context, "on", (cx, args) -> {
                if (args.length != 2 || !(args[1] instanceof Function callback)) {
                    throw new IllegalArgumentException("on expects event name and function");
                }
                String event = String.valueOf(Wrapper.unwrapped(args[0]));
                if (!event.matches("[A-Za-z0-9_.:-]{1,128}") || handlerCount >= MAX_HANDLERS) {
                    throw new IllegalArgumentException("invalid or excessive event handler");
                }
                handlers.computeIfAbsent(event, ignored -> new ArrayList<>()).add(callback);
                handlerCount++;
                return Context.getUndefinedValue();
            }));
            context.addToScope(scope, "schedule", function(scope, context, "schedule", (cx, args) -> {
                if (args.length != 2 || !(args[1] instanceof Function callback) || scheduled.size() >= MAX_SCHEDULED) {
                    throw new IllegalArgumentException("schedule expects bounded delay and function");
                }
                long delay = Math.round(cx.toNumber(args[0]));
                if (delay < 1 || delay > 72_000) {
                    throw new IllegalArgumentException("scheduled delay is out of range");
                }
                scheduled.add(new Scheduled(Math.addExact(currentTick, delay), sequence++, callback));
                return Context.getUndefinedValue();
            }));
            context.addToScope(scope, "track", function(scope, context, "track", (cx, args) -> {
                if (args.length != 1 || resources.size() >= MAX_TRACKED_RESOURCES) {
                    throw new IllegalArgumentException("track expects one bounded resource");
                }
                Object unwrapped = Wrapper.unwrapped(args[0]);
                if (!(unwrapped instanceof AutoCloseable resource)) {
                    throw new IllegalArgumentException("tracked value must implement AutoCloseable");
                }
                resources.add(resource);
                return args[0];
            }));
            if(link!=null||bridge!=null)context.addToScope(scope,"callPackage",function(scope,context,"callPackage",(cx,args)->{
                if(args.length!=3||!(args[0] instanceof String id)||!(args[1] instanceof String member))throw new IllegalArgumentException("STUDIO_SCRIPT_DEPENDENCY_CALL_ARGUMENTS");
                UUID dependency=UUID.fromString(id);if(link!=null&&link.graph().required().containsKey(dependency))return runtime.callPackage(this,cx,dependency,member,args[2]);if(bridge==null)throw new SecurityException("CLIENT_PACKAGE_DEPENDENCY_UNDECLARED");try{return ScriptExchange.into(cx,scope,ClientPackageData.copy(bridge.call(dependency,member,ScriptExchange.portableArguments(cx,scope,args[2]))));}catch(RuntimeException failure){throw failure;}catch(Exception failure){throw new IllegalStateException("CLIENT_PACKAGE_EXPORT_FAILED",failure);}
            }));
            return require(context, entryModule);
        }

        private Object require(Context context, String name) {
            if (!modules.containsKey(name)) {
                throw new IllegalArgumentException("unknown script module " + name);
            }
            if (moduleCache.containsKey(name)) {
                return moduleCache.get(name);
            }
            if (!loadingModules.add(name)) {
                throw new IllegalArgumentException("cyclic script module " + name);
            }
            try {
                String sourceName=name.endsWith(".js")||name.endsWith(".mjs")?name:name+".js";if(link!=null)sourceName="package:"+link.graph().root()+"/"+name;
                Object result = context.evaluateString(scope, modules.get(name), sourceName, 1, null);
                moduleCache.put(name, result);
                return result;
            } finally {
                loadingModules.remove(name);
            }
        }

        private void fire(String event, Object payload) {
            List<Function> callbacks = handlers.getOrDefault(event, List.of());
            for (Function callback : List.copyOf(callbacks)) {
                invoke(callback, new Object[]{payload});
            }
        }

        private void tick(long tick) {
            currentTick = tick;
            int executed = 0;
            while (!scheduled.isEmpty() && scheduled.peek().dueTick <= tick) {
                if (++executed > MAX_SCHEDULED) {
                    throw new IllegalStateException("scheduled script execution limit exceeded");
                }
                invoke(scheduled.remove().callback, new Object[0]);
            }
        }

        private Object invoke(Function callback, Object[] values) {
            Context context = contexts.beginExecution();
            return invokeIn(context,callback,values);
        }
        private Object invokeUntil(Function callback,Object[] values,long deadline){
            if(System.nanoTime()>=deadline)throw new ScriptTimeoutException();
            var context=contexts.beginExecution();((DeadlineContext)context).deadline=deadline;
            var value=invokeIn(context,callback,values);if(System.nanoTime()>=deadline)throw new ScriptTimeoutException();return value;
        }
        private Object invokeIn(Context context,Function callback,Object[] values){
            requireDispatch();
            Object[] wrapped = new Object[values.length];
            for (int index = 0; index < values.length; index++) {
                wrapped[index] = context.javaToJS(values[index], scope);
            }
            return callback.call(context, scope, scope, wrapped);
        }

        private void requireDispatch() {
            if (suspended || !authority.getAsBoolean()) throw new SecurityException("STUDIO_SCRIPT_AUTHORITY_CHANGED");
        }

        @Override
        public void close() throws Exception {
            suspended=true;
            handlers.clear();
            scheduled.clear();
            moduleCache.clear();
            Exception first = null;
            for (AutoCloseable resource : List.copyOf(resources).reversed()) {
                try {
                    resource.close();
                } catch (Exception | LinkageError failure) {
                    if (first == null) {
                        first = failure instanceof Exception e?e:new IllegalStateException("STUDIO_SCRIPT_CLEANUP_UNKNOWN",failure);
                    }
                }
            }
            resources.clear();
            scope = null;
            if (first != null) {
                throw first;
            }
        }

        private static BaseFunction function(
                Scriptable scope,
                Context context,
                String name,
                FunctionBody body
        ) {
            var function = new BaseFunction() {
                @Override
                public String getFunctionName() {
                    return name;
                }

                @Override
                public Object call(Context cx, Scriptable callScope, Scriptable thisObject, Object[] args) {
                    return body.call(cx, args);
                }
            };
            function.setParentScope(scope);
            function.setPrototype(ScriptableObject.getFunctionPrototype(scope, context));
            return function;
        }
    }

    @FunctionalInterface
    private interface FunctionBody {
        Object call(Context context, Object[] arguments);
    }

    private record Scheduled(long dueTick, long sequence, Function callback) {
    }

    private static final class DeadlineContextFactory extends ContextFactory {
        private final Duration timeout;

        private DeadlineContextFactory(Duration timeout) {
            this.timeout = timeout;
        }

        @Override
        protected Context createContext() {
            return new DeadlineContext(this, System.nanoTime() + timeout.toNanos());
        }
        private Context beginExecution(){var context=(DeadlineContext)enter();context.deadline=System.nanoTime()+timeout.toNanos();return context;}
    }

    private static final class DeadlineContext extends Context {
        private long deadline;

        private DeadlineContext(ContextFactory factory, long deadline) {
            super(factory);
            this.deadline = deadline;
            setGenerateObserverCount(true);
            setInstructionObserverThreshold(10_000);
        }

        @Override
        protected void observeInstructionCount(int instructionCount) {
            if (System.nanoTime() >= deadline) {
                throw new ScriptTimeoutException();
            }
        }
    }
}
