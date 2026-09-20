package dev.mineagent.runtime.neoforge.mixin.client;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import java.util.concurrent.CompletableFuture;
@Mixin(Minecraft.class)
public interface ResourceReloadAccess { @Accessor("pendingReload") CompletableFuture<Void> mineagent$pendingReload(); }
