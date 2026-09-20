package dev.mineagent.runtime.scripting.packagehost;

import dev.mineagent.runtime.api.packages.ActivationMode;
import dev.mineagent.runtime.api.packages.RuntimePackageCandidate;
import dev.mineagent.runtime.api.packages.RuntimePackageExecutionPlan;
import dev.mineagent.runtime.scripting.ManagedScriptRuntime;
import dev.mineagent.runtime.scripting.ScriptRejectedException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class RuntimePackageManager implements AutoCloseable {
    private final ManagedScriptRuntime runtime = new ManagedScriptRuntime();
    private final Map<UUID, RuntimePackageExecutionPlan> active = new LinkedHashMap<>();

    public synchronized PackageActivationResult activate(
            RuntimePackageCandidate candidate,
            long currentTaskRevision,
            Map<String, Object> bindings
    ) {
        return activate(new RuntimePackageExecutionPlan(candidate.packageId(), candidate.packageRevision(),
                candidate.taskId(), candidate.taskRevision(), candidate.activationMode(),
                Map.of(candidate.entrypoint(), candidate.source()), candidate.entrypoint(), java.util.Set.of()),
                currentTaskRevision, bindings);
    }

    public synchronized PackageActivationResult activate(
            RuntimePackageExecutionPlan candidate,
            long currentTaskRevision,
            Map<String, Object> bindings
    ) {
        return activateInternal(candidate, currentTaskRevision, bindings, true);
    }

    public synchronized PackageActivationResult restore(
            RuntimePackageExecutionPlan persisted,
            Map<String, Object> bindings
    ) {
        return activateInternal(persisted, persisted.taskRevision(), bindings, false);
    }

    private PackageActivationResult activateInternal(
            RuntimePackageExecutionPlan candidate,
            long currentTaskRevision,
            Map<String, Object> bindings,
            boolean enforceTaskRevision
    ) {
        RuntimePackageExecutionPlan previous = active.get(candidate.packageId());
        long previousRevision = previous == null ? 0 : previous.packageRevision();
        if (enforceTaskRevision && candidate.taskRevision() != currentTaskRevision) {
            return rejected("STALE_TASK_REVISION", previousRevision);
        }
        if (candidate.activationMode() != ActivationMode.HOT_RUNTIME) {
            return rejected("ACTIVATION_REQUIRES_LIFECYCLE", previousRevision);
        }
        if (candidate.packageRevision() <= previousRevision) {
            return rejected("STALE_PACKAGE_REVISION", previousRevision);
        }
        try {
            Object value = runtime.load(candidate.packageId(), candidate.packageRevision(),
                    candidate.modules(), candidate.entrypoint(), bindings);
            active.put(candidate.packageId(), candidate);
            return new PackageActivationResult(true, "", candidate.packageRevision(), value, java.util.List.of());
        } catch (ScriptRejectedException rejected) {
            return new PackageActivationResult(false, "PREFLIGHT_REJECTED", previousRevision,
                    null, rejected.result().diagnostics());
        } catch (Exception | LinkageError executionFailure) {
            return rejected("EXECUTION_FAILED", previousRevision);
        }
    }

    public synchronized Optional<RuntimePackageExecutionPlan> active(UUID packageId) {
        return Optional.ofNullable(active.get(packageId));
    }

    public synchronized void fire(String event, Object payload) {
        runtime.fire(event, payload);
        pruneInactive();
    }

    public synchronized void tick(long tick) {
        runtime.tick(tick);
        pruneInactive();
    }

    public synchronized boolean unload(UUID packageId) throws Exception {
        try { return runtime.unload(packageId); }
        finally { if (!runtime.isLoaded(packageId)) active.remove(packageId); }
    }

    public synchronized boolean isLoaded(UUID packageId) { return runtime.isLoaded(packageId); }

    @Override
    public synchronized void close() throws Exception {
        active.clear();
        runtime.close();
    }

    private static PackageActivationResult rejected(String code, long activeRevision) {
        return new PackageActivationResult(false, code, activeRevision, null, java.util.List.of());
    }

    private void pruneInactive() {
        active.keySet().removeIf(packageId -> !runtime.isLoaded(packageId));
    }
}
