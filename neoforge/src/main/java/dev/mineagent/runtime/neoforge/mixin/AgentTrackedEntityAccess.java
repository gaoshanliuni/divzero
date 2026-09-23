package dev.mineagent.runtime.neoforge.mixin;
import org.spongepowered.asm.mixin.Mixin;import org.spongepowered.asm.mixin.gen.Accessor;import net.minecraft.server.level.ServerEntity;
@Mixin(targets="net.minecraft.server.level.ChunkMap$TrackedEntity")
public interface AgentTrackedEntityAccess {@Accessor("serverEntity") ServerEntity mineagent$serverEntity();}
