package dev.mineagent.runtime.core.task;
import java.util.UUID;
import java.util.Objects;
import java.util.function.BooleanSupplier;
/** One body controller owner; callers supply conditional cancellation for only the owned commands. */
public final class ActionControlLease {
    private UUID owner;private BooleanSupplier current;private Runnable cancel;
    public boolean claim(UUID id,BooleanSupplier current,Runnable cancel){Objects.requireNonNull(id);Objects.requireNonNull(current);Objects.requireNonNull(cancel);if(owner!=null)return owner.equals(id);owner=id;this.current=current;this.cancel=cancel;return true;}
    public boolean owned(){return owner!=null;}
    public boolean owns(UUID id){return id!=null&&id.equals(owner);}
    public void release(UUID id){if(owns(id)){owner=null;current=null;cancel=null;}}
    public boolean validate(){if(owner==null)return true;boolean valid;try{valid=current.getAsBoolean();}catch(RuntimeException e){valid=false;}if(valid)return true;var stop=cancel;owner=null;current=null;cancel=null;stop.run();return false;}
    public void cancel(){if(owner!=null){var stop=cancel;owner=null;current=null;cancel=null;stop.run();}}
}
