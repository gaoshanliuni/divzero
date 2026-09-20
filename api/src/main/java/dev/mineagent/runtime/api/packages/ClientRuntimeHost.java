package dev.mineagent.runtime.api.packages;

/** Explicitly consented local CLIENT native host. Implementations remain process- and connection-bound. */
public interface ClientRuntimeHost {
    Object minecraft();
    Object player();
    long tick();
    String packageId();
    void status(String value);
    String status();
    void message(String value);
    AutoCloseable onTick(Runnable callback);
    AutoCloseable cleanupStatus(String value);
}
