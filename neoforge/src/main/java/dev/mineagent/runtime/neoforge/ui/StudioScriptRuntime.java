package dev.mineagent.runtime.neoforge.ui;

import dev.mineagent.runtime.api.packages.*;
import dev.mineagent.runtime.api.permission.PermissionAction;
import dev.mineagent.runtime.api.task.TaskStatus;
import dev.mineagent.runtime.core.packages.*;
import dev.mineagent.runtime.neoforge.MineAgentRuntimeServices;
import dev.mineagent.runtime.neoforge.content.NativePackageCompatibility;
import dev.mineagent.runtime.scripting.ManagedScriptRuntime;
import dev.mineagent.runtime.scripting.studio.StudioScriptJournal;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import java.util.*;

/** Explicit top-level Rhino runs, distinct from registration-based WorldContent instances. */
public final class StudioScriptRuntime implements AutoCloseable {
    private static final Map<MinecraftServer, StudioScriptRuntime> LIVE = new IdentityHashMap<>();
    private static volatile Map<MinecraftServer,StudioScriptRuntime> OBSERVED=Map.of();
    private static final Set<MinecraftServer> STOPPED = Collections.newSetFromMap(new WeakHashMap<>());
    private final MinecraftServer server;
    private final StudioScriptJournal journal;
    private final ManagedScriptRuntime scripts = new ManagedScriptRuntime();
    private final Map<UUID, Binding> active = new LinkedHashMap<>();
    private final Map<UUID, String> stopIntents = new LinkedHashMap<>();
    private final Map<UUID, Outcome> pending = new LinkedHashMap<>();
    private boolean closed;
    private record Outcome(String state, String error, String source, int line, int column) {}

