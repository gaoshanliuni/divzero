package dev.mineagent.runtime.api.ui;
import java.util.UUID;
import static dev.mineagent.runtime.api.ui.UiProtocol.*;
/** Presentation is scoped to the already-delegated page, never to a shell, HUD or another view. */
public final class UiPresentationPolicy {
    private UiPresentationPolicy(){}
    public static boolean canAgent(Session source,UUID viewer,UUID pkg,long revision,String view){
        if(source==null||source.status()!=Status.RENDERED)return false;var b=source.binding();
        return b.viewerPlayerId().equals(viewer)&&b.ownerPackageId().equals(pkg)&&b.packageRevision()==revision&&b.viewId().equals(view)
                &&b.actorKind()==ActorKind.AGENT&&b.taskId()!=null&&b.taskRevision()>0
                &&(UiInteractionScope.pageOnly(b)||UiInteractionScope.scoreLayout(b)||WorldUiProtocol.bound(b)||ContainerProtocol.bound(b));
    }
}
