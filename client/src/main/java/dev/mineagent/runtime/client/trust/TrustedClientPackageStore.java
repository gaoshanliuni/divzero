package dev.mineagent.runtime.client.trust;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;

public final class TrustedClientPackageStore {
    private final Path file;
    private final Properties values = new Properties();

    public TrustedClientPackageStore(Path file) throws IOException {
        this.file = java.util.Objects.requireNonNull(file, "file").toAbsolutePath().normalize();
        if (Files.exists(this.file)) {
            try (var input = Files.newInputStream(this.file)) {
                values.load(input);
            }
        }
    }

    public synchronized ClientPackageTrustStatus accept(
            ServerTrustStore trust,
            String serverId,
            String fingerprint,
            UUID packageId,
            long revision,
            String source,
            String sha256,
            byte[] signature
    ) throws Exception {
        if (trust == null || serverId == null || serverId.isBlank() || fingerprint == null
                || packageId == null || revision < 1 || source == null || source.isBlank()
                || source.length() > 1_000_000 || sha256 == null || !sha256.matches("[0-9a-f]{64}")
                || signature == null || signature.length < 32 || signature.length > 256) {
            return ClientPackageTrustStatus.INVALID;
        }
        if (trust.status(serverId, fingerprint) != TrustStatus.TRUSTED) {
            return ClientPackageTrustStatus.SERVER_UNTRUSTED;
        }
        String calculated = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(source.getBytes(StandardCharsets.UTF_8)));
        if (!calculated.equals(sha256)) {
            return ClientPackageTrustStatus.HASH_MISMATCH;
        }
        if (!trust.verify(serverId, sha256.getBytes(StandardCharsets.US_ASCII), signature)) {
            return ClientPackageTrustStatus.SIGNATURE_INVALID;
        }
        String prefix = key(serverId, packageId);
        long current = Long.parseLong(values.getProperty(prefix + ".revision", "0"));
        String currentHash = values.getProperty(prefix + ".sha256", "");
        if (revision < current || (revision == current && !currentHash.isEmpty() && !currentHash.equals(sha256))) {
            return ClientPackageTrustStatus.STALE_REVISION;
        }
        values.setProperty(prefix + ".revision", Long.toString(revision));
        values.setProperty(prefix + ".sha256", sha256);
        save();
        return ClientPackageTrustStatus.ACCEPTED;
    }

    public synchronized Optional<String> acceptedHash(String serverId, UUID packageId) {
        if (serverId == null || serverId.isBlank() || packageId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(values.getProperty(key(serverId, packageId) + ".sha256"));
    }

    private void save() throws IOException {
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        Path temporary = Files.createTempFile(file.getParent(), ".client-packages-", ".tmp");
        try {
            try (var output = Files.newOutputStream(temporary)) {
                values.store(output, "MineAgent trusted client packages");
            }
            try {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static String key(String serverId, UUID packageId) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(serverId.getBytes(StandardCharsets.UTF_8)) + "." + packageId;
    }
}
