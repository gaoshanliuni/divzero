package dev.mineagent.runtime.neoforge.content;
import dev.mineagent.runtime.neoforge.MineAgentRuntimeMod;
import dev.mineagent.runtime.neoforge.MineAgentRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import java.nio.file.*;
import java.util.*;

/** Actual default/opt-in/reload/restart evidence. No Provider or generated-content success claims. */
@EventBusSubscriber(modid="mineagent_runtime")
public final class CleanContentBoundarySmoke {
    private static int phase,ticks;private static final BlockPos LEGACY_POS=new BlockPos(1,80,1);
    private static final String[] RECIPES={"basketball","basketball_hoop","agent_blade","sentinel_summoner"};
    private static final Identifier ORE_MODIFIER=Identifier.fromNamespaceAndPath("mineagent_runtime","neoforge/biome_modifier/add_runtime_crystal.json");
    private CleanContentBoundarySmoke(){}
    @SubscribeEvent public static void tick(ServerTickEvent.Post event)throws Exception{
        if(!Boolean.getBoolean("mineagent.cleanContentBoundarySmoke"))return;var server=event.getServer();ticks++;var level=server.overworld();var json=new com.fasterxml.jackson.databind.ObjectMapper();
        Path root=server.getServerDirectory().resolve("content-boundary-evidence");Files.createDirectories(root);
        var repo=server.getPackRepository();boolean selected=repo.getSelectedIds().contains(LegacyContentBoundary.PACK_ID);
        if(phase==0){
            if(Boolean.getBoolean("mineagent.legacyFixtureContent"))throw new IllegalStateException("CLEAN_PROFILE_MUST_NOT_FORCE_COMPAT_PACK");
            if(!repo.getAvailableIds().contains(LegacyContentBoundary.PACK_ID)||selected||anyRecipe(server)||server.getResourceManager().getResource(ORE_MODIFIER).isPresent())throw new IllegalStateException("LEGACY_CONTENT_ENABLED_BY_DEFAULT");
            net.minecraft.world.item.CreativeModeTabs.tryRebuildTabContents(level.enabledFeatures(),true,server.registryAccess());
            var creative=net.minecraft.world.item.CreativeModeTabs.allTabs().stream().flatMap(t->t.getDisplayItems().stream()).map(s->BuiltInRegistries.ITEM.getKey(s.getItem()).toString()).filter(id->id.startsWith("mineagent_runtime:")).collect(java.util.stream.Collectors.toCollection(TreeSet::new));
            if(!creative.equals(LegacyContentBoundary.defaultCreativeItems()))throw new IllegalStateException("DEFAULT_CREATIVE_DEMO_LEAK: "+creative);
            var feature=ResourceKey.create(Registries.PLACED_FEATURE,Identifier.parse("mineagent_runtime:runtime_crystal_ore"));
            if(level.getBiome(LEGACY_POS).value().getGenerationSettings().features().stream().flatMap(net.minecraft.core.HolderSet::stream).anyMatch(h->h.is(feature)))throw new IllegalStateException("DEFAULT_BIOME_HAS_LEGACY_ORE");
            var ops=server.registryAccess().createSerializationContext(com.mojang.serialization.JsonOps.INSTANCE);
            if(Boolean.getBoolean("mineagent.contentBoundaryRestart")){
                if(!level.getBlockState(LEGACY_POS).is(MineAgentRegistries.BASKETBALL_HOOP.get()))throw new IllegalStateException("LEGACY_BLOCK_LOST_AFTER_RESTART");
                var stored=com.google.gson.JsonParser.parseString(Files.readString(root.resolve("legacy-stack.json")));var stack=ItemStack.CODEC.parse(ops,stored).getOrThrow();
                if(!stack.is(MineAgentRegistries.BASKETBALL.get())||stack.getCount()!=3||!"LEGACY_COMPATIBILITY_NOT_GENERATED".equals(stack.get(MineAgentRegistries.CREATION_ORIGIN.get())))throw new IllegalStateException("LEGACY_STACK_LOST_AFTER_RESTART");
                Files.writeString(root.resolve("restart.json"),json.writeValueAsString(Map.of("selectedPacks",repo.getSelectedIds(),"creativeItems",creative,"legacyBlock",level.getBlockState(LEGACY_POS).getBlock().toString(),"itemCount",stack.getCount(),"mode","LEGACY_REUSE_NOT_GENERATION")));
                phase=99;MineAgentRuntimeMod.LOGGER.info("MINEAGENT_CONTENT_BOUNDARY_RESTART_OK defaultsDisabled=true legacyDataRetained=true");server.halt(false);return;
            }
            if(Files.exists(root.resolve("default.json")))throw new IllegalStateException("USE_RESTART_MODE_OR_A_FRESH_ISOLATED_PROFILE");
            Files.writeString(root.resolve("default.json"),json.writeValueAsString(Map.of("availablePacks",repo.getAvailableIds(),"selectedPacks",repo.getSelectedIds(),"creativeItems",creative,"legacyRecipesLoaded",false,"legacyOreInjected",false)));
            // Explicit compatibility fixture, only after proving the default profile is clean.
            level.setBlockAndUpdate(LEGACY_POS,MineAgentRegistries.BASKETBALL_HOOP.get().defaultBlockState());
            var stack=new ItemStack(MineAgentRegistries.BASKETBALL.get(),3);stack.set(MineAgentRegistries.CREATION_ORIGIN.get(),"LEGACY_COMPATIBILITY_NOT_GENERATED");
            Files.writeString(root.resolve("legacy-stack.json"),ItemStack.CODEC.encodeStart(ops,stack).getOrThrow().toString());
            phase=1;server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),"datapack enable \""+LegacyContentBoundary.PACK_ID+"\"");
        }
        if(phase==1&&selected&&allRecipes(server)&&server.getResourceManager().getResource(ORE_MODIFIER).isPresent()){
            Files.writeString(root.resolve("explicit-enabled.json"),json.writeValueAsString(Map.of("selectedPacks",repo.getSelectedIds(),"recipesLoaded",true,"oreModifierResourceLoaded",true,"source","EXPLICIT_DATAPACK_COMMAND_NOT_MODEL_GENERATION")));
            phase=2;server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),"datapack disable \""+LegacyContentBoundary.PACK_ID+"\"");
        }
        if(phase==2&&!selected&&!anyRecipe(server)&&server.getResourceManager().getResource(ORE_MODIFIER).isEmpty()){
            if(!level.getBlockState(LEGACY_POS).is(MineAgentRegistries.BASKETBALL_HOOP.get()))throw new IllegalStateException("DISABLE_DELETED_LEGACY_BLOCK");
            Files.writeString(root.resolve("disabled.json"),json.writeValueAsString(Map.of("selectedPacks",repo.getSelectedIds(),"legacyBlockRetained",true,"legacyRecipesLoaded",false,"legacyOreResourceLoaded",false)));
            phase=99;MineAgentRuntimeMod.LOGGER.info("MINEAGENT_CONTENT_BOUNDARY_OK clean=true explicitEnable=true disable=true legacyIdsRetained=true");server.halt(false);
        }
        if(ticks>1200&&phase!=99)throw new IllegalStateException("CONTENT_BOUNDARY_TIMEOUT phase="+phase);
    }
    private static boolean anyRecipe(net.minecraft.server.MinecraftServer server){return Arrays.stream(RECIPES).anyMatch(id->recipe(server,id));}
    private static boolean allRecipes(net.minecraft.server.MinecraftServer server){return Arrays.stream(RECIPES).allMatch(id->recipe(server,id));}
    private static boolean recipe(net.minecraft.server.MinecraftServer server,String id){return server.getRecipeManager().byKey(ResourceKey.create(Registries.RECIPE,Identifier.fromNamespaceAndPath("mineagent_runtime",id))).isPresent();}
}
