package dev.mineagent.runtime.neoforge.client.ysm;

import net.minecraft.client.Minecraft;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;

public final class YsmRenderObserver {
    private static final String PLAYER_DATA = "com.elfmcys.yesstevemodel.O0OoOoO0oo0O00O0ooo0o0OO";
    private static final String GET_PLAYER_DATA = "O0oo00O0OoooOOOO00ooO000";
    private static final String GET_MODEL = "o00O0ooooooOoo0oOO0oo0O0";
    private static final String GET_TEXTURE = "O0ooO0OoOo00ooo00oO0OoO0";
    private static final String GET_ANIMATION = "Ooooo00oo0OooooO00o000O0";
    private static final String IS_RENDER_READY = "O0oo00O0OoooOOOO00ooO000";
    private static final YsmRenderLedger LEDGER = new YsmRenderLedger();
    private static volatile UUID expectedEntity;
    private static volatile ClientState clientState;

    private YsmRenderObserver() {
    }

    public static void expect(UUID entityId) {
        expectedEntity = entityId;
        clientState = null;
        LEDGER.reset();
    }

    public static void reset() {
        expectedEntity = null;
        clientState = null;
        LEDGER.reset();
    }

    public static void beginFrame(long timestampNanos) {
        LEDGER.beginFrame(timestampNanos);
    }

    public static void recordYsmInvocation(Object ysmWrapper) {
        resolveRenderedEntityId(ysmWrapper).filter(YsmRenderObserver::expected).ifPresent(id -> {
            int before = LEDGER.snapshot().ysmCalls();
            LEDGER.recordYsmDraw(id);
            if (before == 0 && LEDGER.snapshot().ysmCalls() == 1) {
                dev.mineagent.runtime.neoforge.MineAgentRuntimeMod.LOGGER.info(
                        "MINEAGENT_YSM_RENDER_INVOKED entity={}", id);
            }
            var minecraft = Minecraft.getInstance();
            if (minecraft.level != null) {
                Object entity = minecraft.level.getEntity(id);
                readClientState(entity).ifPresent(state -> clientState = state);
            }
        });
    }

    /** Records optional YSM mixin entry without declaring that an authoritative draw completed. */
    public static void recordYsmCallback(Object ysmWrapper) {
        var before = LEDGER.snapshot();
        UUID id = resolveRenderedEntityId(ysmWrapper).filter(YsmRenderObserver::expected).orElse(null);
        LEDGER.recordYsmCallback(id);
        if (before.callbackCount() == 0) {
            dev.mineagent.runtime.neoforge.MineAgentRuntimeMod.LOGGER.info(
                    "MINEAGENT_YSM_RENDER_MIXIN_INVOKED wrapper={}",
                    ysmWrapper == null ? "null" : ysmWrapper.getClass().getName());
        }
    }

