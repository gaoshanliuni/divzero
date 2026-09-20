package dev.mineagent.runtime.client.webui;

import dev.mineagent.runtime.api.ui.UiAgentRpc.Command;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/** Per-control history: retain effectful RPCs, coalesce only in-flight read-only inspections. */
public final class UiAgentRpcHistory implements AutoCloseable {
    private record Entry(Command command,CompletableFuture<String> result){}
    private final Map<UUID,Entry> writes=new LinkedHashMap<>(),reads=new HashMap<>();
    private final int maxWrites,maxReads;
    private boolean closed;
    public UiAgentRpcHistory(int maxWrites,int maxReads){
        if(maxWrites<1||maxWrites>128||maxReads<1||maxReads>8)throw new IllegalArgumentException("UI_RPC_HISTORY_BUDGET");
        this.maxWrites=maxWrites;this.maxReads=maxReads;
    }
    public synchronized CompletableFuture<String> execute(Command command,Supplier<CompletableFuture<String>> action){
        Objects.requireNonNull(command);Objects.requireNonNull(action);
        if(closed)throw new IllegalStateException("USER_INTERRUPTED");
        Entry old=writes.get(command.requestId());if(old==null)old=reads.get(command.requestId());
        if(old!=null){
            if(!old.command.equals(command))throw new IllegalStateException("OPERATION_ID_REUSED");
            return old.result;
        }
        boolean read=Set.of("inspect","presentationInspect","captureChunk").contains(command.kind());
        if(read?reads.size()>=maxReads:writes.size()>=maxWrites)throw new IllegalStateException(read?"UI_RPC_BUSY":"LEDGER_FULL");
        var result=new CompletableFuture<String>();var entry=new Entry(command,result);
        (read?reads:writes).put(command.requestId(),entry); // Reserve before dispatch, including synchronous/reentrant completion.
        if(read)result.whenComplete((r,e)->{synchronized(this){reads.remove(command.requestId(),entry);}});
        try{Objects.requireNonNull(action.get()).whenComplete((value,error)->{
            if(error!=null)result.completeExceptionally(error);else result.complete(value);
        });}catch(RuntimeException failure){result.completeExceptionally(failure);}
        return result;
    }
    public synchronized int retainedWrites(){return writes.size();}
    public synchronized int pendingReads(){return reads.size();}
    @Override public synchronized void close(){
        closed=true;var pending=new ArrayList<Entry>(reads.values());pending.addAll(writes.values());reads.clear();writes.clear();
        pending.forEach(e->e.result.completeExceptionally(new IllegalStateException("USER_INTERRUPTED")));
    }
}
