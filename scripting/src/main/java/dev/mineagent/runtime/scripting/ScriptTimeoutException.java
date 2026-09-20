package dev.mineagent.runtime.scripting;

public final class ScriptTimeoutException extends RuntimeException {
    public ScriptTimeoutException() {
        super("generated script exceeded execution deadline");
    }
}
