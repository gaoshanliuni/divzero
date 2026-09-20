package dev.mineagent.runtime.core.ui;
import dev.mineagent.runtime.api.ui.ContainerProtocol.*;
import java.util.*;
/** One menu actor and lifecycle. Native slot rules stay in Port; the bridge only fences and reads actual results. */
public final class ContainerTransaction implements AutoCloseable {
    public interface Port{boolean valid();Raw capture();void execute(Action action);void close();}
    private record Operation(long expected,Action action,Result result){}
    private final Port port;private final int maximum;private final Map<UUID,Operation> ledger=new LinkedHashMap<>();
    private String digest;private long revision;private boolean closed;
    public ContainerTransaction(Port port,int maximum){this.port=Objects.requireNonNull(port);if(maximum<1||maximum>4096)throw new IllegalArgumentException("CONTAINER_LEDGER_BUDGET");this.maximum=maximum;}
    public synchronized State read(){requireValid();Raw raw=port.capture();if(!Objects.equals(digest,raw.digest())){digest=raw.digest();revision++;}return new State(revision,raw.menuId(),raw.nativeStateId(),raw.menuType(),raw.actorId(),raw.slots(),raw.carried());}
    public synchronized Result apply(UUID operation,long expected,Action action){
        requireValid();Objects.requireNonNull(operation);Objects.requireNonNull(action);var prior=ledger.get(operation);
        if(prior!=null){if(prior.expected()!=expected||!prior.action().equals(action))throw new IllegalStateException("OPERATION_ID_REUSED");return prior.result();}
        if(ledger.size()>=maximum)throw new IllegalStateException("CONTAINER_LEDGER_FULL");State before=read();
        if(before.revision()!=expected)throw new IllegalStateException("CONTAINER_STATE_CONFLICT");
        try{port.execute(action);State after=read();var result=new Result("NATIVE_MENU",before.revision()!=after.revision(),after);ledger.put(operation,new Operation(expected,action,result));return result;}
        catch(RuntimeException unknown){close();throw new IllegalStateException("CONTAINER_OUTCOME_UNKNOWN",unknown);}
    }
    private void requireValid(){if(closed||!port.valid())throw new IllegalStateException("CONTAINER_CLOSED");}
    @Override public synchronized void close(){if(!closed){closed=true;port.close();}}
}
