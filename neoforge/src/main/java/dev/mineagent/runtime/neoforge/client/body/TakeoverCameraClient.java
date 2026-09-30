package dev.mineagent.runtime.neoforge.client.body;

import dev.mineagent.runtime.client.control.TakeoverCameraRotation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import org.lwjgl.glfw.GLFW;

/** Smooths the observing camera only. Native entity aim, reach tests and network input stay untouched. */
@EventBusSubscriber(modid = "mineagent_runtime", value = Dist.CLIENT)
public final class TakeoverCameraClient {
    private static final TakeoverCameraRotation ROTATION = new TakeoverCameraRotation();
    private static Object session, level;
    private static LocalPlayer player;
    private static Vec3 position;
    private static TakeoverCameraRotation.Angles displayed;
    private static boolean dragging;
    private static double pointerX, pointerY;

    private static boolean synchronizeContext() {
        var mc = Minecraft.getInstance();
        Object next = PlayerBodyControlClient.cameraSession();
        if (next == null || mc.player == null || mc.level == null || !mc.player.isAlive()) {
            session = level = null;
            player = null;
            position = null;
            displayed = null;
            cancelDrag();
            return false;
        }
        if (session != next || player != mc.player || level != mc.level) {
            session = next;
            player = mc.player;
            level = mc.level;
            position = player.position();
            ROTATION.reset(player.getYRot(), player.getXRot());
            displayed = ROTATION.sample(1);
            cancelDrag();
        }
        return true;
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void beforeTick(ClientTickEvent.Pre event) {
        // Seed before any controller writes the first tick's body rotation.
        synchronizeContext();
        if (!canRotate()) cancelDrag();
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void afterTick(ClientTickEvent.Post event) {
        if (!synchronizeContext()) return;
        if (position.distanceToSqr(player.position()) > 64) {
            ROTATION.reset(player.getYRot(), player.getXRot());
            displayed = ROTATION.sample(1);
            cancelDrag();
        } else {
            ROTATION.tick(player.getYRot(), player.getXRot());
        }
        position = player.position();
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void cameraAngles(ViewportEvent.ComputeCameraAngles event) {
        if (!synchronizeContext() || event.getCamera().entity() != player
                || player.isPassenger() || player.isSleeping()) return;
        var angles = ROTATION.sample(event.getPartialTick());
        displayed = angles;
        // This event runs before native front/back offset and collision clipping.
        // Preserve other camera offsets and roll; do not write LocalPlayer rotations.
        event.setYaw(event.getYaw() + TakeoverCameraRotation.shortestDelta(player.getYRot(), angles.yaw()));
        event.setPitch(event.getPitch() + angles.pitch() - player.getXRot());
    }

    private static boolean canRotate() {
        var mc = Minecraft.getInstance();
        return AutonomousBodyClient.active() && mc.player != null && mc.player.isAlive()
                && mc.getCameraEntity() == mc.player && !mc.player.isPassenger() && !mc.player.isSleeping()
                && mc.screen == null && mc.getOverlay() == null && mc.isWindowActive();
    }

    public static void cancelDrag() {
        if (dragging) ((dev.mineagent.runtime.neoforge.mixin.client.WorkspaceMouseAccess)Minecraft.getInstance().mouseHandler).mineagent$activeButton(null);
        dragging = false;
    }

    public static boolean mouseButton(InputEvent.MouseButton.Pre event) {
        if (event.getButton() != GLFW.GLFW_MOUSE_BUTTON_RIGHT) return false;
        if (event.getAction() == GLFW.GLFW_RELEASE) {
            boolean consumed = dragging;
            cancelDrag();
            return consumed;
        }
        if (event.getAction() != GLFW.GLFW_PRESS || !canRotate() || !synchronizeContext()) return false;
        var mc = Minecraft.getInstance();
        // A visible panel still owns its area. No grabbing, warping, or real item-use input.
        if (dev.mineagent.runtime.neoforge.client.nativeui.AutonomyControlPanel.containsPointer()) return false;
        pointerX = mc.mouseHandler.xpos();
        pointerY = mc.mouseHandler.ypos();
        dragging = true;
        return true;
    }

    public static void mouseMoved(long window, double x, double y) {
        var mc = Minecraft.getInstance();
        if (window != mc.getWindow().handle() || !dragging) return;
        if (!canRotate() || !synchronizeContext() || !dragging) { cancelDrag(); return; }
        double dx = x - pointerX, dy = y - pointerY;
        pointerX = x; pointerY = y;
        if (dx == 0 && dy == 0) return;
        double sensitivity = mc.options.sensitivity().get() * .6 + .2;
        double scale = sensitivity * sensitivity * sensitivity * 8 * .15;
        // Front view mirrors pitch after the camera event; keep mouse motion natural there too.
        ROTATION.turn(displayed, dx * scale * (mc.options.invertMouseX().get() ? -1 : 1),
                dy * scale * (mc.options.invertMouseY().get() ? -1 : 1) * (mc.options.getCameraType().isMirrored() ? -1 : 1));
    }

    public static boolean observingFreely() { return synchronizeContext() && ROTATION.observingFreely(); }

    public static void followAi() {
        if (!synchronizeContext()) return;
        cancelDrag();
        ROTATION.follow(player.getYRot(), player.getXRot());
        displayed = ROTATION.sample(1);
        setPerspective(net.minecraft.client.CameraType.FIRST_PERSON);
    }

    @SubscribeEvent public static void crosshair(RenderGuiLayerEvent.Pre event) {
        // Free observation has no aiming authority; do not present its center as the attack target.
        if (event.getName().equals(VanillaGuiLayers.CROSSHAIR) && observingFreely()) event.setCanceled(true);
    }

    public static java.util.Map<String,Object> observation() {
        boolean free = observingFreely();
        return java.util.Map.of("free", free, "dragging", dragging, "yaw", displayed == null ? 0 : displayed.yaw(),
                "pitch", displayed == null ? 0 : displayed.pitch());
    }

    public static void cyclePerspective() {
        if (!PlayerBodyControlClient.active()) return;
        setPerspective(Minecraft.getInstance().options.getCameraType().cycle());
    }

    private static void setPerspective(net.minecraft.client.CameraType type) {
        var mc = Minecraft.getInstance();
        var previous = mc.options.getCameraType();
        mc.options.setCameraType(type);
        if (previous.isFirstPerson() != mc.options.getCameraType().isFirstPerson()) {
            mc.gameRenderer.checkEntityPostEffect(mc.options.getCameraType().isFirstPerson() ? mc.getCameraEntity() : null);
        }
        mc.levelRenderer.needsUpdate();
    }

    public static String perspectiveLabel() {
        return switch (Minecraft.getInstance().options.getCameraType()) {
            case FIRST_PERSON -> "视角：第一人称";
            case THIRD_PERSON_BACK -> "视角：第三人称（背后）";
            case THIRD_PERSON_FRONT -> "视角：第三人称（正面）";
        };
    }

    private TakeoverCameraClient() {}
}
