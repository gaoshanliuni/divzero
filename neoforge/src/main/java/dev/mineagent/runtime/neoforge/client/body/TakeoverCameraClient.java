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

/** Smooths the observing camera only. Native entity aim, reach tests and network input stay untouched. */
@EventBusSubscriber(modid = "mineagent_runtime", value = Dist.CLIENT)
public final class TakeoverCameraClient {
    private static final TakeoverCameraRotation ROTATION = new TakeoverCameraRotation();
    private static Object session, level;
    private static LocalPlayer player;
    private static Vec3 position;

    private static boolean synchronizeContext() {
        var mc = Minecraft.getInstance();
        Object next = PlayerBodyControlClient.cameraSession();
        if (next == null || mc.player == null || mc.level == null || !mc.player.isAlive()) {
            session = level = null;
            player = null;
            position = null;
            return false;
        }
        if (session != next || player != mc.player || level != mc.level) {
            session = next;
            player = mc.player;
            level = mc.level;
            position = player.position();
            ROTATION.reset(player.getYRot(), player.getXRot());
        }
        return true;
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void beforeTick(ClientTickEvent.Pre event) {
        // Seed before any controller writes the first tick's body rotation.
        synchronizeContext();
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void afterTick(ClientTickEvent.Post event) {
        if (!synchronizeContext()) return;
        if (position.distanceToSqr(player.position()) > 64) {
            ROTATION.reset(player.getYRot(), player.getXRot());
        } else {
            ROTATION.tick(player.getYRot(), player.getXRot());
        }
        position = player.position();
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void cameraAngles(ViewportEvent.ComputeCameraAngles event) {
        if (!synchronizeContext() || event.getCamera().getEntity() != player
                || player.isPassenger() || player.isSleeping()) return;
        var angles = ROTATION.sample(event.getPartialTick());
        // This event runs before native front/back offset and collision clipping.
        // Preserve other camera offsets and roll; do not write LocalPlayer rotations.
        event.setYaw(event.getYaw() + TakeoverCameraRotation.shortestDelta(player.getYRot(), angles.yaw()));
        event.setPitch(event.getPitch() + angles.pitch() - player.getXRot());
    }

    public static void cyclePerspective() {
        if (!PlayerBodyControlClient.active()) return;
        var mc = Minecraft.getInstance();
        var previous = mc.options.getCameraType();
        mc.options.setCameraType(previous.cycle());
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
