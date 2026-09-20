package dev.mineagent.runtime.core.crypto;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.X509EncodedKeySpec;

public final class SecretChannel {
    private static final byte[] CONTEXT = "MineAgentSecretChannelV1".getBytes(StandardCharsets.UTF_8);
    private static final SecureRandom RANDOM = new SecureRandom();

    private SecretChannel() {
    }

    public static KeyPair generateServerKeyPair() throws GeneralSecurityException {
        return KeyPairGenerator.getInstance("X25519").generateKeyPair();
    }

    public static SecretEnvelope seal(PublicKey serverPublicKey, String cleartext) throws GeneralSecurityException {
        KeyPair ephemeral = generateServerKeyPair();
        byte[] encodedPublic = ephemeral.getPublic().getEncoded();
        byte[] nonce = new byte[12];
        RANDOM.nextBytes(nonce);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(ephemeral.getPrivate(), serverPublicKey), new GCMParameterSpec(128, nonce));
        cipher.updateAAD(encodedPublic);
        byte[] ciphertext = cipher.doFinal(cleartext.getBytes(StandardCharsets.UTF_8));
        return new SecretEnvelope(encodedPublic, nonce, ciphertext);
    }

    public static String open(PrivateKey serverPrivateKey, SecretEnvelope envelope) throws GeneralSecurityException {
        PublicKey clientPublicKey = KeyFactory.getInstance("X25519")
                .generatePublic(new X509EncodedKeySpec(envelope.ephemeralPublicKey()));
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, deriveKey(serverPrivateKey, clientPublicKey),
                new GCMParameterSpec(128, envelope.nonce()));
        cipher.updateAAD(envelope.ephemeralPublicKey());
        return new String(cipher.doFinal(envelope.ciphertext()), StandardCharsets.UTF_8);
    }

    private static SecretKeySpec deriveKey(PrivateKey privateKey, PublicKey publicKey) throws GeneralSecurityException {
        KeyAgreement agreement = KeyAgreement.getInstance("X25519");
        agreement.init(privateKey);
        agreement.doPhase(publicKey, true);
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(CONTEXT);
        return new SecretKeySpec(digest.digest(agreement.generateSecret()), "AES");
    }
}
