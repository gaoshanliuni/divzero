package dev.mineagent.runtime.neoforge.body;

import com.mojang.authlib.GameProfile;
import dev.mineagent.runtime.api.agent.AgentDefinition;
import dev.mineagent.runtime.api.agent.AgentMode;
import dev.mineagent.runtime.core.agent.AgentRegistry;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import dev.mineagent.runtime.agent.chunk.ChunkTicketLedger;
import dev.mineagent.runtime.agent.chunk.ChunkCoordinate;
import dev.mineagent.runtime.agent.chunk.TicketDelta;
import dev.mineagent.runtime.core.config.RuntimeResourceLimits;
import dev.mineagent.runtime.core.config.ServerConfigService;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.inventory.RecipeBookMenu;
import dev.mineagent.runtime.neoforge.MineAgentRegistries;

public final class MineAgentBodyManager implements AutoCloseable {
    private final MinecraftServer server;
    private final ServerConfigService config;
    private final AgentRegistry registry;
    private final dev.mineagent.runtime.core.agent.PersistentAgentService persistentAgents;
    private final Map<UUID, MineAgentPlayer> bodies = new LinkedHashMap<>();
    private final Map<UUID, PendingRespawn> pendingRespawns = new LinkedHashMap<>();
    private final Map<UUID,PendingSpawn> pendingSpawns=new LinkedHashMap<>();
    private boolean stopping;
    private final ChunkTicketLedger ticketLedger;
    private final Map<String, ServerLevel> ticketLevels = new LinkedHashMap<>();
    private final java.util.Set<ChunkCoordinate> installedTickets = new java.util.LinkedHashSet<>();
    private Map<UUID, ChunkCoordinate> ticketCenters = Map.of();
    private RuntimeResourceLimits appliedLimits;
    private String ticketError = "";

    public MineAgentBodyManager(MinecraftServer server) {
        this.server = server;
        this.config = dev.mineagent.runtime.neoforge.MineAgentRuntimeServices.config(server);
        var initialLimits = config.resourceLimits();
        this.registry = new AgentRegistry(initialLimits.maxAgents());
        this.ticketLedger = new ChunkTicketLedger(initialLimits.agentTicketRadius(), initialLimits.maxChunkTickets());
        try {
            this.persistentAgents = dev.mineagent.runtime.core.agent.PersistentAgentService.open(
                    server.getServerDirectory().resolve("mineagent-runtime-data").resolve("runtime.db"),
                    dev.mineagent.runtime.neoforge.MineAgentRuntimeServices.worldId(server), config.resourceLimits().maxAgents());
            for (var stored : persistentAgents.all()) {
                registry.restore(stored.definition());
                prepareRestoredBody(stored.definition());
            }
            synchronizeCreationLimits();
        } catch (Exception failure) {
            throw new IllegalStateException("cannot restore MineAgent players", failure);
        }
    }

    public synchronized AgentDefinition create(String name, ServerPlayer owner) {
        return create(name, owner, AgentMode.CREATOR);
    }

    public synchronized AgentDefinition create(String name, ServerPlayer owner, AgentMode mode) {
        if(stopping)throw new IllegalStateException("BODY_SERVER_STOPPING");
        synchronizeCreationLimits();
        try {
            var stored = persistentAgents.create(name, owner.getUUID(), mode);
            registry.restore(stored.definition());
            spawnBody(stored.definition(), owner.level(), owner.position().add(2, 0, 2));
            return stored.definition();
        } catch (Exception failure) {
            throw failure instanceof RuntimeException runtime ? runtime
                    : new IllegalStateException("cannot persist AI player", failure);
        }
    }

    public synchronized AgentDefinition createAt(
            String name,
            UUID ownerPlayerId,
            ServerLevel level,
            Vec3 position
    ) {
        return createAt(name, ownerPlayerId, AgentMode.CREATOR, level, position);
    }

