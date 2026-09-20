package dev.mineagent.runtime.scripting.javaext;

import java.util.Map;

public interface RuntimeExtension {
    Object start(Map<String, Object> bindings) throws Exception;

    default void stop() throws Exception {
    }
}
