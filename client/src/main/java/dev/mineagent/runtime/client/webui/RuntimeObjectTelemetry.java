package dev.mineagent.runtime.client.webui;
import java.util.Map;
/** Opt-in observation only. It must not enable a scene or create a model request. */
public final class RuntimeObjectTelemetry {
    private RuntimeObjectTelemetry(){}
    public static boolean enabled(){return enabled(System.getProperties());}
    public static boolean enabled(Map<?,?> flags){for(String name:new String[]{"mineagent.runtimeObjectSmoke","mineagent.worldPackageObjectSmoke","mineagent.worldPatchSmoke","mineagent.worldUiSmoke","mineagent.worldUiModelSmoke","mineagent.worldUiRepairSmoke","mineagent.worldUiAgentSmoke"})if(Boolean.parseBoolean(String.valueOf(flags.get(name))))return true;return false;}
}
