package dev.mineagent.runtime.api.ui;
import java.util.Set;
/** Diagnostic only: a client-reported stop source never grants permissions or changes cancellation semantics. */
public final class UiInterruptSignal {
    private static final Set<String> SIGNALS=Set.of("NATIVE_INPUT","FRAME_NAVIGATION","VIEW_CLOSED","VIEW_HIDDEN","PLAYER_STOP","PORT_CANCEL","ALL_VIEWS_INTERRUPTED","TAKEOVER","SERVER_VIEW_CLOSED");
    private UiInterruptSignal(){}
    public static String normalize(String value){return value!=null&&SIGNALS.contains(value)?value:"CLIENT_REQUEST";}
}
