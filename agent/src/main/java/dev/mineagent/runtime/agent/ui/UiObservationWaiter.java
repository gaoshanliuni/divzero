package dev.mineagent.runtime.agent.ui;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.function.*;
/** Read-only bounded wait. Document changes, cancelled leases and unavailable views never count as a match. */
public final class UiObservationWaiter {
    public record Result(boolean matched,String status,String observation,int polls){}
    private static final ObjectMapper JSON=new ObjectMapper();
    private UiObservationWaiter(){}
    public static CompletableFuture<Result> waitFor(UiAgentController.Port port,UiCondition condition,Duration timeout,BooleanSupplier cancelled,Consumer<String> observer){
        return waitFor(port,condition,timeout,cancelled,observer,"");
    }
    public static CompletableFuture<Result> waitFor(UiAgentController.Port port,UiCondition condition,Duration timeout,BooleanSupplier cancelled,Consumer<String> observer,String expectedDocument){
        if(timeout.isNegative()||timeout.isZero()||timeout.compareTo(Duration.ofSeconds(15))>0)throw new IllegalArgumentException("UI_WAIT_TIMEOUT");
        return poll(port,condition,System.nanoTime()+timeout.toNanos(),cancelled,observer,expectedDocument,0).exceptionally(e->new Result(false,root(e) instanceof TimeoutException?"TIMEOUT":"FAILED","",0));
    }
    private static CompletableFuture<Result> poll(UiAgentController.Port port,UiCondition condition,long deadline,BooleanSupplier cancelled,Consumer<String> observer,String document,int count){
        if(cancelled.getAsBoolean())return CompletableFuture.completedFuture(new Result(false,"USER_INTERRUPTED","",count));
        long left=TimeUnit.NANOSECONDS.toMillis(deadline-System.nanoTime());if(left<=0)return CompletableFuture.completedFuture(new Result(false,"TIMEOUT","",count));
        return port.inspect().orTimeout(Math.max(1,left),TimeUnit.MILLISECONDS).thenCompose(value->{
            try{
                if(cancelled.getAsBoolean())return CompletableFuture.completedFuture(new Result(false,"USER_INTERRUPTED",value,count));
                if(value.length()>65536)throw new IllegalArgumentException("UI_OBSERVATION_LIMIT");
                var data=JSON.readTree(value);String status=data.path("status").asText(),id=data.path("documentId").asText();
                if(!status.equals("OBSERVED"))return CompletableFuture.completedFuture(new Result(false,status,value,count+1));
                if(id.isBlank()||(!document.isBlank()&&!document.equals(id)))return CompletableFuture.completedFuture(new Result(false,"STALE_VIEW",value,count+1));
                observer.accept(value);if(condition.matches(value))return CompletableFuture.completedFuture(new Result(true,"MATCHED",value,count+1));
                return CompletableFuture.runAsync(()->{},CompletableFuture.delayedExecutor(Math.min(100,Math.max(1,TimeUnit.NANOSECONDS.toMillis(deadline-System.nanoTime()))),TimeUnit.MILLISECONDS))
                        .thenCompose(ignored->poll(port,condition,deadline,cancelled,observer,id,count+1));
            }catch(Exception invalid){return CompletableFuture.failedFuture(invalid);}
        });
    }
    private static Throwable root(Throwable e){for(int i=0;i<8&&e.getCause()!=null;i++)e=e.getCause();return e;}
}
