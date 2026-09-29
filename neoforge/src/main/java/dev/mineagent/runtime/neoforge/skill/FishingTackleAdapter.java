package dev.mineagent.runtime.neoforge.skill;

import dev.mineagent.runtime.neoforge.mixin.SkillFishingHookAccess;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.item.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/** Native-compatible tackle can supply its real owned hook and bite state; no timed synthetic catch. */
public interface FishingTackleAdapter {
    String id();boolean supports(ItemStack stack);
    FishingHook hook(ServerPlayer player);
    boolean biting(FishingHook hook);
    List<FishingTackleAdapter> REGISTRY=new CopyOnWriteArrayList<>();
    FishingTackleAdapter VANILLA=new FishingTackleAdapter(){
        public String id(){return "minecraft:native_rod";}
        public boolean supports(ItemStack stack){return stack.getItem() instanceof FishingRodItem;}
        public FishingHook hook(ServerPlayer player){return player.fishing;}
        public boolean biting(FishingHook hook){return hook instanceof SkillFishingHookAccess state&&state.divzero$skillNibble()>0;}
    };
    static void register(FishingTackleAdapter adapter){Objects.requireNonNull(adapter);if(REGISTRY.stream().anyMatch(a->a.id().equals(adapter.id())))throw new IllegalArgumentException("FISHING_ADAPTER_DUPLICATE");REGISTRY.add(adapter);}
    static FishingTackleAdapter find(ItemStack item){return REGISTRY.stream().filter(a->a.supports(item)).findFirst().orElse(VANILLA.supports(item)?VANILLA:null);}
}
