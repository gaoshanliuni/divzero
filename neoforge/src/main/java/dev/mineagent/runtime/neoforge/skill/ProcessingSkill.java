package dev.mineagent.runtime.neoforge.skill;

import dev.mineagent.runtime.core.task.*;
import dev.mineagent.runtime.neoforge.body.*;
import dev.mineagent.runtime.neoforge.task.*;
import dev.mineagent.runtime.neoforge.ui.ContainerOpenScope;
import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.*;
import net.minecraft.world.item.crafting.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.*;
import java.util.*;

/** Finite production using native recipe/menu actions and the persistent skill receipt lifecycle. */
final class ProcessingSkill {
    private BlockEntity furnace;
    private ItemStack expectedOutput = ItemStack.EMPTY;
    private int loadedAt;
    private boolean cooking;

    static void tick(SkillWork w) throws Exception {
        if (w.processing == null) w.processing = new ProcessingSkill();
        w.processing.advance(w);
    }
    private void advance(SkillWork w) throws Exception {
        var spec = w.session.spec().process();
        if (!(w.player() instanceof MineAgentPlayer body) || body.hasInfiniteMaterials()) {
            w.pause("PROCESS_REQUIRES_FINITE_AI_INVENTORY"); return;
        }
        if (!w.saved.isDone() || w.ioFailed) return;
        if (w.session.count("processedUnits") >= spec.count()) { w.completed("NATIVE_PROCESSING_VERIFIED"); return; }
        var pos = BlockPos.containing(spec.station().x(), spec.station().y(), spec.station().z());
        w.block = pos;
        if (!body.level().hasChunkAt(pos)) { w.waitFor("PROCESS_STATION_CHUNK_UNLOADED", 40); return; }
        if (!body.level().mayInteract(body, pos) || dev.mineagent.runtime.neoforge.ui.ServerInteractionRules.denied(body,pos,null,"block_use",false)) {
            w.pause("PROCESS_STATION_PERMISSION_DENIED " + pos.toShortString()); return;
        }
        if (!w.runtime.reservations.reserve(w.reservation(pos), w.token(), w.tick(), 100)) { w.waitFor("PROCESS_STATION_RESERVED", 20); return; }
        if (!w.reach(pos, InteractionTargetResolver.Kind.BLOCK)) return;
        if (!body.isWithinBlockInteractionRange(pos, 0)) { w.stand = null; w.search = null; return; }
        var hit = InteractionTargetResolver.topHit(body, pos);
        if (!InteractionTargetResolver.lineOfSight(body, body.getEyePosition(), hit, pos, false)) {
            w.stand = null; w.search = null; w.nextTick = w.tick() + 5; return;
        }
        w.actor.aim(w.token(), hit);
        w.actor.stop(w.token());
        RecipeHolder<?> holder = spec.action() == ProcessingSpec.Action.STORE ? null : NativeRecipeCatalog.require(body, spec.recipe(), spec.recipeReference());
        if (w.operation == null) {
            w.prepare("PROCESS_" + spec.action(), Map.of("block", pos.toShortString(), "recipe", spec.recipe(),
                    "item", spec.item(), "processedBefore", Long.toString(w.session.count("processedUnits")),
                    "inventoryBefore", NativeCraftingAction.inventory(body).toString()));
            return;
        }
        if (w.interruptedOperation || w.executed && !cooking) { w.pause("PROCESS_OUTCOME_REQUIRES_RECONCILIATION"); return; }
        switch (spec.action()) {
            case CRAFT -> craft(w, body, holder, pos);
            case STORE -> store(w, body, pos);
            case SMELT -> smelt(w, body, holder, pos);
        }
    }
    private void craft(SkillWork w, MineAgentPlayer body, RecipeHolder<?> holder, BlockPos pos) throws Exception {
        if (!(holder.value() instanceof CraftingRecipe)) throw new IllegalArgumentException("process.recipe: expected crafting recipe; execution=NOT_STARTED");
        w.executed = true;
        var action = new WorldActionSpec(w.operation.toString(), "craft_recipe", pos.getX(), pos.getY(), pos.getZ(),
                "", "", Map.of("recipe", w.session.spec().process().recipe(), "count", "1", "table", "true"));
        var result = NativeCraftingAction.execute(body, action);
        if (!result.verified()) {
            if (result.error().equals("CRAFT_MATERIALS_MISSING") && Objects.equals(result.evidence().get("inventoryBefore"), result.evidence().get("inventoryAfter"))) {
                w.confirm("NOT_EXECUTED_MATERIAL_MISSING", result.evidence()); w.waitFor("PROCESS_MATERIAL_REQUIRED", 40); return;
            }
            // Even recipe placement can move materials. Do not infer a no-effect failure or replay it.
            w.session.receipt(merge(w.session.receipt(), result.evidence()));
            w.pause(result.error()); return;
        }
        w.session.add("processedUnits", 1); w.session.add("craftedItems", Long.parseLong(result.evidence().get("outputCount")));
        w.confirm("VERIFIED_NATIVE_CRAFT", result.evidence());
    }
    private void store(SkillWork w, MineAgentPlayer body, BlockPos pos) {
        var spec = w.session.spec().process();
        Item item = BuiltInRegistries.ITEM.getValue(Identifier.parse(spec.item()));
        int remaining = (int)(spec.count() - w.session.count("processedUnits"));
        var menu = open(body, pos);
        try {
            if (!(menu instanceof ChestMenu)) throw new IllegalArgumentException("process.station: expected a usable chest; execution=NOT_STARTED");
            Slot source = menu.slots.stream().filter(s -> s.container == body.getInventory() && s.getItem().is(item) && s.mayPickup(body)).findFirst().orElse(null);
            if (source == null) { w.confirm("NOT_EXECUTED_MATERIAL_MISSING", Map.of("missingItem", spec.item())); w.waitFor("PROCESS_MATERIAL_REQUIRED", 40); return; }
            ItemStack unit = source.getItem().copyWithCount(1);
            Slot destination = menu.slots.stream().filter(s -> s.container != body.getInventory() && s.mayPlace(unit)
                    && (s.getItem().isEmpty() || ItemStack.isSameItemSameComponents(unit, s.getItem())) && s.getItem().getCount() < s.getMaxStackSize(unit)).findFirst().orElse(null);
            if (destination == null) { w.confirm("NOT_EXECUTED_CHEST_FULL", Map.of("item", spec.item())); w.waitFor("PROCESS_CHEST_FULL", 40); return; }
            int quantity = Math.min(remaining, Math.min(source.getItem().getCount(), destination.getMaxStackSize(unit) - destination.getItem().getCount()));
            int beforeInventory = count(menu, body, unit, true), beforeChest = count(menu, body, unit, false);
            w.executed = true;
            transfer(menu, body, source, destination, quantity);
            int afterInventory = count(menu, body, unit, true), afterChest = count(menu, body, unit, false);
            if (beforeInventory - afterInventory != quantity || afterChest - beforeChest != quantity)
                throw new IllegalStateException("STORE_NATIVE_TRANSFER_NOT_VERIFIED; station=" + pos.toShortString());
            w.session.add("processedUnits", quantity); w.session.add("storedItems", quantity);
            w.confirm("VERIFIED_CHEST_TRANSFER", Map.of("item", spec.item(), "quantity", Integer.toString(quantity),
                    "inventoryBefore", Integer.toString(beforeInventory), "inventoryAfter", Integer.toString(afterInventory),
                    "chestBefore", Integer.toString(beforeChest), "chestAfter", Integer.toString(afterChest), "slot", Integer.toString(destination.index)));
        } finally { close(body, menu); }
    }
    private void smelt(SkillWork w, MineAgentPlayer body, RecipeHolder<?> holder, BlockPos pos) {
        if (!(holder.value() instanceof AbstractCookingRecipe recipe)) throw new IllegalArgumentException("process.recipe: expected furnace recipe; execution=NOT_STARTED");
        var block = body.level().getBlockState(pos);
        boolean correct = recipe.getType() == RecipeType.SMELTING && block.is(Blocks.FURNACE)
                || recipe.getType() == RecipeType.BLASTING && block.is(Blocks.BLAST_FURNACE)
                || recipe.getType() == RecipeType.SMOKING && block.is(Blocks.SMOKER);
        if (!correct) throw new IllegalArgumentException("process.station: workstation does not support recipe " + holder.id().identifier() + "; execution=NOT_STARTED");
        var menu = open(body, pos);
        try {
            if (!(menu instanceof AbstractFurnaceMenu actual)) throw new IllegalStateException("PROCESS_FURNACE_MENU_NOT_OPENED");
            if (!cooking) {
                if (menu.getSlot(0).hasItem() || menu.getSlot(2).hasItem()) {
                    w.confirm("NOT_EXECUTED_FURNACE_OCCUPIED", Map.of("block", pos.toShortString())); w.waitFor("PROCESS_FURNACE_OCCUPIED", 40); return;
                }
                Slot input = menu.slots.stream().filter(s -> s.container == body.getInventory() && recipe.input().test(s.getItem()) && s.mayPickup(body)).findFirst().orElse(null);
                if (input == null) { w.confirm("NOT_EXECUTED_MATERIAL_MISSING", Map.of("recipe", holder.id().identifier().toString())); w.waitFor("PROCESS_MATERIAL_REQUIRED", 40); return; }
                Slot fuel = fuel(menu, body, recipe, w.session.spec().process().fuel());
                int lit = ((dev.mineagent.runtime.neoforge.mixin.FurnaceMenuDataAccess)actual).divzero$data().get(0);
                var existingFuel = menu.getSlot(1).getItem();
                long availableBurn = Math.max(0, lit) + (long)existingFuel.getCount() * existingFuel.getBurnTime(recipe.getType(), body.level().fuelValues());
                long neededBurn = Math.max(0L, recipe.cookingTime() - availableBurn);
                int fuelUnits = neededBurn == 0 || fuel == null ? 0 : (int)((neededBurn - 1) / fuel.getItem().getBurnTime(recipe.getType(), body.level().fuelValues()) + 1);
                if (neededBurn > 0 && (fuel == null || fuel.getItem().getCount() < fuelUnits + (fuel == input ? 1 : 0) || !existingFuel.isEmpty() && !ItemStack.isSameItemSameComponents(existingFuel, fuel.getItem())
                        || fuelUnits > menu.getSlot(1).getMaxStackSize(fuel.getItem()) - existingFuel.getCount())) {
                    w.confirm("NOT_EXECUTED_FUEL_MISSING", Map.of("recipe", holder.id().identifier().toString())); w.waitFor("PROCESS_FUEL_REQUIRED", 40); return;
                }
                expectedOutput = recipe.assemble(new SingleRecipeInput(input.getItem().copyWithCount(1))).copy();
                if (expectedOutput.isEmpty()) throw new IllegalStateException("PROCESS_RECIPE_EMPTY_OUTPUT");
                w.executed = true; furnace = body.level().getBlockEntity(pos);
                transfer(menu, body, input, menu.getSlot(0), 1);
                if (fuelUnits > 0) transfer(menu, body, Objects.requireNonNull(fuel), menu.getSlot(1), fuelUnits);
                cooking = true; loadedAt = w.tick(); w.session.add("smeltingInputsLoaded", 1);
                w.session.receipt(merge(w.session.receipt(), Map.of("state", "PREPARED", "processingPhase", "WAITING_NATIVE_SMELT", "output", expectedOutput.toString(), "loadedTick", Integer.toString(loadedAt))));
                w.runtime.persist(w);
            } else {
                if (furnace == null || furnace != body.level().getBlockEntity(pos)) throw new IllegalStateException("SMELT_STATION_REPLACED; outcome=UNKNOWN");
                var output = menu.getSlot(2).getItem();
                if (!output.isEmpty()) {
                    if (!ItemStack.matches(output, expectedOutput) || menu.getSlot(0).hasItem()) throw new IllegalStateException("SMELT_CONTENT_CHANGED; outcome=UNKNOWN");
                    int before = count(menu, body, expectedOutput, true);
                    var taken = menu.quickMoveStack(body, 2);
                    if (taken.isEmpty()) { w.session.phase("SMELT_OUTPUT_INVENTORY_FULL"); w.nextTick = w.tick() + 20; return; }
                    if (menu.getSlot(2).hasItem() || count(menu, body, expectedOutput, true) - before != expectedOutput.getCount()) throw new IllegalStateException("SMELT_OUTPUT_TRANSFER_NOT_VERIFIED");
                    w.session.add("processedUnits", 1); w.session.add("smeltedItems", expectedOutput.getCount());
                    w.confirm("VERIFIED_NATIVE_SMELT", Map.of("outputItem", BuiltInRegistries.ITEM.getKey(expectedOutput.getItem()).toString(),
                            "outputCount", Integer.toString(expectedOutput.getCount()), "elapsedTicks", Integer.toString(w.tick() - loadedAt), "station", pos.toShortString()));
                    cooking = false; furnace = null; return;
                }
                if (!menu.getSlot(0).hasItem() || menu.getSlot(0).getItem().getCount() != 1 || !recipe.input().test(menu.getSlot(0).getItem()))
                    throw new IllegalStateException("SMELT_INPUT_OR_OUTPUT_REMOVED; outcome=UNKNOWN");
                if (!actual.isLit() && menu.getSlot(1).getItem().getBurnTime(recipe.getType(), body.level().fuelValues()) <= 0) {
                    // Enough fuel was observed and inserted before loading. External interference cannot authorize replay.
                    throw new IllegalStateException("SMELT_FUEL_REMOVED_OR_BURN_CHANGED; outcome=UNKNOWN; inspect actual station before resuming");
                }
                if (w.tick() - loadedAt > Math.max(1200L, (long)recipe.cookingTime() * 4)) throw new IllegalStateException("SMELT_NO_PROGRESS; inspect actual station before resuming");
            }
            w.session.phase("SMELT_WAITING_NATIVE_TICKS"); w.nextTick = w.tick() + 10;
        } finally { close(body, menu); }
    }
    private static Slot fuel(AbstractContainerMenu menu, MineAgentPlayer body, AbstractCookingRecipe recipe, String requested) {
        return menu.slots.stream().filter(s -> s.container == body.getInventory() && s.mayPickup(body) && !s.getItem().isEmpty()
                && (requested.isBlank() || BuiltInRegistries.ITEM.getKey(s.getItem().getItem()).toString().equals(requested))
                && s.getItem().getBurnTime(recipe.getType(), body.level().fuelValues()) > 0)
                .max(Comparator.comparingInt(s -> s.getItem().getBurnTime(recipe.getType(), body.level().fuelValues()))).orElse(null);
    }
    private static AbstractContainerMenu open(MineAgentPlayer body, BlockPos pos) {
        if (body.containerMenu != body.inventoryMenu || !body.inventoryMenu.getCarried().isEmpty() || body.isUsingItem()) throw new IllegalStateException("PROCESS_ACTOR_MENU_BUSY");
        var hit = new BlockHitResult(InteractionTargetResolver.topHit(body,pos), Direction.UP, pos, false);
        try (var scope = new ContainerOpenScope(body)) { body.gameMode.useItemOn(body, body.level(), body.getMainHandItem(), InteractionHand.MAIN_HAND, hit); }
        var menu = body.containerMenu;
        if (menu == body.inventoryMenu || !menu.stillValid(body)) { if (menu != body.inventoryMenu) body.doCloseContainer(); throw new IllegalStateException("PROCESS_STATION_NOT_OPENED"); }
        return menu;
    }
    private static void close(MineAgentPlayer body, AbstractContainerMenu menu) {
        if (body.containerMenu == menu) body.doCloseContainer();
        body.inventoryMenu.broadcastFullState();
    }
    /** Exact quantity through ordinary pickup/right-click/return, preserving native slot restrictions and events. */
    private static void transfer(AbstractContainerMenu menu, MineAgentPlayer body, Slot source, Slot target, int quantity) {
        if (quantity < 1 || quantity > source.getItem().getCount() || !menu.getCarried().isEmpty() || !source.mayPickup(body)
                || !target.mayPlace(source.getItem())) throw new IllegalStateException("PROCESS_TRANSFER_PRECONDITION");
        ItemStack unit = source.getItem().copyWithCount(1); int before = target.getItem().getCount();
        menu.clicked(source.index, 0, ContainerInput.PICKUP, body);
        for (int i = 0; i < quantity; i++) menu.clicked(target.index, 1, ContainerInput.PICKUP, body);
        if (!menu.getCarried().isEmpty()) menu.clicked(source.index, 0, ContainerInput.PICKUP, body);
        menu.broadcastChanges();
        if (!menu.getCarried().isEmpty() || !ItemStack.isSameItemSameComponents(unit, target.getItem()) || target.getItem().getCount() - before != quantity)
            throw new IllegalStateException("PROCESS_NATIVE_TRANSFER_REJECTED_OR_CHANGED; outcome=UNKNOWN");
    }
    private static int count(AbstractContainerMenu menu, MineAgentPlayer body, ItemStack item, boolean inventory) {
        int total = 0; for (var slot : menu.slots) if ((slot.container == body.getInventory()) == inventory && ItemStack.isSameItemSameComponents(slot.getItem(), item)) total += slot.getItem().getCount(); return total;
    }
    private static Map<String, String> merge(Map<String, String> before, Map<String, String> after) { var out = new LinkedHashMap<>(before); out.putAll(after); return Map.copyOf(out); }
}
