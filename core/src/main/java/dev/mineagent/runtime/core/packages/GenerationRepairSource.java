package dev.mineagent.runtime.core.packages;

import java.util.UUID;

/** Immutable reference to an uninstalled failed generation, never a published package revision. */
public record GenerationRepairSource(UUID operationId,long jobRevision,String rawOutputSha256,
                                     String originalPrompt,String errorCode) {
    public GenerationRepairSource {
        if(operationId==null||jobRevision<1||rawOutputSha256==null||!rawOutputSha256.matches("[a-f0-9]{64}")
                ||originalPrompt==null||originalPrompt.isBlank()||originalPrompt.length()>8192
                ||errorCode==null||!errorCode.matches("[A-Z][A-Z0-9_]{0,79}"))throw new IllegalArgumentException("GENERATION_REPAIR_SOURCE");
    }
}
