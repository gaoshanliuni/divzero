package dev.mineagent.runtime.api.ui;

import dev.mineagent.runtime.api.ui.UiProtocol.*;
import java.util.*;

/** Lease-only changes never create a new Actor, document, package or world authority. */
public final class ReadOnlyUiLease {
    private ReadOnlyUiLease() {}
    public static boolean lostLease(Code code){return code==Code.EXPIRED||code==Code.VIEW_NOT_RENDERED;}
    public static boolean eligible(Binding b) {
        return b != null && !b.preview() && b.actorKind()==ActorKind.PLAYER && b.actorId().equals(b.viewerPlayerId())
                && b.taskId()==null && b.taskRevision()==0 && !b.targetObjectId().isBlank() && b.capabilities().equals(Set.of("scoreview.read"));
    }
    public static boolean sameContext(Session a, Session b) {
        if (Objects.equals(a,b)) return true;
        return a!=null && b!=null && eligible(a.binding()) && a.binding().equals(b.binding())
                && a.sessionId().equals(b.sessionId()) && a.serverInstanceId().equals(b.serverInstanceId())
                && a.pageGeneration()==b.pageGeneration() && a.controlEpoch()==b.controlEpoch()
                && a.status()==Status.RENDERED && b.status()==Status.RENDERED;
    }
    public static Session accept(Session current,Session offered) {
        if(current==null||offered==null||!eligible(current.binding())||!sameContext(current,offered)
                ||offered.status()!=Status.RENDERED||offered.expiresAtMillis()<current.expiresAtMillis())
            throw new SecurityException("UI_LEASE_CONTEXT");
        return offered;
    }
    /** Only passive, unchanged read-only documents may accept fresh admission after their old lease expired. */
    public static Session readmitted(Session old,Session fresh) {
        if(old==null||fresh==null||!eligible(old.binding())||!old.binding().equals(fresh.binding())
                ||old.sessionId().equals(fresh.sessionId())||!old.serverInstanceId().equals(fresh.serverInstanceId())
                ||fresh.status()!=Status.LOADING||fresh.pageGeneration()!=1||fresh.controlEpoch()!=1)
            throw new SecurityException("HUD_READMISSION_CONTEXT");
        return fresh;
    }
}
