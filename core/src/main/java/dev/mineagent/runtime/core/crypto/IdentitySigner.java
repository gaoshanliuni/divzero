package dev.mineagent.runtime.core.crypto;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.HexFormat;

public final class IdentitySigner implements AutoCloseable {
    private static final String PRIVATE_FILE = "identity-private.pk8";
    private static final String PUBLIC_FILE = "identity-public.x509";
    private final PrivateKey privateKey;
    private final PublicKey publicKey;

    private IdentitySigner(PrivateKey privateKey, PublicKey publicKey) {
        this.privateKey = privateKey;
        this.publicKey = publicKey;
    }

    public static IdentitySigner open(Path directory) throws IOException, GeneralSecurityException {
        Path root = directory.toAbsolutePath().normalize();
        Files.createDirectories(root);
        Path privatePath = root.resolve(PRIVATE_FILE);
        Path publicPath = root.resolve(PUBLIC_FILE);
        boolean hasPrivate = Files.exists(privatePath);
        boolean hasPublic = Files.exists(publicPath);
        if (hasPrivate != hasPublic) {
            throw new GeneralSecurityException("incomplete persisted Ed25519 identity");
        }
        if (!hasPrivate) {
            KeyPair generated = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            writeAtomic(privatePath, generated.getPrivate().getEncoded());
            try {
                writeAtomic(publicPath, generated.getPublic().getEncoded());
            } catch (Exception failure) {
                Files.deleteIfExists(privatePath);
                throw failure;
            }
        }
        KeyFactory factory = KeyFactory.getInstance("Ed25519");
        PrivateKey privateKey = factory.generatePrivate(new PKCS8EncodedKeySpec(Files.readAllBytes(privatePath)));
        PublicKey publicKey = factory.generatePublic(new X509EncodedKeySpec(Files.readAllBytes(publicPath)));
        byte[] proof = "mineagent-identity-proof".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] signature = sign(privateKey, proof);
        if (!verify(publicKey.getEncoded(), proof, signature)) {
            throw new GeneralSecurityException("Ed25519 identity key pair does not match");
        }
        return new IdentitySigner(privateKey, publicKey);
    }

    public byte[] sign(byte[] payload) throws GeneralSecurityException {
        return sign(privateKey, payload);
    }

    public byte[] publicKeyEncoded() {
        return publicKey.getEncoded().clone();
    }

    public String fingerprint() {
        try {
            return HexFormat.of().withUpperCase().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(publicKey.getEncoded()));
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    public static boolean verify(byte[] publicKeyEncoded, byte[] payload, byte[] signature)
            throws GeneralSecurityException {
        PublicKey publicKey = KeyFactory.getInstance("Ed25519")
                .generatePublic(new X509EncodedKeySpec(publicKeyEncoded));
        Signature verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(publicKey);
        verifier.update(payload);
        return verifier.verify(signature);
    }

    private static byte[] sign(PrivateKey key, byte[] payload) throws GeneralSecurityException {
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(key);
        signer.update(payload);
        return signer.sign();
    }

    private static void writeAtomic(Path target, byte[] content) throws IOException {
        Path temporary = Files.createTempFile(target.getParent(), ".identity-", ".tmp");
        try {
            Files.write(temporary, content);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, target);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    @Override
    public void close() {
        // JCA private keys do not expose a portable destruction API.
    }
}
