package dev.mineagent.runtime.worker.process;
/** A broken Worker transport does not prove that a Provider request was never charged or executed. */
public final class WorkerRetryPolicy {
    private WorkerRetryPolicy(){}
    public static boolean mayRetryAfterUncertainTransport(String type){return java.util.Set.of("health.check","provider.configure","provider.snapshot","storage.configure").contains(type);}
}
