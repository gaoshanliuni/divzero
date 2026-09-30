package dev.mineagent.runtime.worker.smoke;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Retained pure policies for historical acceptance tests. Native UI fixtures own current launches. */
public final class ProductionJointAppearanceLauncher {
    static Path freshRun(Path root)throws Exception{return Files.createDirectories(root.toAbsolutePath().normalize().resolve(UUID.randomUUID().toString()));}
    static Optional<Path> resumeSelection(Path root,String worldPatch,String worldUiRepair)throws Exception{
        if(!worldPatch.isBlank()&&!worldUiRepair.isBlank())throw new IllegalArgumentException("RESUME_MODE_CONFLICT");
        String selected=worldPatch.isBlank()?worldUiRepair:worldPatch;return selected.isBlank()?Optional.empty():Optional.of(stoppedProfile(root,Path.of(selected)));
    }
    static Path stoppedProfile(Path root,Path profile)throws Exception{
        final Path parent,actual;try{parent=root.toRealPath();actual=profile.toRealPath();}catch(java.io.IOException e){throw new IllegalArgumentException("RESUME_PROFILE_PATH",e);}
        if(!actual.getParent().equals(parent)||!profile.toAbsolutePath().normalize().getParent().toRealPath().equals(parent)||!actual.getFileName().equals(profile.getFileName())||Files.isSymbolicLink(profile)||!actual.getFileName().toString().equals(UUID.fromString(actual.getFileName().toString()).toString())||!Files.isDirectory(actual.resolve("game")))throw new IllegalArgumentException("RESUME_PROFILE_PATH");
        var previous=new ObjectMapper().readTree(actual.resolve("process.json").toFile());
        if(!Path.of(previous.path("run").asText()).toRealPath().equals(actual))throw new IllegalArgumentException("RESUME_PROFILE_CONTEXT");
        long pid=previous.path("pid").asLong(-1);if(pid<1||ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false))throw new IllegalStateException("RESUME_PROCESS_NOT_STOPPED");return actual;
    }
    static List<String> stages(boolean recovery){return recovery?List.of("prepare","resume","verify"):List.of("joint");}
    static List<String> personaStages(boolean persona){return persona?List.of("prepare","resume"):List.of("joint");}
    static void validateConversationAgentMode(Map<?,?> flags){
        boolean real=Boolean.parseBoolean(String.valueOf(flags.get("mineagent.conversationAgentReal")));
        boolean active=Boolean.parseBoolean(String.valueOf(flags.get("mineagent.conversationAgentSmoke")));
        String db=Objects.toString(flags.get("mineagent.conversationAgentProviderDb"),"");
        if((real&&!active)||(real==db.isBlank()))throw new IllegalArgumentException("CONVERSATION_AGENT_REAL_CONFIG");
        String scenario=Objects.toString(flags.get("mineagent.conversationAgentScenario"),""),budget=Objects.toString(flags.get("mineagent.conversationAgentCallBudget"),"");
        if(!Set.of("","language_ui","about_ui","interaction_schema","clearance_path_zero","clearance_path","feedback_chat","feedback_nine_zero","feedback_nine","full_import_zero","media_tools_saved","media_tools","building_files_zero","building_search","building_files","world_geometry","world_geometry_zero","agent_models","agent_models_ui","python_host","python_host_saved","recovery","creature_events","creature_events_saved","watch_ui","watch_ui_saved","creatures","creatures_saved","thinking","thinking_saved","provider_models","feedback","feedback_remaining","feedback_interrupt","feedback_native_zero","management","management_ysm","management_ysm_saved","autonomy","primitives","basketball","basketball_retry","basketball_layout","diamond","rules","cobble","blueprint","web","interaction","runtime_item","runtime_item_saved","runtime_throw","runtime_throw_saved","runtime_throw_sphere","runtime_throw_sphere_saved","basketball_saved").contains(scenario)||!real&&(!scenario.isBlank()||!budget.isBlank()))throw new IllegalArgumentException("CONVERSATION_AGENT_REAL_SCENARIO");
        if(!budget.isBlank()&&!budget.equals("unlimited")&&(!budget.matches("[0-9]{1,2}")||Integer.parseInt(budget)<1||Integer.parseInt(budget)>24))throw new IllegalArgumentException("CONVERSATION_AGENT_REAL_BUDGET");
        String blueprint=Objects.toString(flags.get("mineagent.conversationAgentBlueprint"),"");if(scenario.equals("blueprint")==blueprint.isBlank())throw new IllegalArgumentException("CONVERSATION_AGENT_BLUEPRINT_SOURCE");
        String item=Objects.toString(flags.get("mineagent.conversationAgentItemArtifact"),"");if(Set.of("media_tools_saved","building_files_zero","building_files","python_host_saved","creature_events_saved","watch_ui_saved","creatures_saved","thinking_saved","runtime_item_saved","runtime_throw_saved","runtime_throw_sphere_saved","basketball_saved","management_ysm_saved").contains(scenario)==item.isBlank())throw new IllegalArgumentException("RUNTIME_ITEM_ARTIFACT_SOURCE");
        if(!active)return;
        var allowed=Set.of("mineagent.conversationAgentItemArtifact","mineagent.conversationAgentBlueprint","mineagent.conversationAgentScenario","mineagent.conversationAgentCallBudget","mineagent.conversationAgentSmoke","mineagent.conversationAgentReal","mineagent.conversationAgentProviderDb","mineagent.worldUiFixture","mineagent.worldUiAgentFixture");
        flags.forEach((k,v)->{if(k.toString().startsWith("mineagent.")&&!allowed.contains(k.toString())&&!v.toString().isBlank()&&!v.toString().equals("false"))throw new IllegalArgumentException("CONVERSATION_AGENT_MODE_CONFLICT");});
    }
    static void validateConversationMode(Map<?,?> flags){
        String summaryFailure=Objects.toString(flags.get("mineagent.conversationSummaryFailureMode"),"");
        if(!Set.of("","cancel","provider-failure","invalid-output","budget-exhausted").contains(summaryFailure)||!summaryFailure.isEmpty()&&(!Boolean.parseBoolean(String.valueOf(flags.get("mineagent.conversationSummarySmoke")))||Boolean.parseBoolean(String.valueOf(flags.get("mineagent.conversationNativeSmoke")))))throw new IllegalArgumentException("SUMMARY_FIXTURE_FAILURE_MODE");
        if(Boolean.parseBoolean(String.valueOf(flags.get("mineagent.conversationSummarySmoke")))&&!Boolean.parseBoolean(String.valueOf(flags.get("mineagent.conversationSmoke"))))throw new IllegalArgumentException("SUMMARY_FIXTURE_PARENT_REQUIRED");
        if(!Boolean.parseBoolean(String.valueOf(flags.get("mineagent.conversationSmoke"))))return;
        for(String key:List.of("personaSmoke","worldUiAgentSmoke","worldUiModelSmoke","worldUiFailureSmoke","worldUiSmoke","worldMoveSmoke","appearanceChoiceSmoke","appearanceRecoverySmoke","appearanceAgentSmoke","runtimeObjectSmoke","worldPackageObjectSmoke","appearanceAgentProviderDb","worldPatchProfile","worldUiRepairProfile")){
            Object value=flags.get("mineagent."+key);if(value!=null&&!value.toString().isBlank()&&!value.toString().equals("false"))throw new IllegalArgumentException("CONVERSATION_FIXTURE_MODE_CONFLICT");
        }
    }
    static void validateWorkspaceMode(Map<?,?> flags){
        if(!Boolean.parseBoolean(String.valueOf(flags.get("mineagent.workspaceSmoke"))))return;
        if(!Boolean.parseBoolean(String.valueOf(flags.get("mineagent.worldUiSmoke"))))throw new IllegalArgumentException("WORKSPACE_FIXTURE_PARENT_REQUIRED");
        for(String key:List.of("conversationSmoke","personaSmoke","worldMoveSmoke","worldUiModelSmoke","worldUiAgentSmoke","worldUiFailureSmoke","appearanceAgentProviderDb","worldPatchProfile","worldUiRepairProfile","appearanceChoiceSmoke","appearanceRecoverySmoke","appearanceAgentSmoke","runtimeObjectSmoke","worldPackageObjectSmoke")){Object value=flags.get("mineagent."+key);if(value!=null&&!value.toString().isBlank()&&!value.toString().equals("false"))throw new IllegalArgumentException("WORKSPACE_FIXTURE_MODE_CONFLICT");}
    }
    static void validateBodySurvivalMode(Map<?,?> flags){
        if(!Boolean.parseBoolean(String.valueOf(flags.get("mineagent.bodySurvivalSmoke"))))return;
        var allowed=Set.of("mineagent.bodySurvivalSmoke","mineagent.worldUiFixture","mineagent.worldUiAgentFixture");
        flags.forEach((key,value)->{String k=String.valueOf(key),v=Objects.toString(value,"");if(k.startsWith("mineagent.")&&!allowed.contains(k)&&!v.isBlank()&&!v.equalsIgnoreCase("false"))throw new IllegalArgumentException("SURVIVAL_FIXTURE_MODE_CONFLICT");});
    }
    static void validateSettingsMode(Map<?,?> flags){
        if(!Boolean.parseBoolean(String.valueOf(flags.get("mineagent.settingsSmoke"))))return;
        var allowed=Set.of("mineagent.settingsSmoke","mineagent.worldUiFixture","mineagent.worldUiAgentFixture");
        flags.forEach((key,value)->{String k=String.valueOf(key),v=Objects.toString(value,"");if(k.startsWith("mineagent.")&&!allowed.contains(k)&&!v.isBlank()&&!v.equalsIgnoreCase("false"))throw new IllegalArgumentException("SETTINGS_FIXTURE_MODE_CONFLICT");});
    }
    static void validateAgentManagementMode(Map<?,?> flags){
        if(!Boolean.parseBoolean(String.valueOf(flags.get("mineagent.agentManagementSmoke"))))return;
        var allowed=Set.of("mineagent.agentManagementSmoke","mineagent.worldUiFixture","mineagent.worldUiAgentFixture");
        flags.forEach((key,value)->{String k=String.valueOf(key),v=Objects.toString(value,"");if(k.startsWith("mineagent.")&&!allowed.contains(k)&&!v.isBlank()&&!v.equalsIgnoreCase("false"))throw new IllegalArgumentException("AGENT_MANAGEMENT_FIXTURE_CONFLICT");});
    }
    static void validateResourcePackMode(Map<?,?> flags){
        if(!Boolean.parseBoolean(String.valueOf(flags.get("mineagent.resourcePackSmoke"))))return;
        var allowed=Set.of("mineagent.resourcePackSmoke","mineagent.worldUiFixture","mineagent.worldUiAgentFixture");
        flags.forEach((key,value)->{String k=String.valueOf(key),v=Objects.toString(value,"");if(k.startsWith("mineagent.")&&!allowed.contains(k)&&!v.isBlank()&&!v.equalsIgnoreCase("false"))throw new IllegalArgumentException("RESOURCE_PACK_FIXTURE_MODE_CONFLICT");});
    }
    static void validateClientScriptMode(Map<?,?> flags){
        if(!Boolean.parseBoolean(String.valueOf(flags.get("mineagent.clientScriptSmoke"))))return;
        var allowed=Set.of("mineagent.clientScriptSmoke","mineagent.worldUiFixture","mineagent.worldUiAgentFixture");
        flags.forEach((key,value)->{String k=String.valueOf(key),v=Objects.toString(value,"");if(k.startsWith("mineagent.")&&!allowed.contains(k)&&!v.isBlank()&&!v.equalsIgnoreCase("false"))throw new IllegalArgumentException("CLIENT_SCRIPT_FIXTURE_MODE_CONFLICT");});
    }
    static void validateClientJavaMode(Map<?,?> flags){
        if(!Boolean.parseBoolean(String.valueOf(flags.get("mineagent.clientJavaSmoke"))))return;
        var allowed=Set.of("mineagent.clientJavaSmoke","mineagent.worldUiFixture","mineagent.worldUiAgentFixture");flags.forEach((key,value)->{String k=String.valueOf(key),v=Objects.toString(value,"");if(k.startsWith("mineagent.")&&!allowed.contains(k)&&!v.isBlank()&&!v.equalsIgnoreCase("false"))throw new IllegalArgumentException("CLIENT_JAVA_FIXTURE_MODE_CONFLICT");});
    }
    static void validateClientDependencyMode(Map<?,?> flags){if(!Boolean.parseBoolean(String.valueOf(flags.get("mineagent.clientDependencySmoke"))))return;var allowed=Set.of("mineagent.clientDependencySmoke","mineagent.worldUiFixture","mineagent.worldUiAgentFixture");flags.forEach((key,value)->{String k=String.valueOf(key),v=Objects.toString(value,"");if(k.startsWith("mineagent.")&&!allowed.contains(k)&&!v.isBlank()&&!v.equalsIgnoreCase("false"))throw new IllegalArgumentException("CLIENT_DEPENDENCY_FIXTURE_MODE_CONFLICT");});}
    static void validateClientStudioMode(Map<?,?> flags){if(!Boolean.parseBoolean(String.valueOf(flags.get("mineagent.clientStudioSmoke"))))return;var allowed=Set.of("mineagent.clientStudioSmoke","mineagent.worldUiFixture","mineagent.worldUiAgentFixture");flags.forEach((key,value)->{String k=String.valueOf(key),v=Objects.toString(value,"");if(k.startsWith("mineagent.")&&!allowed.contains(k)&&!v.isBlank()&&!v.equalsIgnoreCase("false"))throw new IllegalArgumentException("CLIENT_STUDIO_FIXTURE_MODE_CONFLICT");});}
    static void validatePackageAssetMode(Map<?,?> flags){if(!Boolean.parseBoolean(String.valueOf(flags.get("mineagent.packageAssetSmoke"))))return;var allowed=Set.of("mineagent.packageAssetSmoke","mineagent.worldUiFixture","mineagent.worldUiAgentFixture");flags.forEach((key,value)->{String k=String.valueOf(key),v=Objects.toString(value,"");if(k.startsWith("mineagent.")&&!allowed.contains(k)&&!v.isBlank()&&!v.equalsIgnoreCase("false"))throw new IllegalArgumentException("PACKAGE_ASSET_FIXTURE_MODE_CONFLICT");});}
    static void validateBootUpgradeMode(Map<?,?> flags){if(!Boolean.parseBoolean(String.valueOf(flags.get("mineagent.bootUpgradeSmoke"))))return;var allowed=Set.of("mineagent.bootUpgradeSmoke","mineagent.worldUiFixture","mineagent.worldUiAgentFixture");flags.forEach((key,value)->{String k=String.valueOf(key),v=Objects.toString(value,"");if(k.startsWith("mineagent.")&&!allowed.contains(k)&&!v.isBlank()&&!v.equalsIgnoreCase("false"))throw new IllegalArgumentException("BOOT_UPGRADE_FIXTURE_MODE_CONFLICT");});}
    static void validateBootDependencyMode(Map<?,?> flags){if(!Boolean.parseBoolean(String.valueOf(flags.get("mineagent.bootDependencySmoke"))))return;var allowed=Set.of("mineagent.bootDependencySmoke","mineagent.worldUiFixture","mineagent.worldUiAgentFixture");flags.forEach((key,value)->{String k=String.valueOf(key),v=Objects.toString(value,"");if(k.startsWith("mineagent.")&&!allowed.contains(k)&&!v.isBlank()&&!v.equalsIgnoreCase("false"))throw new IllegalArgumentException("BOOT_DEPENDENCY_FIXTURE_MODE_CONFLICT");});}
    static List<String> bodyRecoveryStages(){return List.of("prepare-death","resume-death","resume-end","resume-hardcore","verify-mode");}
    static void snapshotBodyRecoveryStage(Path game,String stage)throws Exception{
        if(!bodyRecoveryStages().contains(stage))throw new IllegalArgumentException("BODY_RECOVERY_SNAPSHOT_STAGE");
        var mapper=new ObjectMapper();var root=game.resolve("body-recovery-evidence");
        String id=UUID.fromString(mapper.readTree(root.resolve("journal.json").toFile()).path("agent").asText()).toString();
        var source=new LinkedHashMap<String,Path>();var world=game.resolve("saves/SmokeWorld");
        source.put("player.dat",world.resolve("players/data/"+id+".dat"));source.put("stats.json",world.resolve("players/stats/"+id+".json"));source.put("level.dat",world.resolve("level.dat"));
        source.put("runtime.db",game.resolve("mineagent-runtime-data/runtime.db"));
        for(String suffix:List.of("-wal","-shm")){var file=game.resolve("mineagent-runtime-data/runtime.db"+suffix);if(Files.isRegularFile(file))source.put("runtime.db"+suffix,file);}
        for(var path:source.values())if(!Files.isRegularFile(path))throw new IllegalStateException("BODY_RECOVERY_NATIVE_SAVE_MISSING");
        var destination=root.resolve(stage).resolve("native-save");Files.createDirectories(destination);var hashes=new LinkedHashMap<String,String>();
        for(var entry:source.entrySet()){Files.copy(entry.getValue(),destination.resolve(entry.getKey()));hashes.put(entry.getKey(),HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(entry.getValue()))));}
        Files.writeString(destination.resolve("manifest.json"),mapper.writeValueAsString(Map.of("stage",stage,"agent",id,"afterProcessExit",true,"files",hashes)));
    }
    static void validateBodyRecoveryMode(Map<?,?> flags){
        if(!Boolean.parseBoolean(String.valueOf(flags.get("mineagent.bodyRecoverySmoke"))))return;
        var allowed=Set.of("mineagent.bodyRecoverySmoke","mineagent.worldUiFixture","mineagent.worldUiAgentFixture");
        flags.forEach((key,value)->{String k=String.valueOf(key),v=Objects.toString(value,"");if(k.startsWith("mineagent.")&&!allowed.contains(k)&&!v.isBlank()&&!v.equalsIgnoreCase("false"))throw new IllegalArgumentException("BODY_RECOVERY_MODE_CONFLICT");});
    }
    static void validateNativeAtlasMode(Map<?,?> flags){
        if(Boolean.parseBoolean(String.valueOf(flags.get("mineagent.nativePopupLegacySmoke")))&&!Boolean.parseBoolean(String.valueOf(flags.get("mineagent.nativePopupSmoke"))))throw new IllegalArgumentException("POPUP_LEGACY_PARENT_REQUIRED");
        if(Boolean.parseBoolean(String.valueOf(flags.get("mineagent.nativePopupSmoke")))&&!Boolean.parseBoolean(String.valueOf(flags.get("mineagent.nativeAtlasInputSmoke"))))throw new IllegalArgumentException("POPUP_FIXTURE_PARENT_REQUIRED");
        if(Boolean.parseBoolean(String.valueOf(flags.get("mineagent.nativeAtlasInputSmoke")))&&!Boolean.parseBoolean(String.valueOf(flags.get("mineagent.nativeAtlasSmoke"))))throw new IllegalArgumentException("ATLAS_INPUT_FIXTURE_PARENT_REQUIRED");
        if(!Boolean.parseBoolean(String.valueOf(flags.get("mineagent.nativeAtlasSmoke"))))return;
        if(!Boolean.parseBoolean(String.valueOf(flags.get("mineagent.worldUiSmoke"))))throw new IllegalArgumentException("ATLAS_FIXTURE_PARENT_REQUIRED");
        // Fail closed for new fixture/provider/resume flags, not just today's known modes.
        var allowed=Set.of("mineagent.nativeAtlasSmoke","mineagent.nativeAtlasInputSmoke","mineagent.nativePopupSmoke","mineagent.nativePopupLegacySmoke","mineagent.worldUiSmoke","mineagent.worldUiFixture","mineagent.worldUiAgentFixture");
        flags.forEach((key,value)->{String name=String.valueOf(key),v=Objects.toString(value,"");
            if(name.startsWith("mineagent.")&&!allowed.contains(name)&&!v.isBlank()&&!v.equalsIgnoreCase("false"))throw new IllegalArgumentException("ATLAS_FIXTURE_MODE_CONFLICT");
        });
    }
    static void validateViewSettingsMode(Map<?,?> flags){
        boolean manual=Boolean.parseBoolean(String.valueOf(flags.get("mineagent.viewSettingsSmoke")));
        boolean paint=Boolean.parseBoolean(String.valueOf(flags.get("mineagent.viewSettingsPaintSmoke")));
        if(!manual&&!paint)return;
        if(manual&&paint||Boolean.parseBoolean(String.valueOf(flags.get("mineagent.workspaceSmoke"))))throw new IllegalArgumentException("VIEW_SETTINGS_FIXTURE_MODE_CONFLICT");
        var check=new HashMap<Object,Object>();flags.forEach(check::put);check.put("mineagent.workspaceSmoke","true");validateWorkspaceMode(check);
    }
    static void validateObjectDirectoryMode(Map<?,?> flags){
        if(!Boolean.parseBoolean(String.valueOf(flags.get("mineagent.objectDirectorySmoke"))))return;
        for(String key:List.of("workspaceSmoke","viewSettingsSmoke","viewSettingsPaintSmoke","livePlacementSmoke")){Object value=flags.get("mineagent."+key);if(value!=null&&!value.toString().isBlank()&&!value.toString().equals("false"))throw new IllegalArgumentException("DIRECTORY_FIXTURE_MODE_CONFLICT");}
        var check=new HashMap<Object,Object>();flags.forEach(check::put);check.put("mineagent.workspaceSmoke","true");validateWorkspaceMode(check);
    }
    static void validateSharedStateMode(Map<?,?> flags){
        if(!Boolean.parseBoolean(String.valueOf(flags.get("mineagent.sharedStateSmoke"))))return;
        for(String key:List.of("workspaceSmoke","viewSettingsSmoke","viewSettingsPaintSmoke","scheduleSmoke","eventSmoke","audienceSmoke","objectDirectorySmoke","livePlacementSmoke","livePlacementModel","livePlacementProviderDb")){Object value=flags.get("mineagent."+key);if(value!=null&&!value.toString().isBlank()&&!value.toString().equals("false"))throw new IllegalArgumentException("SHARED_FIXTURE_MODE_CONFLICT");}
        var check=new HashMap<Object,Object>();flags.forEach(check::put);check.put("mineagent.workspaceSmoke","true");validateWorkspaceMode(check);
    }
    static void validateAudienceMode(Map<?,?> flags){
        if(!Boolean.parseBoolean(String.valueOf(flags.get("mineagent.audienceSmoke"))))return;
        for(String key:List.of("conversationSmoke","personaSmoke","workspaceSmoke","viewSettingsSmoke","viewSettingsPaintSmoke","objectDirectorySmoke","livePlacementSmoke","livePlacementModel","livePlacementProviderDb")){Object value=flags.get("mineagent."+key);if(value!=null&&!value.toString().isBlank()&&!value.toString().equals("false"))throw new IllegalArgumentException("AUDIENCE_FIXTURE_MODE_CONFLICT");}
        var check=new HashMap<Object,Object>();flags.forEach(check::put);check.put("mineagent.personaSmoke","true");validatePersonaMode(check);
    }
    static void validateEventMode(Map<?,?> flags){
        if(!Boolean.parseBoolean(String.valueOf(flags.get("mineagent.eventSmoke"))))return;
        if(Boolean.parseBoolean(String.valueOf(flags.get("mineagent.audienceSmoke"))))throw new IllegalArgumentException("EVENT_FIXTURE_MODE_CONFLICT");
        var check=new HashMap<Object,Object>();flags.forEach(check::put);check.put("mineagent.audienceSmoke","true");validateAudienceMode(check);
    }
    static void validateScheduleMode(Map<?,?> flags){
        if(!Boolean.parseBoolean(String.valueOf(flags.get("mineagent.scheduleSmoke"))))return;
        if(Boolean.parseBoolean(String.valueOf(flags.get("mineagent.eventSmoke"))))throw new IllegalArgumentException("SCHEDULE_FIXTURE_MODE_CONFLICT");
        var check=new HashMap<Object,Object>();flags.forEach(check::put);check.put("mineagent.eventSmoke","true");validateEventMode(check);
    }
    static void validateSharedAgentMode(Map<?,?> flags){
        if(!Boolean.parseBoolean(String.valueOf(flags.get("mineagent.sharedAgentSmoke"))))return;
        for(String key:List.of("scheduleSmoke","eventSmoke","audienceSmoke","sharedStateSmoke","worldUiSmoke","sharedMultiplayerServer","sharedMultiplayerRole")){Object value=flags.get("mineagent."+key);if(value!=null&&!value.toString().isBlank()&&!value.toString().equals("false"))throw new IllegalArgumentException("SHARED_AGENT_FIXTURE_MODE_CONFLICT");}
        var check=new HashMap<Object,Object>();flags.forEach(check::put);check.put("mineagent.scheduleSmoke","true");validateScheduleMode(check);
    }
    static List<String> opacityPersistenceStages(){return List.of("prepare","resume","legacy","reenabled");}
    static void validateLivePlacementMode(Map<?,?> flags){
        String mode=Objects.toString(flags.get("mineagent.livePlacementSmoke"),""),model=Objects.toString(flags.get("mineagent.livePlacementModel"),""),source=Objects.toString(flags.get("mineagent.livePlacementProviderDb"),"");
        if(Boolean.parseBoolean(String.valueOf(flags.get("mineagent.opacityPersistenceSmoke")))&&!Set.of("opacity-half","opacity-zero").contains(mode))throw new IllegalArgumentException("OPACITY_PERSISTENCE_PARENT_REQUIRED");
        if(mode.isEmpty()){if(!model.isEmpty()||!source.isEmpty())throw new IllegalArgumentException("LIVE_PLACEMENT_REAL_MODE_REQUIRED");return;}
        if(!Set.of("positive","stale","cancel","real","opacity-half","opacity-zero","opacity-unavailable").contains(mode)||Boolean.parseBoolean(String.valueOf(flags.get("mineagent.workspaceSmoke")))||Boolean.parseBoolean(String.valueOf(flags.get("mineagent.viewSettingsSmoke")))||Boolean.parseBoolean(String.valueOf(flags.get("mineagent.viewSettingsPaintSmoke"))))throw new IllegalArgumentException("LIVE_PLACEMENT_MODE");
        if(mode.equals("real")?(source.isBlank()||model.isBlank()||model.length()>256||model.chars().anyMatch(c->Character.isWhitespace(c)||Character.isISOControl(c))):(!model.isEmpty()||!source.isEmpty()))throw new IllegalArgumentException("LIVE_PLACEMENT_PROVIDER_MODE");
        var check=new HashMap<Object,Object>();flags.forEach(check::put);check.put("mineagent.workspaceSmoke","true");validateWorkspaceMode(check);
    }
    static void validatePersonaMode(Map<?,?> flags){
        if(!Boolean.parseBoolean(String.valueOf(flags.get("mineagent.personaSmoke"))))return;
        for(String name:List.of("worldUiAgentSmoke","worldUiModelSmoke","worldUiFailureSmoke","worldUiSmoke","worldMoveSmoke","appearanceChoiceSmoke","appearanceRecoverySmoke","appearanceAgentSmoke","runtimeObjectSmoke","worldPackageObjectSmoke","worldPatchProfile","worldUiRepairProfile","appearanceAgentProviderDb")){
            Object value=flags.get("mineagent."+name);if(value!=null&&!value.toString().isBlank()&&!value.toString().equals("false"))throw new IllegalArgumentException("PERSONA_FIXTURE_MODE_CONFLICT");
        }
    }
    static void validateWorldAgentMode(Map<?,?> flags){
        String cancel=flags.get("mineagent.worldUiAgentCancelMode")==null?"":flags.get("mineagent.worldUiAgentCancelMode").toString();
        String model=flags.get("mineagent.worldUiAgentModel")==null?"":flags.get("mineagent.worldUiAgentModel").toString();
        if(!model.isEmpty()&&(!Boolean.parseBoolean(String.valueOf(flags.get("mineagent.worldUiAgentSmoke")))||!cancel.isEmpty()||model.isBlank()||model.length()>256||model.chars().anyMatch(c->Character.isWhitespace(c)||Character.isISOControl(c))))throw new IllegalArgumentException("WORLD_UI_AGENT_REAL_MODEL_MODE");
        if(!Set.of("","hide","close","stop","far","queued-stop","queued-revoke").contains(cancel)||!cancel.isEmpty()&&!Boolean.parseBoolean(String.valueOf(flags.get("mineagent.worldUiAgentSmoke"))))throw new IllegalArgumentException("WORLD_UI_AGENT_CANCEL_MODE");
        if(!Boolean.parseBoolean(String.valueOf(flags.get("mineagent.worldUiAgentSmoke"))))return;
        for(String name:List.of("worldUiModelSmoke","worldUiFailureSmoke","worldUiSmoke","worldMoveSmoke","appearanceChoiceSmoke","appearanceRecoverySmoke","appearanceAgentSmoke","runtimeObjectSmoke","worldPackageObjectSmoke","worldPatchProfile","worldUiRepairProfile")){
            Object value=flags.get("mineagent."+name);
            if(value!=null&&(name.endsWith("Profile")?!value.toString().isBlank():Boolean.parseBoolean(value.toString())))throw new IllegalArgumentException("WORLD_UI_AGENT_MODE_CONFLICT");
        }
    }
    private static final class NegativeProvider implements AutoCloseable{
        final com.sun.net.httpserver.HttpServer http;final Path game;final java.util.concurrent.atomic.AtomicInteger calls=new java.util.concurrent.atomic.AtomicInteger();
        NegativeProvider(Path game)throws Exception{
            this.game=game;http=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
            http.createContext("/v1/chat/completions",exchange->{calls.incrementAndGet();exchange.getRequestBody().readAllBytes();byte[] body="{\"error\":{\"code\":\"CONTROLLED_TEST_REJECTION\"}}".getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(401,body.length);exchange.getResponseBody().write(body);exchange.close();});http.start();
            try(var target=dev.mineagent.runtime.core.config.ServerConfigService.open(game.resolve("mineagent-runtime-data/runtime.db"))){
                if(!target.apply(new dev.mineagent.runtime.api.config.ConfigPatch(target.snapshot().revision(),Map.of("provider.openai.baseUrl","http://127.0.0.1:"+http.getAddress().getPort()+"/v1/","provider.openai.model","negative-fixture","provider.openai.apiKey","not-a-real-key","voice.output.enabled","false")),true).accepted())throw new IllegalStateException("NEGATIVE_PROVIDER_CONFIG");
            }catch(Exception e){http.stop(0);throw e;}
        }
        public void close()throws Exception{http.stop(0);Files.writeString(game.resolve("controlled-provider-evidence.json"),new ObjectMapper().writeValueAsString(Map.of("provider","CONTROLLED_LOCAL_HTTP_NOT_MODEL","httpStatus",401,"calls",calls.get(),"paidProviderCalls",0)));}
    }
    static void verifyOutput(int exit,String output,String marker){
        if(exit!=0||!output.contains("CLIENT in PROD")||!output.contains(marker)||output.contains("/FATAL]")||output.contains("Exception in server tick loop"))
            throw new IllegalStateException("PRODUCTION_STAGE_FAILED: "+marker);
    }
    static void copyProvider(Path source,Path game)throws Exception{copyProvider(source,game,null);}
    static void copyProvider(Path source,Path game,String modelOverride)throws Exception{
        if(modelOverride!=null&&(modelOverride.isBlank()||modelOverride.length()>256||modelOverride.chars().anyMatch(c->Character.isISOControl(c)||Character.isWhitespace(c))))throw new IllegalArgumentException("PROVIDER_MODEL_INVALID");
        if(!Files.isRegularFile(source)||Files.exists(game.resolve("mineagent-runtime-data/runtime.db")))throw new IllegalArgumentException("PROVIDER_SOURCE_OR_TARGET");
        var values=new LinkedHashMap<String,String>();
        try(var connection=java.sql.DriverManager.getConnection("jdbc:sqlite:"+source.toAbsolutePath().toUri()+"?mode=ro");var query=connection.prepareStatement("SELECT key,value FROM mineagent_config WHERE key IN ('provider.openai.baseUrl','provider.openai.model','provider.openai.apiKey')");var rows=query.executeQuery()){
            while(rows.next())values.put(rows.getString(1),rows.getString(2));
        }
        if(values.size()!=3||values.values().stream().anyMatch(String::isBlank))throw new IllegalStateException("PROVIDER_NOT_CONFIGURED");
        if(modelOverride!=null)values.put("provider.openai.model",modelOverride);
        values.put("voice.output.enabled","false"); // Keep this visual fixture from starting unrelated TTS work.
        try(var target=dev.mineagent.runtime.core.config.ServerConfigService.open(game.resolve("mineagent-runtime-data/runtime.db"))){
            if(!target.apply(new dev.mineagent.runtime.api.config.ConfigPatch(target.snapshot().revision(),values),true).accepted())throw new IllegalStateException("PROVIDER_COPY_FAILED");
        }
        Files.writeString(game.resolve("appearance-agent-provider.json"),new ObjectMapper().writeValueAsString(Map.of("baseUrl",values.get("provider.openai.baseUrl"),"model",values.get("provider.openai.model"),"credentialConfigured",true)));
    }
    static void checkPreparedBoundary(Path game)throws Exception{
        var json=new ObjectMapper();var journal=json.readTree(game.resolve("appearance-recovery-evidence/journal.json").toFile());
        var checked=new LinkedHashMap<String,Object>();
        try(var connection=java.sql.DriverManager.getConnection("jdbc:sqlite:"+game.resolve("mineagent-runtime-data/runtime.db").toAbsolutePath().toUri()+"?mode=ro");var query=connection.prepareStatement("SELECT payload FROM mineagent_runtime_records WHERE world_id=? AND namespace='decisions_v1' AND record_id=? AND deleted=0")){
            for(String key:List.of("unknown","outbox")){
                query.setString(1,journal.path("world").asText());query.setString(2,journal.path(key).asText());
                try(var rows=query.executeQuery()){
                    if(!rows.next())throw new IllegalStateException("PREPARE_DECISION_MISSING");var stored=json.readTree(rows.getString(1));
                    if(!stored.path("request").path("status").asText().equals("RESOLVED")||key.equals("unknown")&&!stored.path("domainEffect").path("state").asText().equals("APPLYING")||key.equals("outbox")&&!stored.path("domainEffect").isNull())throw new IllegalStateException("PREPARE_BOUNDARY_CHANGED_BEFORE_EXIT");
                    checked.put(key,stored.path("domainEffect"));
                }
            }
        }
        Files.writeString(game.resolve("appearance-recovery-evidence/prepare-closed-check.json"),json.writeValueAsString(Map.of("databaseReadAfterProcessExit",true,"effects",checked)));
    }
    static void verify(Path file,String algorithm,String expected)throws Exception{try(var in=Files.newInputStream(file)){var d=MessageDigest.getInstance(algorithm);byte[] buffer=new byte[65536];for(int n;(n=in.read(buffer))!=-1;)d.update(buffer,0,n);if(!HexFormat.of().formatHex(d.digest()).equals(expected))throw new IllegalStateException("PINNED_ARTIFACT_MISMATCH: "+file.getFileName());}}
    static String sha256(Path file)throws Exception{try(var in=Files.newInputStream(file)){var d=MessageDigest.getInstance("SHA-256");byte[] buffer=new byte[65536];for(int n;(n=in.read(buffer))!=-1;)d.update(buffer,0,n);return HexFormat.of().formatHex(d.digest());}}
    static int bootTool(Path tool,Path game,com.fasterxml.jackson.databind.JsonNode plan,String planHash,String action,Path log)throws Exception{
        var command=List.of("pwsh","-NoProfile","-File",tool.toString(),"-GameDirectory",game.toString(),"-ModsDirectory",game.resolve("mods").toString(),"-OperationId",plan.path("operation").asText(),"-PlanHash",planHash,"-ConfirmModId",plan.path("modId").asText(),"-JvmStopped","-Action",action,"-Confirm:$false");
        var process=new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();if(!process.waitFor(2,TimeUnit.MINUTES)){process.destroyForcibly();process.waitFor(10,TimeUnit.SECONDS);throw new IllegalStateException("BOOT_OFFLINE_TOOL_TIMEOUT_"+action);}return process.exitValue();
    }
    static int bootRemove(Path tool,Path game,com.fasterxml.jackson.databind.JsonNode receipt,Path log)throws Exception{
        var command=List.of("pwsh","-NoProfile","-File",tool.toString(),"-GameDirectory",game.toString(),"-BuildId",receipt.path("id").asText(),"-ConfirmModId",receipt.path("modId").asText(),"-JvmStopped","-Confirm:$false");var process=new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();if(!process.waitFor(2,TimeUnit.MINUTES)){process.destroyForcibly();process.waitFor(10,TimeUnit.SECONDS);throw new IllegalStateException("BOOT_OFFLINE_REMOVE_TIMEOUT");}return process.exitValue();
    }
    static Path bootReceiptTarget(Path game,com.fasterxml.jackson.databind.JsonNode receipt){String slot=receipt.path("slot").asText("");String filename=slot.isEmpty()?"mineagent-boot-"+receipt.path("manifest").path("packageId").asText()+"-"+receipt.path("artifact").asText()+".jar":slot;return game.resolve("mods").resolve(filename);}
    static List<Path> bootPlanFiles(Path game)throws Exception{Path directory=game.resolve("mineagent-runtime-data/boot-install/upgrades");if(!Files.isDirectory(directory))throw new IllegalStateException("BOOT_UPGRADE_PLAN_DIRECTORY");try(var files=Files.list(directory)){return files.filter(Files::isRegularFile).filter(p->p.getFileName().toString().matches("[a-f0-9-]{36}\\.json")).sorted().toList();}}
    private static void opacityCutoff(Path game,String stage,int calls)throws Exception{
        if(!Files.isRegularFile(game.resolve("opacity-persistence/prepare.json")))throw new IllegalStateException("OPACITY_PREPARE_CHECKPOINT_MISSING");
        Path target=Files.createDirectories(game.resolve("opacity-persistence/cutoffs/"+stage));var hashes=new TreeMap<String,String>();
        Path data=game.resolve("mineagent-runtime-data");try(var files=Files.list(data)){for(var p:files.filter(Files::isRegularFile).toList())if(p.getFileName().toString().equals("runtime.db")||p.getFileName().toString().equals("runtime.db-wal")||p.getFileName().toString().matches("ui-placement-.*\\.db(?:-wal)?")){Files.copy(p,target.resolve(p.getFileName()));hashes.put(p.getFileName().toString(),HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p))));}}
        Path prefs=data.resolve("ui-state");if(Files.isDirectory(prefs)){Path out=Files.createDirectories(target.resolve("ui-state"));try(var files=Files.list(prefs)){for(var p:files.filter(Files::isRegularFile).toList()){Files.copy(p,out.resolve(p.getFileName()));hashes.put("ui-state/"+p.getFileName(),HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p))));}}}
        Files.copy(game.resolve("config/mineagent-webgui.properties"),target.resolve("renderer.properties"));
        Files.writeString(target.resolve("cutoff.json"),new ObjectMapper().writeValueAsString(Map.of("stage",stage,"afterJvmExit",true,"providerCalls",calls,"files",hashes)));
    }
}
