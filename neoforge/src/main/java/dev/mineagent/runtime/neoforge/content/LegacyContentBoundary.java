package dev.mineagent.runtime.neoforge.content;
import java.util.Set;

/** Retained native IDs decode older saves; they are not a substitute for new runtime generation. */
public final class LegacyContentBoundary {
    public static final String PACK_ID="mod/mineagent_runtime:resourcepacks/legacy_compat";
    private static final Set<String> LEGACY=Set.of("mineagent_runtime:basketball","mineagent_runtime:basketball_hoop",
            "mineagent_runtime:runtime_crystal","mineagent_runtime:agent_blade","mineagent_runtime:sentinel_boss","mineagent_runtime:sentinel_summoner");
    private LegacyContentBoundary(){}
    public static Set<String> defaultCreativeItems(){return Set.of("mineagent_runtime:automation_console","mineagent_runtime:media_screen");}
    public static boolean legacyIdentifier(String id){return LEGACY.contains(id);}
    public static void requireGenerativePrimitive(String id){if(legacyIdentifier(id))throw new IllegalStateException("LEGACY_CONTENT_REQUIRES_EXPLICIT_REUSE");}
}
