package dev.mineagent.runtime.neoforge.client.ysm;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Optional;

/**
 * Pinned YSM compatibility path for server-owned MineAgent player bodies.
 * It delegates all model preparation and drawing to YSM's own client objects and renderers.
 */
public final class YsmRenderFallback {
    private static final String ENTRYPOINT = "com.elfmcys.yesstevemodel.YesSteveModel";
    private static final String CLIENT_DATA = "com.elfmcys.yesstevemodel.O0OoOoO0oo0O00O0ooo0o0OO";
    private static final String RENDER_DATA = "com.elfmcys.yesstevemodel.OOOoooO00oo000OOOOoO0OO0";
    private static final String RENDERERS = "com.elfmcys.yesstevemodel.O0oooooOOoOOOoOooo0o00o0";
    private static final String PROJECTILE_DATA = "com.elfmcys.yesstevemodel.OO0o0o0O0OO0O0OOOOoo0OOo";
    private static final String ENTITY_DATA = "com.elfmcys.yesstevemodel.ooOoOo0oOo000oOOooOoO00O";
    private static final String PLAYER_RENDER_DATA = "com.elfmcys.yesstevemodel.O00o0O0Ooooo0ooo00O0o0o0";
    private static volatile boolean circuitOpen;
    private static volatile String diagnostic = "NOT_RUN";
    private static volatile Gate gate = Gate.UNCHECKED;

    private YsmRenderFallback() {
    }

    public static synchronized void initialize() {
        if (gate != Gate.UNCHECKED) {
            return;
        }
        try {
            var container = net.neoforged.fml.ModList.get().getModContainerById("yes_steve_model");
            if (container.isEmpty()) {
                gate = Gate.ABSENT;
                diagnostic = "ABSENT";
                return;
            }
            if (!container.get().getModInfo().getVersion().toString().startsWith("2.6.5-neoforge+mc26.1")) {
                gate = Gate.UNSUPPORTED;
                diagnostic = "UNSUPPORTED_VERSION";
                return;
            }
            var file = net.neoforged.fml.ModList.get().getModFileById("yes_steve_model");
            if (file == null || !new dev.mineagent.runtime.integrations.ysm.YsmJarVerifier()
                    .verify(file.getFile().getFilePath())) {
                gate = Gate.UNSUPPORTED;
                diagnostic = "CHECKSUM_MISMATCH";
                return;
            }
            gate = Gate.SUPPORTED;
            diagnostic = "READY";
        } catch (RuntimeException failure) {
            gate = Gate.UNSUPPORTED;
            diagnostic = "GATE_FAILED:" + failure.getClass().getSimpleName();
        }
    }

