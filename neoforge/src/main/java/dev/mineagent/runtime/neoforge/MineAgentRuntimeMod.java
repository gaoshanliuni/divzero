package dev.mineagent.runtime.neoforge;

import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.bus.api.SubscribeEvent;
import dev.mineagent.runtime.neoforge.command.MineAgentCommands;
import dev.mineagent.runtime.neoforge.integration.NeoForgeYsmRuntimeBridge;
import dev.mineagent.runtime.neoforge.network.MineAgentNetwork;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import org.slf4j.Logger;

@Mod(MineAgentRuntimeMod.MOD_ID)
public final class MineAgentRuntimeMod {
    public static final String MOD_ID = "mineagent_runtime";
    public static final Logger LOGGER = LogUtils.getLogger();
    public static final java.security.KeyPair SECRET_TRANSPORT_KEYS = createSecretTransportKeys();
    private final java.util.Map<net.minecraft.server.MinecraftServer, SmokeTestState> smokeTests = new java.util.IdentityHashMap<>();
    private final java.util.Map<net.minecraft.server.MinecraftServer, Integer> multiplayerSmokeStops =
            new java.util.IdentityHashMap<>();
    private final java.util.Set<net.minecraft.server.MinecraftServer> multiplayerSmokeVerified =
            java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    private final java.util.Map<net.minecraft.server.MinecraftServer, com.sun.net.httpserver.HttpServer>
            multiplayerMediaServers = new java.util.IdentityHashMap<>();
    private final java.util.Map<net.minecraft.server.MinecraftServer, MultiplayerYsmAttempt>
            multiplayerYsmAttempts = new java.util.IdentityHashMap<>();
    private final java.util.Map<net.minecraft.server.MinecraftServer, Integer> restartYsmStops =
            new java.util.IdentityHashMap<>();
    private final java.util.Map<net.minecraft.server.MinecraftServer, RestartYsmAttempt> restartYsmAttempts =
            new java.util.IdentityHashMap<>();

    public MineAgentRuntimeMod(IEventBus modBus, ModContainer container) {
        LOGGER.info("MineAgent Runtime initializing for Minecraft 26.1.2");
        MineAgentRegistries.register(modBus);
        modBus.addListener(MineAgentNetwork::register);
        modBus.addListener(this::addCreativeTabContents);
        modBus.addListener(this::createEntityAttributes);
        modBus.addListener(this::legacyPackFinder);
        NeoForge.EVENT_BUS.register(this);
    }

