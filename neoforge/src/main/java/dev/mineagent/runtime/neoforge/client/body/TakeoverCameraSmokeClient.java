package dev.mineagent.runtime.neoforge.client.body;

import dev.mineagent.runtime.client.control.TakeoverCameraRotation;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import java.util.*;

/** Opt-in evidence from actual rendered frames, separate from the camera implementation. */
@EventBusSubscriber(modid = "mineagent_runtime", value = Dist.CLIENT)
public final class TakeoverCameraSmokeClient {
    private static boolean recording;
    private static int tick = -1, events, rendered, subTickChanges, interpolated;
    private static float lastYaw, lastBodyYaw, eventYaw, eventPitch, bodyYaw, bodyPitch;
    private static String error = "";
    private static final Set<String> views = new LinkedHashSet<>();

    public static void begin() {
        if (!Boolean.getBoolean("mineagent.skillSmoke")) throw new IllegalStateException("CAMERA_SMOKE_DISABLED");
        recording = true;
        tick = -1;
        events = rendered = subTickChanges = interpolated = 0;
        error = "";
        views.clear();
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void camera(ViewportEvent.ComputeCameraAngles event) {
        var mc = Minecraft.getInstance();
        if (!recording || !AutonomousBodyClient.active() || event.getCamera().getEntity() != mc.player) return;
        bodyYaw = mc.player.getYRot();
        bodyPitch = mc.player.getXRot();
        eventYaw = event.getYaw();
        eventPitch = event.getPitch();
        if (tick == mc.player.tickCount && bodyYaw == lastBodyYaw
                && Math.abs(TakeoverCameraRotation.shortestDelta(lastYaw, eventYaw)) > .02f) subTickChanges++;
        if (event.getPartialTick() > .05 && event.getPartialTick() < .95
                && Math.abs(TakeoverCameraRotation.shortestDelta(bodyYaw, eventYaw)) > .05f) interpolated++;
        tick = mc.player.tickCount;
        lastBodyYaw = bodyYaw;
        lastYaw = eventYaw;
        events++;
    }

    @SubscribeEvent
    public static void frame(RenderFrameEvent.Post event) {
        var mc = Minecraft.getInstance();
        if (!recording || !AutonomousBodyClient.active() || rendered == events || mc.player == null) return;
        var camera = mc.gameRenderer.getMainCamera();
        boolean front = mc.options.getCameraType().isMirrored();
        if (Math.abs(TakeoverCameraRotation.shortestDelta(eventYaw + (front ? 180 : 0), camera.yRot())) > .01
                || Math.abs((front ? -eventPitch : eventPitch) - camera.xRot()) > .01) error = "CAMERA_RENDER_DID_NOT_USE_INTERPOLATION";
        if (mc.player.getYRot() != bodyYaw || mc.player.getXRot() != bodyPitch) error = "RENDER_CHANGED_BODY_AIM";
        views.add(mc.options.getCameraType().name());
        rendered = events;
    }

    public static Map<String, Object> report() {
        return Map.of("cameraEvents", events, "renderedFrames", rendered, "subTickAngleChanges", subTickChanges,
                "interpolatedFrames", interpolated, "views", List.copyOf(views), "error", error);
    }

    private TakeoverCameraSmokeClient() {}
}
