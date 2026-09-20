package dev.mineagent.runtime.worker.smoke;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

public final class ProductionYsmSmokeLauncher {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PINNED_YSM_SHA512 =
            "b5e2445022e6c071b49c7eb312bc2e1628980a615506414a6ced06950999b8f6"
                    + "27829cebe848bd8a2dc025c121abfb4286221bb641ad7648b13446e78dc07df3";
    private static final Duration TIMEOUT = Duration.ofMinutes(4);

    private ProductionYsmSmokeLauncher() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 7) {
            throw new IllegalArgumentException(
                    "Expected: <minecraftRoot> <versionDir> <gameDir> <modJar> <ysmJar> <javaHome> <smokeWorld>");
        }
        Path minecraftRoot = Path.of(args[0]).toAbsolutePath().normalize();
        Path versionDirectory = Path.of(args[1]).toAbsolutePath().normalize();
        Path gameDirectory = Path.of(args[2]).toAbsolutePath().normalize();
        Path modJar = requiredFile(args[3], "MineAgent JAR");
        Path ysmJar = requiredFile(args[4], "YSM JAR");
        Path javaHome = Path.of(args[5]).toAbsolutePath().normalize();
        Path smokeWorld = Path.of(args[6]).toAbsolutePath().normalize();
        String versionId = versionDirectory.getFileName().toString();
        Path manifestPath = requiredFile(versionDirectory.resolve(versionId + ".json"), "version manifest");
        Path versionJar = requiredFile(versionDirectory.resolve(versionId + ".jar"), "version JAR");
        Path java = requiredFile(javaHome.resolve("bin/java.exe"), "Java 25 executable");
        Path natives = requiredDirectory(versionDirectory.resolve(versionId + "-natives"), "native directory");
        requireYsmChecksum(ysmJar);

        JsonNode manifest = JSON.readTree(manifestPath.toFile());
        var os = new OsContext("windows", System.getProperty("os.version"), System.getProperty("os.arch"));
        List<Path> classpath = new ArrayList<>(resolveLibraries(minecraftRoot, manifest, os));
        classpath.add(versionJar);
        if (classpath.size() < 50) {
            throw new IllegalStateException("Production classpath is incomplete: " + classpath.size());
        }

        prepareGameDirectory(gameDirectory, modJar, ysmJar, smokeWorld);
        Path consoleLog = gameDirectory.resolve("production-ysm-smoke-console.log");
        Files.deleteIfExists(consoleLog);
        String nativePath = natives.toString();
        String assetIndex = manifest.path("assetIndex").path("id").asText("30");
        List<String> command = new ArrayList<>();
        command.add(java.toString());
        command.add("--sun-misc-unsafe-memory-access=allow");
        command.add("--enable-native-access=ALL-UNNAMED");
        command.add("-Djava.library.path=" + nativePath);
        command.add("-Djna.tmpdir=" + nativePath);
        command.add("-Dorg.lwjgl.system.SharedLibraryExtractPath=" + nativePath);
        command.add("-Dio.netty.native.workdir=" + nativePath);
        command.add("-Dminecraft.launcher.brand=mineagent-production-smoke");
        command.add("-Dminecraft.launcher.version=1.0");
        command.add("-Djava.net.preferIPv6Addresses=system");
        command.add("-DlibraryDirectory=" + minecraftRoot.resolve("libraries"));
        command.add("--add-opens=java.base/java.lang.invoke=ALL-UNNAMED");
        command.add("--add-exports=jdk.naming.dns/com.sun.jndi.dns=java.naming");
        command.add("-Dmineagent.clientSmokeTest=true");
        command.add("-Dmineagent.integratedSmokeTest=true");
        command.add("-Dmineagent.smokeTest=true");
        command.add("-Dmineagent.productionYsmSmokeTest=true");
        command.add("-Dfile.encoding=UTF-8");
        command.add("-Dstdout.encoding=UTF-8");
        command.add("-Dstderr.encoding=UTF-8");
        command.add("-Xmx4G");
        command.add("-cp");
        command.add(String.join(System.getProperty("path.separator"),
                classpath.stream().map(Path::toString).toList()));
        command.add("net.neoforged.fml.startup.Client");
        command.addAll(List.of(
                "--username", "YsmSmoke",
                "--version", versionId,
                "--gameDir", gameDirectory.toString(),
                "--assetsDir", minecraftRoot.resolve("assets").toString(),
                "--assetIndex", assetIndex,
                "--uuid", "00000000000000000000000000000001",
                "--accessToken", "0",
                "--clientId", "0",
                "--xuid", "0",
                "--versionType", "release",
                "--quickPlaySingleplayer=SmokeWorld",
                "--quickPlayPath=" + gameDirectory.resolve("quickplay.log"),
                "--fml.neoForgeVersion", "26.1.2.106",
                "--fml.mcVersion", "26.1.2",
                "--fml.neoFormVersion", "1"
        ));

        Process process = new ProcessBuilder(command)
                .directory(gameDirectory.toFile())
                .redirectErrorStream(true)
                .redirectOutput(consoleLog.toFile())
                .start();
        if (!process.waitFor(TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
            process.destroyForcibly();
            process.waitFor(10, TimeUnit.SECONDS);
            throw new IllegalStateException("Production YSM smoke timed out after " + TIMEOUT);
        }
        String log = Files.readString(consoleLog, StandardCharsets.UTF_8);
        if (process.exitValue() != 0) {
            throw new IllegalStateException("Production YSM smoke exited " + process.exitValue()
                    + "; see " + consoleLog);
        }
        verifyLog(log);
        Files.deleteIfExists(gameDirectory.resolve("saves/SmokeWorld/session.lock"));
        Path restartLog = gameDirectory.resolve("production-ysm-restart-console.log");
        Files.deleteIfExists(restartLog);
        List<String> restartCommand = new ArrayList<>(command);
        restartCommand.remove("-Dmineagent.smokeTest=true");
        restartCommand.remove("-Dmineagent.productionYsmSmokeTest=true");
        restartCommand.add(restartCommand.indexOf("-Xmx4G"), "-Dmineagent.smokeTest=false");
        restartCommand.add(restartCommand.indexOf("-Xmx4G"), "-Dmineagent.productionYsmRestartSmokeTest=true");
        Process restart = new ProcessBuilder(restartCommand).directory(gameDirectory.toFile())
                .redirectErrorStream(true).redirectOutput(restartLog.toFile()).start();
        if (!restart.waitFor(TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
            restart.destroyForcibly();
            throw new IllegalStateException("Production YSM restart smoke timed out");
        }
        String restartOutput = Files.readString(restartLog, StandardCharsets.UTF_8);
        if (restart.exitValue() != 0 || !restartOutput.contains("CLIENT in PROD")
                || !restartOutput.contains("MINEAGENT_SMOKE_YSM_RESTART_SERVER_OK")
                || !restartOutput.contains("MINEAGENT_SMOKE_YSM_RESTART_OBSERVER_OK")
                || !restartOutput.contains("MINEAGENT_INTEGRATED_CLIENT_OK")) {
            throw new IllegalStateException("Production YSM restart persistence smoke failed; see " + restartLog);
        }
        System.out.println("MINEAGENT_PRODUCTION_YSM_SMOKE_OK log=" + consoleLog);
    }

    static List<Path> resolveLibraries(Path minecraftRoot, JsonNode manifest, OsContext os) {
        List<Path> resolved = new ArrayList<>();
        for (JsonNode library : manifest.path("libraries")) {
            if (!allowed(library.path("rules"), os)) {
                continue;
            }
            String artifact = library.path("downloads").path("artifact").path("path").asText("");
            if (artifact.isBlank()) {
                continue;
            }
            Path path = minecraftRoot.resolve("libraries").resolve(artifact).toAbsolutePath().normalize();
            if (!Files.isRegularFile(path)) {
                throw new IllegalStateException("Required production library is missing: " + artifact);
            }
            resolved.add(path);
        }
        return List.copyOf(resolved);
    }

    private static boolean allowed(JsonNode rules, OsContext os) {
        if (!rules.isArray() || rules.isEmpty()) {
            return true;
        }
        boolean allowed = false;
        for (JsonNode rule : rules) {
            if (matches(rule, os)) {
                allowed = "allow".equals(rule.path("action").asText());
            }
        }
        return allowed;
    }

    private static boolean matches(JsonNode rule, OsContext os) {
        if (rule.has("features")) {
            return false;
        }
        JsonNode requiredOs = rule.path("os");
        if (requiredOs.isMissingNode()) {
            return true;
        }
        if (requiredOs.has("name") && !requiredOs.path("name").asText().equals(os.name())) {
            return false;
        }
        if (requiredOs.has("version") && !Pattern.compile(requiredOs.path("version").asText())
                .matcher(os.version()).find()) {
            return false;
        }
        return !requiredOs.has("arch") || Pattern.compile(requiredOs.path("arch").asText())
                .matcher(os.arch()).find();
    }

    static void verifyLog(String log) {
        List<String> required = List.of(
                "CLIENT in PROD",
                "MINEAGENT_SMOKE_YSM_RUNTIME_READY",
                "MINEAGENT_SMOKE_YSM_SERVER_READBACK_OK model=default texture=blue animation=idle",
                "MINEAGENT_SMOKE_YSM_RULE_INVARIANTS_OK",
                "MINEAGENT_SMOKE_YSM_NATIVE_ACTIONS_OK",
                "MINEAGENT_SMOKE_YSM_RENDER_FRAME_OK",
                "MINEAGENT_SMOKE_YSM_UI_APPLY_OK model=default texture=blue animation=idle",
                "MINEAGENT_SMOKE_YSM_SELECTION_CARD_OK",
                "MINEAGENT_SMOKE_YSM_CHAT_EQUIVALENCE_OK",
                "MINEAGENT_SMOKE_YSM_STALE_REJECTED_OK",
                "MINEAGENT_SMOKE_YSM_IDEMPOTENT_REPLAY",
                "MINEAGENT_SMOKE_YSM_IDEMPOTENT_CLIENT_OK",
                "MINEAGENT_SMOKE_YSM_LIFECYCLE_OK respawn=true tracking=true",
                "MINEAGENT_INTEGRATED_CLIENT_OK"
        );
        for (String marker : required) {
            if (!log.contains(marker)) {
                throw new IllegalStateException("Production YSM smoke missing marker: " + marker);
            }
        }
        String normalized = log.toLowerCase(Locale.ROOT);
        if (normalized.contains("failed to load native lib") || normalized.contains("err: 54")
                || normalized.contains("mineagent_smoke_ysm_ui_apply_missing")) {
            throw new IllegalStateException("Production YSM smoke contains a native/UI failure");
        }
    }

    private static void prepareGameDirectory(Path gameDirectory, Path modJar, Path ysmJar, Path smokeWorld)
            throws IOException {
        resetSmokeGameDirectory(gameDirectory);
        Path mods = Files.createDirectories(gameDirectory.resolve("mods"));
        Files.copy(modJar, mods.resolve(modJar.getFileName()), StandardCopyOption.REPLACE_EXISTING);
        Files.copy(ysmJar, mods.resolve(ysmJar.getFileName()), StandardCopyOption.REPLACE_EXISTING);
        Path options = gameDirectory.resolve("options.txt");
        if (!Files.exists(options)) {
            Files.writeString(options, "onboardAccessibility:false\nstartedCleanly:true\n", StandardCharsets.UTF_8);
        }
        Path targetWorld = gameDirectory.resolve("saves/SmokeWorld");
        requiredDirectory(smokeWorld, "SmokeWorld fixture");
        try (var paths = Files.walk(smokeWorld)) {
            for (Path source : paths.toList()) {
                Path target = targetWorld.resolve(smokeWorld.relativize(source));
                if (Files.isDirectory(source)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        Files.deleteIfExists(targetWorld.resolve("session.lock"));
    }

    static void resetSmokeGameDirectory(Path gameDirectory) throws IOException {
        Path normalized = gameDirectory.toAbsolutePath().normalize();
        if (normalized.getFileName() == null
                || !"run-production-ysm-smoke".equals(normalized.getFileName().toString())) {
            throw new IllegalArgumentException("Refusing to reset a non-smoke directory: " + normalized);
        }
        if (Files.exists(normalized)) {
            try (var paths = Files.walk(normalized)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
        Files.createDirectories(normalized);
    }

    private static void requireYsmChecksum(Path ysmJar) throws Exception {
        String actual = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-512").digest(Files.readAllBytes(ysmJar)));
        if (!PINNED_YSM_SHA512.equals(actual)) {
            throw new IllegalStateException("Pinned YSM SHA-512 mismatch: " + actual);
        }
    }

    private static Path requiredFile(String path, String label) {
        return requiredFile(Path.of(path), label);
    }

    private static Path requiredFile(Path path, String label) {
        Path normalized = path.toAbsolutePath().normalize();
        if (!Files.isRegularFile(normalized)) {
            throw new IllegalArgumentException(label + " does not exist: " + normalized);
        }
        return normalized;
    }

    private static Path requiredDirectory(Path path, String label) {
        Path normalized = path.toAbsolutePath().normalize();
        if (!Files.isDirectory(normalized)) {
            throw new IllegalArgumentException(label + " does not exist: " + normalized);
        }
        return normalized;
    }

    record OsContext(String name, String version, String arch) {
    }
}
