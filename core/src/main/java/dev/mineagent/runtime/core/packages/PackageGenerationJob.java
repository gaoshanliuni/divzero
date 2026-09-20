package dev.mineagent.runtime.core.packages;

import java.util.Objects;
import java.util.UUID;
import dev.mineagent.runtime.core.compile.NativeCoderContext;

/** Server-owned provenance. A task mutation revision is not an intent revision. */
public record PackageGenerationJob(UUID operationId, UUID worldId, UUID ownerPlayerId, UUID agentId,
        UUID taskId, long taskIntentRevision, UUID packageId, long packageRevision, String prompt,
        String state, String errorCode, String providerId, String rawOutputSha256, String canonicalSha256,
        long revision, long updatedAtEpochMillis, String purpose, GenerationRepairSource repairSource,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) NativeCoderContext nativeSelection,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) NativeCoderContext.Snapshot nativeContext) {
    public PackageGenerationJob(UUID operationId,UUID worldId,UUID ownerPlayerId,UUID agentId,UUID taskId,long taskIntentRevision,
            UUID packageId,long packageRevision,String prompt,String state,String errorCode,String providerId,String rawOutputSha256,
            String canonicalSha256,long revision,long updatedAtEpochMillis,String purpose,GenerationRepairSource repairSource){
        this(operationId,worldId,ownerPlayerId,agentId,taskId,taskIntentRevision,packageId,packageRevision,prompt,state,errorCode,providerId,rawOutputSha256,canonicalSha256,revision,updatedAtEpochMillis,purpose,repairSource,null,null);
    }
    public PackageGenerationJob(UUID operationId,UUID worldId,UUID ownerPlayerId,UUID agentId,UUID taskId,long taskIntentRevision,
            UUID packageId,long packageRevision,String prompt,String state,String errorCode,String providerId,String rawOutputSha256,
            String canonicalSha256,long revision,long updatedAtEpochMillis,String purpose){
        this(operationId,worldId,ownerPlayerId,agentId,taskId,taskIntentRevision,packageId,packageRevision,prompt,state,errorCode,
                providerId,rawOutputSha256,canonicalSha256,revision,updatedAtEpochMillis,purpose,null,null,null);
    }
    public PackageGenerationJob(UUID operationId,UUID worldId,UUID ownerPlayerId,UUID agentId,UUID taskId,long taskIntentRevision,
            UUID packageId,long packageRevision,String prompt,String state,String errorCode,String providerId,String rawOutputSha256,
            String canonicalSha256,long revision,long updatedAtEpochMillis){
        this(operationId,worldId,ownerPlayerId,agentId,taskId,taskIntentRevision,packageId,packageRevision,prompt,state,errorCode,
                providerId,rawOutputSha256,canonicalSha256,revision,updatedAtEpochMillis,"UI_PACKAGE");
    }
    public PackageGenerationJob {
        purpose=purpose==null?"UI_PACKAGE":purpose;
        if(!java.util.Set.of("UI_PACKAGE","WORLD_CONTENT").contains(purpose))throw new IllegalArgumentException("GENERATION_PURPOSE");
        Objects.requireNonNull(operationId); Objects.requireNonNull(worldId); Objects.requireNonNull(ownerPlayerId);
        Objects.requireNonNull(agentId); Objects.requireNonNull(taskId); Objects.requireNonNull(packageId);
        if(nativeContext!=null&&(nativeSelection==null||!nativeContext.selectionHash().equals(nativeSelection.fingerprint())))throw new IllegalArgumentException("GENERATION_NATIVE_CONTEXT");
        if(repairSource!=null&&repairSource.operationId().equals(operationId))throw new IllegalArgumentException("GENERATION_REPAIR_SELF_REFERENCE");
        if (taskIntentRevision < 1 || packageRevision < 1 || revision < 1 || prompt == null || prompt.isBlank() || prompt.length() > 8192
                || !java.util.Set.of("GENERATING", "PUBLISHED", "FAILED", "STALE", "CANCELLED", "INTERRUPTED").contains(state))
            throw new IllegalArgumentException("INVALID_GENERATION_JOB");
    }
    public String generateStep(){return purpose.equals("WORLD_CONTENT")?"generate_content":"generate_ui";}
    public String publishStep(){return purpose.equals("WORLD_CONTENT")?"publish_content":"publish_ui";}
}
