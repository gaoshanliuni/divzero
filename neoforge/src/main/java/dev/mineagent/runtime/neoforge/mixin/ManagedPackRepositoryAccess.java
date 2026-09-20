package dev.mineagent.runtime.neoforge.mixin;
import net.minecraft.server.packs.repository.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import java.util.Set;
@Mixin(PackRepository.class)
public interface ManagedPackRepositoryAccess { @Accessor("sources") Set<RepositorySource> mineagent$sources(); }
