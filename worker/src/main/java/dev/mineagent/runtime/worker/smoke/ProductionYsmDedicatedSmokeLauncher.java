package dev.mineagent.runtime.worker.smoke;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;

public final class ProductionYsmDedicatedSmokeLauncher {
    private static final String YSM_SHA512 =
            "b5e2445022e6c071b49c7eb312bc2e1628980a615506414a6ced06950999b8f6"
                    + "27829cebe848bd8a2dc025c121abfb4286221bb641ad7648b13446e78dc07df3";

    private ProductionYsmDedicatedSmokeLauncher() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 4) {
            throw new IllegalArgumentException("Expected <serverDist> <modJar> <ysmJar> <javaHome>");
        }
        Path distribution = Path.of(args[0]).toAbsolutePath().normalize();
        if (!Files.isDirectory(distribution)
                || !"run-production-server-dist".equals(distribution.getFileName().toString())) {
            throw new IllegalArgumentException("Invalid dedicated smoke distribution: " + distribution);
        }
        Path modJar = requiredFile(args[1]);
        Path ysmJar = requiredFile(args[2]);
        Path java = requiredFile(Path.of(args[3]).resolve("bin/java.exe").toString());
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-512")
                .digest(Files.readAllBytes(ysmJar)));
        if (!YSM_SHA512.equals(hash)) {
            throw new IllegalStateException("Pinned YSM checksum mismatch");
        }
        resetChild(distribution, "world");
        resetChild(distribution, "mineagent-runtime-data");
        resetChild(distribution, "config");
        resetChild(distribution, "logs");
        Path mods = Files.createDirectories(distribution.resolve("mods"));
        Files.copy(modJar, mods.resolve(modJar.getFileName()), StandardCopyOption.REPLACE_EXISTING);
        Files.copy(ysmJar, mods.resolve(ysmJar.getFileName()), StandardCopyOption.REPLACE_EXISTING);
        extractDefaultYsmFixture(ysmJar, distribution.resolve("config/yes_steve_model/builtin/default"));
        Files.writeString(distribution.resolve("eula.txt"), "eula=true\n", StandardCharsets.UTF_8);
        Path userArgs = requiredFile(distribution.resolve("user_jvm_args.txt").toString());
        Path winArgs = requiredFile(distribution.resolve(
                "libraries/net/neoforged/neoforge/26.1.2.106/win_args.txt").toString());
        Path log = distribution.resolve("production-ysm-dedicated-smoke.log");
        var command = List.of(
                java.toString(),
                "-Dmineagent.smokeTest=true",
                "-Dmineagent.productionYsmDedicatedSmokeTest=true",
                "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8",
                "@" + userArgs, "@" + winArgs, "--nogui"
        );
        Process process = new ProcessBuilder(command).directory(distribution.toFile())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        if (!process.waitFor(4, TimeUnit.MINUTES)) {
            process.destroyForcibly();
            throw new IllegalStateException("Dedicated YSM smoke timed out");
        }
        String output = Files.readString(log, StandardCharsets.UTF_8);
        if (process.exitValue() != 0) {
            throw new IllegalStateException("Dedicated YSM smoke exited " + process.exitValue() + "; see " + log);
        }
        verifyLog(output);
        System.out.println("MINEAGENT_PRODUCTION_YSM_DEDICATED_SMOKE_OK log=" + log);
    }

    static void verifyLog(String log) {
        for (String marker : List.of(
                "DEDICATED_SERVER in PROD",
                "MINEAGENT_SMOKE_YSM_RUNTIME_READY",
                "MINEAGENT_SMOKE_YSM_DEDICATED_DIMENSION_OK",
                "MINEAGENT_SMOKE_YSM_DEDICATED_LIFECYCLE_OK dimension=true respawn=true "
                        + "initialMissingAssetDiagnosed=true",
                "MINEAGENT_SMOKE_BODY_TICK_OK")) {
            if (!log.contains(marker)) {
                throw new IllegalStateException("Dedicated YSM smoke missing marker: " + marker);
            }
        }
        if (log.contains("Failed to load native lib") || log.contains("err: 54")) {
            throw new IllegalStateException("Dedicated YSM native failed");
        }
    }

    private static void resetChild(Path distribution, String child) throws Exception {
        Path target = distribution.resolve(child).toAbsolutePath().normalize();
        if (!target.startsWith(distribution) || target.equals(distribution)) {
            throw new IllegalArgumentException("Unsafe dedicated reset path: " + target);
        }
        if (Files.exists(target)) {
            try (var paths = Files.walk(target)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    private static void extractDefaultYsmFixture(Path ysmJar, Path targetDirectory) throws Exception {
        String prefix = "assets/yes_steve_model/builtin/default/";
        Files.createDirectories(targetDirectory);
        try (var zip = new java.util.zip.ZipFile(ysmJar.toFile())) {
            var entries = zip.entries();
            int files = 0;
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                if (entry.isDirectory() || !entry.getName().startsWith(prefix)) {
                    continue;
                }
                Path relative = Path.of(entry.getName().substring(prefix.length())).normalize();
                Path target = targetDirectory.resolve(relative).normalize();
                if (!target.startsWith(targetDirectory)) {
                    throw new IllegalStateException("Unsafe YSM fixture entry: " + entry.getName());
                }
                Files.createDirectories(target.getParent());
                try (var input = zip.getInputStream(entry)) {
                    Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
                }
                files++;
            }
            if (files < 4 || !Files.isRegularFile(targetDirectory.resolve("ysm.json"))) {
                throw new IllegalStateException("Pinned YSM default fixture is incomplete");
            }
        }
    }

    private static Path requiredFile(String value) {
        Path path = Path.of(value).toAbsolutePath().normalize();
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("Required file is missing: " + path);
        }
        return path;
    }
}
