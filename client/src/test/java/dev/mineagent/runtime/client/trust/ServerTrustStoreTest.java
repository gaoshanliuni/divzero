package dev.mineagent.runtime.client.trust;

import dev.mineagent.runtime.core.crypto.IdentitySigner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ServerTrustStoreTest {
    @TempDir
    Path temporaryDirectory;

    @Test void oldConfirmationRecognizesTheSameFullKeyWithoutDerivingItsLegacyDigest()throws Exception{
        try(var identity=IdentitySigner.open(temporaryDirectory.resolve("legacy-key"));var different=IdentitySigner.open(temporaryDirectory.resolve("different-key"))){
            Path file=temporaryDirectory.resolve("legacy-trust.properties");var properties=new java.util.Properties();String prefix=java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("server".getBytes(StandardCharsets.UTF_8));
            properties.setProperty(prefix+".fingerprint","C".repeat(64));properties.setProperty(prefix+".publicKey",java.util.Base64.getEncoder().encodeToString(identity.publicKeyEncoded()));try(var out=java.nio.file.Files.newOutputStream(file)){properties.store(out,"legacy fixture");}
            var trust=new ServerTrustStore(file);assertEquals(TrustStatus.TRUSTED,trust.status("server",identity.fingerprint()));assertEquals(TrustStatus.MISMATCH,trust.status("server",different.fingerprint()));
            trust.confirm("server",identity.fingerprint(),identity.publicKeyEncoded());assertEquals("c".repeat(64),trust.trustedFingerprint("server"));assertEquals(TrustStatus.TRUSTED,new ServerTrustStore(file).status("server",identity.fingerprint()));
        }
    }

    @Test
    void requiresConfirmationThenVerifiesSignatureAcrossRestart() throws Exception {
        byte[] payload = "snapshot".getBytes(StandardCharsets.UTF_8);
        try (var identity = IdentitySigner.open(temporaryDirectory.resolve("identity"))) {
            byte[] signature = identity.sign(payload);
            Path trustFile = temporaryDirectory.resolve("trust.properties");
            var store = new ServerTrustStore(trustFile);

            assertEquals(TrustStatus.UNKNOWN, store.status("localhost:25565", identity.fingerprint()));
            store.confirm("localhost:25565", identity.fingerprint(), identity.publicKeyEncoded());
            assertEquals(TrustStatus.TRUSTED, store.status("localhost:25565", identity.fingerprint()));
            assertTrue(store.verify("localhost:25565", payload, signature));

            var reopened = new ServerTrustStore(trustFile);
            assertTrue(reopened.verify("localhost:25565", payload, signature));
            assertEquals(TrustStatus.MISMATCH, reopened.status("localhost:25565", "different"));
        }
    }
}
