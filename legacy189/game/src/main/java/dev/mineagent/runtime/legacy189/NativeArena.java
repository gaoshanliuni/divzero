package dev.mineagent.runtime.legacy189;

import com.google.gson.*;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.*;
import net.minecraft.world.WorldServer;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Import runs on the server thread in bounded batches, with a complete preflight
 * and a separate native readback. Disk I/O and JSON validation run off-thread.
 */
public final class NativeArena {
    public static final int VOLUME = 33670;
    private static final Gson JSON = new GsonBuilder().disableHtmlEscaping().create();
    private static Import active;
    private static String phase = "IDLE", error = "";
    private static int changed, verified;
    private NativeArena() { }
    public static boolean ready() { return NativeRuntime.data().arenaReady(); }
    public static String phase() { return phase; }
    public static String error() { return error; }
    public static int verified() { return verified; }
    public static boolean field(BlockPos pos) { return pos.getX() >= -16 && pos.getX() <= 16 && pos.getZ() >= 784 && pos.getZ() <= 816 && pos.getY() >= 101 && pos.getY() < 256; }
    public static boolean protectedArea(BlockPos pos) { return pos.getX() >= -18 && pos.getX() <= 18 && pos.getZ() >= 754 && pos.getZ() <= 818 && pos.getY() >= 97; }
    /** Walking bounds include the rim. Editing bounds remain the smaller field(). */
    public static boolean containsFighter(net.minecraft.entity.Entity entity){return entity!=null&&entity.posX>=-17.7&&entity.posX<=18.7&&entity.posZ>=783.3&&entity.posZ<=818.7&&entity.posY>=97;}
    public static boolean walkCell(int x,int z){return x>=-18&&x<=18&&z>=783&&z<=818;}
    public static void start(EntityPlayerMP owner) {
        if (!owner.canCommandSenderUseCommand(2, "ai") || owner.dimension != 0) throw new SecurityException("导入竞技场需要主世界管理权限");
        if (active != null) throw new IllegalStateException("竞技场导入正在进行");
        if (ready()) throw new IllegalStateException("此世界已有竞技场；不能覆盖已启用的地图");
        File file = MinecraftServer.getServer().getFile("divzero-import/pvp-arena-transfer.json");
        active = new Import(owner, file); phase = "READING"; error = ""; changed = verified = 0;
    }
    public static void tick() {
        if (active == null) return;
        Import job = active;
        try {
            if (MinecraftServer.getServer().getConfigurationManager().getPlayerByUUID(job.owner.getUniqueID()) != job.owner
                    || job.owner.worldObj != job.world || !job.owner.canCommandSenderUseCommand(2, "ai")
                    || !job.session.equals(NativeRuntime.session(job.owner))) throw new IllegalStateException("ARENA_IMPORT_CONTEXT_CHANGED");
            if (job.states == null) {
                if (!job.source.isDone()) return;
                JsonObject source = job.source.join();
                job.hash = source.get("contentSha256").getAsString();
                JsonArray palette = source.getAsJsonArray("palette");
                IBlockState[] resolved = new IBlockState[palette.size()];
                for (int i = 0; i < resolved.length; i++) resolved[i] = LegacyBlocks.resolve(palette.get(i).getAsJsonObject());
                job.states = new IBlockState[VOLUME]; int at = 0;
                for (JsonElement element : source.getAsJsonArray("runs")) {
                    JsonArray run = element.getAsJsonArray(); int index = run.get(0).getAsInt(), count = run.get(1).getAsInt();
                    for (int i = 0; i < count; i++) job.states[at++] = resolved[index];
                }
                // Tile entities require an explicit content migration, never a destructive shortcut.
                for (int x = -2; x <= 1; x++) for (int z = 47; z <= 51; z++) job.world.getChunkFromChunkCoords(x, z);
                phase = "PREFLIGHT";
            }
            for (int budget = 0; budget < 2048 && active != null; budget++) {
                if (job.cursor == VOLUME) {
                    job.cursor = 0;
                    if (phase.equals("PREFLIGHT")) phase = "WRITING";
                    else if (phase.equals("WRITING")) phase = "VERIFYING";
                    else { finish(job); break; }
                }
                BlockPos pos = position(job.cursor); IBlockState expected = job.states[job.cursor++];
                if (phase.equals("PREFLIGHT")) {
                    if (job.world.getTileEntity(pos) != null) throw new IllegalStateException("ARENA_EXISTING_BLOCK_ENTITY");
                } else if (phase.equals("WRITING")) {
                    if (!job.world.getBlockState(pos).equals(expected)) {
                        if (!job.world.setBlockState(pos, expected, 2)) throw new IllegalStateException("ARENA_BLOCK_WRITE_FAILED " + pos);
                        changed++;
                    }
                } else {
                    if (!job.world.getBlockState(pos).equals(expected)) throw new IllegalStateException("ARENA_READBACK_MISMATCH " + pos);
                    verified++;
                }
            }
        } catch (Exception failure) {
            error = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
            phase = changed == 0 ? "REJECTED" : "PARTIAL"; active = null;
            LegacyMod.logger.error("Arena import stopped; changed={}, verified={}", changed, verified, failure);
            job.owner.addChatMessage(new ChatComponentText("[DivZero] 地图导入 " + phase + "：" + error));
        }
    }
    private static void finish(Import job) {
        for (BlockPos feet : Arrays.asList(new BlockPos(0, 101, 766), new BlockPos(-5, 101, 800), new BlockPos(5, 101, 800))) {
            if (!job.world.getBlockState(feet.down()).getBlock().isFullCube() || !job.world.isAirBlock(feet) || !job.world.isAirBlock(feet.up()))
                throw new IllegalStateException("ARENA_NATIVE_SPAWN_COLLISION");
        }
        NativeRuntime.data().arena(job.hash);
        NativeRuntime.data().modernCombat(true);
        for (EntityPlayerMP player : MinecraftServer.getServer().getConfigurationManager().getPlayerList()) NativeNetwork.sync(player);
        job.world.setSpawnPoint(new BlockPos(0, 101, 766));
        job.world.getGameRules().setOrCreateGameRule("spawnRadius", "0");
        job.owner.setSpawnPoint(new BlockPos(0, 101, 766), true);
        phase = "VERIFIED"; active = null;
        lobby(job.owner);
        job.owner.addChatMessage(new ChatComponentText("[DivZero] 竞技场已逐格核对：" + verified + "。使用 /ai duel equip 设置对局。"));
    }
    public static void lobby(EntityPlayerMP player) {
        if (!player.isEntityAlive()) return;
        player.playerNetServerHandler.setPlayerLocation(.5, 101, 766.5, 0, 0);
        player.motionX = player.motionY = player.motionZ = 0; player.fallDistance = 0;
    }
    public static BlockPos position(int index) { return new BlockPos(-18 + index % 37, 97 + index / (37 * 65), 754 + index / 37 % 65); }
    public static void stop() { if (active != null) active.source.cancel(true); active = null; phase = "IDLE"; error = ""; }
    private static JsonObject read(File file) {
        try {
            if (!file.isFile() || file.length() > 4 * 1024 * 1024) throw new IOException("ARENA_TRANSFER_MISSING_OR_TOO_LARGE");
            JsonObject source = new JsonParser().parse(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8)).getAsJsonObject();
            if (source.get("schema").getAsInt() != 1 || !source.get("kind").getAsString().equals("divzero-arena-transfer")
                    || source.get("sourceDataVersion").getAsInt() != 4790 || source.get("blocks").getAsInt() != VOLUME
                    || !source.get("order").getAsString().equals("YZX_X_FASTEST")
                    || !source.get("minimum").toString().equals("[-18,97,754]") || !source.get("maximum").toString().equals("[18,110,818]"))
                throw new IOException("ARENA_TRANSFER_SCHEMA");
            String expected = source.remove("contentSha256").getAsString();
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical(source).getBytes(StandardCharsets.UTF_8));
            StringBuilder hash = new StringBuilder(); for (byte b : digest) hash.append(String.format(Locale.ROOT, "%02x", b & 255));
            if (!hash.toString().equals(expected)) throw new IOException("ARENA_TRANSFER_HASH");
            source.addProperty("contentSha256", expected);
            int paletteSize = source.getAsJsonArray("palette").size(), total = 0;
            if (paletteSize < 1 || paletteSize > 4096) throw new IOException("ARENA_PALETTE_SIZE");
            for (JsonElement element : source.getAsJsonArray("runs")) {
                JsonArray run = element.getAsJsonArray();
                if (run.size() != 2) throw new IOException("ARENA_RUN");
                int index = run.get(0).getAsInt(), count = run.get(1).getAsInt();
                if (index < 0 || index >= paletteSize || count <= 0 || count > VOLUME - total) throw new IOException("ARENA_RUN_BOUNDS");
                total += count;
            }
            if (total != VOLUME) throw new IOException("ARENA_VOLUME");
            return source;
        } catch (Exception error) { throw new IllegalStateException("ARENA_TRANSFER_REJECTED", error); }
    }
    private static String canonical(JsonElement value) {
        if (value.isJsonObject()) {
            SortedMap<String, JsonElement> sorted = new TreeMap<String, JsonElement>();
            for (Map.Entry<String, JsonElement> entry : value.getAsJsonObject().entrySet()) sorted.put(entry.getKey(), entry.getValue());
            StringBuilder out = new StringBuilder("{");
            for (Map.Entry<String, JsonElement> entry : sorted.entrySet()) { if (out.length() > 1) out.append(','); out.append(JSON.toJson(entry.getKey())).append(':').append(canonical(entry.getValue())); }
            return out.append('}').toString();
        }
        if (value.isJsonArray()) { StringBuilder out = new StringBuilder("["); for (JsonElement item : value.getAsJsonArray()) { if (out.length() > 1) out.append(','); out.append(canonical(item)); } return out.append(']').toString(); }
        return JSON.toJson(value);
    }
    private static final class Import {
        final EntityPlayerMP owner; final WorldServer world; final UUID session; final CompletableFuture<JsonObject> source;
        IBlockState[] states; int cursor; String hash;
        Import(EntityPlayerMP owner, File file) { this.owner = owner; world = owner.getServerForPlayer(); session = NativeRuntime.session(owner); source = CompletableFuture.supplyAsync(() -> read(file)); }
    }
}
