package dev.mineagent.runtime.core.ui;

import dev.mineagent.runtime.api.ui.UiProtocol.*;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/** Server authority: ephemeral session grants, bounded non-evicting operation receipts and revocable control leases. */
public final class UiSessionService {
    private static final UUID NO_OPERATION = new UUID(0, 0);
    private final UUID serverInstanceId = UUID.randomUUID();
    private final UUID worldId;
    private final Clock clock;
    private final Function<Session, Code> liveAuthorization;
    private final int maxSessions, maxOperations;
    private final Map<UUID, State> states = new LinkedHashMap<>();
    private static final class State {
        Session session;
        UUID writer;
        final Map<UUID, Operation> operations = new LinkedHashMap<>();
        State(Session session) { this.session = session; }
    }
    private record Operation(Request request, Receipt receipt, boolean write) {}
    public record CompletedOperation(Request request,Receipt receipt) {}

    public UiSessionService(UUID worldId, Clock clock, Function<Session, Code> liveAuthorization,
                            int maxSessions, int maxOperations) {
        this.worldId = java.util.Objects.requireNonNull(worldId); this.clock = java.util.Objects.requireNonNull(clock);
        this.liveAuthorization = java.util.Objects.requireNonNull(liveAuthorization);
        if (maxSessions < 1 || maxOperations < 1) throw new IllegalArgumentException("UI_BUDGET");
        this.maxSessions = maxSessions; this.maxOperations = maxOperations;
    }
    public synchronized Session open(Binding binding, boolean realClientAvailable, boolean authorized, long ttlMillis) {
        if (ttlMillis < 1 || ttlMillis > 86_400_000) throw new IllegalArgumentException("UI_TTL");
        return openUntil(binding,realClientAvailable,authorized,Math.addExact(clock.millis(),ttlMillis));
    }
    /** A grant deadline is absolute; measuring a relative TTL twice must not extend it. */
    public synchronized Session openUntil(Binding binding, boolean realClientAvailable, boolean authorized, long expiresAtMillis) {
        if (!realClientAvailable || !authorized || !binding.worldId().equals(worldId)) throw new SecurityException("UI_ADMISSION_DENIED");
        long now=clock.millis();if(expiresAtMillis<=now||expiresAtMillis-now>86_400_000)throw new IllegalArgumentException("UI_DEADLINE");
        states.values().removeIf(s -> s.session.status() == Status.CLOSED || s.session.expiresAtMillis() <= now);
        if (states.size() >= maxSessions) throw new IllegalStateException("UI_SESSION_BUDGET");
        Session session = new Session(UUID.randomUUID(), serverInstanceId, binding, 1, 1, expiresAtMillis, Status.LOADING);
        if (liveAuthorization.apply(session) != Code.OK) throw new SecurityException("UI_ADMISSION_REVOKED");
        if(clock.millis()>=expiresAtMillis)throw new SecurityException("UI_ADMISSION_EXPIRED");
        states.put(session.sessionId(), new State(session));
        return session;
    }
    public synchronized List<Session> list(UUID viewer) {
        return states.values().stream().filter(s -> access(s, viewer) == Code.OK).map(s -> s.session).toList();
    }
    public synchronized Optional<Session> get(UUID viewer, UUID id) {
        State state = states.get(id);
        return access(state, viewer) == Code.OK ? Optional.of(state.session) : Optional.empty();
    }
    public synchronized List<CompletedOperation> completedOperations(UUID viewer,UUID sessionId,int limit) {
        if(limit<1||limit>64)throw new IllegalArgumentException("UI_EVIDENCE_LIMIT");
        State state=states.get(sessionId);if(access(state,viewer)!=Code.OK)return List.of();
        return state.operations.values().stream().filter(o->o.receipt().code()!=Code.IN_PROGRESS)
                .map(o->new CompletedOperation(o.request(),o.receipt())).toList().reversed().stream().limit(limit).toList();
    }
    public synchronized Receipt rendered(UUID viewer, UUID id, long pageGeneration) {
        State state = states.get(id); Code access = access(state, viewer);
        if (access != Code.OK) return Receipt.of(NO_OPERATION, access);
        if (state.session.pageGeneration() != pageGeneration) return Receipt.of(NO_OPERATION, Code.STALE_VIEW);
        state.session = copy(state.session, pageGeneration, state.session.controlEpoch(), Status.RENDERED);
        return Receipt.of(NO_OPERATION, Code.OK);
    }
    public synchronized Receipt begin(UUID viewer, Request request, String capability, boolean write) {
        State state = states.get(request.sessionId()); Code access = access(state, viewer);
        if (access != Code.OK) return Receipt.of(request.operationId(), access);
        Session s = state.session;
        if (!s.binding().capabilities().contains(capability)) return Receipt.of(request.operationId(), Code.PERMISSION_DENIED);
        // Admission/identity is checked even for terminal replay: no private receipt leaks after permission revocation.
        Operation old = state.operations.get(request.operationId());
        if (old != null) return old.request().equals(request) ? old.receipt() : Receipt.of(request.operationId(), Code.OPERATION_ID_REUSED);
        if (s.pageGeneration() != request.pageGeneration()) return Receipt.of(request.operationId(), Code.STALE_VIEW);
        if (s.binding().taskRevision() != request.taskRevision()) return Receipt.of(request.operationId(), Code.STALE_TASK);
        if (s.controlEpoch() != request.controlEpoch()) return Receipt.of(request.operationId(), Code.USER_INTERRUPTED);
        if (s.status() != Status.RENDERED) return Receipt.of(request.operationId(), Code.VIEW_NOT_RENDERED);
        if (write && s.binding().preview()) return Receipt.of(request.operationId(), Code.PREVIEW_READ_ONLY);
        if (write && state.writer != null) return Receipt.of(request.operationId(), Code.BUSY);
        if (state.operations.size() >= maxOperations) return Receipt.of(request.operationId(), Code.LEDGER_FULL);
        state.operations.put(request.operationId(), new Operation(request, Receipt.of(request.operationId(), Code.IN_PROGRESS), write));
        if (write) state.writer = request.operationId();
        return Receipt.of(request.operationId(), Code.OK);
    }
    /** Only for dispatcher-allowlisted side-effect-free reads. Does not cache large chunks or periodic snapshots. */
    public synchronized Code checkRead(UUID viewer, Request request, String capability) {
        State state = states.get(request.sessionId()); Code allowed = access(state, viewer);
        if (allowed != Code.OK) return allowed;
        Session s = state.session;
        if (!s.binding().capabilities().contains(capability)) return Code.PERMISSION_DENIED;
        if (s.pageGeneration() != request.pageGeneration()) return Code.STALE_VIEW;
        if (s.binding().taskRevision() != request.taskRevision()) return Code.STALE_TASK;
        if (s.controlEpoch() != request.controlEpoch()) return Code.USER_INTERRUPTED;
        return s.status() == Status.RENDERED ? Code.OK : Code.VIEW_NOT_RENDERED;
    }
    /** Trusted dispatcher opt-in after a successful read. No new grant, mutation replay, or expired-session resurrection. */
    public synchronized Session renewReadOnly(UUID viewer,Request request,long ttlMillis) {
        if(ttlMillis<1||ttlMillis>86_400_000)throw new IllegalArgumentException("UI_TTL");
        if(!request.action().equals("scoreview.read")||checkRead(viewer,request,"scoreview.read")!=Code.OK)
            throw new SecurityException("UI_LEASE_NOT_AUTHORIZED");
        State state=states.get(request.sessionId());Session s=state.session;
        if(!dev.mineagent.runtime.api.ui.ReadOnlyUiLease.eligible(s.binding()))throw new SecurityException("UI_LEASE_NOT_READ_ONLY");
        state.session=new Session(s.sessionId(),s.serverInstanceId(),s.binding(),s.pageGeneration(),s.controlEpoch(),
                Math.max(s.expiresAtMillis(),Math.addExact(clock.millis(),ttlMillis)),s.status());
        return state.session;
    }
    /** Called by the trusted dispatcher after real completion, never directly by a page. */
    public synchronized Receipt complete(Request request, Code code, Map<String, String> values) {
        if (code == Code.OK || code == Code.IN_PROGRESS || code == Code.BUSY) throw new IllegalArgumentException("UI_NONTERMINAL_RESULT");
        State state = states.get(request.sessionId());
        if (state == null) return Receipt.of(request.operationId(), Code.VIEW_NOT_RENDERED);
        Operation old = state.operations.get(request.operationId());
        if (old == null || !old.request().equals(request)) return Receipt.of(request.operationId(), Code.INVALID_REQUEST);
        if (old.receipt().code() != Code.IN_PROGRESS) return old.receipt();
        Code authorization = access(state, state.session.binding().viewerPlayerId());
        Receipt result = authorization == Code.OK ? new Receipt(request.operationId(), code, values) : Receipt.of(request.operationId(), authorization);
        state.operations.put(request.operationId(), new Operation(request, result, old.write()));
        if (request.operationId().equals(state.writer)) state.writer = null;
        return result;
    }
    public synchronized Session interrupt(UUID viewer, UUID id) {
        State state = require(viewer, id);
        cancelOperations(state, Code.USER_INTERRUPTED);
        state.session = copy(state.session, state.session.pageGeneration(), state.session.controlEpoch() + 1, state.session.status());
        return state.session;
    }
    public synchronized Session navigate(UUID viewer, UUID id) {
        State state = require(viewer, id);
        cancelOperations(state, Code.STALE_VIEW);
        state.session = copy(state.session, state.session.pageGeneration() + 1, state.session.controlEpoch() + 1, Status.LOADING);
        return state.session;
    }
    public synchronized Receipt close(UUID viewer, UUID id) {
        State state = states.get(id);
        if(state==null)return Receipt.of(NO_OPERATION,Code.VIEW_NOT_RENDERED);
        if(!state.session.binding().viewerPlayerId().equals(viewer))return Receipt.of(NO_OPERATION,Code.PERMISSION_DENIED);
        cancelOperations(state, Code.USER_INTERRUPTED);
        state.session = copy(state.session, state.session.pageGeneration(), state.session.controlEpoch() + 1, Status.CLOSED);
        return Receipt.of(NO_OPERATION, Code.CLOSED);
    }
    /** Administrative invalidation, not a webpage capability. Closes even sessions whose old package lost authorization. */
    public synchronized List<Session> invalidatePackage(UUID packageId){
        var result=new java.util.ArrayList<Session>();
        for(var state:states.values())if(state.session.status()!=Status.CLOSED&&state.session.binding().ownerPackageId().equals(packageId)){
            result.add(state.session);cancelOperations(state,Code.STALE_PACKAGE);state.session=copy(state.session,state.session.pageGeneration(),state.session.controlEpoch()+1,Status.CLOSED);
        }
        return List.copyOf(result);
    }
    /** Owning Native adapter retires its exact lease after external authorization was revoked. Not a client admission API. */
    public synchronized void retire(UUID viewer,UUID id,String viewId){var state=states.get(id);if(state==null||!state.session.binding().viewerPlayerId().equals(viewer)||!state.session.binding().viewId().equals(viewId))return;cancelOperations(state,Code.CLOSED);state.session=copy(state.session,state.session.pageGeneration(),state.session.controlEpoch()+1,Status.CLOSED);}
    public synchronized void disconnect(UUID viewer) {
        for (State state : states.values()) if (state.session.binding().viewerPlayerId().equals(viewer)) {
            cancelOperations(state, Code.USER_INTERRUPTED);
            state.session = copy(state.session, state.session.pageGeneration(), state.session.controlEpoch() + 1, Status.CLOSED);
        }
    }
    private State require(UUID viewer, UUID id) {
        State state = states.get(id);
        if (access(state, viewer) != Code.OK) throw new SecurityException("UI_SESSION_DENIED");
        return state;
    }
    private Code access(State state, UUID viewer) {
        if (state == null) return Code.VIEW_NOT_RENDERED;
        if (!state.session.binding().viewerPlayerId().equals(viewer)) return Code.PERMISSION_DENIED;
        if (state.session.status() == Status.CLOSED) return Code.VIEW_NOT_RENDERED;
        if (state.session.expiresAtMillis() <= clock.millis()) return Code.EXPIRED;
        Code result = liveAuthorization.apply(state.session);
        return result == null ? Code.PERMISSION_DENIED : result;
    }
    private void cancelOperations(State state, Code reason) {
        state.operations.replaceAll((id, op) -> op.receipt().code() == Code.IN_PROGRESS
                ? new Operation(op.request(), Receipt.of(id, reason), op.write()) : op);
        state.writer = null;
    }
    private Session copy(Session s, long page, long control, Status status) {
        return new Session(s.sessionId(), s.serverInstanceId(), s.binding(), page, control, s.expiresAtMillis(), status);
    }
}
