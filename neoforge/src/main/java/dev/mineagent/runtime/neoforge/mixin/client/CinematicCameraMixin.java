package dev.mineagent.runtime.neoforge.mixin.client;

import dev.mineagent.runtime.neoforge.client.cinematic.CinematicCaptureClient;
import net.minecraft.client.Camera;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Filming must not replace the LocalPlayer input owner: climbing and riding still run normally. */
@Mixin(Camera.class)
public abstract class CinematicCameraMixin {
    @Shadow protected abstract void setPosition(Vec3 position);
    @Shadow protected abstract void setRotation(float yaw, float pitch);
    @Inject(method="alignWithEntity", at=@At("RETURN"))
    private void mineagent$cinematicPose(float partialTick, CallbackInfo callback) {
        var pose=CinematicCaptureClient.cameraPose();
        if(pose!=null){setPosition(pose.position());setRotation(pose.yaw(),pose.pitch());}
    }
}
