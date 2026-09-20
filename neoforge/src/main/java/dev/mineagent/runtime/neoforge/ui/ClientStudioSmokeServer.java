package dev.mineagent.runtime.neoforge.ui;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mineagent.runtime.api.config.ConfigPatch;
import dev.mineagent.runtime.api.packages.*;
import dev.mineagent.runtime.api.permission.PermissionAction;
import dev.mineagent.runtime.core.packages.ClientJavaPlan;
import dev.mineagent.runtime.core.packages.ClientScriptPlan;
import dev.mineagent.runtime.core.packages.RuntimePackageCanonicalizer;
import dev.mineagent.runtime.neoforge.MineAgentRuntimeServices;
import dev.mineagent.runtime.worker.generation.GeneratedFile;
import dev.mineagent.runtime.worker.generation.ParsedRuntimePackage;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

/** Real F2 manual CLIENT Studio fixture. It creates only authority and one signed dependency. */
@EventBusSubscriber(modid = "mineagent_runtime")
public final class ClientStudioSmokeServer {
    public static final String DEPENDENCY_NAME = "CLIENT Studio Dependency";
    public static final String RHINO_NAME = "Manual CLIENT Rhino Studio";
    public static final String JAVA_NAME = "Manual CLIENT Java Studio";
    public static final String RHINO_STATUS = "STUDIO_RHINO:MULTI";
    public static final String JAVA_STATUS = "STUDIO_JAVA:MULTI";
    private static final ObjectMapper JSON = new ObjectMapper();
    public static volatile RuntimePackage dependencyPackage;
    public static volatile RuntimePackage rhinoPackage;
    public static volatile RuntimePackage javaPackage;
    public static volatile UUID agentId;
    public static volatile String failure;
    public static volatile boolean verified;
    private static int ticks;

    private ClientStudioSmokeServer() {}

    public static boolean enabled() {
        return Boolean.getBoolean("mineagent.clientStudioSmoke");
    }

    private static Path root(net.minecraft.server.MinecraftServer server) {
        return server.getServerDirectory().resolve("client-studio-smoke");
    }

    private static NativeCompatibility compatibility() {
        return new NativeCompatibility(1, Map.of("CLIENT", new NativeCompatibility.Target(
                "26.1.2", "neoforge", "26.1.2.106", "official", 25, Map.of())));
    }

    private static RuntimePackage installDependency(net.minecraft.server.MinecraftServer server,
                                                    ServerPlayer viewer) throws Exception {
        String source = "client.status('STUDIO_DEP_READY');"
                + "track(client.cleanupStatus('STUDIO_DEP_CLEANED'));"
                + "({value:function(value){return 'STUDIO_DEP:'+value;}});";
        byte[] bytes = source.getBytes(StandardCharsets.UTF_8);
        String path = "client/studio-dependency.js";
        String hash = RuntimePackageCanonicalizer.sha256(bytes);
        var file = new GeneratedFile(path, RuntimeResourceSide.CLIENT, "application/javascript", hash, bytes);
        var parsed = new ParsedRuntimePackage(DEPENDENCY_NAME, "1.0", RuntimePackageType.EXTENSION,
                ActivationMode.HOT_RUNTIME, Map.of(), Set.of(), Map.of("client",
                new RuntimeEntrypoint(path, RuntimeResourceSide.CLIENT, hash)), List.of(), List.of(file),
                compatibility());
        return ServerPackageRuntime.get(server).importOwned(viewer, UUID.randomUUID(), parsed);
    }

