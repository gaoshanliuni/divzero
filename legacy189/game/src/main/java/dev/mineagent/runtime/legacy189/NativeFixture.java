package dev.mineagent.runtime.legacy189;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.BlockPos;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;

/** Explicitly gated isolated-instance probe. It never runs in an ordinary world. */
public final class NativeFixture {
    public static final String WORLD = "DivZero189NativeFixture";
    public static volatile boolean equipmentClicks;
    public static volatile boolean finished;
    public static volatile boolean successful;
    private static int stage, started;
    private static NativeAgent body;
    private static final JsonObject evidence = new JsonObject();
    private NativeFixture() { }
    public static boolean requested() { return Boolean.getBoolean("divzero.legacyFixture"); }
    public static void tick(MinecraftServer server) {
        if (!requested() || finished || !server.isSinglePlayer()) return;
        if (!new File(server.getFile("."), "divzero-native-fixture-allow").isFile()) return;
        if (!WORLD.equals(server.worldServerForDimension(0).getWorldInfo().getWorldName())) return;
        EntityPlayerMP human = null;
        for (EntityPlayerMP player : server.getConfigurationManager().getPlayerList()) if (!(player instanceof NativeAgent)) { human = player; break; }
        if (human == null || server.getTickCounter() < 180) return;
        try {
            if (Boolean.getBoolean("divzero.legacyFixtureResume")) { resume(server, human); return; }
            if (stage == 0) {
                NativeRuntime.data().enabled(human.getUniqueID(), true);
                for (int x = -6; x <= 6; x++) for (int z = -6; z <= 6; z++) {
                    human.worldObj.setBlockState(new BlockPos(x, 79, z), Blocks.stone.getDefaultState(), 3);
                    for (int y = 80; y <= 85; y++) human.worldObj.setBlockToAir(new BlockPos(x, y, z));
                }
                human.setGameType(net.minecraft.world.WorldSettings.GameType.SURVIVAL);
                human.setPositionAndUpdate(0.5, 80, 0.5);
                human.inventory.clear(); human.setHealth(20); human.getFoodStats().setFoodLevel(20);
                human.inventory.currentItem = 0; human.inventory.setInventorySlotContents(0, new ItemStack(Items.diamond_sword));
                NativeOffhand hand = NativeOffhand.get(human); hand.set(new ItemStack(Items.iron_sword));
                long before = hand.revision(); hand.swap(before);
                require(human.getHeldItem().getItem() == Items.iron_sword && hand.stack().getItem() == Items.diamond_sword, "REAL_SWAP");
                try { hand.swap(before); throw new IllegalStateException("STALE_SWAP_ACCEPTED"); }
                catch (IllegalStateException rejected) { require(hand.stack().getItem() == Items.diamond_sword, "STALE_SWAP_MUTATED"); }
                hand.swap(hand.revision());
                NBTTagCompound saved = new NBTTagCompound(); hand.saveNBTData(saved); hand.set(null); hand.loadNBTData(saved);
                require(hand.stack().getItem() == Items.iron_sword, "OFFHAND_NBT");
                NativeNetwork.sync(human);
                body = NativeRuntime.create(human, "兼容测试 AI");
                body.setPositionAndUpdate(2.5, 83, 0.5); body.inventory.setInventorySlotContents(0, new ItemStack(Items.diamond_sword));
                evidence.addProperty("agent", body.getUniqueID().toString()); evidence.addProperty("world", NativeRuntime.data().identity().toString());
                evidence.addProperty("offhandSwapAndNbt", true); evidence.addProperty("source", "FIXTURE_ONLY");
                evidence.addProperty("modernCombatVerified", false);
                human.openGui(LegacyMod.instance, 0, human.worldObj, 0, 0, 0);
                started = server.getTickCounter(); stage = 1;
            } else if (stage == 1 && equipmentClicks && server.getTickCounter() - started >= 140) {
                require(Math.abs(body.posY - 80) < 0.02, "NATIVE_PLAYER_GRAVITY");
                require(NativeOffhand.get(human).stack() != null && NativeOffhand.get(human).stack().getItem() == Items.iron_sword
                        && NativeOffhand.get(human).stack().stackSize == 1 && human.inventory.getStackInSlot(9) == null, "NATIVE_CONTAINER_CLICKS");
                human.closeScreen();
                float humanBefore = human.getHealth(), agentBefore = body.getHealth();
                body.attackTargetEntityWithCurrentItem(human);
                require(human.getHealth() < humanBefore, "AI_TO_REAL_PLAYER_DAMAGE");
                human.attackTargetEntityWithCurrentItem(body);
                require(body.getHealth() < agentBefore, "REAL_PLAYER_TO_AI_DAMAGE");
                evidence.addProperty("humanDamage", humanBefore - human.getHealth());
                evidence.addProperty("agentDamage", agentBefore - body.getHealth());
                evidence.addProperty("nativeGravity", true); evidence.addProperty("nativeContainer", true);
                server.getConfigurationManager().saveAllPlayerData(); server.saveAllWorlds(false);
                complete(server, true, "NATIVE_BASELINE_PASSED");
            } else if (server.getTickCounter() - started > 1000 && stage > 0) throw new IllegalStateException("NATIVE_FIXTURE_TIMEOUT_STAGE_" + stage);
        } catch (Throwable failure) {
            LegacyMod.logger.error("DIVZERO_LEGACY_FIXTURE_FAILED", failure);
            complete(server, false, failure.toString());
        }
    }
    private static void resume(MinecraftServer server, EntityPlayerMP human) throws Exception {
        File source = server.getFile("divzero-native-result.json");
        JsonObject previous = new JsonParser().parse(new String(Files.readAllBytes(source.toPath()), StandardCharsets.UTF_8)).getAsJsonObject();
        UUID id = UUID.fromString(previous.get("agent").getAsString());
        require(previous.get("successful").getAsBoolean(), "BASELINE_FAILED");
        require(NativeRuntime.data().identity().toString().equals(previous.get("world").getAsString()), "WORLD_ID_NOT_RESTORED");
        require(NativeRuntime.enabled(human), "ACTIVATION_NOT_RESTORED");
        require(server.getConfigurationManager().getPlayerByUUID(id) instanceof NativeAgent, "BODY_ID_NOT_RESTORED");
        require(NativeOffhand.get(human).stack() != null && NativeOffhand.get(human).stack().getItem() == Items.iron_sword, "OFFHAND_NOT_RESTORED");
        evidence.addProperty("agent", id.toString()); evidence.addProperty("world", NativeRuntime.data().identity().toString());
        evidence.addProperty("persistentIdentityAndOffhand", true); evidence.addProperty("source", "FIXTURE_ONLY");
        complete(server, true, "NATIVE_REOPEN_PASSED");
    }
    private static void complete(MinecraftServer server, boolean success, String message) {
        successful = success; evidence.addProperty("successful", success); evidence.addProperty("result", message);
        try {
            File file = server.getFile(Boolean.getBoolean("divzero.legacyFixtureResume") ? "divzero-native-resume.json" : "divzero-native-result.json");
            Files.write(file.toPath(), new GsonBuilder().setPrettyPrinting().create().toJson(evidence).getBytes(StandardCharsets.UTF_8));
        } catch (IOException error) { successful = false; LegacyMod.logger.error("Cannot write legacy fixture receipt", error); }
        finished = true; LegacyMod.logger.info("DIVZERO_LEGACY_FIXTURE_RESULT={}", message);
    }
    private static void require(boolean value, String message) { if (!value) throw new IllegalStateException(message); }
}