    public static void recordVanillaInvocation(int entityId) {
        var minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }
        var entity = minecraft.level.getEntity(entityId);
        if (entity != null && expected(entity.getUUID())) {
            LEDGER.recordVanillaDraw(entity.getUUID());
        }
    }

    public static Snapshot snapshot() {
        var accounting = LEDGER.snapshot();
        return new Snapshot(expectedEntity, accounting.callbackCount(), accounting.ysmCalls(),
                accounting.vanillaCalls(), Optional.ofNullable(clientState),
                accounting.firstFrameNanos(), accounting.lastFrameNanos(), accounting.uniqueFrames(),
                accounting.duplicateDraws(), accounting.duplicateCallbacks(), accounting.boundedCacheSize(),
                accounting.cacheCapacity(), accounting.cacheOverflowCount());
    }

    public static Optional<ClientState> inspectExpectedClientState() {
        var minecraft = Minecraft.getInstance();
        if (minecraft.level == null || expectedEntity == null) {
            return Optional.empty();
        }
        Object entity = minecraft.level.getEntity(expectedEntity);
        Optional<ClientState> state = readClientState(entity);
        state.ifPresent(value -> clientState = value);
        return state;
    }

    static Optional<UUID> resolveRenderedEntityId(Object ysmWrapper) {
        if (ysmWrapper == null) {
            return Optional.empty();
        }
        try {
            Method modelAccessor = ysmWrapper.getClass().getMethod("O0oo00O0OoooOOOO00ooO000");
            modelAccessor.setAccessible(true);
            Object model = modelAccessor.invoke(ysmWrapper);
            Method entityAccessor = model.getClass().getMethod("ooo00ooOOooOOo0O00OO000o");
            entityAccessor.setAccessible(true);
            Object entity = entityAccessor.invoke(model);
            Method uuidAccessor = entity.getClass().getMethod("getUUID");
            uuidAccessor.setAccessible(true);
            return Optional.of((UUID) uuidAccessor.invoke(entity));
        } catch (ReflectiveOperationException | RuntimeException failure) {
            return Optional.empty();
        }
    }

    private static Optional<ClientState> readClientState(Object player) {
        if (player == null) {
            return Optional.empty();
        }
        try {
            ClassLoader loader = Thread.currentThread().getContextClassLoader();
            Class<?> dataType = Class.forName(PLAYER_DATA, false, loader);
            Method getData = Arrays.stream(dataType.getMethods())
                    .filter(method -> method.getName().equals(GET_PLAYER_DATA) && method.getParameterCount() == 1)
                    .filter(method -> java.lang.reflect.Modifier.isStatic(method.getModifiers()))
                    .findFirst().orElseThrow(NoSuchMethodException::new);
            Object optional = getData.invoke(null, player);
            if (!(optional instanceof Optional<?> value) || value.isEmpty()) {
                return Optional.empty();
            }
            Object data = value.orElseThrow();
            String model = (String) data.getClass().getMethod(GET_MODEL).invoke(data);
            String texture = (String) data.getClass().getMethod(GET_TEXTURE).invoke(data);
            String animation = (String) data.getClass().getMethod(GET_ANIMATION).invoke(data);
            boolean ready = Boolean.TRUE.equals(data.getClass().getMethod(IS_RENDER_READY).invoke(data));
            return Optional.of(new ClientState(model, texture, animation, ready));
        } catch (ReflectiveOperationException | LinkageError | RuntimeException failure) {
            return Optional.empty();
        }
    }

    private static boolean expected(UUID id) {
        return id != null && id.equals(expectedEntity);
    }

    public static boolean isManagedAgent(UUID id) {
        if (expected(id)) {
            return true;
        }
        var values = dev.mineagent.runtime.neoforge.network.PanelSnapshotInbox.snapshot().values();
        int count;
        try {
            count = Integer.parseInt(values.getOrDefault("agent.count", "0"));
        } catch (NumberFormatException invalid) {
            return false;
        }
        for (int index = 0; index < Math.min(count, 4); index++) {
            if (String.valueOf(id).equals(values.get("agent." + index + ".id"))) {
                return true;
            }
        }
        return false;
    }

    public record ClientState(String modelId, String textureId, String animationId, boolean renderReady) {
    }

    public record Snapshot(
            UUID entityId,
            int callbackCount,
            int ysmCalls,
            int vanillaCalls,
            Optional<ClientState> clientState,
            long firstFrameNanos,
            long lastFrameNanos,
            int uniqueFrames,
            int duplicateDraws,
            int duplicateCallbacks,
            int boundedCacheSize,
            int cacheCapacity,
            int cacheOverflowCount
    ) {
        public int rawYsmCalls() {
            return callbackCount;
        }

        public int uniqueYsmFrames() {
            return ysmCalls;
        }

        public long firstInvocationNanos() {
            return firstFrameNanos;
        }

        public long lastInvocationNanos() {
            return lastFrameNanos;
        }

        public double averageFrameMillis() {
            return uniqueFrames < 2 ? 0 : (lastFrameNanos - firstFrameNanos) / 1_000_000.0 / (uniqueFrames - 1);
        }
    }
}
