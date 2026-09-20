package dev.mineagent.runtime.neoforge.mixin;
import dev.mineagent.runtime.neoforge.content.WorldReopenBootstrap;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.level.saveddata.*;
import net.minecraft.world.level.storage.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import com.mojang.serialization.DataResult;
@Mixin(LevelStorageSource.class)
public abstract class ManagedWorldgenReadMixin {
    @Redirect(method="getLevelDataAndDimensions",at=@At(value="INVOKE",target="Lnet/minecraft/world/level/storage/LevelStorageSource;readExistingSavedData(Lnet/minecraft/world/level/storage/LevelStorageSource$LevelStorageAccess;Lnet/minecraft/core/HolderLookup$Provider;Lnet/minecraft/world/level/saveddata/SavedDataType;)Lcom/mojang/serialization/DataResult;"))
    private static <T extends SavedData> DataResult<T> mineagent$noRandomSeed(LevelStorageSource.LevelStorageAccess access,HolderLookup.Provider registries,SavedDataType<T> type){
        var result=LevelStorageSource.readExistingSavedData(access,registries,type);
        if(!result.isSuccess()&&WorldReopenBootstrap.managedWorld(access.getLevelPath(LevelResource.ROOT)))throw new IllegalStateException("WORLD_REOPEN_WORLDGEN_UNAVAILABLE_NO_RANDOM_SEED");return result;
    }
}
