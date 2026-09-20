package dev.mineagent.runtime.api.packages;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record CodeDraft(
        UUID draftId,
        UUID worldId,
        UUID ownerPlayerId,
        UUID taskId,
        UUID packageId,
        long taskRevision,
        long packageRevision,
        long revision,
        String path,
        String source,
        CodeDraftStatus status,
        List<String> history,
        long updatedAtEpochMillis,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY)
        java.util.Map<String,SourceRef> additionalSources,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY)
        List<WorkspaceVersion> workspaceHistory,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_DEFAULT)
        boolean workspace,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY)
        java.util.Map<UUID,String> dependencies,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_DEFAULT)
        boolean dependencyDeclaration
) {
    public record SourceRef(String sha256,int bytes){public SourceRef{if(sha256==null||!sha256.matches("[a-f0-9]{64}")||bytes<0||bytes>64000)throw new IllegalArgumentException("STUDIO_WORKSPACE_REF");}}
    public record WorkspaceVersion(long revision,String entry,java.util.Map<String,SourceRef> files,@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY) java.util.Map<UUID,String> dependencies){public WorkspaceVersion(long revision,String entry,java.util.Map<String,SourceRef> files){this(revision,entry,files,java.util.Map.of());}public WorkspaceVersion{if(revision<1||entry==null)throw new IllegalArgumentException("STUDIO_WORKSPACE_VERSION");files=java.util.Map.copyOf(files);dependencies=dependencies==null?java.util.Map.of():java.util.Map.copyOf(dependencies);if(files.size()>64||!files.containsKey(entry)||dependencies.size()>32||dependencies.values().stream().anyMatch(v->!v.matches("[0-9A-Za-z_.+-]{1,64}")))throw new IllegalArgumentException("STUDIO_WORKSPACE_VERSION");}}
    public CodeDraft(UUID draftId,UUID worldId,UUID ownerPlayerId,UUID taskId,UUID packageId,long taskRevision,long packageRevision,long revision,String path,String source,CodeDraftStatus status,List<String> history,long updatedAtEpochMillis,java.util.Map<String,SourceRef> additionalSources,List<WorkspaceVersion> workspaceHistory,boolean workspace,java.util.Map<UUID,String> dependencies){this(draftId,worldId,ownerPlayerId,taskId,packageId,taskRevision,packageRevision,revision,path,source,status,history,updatedAtEpochMillis,additionalSources,workspaceHistory,workspace,dependencies,false);}
    public CodeDraft(UUID draftId,UUID worldId,UUID ownerPlayerId,UUID taskId,UUID packageId,long taskRevision,long packageRevision,long revision,String path,String source,CodeDraftStatus status,List<String> history,long updatedAtEpochMillis,java.util.Map<String,SourceRef> additionalSources,List<WorkspaceVersion> workspaceHistory,boolean workspace){this(draftId,worldId,ownerPlayerId,taskId,packageId,taskRevision,packageRevision,revision,path,source,status,history,updatedAtEpochMillis,additionalSources,workspaceHistory,workspace,java.util.Map.of());}
    public CodeDraft(UUID draftId,UUID worldId,UUID ownerPlayerId,UUID taskId,UUID packageId,long taskRevision,long packageRevision,long revision,String path,String source,CodeDraftStatus status,List<String> history,long updatedAtEpochMillis){this(draftId,worldId,ownerPlayerId,taskId,packageId,taskRevision,packageRevision,revision,path,source,status,history,updatedAtEpochMillis,java.util.Map.of(),List.of(),false);}
    public CodeDraft {
        Objects.requireNonNull(draftId, "draftId");
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(ownerPlayerId, "ownerPlayerId");
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(packageId, "packageId");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(status, "status");
        history = List.copyOf(history);
        additionalSources=additionalSources==null?java.util.Map.of():java.util.Map.copyOf(additionalSources);
        workspaceHistory=workspaceHistory==null?List.of():List.copyOf(workspaceHistory);
        workspace=workspace||!additionalSources.isEmpty();
        dependencies=dependencies==null?java.util.Map.of():java.util.Map.copyOf(dependencies);
        dependencyDeclaration=dependencyDeclaration||!dependencies.isEmpty();
        workspace=workspace||dependencyDeclaration;
        if(dependencies.size()>32||dependencies.containsKey(packageId)||dependencies.values().stream().anyMatch(v->!v.matches("[0-9A-Za-z_.+-]{1,64}")))throw new IllegalArgumentException("STUDIO_DEPENDENCY_DECLARATION");
        if(workspaceHistory.stream().anyMatch(v->v.dependencies().containsKey(packageId)))throw new IllegalArgumentException("STUDIO_DEPENDENCY_DECLARATION");
        if(additionalSources.size()>63||additionalSources.containsKey(path)||workspaceHistory.size()>20)throw new IllegalArgumentException("STUDIO_WORKSPACE_LIMIT");
    }
}
