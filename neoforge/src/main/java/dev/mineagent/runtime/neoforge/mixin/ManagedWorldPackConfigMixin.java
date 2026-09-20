package dev.mineagent.runtime.neoforge.mixin;
import dev.mineagent.runtime.neoforge.content.WorldReopenBootstrap;
import net.minecraft.server.WorldLoader;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.world.level.WorldDataConfiguration;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
@Mixin(WorldLoader.PackConfig.class)
public abstract class ManagedWorldPackConfigMixin {
    @Shadow @Final private PackRepository packRepository;
    @Shadow @Final private boolean safeMode;
    @ModifyArg(method="createResourceManager",at=@At(value="INVOKE",target="Lnet/minecraft/server/MinecraftServer;configurePackRepository(Lnet/minecraft/server/packs/repository/PackRepository;Lnet/minecraft/world/level/WorldDataConfiguration;ZZ)Lnet/minecraft/world/level/WorldDataConfiguration;"),index=1)
    private WorldDataConfiguration mineagent$nextOpen(WorldDataConfiguration initial){return WorldReopenBootstrap.configure(packRepository,initial,safeMode);}
}
