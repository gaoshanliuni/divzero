package dev.mineagent.runtime.client.webui;
import dev.mineagent.runtime.api.ui.UiProtocol.*;
import java.util.*;
public final class ContentPreviewPolicy {
    private ContentPreviewPolicy(){}
    public static void requireContainer(Session content,Session shell,UUID pkg,long revision){
        if(content==null||!dev.mineagent.runtime.api.ui.ContainerProtocol.bound(content.binding())||content.binding().taskId()!=null)throw new SecurityException("CONTAINER_SESSION_CONTEXT");
        require(content,shell,pkg,revision,UUID.fromString(content.binding().targetObjectId()),false,false);
    }
    public static void requireAgentContainer(Session content,Session shell,UUID pkg,long revision,UUID agent){
        if(content==null||shell==null)throw new SecurityException("CONTAINER_AGENT_SESSION_CONTEXT");var b=content.binding();var a=shell.binding();
        if(!dev.mineagent.runtime.api.ui.ContainerProtocol.bound(b)||b.actorKind()!=ActorKind.AGENT||!b.actorId().equals(agent)||b.taskId()==null||b.taskRevision()<1
                ||!b.ownerPackageId().equals(pkg)||b.packageRevision()!=revision||!b.viewerPlayerId().equals(a.viewerPlayerId())||!b.worldId().equals(a.worldId())||!content.serverInstanceId().equals(shell.serverInstanceId())||content.status()!=Status.LOADING)throw new SecurityException("CONTAINER_AGENT_SESSION_CONTEXT");
        UUID.fromString(b.targetObjectId());
    }
    public static void require(Session content,Session shell,UUID pkg,long revision,UUID target,boolean preview,boolean readOnly){
        var b=content.binding();var parent=shell.binding();
        if(!content.serverInstanceId().equals(shell.serverInstanceId())||!b.ownerPackageId().equals(pkg)||b.packageRevision()!=revision||b.preview()!=preview||b.actorKind()!=ActorKind.PLAYER
                ||!b.actorId().equals(parent.viewerPlayerId())||!b.viewerPlayerId().equals(parent.viewerPlayerId())||!b.worldId().equals(parent.worldId())||!b.targetObjectId().equals(target.toString())
                ||(readOnly&&!b.capabilities().equals(Set.of("scoreview.read"))))throw new SecurityException("CONTENT_SESSION_CONTEXT");
    }
}
