package dev.mineagent.runtime.neoforge.mixin;

import dev.mineagent.runtime.neoforge.content.ManagedDataPackGuard;
import net.minecraft.server.packs.*;
import net.minecraft.server.packs.repository.*;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.nio.file.Path;
import java.util.function.Consumer;

@Mixin(FolderRepositorySource.class)
public abstract class ManagedDataPackSourceMixin implements dev.mineagent.runtime.neoforge.content.ManagedPackSourceIdentity {
    @Shadow @Final private Path folder;
    @Shadow @Final private PackType packType;
    @Unique private java.util.UUID mineagent$source;
    @Override public Path mineagent$folder(){return folder;}
    @Override public PackType mineagent$packType(){return packType;}
    @Override public synchronized java.util.UUID mineagent$sourceId(){if(mineagent$source==null)mineagent$source=java.util.UUID.randomUUID();return mineagent$source;}
    @Inject(method="loadPacks",at=@At("HEAD"))
    private void mineagent$hook(Consumer<Pack> consumer,CallbackInfo ci){ManagedDataPackGuard.hooked();dev.mineagent.runtime.neoforge.content.ManagedClientResourcePackGuard.hooked();}
    @Redirect(method="lambda$loadPacks$0",at=@At(value="INVOKE",target="Lnet/minecraft/server/packs/repository/Pack;readMetaAndCreate(Lnet/minecraft/server/packs/PackLocationInfo;Lnet/minecraft/server/packs/repository/Pack$ResourcesSupplier;Lnet/minecraft/server/packs/PackType;Lnet/minecraft/server/packs/PackSelectionConfig;)Lnet/minecraft/server/packs/repository/Pack;"))
    private Pack mineagent$managedPack(PackLocationInfo location,Pack.ResourcesSupplier supplier,PackType type,PackSelectionConfig selection){return type==PackType.CLIENT_RESOURCES?dev.mineagent.runtime.neoforge.content.ManagedClientResourcePackGuard.create(folder,location,supplier,type,selection):ManagedDataPackGuard.create(folder,location,supplier,type,selection,mineagent$sourceId());}
}
