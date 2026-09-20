package dev.mineagent.runtime.api.extension;

import java.util.Map;

/** Invoked by a real NeoForge @Mod constructor, not a world instance or hot-reload host. */
public interface BootExtension {
    /** modEventBus, modContainer, physicalSide, modId and immutable package provenance; no world exists yet. */
    void initialize(Map<String, Object> context) throws Exception;
}
