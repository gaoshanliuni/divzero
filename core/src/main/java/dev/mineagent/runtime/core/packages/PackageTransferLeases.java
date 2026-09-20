package dev.mineagent.runtime.core.packages;
import java.time.Clock;
import java.util.*;
import java.util.function.Predicate;

/** Ephemeral download bytes only. A lease is bound to the real viewer + shell session and never grants gameplay rights. */
public final class PackageTransferLeases {
    public record Offer(UUID transferId, UUID packageId, long packageRevision, int size, String sha256, long expiresAt) {}
    private record Lease(UUID viewer, UUID session, Offer offer, byte[] bytes) {}
    private final Clock clock;private final int maximumBytes;
    private final Map<UUID, Lease> leases = new HashMap<>();
    public PackageTransferLeases(Clock clock) { this(clock,PackagePreviewBundle.MAX_BYTES); }
    public PackageTransferLeases(Clock clock,int maximumBytes){this.clock=Objects.requireNonNull(clock);if(maximumBytes<1||maximumBytes>ResourcePackPlan.MAX_BUNDLE)throw new IllegalArgumentException("PACKAGE_TRANSFER_LIMIT");this.maximumBytes=maximumBytes;}
    public synchronized Offer offer(UUID viewer, UUID session, UUID packageId, long revision, byte[] body) throws Exception {
        expire();
        if (body.length < 1 || body.length > maximumBytes || revision < 1) throw new IllegalArgumentException("UI_TRANSFER_BUDGET");
        release(viewer);
        if (leases.size() >= 4) throw new IllegalStateException("UI_TRANSFER_BUSY");
        var offer = new Offer(UUID.randomUUID(), packageId, revision, body.length, RuntimePackageCanonicalizer.sha256(body), clock.millis() + 120_000);
        leases.put(offer.transferId(), new Lease(viewer, session, offer, body.clone()));
        return offer;
    }
    public synchronized byte[] chunk(UUID viewer, UUID session, UUID transfer, int offset, Predicate<Offer> authorized) {
        expire();
        var lease = leases.get(transfer);
        if (lease == null || !lease.viewer.equals(viewer) || !lease.session.equals(session) || !authorized.test(lease.offer))
            throw new SecurityException("UI_TRANSFER_DENIED");
        if (offset < 0 || offset >= lease.bytes.length || offset % PackagePreviewBundle.CHUNK_BYTES != 0)
            throw new IllegalArgumentException("UI_CHUNK_RANGE");
        return Arrays.copyOfRange(lease.bytes, offset, Math.min(lease.bytes.length, offset + PackagePreviewBundle.CHUNK_BYTES));
    }
    public synchronized void release(UUID viewer) { leases.values().removeIf(l -> l.viewer.equals(viewer)); }
    public synchronized void release(UUID viewer,UUID session,UUID transfer){var lease=leases.get(transfer);if(lease!=null&&lease.viewer.equals(viewer)&&lease.session.equals(session))leases.remove(transfer);}
    public synchronized void clear() { leases.clear(); }
    public synchronized int count() { expire(); return leases.size(); }
    private void expire() { leases.values().removeIf(l -> l.offer.expiresAt <= clock.millis()); }
}