    public static boolean tryRender(
            AvatarRenderState state,
            PoseStack poseStack,
            SubmitNodeCollector collector,
            CameraRenderState cameraState
    ) {
        if(dev.mineagent.runtime.neoforge.client.AgentSkinClient.selected(state.id))return false;
        if (gate == Gate.UNCHECKED) {
            initialize();
        }
        if (gate != Gate.SUPPORTED) {
            return false;
        }
        if (circuitOpen) {
            diagnostic = "CIRCUIT_OPEN";
            return false;
        }
        var minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            diagnostic = "NO_LEVEL";
            return false;
        }
        var entity = minecraft.level.getEntity(state.id);
        if (!(entity instanceof net.minecraft.world.entity.player.Player player)
                || !YsmRenderObserver.isManagedAgent(entity.getUUID())) {
            return false;
        }
        try {
            ClassLoader loader = Thread.currentThread().getContextClassLoader();
            if (!Boolean.TRUE.equals(Class.forName(ENTRYPOINT, false, loader)
                    .getMethod("isAvailable").invoke(null))) {
                diagnostic = "RUNTIME_UNAVAILABLE";
                return false;
            }
            Class<?> clientDataType = Class.forName(CLIENT_DATA, false, loader);
            Method clientDataAccessor = Arrays.stream(clientDataType.getMethods())
                    .filter(method -> method.getName().equals("O0oo00O0OoooOOOO00ooO000"))
                    .filter(method -> Modifier.isStatic(method.getModifiers()) && method.getParameterCount() == 1)
                    .findFirst().orElseThrow(NoSuchMethodException::new);
            Object optional = clientDataAccessor.invoke(null, player);
            if (!(optional instanceof Optional<?> value) || value.isEmpty()) {
                diagnostic = "NO_CLIENT_DATA";
                return false;
            }
            Object clientData = value.orElseThrow();
            Class<?> renderDataType = Class.forName(RENDER_DATA, false, loader);
            Object wrapper = lookupWrapper(renderDataType, state);
            if (wrapper == null) {
                Method build = Arrays.stream(renderDataType.getMethods())
                        .filter(method -> method.getName().equals("OO0OoO00ooOOo0o00O000OoO"))
                        .filter(method -> Modifier.isStatic(method.getModifiers()) && method.getParameterCount() == 2)
                        .filter(method -> method.getParameterTypes()[0].isInstance(clientData))
                        .filter(method -> method.getParameterTypes()[1].isInstance(state))
                        .findFirst().orElseThrow(NoSuchMethodException::new);
                build.invoke(null, clientData, state);
                wrapper = lookupWrapper(renderDataType, state);
            }
            if (wrapper == null) {
                diagnostic = "NO_WRAPPER";
                return false;
            }
            Object modelData = wrapper.getClass().getMethod("oOo0O0Oooo0o0Oo0O0O00Ooo").invoke(wrapper);
            Class<?> rendererRegistry = Class.forName(RENDERERS, false, loader);
            Object renderer;
            if (Class.forName(PLAYER_RENDER_DATA, false, loader).isInstance(modelData)) {
                renderer = rendererRegistry.getMethod("OO0OoO00ooOOo0o00O000OoO").invoke(null);
            } else if (Class.forName(PROJECTILE_DATA, false, loader).isInstance(modelData)) {
                renderer = rendererRegistry.getMethod("O0oo00O0OoooOOOO00ooO000").invoke(null);
            } else if (Class.forName(ENTITY_DATA, false, loader).isInstance(modelData)) {
                renderer = rendererRegistry.getMethod("OoO0Ooo0OO0Ooooo0O0OoOOO").invoke(null);
            } else {
                diagnostic = "UNKNOWN_MODEL_DATA:" + modelData.getClass().getName();
                return false;
            }
            Object finalWrapper = wrapper;
            Method render = Arrays.stream(renderer.getClass().getMethods())
                    .filter(method -> method.getName().equals("OO0OoO00ooOOo0o00O000OoO"))
                    .filter(method -> method.getParameterCount() == 5)
                    .filter(method -> method.getParameterTypes()[0].isInstance(state))
                    .filter(method -> method.getParameterTypes()[1].isInstance(finalWrapper))
                    .findFirst().orElseThrow(NoSuchMethodException::new);
            render.invoke(renderer, state, wrapper, poseStack, collector, cameraState);
            YsmRenderObserver.recordYsmInvocation(wrapper);
            diagnostic = "RENDERED";
            return true;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException failure) {
            circuitOpen = true;
            diagnostic = failure.getClass().getSimpleName() + ":" + String.valueOf(failure.getMessage());
            dev.mineagent.runtime.neoforge.MineAgentRuntimeMod.LOGGER.error(
                    "Pinned YSM MineAgent renderer fallback disabled after failure", failure);
            return false;
        }
    }

    public static String diagnostic() {
        return diagnostic;
    }

    private enum Gate {
        UNCHECKED,
        ABSENT,
        UNSUPPORTED,
        SUPPORTED
    }

    private static Object lookupWrapper(Class<?> renderDataType, AvatarRenderState state)
            throws ReflectiveOperationException {
        Method lookup = Arrays.stream(renderDataType.getMethods())
                .filter(method -> method.getName().equals("OO0OoO00ooOOo0o00O000OoO"))
                .filter(method -> Modifier.isStatic(method.getModifiers()) && method.getParameterCount() == 1)
                .filter(method -> method.getParameterTypes()[0].isInstance(state))
                .findFirst().orElseThrow(NoSuchMethodException::new);
        return lookup.invoke(null, state);
    }
}
