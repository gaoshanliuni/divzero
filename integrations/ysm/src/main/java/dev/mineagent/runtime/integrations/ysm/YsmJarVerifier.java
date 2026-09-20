package dev.mineagent.runtime.integrations.ysm;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

public final class YsmJarVerifier {
    public static final String MODRINTH_VERSION_ID = "1rPJlKDJ";
    public static final String PINNED_SHA512 =
            "b5e2445022e6c071b49c7eb312bc2e1628980a615506414a6ced06950999b8f6"
                    + "27829cebe848bd8a2dc025c121abfb4286221bb641ad7648b13446e78dc07df3";
    private final String expectedSha512;

    public YsmJarVerifier() {
        this(PINNED_SHA512);
    }

    public YsmJarVerifier(String expectedSha512) {
        if (expectedSha512 == null || !expectedSha512.matches("[0-9a-fA-F]{128}")) {
            throw new IllegalArgumentException("invalid expected SHA-512");
        }
        this.expectedSha512 = expectedSha512.toLowerCase(java.util.Locale.ROOT);
    }

    public boolean verify(Path jar) {
        try {
            return Files.isRegularFile(jar) && expectedSha512.equals(sha512(jar));
        } catch (Exception failure) {
            return false;
        }
    }

    public static String sha512(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-512");
        try (var input = Files.newInputStream(path)) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                if (count > 0) {
                    digest.update(buffer, 0, count);
                }
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