    public synchronized AgentDefinition createAt(
            String name,
            UUID ownerPlayerId,
            AgentMode mode,
            ServerLevel level,
            Vec3 position
    ) {
        if(stopping)throw new IllegalStateException("BODY_SERVER_STOPPING");
        synchronizeCreationLimits();
        AgentDefinition definition = registry.create(name, ownerPlayerId, mode);
        spawnBody(definition, level, position);
        return definition;
    }

    public synchronized AgentDefinition createPersistentAt(
            String name,
            UUID ownerPlayerId,
            ServerLevel level,
            Vec3 position
    ) {
        if(stopping)throw new IllegalStateException("BODY_SERVER_STOPPING");
        synchronizeCreationLimits();
        try {
            var stored = persistentAgents.create(name, ownerPlayerId, AgentMode.CREATOR);
            registry.restore(stored.definition());
            spawnBody(stored.definition(), level, position);
            return stored.definition();
        } catch (Exception failure) {
            throw failure instanceof RuntimeException runtime ? runtime
                    : new IllegalStateException("cannot persist AI player", failure);
        }
    }

    public synchronized List<AgentDefinition> definitions() {
        return registry.all();
    }

    public synchronized Optional<MineAgentPlayer> body(UUID agentId) {
        return Optional.ofNullable(bodies.get(agentId));
    }

    public synchronized long authorityGeneration(UUID agentId){return persistentAgents.get(agentId).map(dev.mineagent.runtime.core.agent.PersistentAgent::authorityGeneration).orElse(-1L);}

    public synchronized long revision(UUID agentId) {
        return persistentAgents.get(agentId).map(dev.mineagent.runtime.core.agent.PersistentAgent::revision).orElse(0L);
    }

    public synchronized void moveToOwner(UUID agentId) {
        MineAgentPlayer body = bodies.get(agentId);
        if (body == null || !body.canAct()) {
            return;
        }
        ServerPlayer owner = server.getPlayerList().getPlayer(body.ownerPlayerId());
        if (owner != null && owner.level() == body.level()) {
            body.movementController().moveTo(owner.position());
        }
    }

    public synchronized boolean rename(UUID agentId, UUID playerId, boolean operator, String displayName) {
        var persisted = persistentAgents.get(agentId);
        if (persisted.isPresent()) {
            try {
                var result = persistentAgents.rename(agentId, persisted.get().revision(), playerId, operator, displayName);
                if (!result.accepted()) {
                    return false;
                }
            } catch (Exception failure) {
                throw new IllegalStateException("cannot persist AI player rename", failure);
            }
        }
        if (!registry.rename(agentId, playerId, operator, displayName)) {
            return false;
        }
        MineAgentPlayer body = bodies.get(agentId);
        if (body != null) {
            body.setAgentDisplayName(displayName.strip());
        }
        return true;
    }

    public synchronized boolean setMode(UUID agentId, UUID playerId, boolean operator, AgentMode mode) {
        var persisted = persistentAgents.get(agentId);
        if (persisted.isPresent()) {
            try {
                var result = persistentAgents.setMode(agentId, persisted.get().revision(), playerId, operator, mode);
                if (!result.accepted()) {
                    return false;
                }
            } catch (Exception failure) {
                throw new IllegalStateException("cannot persist AI player mode", failure);
            }
        }
        if (!registry.setMode(agentId, playerId, operator, mode)) {
            return false;
        }
        MineAgentPlayer body = bodies.get(agentId);
        if (body != null) {
            body.gameMode.changeGameModeForPlayer(mode == AgentMode.CREATOR ? GameType.CREATIVE : GameType.SURVIVAL);
        }
        return true;
    }

