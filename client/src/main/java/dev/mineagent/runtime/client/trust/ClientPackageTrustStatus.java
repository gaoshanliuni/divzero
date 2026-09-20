package dev.mineagent.runtime.client.trust;

public enum ClientPackageTrustStatus {
    ACCEPTED,
    SERVER_UNTRUSTED,
    HASH_MISMATCH,
    SIGNATURE_INVALID,
    STALE_REVISION,
    INVALID
}
