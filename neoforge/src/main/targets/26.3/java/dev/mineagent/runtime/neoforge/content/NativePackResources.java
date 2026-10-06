package dev.mineagent.runtime.neoforge.content;
import net.minecraft.server.packs.*;
/** Resource snapshots still own their verified bytes; metadata parsing uses the new native base. */
public abstract class NativePackResources extends AbstractPackMetadataResources implements PackResources {
    protected NativePackResources(PackLocationInfo location){super(location);}
}
