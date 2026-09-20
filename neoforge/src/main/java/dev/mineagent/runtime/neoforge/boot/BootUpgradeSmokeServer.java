package dev.mineagent.runtime.neoforge.boot;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mineagent.runtime.api.config.ConfigPatch;
import dev.mineagent.runtime.api.packages.*;
import dev.mineagent.runtime.api.permission.PermissionAction;
import dev.mineagent.runtime.core.boot.BootExtensionPlan;
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

/** Persistent four-JVM fixture for real BOOT install, replacement and rollback. */
@EventBusSubscriber(modid = "mineagent_runtime")
public final class BootUpgradeSmokeServer {
    public static final String NAME = "BOOT Upgrade Smoke";
    public static final String MOD_ID = "mineagent_boot_upgrade_smoke";
    public static final String SOURCE_PATH = "boot/src/smoke/boot/UpgradeExtension.java";
    public static final String V1_SOURCE = """
            package smoke.boot;
            import java.nio.charset.StandardCharsets;
            import java.nio.file.*;
            import java.util.Map;
            import dev.mineagent.runtime.api.extension.BootExtension;
            import net.neoforged.fml.loading.FMLPaths;
            public final class UpgradeExtension implements BootExtension {
              public void initialize(Map<String,Object> context) throws Exception {
                String value="V1|"+context.get("packageId")+"|"+context.get("packageHash")+"|"+context.get("physicalSide");
                Files.writeString(FMLPaths.GAMEDIR.get().resolve("boot-upgrade-runtime.txt"),value,StandardCharsets.UTF_8,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING,StandardOpenOption.WRITE);
              }
            }
            """;
    private static final ObjectMapper JSON = new ObjectMapper();
    public static volatile RuntimePackage runtimePackage;
    public static volatile UUID agentId;
    public static volatile String failure;
    public static volatile boolean ready;
    private static int ticks;

    private BootUpgradeSmokeServer() {}

    public static boolean enabled() { return Boolean.getBoolean("mineagent.bootUpgradeSmoke"); }
    public static String stage() { return System.getProperty("mineagent.bootUpgradeStage", "prepare"); }
    private static Path root(net.minecraft.server.MinecraftServer server) {
        return server.getServerDirectory().resolve("boot-upgrade-smoke");
    }

    private static GeneratedFile file(String path, String value, String media) throws Exception {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        return new GeneratedFile(path, RuntimeResourceSide.CLIENT, media,
                RuntimePackageCanonicalizer.sha256(bytes), bytes);
    }

    private static RuntimePackage install(net.minecraft.server.MinecraftServer server, ServerPlayer viewer)
            throws Exception {
        String descriptor = JSON.writeValueAsString(Map.of("schema", 1, "modId", MOD_ID,
                "entrypoint", "smoke.boot.UpgradeExtension", "mixins", List.of()));
        var descriptorFile = file(BootExtensionPlan.DESCRIPTOR, descriptor, "application/json");
        var source = file(SOURCE_PATH, V1_SOURCE, "text/x-java-source");
        var compatibility = new NativeCompatibility(1, Map.of("CLIENT", new NativeCompatibility.Target(
                "26.1.2", "neoforge", "26.1.2.106", "official", 25, Map.of())));
        var parsed = new ParsedRuntimePackage(NAME, "1.0.0", RuntimePackageType.EXTENSION,
                ActivationMode.BOOT_EXTENSION, Map.of(), Set.of(), Map.of("boot", new RuntimeEntrypoint(
                BootExtensionPlan.DESCRIPTOR, RuntimeResourceSide.CLIENT, descriptorFile.sha256())), List.of(),
                List.of(descriptorFile, source), compatibility);
        return ServerPackageRuntime.get(server).importOwned(viewer, UUID.randomUUID(), parsed);
    }

    private static void require(boolean value, String code) {
        if (!value) throw new IllegalStateException(code);
    }

    private static com.fasterxml.jackson.databind.JsonNode journal(net.minecraft.server.MinecraftServer server)
            throws Exception {
        return JSON.readTree(Files.readString(root(server).resolve("journal.json")));
    }

    private static void verifyLoaded(net.minecraft.server.MinecraftServer server, String expected,
                                     String canonical) throws Exception {
        var proof = NativeBootProof.get(MOD_ID).orElseThrow(
                () -> new IllegalStateException("BOOT_UPGRADE_PROOF_MISSING"));
        String marker = Files.readString(server.getServerDirectory().resolve("boot-upgrade-runtime.txt"));
        require(proof.canonical().equals(canonical) && marker.equals(expected + "|" + proof.packageId() + "|"
                + canonical + "|CLIENT"), "BOOT_UPGRADE_LOADED_SOURCE");
        require(Path.of(proof.path()).toRealPath().getParent().equals(
                net.neoforged.fml.loading.FMLPaths.MODSDIR.get().toRealPath()), "BOOT_UPGRADE_LOADER_PATH");
    }

