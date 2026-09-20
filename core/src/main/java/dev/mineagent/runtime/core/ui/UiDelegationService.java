package dev.mineagent.runtime.core.ui;

import dev.mineagent.runtime.api.task.ManagedTask;
import dev.mineagent.runtime.api.task.TaskStatus;
import dev.mineagent.runtime.api.ui.UiProtocol.*;
import java.time.Clock;
import java.util.*;

/** Explicit, target-scoped Agent authority. Revocation never silently turns the same document into a PLAYER session.
 * This core registry alone does not expose a client tool; the server dispatcher must bind a new UiSession to its lease.
 */
public final class UiDelegationService {
    private static final Set<String> LAYOUT_SCOPE=Set.of("scoreview.read","scoreview.patch");
    public record Lease(UUID leaseId,UUID sourceSessionId,Binding binding,long expiresAtMillis) {}
    private static final class State { final Lease lease;Code denial=Code.OK;State(Lease lease){this.lease=lease;} }
    private final UUID worldId;
    private final Clock clock;
    private final int maximumActive;
    private final Map<UUID,State> byTask=new LinkedHashMap<>();
    public UiDelegationService(UUID worldId,Clock clock,int maximumActive){
        this.worldId=Objects.requireNonNull(worldId);this.clock=Objects.requireNonNull(clock);
        if(maximumActive<1||maximumActive>128)throw new IllegalArgumentException("UI_DELEGATION_BUDGET");this.maximumActive=maximumActive;
    }
    public synchronized Lease issue(UUID requester,Session source,ManagedTask task,boolean mayDelegate,long ttlMillis){
        Objects.requireNonNull(source);Objects.requireNonNull(task);var b=source.binding();
        boolean pageOnly=dev.mineagent.runtime.api.ui.UiInteractionScope.pageOnly(b);
        if(!mayDelegate||source.status()!=Status.RENDERED||source.expiresAtMillis()<=clock.millis()
                ||!worldId.equals(b.worldId())||!worldId.equals(task.worldId())||!requester.equals(b.viewerPlayerId())
                ||b.actorKind()!=ActorKind.PLAYER||!requester.equals(b.actorId())||!requester.equals(task.ownerPlayerId())
                ||task.status()!=TaskStatus.RUNNING||(!pageOnly&&!dev.mineagent.runtime.api.ui.UiInteractionScope.scoreLayout(b)))throw new SecurityException("UI_DELEGATION_NOT_AUTHORIZED");
        if(ttlMillis<1||ttlMillis>900000)throw new IllegalArgumentException("UI_DELEGATION_TTL");
        expire();
        if(byTask.containsKey(task.taskId()))throw new IllegalStateException("UI_DELEGATION_TASK_ALREADY_USED");
        if(byTask.size()>=4096||byTask.values().stream().filter(s->s.denial==Code.OK).count()>=maximumActive)
            throw new IllegalStateException("UI_DELEGATION_BUDGET");
        if(byTask.values().stream().anyMatch(s->s.denial==Code.OK&&s.lease.binding().viewId().equals(b.viewId())&&s.lease.binding().viewerPlayerId().equals(requester)))
            throw new IllegalStateException("UI_CONTROL_BUSY");
        var binding=new Binding(b.viewId(),b.ownerPackageId(),b.packageRevision(),b.packageVersion(),b.entryPath(),worldId,
                requester,task.agentId(),ActorKind.AGENT,task.taskId(),task.intentRevision(),b.targetObjectId(),pageOnly,pageOnly?dev.mineagent.runtime.api.ui.UiInteractionScope.PAGE:LAYOUT_SCOPE);
        var lease=new Lease(UUID.randomUUID(),source.sessionId(),binding,Math.min(source.expiresAtMillis(),clock.millis()+ttlMillis));
        byTask.put(task.taskId(),new State(lease));return lease;
    }
    public synchronized Code authorize(Binding binding,ManagedTask live,long packageRevision,boolean liveAuthority){
        if(binding==null||binding.taskId()==null)return Code.PERMISSION_DENIED;
        var state=byTask.get(binding.taskId());
        if(state==null||!state.lease.binding().equals(binding))return Code.PERMISSION_DENIED;
        if(state.denial!=Code.OK)return state.denial;
        if(clock.millis()>=state.lease.expiresAtMillis())return state.denial=Code.EXPIRED;
        if(!liveAuthority)return state.denial=Code.PERMISSION_DENIED;
        if(packageRevision!=binding.packageRevision())return state.denial=Code.STALE_PACKAGE;
        if(live==null||live.status()!=TaskStatus.RUNNING||!live.taskId().equals(binding.taskId())||!live.worldId().equals(worldId)
                ||!live.agentId().equals(binding.actorId())||!live.ownerPlayerId().equals(binding.viewerPlayerId())
                ||live.intentRevision()!=binding.taskRevision())return state.denial=Code.STALE_TASK;
        return Code.OK;
    }
    public synchronized Lease issueContainer(UUID requester,Session shell,Binding target,ManagedTask task,boolean approved,long ttlMillis){
        if(!approved||shell==null||task==null||target==null||shell.status()!=Status.RENDERED||shell.expiresAtMillis()<=clock.millis()
                ||!shell.binding().capabilities().contains("container.agent")||shell.binding().actorKind()!=ActorKind.PLAYER||!shell.binding().actorId().equals(requester)||!shell.binding().viewerPlayerId().equals(requester)
                ||!worldId.equals(shell.binding().worldId())||!worldId.equals(target.worldId())||!worldId.equals(task.worldId())||!requester.equals(target.viewerPlayerId())||!requester.equals(task.ownerPlayerId())
                ||!dev.mineagent.runtime.api.ui.ContainerProtocol.bound(target)||target.actorKind()!=ActorKind.AGENT||!target.actorId().equals(task.agentId())
                ||!task.taskId().equals(target.taskId())||target.taskRevision()!=task.intentRevision()||task.status()!=TaskStatus.RUNNING)throw new SecurityException("UI_CONTAINER_DELEGATION_DENIED");
        if(ttlMillis<1||ttlMillis>900000)throw new IllegalArgumentException("UI_DELEGATION_TTL");expire();
        if(byTask.containsKey(task.taskId()))throw new IllegalStateException("UI_DELEGATION_TASK_ALREADY_USED");
        if(byTask.size()>=4096||byTask.values().stream().filter(s->s.denial==Code.OK).count()>=maximumActive)throw new IllegalStateException("UI_DELEGATION_BUDGET");
        var lease=new Lease(UUID.randomUUID(),shell.sessionId(),target,Math.min(shell.expiresAtMillis(),clock.millis()+ttlMillis));byTask.put(task.taskId(),new State(lease));return lease;
    }
    /** Admission preflight; call against the current Session before creating a task or invoking Native interaction. */
    public void requireWorldSource(UUID requester,Session source,boolean approved){
        if(!approved||source==null||source.status()!=Status.RENDERED||source.expiresAtMillis()<=clock.millis())throw new SecurityException("WORLD_UI_DELEGATION_DENIED");
        var b=source.binding();
        if(!dev.mineagent.runtime.api.ui.WorldUiProtocol.bound(b)||!worldId.equals(b.worldId())||b.actorKind()!=ActorKind.PLAYER||!Objects.equals(requester,b.actorId())||!Objects.equals(requester,b.viewerPlayerId())||b.taskId()!=null)throw new SecurityException("WORLD_UI_DELEGATION_DENIED");
    }
    public synchronized Lease issueWorld(UUID requester,Session source,Binding target,ManagedTask task,boolean approved,long ttlMillis){
        requireWorldSource(requester,source,approved);
        if(target==null||task==null)throw new SecurityException("WORLD_UI_DELEGATION_DENIED");var b=source.binding();
        if(!dev.mineagent.runtime.api.ui.WorldUiProtocol.bound(b)||!dev.mineagent.runtime.api.ui.WorldUiProtocol.bound(target)||b.actorKind()!=ActorKind.PLAYER||!requester.equals(b.actorId())||!requester.equals(b.viewerPlayerId())||b.taskId()!=null||b.viewId().equals(target.viewId())
                ||!worldId.equals(b.worldId())||!worldId.equals(target.worldId())||!worldId.equals(task.worldId())||!requester.equals(task.ownerPlayerId())||!requester.equals(target.viewerPlayerId())||target.actorKind()!=ActorKind.AGENT||!target.actorId().equals(task.agentId())||!task.taskId().equals(target.taskId())||target.taskRevision()!=task.intentRevision()||task.status()!=TaskStatus.RUNNING
                ||!b.ownerPackageId().equals(target.ownerPackageId())||b.packageRevision()!=target.packageRevision()||!b.packageVersion().equals(target.packageVersion())||!b.entryPath().equals(target.entryPath())||!b.targetObjectId().equals(target.targetObjectId()))throw new SecurityException("WORLD_UI_DELEGATION_DENIED");
        if(ttlMillis<1||ttlMillis>900000)throw new IllegalArgumentException("UI_DELEGATION_TTL");expire();
        if(byTask.containsKey(task.taskId()))throw new IllegalStateException("UI_DELEGATION_TASK_ALREADY_USED");
        if(byTask.size()>=4096||byTask.values().stream().filter(s->s.denial==Code.OK).count()>=maximumActive)throw new IllegalStateException("UI_DELEGATION_BUDGET");
        var lease=new Lease(UUID.randomUUID(),source.sessionId(),target,Math.min(source.expiresAtMillis(),clock.millis()+ttlMillis));byTask.put(task.taskId(),new State(lease));return lease;
    }
    public synchronized void revoke(UUID requester,UUID leaseId){
        var state=byTask.values().stream().filter(s->s.lease.leaseId().equals(leaseId)).findFirst().orElseThrow(()->new SecurityException("UI_LEASE_NOT_OWNED"));
        if(!state.lease.binding().viewerPlayerId().equals(requester))throw new SecurityException("UI_LEASE_NOT_OWNED");
        if(state.denial==Code.OK)state.denial=Code.USER_INTERRUPTED;
    }
    public synchronized void disconnect(UUID viewer){byTask.values().stream().filter(s->s.lease.binding().viewerPlayerId().equals(viewer)&&s.denial==Code.OK).forEach(s->s.denial=Code.USER_INTERRUPTED);}
    public synchronized void clear(){byTask.clear();}
    private void expire(){byTask.values().stream().filter(s->s.denial==Code.OK&&s.lease.expiresAtMillis()<=clock.millis()).forEach(s->s.denial=Code.EXPIRED);}
}
