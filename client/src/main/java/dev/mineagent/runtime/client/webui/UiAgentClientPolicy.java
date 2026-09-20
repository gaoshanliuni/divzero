package dev.mineagent.runtime.client.webui;
import dev.mineagent.runtime.api.ui.UiProtocol.*;
import dev.mineagent.runtime.api.ui.UiAgentRpc.Command;
import java.util.UUID;
public final class UiAgentClientPolicy {
    private UiAgentClientPolicy(){}
    public static void requireRebind(Session source,Session target,UUID expectedAgent){
        requireRebind(source,target,expectedAgent,true);
    }
    public static void requireRebind(Session source,Session target,UUID expectedAgent,boolean visible){
        if(!visible)throw new SecurityException("VIEW_NOT_RENDERED");
        if(source==null||target==null)throw new SecurityException("UI_AGENT_SESSION_MISSING");var a=source.binding();var b=target.binding();
        boolean page=dev.mineagent.runtime.api.ui.UiInteractionScope.pageOnly(a)&&dev.mineagent.runtime.api.ui.UiInteractionScope.pageOnly(b);
        boolean score=dev.mineagent.runtime.api.ui.UiInteractionScope.scoreLayout(a)&&dev.mineagent.runtime.api.ui.UiInteractionScope.scoreLayout(b)&&b.capabilities().equals(dev.mineagent.runtime.api.ui.UiInteractionScope.SCORE_LAYOUT);
        if(source.sessionId().equals(target.sessionId())||!a.actorId().equals(a.viewerPlayerId())||source.status()!=Status.RENDERED||target.status()==Status.CLOSED||a.actorKind()!=ActorKind.PLAYER||b.actorKind()!=ActorKind.AGENT||(!page&&!score)||!b.actorId().equals(expectedAgent)
                ||!source.serverInstanceId().equals(target.serverInstanceId())||!a.viewerPlayerId().equals(b.viewerPlayerId())||!a.worldId().equals(b.worldId())
                ||!a.viewId().equals(b.viewId())||!a.ownerPackageId().equals(b.ownerPackageId())||a.packageRevision()!=b.packageRevision()
                ||!a.packageVersion().equals(b.packageVersion())||!a.entryPath().equals(b.entryPath())||!a.targetObjectId().equals(b.targetObjectId())
                ||b.taskId()==null||b.taskRevision()<1)throw new SecurityException("UI_AGENT_REBIND_MISMATCH");
    }
    public static void requirePageBinding(Session shell,Session page,String view,UUID pkg,long revision,String entry){
        if(shell==null||page==null)throw new SecurityException("UI_PAGE_BINDING");var a=shell.binding();var b=page.binding();
        if(!dev.mineagent.runtime.api.ui.UiInteractionScope.pageOnly(b)||b.actorKind()!=ActorKind.PLAYER||!b.actorId().equals(a.viewerPlayerId())||!b.viewerPlayerId().equals(a.viewerPlayerId())
                ||!b.worldId().equals(a.worldId())||!page.serverInstanceId().equals(shell.serverInstanceId())||b.taskId()!=null||b.taskRevision()!=0||page.status()==Status.CLOSED
                ||!b.viewId().equals(view)||b.viewId().equals(a.viewId())||!b.ownerPackageId().equals(pkg)||b.packageRevision()!=revision||!b.entryPath().equals(entry))throw new SecurityException("UI_PAGE_BINDING");
    }
    public static boolean matches(Session session,Command c){
        return session!=null&&session.status()==Status.RENDERED&&session.binding().actorKind()==ActorKind.AGENT&&session.sessionId().equals(c.sessionId())
                &&session.binding().viewId().equals(c.viewId())&&session.pageGeneration()==c.pageGeneration()&&session.controlEpoch()==c.controlEpoch()
                &&session.binding().taskRevision()==c.taskRevision();
    }
}
