package dev.mineagent.runtime.neoforge.client.webui;

import dev.mineagent.runtime.agent.ui.UiAgentController;
import dev.mineagent.runtime.api.ui.UiProtocol.ActorKind;
import dev.mineagent.runtime.neoforge.client.nativeui.NativePackageAgent;
import java.util.*;
import java.util.concurrent.*;

/** Single native widget-root observer/control boundary; trusted workspace controls are never delegated. */
public final class PackagePageAgent {
    public static UiAgentController.Port controlPreview(String view){if(PackageContentClient.owns(view))throw new SecurityException("CONTENT_AUTOMATION_REQUIRES_AGENT_DELEGATION");return NativePackageAgent.control(view,false);}
    public static UiAgentController.Port controlDelegated(String view,UUID session){return controlDelegated(view,session,false);}
    public static UiAgentController.Port controlDelegated(String view,UUID sessionId,boolean presentationOnly){var session=PackageContentClient.session(view);if(session==null||session.binding().actorKind()!=ActorKind.AGENT||!session.sessionId().equals(sessionId))throw new SecurityException("CONTENT_AGENT_SESSION_REQUIRED");return NativePackageAgent.control(view,presentationOnly);}
    public static CompletableFuture<String> inspectManagedView(String view){return NativePackageAgent.inspect(view);}
    public static CompletableFuture<PackageViewCapture.Captured> captureManagedView(String view){return NativePackageAgent.capture(view);}
    public static void cancel(String view){NativePackageAgent.cancel(view);}
    static void interruptControls(){NativePackageAgent.clear();}
    public static void clear(){NativePackageAgent.clear();}
    static List<Long> frameIds(Collection<Long> ids){return ids==null?List.of():List.copyOf(ids);}
    private PackagePageAgent(){}
}
