package dev.mineagent.runtime.core.crypto;

import org.junit.jupiter.api.Test;

import java.security.GeneralSecurityException;

import static org.junit.jupiter.api.Assertions.*;

class SecretChannelTest {
    @Test void rejectsUnknownEnvelopeVersionAndModifiedCiphertext()throws Exception{
        var keys=SecretChannel.generateServerKeyPair();var envelope=SecretChannel.seal(keys.getPublic(),"fixture-secret");byte[] changed=envelope.ciphertext();changed[0]=1;assertThrows(GeneralSecurityException.class,()->SecretChannel.open(keys.getPrivate(),new SecretEnvelope(envelope.ephemeralPublicKey(),envelope.nonce(),changed)));
        byte[] tampered=envelope.ciphertext();tampered[tampered.length-1]^=1;assertThrows(GeneralSecurityException.class,()->SecretChannel.open(keys.getPrivate(),new SecretEnvelope(envelope.ephemeralPublicKey(),envelope.nonce(),tampered)));
    }
    @Test
    void sealsSecretForServerUsingEphemeralX25519AndAesGcm() throws Exception {
        var serverKeys = SecretChannel.generateServerKeyPair();

        SecretEnvelope envelope = SecretChannel.seal(serverKeys.getPublic(), "api-secret");
        String cleartext = SecretChannel.open(serverKeys.getPrivate(), envelope);

        assertEquals("api-secret", cleartext);
        assertEquals(12, envelope.nonce().length);
        assertNotEquals("api-secret", new String(envelope.ciphertext(), java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test
    void wrongServerPrivateKeyCannotOpenEnvelope() throws Exception {
        var intendedServer = SecretChannel.generateServerKeyPair();
        var otherServer = SecretChannel.generateServerKeyPair();
        SecretEnvelope envelope = SecretChannel.seal(intendedServer.getPublic(), "api-secret");

        assertThrows(GeneralSecurityException.class,
                () -> SecretChannel.open(otherServer.getPrivate(), envelope));
    }
}
