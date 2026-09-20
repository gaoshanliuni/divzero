package dev.mineagent.runtime.neoforge.client.ysm;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Render-thread accounting separated from Minecraft and YSM types so frame semantics stay deterministic.
 */
final class YsmRenderLedger {
    static final int MAX_CACHED_ENTITIES = 64;

    private static final byte YSM_DRAW_RECORDED = 1;
    private static final byte VANILLA_DRAW_RECORDED = 2;
    private static final byte CALLBACK_RECORDED = 4;

    private final Map<UUID, Byte> currentFrameEntities = new HashMap<>();
    private int uniqueFrames;
    private int ysmCalls;
    private int vanillaCalls;
    private int callbackCount;
    private int duplicateDraws;
    private int duplicateCallbacks;
    private int cacheOverflowCount;
    private long firstFrameNanos;
    private long lastFrameNanos;

    synchronized void beginFrame(long timestampNanos) {
        currentFrameEntities.clear();
        uniqueFrames++;
        if (uniqueFrames == 1) {
            firstFrameNanos = timestampNanos;
        }
        lastFrameNanos = timestampNanos;
    }

    synchronized void recordYsmDraw(UUID entityId) {
        recordDraw(entityId, true);
    }

    synchronized void recordVanillaDraw(UUID entityId) {
        recordDraw(entityId, false);
    }

    synchronized void recordYsmCallback(UUID entityId) {
        callbackCount++;
        if (entityId == null) {
            return;
        }
        Byte flags = currentFrameEntities.get(entityId);
        if (flags == null && currentFrameEntities.size() >= MAX_CACHED_ENTITIES) {
            cacheOverflowCount++;
            return;
        }
        byte value = flags == null ? 0 : flags;
        if ((value & CALLBACK_RECORDED) != 0) {
            duplicateCallbacks++;
            return;
        }
        currentFrameEntities.put(entityId, (byte) (value | CALLBACK_RECORDED));
    }

    synchronized void reset() {
        currentFrameEntities.clear();
        uniqueFrames = 0;
        ysmCalls = 0;
        vanillaCalls = 0;
        callbackCount = 0;
        duplicateDraws = 0;
        duplicateCallbacks = 0;
        cacheOverflowCount = 0;
        firstFrameNanos = 0;
        lastFrameNanos = 0;
    }

    synchronized Snapshot snapshot() {
        return new Snapshot(uniqueFrames, ysmCalls, vanillaCalls, callbackCount,
                duplicateDraws, duplicateCallbacks, currentFrameEntities.size(),
                MAX_CACHED_ENTITIES, cacheOverflowCount, firstFrameNanos, lastFrameNanos);
    }

    private void recordDraw(UUID entityId, boolean ysm) {
        if (entityId == null) {
            return;
        }
        Byte flags = currentFrameEntities.get(entityId);
        if (flags == null && currentFrameEntities.size() >= MAX_CACHED_ENTITIES) {
            cacheOverflowCount++;
            return;
        }
        byte value = flags == null ? 0 : flags;
        byte drawKind = ysm ? YSM_DRAW_RECORDED : VANILLA_DRAW_RECORDED;
        if ((value & (YSM_DRAW_RECORDED | VANILLA_DRAW_RECORDED)) != 0) {
            duplicateDraws++;
        }
        if ((value & drawKind) != 0) {
            return;
        }
        currentFrameEntities.put(entityId, (byte) (value | drawKind));
        if (ysm) {
            ysmCalls++;
        } else {
            vanillaCalls++;
        }
    }

    record Snapshot(
            int uniqueFrames,
            int ysmCalls,
            int vanillaCalls,
            int callbackCount,
            int duplicateDraws,
            int duplicateCallbacks,
            int boundedCacheSize,
            int cacheCapacity,
            int cacheOverflowCount,
            long firstFrameNanos,
            long lastFrameNanos
    ) {
        double averageFrameMillis() {
            return uniqueFrames < 2
                    ? 0
                    : (lastFrameNanos - firstFrameNanos) / 1_000_000.0 / (uniqueFrames - 1);
        }
    }
}
