package dev.mineagent.runtime.scripting.studio;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mineagent.runtime.api.packages.ActivationMode;
import dev.mineagent.runtime.api.packages.CodeDraft;
import dev.mineagent.runtime.api.packages.CodeDraftStatus;
import dev.mineagent.runtime.api.packages.RuntimePackageCandidate;
import dev.mineagent.runtime.core.persistence.SqliteRuntimeRepository;
import dev.mineagent.runtime.core.packages.CodeDraftSources;
import dev.mineagent.runtime.core.content.ContentAddressedStore;
import dev.mineagent.runtime.scripting.packagehost.RuntimePackageManager;
import dev.mineagent.runtime.scripting.preflight.ScriptPreflight;

import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class CodeDraftService implements AutoCloseable {
    private static final String NAMESPACE = "code_drafts";
    private final SqliteRuntimeRepository repository;
    private final UUID worldId;
    private final Clock clock;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ScriptPreflight preflight = new ScriptPreflight();
    private final RuntimePackageManager packages;
    private final ContentAddressedStore content;
    private final Map<UUID, CodeDraft> drafts = new LinkedHashMap<>();
    private final JavaPublicationJournal javaPublications;
    private final StudioScriptJournal scriptPublications;
    private final StudioCoderJournal coder;
    public StudioCoderJournal coder(){return coder;}
    public StudioScriptJournal scriptPublications(){return scriptPublications;}
    public JavaPublicationJournal javaPublications(){return javaPublications;}

    private CodeDraftService(
            SqliteRuntimeRepository repository,
            UUID worldId,
            Clock clock,
            RuntimePackageManager packages,ContentAddressedStore content
    ) throws Exception {
        this.repository = repository;
        this.worldId = worldId;
        this.clock = clock;
        this.packages = java.util.Objects.requireNonNull(packages, "packages");
        this.content=content;
        this.javaPublications = new JavaPublicationJournal(repository,worldId,clock);this.scriptPublications=new StudioScriptJournal(repository,worldId,clock);this.coder=new StudioCoderJournal(repository,worldId,clock);
        for (var record : repository.list(worldId, NAMESPACE)) {
            CodeDraft draft = mapper.readValue(record.payload(), CodeDraft.class);
            drafts.put(draft.draftId(), draft);
        }
    }

    public static CodeDraftService open(
            Path database,
            UUID worldId,
            Clock clock,
            RuntimePackageManager packages
    ) throws Exception {
        var repository = new SqliteRuntimeRepository(database);
        try {
            return new CodeDraftService(repository, worldId, clock, packages,new ContentAddressedStore(database.toAbsolutePath().getParent().resolve("content")));
        } catch (Exception failure) {
            repository.close();
            throw failure;
        }
    }

    public synchronized CodeDraft create(
            UUID ownerPlayerId,
            UUID taskId,
            UUID packageId,
            long taskRevision,
            long packageRevision,
            String path,
            String source
    ) throws Exception {
        validate(ownerPlayerId, taskId, packageId, taskRevision, packageRevision, path, source);
        if (isJavaScript(path)) {
            var inspected = preflight.inspect(source);
            if (!inspected.accepted()) {
                throw new IllegalArgumentException("generated code failed preflight: " + inspected.diagnostics());
            }
        }
        UUID draftId = UUID.randomUUID();
        var draft = new CodeDraft(draftId, worldId, ownerPlayerId, taskId, packageId,
                taskRevision, packageRevision, 1, path, source, CodeDraftStatus.DRAFT,
                List.of(), clock.millis());
        var result = repository.compareAndSet(worldId, NAMESPACE, draftId.toString(), 0,
                mapper.writeValueAsString(draft), draft.updatedAtEpochMillis());
        if (!result.accepted()) {
            throw new IllegalStateException("draft id collision");
        }
        drafts.put(draftId, draft);
        return draft;
    }

    public synchronized CodeDraft createIdempotent(UUID operation,UUID owner,UUID task,UUID pkg,long taskRevision,long packageRevision,String path,String source)throws Exception {
        return createIdempotent(operation,owner,task,pkg,taskRevision,packageRevision,path,source,Map.of());
    }
    public synchronized CodeDraft createIdempotent(UUID operation,UUID owner,UUID task,UUID pkg,long taskRevision,long packageRevision,String path,String source,Map<String,CodeDraft.SourceRef> additional)throws Exception {
        return createIdempotent(operation,owner,task,pkg,taskRevision,packageRevision,path,source,additional,!additional.isEmpty());
    }
    public synchronized CodeDraft createIdempotent(UUID operation,UUID owner,UUID task,UUID pkg,long taskRevision,long packageRevision,String path,String source,Map<String,CodeDraft.SourceRef> additional,boolean workspace)throws Exception {
        return createIdempotent(operation,owner,task,pkg,taskRevision,packageRevision,path,source,additional,workspace,Map.of());
    }
    public synchronized CodeDraft createIdempotent(UUID operation,UUID owner,UUID task,UUID pkg,long taskRevision,long packageRevision,String path,String source,Map<String,CodeDraft.SourceRef> additional,boolean workspace,Map<UUID,String> dependencies)throws Exception {
        return createIdempotent(operation,owner,task,pkg,taskRevision,packageRevision,path,source,additional,workspace,dependencies,!dependencies.isEmpty());
    }
    public synchronized CodeDraft createIdempotent(UUID operation,UUID owner,UUID task,UUID pkg,long taskRevision,long packageRevision,String path,String source,Map<String,CodeDraft.SourceRef> additional,boolean workspace,Map<UUID,String> dependencies,boolean dependencyDeclaration)throws Exception {
        dependencyDeclaration=dependencyDeclaration||!dependencies.isEmpty();workspace=workspace||!additional.isEmpty()||dependencyDeclaration;
        if(dependencyDeclaration&&!CodeDraftSources.java(path)&&!isJavaScript(path))throw new IllegalArgumentException("STUDIO_DEPENDENCY_JAVA_REQUIRED");
        validate(owner,task,pkg,taskRevision,packageRevision,path,source);if(isJavaScript(path)&&!preflight.inspect(source).accepted())throw new IllegalArgumentException("STUDIO_SCRIPT_PREFLIGHT");UUID id=idempotentDraftId(worldId,owner,operation);
        var old=drafts.get(id);if(old!=null){if(!old.ownerPlayerId().equals(owner)||!old.taskId().equals(task)||!old.packageId().equals(pkg)||!old.path().equals(path)||!old.source().equals(source)||!old.additionalSources().equals(additional)||old.workspace()!=workspace||!old.dependencies().equals(dependencies)||old.dependencyDeclaration()!=dependencyDeclaration)throw new IllegalStateException("JAVA_STUDIO_OPERATION_REUSED");return old;}
        if(drafts.size()>=4096)throw new IllegalStateException("JAVA_STUDIO_DRAFT_BUDGET");var value=new CodeDraft(id,worldId,owner,task,pkg,taskRevision,packageRevision,1,path,source,CodeDraftStatus.DRAFT,List.of(),clock.millis(),additional,List.of(),workspace,dependencies,dependencyDeclaration);
        if(value.workspace()){CodeDraftSources.validate(path,CodeDraftSources.refs(value));validateSide(path,CodeDraftSources.refs(value).keySet());}
        if(!repository.compareAndSet(worldId,NAMESPACE,id.toString(),0,mapper.writeValueAsString(value),clock.millis()).accepted())throw new IllegalStateException("JAVA_STUDIO_DRAFT_CAS");drafts.put(id,value);return value;
    }
    public synchronized Optional<CodeDraft> get(UUID draftId) {
        return Optional.ofNullable(drafts.get(draftId));
    }
    public static UUID idempotentDraftId(UUID world,UUID owner,UUID operation){return UUID.nameUUIDFromBytes(("web-studio|"+world+"|"+owner+"|"+operation).getBytes(java.nio.charset.StandardCharsets.UTF_8));}

    public synchronized List<CodeDraft> allFor(UUID playerId, boolean operator) {
        return drafts.values().stream()
                .filter(draft -> operator || draft.ownerPlayerId().equals(playerId))
                .sorted(java.util.Comparator.comparingLong(CodeDraft::updatedAtEpochMillis).reversed())
                .toList();
    }

    public synchronized CodeDraftResult update(
            UUID draftId,
            UUID playerId,
            long expectedRevision,
            String source
    ) throws Exception {
        CodeDraft current = require(draftId);
        if (!current.ownerPlayerId().equals(playerId)) {
            return CodeDraftResult.rejected(current, "FORBIDDEN", List.of());
        }
        if (current.revision() != expectedRevision) {
            return CodeDraftResult.rejected(current, "STALE_REVISION", List.of());
        }
        if (current.status() != CodeDraftStatus.DRAFT) {
            return CodeDraftResult.rejected(current, "ALREADY_PUBLISHED", List.of());
        }
        CodeDraftSources.ref(source);
        if (isJavaScript(current.path())) {
            var inspected = preflight.inspect(source);
            if (!inspected.accepted()) {
                return CodeDraftResult.rejected(current, "PREFLIGHT_REJECTED", inspected.diagnostics());
            }
        }
        var history = new ArrayList<>(current.history());
        history.add(current.source());
        while (history.size() > 20) {
            history.removeFirst();
        }
        CodeDraft next = copy(current, current.revision() + 1, source, CodeDraftStatus.DRAFT,history,clock.millis());
        if(current.workspace()||!current.dependencies().isEmpty())next=workspaceCopy(next,next.path(),next.source(),next.additionalSources(),snapshots(current),true);
        return save(current, next, null);
    }

    public synchronized Map<String,String> sources(CodeDraft draft)throws Exception{return CodeDraftSources.readAll(draft,content);}
    public synchronized String sourceFile(CodeDraft draft,String path)throws Exception {
        if(draft.path().equals(path))return draft.source();var ref=draft.additionalSources().get(path);if(ref==null)throw new IllegalArgumentException("STUDIO_WORKSPACE_FILE_MISSING");return CodeDraftSources.read(content,ref);
    }
    public synchronized String historyFile(CodeDraft.WorkspaceVersion version,String path)throws Exception {var ref=version.files().get(path);if(ref==null)throw new IllegalArgumentException("STUDIO_WORKSPACE_FILE_MISSING");return CodeDraftSources.read(content,ref);}
    private CodeDraft.SourceRef storeSource(String source)throws Exception {var ref=CodeDraftSources.ref(source);if(!content.put(CodeDraftSources.utf8(source)).sha256().equals(ref.sha256()))throw new IllegalStateException("STUDIO_WORKSPACE_SOURCE_CHANGED");return ref;}
    private List<CodeDraft.WorkspaceVersion> snapshots(CodeDraft current)throws Exception {
        storeSource(current.source());var values=new ArrayList<>(current.workspaceHistory());values.add(new CodeDraft.WorkspaceVersion(current.revision(),current.path(),CodeDraftSources.refs(current),current.dependencies()));while(values.size()>20)values.removeFirst();return List.copyOf(values);
    }
    private static CodeDraft workspaceCopy(CodeDraft d,String entry,String source,Map<String,CodeDraft.SourceRef> files,List<CodeDraft.WorkspaceVersion> history,boolean workspace){return new CodeDraft(d.draftId(),d.worldId(),d.ownerPlayerId(),d.taskId(),d.packageId(),d.taskRevision(),d.packageRevision(),d.revision(),entry,source,d.status(),d.history(),d.updatedAtEpochMillis(),files,history,workspace,d.dependencies(),d.dependencyDeclaration());}
    public synchronized CodeDraftResult file(UUID id,UUID owner,long revision,String action,String path,String target,String source)throws Exception {
        var current=require(id);if(!current.ownerPlayerId().equals(owner))return CodeDraftResult.rejected(current,"FORBIDDEN",List.of());if(current.revision()!=revision)return CodeDraftResult.rejected(current,"STALE_REVISION",List.of());if(current.status()!=CodeDraftStatus.DRAFT)return CodeDraftResult.rejected(current,"ALREADY_PUBLISHED",List.of());
        var refs=new LinkedHashMap<>(CodeDraftSources.refs(current));String entry=current.path(),primary=current.source();var dependencies=current.dependencies();
        switch(action){
            case "put","add"->{CodeDraftSources.path(path);if(action.equals("add")==refs.containsKey(path))throw new IllegalArgumentException("STUDIO_WORKSPACE_FILE_EXISTS_OR_MISSING");if(CodeDraftSources.java(entry)!=CodeDraftSources.java(path)||entry.startsWith("client/")!=path.startsWith("client/"))throw new IllegalArgumentException("STUDIO_WORKSPACE_LANGUAGE");var ref=storeSource(source);refs.put(path,ref);if(path.equals(entry))primary=source;}
            case "remove"->{if(path.equals(entry))throw new IllegalStateException("STUDIO_WORKSPACE_ENTRY_DELETE");if(refs.remove(path)==null)throw new IllegalArgumentException("STUDIO_WORKSPACE_FILE_MISSING");}
            case "rename"->{CodeDraftSources.path(target);if(CodeDraftSources.java(entry)!=CodeDraftSources.java(target)||entry.startsWith("client/")!=target.startsWith("client/"))throw new IllegalArgumentException("STUDIO_WORKSPACE_LANGUAGE");if(!refs.containsKey(path)||refs.containsKey(target))throw new IllegalArgumentException("STUDIO_WORKSPACE_RENAME");var ref=refs.remove(path);refs.put(target,ref);if(path.equals(entry))entry=target;}
            case "entry"->{if(!refs.containsKey(path)||entry.startsWith("client/")!=path.startsWith("client/"))throw new IllegalArgumentException("STUDIO_WORKSPACE_FILE_MISSING");primary=sourceFile(current,path);entry=path;}
            case "restore"->{long version=Long.parseLong(target);var saved=current.workspaceHistory().stream().filter(v->v.revision()==version).findFirst().orElseThrow(()->new IllegalArgumentException("STUDIO_WORKSPACE_HISTORY_MISSING"));for(var ref:saved.files().values())CodeDraftSources.read(content,ref);refs.clear();refs.putAll(saved.files());entry=saved.entry();dependencies=saved.dependencies();primary=CodeDraftSources.read(content,saved.files().get(entry));}
            default->throw new IllegalArgumentException("STUDIO_WORKSPACE_ACTION");
        }
        CodeDraftSources.validate(entry,refs);validateSide(entry,refs.keySet());var history=snapshots(current);var extra=new LinkedHashMap<>(refs);extra.remove(entry);
        var next=new CodeDraft(id,worldId,owner,current.taskId(),current.packageId(),current.taskRevision(),current.packageRevision(),revision+1,entry,primary,CodeDraftStatus.DRAFT,current.history(),clock.millis(),extra,history,true,dependencies,current.dependencyDeclaration()||action.equals("restore"));
        return save(current,next,null);
    }

    public synchronized CodeDraftResult dependencies(UUID id,UUID owner,long revision,Map<UUID,String> dependencies)throws Exception {
        var current=require(id);
        if(!current.ownerPlayerId().equals(owner))return CodeDraftResult.rejected(current,"FORBIDDEN",List.of());
        if(current.revision()!=revision)return CodeDraftResult.rejected(current,"STALE_REVISION",List.of());
        if(current.status()!=CodeDraftStatus.DRAFT)return CodeDraftResult.rejected(current,"ALREADY_PUBLISHED",List.of());
        var next=new CodeDraft(id,worldId,owner,current.taskId(),current.packageId(),current.taskRevision(),current.packageRevision(),revision+1,current.path(),current.source(),current.status(),current.history(),clock.millis(),current.additionalSources(),snapshots(current),true,dependencies,true);
        return save(current,next,null);
    }

    public synchronized CodeDraftResult publish(
            UUID draftId,
            UUID playerId,
            long expectedRevision,
            long currentTaskRevision,
            Map<String, Object> bindings
    ) throws Exception {
        CodeDraft current = require(draftId);
        if(!current.additionalSources().isEmpty()||!current.dependencies().isEmpty())return CodeDraftResult.rejected(current,"STUDIO_WORKSPACE_USE_UNIFIED_RUNTIME",List.of());
        if (!current.ownerPlayerId().equals(playerId)) {
            return CodeDraftResult.rejected(current, "FORBIDDEN", List.of());
        }
        if (current.revision() != expectedRevision) {
            return CodeDraftResult.rejected(current, "STALE_REVISION", List.of());
        }
        var activation = packages.activate(new RuntimePackageCandidate(
                current.packageId(), current.packageRevision(), current.taskId(), current.taskRevision(),
                ActivationMode.HOT_RUNTIME, current.path(), current.source()), currentTaskRevision, bindings);
        if (!activation.activated()) {
            return CodeDraftResult.rejected(current, activation.errorCode(), activation.diagnostics());
        }
        CodeDraft next = copy(current, current.revision() + 1, current.source(), CodeDraftStatus.PUBLISHED,
                current.history(), clock.millis());
        return save(current, next, activation.entrypointResult());
    }

    public synchronized CodeDraftResult markExternalPublished(UUID draftId,UUID playerId,long expectedRevision,long currentTaskRevision)throws Exception{return markStudioPublished(draftId,playerId,expectedRevision,currentTaskRevision,true);}
    public synchronized CodeDraftResult markScriptPublished(UUID draftId,UUID playerId,long expectedRevision,long currentTaskRevision)throws Exception{return markStudioPublished(draftId,playerId,expectedRevision,currentTaskRevision,false);}
    private CodeDraftResult markStudioPublished(UUID draftId,UUID playerId,long expectedRevision,long currentTaskRevision,boolean javaSource)throws Exception {
        CodeDraft current = require(draftId);
        if (!current.ownerPlayerId().equals(playerId)) {
            return CodeDraftResult.rejected(current, "FORBIDDEN", List.of());
        }
        if (current.revision() != expectedRevision) {
            return CodeDraftResult.rejected(current, "STALE_REVISION", List.of());
        }
        if (current.status() != CodeDraftStatus.DRAFT) {
            return CodeDraftResult.rejected(current, "ALREADY_PUBLISHED", List.of());
        }
        if (current.taskRevision() != currentTaskRevision) {
            return CodeDraftResult.rejected(current, "STALE_TASK_REVISION", List.of());
        }
        if (javaSource?!current.path().toLowerCase(java.util.Locale.ROOT).endsWith(".java"):!isJavaScript(current.path())) {
            return CodeDraftResult.rejected(current, "NOT_EXTERNAL_SOURCE", List.of());
        }
        CodeDraft next = copy(current, current.revision() + 1, current.source(), CodeDraftStatus.PUBLISHED,
                current.history(), clock.millis());
        return save(current, next, null);
    }

    private CodeDraftResult save(CodeDraft current, CodeDraft next, Object executionResult) throws Exception {
        if(next.workspace()){CodeDraftSources.validate(next.path(),CodeDraftSources.refs(next));validateSide(next.path(),CodeDraftSources.refs(next).keySet());if(mapper.writeValueAsBytes(next).length>4*1024*1024-1024)throw new IllegalStateException("STUDIO_WORKSPACE_METADATA_LIMIT");}
        var result = repository.compareAndSet(worldId, NAMESPACE, current.draftId().toString(),
                current.revision(), mapper.writeValueAsString(next), next.updatedAtEpochMillis());
        if (!result.accepted()) {
            CodeDraft latest = mapper.readValue(result.record().payload(), CodeDraft.class);
            drafts.put(latest.draftId(), latest);
            return CodeDraftResult.rejected(latest, "STALE_REVISION", List.of());
        }
        drafts.put(next.draftId(), next);
        return new CodeDraftResult(true, "", next, executionResult, List.of());
    }

    private CodeDraft require(UUID draftId) {
        CodeDraft draft = drafts.get(draftId);
        if (draft == null) {
            throw new IllegalArgumentException("unknown code draft");
        }
        return draft;
    }

    private static CodeDraft copy(
            CodeDraft draft,
            long revision,
            String source,
            CodeDraftStatus status,
            List<String> history,
            long updatedAt
    ) {
        return new CodeDraft(draft.draftId(), draft.worldId(), draft.ownerPlayerId(), draft.taskId(),
                draft.packageId(), draft.taskRevision(), draft.packageRevision(), revision,
                draft.path(), source, status, history, updatedAt,draft.additionalSources(),draft.workspaceHistory(),draft.workspace(),draft.dependencies(),draft.dependencyDeclaration());
    }
    private static void validateSide(String entry,java.util.Collection<String> paths){boolean client=entry.startsWith("client/");if(paths.stream().anyMatch(path->path.startsWith("client/")!=client))throw new IllegalArgumentException("STUDIO_WORKSPACE_SIDE");}

    private static void validate(
            UUID owner,
            UUID task,
            UUID packageId,
            long taskRevision,
            long packageRevision,
            String path,
            String source
    ) {
        if (owner == null || task == null || packageId == null || taskRevision < 0 || packageRevision < 1
                || path == null || !path.matches("[A-Za-z0-9_./-]{1,128}") || path.contains("..")
                || source == null || source.isBlank() || source.length() > 16_000
                || !(isJavaScript(path) || path.toLowerCase(java.util.Locale.ROOT).endsWith(".java"))) {
            throw new IllegalArgumentException("invalid code draft");
        }
    }

    private static boolean isJavaScript(String path) {
        String lower = path.toLowerCase(java.util.Locale.ROOT);
        return lower.endsWith(".js") || lower.endsWith(".mjs");
    }

    @Override
    public synchronized void close() throws Exception {
        repository.close();
    }
}
