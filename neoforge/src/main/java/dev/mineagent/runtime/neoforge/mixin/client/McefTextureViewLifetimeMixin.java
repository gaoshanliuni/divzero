package dev.mineagent.runtime.neoforge.mixin.client;
import com.cinemamod.mcef.MCEFDirectTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import dev.mineagent.runtime.neoforge.client.webui.McefTextureRetirement;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(MCEFDirectTexture.class)
public abstract class McefTextureViewLifetimeMixin {
    @Redirect(method={"bindTexture","close"},at=@At(value="INVOKE",target="Lcom/mojang/blaze3d/textures/GpuTextureView;close()V"),require=2)
    private void mineagent$retireView(GpuTextureView view){McefTextureRetirement.view(view,((MCEFDirectTexture)(Object)this).getBoundTexture());}
}
