package dev.mineagent.runtime.core.packages;

import java.util.UUID;

/** Source-only bridge from real CodeDraft records into the shared RuntimePackage library. */
public final class JavaStudioMetadata {
    private JavaStudioMetadata(){}
    public record Input(UUID operation,UUID world,UUID owner,UUID draft,long draftRevision,UUID packageId,long expectedPackageRevision,String sourceHash,String name){}
    public record Link(UUID world,UUID owner,UUID packageId,UUID draft,long draftRevision,String sourceHash,String canonical,long packageRevision){}
    public record Receipt(Input input,String outcome,String canonical,long packageRevision){}
}
