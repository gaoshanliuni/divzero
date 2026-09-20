package dev.mineagent.runtime.worker.smoke;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** One explicit localhost BOOT source patch. It validates transport, never model quality. */
final class BootUpgradeControlledProvider implements AutoCloseable {
    static final String SOURCE_PATH = "boot/src/smoke/boot/UpgradeExtension.java";
    static final String V2_SOURCE = """
            package smoke.boot;
            import java.nio.charset.StandardCharsets;
            import java.nio.file.*;
            import java.util.Map;
            import dev.mineagent.runtime.api.extension.BootExtension;
            import net.neoforged.fml.loading.FMLPaths;
            public final class UpgradeExtension implements BootExtension {
              public void initialize(Map<String,Object> context) throws Exception {
                String value="V2|"+context.get("packageId")+"|"+context.get("packageHash")+"|"+context.get("physicalSide");
                Files.writeString(FMLPaths.GAMEDIR.get().resolve("boot-upgrade-runtime.txt"),value,StandardCharsets.UTF_8,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING,StandardOpenOption.WRITE);
              }
            }
            """;
    private final HttpServer http;
    private final Path game;
    private final AtomicInteger calls = new AtomicInteger();
    private final ObjectMapper json = new ObjectMapper();

    BootUpgradeControlledProvider(Path game) throws Exception {
        this.game = game;
        http = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        http.createContext("/v1/chat/completions", exchange -> {
            try {
                byte[] raw = exchange.getRequestBody().readNBytes(4 * 1024 * 1024 + 1);
                if (raw.length > 4 * 1024 * 1024) throw new IllegalArgumentException("BOOT_UPGRADE_PROVIDER_REQUEST_LIMIT");
                String request = new String(raw, StandardCharsets.UTF_8);
                if (!request.contains("BOOT_EXTENSION") || !request.contains(SOURCE_PATH)
                        || !request.contains("mineagent_boot_upgrade_smoke"))
                    throw new IllegalStateException("BOOT_UPGRADE_PROVIDER_CONTEXT");
                int call = calls.incrementAndGet();
                if (call != 1) throw new IllegalStateException("BOOT_UPGRADE_PROVIDER_REPLAY");
                Files.writeString(game.resolve("boot-upgrade-provider-request.json"), request);
                String candidate = json.writeValueAsString(Map.of(
                        "files", List.of(Map.of("path", SOURCE_PATH, "content", V2_SOURCE, "encoding", "utf8")),
                        "delete", List.of(), "version", "2.0.0", "dependencies", Map.of()));
                byte[] body = json.writeValueAsBytes(Map.of("model", "controlled-boot-upgrade",
                        "choices", List.of(Map.of("finish_reason", "stop", "message", Map.of("content", candidate)))));
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } catch (Exception failure) {
                Files.writeString(game.resolve("boot-upgrade-provider-failure.txt"), failure.toString());
                byte[] body = "{\"error\":{\"code\":\"CONTROLLED_BOOT_UPGRADE_FAILED\"}}"
                        .getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(500, body.length);
                exchange.getResponseBody().write(body);
            } finally {
                exchange.close();
            }
        });
        http.start();
        try (var config = dev.mineagent.runtime.core.config.ServerConfigService.open(
                game.resolve("mineagent-runtime-data/runtime.db"))) {
            if (!config.apply(new dev.mineagent.runtime.api.config.ConfigPatch(config.snapshot().revision(), Map.of(
                    "provider.openai.baseUrl", "http://127.0.0.1:" + http.getAddress().getPort() + "/v1/",
                    "provider.openai.model", "controlled-boot-upgrade",
                    "provider.openai.apiKey", "fixture-only",
                    "voice.output.enabled", "false")), true).accepted())
                throw new IllegalStateException("BOOT_UPGRADE_PROVIDER_CONFIG");
        } catch (Exception failure) {
            http.stop(0);
            throw failure;
        }
    }

    void verify() throws Exception {
        if (calls.get() != 1 || !Files.isRegularFile(game.resolve("boot-upgrade-provider-request.json"))
                || Files.exists(game.resolve("boot-upgrade-provider-failure.txt")))
            throw new IllegalStateException("BOOT_UPGRADE_PROVIDER_CALLS");
    }

    @Override
    public void close() throws Exception {
        http.stop(0);
        Files.writeString(game.resolve("boot-upgrade-provider.json"), json.writeValueAsString(Map.of(
                "kind", "CONTROLLED_LOCAL_BOOT_PATCH_NOT_MODEL_QUALITY", "calls", calls.get(), "paidCalls", 0)));
    }
}
