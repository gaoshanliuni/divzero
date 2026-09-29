package dev.mineagent.runtime.neoforge.skill;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import java.util.*;

/** Public extension point: custom crops keep their real native harvest/plant interaction semantics. */
public interface CropAdapter {
    String id();boolean supports(BlockState state);boolean mature(BlockState state);Item seed();Block block();
    default boolean harvestByUse(){return false;}
    default boolean canPlant(ServerPlayer player,BlockPos crop){return player.level().getBlockState(crop).isAir()&&block().defaultBlockState().canSurvive(player.level(),crop);}
    List<CropAdapter> REGISTRY=new java.util.concurrent.CopyOnWriteArrayList<>();
    static void register(CropAdapter adapter){Objects.requireNonNull(adapter);if(REGISTRY.stream().anyMatch(a->a.id().equals(adapter.id())))throw new IllegalArgumentException("CROP_ADAPTER_DUPLICATE");REGISTRY.add(adapter);}
    static List<CropAdapter> adapters(){return List.copyOf(REGISTRY);}
    static Optional<CropAdapter> find(BlockState state){return REGISTRY.stream().filter(a->a.supports(state)).findFirst();}
    java.util.concurrent.atomic.AtomicBoolean DEFAULTS_INSTALLED=new java.util.concurrent.atomic.AtomicBoolean();
    private static void builtin(CropAdapter adapter){if(REGISTRY.stream().noneMatch(a->a.id().equals(adapter.id())))register(adapter);}
    static void defaults(){if(!DEFAULTS_INSTALLED.compareAndSet(false,true))return;
        for(var pair:List.of(new Object[]{Blocks.WHEAT,Items.WHEAT_SEEDS},new Object[]{Blocks.CARROTS,Items.CARROT},new Object[]{Blocks.POTATOES,Items.POTATO},new Object[]{Blocks.BEETROOTS,Items.BEETROOT_SEEDS})){
            var crop=(CropBlock)pair[0];var item=(Item)pair[1];builtin(new CropAdapter(){public String id(){return net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(crop).toString();}public boolean supports(BlockState s){return s.is(crop);}public boolean mature(BlockState s){return crop.isMaxAge(s);}public Item seed(){return item;}public Block block(){return crop;}});
        }
        builtin(new CropAdapter(){public String id(){return "minecraft:nether_wart";}public boolean supports(BlockState s){return s.is(Blocks.NETHER_WART);}public boolean mature(BlockState s){return s.getValue(NetherWartBlock.AGE)==3;}public Item seed(){return Items.NETHER_WART;}public Block block(){return Blocks.NETHER_WART;}});
        builtin(new CropAdapter(){public String id(){return "minecraft:sweet_berry_bush";}public boolean supports(BlockState s){return s.is(Blocks.SWEET_BERRY_BUSH);}public boolean mature(BlockState s){return s.getValue(SweetBerryBushBlock.AGE)>=2;}public Item seed(){return Items.SWEET_BERRIES;}public Block block(){return Blocks.SWEET_BERRY_BUSH;}public boolean harvestByUse(){return true;}});
    }
}
