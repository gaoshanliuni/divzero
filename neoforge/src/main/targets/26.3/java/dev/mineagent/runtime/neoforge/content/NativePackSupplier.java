package dev.mineagent.runtime.neoforge.content;
import net.minecraft.server.packs.*;
import net.minecraft.server.packs.repository.Pack;
/** One managed signed snapshot is one native resource layer; retain separate metadata/full admission. */
public abstract class NativePackSupplier implements Pack.ResourcesSupplier {
    public abstract PackResources openPrimary(PackLocationInfo location);
    public abstract PackResources openFull(PackLocationInfo location,Pack.Metadata metadata);
    public final PackMetadataResources openMetadata(PackLocationInfo location){return openPrimary(location);}
    public final java.util.stream.Stream<PackResources> openResources(PackLocationInfo location,Pack.Metadata metadata){return java.util.stream.Stream.of(openFull(location,metadata));}
}
