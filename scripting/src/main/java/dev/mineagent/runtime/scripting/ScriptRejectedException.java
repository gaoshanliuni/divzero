package dev.mineagent.runtime.scripting;

import dev.mineagent.runtime.scripting.preflight.PreflightResult;

public final class ScriptRejectedException extends RuntimeException {
    private final PreflightResult result;

    public ScriptRejectedException(PreflightResult result) {
        super("脚本未通过 Preflight: " + result.diagnostics());
        this.result = result;
    }

    public PreflightResult result() {
        return result;
    }
}
