package dev.mineagent.runtime.neoforge.mixin.client;
import net.minecraft.client.model.geom.ModelPart;import org.spongepowered.asm.mixin.Mixin;import org.spongepowered.asm.mixin.gen.Accessor;import java.util.*;
@Mixin(ModelPart.class)
public interface EntityModelPartsAccess {@Accessor("children") Map<String,ModelPart> mineagent$children();@Accessor("cubes") List<ModelPart.Cube> mineagent$cubes();}