    private static void validatePublished(net.minecraft.server.MinecraftServer server) throws Exception {
        var all = ServerPackageRuntime.get(server).worldLibrary().all();
        rhinoPackage = all.stream().filter(p -> p.name().equals(RHINO_NAME)).findFirst().orElse(null);
        javaPackage = all.stream().filter(p -> p.name().equals(JAVA_NAME)).findFirst().orElse(null);
        if (rhinoPackage == null || javaPackage == null) return;
        Map<UUID, String> dependencies = Map.of(dependencyPackage.packageId(), "1.0");
        var rhino = ClientScriptPlan.inspect(rhinoPackage);
        var java = ClientJavaPlan.inspect(javaPackage);
        require(rhino.modules().keySet().equals(Set.of("client/main.js", "client/helper.js")),
                "CLIENT_STUDIO_RHINO_FILES");
        require(java.sources().keySet().equals(Set.of(
                "client/dev/mineagent/studio/ManualClientExtension.java",
                "client/dev/mineagent/studio/StudioHelper.java")), "CLIENT_STUDIO_JAVA_FILES");
        require(rhinoPackage.dependencies().equals(dependencies)
                        && javaPackage.dependencies().equals(dependencies),
                "CLIENT_STUDIO_DEPENDENCIES");
        for (var value : List.of(rhinoPackage, javaPackage)) {
            require(value.activationMode() == ActivationMode.HOT_RUNTIME && !value.enabled(),
                    "CLIENT_STUDIO_LIFECYCLE");
            require(!value.permissions().contains("RUN_CODE")
                            && !value.entrypoints().containsKey("java")
                            && !value.entrypoints().containsKey("studio_script")
                            && value.entrypoints().values().stream().allMatch(e -> e.side() == RuntimeResourceSide.CLIENT)
                            && value.resources().values().stream().allMatch(e -> e.side() == RuntimeResourceSide.CLIENT),
                    "CLIENT_STUDIO_SERVER_SOURCE_CROSSED");
            require(value.nativeCompatibility() != null
                            && value.nativeCompatibility().targets().keySet().equals(Set.of("CLIENT")),
                    "CLIENT_STUDIO_COMPATIBILITY");
        }
        var drafts = MineAgentRuntimeServices.codeDrafts(server).allFor(
                server.getPlayerList().getPlayers().stream()
                        .filter(p -> !(p instanceof dev.mineagent.runtime.neoforge.body.MineAgentPlayer))
                        .findFirst().orElseThrow().getUUID(), false);
        require(drafts.stream().filter(d -> Set.of(rhinoPackage.packageId(), javaPackage.packageId())
                .contains(d.packageId())).allMatch(d -> d.path().startsWith("client/")),
                "CLIENT_STUDIO_DRAFT_SIDE");
        Files.createDirectories(root(server));
        Files.writeString(root(server).resolve("server-published.json"), JSON.writeValueAsString(Map.of(
                "rhino", rhinoPackage, "java", javaPackage, "dependency", dependencyPackage,
                "drafts", drafts.stream().filter(d -> Set.of(rhinoPackage.packageId(), javaPackage.packageId())
                        .contains(d.packageId())).toList(), "serverExecutionEntrypoints", List.of(),
                "providerCalls", 0, "systemInputInjected", false)));
        verified = true;
    }

    private static void require(boolean value, String code) {
        if (!value) throw new IllegalStateException(code);
    }

    @SubscribeEvent
    public static void tick(ServerTickEvent.Post event) throws Exception {
        if (!enabled() || failure != null || verified) return;
        var server = event.getServer();
        ticks++;
        var viewer = server.getPlayerList().getPlayers().stream()
                .filter(p -> !(p instanceof dev.mineagent.runtime.neoforge.body.MineAgentPlayer))
                .findFirst().orElse(null);
        if (viewer == null) return;
        try {
            if (ticks > 12000) throw new IllegalStateException("CLIENT_STUDIO_SERVER_TIMEOUT");
            if (dependencyPackage == null) {
                var grants = new HashSet<>(MineAgentRuntimeServices.permissions(server)
                        .trustedActions(viewer.getUUID()));
                grants.addAll(Set.of(PermissionAction.RUN_CODE, PermissionAction.MANAGE_PACKAGES,
                        PermissionAction.START_TASK));
                var config = MineAgentRuntimeServices.config(server);
                String encoded = grants.stream().map(Enum::name).sorted().collect(Collectors.joining(","));
                require(config.apply(new ConfigPatch(config.snapshot().revision(), Map.of(
                        "runtime.initialized", "true", "voice.output.enabled", "false",
                        "permission.player." + viewer.getUUID(), encoded)), true).accepted(),
                        "CLIENT_STUDIO_CONFIG");
                MineAgentRuntimeServices.permissions(server).setTrustedActions(viewer.getUUID(), grants);
                agentId = MineAgentRuntimeServices.bodies(server).createPersistentAt("CLIENT Studio Actor",
                        viewer.getUUID(), server.overworld(), viewer.position().add(2, 0, 0)).agentId();
                dependencyPackage = installDependency(server, viewer);
                Files.createDirectories(root(server));
                Files.writeString(root(server).resolve("fixture.json"), JSON.writeValueAsString(Map.of(
                        "viewer", viewer.getUUID(), "agent", agentId, "dependency", dependencyPackage,
                        "providerCalls", 0, "systemInputInjected", false)));
            }
            validatePublished(server);
        } catch (Exception error) {
            failure = Objects.toString(error.getMessage(), error.getClass().getSimpleName());
            Files.createDirectories(root(server));
            Files.writeString(root(server).resolve("server-failure.json"), JSON.writeValueAsString(Map.of(
                    "ticks", ticks, "error", error.toString())));
            throw error;
        }
    }
}
