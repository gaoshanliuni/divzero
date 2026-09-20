package dev.mineagent.runtime.neoforge.integration;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Reflection boundary for the exact, checksum-pinned YSM 2.6.5 NeoForge build. */
final class PinnedYsmReflection {
    private static final String ENTRYPOINT = "com.elfmcys.yesstevemodel.YesSteveModel";
    private static final String PLAYER_DATA = "com.elfmcys.yesstevemodel.o0oo0000oooOo0ooOOOo0OOo";
    private static final String GET_PLAYER_DATA = "O0oo00O0OoooOOOO00ooO000";
    private static final String GET_MODEL = "O0oo00O0OoooOOOO00ooO000";
    private static final String GET_TEXTURE = "oo000Oo000o0Ooooooo00oOo";
    private static final String GET_ANIMATION_STATE = "Oo0o00OoooOoo00o00O00O0o";
    private static final String ACTIVE_ANIMATION = "oOo0O0Oooo0o0Oo0O0O00Ooo";
    private static final String MODEL_REGISTRY = "com.elfmcys.yesstevemodel.ooo000Oo0O0o0oOoO0O00oOO";
    private static final String MODEL_MAP = "OO0OoO00ooOOo0o00O000OoO";
    private static final String NETWORK = "com.elfmcys.yesstevemodel.OOOOo0ooOooOOOo0O00o0ooo";
    private static volatile String syncDiagnostic = "NOT_RUN";

    private PinnedYsmReflection() {
    }

    static boolean runtimeAvailable(ClassLoader classLoader) {
        try {
            Class<?> entrypoint = Class.forName(ENTRYPOINT, false, classLoader);
            return Boolean.TRUE.equals(entrypoint.getMethod("isAvailable").invoke(null));
        } catch (ReflectiveOperationException | LinkageError | SecurityException failure) {
            return false;
        }
    }

    static Optional<AppliedState> readAppliedState(Object serverPlayer, ClassLoader classLoader) {
        try {
            Class<?> dataType = Class.forName(PLAYER_DATA, false, classLoader);
            Method attachmentAccessor = Arrays.stream(dataType.getMethods())
                    .filter(method -> method.getName().equals(GET_PLAYER_DATA))
                    .filter(method -> Modifier.isStatic(method.getModifiers()))
                    .filter(method -> method.getParameterCount() == 1)
                    .findFirst()
                    .orElseThrow(NoSuchMethodException::new);
            Object optional = attachmentAccessor.invoke(null, serverPlayer);
            if (!(optional instanceof Optional<?> state) || state.isEmpty()) {
                return Optional.empty();
            }
            Object playerData = state.orElseThrow();
            String model = (String) dataType.getMethod(GET_MODEL).invoke(playerData);
            String texture = (String) dataType.getMethod(GET_TEXTURE).invoke(playerData);
            Object animationState = dataType.getMethod(GET_ANIMATION_STATE).invoke(playerData);
            Field animationField = animationState.getClass().getDeclaredField(ACTIVE_ANIMATION);
            animationField.setAccessible(true);
            String animation = (String) animationField.get(animationState);
            return Optional.of(new AppliedState(model, texture, animation));
        } catch (ReflectiveOperationException | LinkageError | SecurityException | ClassCastException failure) {
            return Optional.empty();
        }
    }

    static List<String> availableModels(ClassLoader classLoader) {
        try {
            Object value = Class.forName(MODEL_REGISTRY, false, classLoader).getMethod(MODEL_MAP).invoke(null);
            if (!(value instanceof Map<?, ?> models)) {
                return List.of();
            }
            return models.keySet().stream().filter(String.class::isInstance).map(String.class::cast)
                    .sorted().limit(64).toList();
        } catch (ReflectiveOperationException | LinkageError | SecurityException failure) {
            return List.of();
        }
    }

    static boolean syncPlayerState(Object target, Iterable<?> observers, ClassLoader classLoader) {
        try {
            Class<?> dataType = Class.forName(PLAYER_DATA, false, classLoader);
            Method attachmentAccessor = Arrays.stream(dataType.getMethods())
                    .filter(method -> method.getName().equals(GET_PLAYER_DATA))
                    .filter(method -> Modifier.isStatic(method.getModifiers()))
                    .filter(method -> method.getParameterCount() == 1)
                    .findFirst().orElseThrow(NoSuchMethodException::new);
            Object optional = attachmentAccessor.invoke(null, target);
            if (!(optional instanceof Optional<?> state) || state.isEmpty()) {
                syncDiagnostic = "NO_ATTACHMENT";
                return false;
            }
            Object data = state.orElseThrow();
            Method packetFactory = Arrays.stream(dataType.getMethods())
                    .filter(method -> method.getName().equals("OO0OoO00ooOOo0o00O000OoO"))
                    .filter(method -> !Modifier.isStatic(method.getModifiers()))
                    .filter(method -> method.getParameterCount() == 2)
                    .filter(method -> method.getParameterTypes()[1] == boolean.class)
                    .findFirst().orElseThrow(NoSuchMethodException::new);
            Object packetOptional = packetFactory.invoke(data, target, false);
            if (!(packetOptional instanceof Optional<?> packetValue) || packetValue.isEmpty()) {
                syncDiagnostic = "NO_PACKET";
                return false;
            }
            Object packet = packetValue.orElseThrow();
            Class<?> network = Class.forName(NETWORK, false, classLoader);
            boolean sent = false;
            for (Object observer : observers) {
                Method sender = Arrays.stream(network.getDeclaredMethods())
                        .filter(method -> method.getName().equals("OO0OoO00ooOOo0o00O000OoO"))
                        .filter(method -> Modifier.isStatic(method.getModifiers()))
                        .filter(method -> method.getParameterCount() == 2)
                        .filter(method -> method.getParameterTypes()[0].isInstance(packet))
                        .filter(method -> method.getParameterTypes()[1].isInstance(observer))
                        .findFirst().orElseThrow(NoSuchMethodException::new);
                sender.setAccessible(true);
                sender.invoke(null, packet, observer);
                sent = true;
            }
            syncDiagnostic = sent ? "SENT" : "NO_OBSERVERS";
            return sent;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException failure) {
            syncDiagnostic = failure.getClass().getSimpleName() + ":" + String.valueOf(failure.getMessage());
            return false;
        }
    }

    static String syncDiagnostic() {
        return syncDiagnostic;
    }

    record AppliedState(String modelId, String textureId, String animationId) {
    }
}
