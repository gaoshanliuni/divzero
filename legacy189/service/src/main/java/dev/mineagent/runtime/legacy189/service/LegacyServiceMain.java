package dev.mineagent.runtime.legacy189.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mineagent.runtime.api.worker.WorkerEnvelope;
import dev.mineagent.runtime.core.conversation.ConversationTools;
import dev.mineagent.runtime.legacy189.bridge.BridgeFrame;
import dev.mineagent.runtime.legacy189.bridge.FrameCodec;
import dev.mineagent.runtime.worker.WorkerCancellation;
import dev.mineagent.runtime.worker.WorkerRequestHandler;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** The existing DivZero Worker executes in Java 25; Minecraft stays in Java 8.
 * This transport exposes service operations, never claims unimplemented Native
 * tools are executable, and does not restart/replay an uncertain operation.
 */
public final class LegacyServiceMain {
    private static final ObjectMapper JSON = new ObjectMapper();
    private LegacyServiceMain() { }
    public static void main(String[] args) {
        if (args.length != 4) throw new IllegalArgumentException("LEGACY_SERVICE_ARGUMENTS");
        UUID world = UUID.fromString(args[0]), owner = UUID.fromString(args[1]), session = UUID.fromString(args[2]);
        Path directory = Path.of(args[3]).toAbsolutePath().normalize().resolve(world.toString()).resolve(owner.toString());
        FrameCodec.Reader input = new FrameCodec.Reader(System.in);
        FrameCodec.Writer output = new FrameCodec.Writer(System.out);
        var pending = new ConcurrentHashMap<UUID, FutureTask<Void>>();
        Set<UUID> seen = new HashSet<>();
        var pool = Executors.newVirtualThreadPerTaskExecutor();
        WorkerRequestHandler handler = new WorkerRequestHandler();
        try {
            Files.createDirectories(directory);
            WorkerEnvelope configured = handler.handle(new WorkerEnvelope(1, UUID.randomUUID(), "storage.configure", Map.of("contentRoot", directory.resolve("content").toString())));
            if (!configured.type().equals("storage.configured")) throw new IllegalStateException("LEGACY_STORAGE_FAILED");
            boolean greeted = false;
            while (true) {
                BridgeFrame frame = input.read();
                if (frame == null) break;
                frame.requireScope(world, owner, session);
                if (frame.kind() == BridgeFrame.Kind.GOODBYE) break;
                if (frame.kind() == BridgeFrame.Kind.HELLO) {
                    if (greeted || !seen.add(frame.operation())) throw new IllegalStateException("LEGACY_HANDSHAKE_DUPLICATE");
                    greeted = true;
                    emit(output, frame, BridgeFrame.Kind.RESULT, "service.ready", Map.of("protocol", FrameCodec.PROTOCOL,
                            "java", Runtime.version().feature(), "workerStorage", true, "nativeParityVerified", false));
                    continue;
                }
                if (!greeted) throw new IllegalStateException("LEGACY_HANDSHAKE_REQUIRED");
                if (frame.kind() == BridgeFrame.Kind.CANCEL) {
                    FutureTask<Void> active = pending.get(frame.operation());
                    if (active != null) { active.cancel(true); WorkerCancellation.cancel(frame.operation()); }
                    // Cancellation is a request, not a claim that a side effect was undone.
                    emit(output, frame, BridgeFrame.Kind.RESULT, "request.cancelRequested", Map.of("knownOperation", active != null));
                    continue;
                }
                if (frame.kind() != BridgeFrame.Kind.REQUEST) throw new IllegalStateException("LEGACY_DIRECTION");
                if (!seen.add(frame.operation())) {
                    emit(output, frame, BridgeFrame.Kind.RESULT, "error", Map.of("code", "WORKER_DUPLICATE_REQUEST"));
                    continue;
                }
                Map<String, Object> content = JSON.readValue(frame.payload(), new TypeReference<>() {});
                if (!content.keySet().equals(Set.of("type", "payload")) || !(content.get("type") instanceof String type)
                        || !(content.get("payload") instanceof Map<?, ?>)) throw new IllegalArgumentException("LEGACY_REQUEST_SCHEMA");
                Map<String, Object> payload = JSON.convertValue(content.get("payload"), new TypeReference<>() {});
                if (type.equals("storage.configure")) {
                    emit(output, frame, BridgeFrame.Kind.RESULT, "error", Map.of("code", "LEGACY_STORAGE_SCOPE_FIXED")); continue;
                }
                if (type.equals("conversation.sourceCatalog")) {
                    emit(output, frame, BridgeFrame.Kind.RESULT, "conversation.sourceCatalog", Map.of(
                            "kind", "SOURCE_CATALOG_NOT_NATIVE_CAPABILITIES", "tools", ConversationTools.ALL,
                            "nativeParityVerified", false));
                    continue;
                }
                WorkerEnvelope request = new WorkerEnvelope(1, frame.operation(), type, payload);
                if (Set.of("health.check", "provider.configure", "provider.snapshot").contains(type)) {
                    WorkerEnvelope result = handler.handle(request);
                    emit(output, frame, BridgeFrame.Kind.RESULT, result.type(), result.payload()); continue;
                }
                WorkerRequestHandler snapshot = handler.requestSnapshot();
                FutureTask<Void> task = new FutureTask<>(() -> {
                    Consumer<WorkerEnvelope> delta = value -> {
                        if (Thread.currentThread().isInterrupted()) throw new CancellationException();
                        try { emit(output, frame, BridgeFrame.Kind.EVENT, value.type(), value.payload()); }
                        catch (IOException failed) { throw new UncheckedIOException(failed); }
                    };
                    try (var ignored = WorkerCancellation.enter(frame.operation())) {
                        WorkerEnvelope result = type.equals("model.stream") ? snapshot.handleStreaming(request, delta) : snapshot.handle(request);
                        if (!Thread.currentThread().isInterrupted()) emit(output, frame, BridgeFrame.Kind.RESULT, result.type(), result.payload());
                    } catch (CancellationException ignored) { }
                    catch (Exception failed) {
                        if (!Thread.currentThread().isInterrupted()) emit(output, frame, BridgeFrame.Kind.RESULT, "error", Map.of("code", "LEGACY_WORKER_REQUEST_FAILED"));
                    }
                    return null;
                }) { @Override protected void done() { pending.remove(frame.operation(), this); } };
                pending.put(frame.operation(), task); pool.execute(task);
            }
        } catch (Exception failure) {
            // Never print provider settings, request payloads, credentials or generated source.
            System.err.println("DIVZERO_LEGACY_SERVICE_STOPPED " + failure.getClass().getSimpleName());
        } finally {
            pending.values().forEach(task -> task.cancel(true)); pool.shutdownNow();
            try { pool.awaitTermination(5, TimeUnit.SECONDS); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            handler.close();
        }
    }
    private static void emit(FrameCodec.Writer output, BridgeFrame source, BridgeFrame.Kind kind, String type, Map<String, ?> payload) throws IOException {
        output.write(new BridgeFrame(kind, source.operation(), source.world(), source.owner(), source.session(), source.revision(),
                JSON.writeValueAsBytes(Map.of("type", type, "payload", payload))));
    }
}
