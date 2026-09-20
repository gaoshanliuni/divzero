package dev.mineagent.runtime.neoforge.boot;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mineagent.runtime.api.config.ConfigPatch;
import dev.mineagent.runtime.api.packages.*;
import dev.mineagent.runtime.api.permission.PermissionAction;
import dev.mineagent.runtime.core.boot.*;
import dev.mineagent.runtime.core.compile.NativeCompilationSnapshot;
import dev.mineagent.runtime.core.packages.RuntimePackageCanonicalizer;
import dev.mineagent.runtime.neoforge.MineAgentRuntimeServices;
import dev.mineagent.runtime.neoforge.ui.ServerPackageRuntime;
import dev.mineagent.runtime.worker.generation.*;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

/** Actual dependency/consumer BOOT Mod pair across install, Loader use and reverse-order removal. */
@EventBusSubscriber(modid = "mineagent_runtime")
public final class BootDependencySmokeServer {
    public static final String DEP_NAME = "BOOT Dependency API";
    public static final String CONSUMER_NAME = "BOOT Dependency Consumer";
    public static final String DEP_MOD = "mineagent_boot_dep_a";
    public static final String CONSUMER_MOD = "mineagent_boot_dep_b";
    private static final ObjectMapper JSON = new ObjectMapper();
    public static volatile RuntimePackage dependencyPackage, consumerPackage;
    public static volatile UUID agentId;
    public static volatile String failure;
    public static volatile boolean ready;
    private static int ticks;

    private BootDependencySmokeServer() {}
    public static boolean enabled() { return Boolean.getBoolean("mineagent.bootDependencySmoke"); }
    public static String stage() { return System.getProperty("mineagent.bootDependencyStage", "prepare"); }
    private static Path root(net.minecraft.server.MinecraftServer server) {
        return server.getServerDirectory().resolve("boot-dependency-smoke");
    }

    private static GeneratedFile text(String path, String value, String media) throws Exception {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        return new GeneratedFile(path, RuntimeResourceSide.CLIENT, media,
                RuntimePackageCanonicalizer.sha256(bytes), bytes);
    }

    private static RuntimePackage install(net.minecraft.server.MinecraftServer server, ServerPlayer viewer,
                                          UUID id, String name, String modId, String entryClass,
                                          String sourcePath, String source, Map<UUID, String> dependencies)
            throws Exception {
        var descriptor = text(BootExtensionPlan.DESCRIPTOR, JSON.writeValueAsString(Map.of(
                "schema", 1, "modId", modId, "entrypoint", entryClass, "mixins", List.of())),
                "application/json");
        var java = text(sourcePath, source, "text/x-java-source");
        var compatibility = new NativeCompatibility(1, Map.of("CLIENT", new NativeCompatibility.Target(
                "26.1.2", "neoforge", "26.1.2.106", "official", 25, Map.of())));
        var parsed = new ParsedRuntimePackage(name, "1.0.0", RuntimePackageType.EXTENSION,
                ActivationMode.BOOT_EXTENSION, dependencies, Set.of(), Map.of("boot", new RuntimeEntrypoint(
                BootExtensionPlan.DESCRIPTOR, RuntimeResourceSide.CLIENT, descriptor.sha256())), List.of(),
                List.of(descriptor, java), compatibility);
        return ServerPackageRuntime.get(server).importOwned(viewer, id, parsed);
    }

    private static String dependencySource() {
        return """
                package smoke.bootdep.api;
                import java.nio.charset.StandardCharsets;
                import java.nio.file.*;
                import java.util.Map;
                import dev.mineagent.runtime.api.extension.BootExtension;
                import net.neoforged.fml.loading.FMLPaths;
                public final class DependencyExtension implements BootExtension {
                  public static String value(){ return "DEP_V1"; }
                  public void initialize(Map<String,Object> context) throws Exception {
                    Files.writeString(FMLPaths.GAMEDIR.get().resolve("boot-dependency-api.txt"),"DEPENDENCY_READY|"+context.get("packageId")+"|"+context.get("packageHash"),StandardCharsets.UTF_8,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING,StandardOpenOption.WRITE);
                  }
                }
                """;
    }

