package dev.mineagent.runtime.client.webui;
import dev.mineagent.runtime.api.ui.*;
import java.util.*;

/** Native-created tokens prove receipt of an exact read response, not application semantics. One per view. */
public final class DeliveryReadWitness {
    public record Reading(String token,UiProtocol.Session context,long lifecycle,long revision,String sha256,long expiresAt){}
    private final LinkedHashMap<String,Reading> pending=new LinkedHashMap<>();
    private UiProtocol.Session context;private long lifecycle,highest;private String highestHash="";private Reading confirmed;
    public Optional<String> issue(UiProtocol.Session current,long life,long revision,String hash,long tick){
        if(current==null||life<1||revision<1||revision>9007199254740991L||hash==null||!hash.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("DELIVERY_READ_WITNESS");
        if(context==null||!ReadOnlyUiLease.sameContext(context,current)||lifecycle!=life){context=current;lifecycle=life;highest=0;highestHash="";confirmed=null;pending.clear();}
        pending.values().removeIf(r->tick>=r.expiresAt());
        if(revision<highest)return Optional.empty();if(revision==highest&&!hash.equals(highestHash))throw new IllegalArgumentException("DELIVERY_READ_REVISION_REUSED");
        highest=revision;highestHash=hash;
        if(confirmed!=null&&confirmed.revision()==revision&&confirmed.sha256().equals(hash))return Optional.empty();
        while(pending.size()>=4)pending.remove(pending.keySet().iterator().next());
        String token=UUID.randomUUID().toString();pending.put(token,new Reading(token,current,life,revision,hash,Math.addExact(tick,200)));return Optional.of(token);
    }
    public Reading consume(String token,UiProtocol.Session current,long life,long tick){
        var r=pending.get(token);if(r==null||tick>=r.expiresAt()||r.lifecycle()!=life||!ReadOnlyUiLease.sameContext(r.context(),current)||r.revision()<highest)throw new SecurityException("DELIVERY_READ_TOKEN_INVALID");
        pending.remove(token);return r;
    }
    public void confirm(Reading r){if(context!=null&&r.lifecycle()==lifecycle&&ReadOnlyUiLease.sameContext(context,r.context())&&(confirmed==null||r.revision()>=confirmed.revision()))confirmed=r;}
    public int pendingCount(){return pending.size();}
}
