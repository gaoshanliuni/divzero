package dev.mineagent.runtime.client.webui;
import dev.mineagent.runtime.api.ui.*;
import java.util.*;

/** Stable local data partition only; a new server-approved Session is still required for every restoration. */
public record DeliveryDraftScope(String server,String serverIdentity,UUID world,UUID viewer,UUID delivery,
        UUID packageId,long packageRevision,String canonical,String entry){
    public DeliveryDraftScope{if(server==null||server.isBlank()||server.length()>512||serverIdentity==null||!serverIdentity.matches("[a-fA-F0-9]{64}")||world==null||viewer==null||delivery==null||packageId==null||packageRevision<1||canonical==null||!canonical.matches("[a-f0-9]{64}")||entry==null||entry.length()>256)throw new IllegalArgumentException("DELIVERY_DRAFT_SCOPE");serverIdentity=serverIdentity.toLowerCase(Locale.ROOT);}
    public static DeliveryDraftScope of(String server,String identity,UiProtocol.Session session,String canonical){
        var b=Objects.requireNonNull(session).binding();UUID delivery=DeliveryProtocol.deliveryId(b);
        return new DeliveryDraftScope(server,identity,b.worldId(),b.viewerPlayerId(),delivery,b.ownerPackageId(),b.packageRevision(),canonical,b.entryPath());
    }
    public String storageKey(){return server.length()+":"+server+"|"+serverIdentity+"|"+world+"|"+viewer+"|"+delivery+"|"+packageId+"|"+packageRevision+"|"+canonical+"|"+entry;}
}
