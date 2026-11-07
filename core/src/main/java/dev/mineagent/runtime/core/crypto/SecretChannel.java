package dev.mineagent.runtime.core.crypto;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.KDF;
import javax.crypto.SecretKey;
import javax.crypto.spec.HKDFParameterSpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.X509EncodedKeySpec;

public final class SecretChannel {
    private static final byte[] CONTEXT = "MineAgentSecretChannelV2".getBytes(StandardCharsets.UTF_8);
    private static final byte VERSION=2;
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
        cipher.updateAAD(CONTEXT);
        byte[] ciphertext = cipher.doFinal(cleartext.getBytes(StandardCharsets.UTF_8));
        byte[] versioned=new byte[ciphertext.length+1];versioned[0]=VERSION;System.arraycopy(ciphertext,0,versioned,1,ciphertext.length);
        return new SecretEnvelope(encodedPublic, nonce, versioned);
    }

    public static String open(PrivateKey serverPrivateKey, SecretEnvelope envelope) throws GeneralSecurityException {
        byte[] sealed=envelope.ciphertext();if(sealed.length<18||sealed[0]!=VERSION)throw new GeneralSecurityException("SECRET_ENVELOPE_VERSION");
        PublicKey clientPublicKey = KeyFactory.getInstance("X25519")
                .generatePublic(new X509EncodedKeySpec(envelope.ephemeralPublicKey()));
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, deriveKey(serverPrivateKey, clientPublicKey),
                new GCMParameterSpec(128, envelope.nonce()));
        cipher.updateAAD(envelope.ephemeralPublicKey());
        cipher.updateAAD(CONTEXT);
        return new String(cipher.doFinal(sealed,1,sealed.length-1), StandardCharsets.UTF_8);
    }

    private static SecretKey deriveKey(PrivateKey privateKey, PublicKey publicKey) throws GeneralSecurityException {
        KeyAgreement agreement = KeyAgreement.getInstance("X25519");
        agreement.init(privateKey);
        agreement.doPhase(publicKey, true);
        byte[] shared=agreement.generateSecret();try{return KDF.getInstance("HKDF-SHA256").deriveKey("AES",HKDFParameterSpec.ofExtract().addIKM(shared).addSalt(CONTEXT).thenExpand(CONTEXT,32));}finally{java.util.Arrays.fill(shared,(byte)0);}
    }
}