    public synchronized boolean setCollaborator(
            UUID agentId,
            UUID ownerId,
            UUID collaboratorId,
            boolean enabled
    ) {
        var persisted = persistentAgents.get(agentId);
        if (persisted.isPresent()) {
            try {
                var result = persistentAgents.setCollaborator(
                        agentId, persisted.get().revision(), ownerId, collaboratorId, enabled);
                if (!result.accepted()) {
                    return false;
                }
            } catch (Exception failure) {
                throw new IllegalStateException("cannot persist AI collaborator", failure);
            }
        }
        boolean changed=registry.setCollaborator(agentId, ownerId, collaboratorId, enabled);
        if(changed&&!enabled)dev.mineagent.runtime.neoforge.MineAgentRuntimeServices.taskExecutor(server).authorityChanged(agentId);
        return changed;
    }

    public synchronized boolean remove(UUID agentId, UUID playerId, boolean operator) {
        var persisted = persistentAgents.get(agentId);
        if (persisted.isPresent()) {
            try {
                var result = persistentAgents.delete(agentId, persisted.get().revision(), playerId, operator);
                if (!result.accepted()) {
                    return false;
                }
            } catch (Exception failure) {
                throw new IllegalStateException("cannot delete persisted AI player", failure);
            }
        }
        if (!registry.remove(agentId, playerId, operator)) {
            return false;
        }
        dev.mineagent.runtime.neoforge.MineAgentRuntimeServices.taskExecutor(server).authorityChanged(agentId);
        pendingRespawns.remove(agentId);
        var preparing=pendingSpawns.remove(agentId);if(preparing!=null){preparing.preparation().close();preparing.connection().disconnect(Component.literal("AI player removed"));}
        applyTicketDelta(ticketLedger.remove(agentId));
        MineAgentPlayer body = bodies.remove(agentId);
        if (body != null) {
            server.getPlayerList().remove(body);
            body.discard();
            body.connection.getConnection().disconnect(Component.literal("AI player removed"));
        }
        return true;
    }

    public synchronized boolean beginMining(UUID agentId, BlockPos target) {
        MineAgentPlayer body = bodies.get(agentId);
        if (body == null || !body.canAct() || target == null) {
            return false;
        }
        var before = snapshot(body.level(), target);
        boolean accepted = body.beginMining(target);
        if (accepted && body.level().getBlockState(target).isAir()) {
            recordChanges(body, "BREAK_BLOCK", java.util.List.of(
                    new dev.mineagent.runtime.api.recovery.BlockChange(before, snapshot(body.level(), target))));
        }
        return accepted;
    }

    public synchronized boolean placeBlock(UUID agentId, BlockPos target, Block block) {
        MineAgentPlayer body = bodies.get(agentId);
        if (body == null || !body.canAct() || target == null || block == null
                || !body.isWithinBlockInteractionRange(target, 1.0)
                || !body.level().getBlockState(target).canBeReplaced()) {
            return false;
        }
        var before = snapshot(body.level(), target);
        ItemStack wanted = new ItemStack(block.asItem());
        if (wanted.isEmpty()) {
            return false;
        }
        boolean infinite=body.hasInfiniteMaterials();ItemStack previousHand=body.getMainHandItem().copy();
        try {
        if (infinite) {
            body.setItemInHand(InteractionHand.MAIN_HAND, wanted);
        } else {
            int slot = body.getInventory().findSlotMatchingItem(wanted);
            if (slot < 0) {
                return false;
            }
            if (slot < 9) {
                body.getInventory().setSelectedSlot(slot);
            } else {
                body.getInventory().pickSlot(slot);
            }
        }
        for (Direction face : Direction.values()) {
            BlockPos neighbor = target.relative(face.getOpposite());
            if (body.level().getBlockState(neighbor).isAir()) {
                continue;
            }
            var hit = new BlockHitResult(Vec3.atCenterOf(neighbor), face, neighbor, false);
            var result = body.gameMode.useItemOn(
                    body, body.level(), body.getMainHandItem(), InteractionHand.MAIN_HAND, hit);
            if (result.consumesAction()) {
                if(!body.level().getBlockState(target).is(block))return false;
                body.swing(InteractionHand.MAIN_HAND, true);
                recordChanges(body, "PLACE_BLOCK", java.util.List.of(
                        new dev.mineagent.runtime.api.recovery.BlockChange(before, snapshot(body.level(), target))));
                return true;
            }
        }
        return false;
        } finally {if(infinite)body.setItemInHand(InteractionHand.MAIN_HAND,previousHand);}
    }

