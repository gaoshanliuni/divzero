package dev.mineagent.runtime.client.trust;

import dev.mineagent.runtime.core.crypto.IdentitySigner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;


import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TrustedClientPackageStoreTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void acceptsOnlyExactSourceSignedByConfirmedServer() throws Exception {
        String source = "'client-ready';";
        String hash=dev.mineagent.runtime.api.packages.ResourceIds.create();
        try (var identity = IdentitySigner.open(temporaryDirectory.resolve("identity"))) {
            byte[] signature = dev.mineagent.runtime.core.crypto.SourceSignature.sign(identity,hash,source);
            var trust = new ServerTrustStore(temporaryDirectory.resolve("trust.properties"));
            var packages = new TrustedClientPackageStore(temporaryDirectory.resolve("packages.properties"));
            UUID packageId = UUID.randomUUID();

            assertEquals(ClientPackageTrustStatus.SERVER_UNTRUSTED, packages.accept(
                    trust, "server", identity.fingerprint(), packageId, 1, source, hash, signature));
            trust.confirm("server", identity.fingerprint(), identity.publicKeyEncoded());
            assertEquals(ClientPackageTrustStatus.ACCEPTED, packages.accept(
                    trust, "server", identity.fingerprint(), packageId, 1, source, hash, signature));
            assertEquals(ClientPackageTrustStatus.SIGNATURE_INVALID, packages.accept(
                    trust, "server", identity.fingerprint(), packageId, 2, source + "x", hash, signature));

            var reopened = new TrustedClientPackageStore(temporaryDirectory.resolve("packages.properties"));
            assertEquals(hash, reopened.acceptedHash("server", packageId).orElseThrow());
        }
    }
}
