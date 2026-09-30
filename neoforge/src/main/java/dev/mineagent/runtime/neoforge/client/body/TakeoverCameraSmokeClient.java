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
    private static int freeFrames, independentTurns;
    private static boolean lastFree;
    private static float lastYaw, lastBodyYaw, eventYaw, eventPitch, bodyYaw, bodyPitch;
    private static String error = "";
    private static final Set<String> views = new LinkedHashSet<>();

    public static void begin() {
        if (!Boolean.getBoolean("mineagent.skillSmoke")) throw new IllegalStateException("CAMERA_SMOKE_DISABLED");
        recording = true;
        tick = -1;
        events = rendered = subTickChanges = interpolated = 0;
        freeFrames = independentTurns = 0;
        lastFree = false;
        error = "";
        views.clear();
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void camera(ViewportEvent.ComputeCameraAngles event) {
        var mc = Minecraft.getInstance();
        if (!recording || !AutonomousBodyClient.active() || event.getCamera().entity() != mc.player) return;
        bodyYaw = mc.player.getYRot();
        bodyPitch = mc.player.getXRot();
        eventYaw = event.getYaw();
        eventPitch = event.getPitch();
        boolean free = TakeoverCameraClient.observingFreely();
        if (free) {
            freeFrames++;
            if (lastFree && Math.abs(TakeoverCameraRotation.shortestDelta(lastYaw, eventYaw)) < .01f
                    && Math.abs(TakeoverCameraRotation.shortestDelta(lastBodyYaw, bodyYaw)) > .1f) independentTurns++;
        }
        lastFree = free;
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
                "interpolatedFrames", interpolated, "freeFrames", freeFrames, "independentBodyTurns", independentTurns,
                "views", List.copyOf(views), "error", error);
    }

    /** Exercise actual native callbacks, including the production mixin and NeoForge input events. */
    public static void drag(boolean release) {
        var mc = Minecraft.getInstance();
        require(mc.isWindowActive() && mc.screen == null, "DRAG_NEEDS_FOCUSED_GAME");
        float yaw = mc.player.getYRot(), pitch = mc.player.getXRot();
        var input = AutonomyVirtualInput.snapshot();
        var picked = mc.player.pick(mc.player.blockInteractionRange(), 0, false).getLocation();
        double x = mc.getWindow().getScreenWidth() * .65, y = mc.getWindow().getScreenHeight() * .65;
        move(x, y); button(1); move(x + 120, y - 55);
        if (release) button(0);
        require(TakeoverCameraClient.observingFreely(), "DRAG_DID_NOT_FREE_CAMERA");
        require(mc.player.getYRot() == yaw && mc.player.getXRot() == pitch
                && mc.player.pick(mc.player.blockInteractionRange(), 0, false).getLocation().equals(picked)
                && AutonomyVirtualInput.snapshot().equals(input), "DRAG_CHANGED_BODY_INPUT_OR_PICK");
        require(!mc.mouseHandler.isMouseGrabbed(), "DRAG_GRABBED_CURSOR");
        require(TakeoverCameraClient.observation().get("dragging").equals(!release), "DRAG_RELEASE_STATE");
    }

    public static void move(double x, double y) {
        try {
            var method = net.minecraft.client.MouseHandler.class.getDeclaredMethod("onMove", long.class, double.class, double.class);
            method.setAccessible(true);
            method.invoke(Minecraft.getInstance().mouseHandler, Minecraft.getInstance().getWindow().handle(), x, y);
        } catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
    }

    public static void button(int action) {
        try {
            var method = net.minecraft.client.MouseHandler.class.getDeclaredMethod("onButton", long.class, net.minecraft.client.input.MouseButtonInfo.class, int.class);
            method.setAccessible(true);
            method.invoke(Minecraft.getInstance().mouseHandler, Minecraft.getInstance().getWindow().handle(), new net.minecraft.client.input.MouseButtonInfo(1, 0), action);
        } catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
    }

    public static void key(int code) {
        var mc = Minecraft.getInstance();
        var input = (dev.mineagent.runtime.neoforge.mixin.client.WorkspaceKeyboardAccess)mc.keyboardHandler;
        var key = new net.minecraft.client.input.KeyEvent(code, 0, 0);
        input.mineagent$keyPress(mc.getWindow().handle(), 1, key);
        input.mineagent$keyPress(mc.getWindow().handle(), 0, key);
    }

    private static void require(boolean condition, String code) { if (!condition) throw new IllegalStateException(code); }

    private TakeoverCameraSmokeClient() {}
}