    @SubscribeEvent
    public static void tick(ServerTickEvent.Post event) throws Exception {
        if (!enabled() || failure != null || ready && !stage().equals("upgrade")) return;
        var server = event.getServer();
        ticks++;
        var viewer = server.getPlayerList().getPlayers().stream()
                .filter(p -> !(p instanceof dev.mineagent.runtime.neoforge.body.MineAgentPlayer))
                .findFirst().orElse(null);
        if (viewer == null) return;
        try {
            if (ticks > 12000) throw new IllegalStateException("BOOT_UPGRADE_SERVER_TIMEOUT_" + stage());
            Files.createDirectories(root(server));
            if (stage().equals("prepare")) {
                if (runtimePackage == null) {
                    boolean originalOperator = server.getPlayerList().isOp(viewer.nameAndId());
                    if (!originalOperator) server.getPlayerList().op(viewer.nameAndId());
                    require(viewer.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER),
                            "BOOT_UPGRADE_OPERATOR_SETUP");
                    var grants = new HashSet<>(MineAgentRuntimeServices.permissions(server)
                            .trustedActions(viewer.getUUID()));
                    grants.addAll(Set.of(PermissionAction.RUN_CODE, PermissionAction.MANAGE_PACKAGES,
                            PermissionAction.START_TASK));
                    var config = MineAgentRuntimeServices.config(server);
                    String encoded = grants.stream().map(Enum::name).sorted().collect(Collectors.joining(","));
                    require(config.apply(new ConfigPatch(config.snapshot().revision(), Map.of(
                            "runtime.initialized", "true", "voice.output.enabled", "false",
                            "permission.player." + viewer.getUUID(), encoded)), true).accepted(),
                            "BOOT_UPGRADE_CONFIG");
                    MineAgentRuntimeServices.permissions(server).setTrustedActions(viewer.getUUID(), grants);
                    agentId = MineAgentRuntimeServices.bodies(server).createPersistentAt("BOOT Upgrade Actor",
                            viewer.getUUID(), server.overworld(), viewer.position().add(2, 0, 0)).agentId();
                    runtimePackage = install(server, viewer);
                    Files.writeString(root(server).resolve("journal.json"), JSON.writeValueAsString(Map.of(
                            "packageId", runtimePackage.packageId(), "v1Canonical", runtimePackage.canonicalSha256(),
                            "owner", viewer.getUUID(), "agent", agentId, "modId", MOD_ID,
                            "originalOperator", originalOperator,
                            "providerCalls", 0, "systemInputInjected", false)));
                }
                ready = true;
                return;
            }
            var saved = journal(server);
            if (!server.getPlayerList().isOp(viewer.nameAndId())) server.getPlayerList().op(viewer.nameAndId());
            require(viewer.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER),
                    "BOOT_UPGRADE_OPERATOR_SETUP");
            UUID packageId = UUID.fromString(saved.path("packageId").asText());
            agentId = UUID.fromString(saved.path("agent").asText());
            runtimePackage = ServerPackageRuntime.get(server).worldLibrary().get(packageId).orElseThrow();
            String v1 = saved.path("v1Canonical").asText();
            if (stage().equals("upgrade")) {
                verifyLoaded(server, "V1", v1);
                if (runtimePackage.revision() == 1) {
                    require(runtimePackage.version().equals("1.0.0"), "BOOT_UPGRADE_V1_HEAD");
                    ready = true;
                } else {
                    require(runtimePackage.revision() == 2 && runtimePackage.version().equals("2.0.0"),
                            "BOOT_UPGRADE_V2_HEAD");
                    var plan = BootExtensionPlan.read(runtimePackage,
                            ServerPackageRuntime.get(server).worldContent());
                    require(plan.sources().get("smoke/boot/UpgradeExtension.java").contains("\"V2|\""),
                            "BOOT_UPGRADE_V2_SOURCE");
                }
                return;
            }
            if (stage().equals("verify")) {
                require(runtimePackage.revision() == 2, "BOOT_UPGRADE_VERIFY_HEAD");
                verifyLoaded(server, "V2", runtimePackage.canonicalSha256());
            } else if (stage().equals("rollback")) {
                require(runtimePackage.revision() == 2, "BOOT_UPGRADE_ROLLBACK_LIBRARY_CHANGED");
                verifyLoaded(server, "V1", v1);
            } else throw new IllegalArgumentException("BOOT_UPGRADE_STAGE");
            Files.writeString(root(server).resolve("server-" + stage() + ".json"), JSON.writeValueAsString(Map.of(
                    "stage", stage(), "head", runtimePackage, "proof", NativeBootProof.get(MOD_ID).orElseThrow(),
                    "marker", Files.readString(server.getServerDirectory().resolve("boot-upgrade-runtime.txt")),
                    "providerCalls", stage().equals("upgrade") ? 1 : 0, "systemInputInjected", false)));
            ready = true;
        } catch (Exception error) {
            failure = Objects.toString(error.getMessage(), error.getClass().getSimpleName());
            Files.writeString(root(server).resolve("server-failure-" + stage() + ".json"),
                    JSON.writeValueAsString(Map.of("stage", stage(), "ticks", ticks, "error", error.toString())));
            throw error;
        }
    }
}
