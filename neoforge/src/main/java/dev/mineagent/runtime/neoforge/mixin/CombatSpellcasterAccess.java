package dev.mineagent.runtime.neoforge.mixin;
import net.minecraft.world.entity.monster.illager.SpellcasterIllager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(SpellcasterIllager.class)
public interface CombatSpellcasterAccess {
    @Accessor("spellCastingTickCount") int divzero$castingTicks();
}