    private final class Binding {
        final StudioScriptJournal.Record run;
        final RuntimePackage pack;
        final long runGeneration, manageGeneration;
        boolean enabled = true;
        Binding(StudioScriptJournal.Record run, RuntimePackage pack) {
            this.run = run; this.pack = pack;
            var config = MineAgentRuntimeServices.config(server);
            runGeneration = config.permissionGeneration(run.owner(), PermissionAction.RUN_CODE);
            manageGeneration = config.permissionGeneration(run.owner(), PermissionAction.MANAGE_PACKAGES);
        }
        boolean current(){return currentOwn()&&(run.dependencies()==null||run.dependencies().nodes().isEmpty()||ServerScriptDependencies.current(server,run.owner(),run.dependencies()));}
        boolean currentOwn() {
            try {
                if (!enabled || closed || scripts.isSuspended(run.id()) || scripts.failure(run.id()).isPresent() || !server.isSameThread() || !server.isRunning()
                        || !dev.mineagent.runtime.neoforge.WorldIdentityRuntime.ready(server)
                        || !MineAgentRuntimeServices.worldId(server).equals(run.world())) return false;
                var config = MineAgentRuntimeServices.config(server);
                if (runGeneration != config.permissionGeneration(run.owner(), PermissionAction.RUN_CODE)
                        || manageGeneration != config.permissionGeneration(run.owner(), PermissionAction.MANAGE_PACKAGES)) return false;
                var player = server.getPlayerList().getPlayer(run.owner());
                boolean op = player != null ? player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER)
                        : server.getProfilePermissions(new net.minecraft.server.players.NameAndId(run.owner(), run.ownerName()))
                                .hasPermission(Permissions.COMMANDS_GAMEMASTER);
                var permission = MineAgentRuntimeServices.permissions(server);
                if (!permission.allowed(run.owner(), op, PermissionAction.RUN_CODE)
                        || !permission.allowed(run.owner(), op, PermissionAction.MANAGE_PACKAGES)) return false;
                var current = ServerPackageRuntime.get(server).worldLibrary().get(pack.packageId()).orElse(null);
                return current != null && current.revision() == pack.revision()
                        && current.canonicalSha256().equals(pack.canonicalSha256())
                        && NativeCompatibilityPolicy.check(current, NativePackageCompatibility.observe(), "SERVER").allowed();
            } catch (Exception e) { return false; }
        }
    }

    private StudioScriptRuntime(MinecraftServer server) {
        this.server = server;
        this.journal = MineAgentRuntimeServices.codeDrafts(server).scriptPublications();
    }
    public static synchronized StudioScriptRuntime get(MinecraftServer server) {
        if (STOPPED.contains(server) || !server.isSameThread() || !server.isRunning())
            throw new IllegalStateException("STUDIO_SCRIPT_SERVER_STOPPED");
        var value=LIVE.computeIfAbsent(server, StudioScriptRuntime::new);OBSERVED=Map.copyOf(LIVE);return value;
    }
    public static void stopServer(MinecraftServer server) {
        StudioScriptRuntime value;
        synchronized (StudioScriptRuntime.class) { STOPPED.add(server); value = LIVE.remove(server); }
        if (value != null) value.close();
        synchronized(StudioScriptRuntime.class){OBSERVED=Map.copyOf(LIVE);}
    }
    public static void tick(MinecraftServer server) {
        StudioScriptRuntime value;
        synchronized (StudioScriptRuntime.class) { value = LIVE.get(server); }
        if (value != null) value.tick();
    }
    /** Observation never creates a runtime or resumes a stopped service. */
    public static synchronized Map<String, Boolean> status(MinecraftServer server, UUID id) {
        var value = LIVE.get(server);
        return Map.of("loaded", value != null && value.scripts.isLoaded(id),
                "suspended", value != null && value.scripts.isSuspended(id),
                "metadataPending", value != null && (value.pending.containsKey(id) || value.stopIntents.containsKey(id)));
    }
    /** Library guard never takes a runtime/Class monitor while holding the library lock. */
    public static void requirePackageMutable(MinecraftServer server,UUID pkg){var value=OBSERVED.get(server);if(value!=null)value.scripts.requirePackageMutable(pkg);}
    public static List<UUID> consumers(MinecraftServer server,UUID publication){var value=OBSERVED.get(server);return value==null?List.of():value.scripts.consumers(publication);}
    public static boolean dependencyAvailable(MinecraftServer server,StudioScriptJournal.Record record){
        var value=OBSERVED.get(server);if(value==null||record.legacyStop()||value.pending.containsKey(record.id())||value.stopIntents.containsKey(record.id()))return false;
        var binding=value.active.get(record.id());return binding!=null&&binding.currentOwn()&&value.scripts.isLoaded(record.id())&&binding.run.packageHash().equals(record.packageHash())&&binding.run.sourceHash().equals(record.sourceHash());
    }
    public static void requireDependencyGraph(MinecraftServer server,ScriptDependencyGraph graph){if(graph.nodes().isEmpty())return;var value=OBSERVED.get(server);if(value==null)throw new IllegalStateException("STUDIO_SCRIPT_DEPENDENCY_NOT_LOADED");value.scripts.requireLinked(graph);}
    private void startCurrent(ServerPlayer player, CodeDraft draft, long revision) {
        ServerJavaStudio.authorize(player);
        if (closed || draft.revision() != revision || !draft.ownerPlayerId().equals(player.getUUID())
                || !draft.worldId().equals(MineAgentRuntimeServices.worldId(server)))
            throw new IllegalStateException("STUDIO_SCRIPT_CONTEXT_CHANGED");
        var current = MineAgentRuntimeServices.codeDrafts(server).get(draft.draftId()).orElseThrow();
        var task = MineAgentRuntimeServices.tasks(server).get(draft.taskId()).orElseThrow();
        if (current.status() != CodeDraftStatus.DRAFT || current.revision() != draft.revision()
                || !current.source().equals(draft.source()) || !current.path().equals(draft.path()) || !current.additionalSources().equals(draft.additionalSources()) || !current.dependencies().equals(draft.dependencies()) || !task.ownerPlayerId().equals(player.getUUID())
                || task.revision() != draft.taskRevision() || task.status() != TaskStatus.RUNNING
                || !task.runnableStepIds().contains("publish")
                || dev.mineagent.runtime.core.task.TaskAuthorityFence.revoked(task))
            throw new IllegalStateException("STUDIO_SCRIPT_TASK_CHANGED");
    }
    public StudioScriptJournal.Record run(ServerPlayer player, CodeDraft draft, long revision) throws Exception {
        startCurrent(player, draft, revision);
        if (!RuntimeStudioScriptPlan.path(draft.path())) throw new IllegalArgumentException("STUDIO_SCRIPT_SOURCE_REQUIRED");
        if (MineAgentRuntimeServices.packages(server).active(draft.packageId()).isPresent())
            throw new IllegalStateException("STUDIO_SCRIPT_LEGACY_STILL_RUNNING");
        for (var r : journal.forPackage(player.getUUID(), draft.packageId()))
            if (r.uncertain() || Set.of("STARTING", "START_RETURNED", "PUBLISHED", "STOPPING").contains(r.state()))
                throw new IllegalStateException("STUDIO_SCRIPT_PRIOR_ACTIVE_OR_UNKNOWN");
        for (var r : MineAgentRuntimeServices.codeDrafts(server).javaPublications().forPackage(player.getUUID(), draft.packageId()))
            if (r.uncertain() || MineAgentRuntimeServices.javaExtensions(server).isLoaded(r.id())
                    || Set.of("COMPILING", "COMPILED", "STARTING", "START_RETURNED", "STOPPING").contains(r.state()))
                throw new IllegalStateException("STUDIO_SCRIPT_JAVA_EXECUTION_UNRESOLVED");
        var pack = ServerJavaStudio.ensureSource(player, draft);
        var sources = RuntimeStudioPlan.readAll(pack, ServerPackageRuntime.get(server).worldContent());
        if (!RuntimeStudioPlan.fingerprint(pack).equals(CodeDraftSources.fingerprint(draft))) throw new IllegalStateException("STUDIO_SCRIPT_SOURCE_CHANGED");
        if (!NativeCompatibilityPolicy.check(pack, NativePackageCompatibility.observe(), "SERVER").allowed())
            throw new IllegalStateException("STUDIO_SCRIPT_ENVIRONMENT_CHANGED");
        startCurrent(player, draft, revision);
        var graph=ServerScriptDependencies.resolve(server,player.getUUID(),pack.packageId(),pack.dependencies());
        var prepared = journal.begin(draft, player.getName().getString(), pack.revision(), pack.canonicalSha256(), false,graph);
        if (!prepared.dispatch()) {
            // An intent reobserved after an uncertain insert must never execute its top level.
            if (Set.of("STARTING", "START_RETURNED").contains(prepared.record().state()))
                settle(prepared.record().id(), new Outcome("OUTCOME_UNKNOWN", "STUDIO_SCRIPT_START_INTENT_REOBSERVED", "", 0, 0));
            return journal.get(prepared.record().id());
        }
        var run = prepared.record();
        Binding binding = null;
        try {
            scripts.pin(run.id(),graph);
            binding = new Binding(run, pack);
            active.put(run.id(), binding);
            if (!binding.current()) throw new IllegalStateException("STUDIO_SCRIPT_AUTHORITY_CHANGED");
            scripts.load(run.id(), pack.revision(), sources,
                    pack.entrypoints().get("studio_script").path(),
                    Map.of("host", new dev.mineagent.runtime.neoforge.scripting.MineAgentScriptHost(server, player.getUUID(), binding::current)),
                    binding::current, server.getTickCount(),new ManagedScriptRuntime.Link(graph,run.sourceHash()));
            startCurrent(player, draft, revision);
            if (!binding.current()) throw new IllegalStateException("STUDIO_SCRIPT_AUTHORITY_CHANGED");
            journal.finish(run.id(), "START_RETURNED", "", "", 0, 0);
            if (!MineAgentRuntimeServices.codeDrafts(server).markScriptPublished(draft.draftId(), player.getUUID(), revision, draft.taskRevision()).accepted())
                throw new IllegalStateException("STUDIO_SCRIPT_DRAFT_COMMIT_FAILED");
            if (!MineAgentRuntimeServices.tasks(server).completeStep(draft.taskId(), draft.taskRevision(), "publish").accepted())
                throw new IllegalStateException("STUDIO_SCRIPT_TASK_COMMIT_FAILED");
            return journal.finish(run.id(), "PUBLISHED", "", "", 0, 0);
        } catch (Exception | LinkageError e) {
            if(!scripts.attempted(run.id())){
                scripts.releaseBeforeLoad(run.id());if(binding!=null)binding.enabled=false;active.remove(run.id());
                settle(run.id(),new Outcome("REJECTED_BEFORE_START",code(e),"",0,0));throw new IllegalStateException("STUDIO_SCRIPT_START_REJECTED",e);
            }
            scripts.releaseBeforeLoad(run.id());
            if (binding != null) binding.enabled = false;
            scripts.suspend(run.id());
            active.remove(run.id());
            var outcome = failure(run.id(), code(e));
            // Unload removes the handle before calling tracked cleanup. It is never invoked again to repair a receipt.
            try { scripts.unload(run.id()); } catch (Exception | LinkageError ignored) { }
            settle(run.id(), outcome);
            throw new IllegalStateException("STUDIO_SCRIPT_START_UNCERTAIN", e);
        }
    }
    public StudioScriptJournal.Record stop(ServerPlayer player, CodeDraft draft, UUID id, long revision) throws Exception {
        ServerJavaStudio.authorize(player);
        var record = journal.refresh(id).orElseThrow();
        if (!record.owner().equals(player.getUUID()) || !record.world().equals(draft.worldId())
                || !record.draft().equals(draft.draftId()) || !record.packageId().equals(draft.packageId())
                || record.legacyStop() || record.revision() != revision)
            throw new IllegalStateException("STUDIO_SCRIPT_STOP_CONTEXT");
        if (Set.of("STOP_RETURNED", "STOP_UNKNOWN", "NOT_LOADED_THIS_PROCESS").contains(record.state())) return record;
        if (!Set.of("PUBLISHED", "OUTCOME_UNKNOWN", "STARTING", "START_RETURNED", "STOPPING").contains(record.state()))
            throw new IllegalStateException("STUDIO_SCRIPT_STOP_STATE");
        scripts.requireCanUnload(id);
        stop(id, "USER_STOP");
        return journal.get(id);
    }
    private void quiesce(UUID id) {
        var binding = active.get(id);
        if (binding != null) binding.enabled = false;
        scripts.suspend(id);
    }
    private void stop(UUID id, String reason) {
        quiesce(id);
        if (pending.containsKey(id)) { settle(id, pending.get(id)); return; }
        stopIntents.putIfAbsent(id, reason);
        advanceStop(id);
    }
    private void advanceStop(UUID id) {
        String reason = stopIntents.get(id);
        if (reason == null) return;
        try {scripts.requireCanUnload(id);}catch(IllegalStateException inUse){return;}
        try {
            var record = journal.finish(id, "STOPPING", reason, "", 0, 0);
            if (!record.state().equals("STOPPING")) return;
        } catch (Exception e) { return; } // suspended even when STOPPING cannot yet be recorded
        stopIntents.remove(id); // cleanup starts exactly once in this process, before any result persistence
        Outcome outcome;
        try {
            boolean invoked = scripts.unload(id);
            outcome = new Outcome(invoked ? "STOP_RETURNED" : "NOT_LOADED_THIS_PROCESS", reason, "", 0, 0);
        } catch (Exception | LinkageError e) {
            outcome = new Outcome("STOP_UNKNOWN", "STUDIO_SCRIPT_STOP_FAILED", "", 0, 0);
        }
        active.remove(id);
        settle(id, outcome);
    }
    public static boolean legacyMatches(CodeDraft draft, RuntimePackageExecutionPlan plan) {
        return draft.dependencies().isEmpty() && draft.additionalSources().isEmpty() && RuntimeStudioScriptPlan.path(draft.path()) && plan.packageId().equals(draft.packageId())
                && plan.taskId().equals(draft.taskId()) && plan.taskRevision() == draft.taskRevision()
                && plan.packageRevision() == draft.packageRevision() && plan.modules().size() == 1
                && plan.entrypoint().equals(draft.path()) && Objects.equals(plan.modules().get(plan.entrypoint()), draft.source());
    }
    public StudioScriptJournal.Record stopLegacy(ServerPlayer player, CodeDraft draft) throws Exception {
        ServerJavaStudio.authorize(player);
        if (!draft.ownerPlayerId().equals(player.getUUID()) || !draft.worldId().equals(MineAgentRuntimeServices.worldId(server)))
            throw new IllegalStateException("STUDIO_SCRIPT_CONTEXT_CHANGED");
        var manager = MineAgentRuntimeServices.packages(server);
        var plan = manager.active(draft.packageId()).orElseThrow(() -> new IllegalStateException("STUDIO_SCRIPT_LEGACY_NOT_LOADED"));
        if (!legacyMatches(draft, plan)) throw new IllegalStateException("STUDIO_SCRIPT_LEGACY_SOURCE_CHANGED");
        var prepared = journal.begin(draft, player.getName().getString(), plan.packageRevision(), "", true);
        if (!prepared.dispatch()) {
            if (prepared.record().state().equals("STOPPING"))
                settle(prepared.record().id(), new Outcome("STOP_UNKNOWN", "STUDIO_SCRIPT_LEGACY_INTENT_REOBSERVED", "", 0, 0));
            return journal.get(prepared.record().id());
        }
        try {
            boolean stopped = manager.unload(draft.packageId());
            var old = MineAgentRuntimeServices.contentPackages(server).get(draft.packageId()).orElse(null);
            if (old != null && old.source().equals(draft.source()) && old.enabled()
                    && !MineAgentRuntimeServices.contentPackages(server).setEnabled(old.packageId(), old.revision(), false).accepted())
                throw new IllegalStateException("STUDIO_SCRIPT_LEGACY_METADATA_FAILED");
            var outcome = new Outcome(stopped ? "STOP_RETURNED" : "NOT_LOADED_THIS_PROCESS", "LEGACY_STOP", "", 0, 0);
            settle(prepared.record().id(), outcome);
            return journal.get(prepared.record().id());
        } catch (Exception | LinkageError e) {
            settle(prepared.record().id(), new Outcome("STOP_UNKNOWN", "STUDIO_SCRIPT_LEGACY_STOP_UNKNOWN", "", 0, 0));
            throw new IllegalStateException("STUDIO_SCRIPT_LEGACY_STOP_UNKNOWN", e);
        }
    }
    private Outcome failure(UUID id, String fallback) {
        var f = scripts.failure(id).orElse(null);
        String source = f == null || f.source() == null ? "" : f.source();
        return new Outcome("OUTCOME_UNKNOWN", f == null ? fallback : f.code(),
                source.length() > 256 ? source.substring(0, 256) : source,
                f == null ? 0 : Math.max(0, f.line()), f == null ? 0 : Math.max(0, f.column()));
    }
    private void settle(UUID id, Outcome outcome) {
        try { journal.finish(id, outcome.state(), outcome.error(), outcome.source(), outcome.line(), outcome.column()); pending.remove(id); }
        catch (Exception e) { pending.put(id, outcome); }
    }
    private void tick() {
        if (closed) return;
        // Metadata retries are bounded and never replay top-level code, callbacks, or tracked cleanup.
        if (server.getTickCount() % 20 == 0) {
            for (var entry : List.copyOf(pending.entrySet()).stream().limit(4).toList()) settle(entry.getKey(), entry.getValue());
            for (var id : List.copyOf(stopIntents.keySet()).stream().limit(4).toList()) advanceStop(id);
        }
        for (var entry : List.copyOf(active.entrySet()))
            if (entry.getValue().enabled && !entry.getValue().current()) stop(entry.getKey(), "SOURCE_OR_PERMISSION_CHANGED");
        scripts.tick(server.getTickCount());
        scripts.fire("server.tick", server.getTickCount());
        for (var entry : List.copyOf(active.entrySet())) if (!scripts.isLoaded(entry.getKey())) {
            entry.getValue().enabled = false;
            active.remove(entry.getKey());
            if (!pending.containsKey(entry.getKey()) && !stopIntents.containsKey(entry.getKey()))
                settle(entry.getKey(), failure(entry.getKey(), "STUDIO_SCRIPT_UNLOADED_UNVERIFIED"));
        }
    }
    public static String code(Throwable e) {
        for (int i = 0; e != null && i < 12; i++, e = e.getCause()) {
            String value = Objects.toString(e.getMessage(), "");
            if (value.matches("(?:STUDIO_SCRIPT|STUDIO)_[A-Z_]{1,80}")) return value;
        }
        return "STUDIO_SCRIPT_EXECUTION_FAILED";
    }
    @Override public void close() {
        if (closed) return;
        closed = true;
        for (var id : List.copyOf(active.keySet())) quiesce(id);
        for (var id : List.copyOf(active.keySet())) stop(id, "SERVER_STOPPED");
        // Shutdown must release retained resources even if durable STOPPING is unavailable.
        // Such cleanup is explicitly UNKNOWN, not a successful durable stop.
        for (var id : List.copyOf(stopIntents.keySet())) {
            stopIntents.remove(id);
            try { scripts.unload(id); } catch (Exception | LinkageError ignored) { }
            active.remove(id);
            settle(id, new Outcome("OUTCOME_UNKNOWN", "STUDIO_SCRIPT_SHUTDOWN_RECEIPT_UNAVAILABLE", "", 0, 0));
        }
        for (var entry : List.copyOf(pending.entrySet())) settle(entry.getKey(), entry.getValue());
        // Every tracked handle was removed before cleanup; close cannot retry those resources.
        try { scripts.close(); } catch (Exception | LinkageError ignored) { }
        active.clear();
    }
}
