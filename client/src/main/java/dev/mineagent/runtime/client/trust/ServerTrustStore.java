package dev.mineagent.runtime.client.trust;

import dev.mineagent.runtime.core.crypto.IdentitySigner;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.Properties;

public final class ServerTrustStore {
    private final Path file;
    private final Properties values = new Properties();

    public ServerTrustStore(Path file) throws IOException {
        this.file = file.toAbsolutePath().normalize();
        if (Files.exists(this.file)) {
            try (var input = Files.newInputStream(this.file)) {
                values.load(input);
            }
        }
    }

    public synchronized TrustStatus status(String serverId, String fingerprint) {
        validateServer(serverId);
        if (fingerprint == null || fingerprint.isBlank()) {
            return TrustStatus.MISMATCH;
        }
        String trusted = values.getProperty(key(serverId, "fingerprint"));
        if (trusted == null) {
            return TrustStatus.UNKNOWN;
        }
        return trusted.equalsIgnoreCase(fingerprint) ? TrustStatus.TRUSTED : TrustStatus.MISMATCH;
    }
    public synchronized String trustedFingerprint(String serverId){validateServer(serverId);String value=values.getProperty(key(serverId,"fingerprint"));if(value==null||!value.matches("[a-fA-F0-9]{64}"))throw new IllegalStateException("SERVER_IDENTITY_NOT_TRUSTED");return value.toLowerCase(java.util.Locale.ROOT);}

    public synchronized void confirm(String serverId, String fingerprint, byte[] publicKey) throws IOException {
        validateServer(serverId);
        if (fingerprint == null || !fingerprint.matches("[0-9A-Fa-f]{64}")
                || publicKey == null || publicKey.length < 32 || publicKey.length > 256) {
            throw new IllegalArgumentException("invalid server identity");
        }
        values.setProperty(key(serverId, "fingerprint"), fingerprint.toUpperCase(java.util.Locale.ROOT));
        values.setProperty(key(serverId, "publicKey"), Base64.getEncoder().encodeToString(publicKey));
        save();
    }

    public synchronized boolean verify(String serverId, byte[] payload, byte[] signature) {
        validateServer(serverId);
        String encoded = values.getProperty(key(serverId, "publicKey"));
        if (encoded == null || payload == null || signature == null) {
            return false;
        }
        try {
            return IdentitySigner.verify(Base64.getDecoder().decode(encoded), payload, signature);
        } catch (GeneralSecurityException | IllegalArgumentException failure) {
            return false;
        }
    }

    private void save() throws IOException {
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        Path temporary = Files.createTempFile(file.getParent(), ".trust-", ".tmp");
        try {
            try (var output = Files.newOutputStream(temporary)) {
                values.store(output, "MineAgent trusted server identities");
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

    private static void validateServer(String serverId) {
        if (serverId == null || serverId.isBlank() || serverId.length() > 512) {
            throw new IllegalArgumentException("invalid server id");
        }
    }

    private static String key(String serverId, String field) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(serverId.getBytes(java.nio.charset.StandardCharsets.UTF_8)) + "." + field;
    }
}
