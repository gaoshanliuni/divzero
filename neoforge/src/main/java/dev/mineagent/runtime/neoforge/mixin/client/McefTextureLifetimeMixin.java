package dev.mineagent.runtime.neoforge.mixin.client;
import com.mojang.blaze3d.textures.GpuTexture;
import dev.mineagent.runtime.neoforge.client.webui.McefTextureRetirement;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(targets="com.cinemamod.mcef.MCEFRenderer",remap=false)
public abstract class McefTextureLifetimeMixin {
    @Redirect(method={"onPaint(Ljava/nio/ByteBuffer;II)V","cleanup"},at=@At(value="INVOKE",target="Lcom/mojang/blaze3d/textures/GpuTexture;close()V"),require=2)
    private void mineagent$retireTexture(GpuTexture texture){McefTextureRetirement.texture(texture);}
}
