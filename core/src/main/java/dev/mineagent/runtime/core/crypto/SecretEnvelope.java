package dev.mineagent.runtime.core.crypto;

public record SecretEnvelope(byte[] ephemeralPublicKey, byte[] nonce, byte[] ciphertext) {
    public SecretEnvelope {
        ephemeralPublicKey = ephemeralPublicKey.clone();
        nonce = nonce.clone();
        ciphertext = ciphertext.clone();
    }

    @Override
    public byte[] ephemeralPublicKey() {
        return ephemeralPublicKey.clone();
    }

    @Override
    public byte[] nonce() {
        return nonce.clone();
    }

    @Override
    public byte[] ciphertext() {
        return ciphertext.clone();
    }
}
