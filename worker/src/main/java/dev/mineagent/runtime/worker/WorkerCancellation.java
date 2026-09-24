package dev.mineagent.runtime.worker;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
/** Request-scoped close hooks unblock streaming response readers when their request is interrupted. */
public final class WorkerCancellation implements AutoCloseable {
    private static final ConcurrentMap<UUID,WorkerCancellation> LIVE=new ConcurrentHashMap<>();
    private static final ThreadLocal<WorkerCancellation> CURRENT=new ThreadLocal<>();
    private final UUID id;private final AtomicBoolean cancelled=new AtomicBoolean();
    private final ConcurrentLinkedQueue<AutoCloseable> resources=new ConcurrentLinkedQueue<>();
    private WorkerCancellation(UUID id){this.id=id;}
    public static WorkerCancellation enter(UUID id){var c=new WorkerCancellation(id);LIVE.put(id,c);CURRENT.set(c);return c;}
    public static void watch(AutoCloseable resource){var c=CURRENT.get();if(c==null)return;c.resources.add(resource);if(c.cancelled.get()||Thread.currentThread().isInterrupted())closeResource(resource);}
    public static void cancel(UUID id){var c=LIVE.get(id);if(c!=null){c.cancelled.set(true);Thread.ofVirtual().name("mineagent-request-cancel").start(()->c.resources.forEach(WorkerCancellation::closeResource));}}
    private static void closeResource(AutoCloseable resource){try{resource.close();}catch(Exception ignored){}}
    @Override public void close(){LIVE.remove(id,this);CURRENT.remove();resources.clear();}
}
