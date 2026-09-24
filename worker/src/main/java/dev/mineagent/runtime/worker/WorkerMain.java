package dev.mineagent.runtime.worker;

import dev.mineagent.runtime.api.worker.WorkerEnvelope;
import dev.mineagent.runtime.worker.ipc.WorkerFrameCodec;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

public final class WorkerMain {
    private WorkerMain() {}
    public static void main(String[] args) {
        var codec=new WorkerFrameCodec(8*1024*1024);var output=new Object();
        var tasks=new ConcurrentHashMap<UUID,FutureTask<Void>>();
        var seen=ConcurrentHashMap.<UUID>newKeySet();
        var pool=Executors.newVirtualThreadPerTaskExecutor();
        java.util.function.Consumer<WorkerEnvelope> emit=frame->{synchronized(output){try{codec.write(System.out,frame);}catch(IOException e){throw new UncheckedIOException(e);}}};
        try(var handler=new WorkerRequestHandler()){
            try{
                while(true){
                    var request=codec.read(System.in);
                    if(request.type().equals("request.cancel")){
                        try{var task=tasks.get(UUID.fromString(String.valueOf(request.payload().get("target"))));if(task!=null){task.cancel(true);WorkerCancellation.cancel(UUID.fromString(String.valueOf(request.payload().get("target"))));}}catch(IllegalArgumentException ignored){}
                        continue;
                    }
                    if(!seen.add(request.requestId())){emit.accept(new WorkerEnvelope(1,request.requestId(),"error",Map.of("code","WORKER_DUPLICATE_REQUEST")));continue;}
                    if(Set.of("health.check","provider.configure","provider.snapshot","storage.configure").contains(request.type())){emit.accept(handler.handle(request));continue;}
                    var snapshot=handler.requestSnapshot();
                    var task=new FutureTask<Void>(()->{
                        java.util.function.Consumer<WorkerEnvelope> send=frame->{if(Thread.currentThread().isInterrupted())throw new CancellationException();emit.accept(frame);};
                        try(var cancellation=WorkerCancellation.enter(request.requestId())){var result=request.type().equals("model.stream")?snapshot.handleStreaming(request,send):snapshot.handle(request);send.accept(result);}
                        catch(CancellationException ignored){}
                        catch(Exception failure){if(!Thread.currentThread().isInterrupted())emit.accept(new WorkerEnvelope(1,request.requestId(),"error",Map.of("code","WORKER_REQUEST_FAILED")));}
                        return null;
                    }){@Override protected void done(){tasks.remove(request.requestId(),this);}};
                    tasks.put(request.requestId(),task);pool.execute(task);
                }
            }catch(EOFException end){/* parent closed input */}
            finally{tasks.values().forEach(t->t.cancel(true));pool.shutdownNow();pool.awaitTermination(5,TimeUnit.SECONDS);}
        }catch(IOException|UncheckedIOException|InterruptedException failure){System.err.println("MineAgent Worker IPC stopped: "+failure.getClass().getSimpleName());}
    }
}
