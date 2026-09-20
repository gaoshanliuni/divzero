package dev.mineagent.runtime.neoforge.scripting;

import dev.mineagent.runtime.api.recovery.BlockChange;
import dev.mineagent.runtime.api.recovery.BlockSnapshot;
import dev.mineagent.runtime.neoforge.MineAgentRuntimeServices;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class MineAgentScriptHost {
    private static final int MAX_MUTATIONS = 4_096;
    private static final int MAX_FILL_BLOCKS = 512;

    private final MinecraftServer server;
    private final UUID actorId;
    private final java.util.function.BooleanSupplier authority;
    private int mutations;

    public MineAgentScriptHost(MinecraftServer server,UUID actorId){this(server,actorId,()->true);}
    public MineAgentScriptHost(MinecraftServer server,UUID actorId,java.util.function.BooleanSupplier authority) {
        this.authority=java.util.Objects.requireNonNull(authority);
        this.server = java.util.Objects.requireNonNull(server, "server");
        this.actorId = java.util.Objects.requireNonNull(actorId, "actorId");
    }

    /** Same persisted DecisionService as the trusted WebGUI cards; the caller cannot choose another recipient. */
    public String requestDecision(String taskId, String specification, String blockedNodeIds) throws Exception {
        requireServerThread();
        if (specification == null || specification.length() > 24_000 || blockedNodeIds == null || blockedNodeIds.length() > 1024)
            throw new IllegalArgumentException("DECISION_SPEC_LIMIT");
        var tasks = dev.mineagent.runtime.neoforge.MineAgentRuntimeServices.tasks(server);
        var task = tasks.get(UUID.fromString(taskId)).orElseThrow();
        if (!task.ownerPlayerId().equals(actorId)) throw new SecurityException("DECISION_TASK_OWNER");
        var spec = new com.fasterxml.jackson.databind.ObjectMapper().readTree(specification);
        var options = new ArrayList<dev.mineagent.runtime.api.decision.DecisionOption>();
        for (var option : spec.path("options")) options.add(new dev.mineagent.runtime.api.decision.DecisionOption(
                option.path("optionId").asText(), option.path("title").asText(), option.path("description").asText())) ;
        if (options.size() > 64) throw new IllegalArgumentException("DECISION_OPTION_LIMIT");
        var mode = dev.mineagent.runtime.api.decision.SelectionMode.valueOf(spec.path("selectionMode").asText("SINGLE"));
        var kind = dev.mineagent.runtime.api.decision.DecisionKind.valueOf(spec.path("kind").asText("DESIGN"));
        var request = new dev.mineagent.runtime.api.decision.DecisionRequest(UUID.randomUUID(), 1, actorId, task.revision(), kind,
                spec.path("title").asText(), spec.path("question").asText(), options, mode,
                spec.path("minSelections").asInt(options.isEmpty() ? 0 : 1),
                spec.path("maxSelections").asInt(mode == dev.mineagent.runtime.api.decision.SelectionMode.SINGLE ? Math.min(1, options.size()) : options.size()),
                spec.path("allowCustomInput").asBoolean(kind != dev.mineagent.runtime.api.decision.DecisionKind.AUTHORIZATION),
                dev.mineagent.runtime.api.decision.DecisionStatus.OPEN);
        var nodes = java.util.Arrays.stream(blockedNodeIds.split(",")).map(String::strip).filter(s -> !s.isEmpty())
                .collect(java.util.stream.Collectors.toSet());
        return dev.mineagent.runtime.neoforge.MineAgentRuntimeServices.decisions(server)
                .openForTask(request, tasks, task.taskId(), nodes).decisionId().toString();
    }

    public boolean placeBlock(String dimension, int x, int y, int z, String blockId) throws Exception {
        requireServerThread();
        reserve(1);
        ServerLevel level = level(dimension);
        BlockPos position = position(level, x, y, z);
        Block block = block(blockId);
        BlockSnapshot before = snapshot(level, position);
        boolean changed = level.setBlockAndUpdate(position, block.defaultBlockState());
        if (changed) {
            journal("SCRIPT_PLACE", List.of(new BlockChange(before, snapshot(level, position))));
        }
        return changed;
    }

    public boolean breakBlock(String dimension, int x, int y, int z) throws Exception {
        requireServerThread();
        reserve(1);
        ServerLevel level = level(dimension);
        BlockPos position = position(level, x, y, z);
        if (level.getBlockState(position).isAir()) {
            return false;
        }
        BlockSnapshot before = snapshot(level, position);
        boolean changed = level.destroyBlock(position, true);
        if (changed) {
            journal("SCRIPT_BREAK", List.of(new BlockChange(before, snapshot(level, position))));
        }
        return changed;
    }

    public int fill(
            String dimension,
            int x1,
            int y1,
            int z1,
            int x2,
            int y2,
            int z2,
            String blockId
    ) throws Exception {
        requireServerThread();
        long volume = (Math.abs((long) x2 - x1) + 1) * (Math.abs((long) y2 - y1) + 1)
                * (Math.abs((long) z2 - z1) + 1);
        if (volume < 1 || volume > MAX_FILL_BLOCKS) {
            throw new IllegalArgumentException("fill volume must be 1–" + MAX_FILL_BLOCKS);
        }
        reserve((int) volume);
        ServerLevel level = level(dimension);
        Block block = block(blockId);
        var positions = BlockPos.betweenClosed(
                new BlockPos(Math.min(x1, x2), Math.min(y1, y2), Math.min(z1, z2)),
                new BlockPos(Math.max(x1, x2), Math.max(y1, y2), Math.max(z1, z2)));
        var before = new ArrayList<BlockSnapshot>((int) volume);
        for (BlockPos position : positions) {
            position(level, position.getX(), position.getY(), position.getZ());
            before.add(snapshot(level, position));
        }
        if (volume >= 128) {
            MineAgentRuntimeServices.snapshots(server).create(actorId,
                    "脚本大范围修改前快照", List.copyOf(before));
        }
        var changes = new ArrayList<BlockChange>((int) volume);
        int changed = 0;
        for (BlockSnapshot previous : before) {
            BlockPos position = new BlockPos(previous.x(), previous.y(), previous.z());
            if (level.setBlockAndUpdate(position, block.defaultBlockState())) {
                changes.add(new BlockChange(previous, snapshot(level, position)));
                changed++;
            }
        }
        if (!changes.isEmpty()) {
            journal("SCRIPT_FILL", changes);
        }
        return changed;
    }

    public boolean spawnEntity(String dimension, double x, double y, double z, String entityId) {
        requireServerThread();
        reserve(1);
        ServerLevel level = level(dimension);
        Identifier identifier = Identifier.tryParse(entityId);
        var type = identifier == null ? null : BuiltInRegistries.ENTITY_TYPE.getValue(identifier);
        if (type == null || !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            throw new IllegalArgumentException("invalid entity spawn");
        }
        var entity = type.create(level, EntitySpawnReason.COMMAND);
        if (entity == null) {
            return false;
        }
        entity.snapTo(x, y, z, 0, 0);
        return level.addFreshEntity(entity);
    }

    public boolean moveAgent(String agentId, double x, double y, double z) {
        requireServerThread();
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            throw new IllegalArgumentException("invalid agent target");
        }
        var body = MineAgentRuntimeServices.bodies(server).body(UUID.fromString(agentId)).orElse(null);
        if (body == null) {
            return false;
        }
        body.movementController().moveTo(new net.minecraft.world.phys.Vec3(x, y, z));
        return true;
    }

    public void broadcast(String message) {
        requireServerThread();
        if (message == null || message.isBlank() || message.length() > 1_024) {
            throw new IllegalArgumentException("invalid broadcast message");
        }
        server.getPlayerList().broadcastSystemMessage(Component.literal(message), false);
    }

    private ServerLevel level(String dimension) {
        Identifier id = Identifier.tryParse(dimension);
        if (id == null) {
            throw new IllegalArgumentException("invalid dimension");
        }
        ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, id));
        if (level == null) {
            throw new IllegalArgumentException("unknown dimension");
        }
        return level;
    }

    private static Block block(String id) {
        Identifier identifier = Identifier.tryParse(id);
        Block block = identifier == null ? null : BuiltInRegistries.BLOCK.getValue(identifier);
        if (block == null || block == net.minecraft.world.level.block.Blocks.AIR) {
            throw new IllegalArgumentException("invalid block");
        }
        return block;
    }

    private static BlockPos position(ServerLevel level, int x, int y, int z) {
        var position = new BlockPos(x, y, z);
        if (y < level.getMinY() || y > level.getMaxY() || !level.getWorldBorder().isWithinBounds(position)
                || !level.getChunkSource().hasChunk(
                        net.minecraft.core.SectionPos.blockToSectionCoord(x),
                        net.minecraft.core.SectionPos.blockToSectionCoord(z))) {
            throw new IllegalArgumentException("block position is outside loaded world bounds");
        }
        return position;
    }

    private static BlockSnapshot snapshot(ServerLevel level, BlockPos position) {
        var blockEntity = level.getBlockEntity(position);
        return new BlockSnapshot(level.dimension().identifier().toString(), position.getX(), position.getY(),
                position.getZ(), BlockStateParser.serialize(level.getBlockState(position)),
                blockEntity == null ? "" : blockEntity.saveWithFullMetadata(level.registryAccess()).toString());
    }

    private void journal(String action, List<BlockChange> changes) throws Exception {
        MineAgentRuntimeServices.changeJournal(server).record(actorId, action, changes);
    }

    private void reserve(int count) {
        if (count < 1 || mutations > MAX_MUTATIONS - count) {
            throw new IllegalStateException("script world mutation limit reached");
        }
        mutations += count;
    }

    private void requireServerThread() {
        if(!authority.getAsBoolean())throw new SecurityException("STUDIO_SCRIPT_AUTHORITY_CHANGED");
        if (!server.isSameThread()) {
            throw new IllegalStateException("world mutation must run on the server thread");
        }
    }
}