    public synchronized boolean attack(UUID agentId, Entity target) {
        MineAgentPlayer body = bodies.get(agentId);
        if (body == null || !body.canAct() || target == null || !target.isAlive() || body.distanceToSqr(target) > 16.0) {
            return false;
        }
        body.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES, target.position());
        body.attack(target);
        body.swing(InteractionHand.MAIN_HAND, true);
        return true;
    }

    public synchronized boolean openContainer(UUID agentId, BlockPos position) {
        MineAgentPlayer body = bodies.get(agentId);
        if (body == null || !body.canAct() || position == null || !body.isWithinBlockInteractionRange(position, 1.0)) {
            return false;
        }
        var provider = body.level().getBlockState(position).getMenuProvider(body.level(), position);
        return provider != null && body.openMenu(provider).isPresent();
    }

    public synchronized boolean changeDimension(UUID agentId, ServerLevel destination, Vec3 position) {
        MineAgentPlayer body = bodies.get(agentId);
        if (body == null || !body.canAct() || destination == null || position == null
                || !body.teleportTo(destination, position.x, position.y, position.z,
                java.util.Set.of(), body.getYRot(), body.getXRot(), true)) {
            return false;
        }
        dev.mineagent.runtime.neoforge.integration.MineAgentAppearanceLifecycle.reapply(
                server, agentId, "dimension");
        return true;
    }

    public synchronized Optional<ItemStack> craft(UUID agentId, ResourceKey<Recipe<?>> recipeId) {
        MineAgentPlayer body = bodies.get(agentId);
        if (body == null || !body.canAct() || recipeId == null) {
            return Optional.empty();
        }
        var recipe = server.getRecipeManager().byKey(recipeId).orElse(null);
        if (recipe == null || !(recipe.value() instanceof net.minecraft.world.item.crafting.CraftingRecipe)) {
            return Optional.empty();
        }
        RecipeBookMenu menu = body.containerMenu instanceof RecipeBookMenu active
                ? active : body.inventoryMenu;
        var action = menu.handlePlacement(false, false, recipe, body.level(), body.getInventory());
        if (action == RecipeBookMenu.PostPlaceAction.PLACE_GHOST_RECIPE) {
            return Optional.empty();
        }
        ItemStack result = menu.getSlot(0).getItem().copy();
        if (result.isEmpty()) {
            return Optional.empty();
        }
        if(menu.quickMoveStack(body, 0).isEmpty())return Optional.empty();
        return Optional.of(result);
    }

    public synchronized int mineVein(UUID agentId, BlockPos start, int maximumBlocks) {
        MineAgentPlayer body = bodies.get(agentId);
        if (body == null || !body.canAct() || start == null || maximumBlocks < 1 || maximumBlocks > 128) {
            return 0;
        }
        ServerLevel level = body.level();
        var initial = level.getBlockState(start);
        if (initial.isAir() || !loaded(level, start)) {
            return 0;
        }
        var positions = new dev.mineagent.runtime.agent.navigation.BoundedVeinPlanner().find(
                new dev.mineagent.runtime.agent.navigation.GridPos(start.getX(), start.getY(), start.getZ()),
                maximumBlocks,
                grid -> {
                    BlockPos position = new BlockPos(grid.x(), grid.y(), grid.z());
                    return loaded(level, position) && level.getWorldBorder().isWithinBounds(position)
                            && level.getBlockState(position).is(initial.getBlock());
                });
        int mined = 0;
        var changes = new java.util.ArrayList<dev.mineagent.runtime.api.recovery.BlockChange>();
        for (var grid : positions) {
            BlockPos position = new BlockPos(grid.x(), grid.y(), grid.z());
            if (body.distanceToSqr(Vec3.atCenterOf(position)) <= 100.0) {
                var before = snapshot(level, position);
                if (!body.gameMode.destroyBlock(position)) {
                    continue;
                }
                changes.add(new dev.mineagent.runtime.api.recovery.BlockChange(
                        before, snapshot(level, position)));
                mined++;
            }
        }
        if (!changes.isEmpty()) {
            recordChanges(body, "VEIN_MINE", changes);
        }
        return mined;
    }

    private static dev.mineagent.runtime.api.recovery.BlockSnapshot snapshot(
            ServerLevel level,
            BlockPos position
    ) {
        var blockEntity = level.getBlockEntity(position);
        return new dev.mineagent.runtime.api.recovery.BlockSnapshot(
                level.dimension().identifier().toString(), position.getX(), position.getY(), position.getZ(),
                net.minecraft.commands.arguments.blocks.BlockStateParser.serialize(level.getBlockState(position)),
                blockEntity == null ? "" : blockEntity.saveWithFullMetadata(level.registryAccess()).toString());
    }

    private static boolean loaded(ServerLevel level, BlockPos position) {
        return level.getChunkSource().hasChunk(
                net.minecraft.core.SectionPos.blockToSectionCoord(position.getX()),
                net.minecraft.core.SectionPos.blockToSectionCoord(position.getZ()));
    }

    private void recordChanges(
            MineAgentPlayer body,
            String action,
            java.util.List<dev.mineagent.runtime.api.recovery.BlockChange> changes
    ) {
        try {
            dev.mineagent.runtime.neoforge.MineAgentRuntimeServices.changeJournal(server)
                    .record(body.ownerPlayerId(), action, changes);
        } catch (Exception failure) {
            throw new IllegalStateException("cannot persist AI world change", failure);
        }
    }

    public synchronized void tickAutonomy() {
        for (MineAgentPlayer body : bodies.values()) {
            if(!body.isAlive()||body.isRemoved()||body.deathAccepted()||body.isSpectator())continue;
            if(body.taskControlOwned())continue;
            ServerPlayer owner = server.getPlayerList().getPlayer(body.ownerPlayerId());
            if (owner != null && owner.level() == body.level() && body.distanceToSqr(owner) > 36.0) {
                body.movementController().moveTo(owner.position());
            }
        }
    }

    private void synchronizeCreationLimits() {
        int maximum = config.resourceLimits().maxAgents();
        registry.setMaximumAgents(maximum);
        // Explicit transient/native callers share the same slots as persisted AI.
        long transientCount = registry.all().stream().filter(a -> persistentAgents.get(a.agentId()).isEmpty()).count();
        persistentAgents.setMaximumAgents(Math.max(0, maximum - Math.toIntExact(transientCount)));
    }

    /** Server-thread consumer shared by WebGUI save, native config patches and body lifecycle ticks. */
    public synchronized void refreshResourceLimits() {
        if (!server.isSameThread()) throw new IllegalStateException("RESOURCE_LIMITS_SERVER_THREAD_REQUIRED");
        if (stopping) return;
        synchronizeCreationLimits();
        var limits = config.resourceLimits();
        var centers = new LinkedHashMap<UUID, ChunkCoordinate>();
        for (var body : bodies.values()) {
            if (!body.canAct()) continue;
            String dimension = body.level().dimension().identifier().toString();
            ticketLevels.put(dimension, body.level());
            var center = body.chunkPosition();
            centers.put(body.agentId(), new ChunkCoordinate(dimension, center.x(), center.z()));
        }
        if (!limits.equals(appliedLimits) || !centers.equals(ticketCenters)) {
            ticketLedger.reconcile(centers, limits.agentTicketRadius(), limits.maxChunkTickets());
            ticketCenters = Map.copyOf(centers);
            appliedLimits = limits;
        }
        synchronizeNativeTickets();
    }

    private void applyTicketDelta(TicketDelta delta) {
        if (delta.accepted()) synchronizeNativeTickets();
        ticketCenters = Map.of(); // Reconsider agents previously denied admission after a release.
    }

    private void synchronizeNativeTickets() {
        var desired = ticketLedger.coordinates();
        RuntimeException failure = null;
        // Release first. If any removal fails, do not add tickets above a newly reduced budget.
        for (var coordinate : java.util.Set.copyOf(installedTickets)) {
            if (desired.contains(coordinate)) continue;
            try {
                var level = java.util.Objects.requireNonNull(ticketLevels.get(coordinate.dimension()));
                level.getChunkSource().removeTicketWithRadius(
                        MineAgentRegistries.AGENT_TICKET.get(), new ChunkPos(coordinate.x(), coordinate.z()), 0);
                installedTickets.remove(coordinate);
            } catch (RuntimeException nativeFailure) { failure = nativeFailure; }
        }
        if (failure == null) for (var coordinate : desired) {
            if (installedTickets.contains(coordinate)) continue;
            try {
                var level = java.util.Objects.requireNonNull(ticketLevels.get(coordinate.dimension()));
                level.getChunkSource().addTicketWithRadius(
                        MineAgentRegistries.AGENT_TICKET.get(), new ChunkPos(coordinate.x(), coordinate.z()), 0);
                installedTickets.add(coordinate);
            } catch (RuntimeException nativeFailure) { failure = nativeFailure; }
        }
        if (failure != null && ticketError.isEmpty()) {
            com.mojang.logging.LogUtils.getLogger().warn("MineAgent managed chunk ticket update failed; resource status is degraded", failure);
        }
        // Retrying ticket-set reconciliation is idempotent, not replaying gameplay or a Task.
        ticketError = failure == null ? "" : "NATIVE_TICKET_UPDATE_FAILED";
    }

    public synchronized String ticketState(UUID agentId) {
        if (stopping) return "INACTIVE";
        if (!config.resourceLimits().equals(appliedLimits)) return "PENDING";
        if (!ticketError.isEmpty()) return "DEGRADED";
        if (appliedLimits.maxChunkTickets() == 0) return "DISABLED";
        if (ticketLedger.denied(agentId)) return "LIMIT_REACHED";
        return ticketLedger.chunksFor(agentId).isEmpty() ? "INACTIVE" : "GRANTED";
    }

    /** Public counts only: no locations, secrets, user inventory or native object references. */
    public synchronized Map<String, Object> resourceStatus() {
        var limits = config.resourceLimits();
        var result = new LinkedHashMap<String, Object>();
        result.put("maxAgents", limits.maxAgents()); result.put("agentCount", registry.all().size());
        result.put("maxChunkTickets", limits.maxChunkTickets()); result.put("agentTicketRadius", limits.agentTicketRadius());
        result.put("ticketsPerAgent", limits.ticketsPerAgent()); result.put("requestedTickets", ticketLedger.uniqueTicketCount());
        result.put("activeTickets", installedTickets.size()); result.put("limitedAgents", ticketLedger.deniedAgentCount());
        result.put("overAgentLimit", registry.all().size() > limits.maxAgents());
        result.put("state", stopping ? "STOPPING" : !ticketError.isEmpty() ? "DEGRADED"
                : !limits.equals(appliedLimits) ? "PENDING" : "APPLIED");
        result.put("errorCode", ticketError);
        return Map.copyOf(result);
    }

    public synchronized void tickBodies(int serverTick) {
        if(stopping)return;
        for(var pending:List.copyOf(pendingSpawns.values())){
            if(!pending.preparation().tick())continue;
            pendingSpawns.remove(pending.definition().agentId());
            try{
                var restored=pending.preparation().spawnPlayer(pending.connection(),CommonListenerCookie.createInitial(
                        new GameProfile(pending.definition().agentId(),pending.definition().profileName()),false));
                if(!(restored instanceof MineAgentPlayer body)||!body.agentId().equals(pending.definition().agentId()))throw new IllegalStateException("AI_RESTORED_BODY_TYPE");
                bindSpawnedBody(registry.get(pending.definition().agentId()).orElseThrow(),body);
            }finally{pending.preparation().close();}
        }
        for (MineAgentPlayer body : List.copyOf(bodies.values())) {
            body.ensureTicked(serverTick);
        }
        for (PendingRespawn pending : List.copyOf(pendingRespawns.values())) {
            if (serverTick >= pending.executeAtTick()) {
                MineAgentPlayer current = bodies.get(pending.definition().agentId());
                if (current == pending.deadBody()) {
                    // Unknown native outcomes must not be retried on the next tick.
                    pendingRespawns.remove(pending.definition().agentId());
                    var definition=registry.get(pending.definition().agentId()).orElseThrow();
                    current.connection.handleClientCommand(new net.minecraft.network.protocol.game.ServerboundClientCommandPacket(net.minecraft.network.protocol.game.ServerboundClientCommandPacket.Action.PERFORM_RESPAWN));
                    var replacement=current.connection.player;
                    if(!(replacement instanceof MineAgentPlayer next)||!next.agentId().equals(definition.agentId()))throw new IllegalStateException("AGENT_RESPAWN_TYPE");
                    if(next==current||server.getPlayerList().getPlayer(definition.agentId())!=next)throw new IllegalStateException("AGENT_RESPAWN_NOT_REPLACED");bodies.put(definition.agentId(),next);
                    next.setAgentDisplayName(definition.displayName());next.connection.markClientLoaded();
                    next.connection.resetPosition();next.level().getChunkSource().move(next);
                    dev.mineagent.runtime.neoforge.integration.MineAgentAppearanceLifecycle.reapply(server,definition.agentId(),"respawn");
                }
                pendingRespawns.remove(pending.definition().agentId());
            }
        }
        refreshResourceLimits();
    }

    private MineAgentPlayer spawnBody(AgentDefinition definition, ServerLevel level, Vec3 position) {
        var profile = new GameProfile(definition.agentId(), definition.profileName());
        var body = new MineAgentPlayer(
                server, level, profile, definition.agentId(), definition.ownerPlayerId(),
                definition.displayName(), this::scheduleRespawn
        );
        body.snapTo(position.x, position.y, position.z, 0, 0);
        var connection = new MineAgentConnection();
        server.getPlayerList().placeNewPlayer(
                connection,
                body,
                CommonListenerCookie.createInitial(profile, false)
        );
        bindSpawnedBody(definition,body);return body;
    }
    public synchronized AgentDefinition createIdempotent(UUID operation,String name,ServerPlayer owner,AgentMode mode)throws Exception{
        if(stopping)throw new IllegalStateException("BODY_SERVER_STOPPING");
        synchronizeCreationLimits();
        var stored=persistentAgents.createIdempotent(operation,name,owner.getUUID(),mode);
        if(registry.get(stored.definition().agentId()).isEmpty()){
            registry.restore(stored.definition());spawnBody(stored.definition(),owner.level(),owner.position().add(2,0,2));
        }
        return registry.get(stored.definition().agentId()).orElseThrow();
    }
    public synchronized String bodyState(UUID agentId){
        if(stopping)return "STOPPING";
        if(pendingSpawns.containsKey(agentId))return "RESTORING";
        var body=bodies.get(agentId);if(body==null)return "UNAVAILABLE";
        if(body.deathAccepted()||body.endReturnAccepted()||!body.isAlive())return "RETURNING";
        if(body.isSpectator())return "SPECTATOR";
        if(!body.canAct())return "UNAVAILABLE";
        return body.taskControlOwned()?"BUSY":"READY";
    }

    private void prepareRestoredBody(AgentDefinition definition){
        var profile=new GameProfile(definition.agentId(),definition.profileName());
        var preparation=new net.minecraft.server.network.config.PrepareSpawnTask(server,new net.minecraft.server.players.NameAndId(profile));
        var connection=new MineAgentConnection((nativeServer,level,loadedProfile,info)->{
            if(nativeServer!=server||!loadedProfile.id().equals(definition.agentId()))throw new IllegalArgumentException("AI_RESTORE_IDENTITY");
            return new MineAgentPlayer(server,level,loadedProfile,definition.agentId(),definition.ownerPlayerId(),definition.displayName(),this::scheduleRespawn,info);
        });
        pendingSpawns.put(definition.agentId(),new PendingSpawn(definition,preparation,connection));
        preparation.start(packet->{}); // Native preparation has no custom-channel handshake.
    }
    private record PendingSpawn(AgentDefinition definition,net.minecraft.server.network.config.PrepareSpawnTask preparation,MineAgentConnection connection){}

    private void bindSpawnedBody(AgentDefinition definition,MineAgentPlayer body){
        // The native hardcore respawn result is persisted in player NBT. Definition mode is a
        // requested Creator/Survival preference, not permission to undo death on every restart.
        if(!dev.mineagent.runtime.core.agent.AgentBodyLifecycle.preserveHardcoreSpectator(
                body.loadedNativeState(),server.isHardcore(),body.isSpectator())){
            body.gameMode.changeGameModeForPlayer(definition.mode()==AgentMode.CREATOR?GameType.CREATIVE:GameType.SURVIVAL);
        }
        body.connection.markClientLoaded();
        body.setAgentDisplayName(definition.displayName());
        body.setCustomNameVisible(true);
        bodies.put(definition.agentId(), body);
        dev.mineagent.runtime.neoforge.integration.MineAgentAppearanceLifecycle.reapply(
                server, definition.agentId(), pendingRespawns.containsKey(definition.agentId()) ? "respawn" : "spawn");
        body.restoreDeadLogin();
        refreshResourceLimits();
    }

    private synchronized void scheduleRespawn(MineAgentPlayer deadBody) {
        AgentDefinition definition = registry.get(deadBody.agentId()).orElse(null);
        if (definition == null || bodies.get(deadBody.agentId())!=deadBody || pendingRespawns.containsKey(deadBody.agentId())
                ||!deadBody.deathAccepted()&&!deadBody.endReturnAccepted()) {
            return;
        }
        pendingRespawns.put(definition.agentId(), new PendingRespawn(
                definition,
                deadBody,
                server.getTickCount() + (deadBody.endReturnAccepted()?1:20)
        ));
    }

    private record PendingRespawn(
            AgentDefinition definition,
            MineAgentPlayer deadBody,
            int executeAtTick
    ) {
    }

    @Override
    public synchronized void close() {
        for(var pending:pendingSpawns.values()){pending.preparation().close();pending.connection().disconnect(Component.literal("Server closed"));}pendingSpawns.clear();
        // ServerStopping owns native cleanup. Never touch closed levels during ServerStopped.
        if (!stopping) { ticketLedger.reconcile(Map.of(), 0, 0); synchronizeNativeTickets(); }
        installedTickets.clear();
        ticketLevels.clear();
        try {
            persistentAgents.close();
        } catch (Exception failure) {
            throw new IllegalStateException("cannot close AI player store", failure);
        }
    }

    public synchronized void prepareForServerStop(){
        if(stopping)return;stopping=true;pendingRespawns.clear();
        for(var pending:pendingSpawns.values()){pending.preparation().close();pending.connection().disconnect(Component.literal("Server stopping"));}pendingSpawns.clear();
        for(var body:List.copyOf(bodies.values())){
            body.prepareForServerStop();
            // PlayerList.remove saves the already-flushed native inventory/health/return marker.
            if(server.getPlayerList().getPlayer(body.agentId())==body)server.getPlayerList().remove(body);
            body.connection.getConnection().disconnect(Component.literal("Server stopping"));
            applyTicketDelta(ticketLedger.remove(body.agentId()));
        }
        ticketLedger.reconcile(Map.of(), 0, 0);synchronizeNativeTickets();
        bodies.clear();ticketLevels.clear();
    }
}
