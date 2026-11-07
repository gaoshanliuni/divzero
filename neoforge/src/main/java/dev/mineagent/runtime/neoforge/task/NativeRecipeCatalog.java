package dev.mineagent.runtime.neoforge.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mineagent.runtime.core.content.ObservationTokens;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.crafting.*;
import java.util.*;

/** Server recipe data, including mod recipes. Observations never manufacture recipe outputs. */
public final class NativeRecipeCatalog {
    private static final Map<MinecraftServer, ObservationTokens> REFERENCES = new WeakHashMap<>();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static ObservationTokens references(ServerPlayer actor) {
        return REFERENCES.computeIfAbsent(actor.level().getServer(), s -> new ObservationTokens(4096, 16L * 1024 * 1024));
    }
    private static String source(ServerPlayer actor, RecipeHolder<?> holder) {
        return holder.id().identifier() + "\n" + Recipe.CODEC.encodeStart(
                actor.registryAccess().createSerializationContext(com.mojang.serialization.JsonOps.INSTANCE), holder.value()).getOrThrow();
    }
    public static RecipeHolder<?> require(ServerPlayer actor, String id, String reference) {
        var holder = actor.level().recipeAccess().byKey(ResourceKey.create(Registries.RECIPE, Identifier.parse(id)))
                .orElseThrow(() -> new IllegalArgumentException("process.recipe: registered recipe no longer exists; execution=NOT_STARTED"));
        if (!references(actor).token(source(actor, holder)).equals(reference))
            throw new IllegalArgumentException("process.recipe_reference: stale or expired; inspect_recipes again before a new operation; execution=NOT_STARTED");
        return holder;
    }
    public static Map<String, Object> inspect(ServerPlayer actor, JsonNode args) throws Exception {
        String query = args.path("query").asText("").toLowerCase(Locale.ROOT);
        String kind = args.path("kind").asText("all");
        int offset = args.path("offset").asInt(0);
        if (query.length() > 128 || offset < 0 || !Set.of("auto","any").contains(args.path("match").asText("auto")) || !Set.of("all", "crafting", "smelting", "blasting", "smoking").contains(kind))
            throw new IllegalArgumentException("inspect_recipes: invalid query, kind or offset");
        var catalog=actor.level().recipeAccess().getRecipes().stream().sorted(Comparator.comparing(r -> r.id().identifier().toString())).toList();
        boolean broad=args.path("match").asText("auto").equals("any");
        var exact=broad?Set.<String>of():dev.mineagent.runtime.core.task.RecipeQuery.exactIds(catalog.stream().map(r->r.id().identifier().toString()).toList(),query);
        var rows = new ArrayList<Map<String, Object>>(); int found = 0;
        for (var holder : catalog) {
            var recipe = holder.value();
            String type = recipe instanceof CraftingRecipe ? "crafting" : recipe.getType() == RecipeType.SMELTING ? "smelting"
                    : recipe.getType() == RecipeType.BLASTING ? "blasting" : recipe.getType() == RecipeType.SMOKING ? "smoking" : "";
            if (type.isEmpty() || !kind.equals("all") && !kind.equals(type)) continue;
            if(!exact.isEmpty()&&!exact.contains(holder.id().identifier().toString()))continue;
            String source = source(actor, holder);
            if (exact.isEmpty()&&!source.toLowerCase(Locale.ROOT).contains(query)) continue;
            if (found++ < offset || rows.size() >= 48) continue;
            var row = new LinkedHashMap<String, Object>();
            row.put("recipe", holder.id().identifier().toString()); row.put("recipe_reference", references(actor).token(source));
            row.put("type", type); row.put("station", switch(type) { case "crafting" -> "minecraft:crafting_table"; case "blasting" -> "minecraft:blast_furnace"; case "smoking" -> "minecraft:smoker"; default -> "minecraft:furnace"; });
            row.put("definition", JSON.readTree(source.substring(source.indexOf('\n') + 1)));
            var ingredients=new LinkedHashMap<List<Map<String,Object>>,Integer>();
            for(var ingredient:recipe.placementInfo().ingredients()){
                List<Map<String,Object>> options=ingredient.items().map(h -> Map.<String,Object>of("item",net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(h.value()).toString(),"available",actor.getInventory().countItem(h.value()))).toList();
                ingredients.merge(options,1,Integer::sum);
            }
            row.put("ingredients",ingredients.entrySet().stream().map(e->Map.of("slots",e.getValue(),"alternatives",e.getKey())).toList());
            if (recipe instanceof AbstractCookingRecipe cooking) row.put("cookingTicks", cooking.cookingTime());
            row.put("special", recipe.isSpecial()); rows.add(row);
        }
        return Map.of("status", "OBSERVED", "recipes", rows, "total", found, "nextOffset", offset + rows.size() < found ? offset + rows.size() : -1,
                "actorId", actor.getUUID(), "source", "CURRENT_SERVER_RECIPE_REGISTRY", "matchMode", exact.isEmpty()?"CONTENT_SEARCH":"EXACT_RECIPE_ID");
    }
    private NativeRecipeCatalog() {}
}
