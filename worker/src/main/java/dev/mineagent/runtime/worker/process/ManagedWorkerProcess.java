package dev.mineagent.runtime.worker.process;

import dev.mineagent.runtime.api.worker.WorkerEnvelope;
import dev.mineagent.runtime.worker.WorkerRequestHandler;
import dev.mineagent.runtime.worker.ipc.WorkerFrameCodec;
import java.io.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** One reader routes envelopes by request ID; cancelling one request never kills its neighbours. */
public final class ManagedWorkerProcess implements AutoCloseable {
    private final List<String> command;
    private final Duration timeout;
    private final WorkerFrameCodec codec = new WorkerFrameCodec(8 * 1024 * 1024);
    private final Object writer = new Object();
    private final ConcurrentMap<UUID, Pending> pending = new ConcurrentHashMap<>();
    private volatile Process process;
    private static final class Pending {
        final BlockingQueue<Object> frames = new LinkedBlockingQueue<>();
        volatile boolean cancelled;
    }
    public ManagedWorkerProcess(List<String> command, Duration timeout) { this.command=List.copyOf(command);this.timeout=timeout; }
    public synchronized void start() throws IOException {
        if(isAlive())return;
        Process current=new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.INHERIT).start();
        process=current;
        Thread.ofVirtual().name("mineagent-worker-reader").start(()->{
            try {
                while(process==current){var frame=codec.read(current.getInputStream());var target=pending.get(frame.requestId());if(target!=null)target.frames.add(frame);}
            }catch(IOException failure){if(process==current){current.destroy();pending.values().forEach(p->p.frames.add(failure));}}
        });
    }
    public boolean isAlive(){var current=process;return current!=null&&current.isAlive();}
    public WorkerEnvelope healthCheck()throws Exception{return request(new WorkerEnvelope(WorkerRequestHandler.PROTOCOL_VERSION,UUID.randomUUID(),"health.check",Map.of()));}
    public WorkerEnvelope request(WorkerEnvelope request)throws Exception{return request(request,timeout,()->true);}
    public WorkerEnvelope request(WorkerEnvelope request,BooleanSupplier permit)throws Exception{return request(request,timeout,permit);}
    public WorkerEnvelope request(WorkerEnvelope request,Duration duration)throws Exception{return request(request,duration,()->true);}
    public WorkerEnvelope request(WorkerEnvelope request,Duration duration,BooleanSupplier permit)throws Exception{return exchange(request,ignored->{},duration,permit,5);}
    public WorkerEnvelope streamRequest(WorkerEnvelope request,Consumer<WorkerEnvelope> consume)throws Exception{return streamRequest(request,consume,timeout,()->true);}
    public WorkerEnvelope streamRequest(WorkerEnvelope request,Consumer<WorkerEnvelope> consume,BooleanSupplier permit)throws Exception{return streamRequest(request,consume,timeout,permit);}
    public WorkerEnvelope streamRequest(WorkerEnvelope request,Consumer<WorkerEnvelope> consume,Duration duration)throws Exception{return streamRequest(request,consume,duration,()->true);}
    public WorkerEnvelope streamRequest(WorkerEnvelope request,Consumer<WorkerEnvelope> consume,Duration duration,BooleanSupplier permit)throws Exception{return exchange(request,consume,duration,permit,15);}
    private void send(WorkerEnvelope envelope)throws IOException{
        synchronized(writer){var current=process;if(current==null||!current.isAlive())throw new IOException("worker is not running");codec.write(current.getOutputStream(),envelope);}
    }
    public void cancel(UUID id){var p=pending.get(id);if(p!=null){p.cancelled=true;try{send(new WorkerEnvelope(1,UUID.randomUUID(),"request.cancel",Map.of("target",id.toString())));}catch(IOException ignored){}}}
    private WorkerEnvelope exchange(WorkerEnvelope request,Consumer<WorkerEnvelope> consume,Duration duration,BooleanSupplier permit,int maxMinutes)throws Exception{
        if(duration==null||duration.isZero()||duration.isNegative()||duration.compareTo(Duration.ofMinutes(maxMinutes))>0)throw new IllegalArgumentException("WORKER_REQUEST_TIMEOUT");
        if(!permit.getAsBoolean())throw new WorkerDispatchGate.Rejected();
        var state=new Pending();if(pending.putIfAbsent(request.requestId(),state)!=null)throw new IllegalStateException("WORKER_DUPLICATE_REQUEST");
        boolean complete=false;
        try{
            if(!permit.getAsBoolean())throw new WorkerDispatchGate.Rejected();send(request);
            long deadline=System.nanoTime()+duration.toNanos();int deltas=0;
            while(true){
                if(state.cancelled||!permit.getAsBoolean())throw new CancellationException("WORKER_REQUEST_CANCELLED");
                long remaining=deadline-System.nanoTime();if(remaining<=0)throw new TimeoutException("worker request timed out");
                Object value=state.frames.poll(Math.min(remaining,TimeUnit.MILLISECONDS.toNanos(100)),TimeUnit.NANOSECONDS);
                if(value==null)continue;if(value instanceof IOException failure)throw failure;
                var frame=(WorkerEnvelope)value;
                if(frame.type().equals("model.stream.delta")){if(++deltas>100000)throw new IOException("worker stream delta limit exceeded");consume.accept(frame);}else{complete=true;return frame;}
            }
        }finally{if(!complete)cancel(request.requestId());pending.remove(request.requestId(),state);}
    }
    @Override public synchronized void close()throws Exception{
        var current=process;if(current==null)return;
        pending.values().forEach(p->p.frames.add(new IOException("worker closed")));
        try{current.getOutputStream().close();}catch(IOException ignored){}
        if(!current.waitFor(2000,TimeUnit.MILLISECONDS)){current.destroy();if(!current.waitFor(1000,TimeUnit.MILLISECONDS))current.destroyForcibly();}
        process=null;
    }
}
