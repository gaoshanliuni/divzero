package dev.mineagent.runtime.neoforge.mixin.client;
import com.mojang.blaze3d.textures.GpuTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Pinned MCEF allocates usage=5; explicitly request copy-source support rather than bypassing GPU validation. */
@Mixin(targets="com.cinemamod.mcef.MCEFRenderer",remap=false)
public abstract class McefCaptureTextureMixin {
    @ModifyArg(method="onPaint(Ljava/nio/ByteBuffer;II)V",at=@At(value="INVOKE",target="Lcom/mojang/blaze3d/systems/GpuDevice;createTexture(Ljava/lang/String;ILcom/mojang/blaze3d/textures/TextureFormat;IIII)Lcom/mojang/blaze3d/textures/GpuTexture;"),index=1,require=1)
    private static int mineagent$copySource(int usage){return usage|GpuTexture.USAGE_COPY_SRC;}
    @Inject(method="onPaint(Ljava/nio/ByteBuffer;II)V",at=@At("RETURN"))
    private void mineagent$fullPaint(java.nio.ByteBuffer pixels,int width,int height,CallbackInfo ci){dev.mineagent.runtime.neoforge.client.webui.McefPaintBoundary.painted((com.cinemamod.mcef.MCEFRenderer)(Object)this,false);}
    @Inject(method="onPaint(Ljava/nio/ByteBuffer;IIII)V",at=@At("RETURN"))
    private void mineagent$partialPaint(java.nio.ByteBuffer pixels,int x,int y,int width,int height,CallbackInfo ci){dev.mineagent.runtime.neoforge.client.webui.McefPaintBoundary.painted((com.cinemamod.mcef.MCEFRenderer)(Object)this,true);}
}
