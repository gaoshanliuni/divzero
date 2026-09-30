package dev.mineagent.runtime.core.conversation;

import java.util.*;

/** Scheduling metadata is deliberately independent of disclosure and execution permission. */
public record ToolExecutionTraits(boolean readOnly,boolean body,boolean menu,boolean parallelRead,boolean dimensionIndependent,Set<String> dependencies) {
    private static final Set<String> PARALLEL=Set.of("read_guidance","inspect_content_candidate","read_file","inspect_files","web_search","read_web_page","read_execution_record","inspect_memories");
    private static final Set<String> PORTABLE=Set.of("inspect_package_source","edit_package_sources","read_guidance","inspect_content_candidate","repair_content_package","set_actor_enhancements","inspect_player","inspect_player_control","inspect_effects","inspect_behavior","inspect_skills","skill","inspect_capabilities","observe","stop_actions","read_execution_record","inspect_memories","remember","forget_memory","read_file","inspect_files","web_search","read_web_page","search_images","inspect_persona","inspect_host","read_host_output","inspect_registry","inspect_modeling","validate_model_geometry");
    private static final Set<String> BODY=Set.of("control_agent_body","request_player_control","control_player_session","combat_entity","farm_area","fish_at","guard_area","patrol_route","follow_entity","wander_area","set_behavior_mode","start_skill","set_combat_policy");
    private static final Set<String> MENU=Set.of("interact_block","inspect_container","quick_move_container","close_container");
    public static ToolExecutionTraits of(String tool){
        boolean readOnly=!ConversationTools.mutation(tool);boolean portable=PORTABLE.contains(tool);
        return new ToolExecutionTraits(readOnly,BODY.contains(tool),MENU.contains(tool),readOnly&&PARALLEL.contains(tool),portable,portable?Set.of("world","viewer","agent","permissions"):Set.of("world","dimension","viewer","agent","target","permissions"));
    }
}
