package dev.mineagent.runtime.client.webui;
import dev.mineagent.runtime.api.ui.UiProtocol.*;
import java.util.*;
/** Local draft identity, not an authorization token. New sessions must still be issued by the server. */
public record ContentDraftScope(String server,UUID world,UUID viewer,UUID packageId,long packageRevision,String packageVersion,String entry,String target){
    public ContentDraftScope{Objects.requireNonNull(server);Objects.requireNonNull(world);Objects.requireNonNull(viewer);Objects.requireNonNull(packageId);Objects.requireNonNull(packageVersion);Objects.requireNonNull(entry);Objects.requireNonNull(target);if(server.length()>512||packageRevision<1)throw new IllegalArgumentException("DRAFT_SCOPE");}
    public static ContentDraftScope of(String server,Binding b){return new ContentDraftScope(server,b.worldId(),b.viewerPlayerId(),b.ownerPackageId(),b.packageRevision(),b.packageVersion(),b.entryPath(),b.targetObjectId());}
    public void requireRestore(String server,Binding next,String oldView){
        if(!equals(of(server,next))||next.viewId().equals(oldView)||next.actorKind()!=ActorKind.PLAYER||!next.actorId().equals(next.viewerPlayerId())||next.preview()||!next.capabilities().equals(Set.of("scoreview.read")))throw new SecurityException("DRAFT_SCOPE_OR_RESTORE_PERMISSION");
    }
    public String storageKey(){return server.length()+":"+server+"|"+world+"|"+viewer+"|"+packageId+"|"+packageRevision+"|"+packageVersion+"|"+entry+"|"+target;}
}
