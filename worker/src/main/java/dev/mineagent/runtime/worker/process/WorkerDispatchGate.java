package dev.mineagent.runtime.worker.process;
import java.util.concurrent.Callable;
import java.util.function.BooleanSupplier;
/** Evaluate admission inside the actual request lane, not only before enqueueing work. */
public final class WorkerDispatchGate {
    private WorkerDispatchGate(){}
    public static final class Rejected extends java.io.IOException {public Rejected(){super("REQUEST_AUTHORITY_REVOKED");}}
    public static <T>T dispatch(BooleanSupplier allowed,Callable<T> action)throws Exception{
        if(!allowed.getAsBoolean())throw new Rejected();return action.call();
    }
}
