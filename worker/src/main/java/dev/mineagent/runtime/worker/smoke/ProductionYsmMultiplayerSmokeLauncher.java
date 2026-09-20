package dev.mineagent.runtime.worker.smoke;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;

public final class ProductionYsmMultiplayerSmokeLauncher {
    private static final String YSM_SHA512 =
            "b5e2445022e6c071b49c7eb312bc2e1628980a615506414a6ced06950999b8f6"
                    + "27829cebe848bd8a2dc025c121abfb4286221bb641ad7648b13446e78dc07df3";

    private ProductionYsmMultiplayerSmokeLauncher() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 7) {
            throw new IllegalArgumentException(
                    "Expected <minecraftRoot> <versionDir> <serverDist> <clientRoot> <modJar> <ysmJar> <javaHome>");
        }
        Path minecraftRoot = Path.of(args[0]).toAbsolutePath().normalize();
        Path versionDir = Path.of(args[1]).toAbsolutePath().normalize();
        Path serverDist = Path.of(args[2]).toAbsolutePath().normalize();
        Path clientRoot = Path.of(args[3]).toAbsolutePath().normalize();
        Path modJar = requiredFile(Path.of(args[4]));
        Path ysmJar = requiredFile(Path.of(args[5]));
        Path java = requiredFile(Path.of(args[6]).resolve("bin/java.exe"));
        verifyYsm(ysmJar);
        prepareServer(serverDist, modJar, ysmJar);
        String versionId = versionDir.getFileName().toString();
        JsonNode manifest = new ObjectMapper().readTree(versionDir.resolve(versionId + ".json").toFile());
        List<Path> classpath = new ArrayList<>(ProductionYsmSmokeLauncher.resolveLibraries(
                minecraftRoot, manifest,
                new ProductionYsmSmokeLauncher.OsContext("windows", System.getProperty("os.version"),
                        System.getProperty("os.arch"))));
        classpath.add(requiredFile(versionDir.resolve(versionId + ".jar")));
        Path natives = requiredDirectory(versionDir.resolve(versionId + "-natives"));
        Path clientA = prepareClient(clientRoot.resolve("client-a"), modJar, ysmJar);
        Path clientB = prepareClient(clientRoot.resolve("client-b"), modJar, ysmJar);

        Path serverLog = serverDist.resolve("production-ysm-multiplayer-server.log");
        Process server = new ProcessBuilder(serverCommand(serverDist, java))
                .directory(serverDist.toFile()).redirectErrorStream(true).redirectOutput(serverLog.toFile()).start();
        List<Process> clients = new ArrayList<>();
        try {
            waitForMarker(server, serverLog, "Done (", 90);
            Process firstA = startClient(java, minecraftRoot, versionDir, natives, classpath, manifest,
                    clientA, "ClientA", "0000000000000000000000000000000a", "first.log");
            Process firstB = startClient(java, minecraftRoot, versionDir, natives, classpath, manifest,
                    clientB, "ClientB", "0000000000000000000000000000000b", "first.log");
            clients.add(firstA);
            clients.add(firstB);
            waitSuccess(firstA, 90, "ClientA first");
            waitSuccess(firstB, 90, "ClientB first");
            Process lateA = startClient(java, minecraftRoot, versionDir, natives, classpath, manifest,
                    clientA, "ClientA", "0000000000000000000000000000000a", "reconnect.log");
            clients.add(lateA);
            waitSuccess(lateA, 90, "ClientA reconnect");
            waitSuccess(server, 90, "production YSM server");

            String serverOutput = Files.readString(serverLog, StandardCharsets.UTF_8);
            String aFirst = Files.readString(clientA.resolve("first.log"), StandardCharsets.UTF_8);
            String bFirst = Files.readString(clientB.resolve("first.log"), StandardCharsets.UTF_8);
            String aLate = Files.readString(clientA.resolve("reconnect.log"), StandardCharsets.UTF_8);
            require(serverOutput, "DEDICATED_SERVER in PROD", "MINEAGENT_MULTIPLAYER_TWO_CLIENTS_OK",
                    "MINEAGENT_MULTIPLAYER_YSM_SERVER_APPLY_OK");
            require(aFirst, "CLIENT in PROD", "MINEAGENT_MULTIPLAYER_YSM_OBSERVER_OK player=ClientA");
            require(bFirst, "CLIENT in PROD", "MINEAGENT_MULTIPLAYER_YSM_OBSERVER_OK player=ClientB");
            require(aLate, "CLIENT in PROD", "MINEAGENT_MULTIPLAYER_YSM_OBSERVER_OK player=ClientA");
            System.out.println("MINEAGENT_PRODUCTION_YSM_MULTIPLAYER_OK clients=2 lateJoin=true retracking=true");
        } finally {
            for (Process client : clients) {
                if (client.isAlive()) {
                    client.destroyForcibly();
                    client.waitFor(10, TimeUnit.SECONDS);
                }
            }
            if (server.isAlive()) {
                server.destroyForcibly();
                server.waitFor(10, TimeUnit.SECONDS);
            }
        }
    }

    private static Process startClient(
            Path java, Path minecraftRoot, Path versionDir, Path natives, List<Path> classpath, JsonNode manifest,
            Path gameDir, String username, String uuid, String logName
    ) throws Exception {
        String versionId = versionDir.getFileName().toString();
        String nativePath = natives.toString();
        List<String> command = new ArrayList<>(List.of(
                java.toString(), "--sun-misc-unsafe-memory-access=allow", "--enable-native-access=ALL-UNNAMED",
                "-Djava.library.path=" + nativePath, "-Djna.tmpdir=" + nativePath,
                "-Dorg.lwjgl.system.SharedLibraryExtractPath=" + nativePath,
                "-Dio.netty.native.workdir=" + nativePath,
                "-Dminecraft.launcher.brand=mineagent-production-smoke", "-Dminecraft.launcher.version=1.0",
                "-Djava.net.preferIPv6Addresses=system", "-DlibraryDirectory=" + minecraftRoot.resolve("libraries"),
                "--add-opens=java.base/java.lang.invoke=ALL-UNNAMED",
                "--add-exports=jdk.naming.dns/com.sun.jndi.dns=java.naming",
                "-Dmineagent.clientSmokeTest=true", "-Dmineagent.multiplayerSmokeClient=true",
                "-Dmineagent.productionYsmMultiplayerSmokeTest=true",
                "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8", "-Xmx3G", "-cp",
                String.join(System.getProperty("path.separator"), classpath.stream().map(Path::toString).toList()),
                "net.neoforged.fml.startup.Client", "--username", username, "--version", versionId,
                "--gameDir", gameDir.toString(), "--assetsDir", minecraftRoot.resolve("assets").toString(),
                "--assetIndex", manifest.path("assetIndex").path("id").asText("30"), "--uuid", uuid,
                "--accessToken", "0", "--clientId", "0", "--xuid", "0", "--versionType", "release",
                "--quickPlayMultiplayer=127.0.0.1:25565", "--fml.neoForgeVersion", "26.1.2.106",
                "--fml.mcVersion", "26.1.2", "--fml.neoFormVersion", "1"
        ));
        return new ProcessBuilder(command).directory(gameDir.toFile()).redirectErrorStream(true)
                .redirectOutput(gameDir.resolve(logName).toFile()).start();
    }

    private static List<String> serverCommand(Path dist, Path java) {
        return List.of(java.toString(), "-Dmineagent.multiplayerSmokeServer=true",
                "-Dmineagent.productionYsmMultiplayerSmokeTest=true", "-Dfile.encoding=UTF-8",
                "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8", "@" + dist.resolve("user_jvm_args.txt"),
                "-Dmineagent.smokeTest=false",
                "@" + dist.resolve("libraries/net/neoforged/neoforge/26.1.2.106/win_args.txt"), "--nogui");
    }

    private static void prepareServer(Path dist, Path modJar, Path ysmJar) throws Exception {
        if (!Files.isDirectory(dist) || !"run-production-server-dist".equals(dist.getFileName().toString())) {
            throw new IllegalArgumentException("Invalid server distribution");
        }
        for (String child : List.of("world", "mineagent-runtime-data", "config", "logs", "mods")) {
            deleteTree(dist.resolve(child), dist);
        }
        Path mods = Files.createDirectories(dist.resolve("mods"));
        Files.copy(modJar, mods.resolve(modJar.getFileName()), StandardCopyOption.REPLACE_EXISTING);
        Files.copy(ysmJar, mods.resolve(ysmJar.getFileName()), StandardCopyOption.REPLACE_EXISTING);
        Files.writeString(dist.resolve("eula.txt"), "eula=true\n", StandardCharsets.UTF_8);
        Files.writeString(dist.resolve("server.properties"), "online-mode=false\nserver-port=25565\n"
                + "level-name=world\nview-distance=10\nsimulation-distance=10\n", StandardCharsets.UTF_8);
    }

    private static Path prepareClient(Path dir, Path modJar, Path ysmJar) throws Exception {
        deleteTree(dir, dir.getParent());
        Files.createDirectories(dir);
        Path mods = Files.createDirectories(dir.resolve("mods"));
        Files.copy(modJar, mods.resolve(modJar.getFileName()), StandardCopyOption.REPLACE_EXISTING);
        Files.copy(ysmJar, mods.resolve(ysmJar.getFileName()), StandardCopyOption.REPLACE_EXISTING);
        Files.writeString(dir.resolve("options.txt"), "onboardAccessibility:false\nstartedCleanly:true\n",
                StandardCharsets.UTF_8);
        return dir;
    }

    private static void waitForMarker(Process process, Path log, String marker, int seconds) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (System.nanoTime() < deadline && process.isAlive()) {
            if (Files.exists(log) && Files.readString(log, StandardCharsets.UTF_8).contains(marker)) {
                return;
            }
            Thread.sleep(250);
        }
        throw new IllegalStateException("Timed out waiting for " + marker + " in " + log);
    }

    private static void waitSuccess(Process process, int seconds, String label) throws Exception {
        if (!process.waitFor(seconds, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException(label + " timed out");
        }
        if (process.exitValue() != 0) {
            throw new IllegalStateException(label + " exited " + process.exitValue());
        }
    }

    private static void require(String log, String... markers) {
        for (String marker : markers) {
            if (!log.contains(marker)) {
                throw new IllegalStateException("Missing multiplayer marker: " + marker);
            }
        }
    }

    private static void verifyYsm(Path jar) throws Exception {
        String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-512")
                .digest(Files.readAllBytes(jar)));
        if (!YSM_SHA512.equals(actual)) {
            throw new IllegalStateException("Pinned YSM checksum mismatch");
        }
    }

    private static void deleteTree(Path target, Path root) throws Exception {
        target = target.toAbsolutePath().normalize();
        root = root.toAbsolutePath().normalize();
        if (!target.startsWith(root) || target.equals(root)) {
            throw new IllegalArgumentException("Unsafe reset path " + target);
        }
        if (Files.exists(target)) {
            try (var paths = Files.walk(target)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    private static Path requiredFile(Path path) {
        path = path.toAbsolutePath().normalize();
        if (!Files.isRegularFile(path)) throw new IllegalArgumentException("Missing file " + path);
        return path;
    }

    private static Path requiredDirectory(Path path) {
        path = path.toAbsolutePath().normalize();
        if (!Files.isDirectory(path)) throw new IllegalArgumentException("Missing directory " + path);
        return path;
    }
}
