package dev.mineagent.runtime.legacy189;

import com.google.gson.*;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.properties.PropertyBool;
import net.minecraft.block.properties.PropertyInteger;
import net.minecraft.block.state.BlockState;
import net.minecraft.block.state.IBlockState;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.util.*;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.registry.GameRegistry;
import java.util.*;

/** Exact state resolution for the current arena. Unknown states stop the import. */
public final class LegacyBlocks {
    public static final Block DEEPSLATE = cube("polished_deepslate", 3.5f, 6);
    public static final Block SMOOTH_STONE = cube("smooth_stone", 2, 6);
    public static final Block SMOOTH_QUARTZ = cube("smooth_quartz", 2, 6);
    public static final ModernLeaves ACACIA_LEAVES = new ModernLeaves();
    public static final Map<String, Block> REGISTERED = new LinkedHashMap<String, Block>();
    private LegacyBlocks() { }
    private static Block cube(String name, float hardness, float resistance) {
        return new Block(Material.rock).setUnlocalizedName("divzero." + name).setHardness(hardness).setResistance(resistance * 5 / 3).setCreativeTab(CreativeTabs.tabBlock);
    }
    public static void register() {
        REGISTERED.put("polished_deepslate", DEEPSLATE); REGISTERED.put("smooth_stone", SMOOTH_STONE);
        REGISTERED.put("smooth_quartz", SMOOTH_QUARTZ); REGISTERED.put("acacia_leaves", ACACIA_LEAVES);
        for (Map.Entry<String, Block> entry : REGISTERED.entrySet()) GameRegistry.registerBlock(entry.getValue(), entry.getKey());
        Blocks.fire.setFireInfo(ACACIA_LEAVES, 30, 60);
    }
    public static IBlockState resolve(JsonObject source) {
        String name = source.get("Name").getAsString();
        JsonObject props = source.has("Properties") ? source.getAsJsonObject("Properties") : new JsonObject();
        Set<String> keys = new HashSet<String>(); for (Map.Entry<String, JsonElement> entry : props.entrySet()) keys.add(entry.getKey());
        if (name.equals("minecraft:dark_oak_log")) {
            require(keys.equals(Collections.singleton("axis")), name);
            String axis = props.get("axis").getAsString();
            int meta = axis.equals("y") ? 1 : axis.equals("x") ? 5 : axis.equals("z") ? 9 : -1;
            require(meta >= 0, name); return Blocks.log2.getStateFromMeta(meta);
        }
        if (name.equals("minecraft:grass_block")) {
            require(keys.equals(Collections.singleton("snowy")) && !props.get("snowy").getAsBoolean(), name);
            return Blocks.grass.getDefaultState();
        }
        if (name.equals("minecraft:acacia_leaves")) {
            require(keys.equals(new HashSet<String>(Arrays.asList("distance", "persistent", "waterlogged"))) && !props.get("waterlogged").getAsBoolean(), name);
            int distance = props.get("distance").getAsInt(); require(distance >= 1 && distance <= 7, name);
            return ACACIA_LEAVES.getDefaultState().withProperty(ModernLeaves.DISTANCE, distance).withProperty(ModernLeaves.PERSISTENT, props.get("persistent").getAsBoolean());
        }
        require(keys.isEmpty(), name);
        if (name.equals("minecraft:polished_deepslate")) return DEEPSLATE.getDefaultState();
        if (name.equals("minecraft:smooth_stone")) return SMOOTH_STONE.getDefaultState();
        if (name.equals("minecraft:smooth_quartz")) return SMOOTH_QUARTZ.getDefaultState();
        if (name.equals("minecraft:cyan_terracotta")) return Blocks.stained_hardened_clay.getStateFromMeta(9);
        if (name.equals("minecraft:chiseled_stone_bricks")) return Blocks.stonebrick.getStateFromMeta(3);
        if (name.equals("minecraft:stone_bricks")) return Blocks.stonebrick.getStateFromMeta(0);
        if (name.equals("minecraft:polished_andesite")) return Blocks.stone.getStateFromMeta(6);
        if (name.equals("minecraft:short_grass")) return Blocks.tallgrass.getStateFromMeta(1);
        Map<String, Block> simple = new HashMap<String, Block>();
        simple.put("minecraft:air", Blocks.air); simple.put("minecraft:bedrock", Blocks.bedrock); simple.put("minecraft:dirt", Blocks.dirt);
        simple.put("minecraft:stone", Blocks.stone); simple.put("minecraft:coal_ore", Blocks.coal_ore);
        simple.put("minecraft:glass", Blocks.glass); simple.put("minecraft:sea_lantern", Blocks.sea_lantern);
        require(simple.containsKey(name), name); return simple.get(name).getDefaultState();
    }
    private static void require(boolean condition, String state) { if (!condition) throw new IllegalArgumentException("UNSUPPORTED_ARENA_STATE: " + state); }

