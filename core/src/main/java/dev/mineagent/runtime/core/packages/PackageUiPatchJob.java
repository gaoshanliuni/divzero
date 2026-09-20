package dev.mineagent.runtime.core.packages;
import dev.mineagent.runtime.api.packages.RuntimePackage;
import java.util.*;

/** Historical serialized job shape, reused in separate scope-bound journals; immutable base/candidate provenance. */
public record PackageUiPatchJob(UUID operationId,UUID worldId,UUID ownerPlayerId,UUID agentId,UUID taskId,long taskIntentRevision,
        RuntimePackage base,RuntimePackage candidate,String prompt,String state,String errorCode,String providerId,String rawOutputSha256,
        long headRevision,long revision,long updatedAtEpochMillis){
    public PackageUiPatchJob{
        Objects.requireNonNull(operationId);Objects.requireNonNull(worldId);Objects.requireNonNull(ownerPlayerId);Objects.requireNonNull(agentId);Objects.requireNonNull(taskId);Objects.requireNonNull(base);
        if(taskIntentRevision<1||revision<1||headRevision<0||prompt==null||prompt.isBlank()||prompt.length()>8192
                ||!Set.of("PENDING","READY","APPLYING","APPLIED","ROLLING_BACK","ROLLED_BACK","FAILED","STALE","CANCELLED","INTERRUPTED").contains(state))throw new IllegalArgumentException("UI_PATCH_JOB");
    }
}
