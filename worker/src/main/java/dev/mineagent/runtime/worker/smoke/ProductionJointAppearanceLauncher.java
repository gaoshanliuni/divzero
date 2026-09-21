package dev.mineagent.runtime.worker.smoke;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Current production WebGUI/MCEF/YSM profile. Each invocation keeps a new isolated directory, including failures. */
public final class ProductionJointAppearanceLauncher {
    private static final String YSM="b5e2445022e6c071b49c7eb312bc2e1628980a615506414a6ced06950999b8f627829cebe848bd8a2dc025c121abfb4286221bb641ad7648b13446e78dc07df3";
    private static final String WEBGUI="fd7feec4c60b74c9c6fc0d831fa0599267a2204353b9958e36649351a90dd7b0";
    private static final String MCEF="7043d21c4deaf4149401aa4d1761b73ca63386874c0c5ce1fa80d6ae601e65d0";
    private static final String NATIVE="aaff42ca9a0bf59f2f2590a1262f5facba6db68a90e63a31c926782750610862";
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
        if(!Set.of("","rules","cobble","blueprint","web","interaction","runtime_item","runtime_item_saved","runtime_throw","runtime_throw_saved","runtime_throw_sphere","runtime_throw_sphere_saved").contains(scenario)||!real&&(!scenario.isBlank()||!budget.isBlank()))throw new IllegalArgumentException("CONVERSATION_AGENT_REAL_SCENARIO");
        if(!budget.isBlank()&&(!budget.matches("[0-9]{1,2}")||Integer.parseInt(budget)<1||Integer.parseInt(budget)>24))throw new IllegalArgumentException("CONVERSATION_AGENT_REAL_BUDGET");
        String blueprint=Objects.toString(flags.get("mineagent.conversationAgentBlueprint"),"");if(scenario.equals("blueprint")==blueprint.isBlank())throw new IllegalArgumentException("CONVERSATION_AGENT_BLUEPRINT_SOURCE");
        String item=Objects.toString(flags.get("mineagent.conversationAgentItemArtifact"),"");if(Set.of("runtime_item_saved","runtime_throw_saved","runtime_throw_sphere_saved").contains(scenario)==item.isBlank())throw new IllegalArgumentException("RUNTIME_ITEM_ARTIFACT_SOURCE");
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
    public static void main(String[] args)throws Exception{
        if(args.length!=10)throw new IllegalArgumentException("Expected minecraftRoot versionDir runRoot modJar ysmJar javaHome worldSeed webguiJar mcefJar nativeArchive");
        validateConversationAgentMode(System.getProperties());
        boolean conversationAgent=Boolean.getBoolean("mineagent.conversationAgentSmoke");
        boolean conversationAgentReal=Boolean.getBoolean("mineagent.conversationAgentReal");
        boolean playerBody=Boolean.getBoolean("mineagent.playerBodySmoke");
        boolean desktopWindows=Boolean.getBoolean("mineagent.desktopWindowsSmoke");
        boolean nativeMention=Boolean.getBoolean("mineagent.nativeMentionSmoke"); if(nativeMention){var allowed=Set.of("mineagent.nativeMentionSmoke","mineagent.worldUiFixture","mineagent.worldUiAgentFixture");System.getProperties().forEach((k,v)->{if(k.toString().startsWith("mineagent.")&&!allowed.contains(k.toString())&&!v.toString().isBlank()&&!v.toString().equals("false"))throw new IllegalArgumentException("MENTION_MODE_CONFLICT");});}
        validateBodySurvivalMode(System.getProperties());
        validateBodyRecoveryMode(System.getProperties());
        validateAgentManagementMode(System.getProperties());
        validateResourcePackMode(System.getProperties());
        validateClientScriptMode(System.getProperties());
        validateClientJavaMode(System.getProperties());
        validateClientDependencyMode(System.getProperties());
        validateClientStudioMode(System.getProperties());
        validatePackageAssetMode(System.getProperties());
        validateBootUpgradeMode(System.getProperties());
        validateBootDependencyMode(System.getProperties());
        validateSettingsMode(System.getProperties());
        validateNativeAtlasMode(System.getProperties());
        validateWorldAgentMode(System.getProperties());
        validatePersonaMode(System.getProperties());
        validateConversationMode(System.getProperties());
        validateWorkspaceMode(System.getProperties());
        validateViewSettingsMode(System.getProperties());
        validateObjectDirectoryMode(System.getProperties());
        validateSharedStateMode(System.getProperties());
        validateAudienceMode(System.getProperties());
        validateEventMode(System.getProperties());
        validateScheduleMode(System.getProperties());
        validateSharedAgentMode(System.getProperties());
        validateLivePlacementMode(System.getProperties());
        var minecraft=Path.of(args[0]).toAbsolutePath();var version=Path.of(args[1]).toAbsolutePath();var java=Path.of(args[5]).resolve("bin/java.exe").toAbsolutePath();
        boolean absent=args[4].equals("ABSENT");Path ysm=absent?null:Path.of(args[4]),webgui=Path.of(args[7]),mcef=Path.of(args[8]),archive=Path.of(args[9]);if(!absent)verify(ysm,"SHA-512",YSM);verify(webgui,"SHA-256",WEBGUI);verify(mcef,"SHA-256",MCEF);verify(archive,"SHA-256",NATIVE);
        boolean patchResume=!System.getProperty("mineagent.worldPatchProfile","").isBlank();
        boolean uiRepair=!System.getProperty("mineagent.worldUiRepairProfile","").isBlank();
        var selected=resumeSelection(Path.of(args[2]),System.getProperty("mineagent.worldPatchProfile",""),System.getProperty("mineagent.worldUiRepairProfile",""));
        if(uiRepair&&List.of("mineagent.worldUiModelSmoke","mineagent.worldUiFailureSmoke","mineagent.worldUiSmoke","mineagent.worldMoveSmoke","mineagent.appearanceChoiceSmoke","mineagent.appearanceRecoverySmoke","mineagent.appearanceAgentSmoke","mineagent.runtimeObjectSmoke","mineagent.worldPackageObjectSmoke").stream().anyMatch(Boolean::getBoolean))throw new IllegalArgumentException("UI_REPAIR_MODE_CONFLICT");
        Path run=selected.isPresent()?selected.get():freshRun(Path.of(args[2])),game=Files.createDirectories(run.resolve("game")),mods=Files.createDirectories(game.resolve("mods"));
        try(var guard=FileChannel.open(run.resolve("launch.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);var lock=guard.tryLock()){
        if(lock==null)throw new IllegalStateException("PROFILE_ALREADY_LAUNCHING");
        Path output=selected.isPresent()?Files.createDirectories(run.resolve(uiRepair?"ui-repair-attempts":"patch-attempts").resolve(UUID.randomUUID().toString())):run;
        if(selected.isPresent()){
            if(!Files.isRegularFile(game.resolve("mineagent-runtime-data/runtime.db"))||!Files.isDirectory(game.resolve("saves/SmokeWorld")))throw new IllegalArgumentException("RESUME_DATA_MISSING");
            UUID.fromString(System.getProperty(uiRepair?"mineagent.worldUiRepairGeneration":"mineagent.worldPatchGeneration"));
            Path target=mods.resolve(Path.of(args[3]).getFileName());if(!target.toRealPath().startsWith(game.toRealPath())||Files.isSymbolicLink(target))throw new IllegalArgumentException("RESUME_MOD_PATH");
            verify(mods.resolve(webgui.getFileName()),"SHA-256",WEBGUI);verify(mods.resolve(mcef.getFileName()),"SHA-256",MCEF);
            Files.copy(target,output.resolve("previous-mod.jar"));Files.copy(run.resolve("process.json"),output.resolve("previous-process.json"));Files.copy(Path.of(args[3]),target,StandardCopyOption.REPLACE_EXISTING);
        }else{
        for(var file:List.of(Path.of(args[3]),webgui,mcef))Files.copy(file,mods.resolve(file.getFileName()));if(!absent)Files.copy(ysm,mods.resolve(ysm.getFileName()));
        Path nativeRoot=Files.createDirectories(mods.resolve("mcef-libraries"));
        var extract=new ProcessBuilder("tar","-xzf",archive.toAbsolutePath().toString(),"-C",nativeRoot.toString()).redirectErrorStream(true).redirectOutput(run.resolve("extract.log").toFile()).start();
        if(!extract.waitFor(120,TimeUnit.SECONDS)){extract.destroyForcibly();throw new IllegalStateException("NATIVE_EXTRACTION_TIMEOUT");}if(extract.exitValue()!=0)throw new IllegalStateException("NATIVE_EXTRACTION_FAILED");
        Path seed=Path.of(args[6]).toAbsolutePath().normalize(),world=game.resolve("saves/SmokeWorld");
        try(var paths=Files.walk(seed)){for(var source:paths.toList()){
            if(Files.isSymbolicLink(source))throw new IllegalStateException("SYMLINK_WORLD_FIXTURE");var target=world.resolve(seed.relativize(source));
            if(Files.isDirectory(source))Files.createDirectories(target);else if(!source.getFileName().toString().equals("session.lock"))Files.copy(source,target);
        }}
        if(Boolean.getBoolean("mineagent.packageAssetSmoke")){
            Path other=game.resolve("saves/SmokeWorldAsset");try(var paths=Files.walk(seed)){for(var source:paths.toList()){
                if(Files.isSymbolicLink(source))throw new IllegalStateException("SYMLINK_WORLD_FIXTURE");var target=other.resolve(seed.relativize(source));
                if(Files.isDirectory(source))Files.createDirectories(target);else if(!source.getFileName().toString().equals("session.lock"))Files.copy(source,target);
            }}
            try(var identity=dev.mineagent.runtime.core.persistence.WorldSaveIdentity.open(game.resolve("mineagent-runtime-data"),other,UUID.randomUUID())){if(!identity.ready())throw new IllegalStateException("PACKAGE_ASSET_SECOND_WORLD_IDENTITY");}
        }
        Files.createDirectories(game.resolve("config/mcef"));Files.writeString(game.resolve("config/mcef/mcef.properties"),"cef-disable-web-security=false\nenforce-download-checksums=true\nbrowser-preload-enabled=false\nskip-download=true\n",StandardCharsets.UTF_8);
        if(Set.of("opacity-half","opacity-zero").contains(System.getProperty("mineagent.livePlacementSmoke","")))Files.writeString(game.resolve("config/mineagent-webgui.properties"),"renderer=LIVE_ATLAS\n",StandardCharsets.UTF_8);
        Files.writeString(game.resolve("options.txt"),"onboardAccessibility:false\ntutorialStep:none\npauseOnLostFocus:false\nmaxFps:60\nrenderDistance:6\nguiScale:2\nsoundCategory_master:0.0\n",StandardCharsets.UTF_8);
        }
        String id=version.getFileName().toString();var manifest=new ObjectMapper().readTree(version.resolve(id+".json").toFile());
        var libraries=new ArrayList<>(ProductionYsmSmokeLauncher.resolveLibraries(minecraft,manifest,new ProductionYsmSmokeLauncher.OsContext("windows",System.getProperty("os.version"),System.getProperty("os.arch"))));libraries.add(version.resolve(id+".jar"));
        String natives=version.resolve(id+"-natives").toString();
        var cmd=new ArrayList<>(List.of(java.toString(),"--sun-misc-unsafe-memory-access=allow","--enable-native-access=ALL-UNNAMED","-Djava.library.path="+natives,"-Djna.tmpdir="+natives,"-Dorg.lwjgl.system.SharedLibraryExtractPath="+natives,"-Dio.netty.native.workdir="+natives,
                "-DlibraryDirectory="+minecraft.resolve("libraries"),"--add-opens=java.base/java.lang.invoke=ALL-UNNAMED","--add-exports=jdk.naming.dns/com.sun.jndi.dns=java.naming","-Dfile.encoding=UTF-8","-Dstdout.encoding=UTF-8","-Dstderr.encoding=UTF-8",
                "-Dmineagent.uiSessionSmokeTest=true","-Dmineagent.ysmJointSmoke=true","-Dmcef.java.cef.commit=eaeb3d4370aa3526ee237ad1981ad59af3de4dd1","-Xmx4G","-cp",String.join(System.getProperty("path.separator"),libraries.stream().map(Path::toString).toList()),"net.neoforged.fml.startup.Client",
                "--username","YsmGui","--version",id,"--gameDir",game.toString(),"--assetsDir",minecraft.resolve("assets").toString(),"--assetIndex",manifest.path("assetIndex").path("id").asText("30"),"--uuid","00000000000000000000000000000002","--accessToken","0","--clientId","0","--xuid","0","--versionType","release","--width","1600","--height","1000","--quickPlaySingleplayer=SmokeWorld","--fml.neoForgeVersion","26.1.2.106","--fml.mcVersion","26.1.2","--fml.neoFormVersion","1"));
        if(conversationAgent){if(!absent||nativeMention||desktopWindows||playerBody)throw new IllegalArgumentException("CONVERSATION_AGENT_MODE_CONFLICT");cmd.remove("-Dmineagent.uiSessionSmokeTest=true");cmd.remove("-Dmineagent.ysmJointSmoke=true");cmd.add(1,"-Dmineagent.conversationAgentSmoke=true");if(conversationAgentReal){cmd.add(1,"-Dmineagent.conversationAgentReal=true");cmd.add(1,"-Dmineagent.conversationAgentScenario="+System.getProperty("mineagent.conversationAgentScenario",""));}}
        if(playerBody){if(!absent||nativeMention||desktopWindows)throw new IllegalArgumentException("PLAYER_BODY_MODE_CONFLICT");cmd.remove("-Dmineagent.uiSessionSmokeTest=true");cmd.remove("-Dmineagent.ysmJointSmoke=true");cmd.add(1,"-Dmineagent.playerBodySmoke=true");}
        if(desktopWindows){if(!absent||nativeMention)throw new IllegalArgumentException("DESKTOP_MODE_CONFLICT");cmd.remove("-Dmineagent.uiSessionSmokeTest=true");cmd.remove("-Dmineagent.ysmJointSmoke=true");cmd.add(1,"-Dmineagent.desktopWindowsSmoke=true");String renderer=System.getProperty("mineagent.desktopWindowsRenderer","");if(renderer.isBlank())renderer="LIVE_ATLAS";if(!Set.of("LIVE_ATLAS","LEGACY").contains(renderer))throw new IllegalArgumentException("DESKTOP_RENDERER");Files.writeString(game.resolve("config/mineagent-webgui.properties"),"renderer="+renderer+"\n");}
        if(nativeMention){if(!absent)throw new IllegalArgumentException("MENTION_WITHOUT_YSM");cmd.remove("-Dmineagent.uiSessionSmokeTest=true");cmd.remove("-Dmineagent.ysmJointSmoke=true");cmd.add(1,"-Dmineagent.nativeMentionSmoke=true");}
        if(absent)cmd.add(1,"-Dmineagent.ysmJointAbsent=true");
        boolean settings=Boolean.getBoolean("mineagent.settingsSmoke");if(settings){if(!absent)throw new IllegalArgumentException("SETTINGS_WITHOUT_YSM");cmd.remove("-Dmineagent.uiSessionSmokeTest=true");cmd.remove("-Dmineagent.ysmJointSmoke=true");cmd.add(1,"-Dmineagent.settingsSmoke=true");}
        boolean agentManagement=Boolean.getBoolean("mineagent.agentManagementSmoke");if(agentManagement){if(!absent)throw new IllegalArgumentException("MANAGEMENT_FIXTURE_WITHOUT_YSM");cmd.remove("-Dmineagent.uiSessionSmokeTest=true");cmd.remove("-Dmineagent.ysmJointSmoke=true");cmd.add(1,"-Dmineagent.agentManagementSmoke=true");}
        boolean nativeApiCoder=Boolean.getBoolean("mineagent.nativeApiCoderSmoke"),nativeApi=Boolean.getBoolean("mineagent.nativeApiSmoke")||nativeApiCoder;if(nativeApi){if(!absent)throw new IllegalArgumentException("NATIVE_API_FIXTURE_WITHOUT_YSM");cmd.remove("-Dmineagent.uiSessionSmokeTest=true");cmd.remove("-Dmineagent.ysmJointSmoke=true");cmd.add(1,"-Dmineagent.nativeApiSmoke=true");if(nativeApiCoder)cmd.add(1,"-Dmineagent.nativeApiCoderSmoke=true");}
        boolean resourcePack=Boolean.getBoolean("mineagent.resourcePackSmoke");if(resourcePack){if(!absent)throw new IllegalArgumentException("RESOURCE_PACK_FIXTURE_WITHOUT_YSM");cmd.remove("-Dmineagent.uiSessionSmokeTest=true");cmd.remove("-Dmineagent.ysmJointSmoke=true");cmd.add(1,"-Dmineagent.resourcePackSmoke=true");}
        boolean clientScript=Boolean.getBoolean("mineagent.clientScriptSmoke");if(clientScript){if(!absent)throw new IllegalArgumentException("CLIENT_SCRIPT_FIXTURE_WITHOUT_YSM");cmd.remove("-Dmineagent.uiSessionSmokeTest=true");cmd.remove("-Dmineagent.ysmJointSmoke=true");cmd.add(1,"-Dmineagent.clientScriptSmoke=true");}
        boolean clientJava=Boolean.getBoolean("mineagent.clientJavaSmoke");if(clientJava){if(!absent)throw new IllegalArgumentException("CLIENT_JAVA_FIXTURE_WITHOUT_YSM");cmd.remove("-Dmineagent.uiSessionSmokeTest=true");cmd.remove("-Dmineagent.ysmJointSmoke=true");cmd.add(1,"-Dmineagent.clientJavaSmoke=true");}
        boolean clientDependency=Boolean.getBoolean("mineagent.clientDependencySmoke");if(clientDependency){if(!absent)throw new IllegalArgumentException("CLIENT_DEPENDENCY_FIXTURE_WITHOUT_YSM");cmd.remove("-Dmineagent.uiSessionSmokeTest=true");cmd.remove("-Dmineagent.ysmJointSmoke=true");cmd.add(1,"-Dmineagent.clientDependencySmoke=true");}
        boolean clientStudio=Boolean.getBoolean("mineagent.clientStudioSmoke");if(clientStudio){if(!absent)throw new IllegalArgumentException("CLIENT_STUDIO_FIXTURE_WITHOUT_YSM");cmd.remove("-Dmineagent.uiSessionSmokeTest=true");cmd.remove("-Dmineagent.ysmJointSmoke=true");cmd.add(1,"-Dmineagent.clientStudioSmoke=true");}
        boolean packageAssets=Boolean.getBoolean("mineagent.packageAssetSmoke");if(packageAssets){if(!absent)throw new IllegalArgumentException("PACKAGE_ASSET_FIXTURE_WITHOUT_YSM");cmd.remove("-Dmineagent.uiSessionSmokeTest=true");cmd.remove("-Dmineagent.ysmJointSmoke=true");cmd.add(1,"-Dmineagent.packageAssetSmoke=true");}
        boolean bootUpgrade=Boolean.getBoolean("mineagent.bootUpgradeSmoke");if(bootUpgrade){if(!absent)throw new IllegalArgumentException("BOOT_UPGRADE_FIXTURE_WITHOUT_YSM");cmd.remove("-Dmineagent.uiSessionSmokeTest=true");cmd.remove("-Dmineagent.ysmJointSmoke=true");cmd.add(1,"-Dmineagent.bootUpgradeSmoke=true");}
        boolean bootDependency=Boolean.getBoolean("mineagent.bootDependencySmoke");if(bootDependency){if(!absent)throw new IllegalArgumentException("BOOT_DEPENDENCY_FIXTURE_WITHOUT_YSM");cmd.remove("-Dmineagent.uiSessionSmokeTest=true");cmd.remove("-Dmineagent.ysmJointSmoke=true");cmd.add(1,"-Dmineagent.bootDependencySmoke=true");}
        boolean bodyRecovery=Boolean.getBoolean("mineagent.bodyRecoverySmoke");if(bodyRecovery){if(!absent)throw new IllegalArgumentException("BODY_RECOVERY_WITHOUT_YSM");cmd.remove("-Dmineagent.uiSessionSmokeTest=true");cmd.remove("-Dmineagent.ysmJointSmoke=true");cmd.add(1,"-Dmineagent.bodyRecoverySmoke=true");}
        boolean bodySurvival=Boolean.getBoolean("mineagent.bodySurvivalSmoke");if(bodySurvival){if(!absent)throw new IllegalArgumentException("SURVIVAL_FIXTURE_WITHOUT_YSM");cmd.remove("-Dmineagent.uiSessionSmokeTest=true");cmd.remove("-Dmineagent.ysmJointSmoke=true");cmd.add(1,"-Dmineagent.bodySurvivalSmoke=true");}
        if(Boolean.getBoolean("mineagent.workspaceSmoke"))cmd.add(1,"-Dmineagent.workspaceSmoke=true");
        if(Boolean.getBoolean("mineagent.viewSettingsSmoke"))cmd.add(1,"-Dmineagent.viewSettingsSmoke=true");
        if(Boolean.getBoolean("mineagent.viewSettingsPaintSmoke"))cmd.add(1,"-Dmineagent.viewSettingsPaintSmoke=true");
        if(Boolean.getBoolean("mineagent.nativeAtlasSmoke"))cmd.add(1,"-Dmineagent.nativeAtlasSmoke=true");
        if(Boolean.getBoolean("mineagent.nativePopupLegacySmoke"))cmd.add(1,"-Dmineagent.nativePopupLegacySmoke=true");
        if(Boolean.getBoolean("mineagent.nativePopupSmoke"))cmd.add(1,"-Dmineagent.nativePopupSmoke=true");
        if(Boolean.getBoolean("mineagent.nativeAtlasInputSmoke"))cmd.add(1,"-Dmineagent.nativeAtlasInputSmoke=true");
        if(Boolean.getBoolean("mineagent.sharedStateSmoke"))cmd.add(1,"-Dmineagent.sharedStateSmoke=true");
        if(Boolean.getBoolean("mineagent.objectDirectorySmoke"))cmd.add(1,"-Dmineagent.objectDirectorySmoke=true");
        if(!System.getProperty("mineagent.livePlacementSmoke","").isEmpty()){cmd.add(1,"-Dmineagent.livePlacementSmoke="+System.getProperty("mineagent.livePlacementSmoke"));cmd.add(1,"-Dmineagent.livePlacementModel="+System.getProperty("mineagent.livePlacementModel",""));}
        if(uiRepair){cmd.remove("-Dmineagent.uiSessionSmokeTest=true");cmd.remove("-Dmineagent.ysmJointSmoke=true");cmd.add(1,"-Dmineagent.worldUiRepairSmoke=true");cmd.add(1,"-Dmineagent.worldUiRepairGeneration="+System.getProperty("mineagent.worldUiRepairGeneration"));cmd.add(1,"-Dmineagent.worldUiRepairModel="+System.getProperty("mineagent.worldUiRepairModel",""));cmd.add(1,"-Dmineagent.worldUiRepairRaw="+System.getProperty("mineagent.worldUiRepairRaw",""));cmd.add(1,"-Dmineagent.worldUiRepairVerifyOnly="+Boolean.getBoolean("mineagent.worldUiRepairVerifyOnly"));cmd.add(1,"-Dmineagent.worldUiRepairPrevious="+System.getProperty("mineagent.worldUiRepairPrevious",""));}
        if(Boolean.getBoolean("mineagent.appearanceChoiceSmoke")){
            if(absent)throw new IllegalArgumentException("Choice fixture requires the pinned YSM");
            cmd.add(1,"-Dmineagent.appearanceChoiceSmoke=true");
        }
        boolean recovery=Boolean.getBoolean("mineagent.appearanceRecoverySmoke");
        boolean sharedAgent=Boolean.getBoolean("mineagent.sharedAgentSmoke");if(sharedAgent){cmd.remove("-Dmineagent.uiSessionSmokeTest=true");cmd.remove("-Dmineagent.ysmJointSmoke=true");cmd.add(1,"-Dmineagent.sharedAgentSmoke=true");}
        boolean schedule=Boolean.getBoolean("mineagent.scheduleSmoke");if(schedule){cmd.remove("-Dmineagent.uiSessionSmokeTest=true");cmd.remove("-Dmineagent.ysmJointSmoke=true");cmd.add(1,"-Dmineagent.scheduleSmoke=true");}
        boolean events=Boolean.getBoolean("mineagent.eventSmoke");if(events){cmd.remove("-Dmineagent.uiSessionSmokeTest=true");cmd.remove("-Dmineagent.ysmJointSmoke=true");cmd.add(1,"-Dmineagent.eventSmoke=true");}
        boolean audience=Boolean.getBoolean("mineagent.audienceSmoke");if(audience){cmd.remove("-Dmineagent.uiSessionSmokeTest=true");cmd.remove("-Dmineagent.ysmJointSmoke=true");cmd.add(1,"-Dmineagent.audienceSmoke=true");}
        boolean conversation=Boolean.getBoolean("mineagent.conversationSmoke");if(conversation){cmd.remove("-Dmineagent.uiSessionSmokeTest=true");cmd.remove("-Dmineagent.ysmJointSmoke=true");cmd.add(1,"-Dmineagent.conversationSmoke=true");cmd.add(1,"-Dmineagent.conversationNativeSmoke="+Boolean.getBoolean("mineagent.conversationNativeSmoke"));cmd.add(1,"-Dmineagent.conversationSummarySmoke="+Boolean.getBoolean("mineagent.conversationSummarySmoke"));cmd.add(1,"-Dmineagent.conversationSummaryFailureMode="+System.getProperty("mineagent.conversationSummaryFailureMode",""));}
        boolean persona=Boolean.getBoolean("mineagent.personaSmoke");if(persona){cmd.remove("-Dmineagent.uiSessionSmokeTest=true");cmd.remove("-Dmineagent.ysmJointSmoke=true");cmd.add(1,"-Dmineagent.personaSmoke=true");}
        boolean agent=Boolean.getBoolean("mineagent.appearanceAgentSmoke");
        boolean objects=Boolean.getBoolean("mineagent.runtimeObjectSmoke");
        boolean generatedObjects=Boolean.getBoolean("mineagent.worldPackageObjectSmoke");
        boolean negative=Boolean.getBoolean("mineagent.worldUiFailureSmoke");
        boolean worldUiModel=Boolean.getBoolean("mineagent.worldUiModelSmoke")||negative;
        if(negative&&(Boolean.getBoolean("mineagent.worldUiModelSmoke")||patchResume||generatedObjects||objects||agent||recovery||Boolean.getBoolean("mineagent.worldUiSmoke")||Boolean.getBoolean("mineagent.appearanceChoiceSmoke")))throw new IllegalArgumentException("Controlled provider must use its own fresh fixture mode");
        boolean worldUiAgent=Boolean.getBoolean("mineagent.worldUiAgentSmoke");
        String worldUiAgentCancel=System.getProperty("mineagent.worldUiAgentCancelMode","");
        String worldUiAgentModel=System.getProperty("mineagent.worldUiAgentModel","");
        if(System.getProperty("mineagent.livePlacementSmoke","").equals("real"))copyProvider(Path.of(System.getProperty("mineagent.livePlacementProviderDb")),game,System.getProperty("mineagent.livePlacementModel"));
        if(conversationAgentReal){
            copyProvider(Path.of(System.getProperty("mineagent.conversationAgentProviderDb")),game,"deepseek-flash");
            try(var config=dev.mineagent.runtime.core.config.ServerConfigService.open(game.resolve("mineagent-runtime-data/runtime.db"))){
                if(!"https://api.deepseek.com/v1/".equals(config.snapshot().values().get("provider.openai.baseUrl")))throw new IllegalArgumentException("REAL_PROVIDER_REQUIRES_DEEPSEEK_OFFICIAL_ENDPOINT");
                if(!config.apply(new dev.mineagent.runtime.api.config.ConfigPatch(config.snapshot().revision(),Map.of("provider.openai.enabled","true","runtime.initialized","true")),true).accepted())throw new IllegalStateException("REAL_PROVIDER_CONFIG");
            }
            if(System.getProperty("mineagent.conversationAgentScenario","").equals("blueprint")){
                Path source=Path.of(System.getProperty("mineagent.conversationAgentBlueprint"));if(!Files.isRegularFile(source,LinkOption.NOFOLLOW_LINKS)||Files.size(source)>8*1024*1024||!source.getFileName().toString().endsWith(".nbt"))throw new IllegalArgumentException("BLUEPRINT_FIXTURE_SOURCE");
                Files.copy(source,Files.createDirectory(game.resolve("schematics")).resolve(source.getFileName()));cmd.add(1,"-Dmineagent.conversationAgentBlueprintName="+source.getFileName());
            }
            if(Set.of("runtime_item_saved","runtime_throw_saved","runtime_throw_sphere_saved").contains(System.getProperty("mineagent.conversationAgentScenario",""))){Path artifact=Path.of(System.getProperty("mineagent.conversationAgentItemArtifact"));if(!Files.isRegularFile(artifact,LinkOption.NOFOLLOW_LINKS)||Files.size(artifact)>1024*1024)throw new IllegalArgumentException("RUNTIME_ITEM_ARTIFACT_INVALID");Files.copy(artifact,game.resolve("runtime-item-source.json"));}
            Files.createDirectory(game.resolve("real-provider-audit"));
        }
        try(var conversationAgentProvider=conversationAgent&&!conversationAgentReal?new ConversationAgentControlledProvider(game):null;var playerBodyProvider=playerBody?new PlayerBodyControlledProvider(game):null;var desktopProvider=desktopWindows?new DesktopWindowsControlledProvider(game):null;var nativeMentionProvider=nativeMention?new NativeMentionControlledProvider(game):null;var settingsEndpoint=settings?new SettingsControlledEndpoint(game):null;var managementProvider=agentManagement?new AgentManagementControlledProvider(game):null;var nativeCoderProvider=nativeApiCoder?new NativeApiCoderControlledProvider(game):null;var bootUpgradeProvider=bootUpgrade?new BootUpgradeControlledProvider(game):null;var sharedAgentProvider=sharedAgent?new SharedAgentControlledProvider(game):null;var scheduleProvider=schedule?new ScheduleControlledProvider(game):null;var eventProvider=events?new EventControlledProvider(game):null;var livePlacementProvider=Set.of("positive","stale","cancel","opacity-half","opacity-zero","opacity-unavailable").contains(System.getProperty("mineagent.livePlacementSmoke",""))?new LivePlacementControlledProvider(game,System.getProperty("mineagent.livePlacementSmoke")):null;var conversationProvider=conversation?new ConversationControlledProvider(game,Boolean.getBoolean("mineagent.conversationNativeSmoke"),Boolean.getBoolean("mineagent.conversationSummarySmoke"),System.getProperty("mineagent.conversationSummaryFailureMode","")):null;var negativeProvider=negative?new NegativeProvider(game):null;var worldAgentProvider=worldUiAgent&&worldUiAgentModel.isEmpty()?new WorldUiControlledPlanner(game,worldUiAgentCancel):null){
        if(worldUiAgent){if(!worldUiAgentModel.isEmpty())copyProvider(Path.of(System.getProperty("mineagent.appearanceAgentProviderDb","")),game,worldUiAgentModel);cmd.remove("-Dmineagent.uiSessionSmokeTest=true");cmd.remove("-Dmineagent.ysmJointSmoke=true");cmd.add(1,"-Dmineagent.worldUiAgentSmoke=true");cmd.add(1,"-Dmineagent.worldUiAgentFixture="+System.getProperty("mineagent.worldUiAgentFixture"));cmd.add(1,"-Dmineagent.worldUiAgentCancelMode="+worldUiAgentCancel);cmd.add(1,"-Dmineagent.worldUiAgentModel="+worldUiAgentModel);}
        if(worldUiModel){
            if(patchResume||generatedObjects||objects||agent||recovery||Boolean.getBoolean("mineagent.worldUiSmoke")||Boolean.getBoolean("mineagent.appearanceChoiceSmoke"))throw new IllegalArgumentException("World UI model verification requires a fresh separate mode");
            if(!negative)copyProvider(Path.of(System.getProperty("mineagent.appearanceAgentProviderDb","")),game,System.getProperty("mineagent.worldUiModel",""));
            cmd.remove("-Dmineagent.uiSessionSmokeTest=true");cmd.remove("-Dmineagent.ysmJointSmoke=true");cmd.add(1,"-Dmineagent.worldUiModelSmoke=true");
            if(negative)cmd.add(1,"-Dmineagent.worldUiFailureSmoke=true");
        }
        if(patchResume){if(generatedObjects||objects||agent||recovery||Boolean.getBoolean("mineagent.appearanceChoiceSmoke"))throw new IllegalArgumentException("World patch resume must be a separate mode");cmd.remove("-Dmineagent.uiSessionSmokeTest=true");cmd.remove("-Dmineagent.ysmJointSmoke=true");cmd.add(1,"-Dmineagent.worldPatchSmoke=true");cmd.add(1,"-Dmineagent.worldPatchVerifyOnly="+Boolean.getBoolean("mineagent.worldPatchVerifyOnly"));cmd.add(1,"-Dmineagent.worldPatchGeneration="+System.getProperty("mineagent.worldPatchGeneration"));}
        if(generatedObjects){
            if(objects||recovery||agent||Boolean.getBoolean("mineagent.appearanceChoiceSmoke"))throw new IllegalArgumentException("Generated object mode must be separate");
            copyProvider(Path.of(System.getProperty("mineagent.appearanceAgentProviderDb","")),game);
            cmd.remove("-Dmineagent.uiSessionSmokeTest=true");cmd.remove("-Dmineagent.ysmJointSmoke=true");cmd.add(1,"-Dmineagent.worldPackageTaskSmoke=true");cmd.add(1,"-Dmineagent.worldPackageObjectSmoke=true");
        }
        boolean worldMove=Boolean.getBoolean("mineagent.worldMoveSmoke");
        boolean worldUi=Boolean.getBoolean("mineagent.worldUiSmoke")||worldMove;if(worldUi){if(patchResume||generatedObjects||objects||agent||recovery||worldUiModel)throw new IllegalArgumentException("World UI fixture needs a fresh isolated mode");cmd.remove("-Dmineagent.uiSessionSmokeTest=true");cmd.remove("-Dmineagent.ysmJointSmoke=true");cmd.add(1,"-Dmineagent.worldUiSmoke=true");cmd.add(1,"-Dmineagent.worldUiFixture="+System.getProperty("mineagent.worldUiFixture"));if(worldMove)cmd.add(1,"-Dmineagent.worldMoveSmoke=true");}
        if(objects){if(recovery||agent||Boolean.getBoolean("mineagent.appearanceChoiceSmoke"))throw new IllegalArgumentException("Object fixture mode must be separate");cmd.remove("-Dmineagent.uiSessionSmokeTest=true");cmd.remove("-Dmineagent.ysmJointSmoke=true");cmd.add(1,"-Dmineagent.runtimeObjectSmoke=true");}
        if(agent){
            if(absent||recovery||Boolean.getBoolean("mineagent.appearanceChoiceSmoke"))throw new IllegalArgumentException("Agent fixture requires its own pinned-YSM live mode");
            copyProvider(Path.of(System.getProperty("mineagent.appearanceAgentProviderDb","")),game);
            cmd.remove("-Dmineagent.uiSessionSmokeTest=true");cmd.remove("-Dmineagent.ysmJointSmoke=true");cmd.add(1,"-Dmineagent.appearanceAgentSmoke=true");
        }
        if(recovery){
            if(absent||Boolean.getBoolean("mineagent.appearanceChoiceSmoke"))throw new IllegalArgumentException("Recovery requires pinned YSM and its own fixture mode");
            cmd.remove("-Dmineagent.uiSessionSmokeTest=true");cmd.remove("-Dmineagent.ysmJointSmoke=true");cmd.add(1,"-Dmineagent.appearanceRecoverySmoke=true");
        }
        System.out.println("PRODUCTION_JOINT_RUN="+run);
        if(patchResume)System.out.println("PRODUCTION_WORLD_PATCH_ATTEMPT="+output);
        if(uiRepair)System.out.println("PRODUCTION_WORLD_UI_REPAIR_ATTEMPT="+output);
        boolean shared=Boolean.getBoolean("mineagent.sharedStateSmoke"),opacityPersistence=Boolean.getBoolean("mineagent.opacityPersistenceSmoke");
        com.fasterxml.jackson.databind.JsonNode activeBootPlan=null;String activeBootPlanHash="";Path repositoryRoot=Path.of(args[3]).toAbsolutePath().getParent().getParent().getParent().getParent(),bootToolPath=repositoryRoot.resolve("tools/Update-MineAgentBootExtension.ps1"),bootRemoveToolPath=repositoryRoot.resolve("tools/Remove-MineAgentBootExtension.ps1");if(bootUpgrade&&!Files.isRegularFile(bootToolPath)||bootDependency&&!Files.isRegularFile(bootRemoveToolPath))throw new IllegalStateException("BOOT_OFFLINE_TOOL_MISSING");
        for(String stage:bootUpgrade?List.of("prepare","upgrade","verify","rollback"):bootDependency?List.of("prepare","verify","removed"):packageAssets?List.of("prepare","reuse","verify"):settings?personaStages(true):bodyRecovery?bodyRecoveryStages():opacityPersistence?opacityPersistenceStages():persona||conversation||audience||events||schedule||shared||sharedAgent||clientScript||clientJava?personaStages(true):stages(recovery)){
            var stageCmd=new ArrayList<>(cmd);if(settings)stageCmd.add(1,"-Dmineagent.settingsStage="+stage);if(bodyRecovery)stageCmd.add(1,"-Dmineagent.bodyRecoveryStage="+stage);if(opacityPersistence){stageCmd.add(1,"-Dmineagent.opacityPersistenceSmoke=true");stageCmd.add(1,"-Dmineagent.opacityPersistenceStage="+stage);}
            if(recovery)stageCmd.add(1,"-Dmineagent.appearanceRecoveryStage="+stage);
            if(persona)stageCmd.add(1,"-Dmineagent.personaStage="+stage);
            if(conversation)stageCmd.add(1,"-Dmineagent.conversationStage="+stage);
            if(audience)stageCmd.add(1,"-Dmineagent.audienceStage="+stage);
            if(events)stageCmd.add(1,"-Dmineagent.eventStage="+stage);
            if(schedule)stageCmd.add(1,"-Dmineagent.scheduleStage="+stage);
            if(sharedAgent)stageCmd.add(1,"-Dmineagent.sharedAgentStage="+stage);
            if(shared)stageCmd.add(1,"-Dmineagent.sharedStateStage="+stage);
            if(clientScript)stageCmd.add(1,"-Dmineagent.clientScriptStage="+stage);
            if(clientJava)stageCmd.add(1,"-Dmineagent.clientJavaStage="+stage);
            if(bootUpgrade)stageCmd.add(1,"-Dmineagent.bootUpgradeStage="+stage);
            if(bootDependency)stageCmd.add(1,"-Dmineagent.bootDependencyStage="+stage);
            if(packageAssets){stageCmd.add(1,"-Dmineagent.packageAssetStage="+stage);if(!stage.equals("prepare")){int worldArg=stageCmd.indexOf("--quickPlaySingleplayer=SmokeWorld");if(worldArg<0)throw new IllegalStateException("PACKAGE_ASSET_WORLD_ARGUMENT");stageCmd.set(worldArg,"--quickPlaySingleplayer=SmokeWorldAsset");}}
            String suffix=settings||packageAssets||bodyRecovery||opacityPersistence||recovery||persona||conversation||audience||events||schedule||shared||sharedAgent||clientScript||clientJava||bootUpgrade||bootDependency?"-"+stage:"";Path log=output.resolve("console"+suffix+".log");
            var builder=new ProcessBuilder(stageCmd).directory(game.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());builder.environment().put("LOCALAPPDATA",Files.createDirectories(run.resolve("profile")).toString());
            if(conversationAgentReal){builder.environment().put("MINEAGENT_REAL_PROVIDER_AUDIT_DIR",game.resolve("real-provider-audit").toString());String cap=System.getProperty("mineagent.conversationAgentCallBudget","");builder.environment().put("MINEAGENT_REAL_PROVIDER_MAX_CALLS",Set.of("runtime_item_saved","runtime_throw_saved","runtime_throw_sphere_saved").contains(System.getProperty("mineagent.conversationAgentScenario",""))?"0":cap.isBlank()?"24":cap);}
            var process=builder.start();Files.writeString(run.resolve("process"+suffix+".json"),new ObjectMapper().writeValueAsString(Map.of("pid",process.pid(),"run",run.toString(),"stage",stage)));
            if(!process.waitFor(conversationAgentReal?12:uiRepair?30:generatedObjects||patchResume||worldUiModel?20:bootUpgrade||bootDependency?10:6,TimeUnit.MINUTES)){process.destroyForcibly();process.waitFor(10,TimeUnit.SECONDS);throw new IllegalStateException("JOINT_APPEARANCE_TIMEOUT: "+run+" stage="+stage);}
            String marker=conversationAgent?"MINEAGENT_CONVERSATION_AGENT_OK":playerBody?"MINEAGENT_PLAYER_BODY_OK":desktopWindows?"MINEAGENT_DESKTOP_WINDOWS_OK":nativeMention?"MINEAGENT_NATIVE_MENTION_OK":settings?"MINEAGENT_SETTINGS_"+stage.toUpperCase(Locale.ROOT)+"_OK":packageAssets?"MINEAGENT_PACKAGE_ASSETS_"+stage.toUpperCase(Locale.ROOT)+"_OK":agentManagement?"MINEAGENT_AGENT_MANAGEMENT_OK":nativeApi?"MINEAGENT_NATIVE_API_OK":resourcePack?"MINEAGENT_RESOURCE_PACK_OK":clientScript?"MINEAGENT_CLIENT_SCRIPT_"+stage.toUpperCase(Locale.ROOT)+"_OK":clientJava?"MINEAGENT_CLIENT_JAVA_"+stage.toUpperCase(Locale.ROOT)+"_OK":clientDependency?"MINEAGENT_CLIENT_DEPENDENCY_OK":clientStudio?"MINEAGENT_CLIENT_STUDIO_OK":bootUpgrade?"MINEAGENT_BOOT_UPGRADE_"+stage.toUpperCase(Locale.ROOT)+"_OK":bootDependency?"MINEAGENT_BOOT_DEPENDENCY_"+stage.toUpperCase(Locale.ROOT)+"_OK":bodyRecovery?"MINEAGENT_BODY_RECOVERY_"+stage.toUpperCase(Locale.ROOT).replace('-','_')+"_OK":bodySurvival?"MINEAGENT_BODY_SURVIVAL_OK":opacityPersistence&&!stage.equals("prepare")?"MINEAGENT_OPACITY_PERSISTENCE_"+stage.toUpperCase(Locale.ROOT)+"_OK":sharedAgent?"MINEAGENT_SHARED_AGENT_"+stage.toUpperCase(Locale.ROOT)+"_OK":shared&&stage.equals("resume")?"MINEAGENT_SHARED_STATE_RESUME_OK":schedule?"MINEAGENT_SCHEDULE_"+stage.toUpperCase(Locale.ROOT)+"_OK":events?"MINEAGENT_EVENTS_"+stage.toUpperCase(Locale.ROOT)+"_OK":audience?"MINEAGENT_AUDIENCE_"+stage.toUpperCase(Locale.ROOT)+"_OK":conversation?"MINEAGENT_CONVERSATION_"+stage.toUpperCase(Locale.ROOT)+"_OK":persona?"MINEAGENT_PERSONA_"+stage.toUpperCase(Locale.ROOT)+"_OK":worldUiAgent?"MINEAGENT_WORLD_UI_AGENT_OK":uiRepair?"MINEAGENT_WORLD_UI_REPAIR_OK":negative?"MINEAGENT_WORLD_UI_FAILURE_OK":worldUiModel?"MINEAGENT_WORLD_UI_MODEL_OK":worldUi?"MINEAGENT_WORLD_UI_OK":patchResume?"MINEAGENT_WORLD_PATCH_OK":generatedObjects?"MINEAGENT_WORLD_PACKAGE_TASK_OK":objects?"MINEAGENT_RUNTIME_OBJECT_OK":agent?"MINEAGENT_APPEARANCE_AGENT_OK":recovery?"MINEAGENT_APPEARANCE_RECOVERY_"+stage.toUpperCase(Locale.ROOT)+"_OK":absent?"MINEAGENT_YSM_ABSENT_GUI_OK":"MINEAGENT_YSM_WEBGUI_JOINT_OK";
            verifyOutput(process.exitValue(),Files.readString(log),marker);
            if(bootUpgrade&&stage.equals("upgrade")){
                var plans=bootPlanFiles(game);if(plans.size()!=2)throw new IllegalStateException("BOOT_UPGRADE_PLAN_COUNT");var mapper=new ObjectMapper();Path cancelled=plans.stream().filter(p->Files.isRegularFile(p.resolveSibling(p.getFileName().toString().replace(".json",".cancelled")))).findFirst().orElseThrow(()->new IllegalStateException("BOOT_CANCELLED_PLAN_MISSING"));Path activePlan=plans.stream().filter(p->!p.equals(cancelled)).findFirst().orElseThrow();var cancelledJson=mapper.readTree(cancelled.toFile());var activeJson=mapper.readTree(activePlan.toFile());String cancelledHash=sha256(cancelled),activeHash=sha256(activePlan);Path target=game.resolve("mods").resolve(activeJson.path("filename").asText());if(!sha256(target).equals(activeJson.path("previousArtifact").asText()))throw new IllegalStateException("BOOT_PRE_APPLY_SLOT_CHANGED");int rejected=bootTool(bootToolPath,game,cancelledJson,cancelledHash,"apply",output.resolve("boot-cancelled-apply.log"));if(rejected==0||!sha256(target).equals(activeJson.path("previousArtifact").asText()))throw new IllegalStateException("BOOT_CANCELLED_PLAN_APPLIED");int applied=bootTool(bootToolPath,game,activeJson,activeHash,"apply",output.resolve("boot-apply.log"));if(applied!=0||!sha256(target).equals(activeJson.path("nextArtifact").asText()))throw new IllegalStateException("BOOT_APPLY_FAILED");activeBootPlan=activeJson;activeBootPlanHash=activeHash;Files.writeString(output.resolve("boot-offline-apply.json"),mapper.writeValueAsString(Map.of("cancelledRejected",true,"operation",activeJson.path("operation").asText(),"planHash",activeHash,"targetHash",sha256(target),"jvmStopped",true)));
            }
            if(bootUpgrade&&stage.equals("verify")){
                if(activeBootPlan==null)throw new IllegalStateException("BOOT_ACTIVE_PLAN_MISSING");Path target=game.resolve("mods").resolve(activeBootPlan.path("filename").asText());int rolled=bootTool(bootToolPath,game,activeBootPlan,activeBootPlanHash,"rollback",output.resolve("boot-rollback.log"));if(rolled!=0||!sha256(target).equals(activeBootPlan.path("previousArtifact").asText()))throw new IllegalStateException("BOOT_ROLLBACK_FAILED");Files.writeString(output.resolve("boot-offline-rollback.json"),new ObjectMapper().writeValueAsString(Map.of("operation",activeBootPlan.path("operation").asText(),"planHash",activeBootPlanHash,"targetHash",sha256(target),"jvmStopped",true)));
            }
            if(bootDependency&&stage.equals("verify")){
                var mapper=new ObjectMapper();Path receipts=game.resolve("mineagent-runtime-data/boot-install/receipts");try(var files=Files.list(receipts)){var values=files.filter(Files::isRegularFile).filter(p->p.getFileName().toString().matches("[a-f0-9-]{36}\\.json")).map(p->{try{return mapper.readTree(p.toFile());}catch(Exception e){throw new java.io.UncheckedIOException(new java.io.IOException(e));}}).toList();if(values.size()!=2)throw new IllegalStateException("BOOT_DEPENDENCY_RECEIPT_COUNT");var dependency=values.stream().filter(v->v.path("modId").asText().equals("mineagent_boot_dep_a")).findFirst().orElseThrow();var consumer=values.stream().filter(v->v.path("modId").asText().equals("mineagent_boot_dep_b")).findFirst().orElseThrow();Path depTarget=bootReceiptTarget(game,dependency),consumerTarget=bootReceiptTarget(game,consumer);int rejected=bootRemove(bootRemoveToolPath,game,dependency,output.resolve("boot-dependency-remove-rejected.log"));if(rejected==0||!Files.isRegularFile(depTarget)||!Files.isRegularFile(consumerTarget))throw new IllegalStateException("BOOT_DEPENDENCY_REVERSE_GUARD");if(bootRemove(bootRemoveToolPath,game,consumer,output.resolve("boot-consumer-remove.log"))!=0||Files.exists(consumerTarget)||!Files.isRegularFile(consumerTarget.resolveSibling(consumerTarget.getFileName()+".disabled")))throw new IllegalStateException("BOOT_CONSUMER_OFFLINE_REMOVE");if(bootRemove(bootRemoveToolPath,game,dependency,output.resolve("boot-dependency-remove.log"))!=0||Files.exists(depTarget)||!Files.isRegularFile(depTarget.resolveSibling(depTarget.getFileName()+".disabled")))throw new IllegalStateException("BOOT_DEPENDENCY_OFFLINE_REMOVE");Files.writeString(output.resolve("boot-dependency-offline-removal.json"),mapper.writeValueAsString(Map.of("dependencyFirstRejected",true,"order",List.of("consumer","dependency"),"dependencyArtifact",dependency.path("artifact").asText(),"consumerArtifact",consumer.path("artifact").asText(),"jvmStopped",true)));}
            }
            if(bodyRecovery)snapshotBodyRecoveryStage(game,stage);
            if(worldMove)verifyOutput(process.exitValue(),Files.readString(log),"MINEAGENT_WORLD_MOVE_OK");
            if(Boolean.getBoolean("mineagent.workspaceSmoke"))verifyOutput(process.exitValue(),Files.readString(log),"MINEAGENT_WORKSPACE_OK");
            if(Boolean.getBoolean("mineagent.viewSettingsSmoke"))verifyOutput(process.exitValue(),Files.readString(log),"MINEAGENT_VIEW_SETTINGS_OK");
            if(Boolean.getBoolean("mineagent.nativePopupSmoke"))verifyOutput(process.exitValue(),Files.readString(log),"MINEAGENT_NATIVE_POPUP_OK");
            else if(Boolean.getBoolean("mineagent.nativeAtlasInputSmoke"))verifyOutput(process.exitValue(),Files.readString(log),"MINEAGENT_NATIVE_ATLAS_INPUT_OK");
            else if(Boolean.getBoolean("mineagent.nativeAtlasSmoke"))verifyOutput(process.exitValue(),Files.readString(log),"MINEAGENT_NATIVE_ATLAS_OK");
            if(Boolean.getBoolean("mineagent.viewSettingsPaintSmoke"))verifyOutput(process.exitValue(),Files.readString(log),"MINEAGENT_VIEW_SETTINGS_PAINT_OK");
            if(shared)verifyOutput(process.exitValue(),Files.readString(log),stage.equals("resume")?"MINEAGENT_SHARED_STATE_RESUME_OK":"MINEAGENT_SHARED_STATE_OK");
            if(Boolean.getBoolean("mineagent.objectDirectorySmoke"))verifyOutput(process.exitValue(),Files.readString(log),"MINEAGENT_OBJECT_DIRECTORY_OK");
            if((!opacityPersistence||stage.equals("prepare"))&&!System.getProperty("mineagent.livePlacementSmoke","").isEmpty())verifyOutput(process.exitValue(),Files.readString(log),"MINEAGENT_LIVE_PLACEMENT_OK");if((!opacityPersistence||stage.equals("prepare"))&&Set.of("positive","stale","real","opacity-half","opacity-zero").contains(System.getProperty("mineagent.livePlacementSmoke","")))verifyOutput(process.exitValue(),Files.readString(log),"MINEAGENT_LIVE_PLACEMENT_RESTORE_OK");
            if(negative&&negativeProvider.calls.get()!=1)throw new IllegalStateException("NEGATIVE_PROVIDER_REPLAYED");
            if(worldUiAgent){if(!worldUiAgentModel.isEmpty())verifyOutput(process.exitValue(),Files.readString(log),"MINEAGENT_WORLD_UI_AGENT_MODEL_OK");else if(worldUiAgentCancel.isEmpty())worldAgentProvider.verifyPositive();else{verifyOutput(process.exitValue(),Files.readString(log),"MINEAGENT_WORLD_UI_AGENT_CANCEL_OK");worldAgentProvider.verifyCancellation();}}
            if(recovery&&stage.equals("prepare"))checkPreparedBoundary(game);
            if(opacityPersistence){livePlacementProvider.verify();opacityCutoff(game,stage,livePlacementProvider.calls());}
            System.out.println("PRODUCTION_STAGE_VERIFIED="+stage);
        }
        System.out.println((conversationAgent?"PRODUCTION_CONVERSATION_AGENT_VERIFIED=":playerBody?"PRODUCTION_PLAYER_BODY_VERIFIED=":desktopWindows?"PRODUCTION_DESKTOP_WINDOWS_VERIFIED=":nativeMention?"PRODUCTION_NATIVE_MENTION_VERIFIED=":settings?"PRODUCTION_SETTINGS_VERIFIED=":packageAssets?"PRODUCTION_PACKAGE_ASSETS_VERIFIED=":agentManagement?"PRODUCTION_AGENT_MANAGEMENT_VERIFIED=":nativeApi?"PRODUCTION_NATIVE_API_VERIFIED=":resourcePack?"PRODUCTION_RESOURCE_PACK_VERIFIED=":clientScript?"PRODUCTION_CLIENT_SCRIPT_VERIFIED=":clientJava?"PRODUCTION_CLIENT_JAVA_VERIFIED=":clientDependency?"PRODUCTION_CLIENT_DEPENDENCY_VERIFIED=":clientStudio?"PRODUCTION_CLIENT_STUDIO_VERIFIED=":bootUpgrade?"PRODUCTION_BOOT_UPGRADE_VERIFIED=":bootDependency?"PRODUCTION_BOOT_DEPENDENCY_VERIFIED=":bodyRecovery?"PRODUCTION_BODY_RECOVERY_VERIFIED=":bodySurvival?"PRODUCTION_BODY_SURVIVAL_VERIFIED=":absent?"PRODUCTION_YSM_ABSENT_GUI_VERIFIED=":"PRODUCTION_YSM_WEBGUI_VERIFIED=")+run);
        if(conversationAgentProvider!=null)conversationAgentProvider.verify();if(playerBody)playerBodyProvider.verify();if(desktopWindows)desktopProvider.verify();if(nativeMention)nativeMentionProvider.verify();if(settings)settingsEndpoint.verify();if(agentManagement)managementProvider.verify();if(nativeApiCoder)nativeCoderProvider.verify();if(bootUpgrade)bootUpgradeProvider.verify();if(sharedAgent)sharedAgentProvider.verify();if(schedule)scheduleProvider.verify();if(events)eventProvider.verify();if(conversation)conversationProvider.verify();if(livePlacementProvider!=null)livePlacementProvider.verify();
        }
        }
    }
}
