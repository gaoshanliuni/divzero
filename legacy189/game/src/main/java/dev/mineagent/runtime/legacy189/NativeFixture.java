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
    public static final String PACKED_WORLD = "DivZero PvP 1.8.9";
    public static volatile boolean packedChecked, packedUiClicked;
    public static volatile boolean packedMenuRecovered, packedNextRound, packedStopClicked;
    public static volatile boolean packedFoodUsed, packedStopAllowed;
    public static volatile String packedClientFailure;
    private static boolean packedFighting;
    private static ArenaFeedbackVerification feedbackVerification;
    private static NativeComboVerification comboVerification;
    public static volatile int packedGuiScale;
    public static volatile boolean equipmentClicks;
    public static volatile boolean finished;
    public static volatile boolean successful;
    private static int stage, started;
    private static NativeAgent body;
    private static CombatVerification combatVerification;
    private static DuelVerification duelVerification;
    private static java.util.concurrent.CompletableFuture<JsonObject> workerHealth;
    private static final JsonObject evidence = new JsonObject();
    private NativeFixture() { }
    public static boolean requested() { return Boolean.getBoolean("divzero.legacyFixture"); }
    public static boolean duelProbe() { return requested() && stage == 4 && !finished; }
    public static boolean combatProbe(net.minecraft.entity.Entity target, net.minecraft.entity.Entity source) {
        if (!requested() || stage != 3 || body == null) return false;
        return target == body && (source == null || source.getUniqueID().equals(body.owner))
                || target.getUniqueID().equals(body.owner) && source == body;
    }
    public static void tick(MinecraftServer server) {
        if (!requested() || finished || !server.isSinglePlayer()) return;
        if (!new File(server.getFile("."), "divzero-native-fixture-allow").isFile()) return;
        if (!(Boolean.getBoolean("divzero.legacyPackedFixture") ? PACKED_WORLD : WORLD).equals(server.worldServerForDimension(0).getWorldInfo().getWorldName())) return;
        EntityPlayerMP human = null;
        for (EntityPlayerMP player : server.getConfigurationManager().getPlayerList()) if (!(player instanceof NativeAgent)) { human = player; break; }
        if (human == null || server.getTickCounter() < 180) return;
        try {
            if (Boolean.getBoolean("divzero.legacyPackedFixture")) { packed(server, human); return; }
            if (Boolean.getBoolean("divzero.legacyFixtureResume")) { resume(server, human); return; }
            if (stage == 0) {
                UUID beforeActivation = NativeRuntime.session(human);
                NativeRuntime.setEnabled(human, true);
                require(!beforeActivation.equals(NativeRuntime.session(human)), "ACTIVATION_SESSION_NOT_CHANGED");
                if (NativeService.class.getResource("/META-INF/divzero/service.sha256") != null)
                    workerHealth = NativeService.get(human).thenCompose(service -> service.request("health.check", new JsonObject()));
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
            } else if (stage == 1 && equipmentClicks && server.getTickCounter() - started >= 140 && (workerHealth == null || workerHealth.isDone())) {
                if (workerHealth != null) {
                    JsonObject result = workerHealth.getNow(null);
                    require(result != null && result.get("type").getAsString().equals("health.ok"), "REAL_JAVA25_WORKER_HEALTH");
                    evidence.addProperty("java25WorkerHealth", true);
                }
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
                if (server.getFile("divzero-import/pvp-arena-transfer.json").isFile()) { NativeArena.start(human); stage = 2; started = server.getTickCounter(); }
                else { save(server); complete(server, true, "NATIVE_BASELINE_PASSED"); }
            } else if (stage == 2) {
                if (NativeArena.phase().equals("PARTIAL") || NativeArena.phase().equals("REJECTED")) throw new IllegalStateException(NativeArena.error());
                if (NativeArena.phase().equals("VERIFIED")) {
                    require(NativeArena.verified() == NativeArena.VOLUME && NativeArena.ready(), "ARENA_NATIVE_READBACK");
                    evidence.addProperty("arenaVerified", true); evidence.addProperty("arenaBlocks", NativeArena.verified());
                    evidence.addProperty("arenaHash", NativeRuntime.data().arenaHash());
                    body.playerNetServerHandler.setPlayerLocation(5.5, 101, 800.5, 90, 0);
                    combatVerification = new CombatVerification(human, body, evidence); stage = 3;
                } else if (server.getTickCounter() - started > 1000) throw new IllegalStateException("ARENA_FIXTURE_TIMEOUT");
            } else if (stage == 3) {
                if (combatVerification.advance()) { duelVerification = new DuelVerification(human, body, evidence); stage = 4; }
            } else if (stage == 4) {
                if (duelVerification.advance(human)) { save(server); complete(server, true, "NATIVE_PVP_PASSED"); }
            } else if (server.getTickCounter() - started > 1000 && stage > 0) throw new IllegalStateException("NATIVE_FIXTURE_TIMEOUT_STAGE_" + stage);
        } catch (Throwable failure) {
            LegacyMod.logger.error("DIVZERO_LEGACY_FIXTURE_FAILED", failure);
            complete(server, false, failure.toString());
        }
    }
    private static void packed(MinecraftServer server, EntityPlayerMP human) throws Exception {
        require(NativeArena.ready() && ModernCombat.serverEnabled, "PACKED_ARENA_RULES");
        require(!human.canCommandSenderUseCommand(2, "ai"), "PACKED_MAP_GRANTED_COMMANDS");
        if (packedClientFailure != null) throw new IllegalStateException(packedClientFailure);
        require(human.worldObj.getBlockState(new BlockPos(0, 100, 766)).getBlock() == LegacyBlocks.SMOOTH_QUARTZ, "PACKED_REGISTRY_QUARTZ");
        require(human.worldObj.getBlockState(new BlockPos(10, 100, 766)).getBlock() == LegacyBlocks.DEEPSLATE, "PACKED_REGISTRY_DEEPSLATE");
        if (!packedChecked) {
            require(NativeRuntime.data().agents().isEmpty(), "PACKED_MAP_OLD_AGENTS");
            require(!NativeRuntime.enabled(human), "PACKED_MAP_OLD_ENABLE_STATE");
            for (ItemStack stack : human.inventory.mainInventory) require(stack == null, "PACKED_MAP_OLD_INVENTORY");
            require(NativeOffhand.get(human).stack() == null, "PACKED_MAP_OLD_OFFHAND");
            require(Math.abs(human.posY - 101) < .01 && Math.abs(human.posX - .5) < .1 && Math.abs(human.posZ - 766.5) < .1, "PACKED_LOBBY_SPAWN");
            if (Boolean.getBoolean("divzero.legacyComboFixture")) comboVerification = new NativeComboVerification(evidence);
            else feedbackVerification = new ArenaFeedbackVerification(human, evidence);
            packedChecked = true;
        }
        NativeDuel.Session run = NativeDuel.session(human);
        if (comboVerification != null) {
            if (comboVerification.tick(run, server.getTickCounter())) complete(server, true, "NATIVE_COMBO_JUMP_PASSED");
            return;
        }
        feedbackVerification.tick(run, server.getTickCounter());
        if (packedUiClicked && NativeRuntime.enabled(human) && !packedFighting) {
            require(!human.canCommandSenderUseCommand(2, "ai"), "ACTIVATION_GRANTED_COMMANDS");
            require(run.rounds == 0, "PACKED_MAP_OLD_STATISTICS");
            require(packedMenuRecovered, "PACKED_MENU_RECOVERY");
            require(run.active(), "PACKED_ONE_CLICK_START");
            require(packedGuiScale == Integer.getInteger("divzero.fixtureExpectedScale", 2), "PACKED_GUI_SCALE_NOT_EFFECTIVE");
            boolean denied = false;
            try { new NativeCommands().processCommand(human, new String[] {"combat", "legacy"}); } catch (net.minecraft.command.CommandException expected) { denied = true; }
            require(denied && ModernCombat.serverEnabled, "PACKED_GLOBAL_RULE_PERMISSION");
            evidence.addProperty("source", "FIXTURE_ONLY"); evidence.addProperty("cleanTemplateNative", true);
            evidence.addProperty("noOperatorGrant", true); evidence.addProperty("nativeActivationButton", true); evidence.addProperty("loadoutBoundsVisible", true);
            evidence.addProperty("effectiveGuiScale", packedGuiScale); evidence.addProperty("globalRuleMutationDenied", true);
            evidence.addProperty("oneClickStart", true); evidence.addProperty("keyAndPauseMenuRecovery", true);
            if (run.phase.equals("FIGHTING")) packedFighting = true;
        }
        if (packedFighting && run.rounds == 1 && human.isEntityAlive() && !packedNextRound) {
            require(run.result.equals("AI_WON") && run.losses == 1 && run.travel > 3 && run.meleeHits > 0, "PACKED_NATIVE_ROUND");
            if (run.archive == null || !run.archive.isDone()) return;
            run.archive.get();
            evidence.addProperty("cleanMapRoundAndRespawn", true); evidence.addProperty("aiTravel", run.travel); evidence.addProperty("aiMeleeHits", run.meleeHits);
            packedNextRound = true;
        }
        if (packedStopClicked && !run.active()) {
            require(run.result.equals("CANCELLED") && run.rounds == 1 && run.losses == 1, "PACKED_NEXT_ROUND_CANCEL");
            evidence.addProperty("nextRoundAndStop", true);
            require(packedStopAllowed, "FEEDBACK_SCENES_INCOMPLETE");
            complete(server, true, "NATIVE_PVP_FEEDBACK_PASSED");
        }
        if (server.getTickCounter() > 2600) throw new IllegalStateException("PACKED_UI_TIMEOUT phase=" + run.phase);
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
        if (previous.has("arenaVerified") && previous.get("arenaVerified").getAsBoolean()) {
            require(NativeArena.ready() && NativeRuntime.data().arenaHash().equals(previous.get("arenaHash").getAsString()), "ARENA_MARKER_NOT_RESTORED");
            require(human.worldObj.getBlockState(new BlockPos(0, 100, 766)).getBlock() == LegacyBlocks.SMOOTH_QUARTZ, "ARENA_QUARTZ_NOT_RESTORED");
            require(human.worldObj.getBlockState(new BlockPos(10, 100, 766)).getBlock() == LegacyBlocks.DEEPSLATE, "ARENA_DEEPSLATE_NOT_RESTORED");
            evidence.addProperty("persistentArena", true);
        }
        complete(server, true, "NATIVE_REOPEN_PASSED");
    }
    private static void save(MinecraftServer server) throws Exception {
        server.getConfigurationManager().saveAllPlayerData();
        for (net.minecraft.world.WorldServer level : server.worldServers) level.saveAllChunks(true, null);
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
