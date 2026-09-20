package dev.mineagent.runtime.api.ui;
import dev.mineagent.runtime.api.packages.RuntimeEntrypoint;
import java.util.*;
import static dev.mineagent.runtime.api.ui.UiProtocol.*;

/** Server-issued physical interaction grant. The browser never supplies an actor, instance or code authority. */
public final class WorldUiProtocol {
    public static final Set<String> CAPABILITIES=Set.of("worldui.read","worldui.action");
    private WorldUiProtocol(){}
    public static boolean bound(Binding b){return b!=null&&!b.preview()&&b.targetObjectId().startsWith("world:")&&b.capabilities().equals(CAPABILITIES);}
    public static String localStateTarget(Binding b){return bound(b)&&b.actorKind()==ActorKind.AGENT?b.targetObjectId()+"|actor="+b.actorId():b.targetObjectId();}
    public record Launch(UUID id,String viewId,UUID packageId,long packageRevision,String packageVersion,String entryPath,UUID worldId,UUID viewerId,UUID instanceId,UUID entityId,String part,String canonicalSha256,long expiresAt,UUID actorId,ActorKind actorKind,UUID taskId,long taskRevision){
        public Launch(UUID id,String viewId,UUID packageId,long packageRevision,String packageVersion,String entryPath,UUID worldId,UUID viewerId,UUID instanceId,UUID entityId,String part,String canonicalSha256,long expiresAt){this(id,viewId,packageId,packageRevision,packageVersion,entryPath,worldId,viewerId,instanceId,entityId,part,canonicalSha256,expiresAt,viewerId,ActorKind.PLAYER,null,0);}
        public Launch{
            Objects.requireNonNull(id);Objects.requireNonNull(packageId);Objects.requireNonNull(worldId);Objects.requireNonNull(viewerId);Objects.requireNonNull(instanceId);Objects.requireNonNull(entityId);
            if(viewId==null||viewId.isBlank()||viewId.length()>128||packageRevision<1||packageVersion==null||packageVersion.isBlank()||packageVersion.length()>64||part==null||!part.matches("[A-Za-z0-9_.-]{1,64}")||canonicalSha256==null||!canonicalSha256.matches("[a-f0-9]{64}")||expiresAt<1)throw new IllegalArgumentException("WORLD_UI_LAUNCH");
            RuntimeEntrypoint.requireRelativePath(entryPath);if(!entryPath.startsWith("ui/")||!entryPath.endsWith(".html"))throw new IllegalArgumentException("WORLD_UI_ENTRYPOINT");
            if(actorId==null&&actorKind==null&&taskId==null&&taskRevision==0){actorId=viewerId;actorKind=ActorKind.PLAYER;}
            if(actorId==null||actorKind==null||(actorKind==ActorKind.PLAYER?(!actorId.equals(viewerId)||taskId!=null||taskRevision!=0):(actorId.equals(viewerId)||taskId==null||taskRevision<1)))throw new IllegalArgumentException("WORLD_UI_ACTOR");
        }
        public Binding binding(){return new Binding(viewId,packageId,packageRevision,packageVersion,entryPath,worldId,viewerId,actorId,actorKind,taskId,taskRevision,"world:"+instanceId,false,CAPABILITIES);}
        public Launch forAgent(UUID actor,UUID task,long intent){return forAgent(actor,task,intent,expiresAt);}
        public Launch forAgent(UUID actor,UUID task,long intent,long deadline){if(actorKind!=ActorKind.PLAYER)throw new IllegalArgumentException("WORLD_UI_SOURCE_ACTOR");return new Launch(UUID.randomUUID(),UUID.randomUUID().toString(),packageId,packageRevision,packageVersion,entryPath,worldId,viewerId,instanceId,entityId,part,canonicalSha256,deadline,actor,ActorKind.AGENT,task,intent);}
    }
    public static void requireAgentTransition(Session source,Launch target,UUID actor){
        if(source==null||target==null||source.status()!=Status.RENDERED)throw new SecurityException("WORLD_UI_AGENT_TRANSITION");var a=source.binding();var b=target.binding();
        if(!bound(a)||!bound(b)||a.actorKind()!=ActorKind.PLAYER||!a.actorId().equals(a.viewerPlayerId())||a.taskId()!=null||b.actorKind()!=ActorKind.AGENT||!b.actorId().equals(actor)||a.viewId().equals(b.viewId())||!a.ownerPackageId().equals(b.ownerPackageId())||a.packageRevision()!=b.packageRevision()||!a.packageVersion().equals(b.packageVersion())||!a.entryPath().equals(b.entryPath())||!a.worldId().equals(b.worldId())||!a.viewerPlayerId().equals(b.viewerPlayerId())||!a.targetObjectId().equals(b.targetObjectId()))throw new SecurityException("WORLD_UI_AGENT_TRANSITION");
    }
    public static void require(Session content,Session shell,Launch launch,String canonical){
        if(content==null||shell==null||launch==null||!content.binding().equals(launch.binding())||!content.serverInstanceId().equals(shell.serverInstanceId())||!shell.binding().viewerPlayerId().equals(launch.viewerId())||!shell.binding().worldId().equals(launch.worldId())||!launch.canonicalSha256().equals(canonical))throw new SecurityException("WORLD_UI_SESSION_CONTEXT");
    }
}
