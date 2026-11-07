package dev.mineagent.runtime.core.task;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Set;

/** A finite native workstation operation. Counts are recipe executions, or stored item units. */
public record ProcessingSpec(Action action, String recipe, String recipeReference, String item,
                             int count, SkillSpec.Point station, String fuel) {
    public enum Action { CRAFT, SMELT, STORE }
    public ProcessingSpec {
        if (action == null) throw bad("action", "CRAFT, SMELT or STORE required");
        recipe = recipe == null ? "" : recipe;
        recipeReference = recipeReference == null ? "" : recipeReference;
        item = item == null ? "" : item;
        fuel = fuel == null ? "" : fuel;
        if (count < 1 || count > 4096) throw bad("count", "integer from 1 to 4096 required");
        if (station == null) throw bad("station", "three integer block coordinates required");
        if (station.x() != Math.rint(station.x()) || station.y() != Math.rint(station.y()) || station.z() != Math.rint(station.z()))
            throw bad("station", "block coordinates must be integers");
        if (action != Action.STORE && (!resource(recipe) || recipeReference.isBlank() || recipeReference.length() > 256))
            throw bad("recipe_reference", "recipe and exact observed reference from inspect_recipes required");
        if (action == Action.STORE && (!resource(item) || !recipe.isEmpty() || !recipeReference.isEmpty()))
            throw bad("item", "registered item ID required; STORE does not accept a recipe");
        if (action != Action.STORE && !item.isEmpty()) throw bad("item", "output comes from the registered recipe");
        if (!fuel.isEmpty() && (action != Action.SMELT || !resource(fuel))) throw bad("fuel", "SMELT fuel item ID required");
    }
    public static ProcessingSpec parse(JsonNode n) {
        if (n == null || n.isNull()) return null;
        if (!n.isObject()) throw bad("", "object required");
        for (var field : n.properties()) if (!Set.of("action", "recipe", "recipe_reference", "item", "count", "station", "fuel").contains(field.getKey()))
            throw bad(field.getKey(), "unknown field");
        for (String key : Set.of("action", "recipe", "recipe_reference", "item", "fuel"))
            if (n.has(key) && !n.get(key).isTextual()) throw bad(key, "text required");
        if (!n.path("count").isIntegralNumber() || !n.path("count").canConvertToInt()) throw bad("count", "integer required");
        Action action;
        try { action = Action.valueOf(n.path("action").asText()); }
        catch (IllegalArgumentException e) { throw bad("action", "CRAFT, SMELT or STORE required"); }
        SkillSpec.Point at;
        try { at = SkillSpec.point(n.path("station")); }
        catch (IllegalArgumentException e) { throw bad("station", "three finite integer coordinates required"); }
        return new ProcessingSpec(action, n.path("recipe").asText(), n.path("recipe_reference").asText(),
                n.path("item").asText(), n.path("count").asInt(), at, n.path("fuel").asText());
    }
    private static boolean resource(String value) { return value.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"); }
    private static IllegalArgumentException bad(String field, String expected) {
        return new IllegalArgumentException("process" + (field.isEmpty() ? "" : "." + field) + ": " + expected + "; execution=NOT_STARTED");
    }
}
