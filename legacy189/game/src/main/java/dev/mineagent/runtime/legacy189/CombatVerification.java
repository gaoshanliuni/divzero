package dev.mineagent.runtime.legacy189;

import com.google.gson.*;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.BlockPos;
import java.util.concurrent.CompletableFuture;

/** Controlled native scenes, never submitted as human training rounds. */
public final class CombatVerification {
    private int stage, began;
    private final EntityPlayerMP human;
    private final NativeAgent body;
    private final JsonObject evidence;
    private final CompletableFuture<JsonObject> reference;
    public CombatVerification(EntityPlayerMP human, NativeAgent body, JsonObject evidence) {
        this.human = human; this.body = body; this.evidence = evidence;
        reference = NativeService.get(human).thenCompose(service -> service.request("policy.reference", new JsonObject()));
        reset(); began = tick();
    }
    private static int tick() { return MinecraftServer.getServer().getTickCounter(); }
    private void reset() {
        human.playerNetServerHandler.setPlayerLocation(-.5, 101, 800.5, -90, 0);
        body.playerNetServerHandler.setPlayerLocation(1.5, 101, 800.5, 90, 0);
        human.motionX = human.motionY = human.motionZ = body.motionX = body.motionY = body.motionZ = 0;
        human.setSprinting(false); body.setSprinting(false); human.setHealth(20); body.setHealth(20);
        human.hurtResistantTime = body.hurtResistantTime = 0;
        human.inventory.setInventorySlotContents(0, new ItemStack(Items.diamond_sword)); human.inventory.currentItem = 0;
        body.inventory.setInventorySlotContents(0, new ItemStack(Items.diamond_sword)); body.inventory.currentItem = 0;
        for (int slot = 1; slot <= 4; slot++) { human.setCurrentItemOrArmor(slot, null); body.setCurrentItemOrArmor(slot, null); }
    }
    public boolean advance() {
        if (tick() - began > 600) throw new IllegalStateException("MODERN_COMBAT_FIXTURE_TIMEOUT_" + stage);
        if (stage == 0 && tick() - began > 25 && reference.isDone()) {
            JsonObject data = reference.join().getAsJsonObject("payload"); double maximum = 0;
            for (JsonElement vector : data.getAsJsonArray("vectors")) {
                JsonObject row = vector.getAsJsonObject(); double[] features = new Gson().fromJson(row.get("features"), double[].class);
                maximum = Math.max(maximum, Math.abs(LegacyPolicy.get().cost(features) - row.get("cost").getAsDouble()));
            }
            require(maximum < 1e-12, "JAVA8_POLICY_PARITY"); evidence.addProperty("policyMaximumError", maximum);
            float before = body.getHealth(); human.attackTargetEntityWithCurrentItem(body); float strong = before - body.getHealth();
            close(strong, 7, "FULL_SWORD_DAMAGE");
            // Only this controlled scene clears victim immunity to isolate attack damage.
            body.hurtResistantTime = 0; before = body.getHealth(); human.attackTargetEntityWithCurrentItem(body); float weak = before - body.getHealth();
            close(weak, 7, "NO_COOLDOWN_REPEAT_DAMAGE"); evidence.addProperty("firstSwordDamage", strong); evidence.addProperty("immediateRepeatSwordDamage", weak);
            reset(); body.setCurrentItemOrArmor(1, new ItemStack(Items.iron_boots)); body.setCurrentItemOrArmor(2, new ItemStack(Items.iron_leggings));
            body.setCurrentItemOrArmor(3, new ItemStack(Items.iron_chestplate)); body.setCurrentItemOrArmor(4, new ItemStack(Items.iron_helmet));
            stage = 1; began = tick();
        } else if (stage == 1 && tick() - began > 25) {
            float before = body.getHealth(); human.attackTargetEntityWithCurrentItem(body); float damage = before - body.getHealth(); close(damage, 3.78, "IRON_ARMOR_DAMAGE");
            require(body.inventory.armorInventory[0].getItemDamage() > 0, "NATIVE_ARMOR_DURABILITY"); evidence.addProperty("ironArmorDamage", damage);
            reset(); body.setCurrentItemOrArmor(1, new ItemStack(Items.diamond_boots)); body.setCurrentItemOrArmor(2, new ItemStack(Items.diamond_leggings));
            body.setCurrentItemOrArmor(3, new ItemStack(Items.diamond_chestplate)); body.setCurrentItemOrArmor(4, new ItemStack(Items.diamond_helmet));
            stage = 2; began = tick();
        } else if (stage == 2 && tick() - began > 25) {
            float before = body.getHealth(); human.attackTargetEntityWithCurrentItem(body); float damage = before - body.getHealth(); close(damage, 1.89, "DIAMOND_TOUGHNESS_DAMAGE");
            evidence.addProperty("diamondArmorDamage", damage);
            reset(); stage = 3; began = tick();
        } else if (stage == 3 && tick() - began > 25) {
            BlockPos obstruction = new BlockPos(0, 102, 800); human.worldObj.setBlockState(obstruction, net.minecraft.init.Blocks.stone.getDefaultState(), 3);
            float before = body.getHealth(); human.attackTargetEntityWithCurrentItem(body); close(before - body.getHealth(), 0, "WALL_BLOCKS_ATTACK");
            human.worldObj.setBlockToAir(obstruction);
            body.playerNetServerHandler.setPlayerLocation(1.5, 104, 800.5, 90, 0); body.motionY = 0;
            stage = 4; began = tick();
        } else if (stage == 4 && !body.onGround && body.motionY < 0 && body.fallDistance > 0 && body.posY < 102.1 && body.posY > 101.1) {
            require(ModernCombat.reachable(body, human), "CRITICAL_REACH");
            double actualFall = body.fallDistance; float before = human.getHealth(); body.attackTargetEntityWithCurrentItem(human);
            float damage = before - human.getHealth(); close(damage, 10.5, "NATIVE_FALL_CRITICAL");
            evidence.addProperty("criticalFallDistance", actualFall); evidence.addProperty("criticalDamage", damage);
            evidence.addProperty("modernMeleeVerified", true); evidence.addProperty("wallOcclusionVerified", true);
            reset(); NativeArena.lobby(human); body.playerNetServerHandler.setPlayerLocation(5.5, 101, 800.5, 90, 0); return true;
        }
        return false;
    }
    private static void require(boolean value, String code) { if (!value) throw new IllegalStateException(code); }
    private static void close(double actual, double expected, String code) { if (Math.abs(actual - expected) > .002) throw new IllegalStateException(code + " expected=" + expected + " actual=" + actual); }
}
