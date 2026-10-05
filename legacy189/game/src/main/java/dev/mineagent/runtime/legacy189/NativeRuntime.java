package dev.mineagent.runtime.legacy189;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import java.util.*;

public final class NativeRuntime {
    private static final Map<UUID, NativeAgent> BODIES = new LinkedHashMap<UUID, NativeAgent>();
    private static final Map<UUID, UUID> SESSIONS = new HashMap<UUID, UUID>();
    private static boolean restored;
    private NativeRuntime() { }
    public static NativeWorldData data() { return NativeWorldData.get(MinecraftServer.getServer().worldServerForDimension(0)); }
    public static UUID session(EntityPlayerMP player) {
        UUID id = SESSIONS.get(player.getUniqueID());
        if (id == null) { id = UUID.randomUUID(); SESSIONS.put(player.getUniqueID(), id); }
        return id;
    }
    public static boolean enabled(EntityPlayerMP player) { return data().enabled(player.getUniqueID()); }
    public static void setEnabled(EntityPlayerMP player, boolean enabled) {
        if (data().enabled(player.getUniqueID()) != enabled) {
            data().enabled(player.getUniqueID(), enabled);
            SESSIONS.put(player.getUniqueID(), UUID.randomUUID());
            NativeService.stop(player.getUniqueID());
            if (!enabled) for (NativeAgent body : BODIES.values()) if (body.owner.equals(player.getUniqueID())) body.stopActions();
        }
        NativeNetwork.sync(player);
    }
    public static void requireEnabled(EntityPlayerMP player) {
        if (!enabled(player)) throw new IllegalStateException("请先输入 /ai enable 启用当前世界");
    }
    public static NativeAgent create(EntityPlayerMP owner, String name) {
        requireEnabled(owner);
        NativeWorldData.AgentDefinition value = new NativeWorldData.AgentDefinition(UUID.randomUUID(), owner.getUniqueID(), name,
                owner.dimension, owner.posX + 2, owner.posY, owner.posZ);
        NativeAgent body = spawn(value, false);
        data().put(value);
        return body;
    }
    public static NativeAgent createTransient(EntityPlayerMP owner, String name) {
        requireEnabled(owner);
        return spawn(new NativeWorldData.AgentDefinition(UUID.randomUUID(), owner.getUniqueID(), name, owner.dimension, owner.posX + 2, owner.posY, owner.posZ), false);
    }
    private static NativeAgent spawn(NativeWorldData.AgentDefinition value, boolean restore) {
        MinecraftServer server = MinecraftServer.getServer();
        WorldServer world = server.worldServerForDimension(value.dimension);
        if (world == null) throw new IllegalStateException("LEGACY_AGENT_DIMENSION_UNAVAILABLE");
        NativeAgent body = new NativeAgent(server, world, value);
        if (restore) server.getConfigurationManager().readPlayerDataFromFile(body);
        else body.setGameType(net.minecraft.world.WorldSettings.GameType.SURVIVAL);
        // The normal registration path owns entity tracking, player directory and saving.
        server.getConfigurationManager().playerLoggedIn(body);
        body.playerNetServerHandler.setPlayerLocation(body.posX, body.posY, body.posZ, body.rotationYaw, body.rotationPitch);
        BODIES.put(value.id, body);
        return body;
    }
    public static Collection<NativeAgent> bodies() { return Collections.unmodifiableCollection(BODIES.values()); }
    public static NativeAgent owned(EntityPlayerMP owner, String reference) {
        NativeAgent match = null;
        for (NativeAgent body : BODIES.values()) {
            if (body.getUniqueID().toString().equals(reference) || body.displayName.equals(reference)) {
                if (!body.owner.equals(owner.getUniqueID())) throw new SecurityException("只能管理自己创建的 AI");
                if (match != null) throw new IllegalArgumentException("AI 名称重复，请用准确 UUID");
                match = body;
            }
        }
        if (match == null) throw new IllegalArgumentException("未找到 AI");
        return match;
    }
    public static void delete(EntityPlayerMP owner, String reference) {
        requireEnabled(owner); NativeAgent body = owned(owner, reference);
        data().remove(body.getUniqueID()); removeBody(body);
    }
    public static void removeBody(NativeAgent body) {
        if (BODIES.remove(body.getUniqueID()) == null) return;
        body.stopActions();
        try { MinecraftServer.getServer().getConfigurationManager().playerLoggedOut(body); }
        finally { body.closeConnection(); }
    }
    public static void stop() {
        NativeService.stopAll();
        NativeDuel.stop();
        NativeArena.stop();
        ModernCombat.stop();
        for (NativeAgent body : BODIES.values()) { body.stopActions(); body.closeConnection(); }
        BODIES.clear(); SESSIONS.clear(); restored = false;
    }
    public static class Events {
        @SubscribeEvent public void login(PlayerEvent.PlayerLoggedInEvent event) {
            if (!(event.player instanceof EntityPlayerMP) || event.player instanceof NativeAgent) return;
            EntityPlayerMP player = (EntityPlayerMP) event.player;
            SESSIONS.put(player.getUniqueID(), UUID.randomUUID()); NativeNetwork.sync(player);
            if (NativeDuel.allowed(player)) { player.setSpawnPoint(new net.minecraft.util.BlockPos(0, 101, 766), true); NativeArena.lobby(player); }
        }
        @SubscribeEvent public void logout(PlayerEvent.PlayerLoggedOutEvent event) {
            NativeService.stop(event.player.getUniqueID());
            SESSIONS.remove(event.player.getUniqueID());
            for (NativeAgent body : BODIES.values()) if (body.owner.equals(event.player.getUniqueID())) body.stopActions();
        }
        @SubscribeEvent public void dimension(PlayerEvent.PlayerChangedDimensionEvent event) {
            if (event.player instanceof EntityPlayerMP && !(event.player instanceof NativeAgent)) {
                EntityPlayerMP player = (EntityPlayerMP) event.player;
                NativeService.stop(player.getUniqueID());
                SESSIONS.put(player.getUniqueID(), UUID.randomUUID()); NativeNetwork.sync(player);
            }
        }
        @SubscribeEvent public void respawn(PlayerEvent.PlayerRespawnEvent event) {
            if (event.player instanceof EntityPlayerMP && !(event.player instanceof NativeAgent)) {
                EntityPlayerMP player = (EntityPlayerMP) event.player;
                NativeService.stop(player.getUniqueID());
                SESSIONS.put(player.getUniqueID(), UUID.randomUUID()); NativeNetwork.sync(player);
                NativeDuel.respawn(player);
            }
        }
        @SubscribeEvent public void tick(TickEvent.ServerTickEvent event) {
            if (event.phase != TickEvent.Phase.END) return;
            MinecraftServer server = MinecraftServer.getServer();
            if (server == null || server.worldServers == null) return;
            if (!restored) {
                restored = true;
                for (NativeWorldData.AgentDefinition definition : new ArrayList<NativeWorldData.AgentDefinition>(data().agents())) {
                    try { spawn(definition, true); } catch (RuntimeException failure) { LegacyMod.logger.error("Agent restore failed: {}", definition.id, failure); }
                }
            }
            ModernCombat.tick();
            NativeDuel.tick();
            for (NativeAgent body : new ArrayList<NativeAgent>(BODIES.values())) {
                body.physics(server.getTickCounter());
                NativeWorldData.AgentDefinition definition = data().agent(body.getUniqueID());
                if (definition != null && server.getTickCounter() % 20 == 0) {
                    definition.dimension = body.dimension; definition.x = body.posX; definition.y = body.posY; definition.z = body.posZ; data().markDirty();
                }
            }
            NativeFixture.tick(server);
            NativeArena.tick();
        }
    }
}
