package dev.mineagent.runtime.client.webui;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** Client-thread FIFO: resolve trusted input target, interrupt it, then forward input. Agent work keeps its ordering. */
public final class ManagedInputDispatcher {
    public record TargetRequest(String kind,double x,double y,int width,int height,String atlasToken,String atlasView,boolean held){
        public TargetRequest(String kind,double x,double y,int width,int height){this(kind,x,y,width,height,"","",false);}
        public TargetRequest{if(!Set.of("pointer","keyboard","motion","release").contains(kind)||!Double.isFinite(x)||!Double.isFinite(y)||width<1||height<1||atlasToken==null||atlasView==null||atlasView.length()>128||!atlasToken.isEmpty()&&!atlasToken.matches("[0-9a-f]{24}"))throw new IllegalArgumentException("UI_INPUT_TARGET");}
    }
    private record Entry(TargetRequest request,Runnable action,boolean agent,boolean motion){Entry(TargetRequest r,Runnable a,boolean agent){this(r,a,agent,false);}}
    private final Function<TargetRequest,CompletableFuture<String>> resolver;
    private final Consumer<String> interrupt;private final Consumer<Runnable> executor;private final Runnable failure;private final int limit;
    private final ArrayDeque<Entry> queue=new ArrayDeque<>();private Entry active;private long generation;
    public ManagedInputDispatcher(Function<TargetRequest,CompletableFuture<String>> resolver,Consumer<String> interrupt,Consumer<Runnable> executor,Runnable failure,int limit){
        this.resolver=Objects.requireNonNull(resolver);this.interrupt=Objects.requireNonNull(interrupt);this.executor=Objects.requireNonNull(executor);this.failure=Objects.requireNonNull(failure);
        if(limit<1||limit>256)throw new IllegalArgumentException("UI_INPUT_QUEUE_BUDGET");this.limit=limit;
    }
    public boolean input(TargetRequest request,Runnable action){return enqueue(new Entry(Objects.requireNonNull(request),Objects.requireNonNull(action),false));}
    public boolean passthrough(Runnable action){return enqueue(new Entry(null,Objects.requireNonNull(action),false));}
    public boolean afterInputs(Runnable action){return enqueue(new Entry(null,Objects.requireNonNull(action),true));}
    public boolean motion(Runnable action){
        return motion(null,action);
    }
    public boolean motion(TargetRequest request,Runnable action){
        var entry=new Entry(request,Objects.requireNonNull(action),false,true);
        if(!queue.isEmpty()&&queue.getLast().motion){queue.removeLast();queue.addLast(entry);return true;}
        return enqueue(entry);
    }
    private boolean enqueue(Entry entry){
        if(queue.size()+(active==null?0:1)>=limit){failure.run();cancelNativeInputs();return false;}
        queue.add(entry);pump();return true;
    }
    private void pump(){
        while(active==null&&!queue.isEmpty()){
            Entry entry=queue.remove();
            if(entry.request==null){entry.action.run();continue;}
            active=entry;long epoch=generation;
            try{resolver.apply(entry.request).whenComplete((target,error)->executor.accept(()->{
                if(active!=entry||generation!=epoch)return;
                try{if(error!=null||target==null){if(!entry.motion)failure.run();}else{if(!target.isEmpty()&&!entry.motion&&!entry.request.kind().equals("release"))interrupt.accept(target);entry.action.run();}}
                finally{if(active==entry){active=null;pump();}}
            }));}catch(RuntimeException error){active=null;failure.run();continue;}
            return;
        }
    }
    public void cancelNativeInputs(){
        generation++;active=null;queue.removeIf(e->!e.agent);pump();
    }
}