    private void addCreativeTabContents(net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == net.minecraft.world.item.CreativeModeTabs.FUNCTIONAL_BLOCKS) {
            for(String id:dev.mineagent.runtime.neoforge.content.LegacyContentBoundary.defaultCreativeItems())
                event.accept(net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(net.minecraft.resources.Identifier.parse(id)));
        }
    }
    private void legacyPackFinder(net.neoforged.neoforge.event.AddPackFindersEvent event){
        event.addPackFinders(net.minecraft.resources.Identifier.fromNamespaceAndPath(MOD_ID,"resourcepacks/legacy_compat"),
                net.minecraft.server.packs.PackType.SERVER_DATA,net.minecraft.network.chat.Component.literal("MineAgent 旧内容兼容包（手动启用）"),
                net.minecraft.server.packs.repository.PackSource.create(java.util.function.UnaryOperator.identity(),false),
                Boolean.getBoolean("mineagent.legacyFixtureContent"),net.minecraft.server.packs.repository.Pack.Position.TOP);
    }

    private void createEntityAttributes(net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent event) {
        event.put(MineAgentRegistries.SENTINEL_BOSS.get(),
                dev.mineagent.runtime.neoforge.content.SentinelBoss.createAttributes().build());
    }

    @SubscribeEvent
    public void registerCommands(RegisterCommandsEvent event) {
        MineAgentCommands.register(event.getDispatcher());
    }

    @SubscribeEvent
    public void afterServerTick(ServerTickEvent.Post event) {
        if(!WorldIdentityRuntime.ready(event.getServer()))return;
        MineAgentRuntimeServices.sharedStates(event.getServer()).tick();
        MineAgentRuntimeServices.events(event.getServer()).tick();
        MineAgentRuntimeServices.schedules(event.getServer()).tick();
        MineAgentRuntimeServices.taskExecutor(event.getServer()).reconcileAuthority();
        MineAgentRuntimeServices.bodies(event.getServer()).tickBodies(event.getServer().getTickCount());
        MineAgentRuntimeServices.taskExecutor(event.getServer()).tickWorldActions();
        MineAgentRuntimeServices.packages(event.getServer()).tick(event.getServer().getTickCount());
        dev.mineagent.runtime.neoforge.content.WorldContentRuntime.tick(event.getServer());
        dev.mineagent.runtime.neoforge.content.NativeDataPackRuntime.tick(event.getServer());
        MineAgentRuntimeServices.packages(event.getServer()).fire("server.tick", event.getServer().getTickCount());
        dev.mineagent.runtime.neoforge.ui.StudioScriptRuntime.tick(event.getServer());
        if (event.getServer().getTickCount() % 10 == 0) {
            MineAgentRuntimeServices.bodies(event.getServer()).tickAutonomy();
        }
        if (event.getServer().getTickCount() % 20 == 0) {
            try {
                MineAgentRuntimeServices.decisions(event.getServer()).reconcileTasks(MineAgentRuntimeServices.tasks(event.getServer()));
                MineAgentRuntimeServices.decisions(event.getServer()).resumeAcceptedTasks(MineAgentRuntimeServices.tasks(event.getServer()));
            } catch (Exception failure) { LOGGER.warn("Could not resume a persisted decision task", failure); }
            MineAgentRuntimeServices.taskExecutor(event.getServer()).tick(event.getServer().getTickCount());
        }
        if (event.getServer().getTickCount() % 5 == 0) {
            MineAgentRuntimeServices.mediaCoordinator(event.getServer()).tick();
        }
        MultiplayerYsmAttempt ysmAttempt = multiplayerYsmAttempts.get(event.getServer());
        if (ysmAttempt != null && event.getServer().getTickCount() >= ysmAttempt.executeAtTick()) {
            if (applyMultiplayerYsm(event.getServer(), ysmAttempt.agentId())) {
                multiplayerYsmAttempts.remove(event.getServer());
            } else if (ysmAttempt.attempt() >= 5) {
                throw new IllegalStateException("Multiplayer YSM model did not become available after bounded retries");
            } else {
                multiplayerYsmAttempts.put(event.getServer(), new MultiplayerYsmAttempt(
                        ysmAttempt.agentId(), ysmAttempt.attempt() + 1, event.getServer().getTickCount() + 40));
            }
        }
        RestartYsmAttempt restartAttempt = restartYsmAttempts.get(event.getServer());
        if (restartAttempt != null && event.getServer().getTickCount() >= restartAttempt.executeAtTick()) {
            var reapplied = dev.mineagent.runtime.neoforge.integration.MineAgentAppearanceLifecycle.reapply(
                    event.getServer(), restartAttempt.agentId(), "server-restart").orElseThrow();
            if (reapplied.applied()) {
                restartYsmAttempts.remove(event.getServer());
                LOGGER.info("MINEAGENT_SMOKE_YSM_RESTART_SERVER_OK agent={} revision={}",
                        restartAttempt.agentId(), reapplied.revision());
                restartYsmStops.put(event.getServer(), event.getServer().getTickCount() + 200);
            } else if (restartAttempt.attempt() >= 10) {
                throw new IllegalStateException("Persisted YSM appearance did not reapply after bounded retries: "
                        + reapplied);
            } else {
                restartYsmAttempts.put(event.getServer(), new RestartYsmAttempt(
                        restartAttempt.agentId(), restartAttempt.attempt() + 1,
                        event.getServer().getTickCount() + 20));
            }
        }
        Integer multiplayerStop = multiplayerSmokeStops.get(event.getServer());
        if (multiplayerStop != null && event.getServer().getTickCount() >= multiplayerStop) {
            multiplayerSmokeStops.remove(event.getServer());
            event.getServer().halt(false);
            return;
        }
        Integer restartStop = restartYsmStops.get(event.getServer());
        if (restartStop != null && event.getServer().getTickCount() >= restartStop) {
            restartYsmStops.remove(event.getServer());
            event.getServer().halt(false);
            return;
        }
        SmokeTestState smoke = smokeTests.get(event.getServer());
        if (smoke != null && !smoke.deathTriggered && event.getServer().getTickCount() >= smoke.killAtTick) {
            smoke.deathTriggered = true;
            // die() alone does not lower ServerPlayer health; exercise the actual damage/death path.
            var dying=smoke.originalBody;
            dying.invulnerableTime=0;
            if(!dying.hurtServer(dying.level(),dying.damageSources().genericKill(),1000)||!dying.deathAccepted())
                throw new IllegalStateException("SmokeBot native kill rejected: health="+dying.getHealth()+", changingDimension="+dying.isChangingDimension()+", clientLoaded="+dying.connection.hasClientLoaded());
            LOGGER.info("MINEAGENT_SMOKE_DEATH_TRIGGERED agentId={}", smoke.agentId);
        }
        if (smoke != null && event.getServer().getTickCount() >= smoke.verifyAtTick()) {
            try {
                var body = MineAgentRuntimeServices.bodies(event.getServer()).body(smoke.agentId).orElseThrow();
                if (!body.isAlive()) {
                    throw new IllegalStateException("SmokeBot did not remain alive");
                }
                if (body == smoke.originalBody) {
                    throw new IllegalStateException("SmokeBot did not respawn with a replacement body");
                }
                for (java.util.UUID extraId : smoke.extraAgentIds) {
                    var extra = MineAgentRuntimeServices.bodies(event.getServer()).body(extraId).orElseThrow();
                    if (!extra.isAlive() || extra.tickCount < 40) {
                        throw new IllegalStateException("Parallel SmokeBot did not tick: " + extraId);
                    }
                }
                var navigator = MineAgentRuntimeServices.bodies(event.getServer())
                        .body(smoke.navigationAgentId).orElseThrow();
                if (navigator.position().distanceToSqr(smoke.navigationTarget) > 2.25) {
                    throw new IllegalStateException("Built-in navigator failed obstacle route: "
                            + navigator.position() + " -> " + smoke.navigationTarget);
                }
                if (body.tickCount < 20) {
                    throw new IllegalStateException("SmokeBot did not receive regular ticks: " + body.tickCount);
                }
                if (Boolean.getBoolean("mineagent.productionYsmSmokeTest")
                        || Boolean.getBoolean("mineagent.productionYsmDedicatedSmokeTest")) {
                    var lifecycle = dev.mineagent.runtime.neoforge.integration.MineAgentAppearanceLifecycle.reapply(
                            event.getServer(), smoke.agentId, "verification").orElseThrow();
                    if (!lifecycle.applied()) {
                        throw new IllegalStateException("YSM appearance was not restored after respawn: "
                                + lifecycle.diagnosticCode());
                    }
                    if (Boolean.getBoolean("mineagent.productionYsmDedicatedSmokeTest")) {
                        var origin = body.level();
                        var target = event.getServer().getLevel(net.minecraft.world.level.Level.NETHER);
                        if (target == null || !MineAgentRuntimeServices.bodies(event.getServer()).changeDimension(
                                smoke.agentId, target,
                                net.minecraft.world.phys.Vec3.atBottomCenterOf(target.getRespawnData().pos()))
                                || !MineAgentRuntimeServices.bodies(event.getServer()).changeDimension(
                                smoke.agentId, origin,
                                net.minecraft.world.phys.Vec3.atBottomCenterOf(origin.getRespawnData().pos()))
                                || !dev.mineagent.runtime.neoforge.integration.MineAgentAppearanceLifecycle.reapply(
                                event.getServer(), smoke.agentId, "dedicated-dimension-verification")
                                .map(dev.mineagent.runtime.integrations.ysm.AppearanceLifecycleCoordinator.Result::applied)
                                .orElse(false)) {
                            throw new IllegalStateException("Dedicated YSM post-load dimension reapply failed");
                        }
                        LOGGER.info("MINEAGENT_SMOKE_YSM_DEDICATED_LIFECYCLE_OK "
                                        + "dimension=true respawn=true initialMissingAssetDiagnosed=true revision={}",
                                lifecycle.revision());
                    } else {
                        LOGGER.info("MINEAGENT_SMOKE_YSM_LIFECYCLE_OK respawn=true tracking=true revision={}",
                                lifecycle.revision());
                    }
                }
                int basketballScore = MineAgentRuntimeServices.basketball(event.getServer())
                        .score(smoke.agentId);
                if (basketballScore < 2) {
                    throw new IllegalStateException("Basketball entity did not score through the hoop: " + basketballScore);
                }
                for (var player : event.getServer().getPlayerList().getPlayers()) {
                    if (!(player instanceof dev.mineagent.runtime.neoforge.body.MineAgentPlayer)) {
                        dev.mineagent.runtime.neoforge.network.MineAgentNetwork.sendBasketballScore(
                                event.getServer(), player.getUUID(), 2, 2);
                    }
                }
                LOGGER.info("MINEAGENT_SMOKE_BASKETBALL_OK score={}", basketballScore);
                LOGGER.info("MINEAGENT_SMOKE_LIFECYCLE_OK death=true respawn=true dimension=true combat=true");
                LOGGER.info("MINEAGENT_SMOKE_FOUR_AGENTS_OK active={}",
                        MineAgentRuntimeServices.bodies(event.getServer()).definitions().size());
                LOGGER.info("MINEAGENT_SMOKE_NAVIGATION_OK backend={} distance={}",
                        navigator.movementController().backendName(),
                        Math.sqrt(navigator.position().distanceToSqr(smoke.navigationTarget)));
                LOGGER.info("MINEAGENT_SMOKE_BODY_TICK_OK tickCount={} health={}", body.tickCount, body.getHealth());
            } finally {
                smokeTests.remove(event.getServer());
                event.getServer().halt(false);
            }
        }
    }

    @SubscribeEvent
    public void serverStarted(ServerStartedEvent event) {
        if(!WorldIdentityRuntime.boot(event.getServer())){LOGGER.warn("MineAgent world identity unresolved; no world services started. Use /ai identity.");return;}
        try {
            MineAgentRuntimeServices.config(event.getServer());
            String workerStatus = MineAgentRuntimeServices.worker(event.getServer())
                    .start(event.getServer().getServerDirectory());
            LOGGER.info("MineAgent Worker status={}", workerStatus);
            dev.mineagent.runtime.neoforge.content.WorldContentRuntime.get(event.getServer());
            dev.mineagent.runtime.neoforge.content.NativeDataPackRuntime.get(event.getServer());
            MineAgentRuntimeServices.events(event.getServer());
            MineAgentRuntimeServices.schedules(event.getServer());
            if (Boolean.getBoolean("mineagent.smokeTest")) {
                String source = """
                        package dev.mineagent.smoke;
                        public final class SmokeExtension implements dev.mineagent.runtime.scripting.javaext.RuntimeExtension {
                          public Object start(java.util.Map<String,Object> bindings) { return bindings.get("value"); }
                        }
                        """;
                var compiled = MineAgentRuntimeServices.worker(event.getServer())
                        .compileJava("dev.mineagent.smoke.SmokeExtension", source).get(20, java.util.concurrent.TimeUnit.SECONDS);
                if (!"java.compile.result".equals(compiled.type())
                        || !Boolean.parseBoolean(String.valueOf(compiled.payload().getOrDefault("success", false)))) {
                    throw new IllegalStateException("Worker Java compile smoke failed: " + compiled.payload());
                }
                String hash = String.valueOf(compiled.payload().get("sha256"));
                var loaded = MineAgentRuntimeServices.javaExtensions(event.getServer()).load(
                        MineAgentRuntimeServices.worker(event.getServer()).contentPath(hash), hash,
                        "dev.mineagent.smoke.SmokeExtension", java.util.Map.of("value", 9));
                if (!java.util.Objects.equals(9, loaded.startResult())) {
                    throw new IllegalStateException("Worker Java hot-load smoke failed");
                }
                LOGGER.info("MINEAGENT_SMOKE_JAVA_EXTENSION_OK activation={} result={}",
                        loaded.activationMode(), loaded.startResult());
            }
            for (var media : MineAgentRuntimeServices.media(event.getServer()).all()) {
                if (media.playing() && !media.screenBinding().isBlank()) {
                    MineAgentRuntimeServices.mediaCoordinator(event.getServer()).start(media);
                }
            }
        } catch (Exception failure) {
            LOGGER.error("MineAgent Worker failed to start", failure);
        }
        if (Boolean.getBoolean("mineagent.productionYsmRestartSmokeTest")) {
            var restored = MineAgentRuntimeServices.bodies(event.getServer()).definitions().stream()
                    .filter(agent -> !MineAgentRuntimeServices.config(event.getServer()).snapshot().values()
                            .getOrDefault("agent." + agent.agentId() + ".model", "").isBlank())
                    .findFirst().orElseThrow(() -> new IllegalStateException("No persisted YSM agent was restored"));
            restartYsmAttempts.put(event.getServer(),
                    new RestartYsmAttempt(restored.agentId(), 1, event.getServer().getTickCount() + 20));
            return;
        }
        if (!Boolean.getBoolean("mineagent.smokeTest")) {
            return;
        }
        var server = event.getServer();
        var level = server.overworld();
        for (long packed : level.getChunkSource().getForceLoadedChunks().toLongArray()) {
            level.getChunkSource().updateChunkForced(net.minecraft.world.level.ChunkPos.unpack(packed), false);
        }
        var spawn = level.getRespawnData().pos();
        var smokeOwner = Boolean.getBoolean("mineagent.productionYsmSmokeTest")
                ? new java.util.UUID(0, 1)
                : new java.util.UUID(0, 0);
        var definition = Boolean.getBoolean("mineagent.productionYsmSmokeTest")
                ? MineAgentRuntimeServices.bodies(server).createPersistentAt(
                "SmokeBot", smokeOwner, level, net.minecraft.world.phys.Vec3.atBottomCenterOf(spawn))
                : MineAgentRuntimeServices.bodies(server).createAt(
                "SmokeBot",
                smokeOwner,
                level,
                net.minecraft.world.phys.Vec3.atBottomCenterOf(spawn)
        );
        LOGGER.info("MINEAGENT_SMOKE_BODY_CREATED agentId={} class={}",
                definition.agentId(),
                MineAgentRuntimeServices.bodies(server).body(definition.agentId()).orElseThrow().getClass().getName());
        var body = MineAgentRuntimeServices.bodies(server).body(definition.agentId()).orElseThrow();
        var actionPos = body.blockPosition().east(2);
        level.setBlockAndUpdate(actionPos.below(), net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(actionPos, net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
        if (!MineAgentRuntimeServices.bodies(server).beginMining(definition.agentId(), actionPos)
                || !level.getBlockState(actionPos).isAir()) {
            throw new IllegalStateException("SmokeBot failed a real creative block break");
        }
        if (!MineAgentRuntimeServices.bodies(server).placeBlock(
                definition.agentId(), actionPos, net.minecraft.world.level.block.Blocks.STONE)
                || !level.getBlockState(actionPos).is(net.minecraft.world.level.block.Blocks.STONE)) {
            throw new IllegalStateException("SmokeBot failed a real creative block placement");
        }
        LOGGER.info("MINEAGENT_SMOKE_ACTIONS_OK break=true place=true");
        var scriptPosition = body.blockPosition().north(4);
        level.setBlockAndUpdate(scriptPosition.below(), net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(scriptPosition, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
        String scriptSource = "host.placeBlock(\"minecraft:overworld\"," + scriptPosition.getX() + ","
                + scriptPosition.getY() + "," + scriptPosition.getZ() + ",\"minecraft:gold_block\");";
        var scriptActivation = MineAgentRuntimeServices.packages(server).activate(
                new dev.mineagent.runtime.api.packages.RuntimePackageCandidate(
                        java.util.UUID.randomUUID(), 1, java.util.UUID.randomUUID(), 1,
                        dev.mineagent.runtime.api.packages.ActivationMode.HOT_RUNTIME, "smoke.js", scriptSource),
                1, java.util.Map.of("host",
                        new dev.mineagent.runtime.neoforge.scripting.MineAgentScriptHost(server, definition.ownerPlayerId())));
        if (!scriptActivation.activated()
                || !level.getBlockState(scriptPosition).is(net.minecraft.world.level.block.Blocks.GOLD_BLOCK)
                || MineAgentRuntimeServices.changeJournal(server).all().isEmpty()) {
            throw new IllegalStateException("Rhino script host or change journal smoke failed: " + scriptActivation);
        }
        LOGGER.info("MINEAGENT_SMOKE_SCRIPT_HOST_OK journal=true modules=true events=true");
        if (!MineAgentRuntimeServices.bodies(server).setMode(
                definition.agentId(), definition.ownerPlayerId(), false,
                dev.mineagent.runtime.api.agent.AgentMode.SURVIVAL)) {
            throw new IllegalStateException("SmokeBot failed to enter survival mode");
        }
        body.getInventory().add(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.OAK_LOG));
        var oakPlanksRecipe = net.minecraft.resources.ResourceKey.create(
                net.minecraft.core.registries.Registries.RECIPE,
                net.minecraft.resources.Identifier.parse("minecraft:oak_planks"));
        var crafted = MineAgentRuntimeServices.bodies(server).craft(definition.agentId(), oakPlanksRecipe);
        if (crafted.isEmpty() || !crafted.get().is(net.minecraft.world.item.Items.OAK_PLANKS)) {
            throw new IllegalStateException("SmokeBot failed real survival crafting");
        }
        MineAgentRuntimeServices.bodies(server).setMode(
                definition.agentId(), definition.ownerPlayerId(), false,
                dev.mineagent.runtime.api.agent.AgentMode.CREATOR);
        var chestPos = body.blockPosition().west(2);
        level.setBlockAndUpdate(chestPos, net.minecraft.world.level.block.Blocks.CHEST.defaultBlockState());
        if (!MineAgentRuntimeServices.bodies(server).openContainer(definition.agentId(), chestPos)) {
            throw new IllegalStateException("SmokeBot failed real container interaction");
        }
        body.closeContainer();
        LOGGER.info("MINEAGENT_SMOKE_SURVIVAL_ACTIONS_OK craft=true container=true");
        var chest = (net.minecraft.world.level.block.entity.ChestBlockEntity) level.getBlockEntity(chestPos);
        chest.setItem(0, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND, 3));
        var chestSnapshot = new dev.mineagent.runtime.api.recovery.BlockSnapshot(
                level.dimension().identifier().toString(), chestPos.getX(), chestPos.getY(), chestPos.getZ(),
                net.minecraft.commands.arguments.blocks.BlockStateParser.serialize(level.getBlockState(chestPos)),
                chest.saveWithFullMetadata(level.registryAccess()).toString());
        level.setBlockAndUpdate(chestPos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
        dev.mineagent.runtime.neoforge.network.MineAgentNetwork.restoreBlocksForSmoke(
                server, java.util.List.of(chestSnapshot));
        if (!(level.getBlockEntity(chestPos) instanceof net.minecraft.world.Container restoredChest)
                || !restoredChest.getItem(0).is(net.minecraft.world.item.Items.DIAMOND)
                || restoredChest.getItem(0).getCount() != 3) {
            throw new IllegalStateException("Block entity NBT restore smoke failed");
        }
        LOGGER.info("MINEAGENT_SMOKE_BLOCK_ENTITY_NBT_RESTORE_OK item=diamond count=3");
        var veinStart = body.blockPosition().west(7);
        for (int index = 0; index < 5; index++) {
            level.setBlockAndUpdate(veinStart.east(index),
                    net.minecraft.world.level.block.Blocks.COAL_ORE.defaultBlockState());
        }
        int veinMined = MineAgentRuntimeServices.bodies(server).mineVein(definition.agentId(), veinStart, 5);
        if (veinMined != 5) {
            throw new IllegalStateException("SmokeBot failed bounded vein mining: " + veinMined);
        }
        LOGGER.info("MINEAGENT_SMOKE_VEIN_MINING_OK blocks={}", veinMined);
        var blade = new net.minecraft.world.item.ItemStack(MineAgentRegistries.AGENT_BLADE.get());
        if (!"mineagent_runtime:m6".equals(blade.get(MineAgentRegistries.CREATION_ORIGIN.get()))) {
            throw new IllegalStateException("Custom item data component is unavailable");
        }
        var studioKey = net.minecraft.resources.ResourceKey.create(
                net.minecraft.core.registries.Registries.DIMENSION,
                net.minecraft.resources.Identifier.fromNamespaceAndPath(MOD_ID, "studio"));
        if (server.getLevel(studioKey) == null) {
            throw new IllegalStateException("Studio dimension is unavailable");
        }
        var sentinel = MineAgentRegistries.SENTINEL_BOSS.get().create(
                level, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
        if (sentinel == null) {
            throw new IllegalStateException("Sentinel Boss factory failed");
        }
        sentinel.snapTo(body.getX() + 4, body.getY(), body.getZ() + 4, 0, 0);
        sentinel.setHealth(sentinel.getMaxHealth());
        if (sentinel.getMaxHealth() < 200 || !level.addFreshEntity(sentinel)) {
            throw new IllegalStateException("Sentinel Boss attributes/spawn failed");
        }
        sentinel.discard();
        level.setBlockAndUpdate(body.blockPosition().south(3),
                MineAgentRegistries.AUTOMATION_CONSOLE.get().defaultBlockState());
        level.setBlockAndUpdate(body.blockPosition().south(4),
                MineAgentRegistries.RUNTIME_CRYSTAL.get().defaultBlockState());
        LOGGER.info("MINEAGENT_SMOKE_EXTENSION_CHAIN_OK weapon=true itemData=true boss=true machine=true worldgen=true dimension=true");
        var zombie = net.minecraft.world.entity.EntityType.ZOMBIE.create(
                level, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
        if (zombie == null) {
            throw new IllegalStateException("Could not create combat target");
        }
        zombie.snapTo(body.getX(), body.getY(), body.getZ() + 2, 0, 0);
        level.addFreshEntity(zombie);
        float healthBefore = zombie.getHealth();
        if (!MineAgentRuntimeServices.bodies(server).attack(definition.agentId(), zombie)
                || zombie.getHealth() >= healthBefore) {
            throw new IllegalStateException("SmokeBot failed real combat action");
        }
        zombie.discard();
        var nether = server.getLevel(net.minecraft.world.level.Level.NETHER);
        if (nether == null || !MineAgentRuntimeServices.bodies(server).changeDimension(
                definition.agentId(), nether, net.minecraft.world.phys.Vec3.atBottomCenterOf(nether.getRespawnData().pos()))
                || body.level() != nether
                || !MineAgentRuntimeServices.bodies(server).changeDimension(
                definition.agentId(), level, net.minecraft.world.phys.Vec3.atBottomCenterOf(level.getRespawnData().pos()))
                || body.level() != level) {
            throw new IllegalStateException("SmokeBot failed dimension transition");
        }
        var hoopPos = body.blockPosition().east(4).above();
        for (int y = 1; y <= 6; y++) {
            level.setBlockAndUpdate(hoopPos.above(y), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
        }
        level.setBlockAndUpdate(hoopPos, MineAgentRegistries.BASKETBALL_HOOP.get().defaultBlockState());
        var basketball = new dev.mineagent.runtime.neoforge.basketball.BasketballEntity(
                level, body, new net.minecraft.world.item.ItemStack(MineAgentRegistries.BASKETBALL.get()));
        basketball.setPos(hoopPos.getX() + 0.5, hoopPos.getY() + 1.2, hoopPos.getZ() + 0.5);
        basketball.setDeltaMovement(0, -0.32, 0);
        level.addFreshEntity(basketball);
        var ysm = new NeoForgeYsmRuntimeBridge(server);
        LOGGER.info("MINEAGENT_SMOKE_YSM_STATUS installed={} checksumVerified={} runtimeAvailable={} version={}",
                ysm.installed(), ysm.checksumVerified(), ysm.runtimeAvailable(), ysm.version());
        if (Boolean.getBoolean("mineagent.productionYsmSmokeTest")
                || Boolean.getBoolean("mineagent.productionYsmDedicatedSmokeTest")) {
            if (!ysm.installed() || !ysm.checksumVerified() || !ysm.runtimeAvailable()) {
                throw new IllegalStateException("Pinned YSM native runtime did not initialize in production");
            }
            LOGGER.info("MINEAGENT_SMOKE_YSM_RUNTIME_READY version={} native=true checksum=true", ysm.version());
        }
        if (Boolean.getBoolean("mineagent.productionYsmDedicatedSmokeTest")) {
            long appearanceRevision = 1;
            var config = MineAgentRuntimeServices.config(server);
            var current = config.snapshot();
            String prefix = "agent." + definition.agentId() + ".";
            var persisted = config.apply(new dev.mineagent.runtime.api.config.ConfigPatch(
                    current.revision(), java.util.Map.of(
                    prefix + "model", "default",
                    prefix + "texture", "blue",
                    prefix + "animation", "idle",
                    prefix + "appearanceRevision", Long.toString(appearanceRevision),
                    prefix + "appearanceWorldId", MineAgentRuntimeServices.worldId(server).toString(),
                    prefix + "appearanceProvider", "ysm",
                    prefix + "appearanceProviderVersion", ysm.version(),
                    prefix + "appearanceStatus", "PENDING_REAPPLY",
                    prefix + "appearanceDiagnostic", ""
            )), true);
            var dedicatedNether = server.getLevel(net.minecraft.world.level.Level.NETHER);
            if (!persisted.accepted() || dedicatedNether == null
                    || !MineAgentRuntimeServices.bodies(server).changeDimension(
                    definition.agentId(), dedicatedNether,
                    net.minecraft.world.phys.Vec3.atBottomCenterOf(dedicatedNether.getRespawnData().pos()))
                    || !MineAgentRuntimeServices.bodies(server).changeDimension(
                    definition.agentId(), level, body.position())) {
                throw new IllegalStateException("Dedicated YSM dimension lifecycle failed");
            }
            var diagnostic = dev.mineagent.runtime.neoforge.integration.MineAgentAppearanceLifecycle.reapply(
                    server, definition.agentId(), "dedicated-diagnostic").orElseThrow();
            if (diagnostic.applied() || !"YSM_ASSET_UNAVAILABLE".equals(diagnostic.diagnosticCode())) {
                throw new IllegalStateException("Dedicated missing-asset diagnostic mismatch: " + diagnostic);
            }
            LOGGER.info("MINEAGENT_SMOKE_YSM_DEDICATED_DIMENSION_OK "
                    + "attempted=true missingAssetDiagnostic=YSM_ASSET_UNAVAILABLE revision={}", appearanceRevision);
        }
        var extraIds = new java.util.ArrayList<java.util.UUID>();
        for (int index = 2; index <= 4; index++) {
            var extra = MineAgentRuntimeServices.bodies(server).createAt(
                    "SmokeBot" + index, new java.util.UUID(0, 0), level,
                    body.position().add(index, 0, index));
            extraIds.add(extra.agentId());
        }
        var navigationBody = MineAgentRuntimeServices.bodies(server).body(extraIds.getFirst()).orElseThrow();
        navigationBody.snapTo(body.getX() + 10, body.getY(), body.getZ() + 10, 0, 0);
        var navigationStart = navigationBody.blockPosition();
        for (int x = -1; x <= 6; x++) {
            for (int z = -2; z <= 2; z++) {
                var foot = navigationStart.offset(x, 0, z);
                level.setBlockAndUpdate(foot.below(), net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
                level.setBlockAndUpdate(foot, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
                level.setBlockAndUpdate(foot.above(), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
            }
        }
        level.setBlockAndUpdate(navigationStart.east(2), net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(navigationStart.east(2).above(),
                net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
        var navigationTarget = net.minecraft.world.phys.Vec3.atBottomCenterOf(navigationStart.east(5));
        navigationBody.movementController().moveTo(navigationTarget);
        try {
            MineAgentRuntimeServices.bodies(server).createAt(
                    "SmokeBot5", new java.util.UUID(0, 0), level, body.position());
            throw new IllegalStateException("AI player maximum was not enforced");
        } catch (dev.mineagent.runtime.core.agent.AgentLimitException expected) {
            // Expected capacity guard.
        }
        smokeTests.put(server, new SmokeTestState(
                definition.agentId(), body, extraIds, navigationBody.agentId(), navigationTarget,
                server.getTickCount() + (Boolean.getBoolean("mineagent.productionYsmSmokeTest") ? 360 : 20),
                server.getTickCount() + (Boolean.getBoolean("mineagent.productionYsmSmokeTest") ? 520 : 90)));
    }

    @SubscribeEvent
    public void serverStopped(ServerStoppedEvent event) {
        smokeTests.remove(event.getServer());
        multiplayerSmokeStops.remove(event.getServer());
        multiplayerSmokeVerified.remove(event.getServer());
        multiplayerYsmAttempts.remove(event.getServer());
        restartYsmStops.remove(event.getServer());
        restartYsmAttempts.remove(event.getServer());
        com.sun.net.httpserver.HttpServer mediaServer = multiplayerMediaServers.remove(event.getServer());
        if (mediaServer != null) {
            mediaServer.stop(0);
        }
        try{MineAgentRuntimeServices.remove(event.getServer());}finally{WorldIdentityRuntime.close(event.getServer());}
    }

    @SubscribeEvent
    public void serverStopping(net.neoforged.neoforge.event.server.ServerStoppingEvent event){
        dev.mineagent.runtime.neoforge.ui.ServerStudioCoder.stopServer(event.getServer());
        dev.mineagent.runtime.neoforge.ui.StudioScriptRuntime.stopServer(event.getServer());
        dev.mineagent.runtime.neoforge.boot.NativeBootRuntime.stop(event.getServer());
        dev.mineagent.runtime.neoforge.content.NativeDataPackRuntime.stop(event.getServer());
        MineAgentRuntimeServices.prepareBodyShutdown(event.getServer());
    }

    @SubscribeEvent
    public void interactWithAgent(net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.EntityInteract event) {
        if (!(event.getTarget() instanceof dev.mineagent.runtime.neoforge.body.MineAgentPlayer)
                || !(event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player)) {
            return;
        }
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(
                player, new dev.mineagent.runtime.neoforge.network.MineAgentPayloads.OpenPanel("AGENTS"));
        event.setCancellationResult(net.minecraft.world.InteractionResult.SUCCESS);
        event.setCanceled(true);
    }

    @SubscribeEvent
    public void reapplyAppearanceForObserver(net.neoforged.neoforge.event.entity.player.PlayerEvent.StartTracking event) {
        if (!(event.getEntity() instanceof net.minecraft.server.level.ServerPlayer observer)
                || observer instanceof dev.mineagent.runtime.neoforge.body.MineAgentPlayer
                || !(event.getTarget() instanceof dev.mineagent.runtime.neoforge.body.MineAgentPlayer agent)) {
            return;
        }
        dev.mineagent.runtime.neoforge.integration.MineAgentAppearanceLifecycle.reapply(
                agent.level().getServer(), agent.agentId(), "tracking:" + observer.getUUID());
    }

    @SubscribeEvent
    public void onServerChat(net.neoforged.neoforge.event.ServerChatEvent event) {
        var player = event.getPlayer();
        var server = player.level().getServer();
        if(!WorldIdentityRuntime.ready(server))return;
        var agents = MineAgentRuntimeServices.bodies(server).definitions();
        var mentions=dev.mineagent.runtime.core.interaction.AgentMention.resolve(event.getRawText(),agents.stream().map(dev.mineagent.runtime.api.agent.AgentDefinition::displayName).toList());
        if(event.getRawText().stripLeading().startsWith("@")||!mentions.isEmpty()){
            event.setCanceled(true);
            var targets=agents.stream().filter(a->mentions.stream().anyMatch(m->m.name().equals(a.displayName()))).toList();
            if(mentions.size()!=1||targets.size()!=1){player.sendSystemMessage(net.minecraft.network.chat.Component.literal("[AI] 未找到唯一 AI；输入 @ 后按 Tab 选择名字。每条消息只联系一个 AI。"));return;}
            var target=targets.getFirst();String message=mentions.getFirst().message();
            if(message.isBlank()){player.sendSystemMessage(net.minecraft.network.chat.Component.literal("[AI] 已选择 "+target.displayName()+"；请在名字后输入消息。"));return;}
            if(message.matches("^(?:接管|控制身体)[ ：:].*")){
                try{dev.mineagent.runtime.neoforge.task.AutonomousPlayerAgent.submit(player,target.agentId(),java.util.UUID.randomUUID(),message.replaceFirst("^(?:接管|控制身体)[ ：:]+", ""));}
                catch(Exception failure){player.sendSystemMessage(net.minecraft.network.chat.Component.literal("[AI 接管] 请求未开始；请检查当前权限、是否已有计划或正在乘坐载具。"));}return;
            }
            if(message.matches("^(?:指令|命令)[ ：:].*")){
                try{dev.mineagent.runtime.neoforge.task.PlayerCommandAgent.submit(player,target.agentId(),message.replaceFirst("^(?:指令|命令)[ ：:]+", ""));}
                catch(Exception failure){player.sendSystemMessage(net.minecraft.network.chat.Component.literal("[AI 指令] 请求未开始："+java.util.Objects.toString(failure.getMessage(),"PLAYER_COMMAND_FAILED")));}return;
            }
            if(dev.mineagent.runtime.neoforge.network.MineAgentNetwork.submitChatDecision(player,target.agentId(),message))return;
            try{dev.mineagent.runtime.neoforge.ui.ServerConversations.get(server).submitNative(player,target.agentId(),message,true);}
            catch(Exception failure){player.sendSystemMessage(net.minecraft.network.chat.Component.literal("[AI] "+dev.mineagent.runtime.neoforge.ui.ServerConversations.nativeError(failure)));}return;
        }
        if (agents.isEmpty()) {
            return;
        }
        var focused=dev.mineagent.runtime.neoforge.ui.ServerConversations.get(server).focused(player).filter(dev.mineagent.runtime.core.conversation.ConversationFocusRegistry.Focus::nativeInput);
        java.util.UUID currentAgent=focused.map(dev.mineagent.runtime.core.conversation.ConversationFocusRegistry.Focus::agentId).orElse(null);
        var input = new dev.mineagent.runtime.api.interaction.InteractionInput(
                player.getUUID(), dev.mineagent.runtime.api.interaction.InteractionSource.CHAT,
                event.getRawText(), currentAgent);
        var resolution = new dev.mineagent.runtime.core.interaction.AddressResolver().resolve(input, agents);
        if(dev.mineagent.runtime.neoforge.network.MineAgentNetwork.submitChatDecision(player,resolution.agentId().orElse(null),event.getRawText())){event.setCanceled(true);return;}
        resolution.agentId().ifPresent(agentId->{event.setCanceled(true);dev.mineagent.runtime.neoforge.network.MineAgentNetwork.submitChat(player,agentId,java.util.UUID.randomUUID(),event.getRawText());});
    }

    @SubscribeEvent
    public void onPlayerLogin(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player)
                || player instanceof dev.mineagent.runtime.neoforge.body.MineAgentPlayer) {
            return;
        }
        if(!WorldIdentityRuntime.notifyIfPending(player))return;
        dev.mineagent.runtime.neoforge.network.MineAgentNetwork.sendPanelSnapshot(player);
        dev.mineagent.runtime.neoforge.network.MineAgentNetwork.sendMediaStateTo(player);
        dev.mineagent.runtime.neoforge.network.MineAgentNetwork.sendPackageStateTo(player);
        MineAgentRuntimeServices.mediaCoordinator(player.level().getServer()).synchronizeClient();
        if (Boolean.getBoolean("mineagent.multiplayerSmokeServer")) {
            verifyMultiplayerSmoke(player.level().getServer());
        }
        if (!player.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER)) {
            return;
        }
    }

    private void verifyMultiplayerSmoke(net.minecraft.server.MinecraftServer server) {
        var players = server.getPlayerList().getPlayers().stream()
                .filter(player -> !(player instanceof dev.mineagent.runtime.neoforge.body.MineAgentPlayer))
                .toList();
        if (players.size() < 2 || !multiplayerSmokeVerified.add(server)) {
            return;
        }
        var owner = players.get(0);
        var stranger = players.get(1);
        var definition = MineAgentRuntimeServices.bodies(server).createAt(
                "MultiplayerBot", owner.getUUID(), owner.level(), owner.position().add(2, 0, 2));
        if (Boolean.getBoolean("mineagent.productionYsmMultiplayerSmokeTest")) {
            multiplayerYsmAttempts.put(server,
                    new MultiplayerYsmAttempt(definition.agentId(), 1, server.getTickCount() + 100));
        }
        if (MineAgentRuntimeServices.bodies(server).rename(
                definition.agentId(), stranger.getUUID(), false, "StolenBot")) {
            throw new IllegalStateException("Second client bypassed AI ownership");
        }
        try {
            var task = MineAgentRuntimeServices.tasks(server).create(
                    definition.agentId(), owner.getUUID(), "多人权限测试", 10,
                    java.util.List.of(new dev.mineagent.runtime.core.task.TaskStepSpec("verify", java.util.Set.of())));
            var denied = MineAgentRuntimeServices.tasks(server).transition(
                    task.taskId(), task.revision(), false, dev.mineagent.runtime.api.task.TaskStatus.PAUSED);
            if (denied.accepted() || !"FORBIDDEN".equals(denied.errorCode())) {
                throw new IllegalStateException("Second client bypassed task ownership");
            }
        } catch (Exception failure) {
            throw new IllegalStateException("Multiplayer task verification failed", failure);
        }
        LOGGER.info("MINEAGENT_MULTIPLAYER_TWO_CLIENTS_OK players={} ownership=true taskIsolation=true",
                players.size());
        try {
            String source = "'client-ready';";
            String hash = dev.mineagent.runtime.core.packages.ContentPackageService.sha256(
                    source.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            String signature = java.util.Base64.getEncoder().encodeToString(
                    MineAgentRuntimeServices.identity(server).sign(
                            hash.getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
            var installed = MineAgentRuntimeServices.contentPackages(server).install(
                    new dev.mineagent.runtime.api.packages.ContentPackage(
                            java.util.UUID.randomUUID(), "多人客户端签名测试", "1.0.0",
                            dev.mineagent.runtime.api.packages.ActivationMode.HOT_RUNTIME,
                            java.util.Map.of(), java.util.Set.of("client.code"), source, hash, signature,
                            true, 1, 0));
            if (!installed.accepted()) {
                throw new IllegalStateException("client package install rejected: " + installed.errorCode());
            }
            dev.mineagent.runtime.neoforge.network.MineAgentNetwork.sendPackageStateTo(owner);
            dev.mineagent.runtime.neoforge.network.MineAgentNetwork.sendPackageStateTo(stranger);
        } catch (Exception failure) {
            throw new IllegalStateException("Signed client package smoke setup failed", failure);
        }
        try {
            var fixtureServer = com.sun.net.httpserver.HttpServer.create(
                    new java.net.InetSocketAddress("127.0.0.1", 0), 0);
            var image = new java.awt.image.BufferedImage(16, 16, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    image.setRGB(x, y, ((x + y) & 1) == 0 ? 0xFF33AAFF : 0xFFFFAA33);
                }
            }
            var encodedImage = new java.io.ByteArrayOutputStream();
            if (!javax.imageio.ImageIO.write(image, "PNG", encodedImage)) {
                throw new java.io.IOException("PNG encoder unavailable");
            }
            byte[] png = encodedImage.toByteArray();
            fixtureServer.createContext("/frame.png", exchange -> {
                exchange.getResponseHeaders().set("Content-Type", "image/png");
                exchange.sendResponseHeaders(200, png.length);
                exchange.getResponseBody().write(png);
                exchange.close();
            });
            fixtureServer.start();
            multiplayerMediaServers.put(server, fixtureServer);
            var position = owner.blockPosition().above().relative(owner.getDirection(), 3);
            owner.level().setBlockAndUpdate(position, MineAgentRegistries.MEDIA_SCREEN.get().defaultBlockState());
            var service = MineAgentRuntimeServices.media(server);
            var media = service.add(owner.getUUID(), dev.mineagent.runtime.api.media.MediaKind.IMAGE,
                    "多人媒体测试", "http://127.0.0.1:" + fixtureServer.getAddress().getPort() + "/frame.png");
            var bound = service.bind(media.mediaId(), media.revision(), true,
                    new dev.mineagent.runtime.api.media.MediaScreenBinding(
                            owner.level().dimension().identifier().toString(),
                            position.getX(), position.getY(), position.getZ()).encoded());
            var playing = service.updatePlayback(bound.entry().mediaId(), bound.entry().revision(),
                    true, true, 0, 1.0);
            if (!bound.accepted() || !playing.accepted()) {
                throw new IllegalStateException("Media smoke state rejected");
            }
            MineAgentRuntimeServices.mediaCoordinator(server).start(playing.entry());
            dev.mineagent.runtime.neoforge.network.MineAgentNetwork.sendMediaStateTo(owner);
            dev.mineagent.runtime.neoforge.network.MineAgentNetwork.sendMediaStateTo(stranger);
            LOGGER.info("MINEAGENT_MULTIPLAYER_MEDIA_STARTED mediaId={}", media.mediaId());
        } catch (Exception failure) {
            throw new IllegalStateException("Multiplayer media verification setup failed", failure);
        }
        dev.mineagent.runtime.neoforge.network.MineAgentNetwork.runVoiceSmoke(
                server, definition.agentId(), owner, true);
        dev.mineagent.runtime.neoforge.network.MineAgentNetwork.runVoiceSmoke(
                server, definition.agentId(), owner, false);
        multiplayerSmokeStops.put(server, server.getTickCount() + 1_200);
    }

    private boolean applyMultiplayerYsm(net.minecraft.server.MinecraftServer server, java.util.UUID agentId) {
        var bridge = new dev.mineagent.runtime.neoforge.integration.NeoForgeYsmRuntimeBridge(server);
        var result = new dev.mineagent.runtime.integrations.ysm.YsmAppearanceAdapter(bridge).apply(
                new dev.mineagent.runtime.api.appearance.AppearanceRequest(
                        agentId, "default", "blue", "idle", 1));
        if (result.status() != dev.mineagent.runtime.api.appearance.AppearanceStatus.READY) {
            LOGGER.info("MINEAGENT_MULTIPLAYER_YSM_WAIT status={} diagnostic={}",
                    result.status(), result.diagnosticCode());
            return false;
        }
        var config = MineAgentRuntimeServices.config(server);
        var current = config.snapshot();
        String prefix = "agent." + agentId + ".";
        var persisted = config.apply(new dev.mineagent.runtime.api.config.ConfigPatch(current.revision(), java.util.Map.of(
                prefix + "model", "default", prefix + "texture", "blue", prefix + "animation", "idle",
                prefix + "appearanceRevision", "1", prefix + "appearanceWorldId",
                MineAgentRuntimeServices.worldId(server).toString(), prefix + "appearanceProvider", "ysm",
                prefix + "appearanceProviderVersion", bridge.version(), prefix + "appearanceStatus", "READY",
                prefix + "appearanceDiagnostic", ""
        )), true);
        if (!persisted.accepted()) {
            throw new IllegalStateException("Could not persist multiplayer YSM smoke intent");
        }
        for (var player : server.getPlayerList().getPlayers()) {
            if (!(player instanceof dev.mineagent.runtime.neoforge.body.MineAgentPlayer)) {
                dev.mineagent.runtime.neoforge.network.MineAgentNetwork.sendPanelSnapshot(player);
            }
        }
        LOGGER.info("MINEAGENT_MULTIPLAYER_YSM_SERVER_APPLY_OK agent={} revision=1 observers={}", agentId,
                server.getPlayerList().getPlayers().stream()
                        .filter(player -> !(player instanceof dev.mineagent.runtime.neoforge.body.MineAgentPlayer)).count());
        return true;
    }

    private record MultiplayerYsmAttempt(java.util.UUID agentId, int attempt, int executeAtTick) {
    }

    private record RestartYsmAttempt(java.util.UUID agentId, int attempt, int executeAtTick) {
    }

    private static final class SmokeTestState {
        private final java.util.UUID agentId;
        private final dev.mineagent.runtime.neoforge.body.MineAgentPlayer originalBody;
        private final java.util.List<java.util.UUID> extraAgentIds;
        private final java.util.UUID navigationAgentId;
        private final net.minecraft.world.phys.Vec3 navigationTarget;
        private final int killAtTick;
        private final int verifyAtTick;
        private boolean deathTriggered;

        private SmokeTestState(
                java.util.UUID agentId,
                dev.mineagent.runtime.neoforge.body.MineAgentPlayer originalBody,
                java.util.List<java.util.UUID> extraAgentIds,
                java.util.UUID navigationAgentId,
                net.minecraft.world.phys.Vec3 navigationTarget,
                int killAtTick,
                int verifyAtTick
        ) {
            this.agentId = agentId;
            this.originalBody = originalBody;
            this.extraAgentIds = java.util.List.copyOf(extraAgentIds);
            this.navigationAgentId = navigationAgentId;
            this.navigationTarget = navigationTarget;
            this.killAtTick = killAtTick;
            this.verifyAtTick = verifyAtTick;
        }

        private int verifyAtTick() {
            return verifyAtTick;
        }
    }

    private static java.security.KeyPair createSecretTransportKeys() {
        try {
            return dev.mineagent.runtime.core.crypto.SecretChannel.generateServerKeyPair();
        } catch (java.security.GeneralSecurityException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }
}