    public static final class ModernLeaves extends Block {
        public static final PropertyInteger DISTANCE = PropertyInteger.create("distance", 1, 7);
        public static final PropertyBool PERSISTENT = PropertyBool.create("persistent");
        ModernLeaves() {
            super(Material.leaves); setUnlocalizedName("divzero.acacia_leaves"); setHardness(.2f); setLightOpacity(1);
            setStepSound(soundTypeGrass); setTickRandomly(true); setCreativeTab(CreativeTabs.tabDecorations);
            setDefaultState(blockState.getBaseState().withProperty(DISTANCE, 7).withProperty(PERSISTENT, false));
        }
        @Override protected BlockState createBlockState() { return new BlockState(this, DISTANCE, PERSISTENT); }
        @Override public int getMetaFromState(IBlockState state) { return state.getValue(DISTANCE) - 1 | (state.getValue(PERSISTENT) ? 8 : 0); }
        @Override public IBlockState getStateFromMeta(int meta) { return getDefaultState().withProperty(DISTANCE, Math.min(7, (meta & 7) + 1)).withProperty(PERSISTENT, (meta & 8) != 0); }
        @Override public boolean isOpaqueCube() { return false; }
        @Override public EnumWorldBlockLayer getBlockLayer() { return EnumWorldBlockLayer.CUTOUT_MIPPED; }
        @Override public boolean isLeaves(IBlockAccess world, BlockPos pos) { return true; }
        @Override public IBlockState onBlockPlaced(World world, BlockPos pos, EnumFacing face, float hitX, float hitY, float hitZ, int meta, net.minecraft.entity.EntityLivingBase placer) {
            return getDefaultState().withProperty(PERSISTENT, true);
        }
        @Override public int colorMultiplier(IBlockAccess world, BlockPos pos, int tint) { return net.minecraft.world.biome.BiomeColorHelper.getFoliageColorAtPos(world, pos); }
        @Override public void onNeighborBlockChange(World world, BlockPos pos, IBlockState state, Block neighbor) { world.scheduleUpdate(pos, this, 1); }
        @Override public void updateTick(World world, BlockPos pos, IBlockState state, Random random) {
            int distance = 7;
            for (EnumFacing face : EnumFacing.values()) {
                BlockPos adjacent = pos.offset(face); IBlockState other = world.getBlockState(adjacent);
                if (other.getBlock().canSustainLeaves(world, adjacent)) distance = 1;
                else if (other.getBlock() == this) distance = Math.min(distance, other.getValue(DISTANCE) + 1);
            }
            if (distance != state.getValue(DISTANCE)) world.setBlockState(pos, state.withProperty(DISTANCE, distance), 3);
        }
        @Override public void randomTick(World world, BlockPos pos, IBlockState state, Random random) {
            if (!state.getValue(PERSISTENT) && state.getValue(DISTANCE) == 7) { dropBlockAsItem(world, pos, state, 0); world.setBlockToAir(pos); }
            else updateTick(world, pos, state, random);
        }
        @Override public Item getItemDropped(IBlockState state, Random random, int fortune) { return Item.getItemFromBlock(Blocks.sapling); }
        @Override public int damageDropped(IBlockState state) { return 4; }
        @Override public int quantityDropped(Random random) { return random.nextInt(20) == 0 ? 1 : 0; }
    }
}
