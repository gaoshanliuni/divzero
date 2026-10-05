package dev.mineagent.runtime.legacy189;

import com.google.gson.*;
import dev.mineagent.runtime.legacy189.bridge.*;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/** Nonblocking process bridge. Only private pipes carry provider configuration.
 * The service is versioned with this Mod; AI text cannot select the executable.
 */
public final class NativeService implements AutoCloseable {
    private static final Map<UUID, CompletableFuture<NativeService>> SERVICES = new HashMap<UUID, CompletableFuture<NativeService>>();
    private static final Gson JSON = new GsonBuilder().disableHtmlEscaping().create();
    private final UUID world, owner, session;
    private final Process process;
    private final FrameCodec.Writer writer;
    private final ExecutorService writes;
    private final ConcurrentMap<UUID, CompletableFuture<JsonObject>> requests = new ConcurrentHashMap<UUID, CompletableFuture<JsonObject>>();
    private volatile boolean closed;

    private NativeService(UUID world, UUID owner, UUID session, Path directory) throws Exception {
        this.world = world; this.owner = owner; this.session = session;
        File java = java25();
        Path jar = extract(directory);
        Files.createDirectories(directory.resolve(world.toString()).resolve(owner.toString()));
        process = new ProcessBuilder(java.getCanonicalPath(), "--enable-native-access=ALL-UNNAMED", "-Dfile.encoding=UTF-8", "-jar", jar.toString(),
                world.toString(), owner.toString(), session.toString(), directory.toString())
                .directory(directory.toFile()).redirectError(directory.resolve(world.toString()).resolve(owner.toString()).resolve("service-stderr.log").toFile()).start();
        writer = new FrameCodec.Writer(process.getOutputStream());
        writes = Executors.newSingleThreadExecutor(new ThreadFactory() {
            @Override public Thread newThread(Runnable work) { Thread thread = new Thread(work, "DivZero189-service-write"); thread.setDaemon(true); return thread; }
        });
        Thread reader = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    FrameCodec.Reader frames = new FrameCodec.Reader(process.getInputStream());
                    BridgeFrame frame;
                    while ((frame = frames.read()) != null) {
                        frame.requireScope(NativeService.this.world, NativeService.this.owner, NativeService.this.session);
                        JsonObject value = new JsonParser().parse(new String(frame.payload(), StandardCharsets.UTF_8)).getAsJsonObject();
                        if (frame.kind() == BridgeFrame.Kind.RESULT) {
                            CompletableFuture<JsonObject> pending = requests.remove(frame.operation());
                            if (pending != null) pending.complete(value);
                        }
                        // Streaming EVENT delivery is added with the conversation client; do not
                        // incorrectly treat a partial event as a completed request here.
                    }
                } catch (Exception failure) { LegacyMod.logger.warn("Legacy service pipe stopped: {}", failure.getClass().getSimpleName()); }
                finally { failPending(); }
            }
        }, "DivZero189-service-read");
        reader.setDaemon(true); reader.start();
    }
    public static synchronized CompletableFuture<NativeService> get(final EntityPlayerMP player) {
        NativeRuntime.requireEnabled(player);
        CompletableFuture<NativeService> existing = SERVICES.get(player.getUniqueID());
        if (existing != null) return existing;
        final UUID world = NativeRuntime.data().identity(), owner = player.getUniqueID(), session = NativeRuntime.session(player);
        final Path directory = MinecraftServer.getServer().getFile("mineagent-legacy-services").toPath().toAbsolutePath().normalize();
        final CompletableFuture<NativeService> future = new CompletableFuture<NativeService>();
        SERVICES.put(owner, future);
        Thread startup = new Thread(new Runnable() {
            @Override public void run() {
                NativeService service = null;
                try {
                    service = new NativeService(world, owner, session, directory);
                    JsonObject result = service.send(BridgeFrame.Kind.HELLO, new JsonObject()).get(30, TimeUnit.SECONDS);
                    if (!"service.ready".equals(result.get("type").getAsString())) throw new IOException("LEGACY_SERVICE_NOT_READY");
                    if (!future.complete(service)) service.close();
                } catch (Exception failure) { if (service != null) service.close(); future.completeExceptionally(failure); }
            }
        }, "DivZero189-service-start");
        startup.setDaemon(true); startup.start();
        return future;
    }
    public static synchronized void stop(UUID owner) {
        CompletableFuture<NativeService> future = SERVICES.remove(owner);
        if (future != null) {
            future.thenAccept(NativeService::close);
            future.cancel(false);
        }
    }
    public static synchronized void stopAll() {
        for (UUID owner : new ArrayList<UUID>(SERVICES.keySet())) stop(owner);
    }
    public CompletableFuture<JsonObject> request(String type, JsonObject payload) {
        JsonObject envelope = new JsonObject(); envelope.addProperty("type", type); envelope.add("payload", payload);
        return send(BridgeFrame.Kind.REQUEST, envelope);
    }
    private CompletableFuture<JsonObject> send(BridgeFrame.Kind kind, JsonObject value) {
        final CompletableFuture<JsonObject> result = new CompletableFuture<JsonObject>();
        final UUID operation = UUID.randomUUID();
        final BridgeFrame frame = new BridgeFrame(kind, operation, world, owner, session, 0, JSON.toJson(value).getBytes(StandardCharsets.UTF_8));
        if (closed) { result.completeExceptionally(new IOException("LEGACY_SERVICE_CLOSED")); return result; }
        requests.put(operation, result);
        try {
            writes.execute(new Runnable() {
                @Override public void run() { try { writer.write(frame); } catch (IOException failure) { failPending(); } }
            });
        } catch (RejectedExecutionException stopped) { requests.remove(operation); result.completeExceptionally(stopped); }
        return result;
    }
    private void failPending() {
        closed = true;
        for (CompletableFuture<JsonObject> pending : requests.values()) pending.completeExceptionally(new IOException("LEGACY_SERVICE_DISCONNECTED_UNKNOWN"));
        requests.clear();
        writes.shutdownNow(); process.destroy();
    }
    @Override public void close() {
        failPending();
        Thread reaper = new Thread(new Runnable() {
            @Override public void run() {
                try { if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly(); }
                catch (InterruptedException failure) { Thread.currentThread().interrupt(); process.destroyForcibly(); }
            }
        }, "DivZero189-service-stop");
        reaper.setDaemon(true); reaper.start();
    }
    private static File java25() throws IOException {
        String configured = System.getProperty("divzero.java25", "");
        if (!configured.isEmpty()) {
            File file = new File(configured).getCanonicalFile();
            if (!file.isFile()) throw new IOException("JAVA25_CONFIGURED_PATH_MISSING");
            return file;
        }
        File bundled = new File(System.getProperty("user.home"), "AppData/Roaming/.minecraft/runtime/java-runtime-epsilon/bin/java.exe");
        if (bundled.isFile()) return bundled;
        throw new IOException("JAVA25_RUNTIME_REQUIRED");
    }
    private static Path extract(Path directory) throws Exception {
        String expected;
        try (InputStream hash = NativeService.class.getResourceAsStream("/META-INF/divzero/service.sha256")) {
            if (hash == null) throw new IOException("LEGACY_SERVICE_NOT_PACKAGED");
            ByteArrayOutputStream buffer = new ByteArrayOutputStream(); int value;
            while ((value = hash.read()) != -1) { if (buffer.size() > 128) throw new IOException("LEGACY_SERVICE_MANIFEST"); buffer.write(value); }
            expected = new String(buffer.toByteArray(), StandardCharsets.US_ASCII).trim();
        }
        if (!expected.matches("[0-9a-f]{64}")) throw new IOException("LEGACY_SERVICE_MANIFEST");
        Path target = directory.resolve("engine").resolve(expected).resolve("service.jar");
        if (Files.isRegularFile(target)) {
            if (!digest(target).equals(expected)) throw new IOException("LEGACY_SERVICE_CACHE_CHANGED");
            return target;
        }
        Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), "service-", ".tmp");
        try {
            try (InputStream input = NativeService.class.getResourceAsStream("/META-INF/divzero/service.jar")) {
                if (input == null) throw new IOException("LEGACY_SERVICE_NOT_PACKAGED");
                Files.copy(input, temporary, StandardCopyOption.REPLACE_EXISTING);
            }
            if (!digest(temporary).equals(expected)) throw new IOException("LEGACY_SERVICE_INTEGRITY");
            try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE); }
            catch (FileAlreadyExistsException raced) { if (!digest(target).equals(expected)) throw new IOException("LEGACY_SERVICE_CACHE_CHANGED"); }
            return target;
        } finally { Files.deleteIfExists(temporary); }
    }
    private static String digest(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(path)) { byte[] buffer = new byte[65536]; int count; while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count); }
        StringBuilder value = new StringBuilder(); for (byte b : digest.digest()) value.append(String.format(Locale.ROOT, "%02x", b & 255)); return value.toString();
    }
}
