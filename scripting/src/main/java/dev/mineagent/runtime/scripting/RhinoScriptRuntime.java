package dev.mineagent.runtime.scripting;

import dev.latvian.mods.rhino.ContextFactory;
import dev.mineagent.runtime.scripting.preflight.ScriptPreflight;

import java.util.Map;
import java.time.Duration;
import dev.latvian.mods.rhino.Context;

public final class RhinoScriptRuntime {
    private final ScriptPreflight preflight = new ScriptPreflight();
    private final Duration executionTimeout;

    public RhinoScriptRuntime() {
        this(Duration.ofMillis(250));
    }

    public RhinoScriptRuntime(Duration executionTimeout) {
        if (executionTimeout == null || executionTimeout.isNegative() || executionTimeout.isZero()) {
            throw new IllegalArgumentException("execution timeout must be positive");
        }
        this.executionTimeout = executionTimeout;
    }

    public Object evaluate(String source, Map<String, Object> bindings) {
        var inspection = preflight.inspect(source);
        if (!inspection.accepted()) {
            throw new ScriptRejectedException(inspection);
        }

        var context = new DeadlineContextFactory(executionTimeout).enter();
        var scope = context.initStandardObjects();
        bindings.forEach((name, value) -> context.addToScope(scope, name, value));
        return context.evaluateString(scope, source, "generated.js", 1, null);
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
    }

    private static final class DeadlineContext extends Context {
        private final long deadlineNanos;

        private DeadlineContext(ContextFactory factory, long deadlineNanos) {
            super(factory);
            this.deadlineNanos = deadlineNanos;
            setGenerateObserverCount(true);
            setInstructionObserverThreshold(10_000);
        }

        @Override
        protected void observeInstructionCount(int instructionCount) {
            if (System.nanoTime() >= deadlineNanos) {
                throw new ScriptTimeoutException();
            }
        }
    }
}