    private static String consumerSource() {
        return """
                package smoke.bootdep.consumer;
                import java.nio.charset.StandardCharsets;
                import java.nio.file.*;
                import java.util.Map;
                import dev.mineagent.runtime.api.extension.BootExtension;
                import net.neoforged.fml.loading.FMLPaths;
                import smoke.bootdep.api.DependencyExtension;
                public final class ConsumerExtension implements BootExtension {
                  public void initialize(Map<String,Object> context) throws Exception {
                    Files.writeString(FMLPaths.GAMEDIR.get().resolve("boot-dependency-consumer.txt"),"CONSUMER:"+DependencyExtension.value()+"|"+context.get("packageId")+"|"+context.get("packageHash"),StandardCharsets.UTF_8,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING,StandardOpenOption.WRITE);
                  }
                }
                """;
    }

    private static void require(boolean value, String code) {
        if (!value) throw new IllegalStateException(code);
    }

    @SubscribeEvent
    public static void tick(ServerTickEvent.Post event) throws Exception {
        if (!enabled() || failure != null || ready) return;
        var server = event.getServer();ticks++;
        var viewer = server.getPlayerList().getPlayers().stream()
                .filter(p -> !(p instanceof dev.mineagent.runtime.neoforge.body.MineAgentPlayer))
                .findFirst().orElse(null);
        if (viewer == null) return;
        try {
            if (ticks > 12000) throw new IllegalStateException("BOOT_DEPENDENCY_SERVER_TIMEOUT_" + stage());
            Files.createDirectories(root(server));
            if (stage().equals("prepare")) {
                boolean originalOperator = server.getPlayerList().isOp(viewer.nameAndId());
                if (!originalOperator) server.getPlayerList().op(viewer.nameAndId());
                var grants = new HashSet<>(MineAgentRuntimeServices.permissions(server)
                        .trustedActions(viewer.getUUID()));
                grants.addAll(Set.of(PermissionAction.RUN_CODE, PermissionAction.MANAGE_PACKAGES,
                        PermissionAction.START_TASK));
                var config = MineAgentRuntimeServices.config(server);
                require(config.apply(new ConfigPatch(config.snapshot().revision(), Map.of(
                        "runtime.initialized", "true", "voice.output.enabled", "false",
                        "permission.player." + viewer.getUUID(), grants.stream().map(Enum::name).sorted()
                                .collect(Collectors.joining(",")))), true).accepted(), "BOOT_DEPENDENCY_CONFIG");
                MineAgentRuntimeServices.permissions(server).setTrustedActions(viewer.getUUID(), grants);
                agentId = MineAgentRuntimeServices.bodies(server).createPersistentAt("BOOT Dependency Actor",
                        viewer.getUUID(), server.overworld(), viewer.position().add(2, 0, 0)).agentId();
                UUID dep = UUID.randomUUID(), consumer = UUID.randomUUID();
                dependencyPackage = install(server, viewer, dep, DEP_NAME, DEP_MOD,
                        "smoke.bootdep.api.DependencyExtension",
                        "boot/src/smoke/bootdep/api/DependencyExtension.java", dependencySource(), Map.of());
                consumerPackage = install(server, viewer, consumer, CONSUMER_NAME, CONSUMER_MOD,
                        "smoke.bootdep.consumer.ConsumerExtension",
                        "boot/src/smoke/bootdep/consumer/ConsumerExtension.java", consumerSource(),
                        Map.of(dep, "1.0.0"));
                Files.writeString(root(server).resolve("journal.json"), JSON.writeValueAsString(Map.of(
                        "dependency", dep, "consumer", consumer, "depCanonical", dependencyPackage.canonicalSha256(),
                        "consumerCanonical", consumerPackage.canonicalSha256(), "agent", agentId,
                        "originalOperator", originalOperator, "providerCalls", 0, "systemInputInjected", false)));
                ready = true;return;
            }
            var saved = JSON.readTree(Files.readString(root(server).resolve("journal.json")));
            if (!server.getPlayerList().isOp(viewer.nameAndId())) server.getPlayerList().op(viewer.nameAndId());
            agentId = UUID.fromString(saved.path("agent").asText());
            dependencyPackage = ServerPackageRuntime.get(server).worldLibrary().get(
                    UUID.fromString(saved.path("dependency").asText())).orElseThrow();
            consumerPackage = ServerPackageRuntime.get(server).worldLibrary().get(
                    UUID.fromString(saved.path("consumer").asText())).orElseThrow();
            if (stage().equals("verify")) {
                var depProof = NativeBootProof.get(DEP_MOD).orElseThrow();
                var consumerProof = NativeBootProof.get(CONSUMER_MOD).orElseThrow();
                require(depProof.canonical().equals(dependencyPackage.canonicalSha256())
                                && consumerProof.canonical().equals(consumerPackage.canonicalSha256()),
                        "BOOT_DEPENDENCY_PROOF");
                String depMarker = Files.readString(server.getServerDirectory().resolve("boot-dependency-api.txt"));
                String consumerMarker = Files.readString(server.getServerDirectory().resolve(
                        "boot-dependency-consumer.txt"));
                require(depMarker.equals("DEPENDENCY_READY|" + dependencyPackage.packageId() + "|"
                                + dependencyPackage.canonicalSha256())
                                && consumerMarker.equals("CONSUMER:DEP_V1|" + consumerPackage.packageId() + "|"
                                + consumerPackage.canonicalSha256()), "BOOT_DEPENDENCY_RUNTIME_CALL");
                Path consumerFile = Path.of(consumerProof.path());
                var artifact = BootArtifact.inspect(NativeCompilationSnapshot.read(consumerFile,
                        BootExtensionPlan.MAX_ARCHIVE));
                var graph = artifact.metadata().dependencies();
                require(graph != null && graph.nodes().size() == 1
                                && graph.node(dependencyPackage.packageId()).artifact().equals(depProof.archiveHash()),
                        "BOOT_DEPENDENCY_GRAPH_RUNTIME");
                Files.writeString(root(server).resolve("server-verify.json"), JSON.writeValueAsString(Map.of(
                        "dependencyProof", depProof, "consumerProof", consumerProof, "graph", graph,
                        "dependencyMarker", depMarker, "consumerMarker", consumerMarker,
                        "providerCalls", 0, "systemInputInjected", false)));
            } else if (stage().equals("removed")) {
                require(NativeBootProof.get(DEP_MOD).isEmpty() && NativeBootProof.get(CONSUMER_MOD).isEmpty()
                                && net.neoforged.fml.ModList.get().getModFileById(DEP_MOD) == null
                                && net.neoforged.fml.ModList.get().getModFileById(CONSUMER_MOD) == null,
                        "BOOT_DEPENDENCY_REMOVAL_REPLAYED");
                Files.writeString(root(server).resolve("server-removed.json"), JSON.writeValueAsString(Map.of(
                        "dependencyLoaded", false, "consumerLoaded", false,
                        "providerCalls", 0, "systemInputInjected", false)));
            } else throw new IllegalArgumentException("BOOT_DEPENDENCY_STAGE");
            ready = true;
        } catch (Exception error) {
            failure = Objects.toString(error.getMessage(), error.getClass().getSimpleName());
            Files.writeString(root(server).resolve("server-failure-" + stage() + ".json"),
                    JSON.writeValueAsString(Map.of("stage", stage(), "ticks", ticks, "error", error.toString())));
            throw error;
        }
    }
}
