package dev.mineagent.runtime.core.packages;

import java.util.UUID;

/** Immutable reference to an uninstalled failed generation, never a published package revision. */
public record GenerationRepairSource(UUID operationId,long jobRevision,String rawOutputSha256,
                                     String originalPrompt,String errorCode,String diagnostic) {
    public GenerationRepairSource(UUID operationId,long jobRevision,String rawOutputSha256,String originalPrompt,String errorCode){this(operationId,jobRevision,rawOutputSha256,originalPrompt,errorCode,"");}
    public GenerationRepairSource {
        diagnostic=diagnostic==null?"":diagnostic;
        if(diagnostic.length()>16384)throw new IllegalArgumentException("GENERATION_DIAGNOSTIC_SIZE");
        if(operationId==null||jobRevision<1||rawOutputSha256==null||!rawOutputSha256.matches("[a-f0-9]{64}")
                ||originalPrompt==null||originalPrompt.isBlank()||originalPrompt.length()>8192
                ||errorCode==null||!errorCode.matches("[A-Z][A-Z0-9_]{0,79}"))throw new IllegalArgumentException("GENERATION_REPAIR_SOURCE");
    }
}
