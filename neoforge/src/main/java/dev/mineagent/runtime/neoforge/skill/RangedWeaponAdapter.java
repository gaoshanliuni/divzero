package dev.mineagent.runtime.neoforge.skill;

import dev.mineagent.runtime.core.task.BallisticIntercept;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/** Trusted adapters describe a real weapon; all uses still pass through the same native actor. */
public interface RangedWeaponAdapter {
    enum Use { CHARGE_RELEASE, CHARGE_CLICK, CLICK }
    String id();boolean matches(ItemStack stack);
    boolean ammunition(ServerPlayer actor,ItemStack weapon);
    boolean ready(ServerPlayer actor,ItemStack weapon);
    BallisticIntercept.Physics physics(ServerPlayer actor,ItemStack weapon);
    Use use();double range();
    default double pitchOffset(){return 0;}
    default double areaRadius(){return 0;}
    default net.minecraft.world.phys.Vec3 inheritedMovement(ServerPlayer actor){return net.minecraft.world.phys.Vec3.ZERO;}
    List<RangedWeaponAdapter> REGISTERED=new CopyOnWriteArrayList<>();
    static AutoCloseable register(RangedWeaponAdapter adapter){Objects.requireNonNull(adapter);if(REGISTERED.stream().anyMatch(a->a.id().equals(adapter.id())))throw new IllegalArgumentException("RANGED_ADAPTER_DUPLICATE");REGISTERED.add(adapter);return ()->REGISTERED.remove(adapter);}
}
