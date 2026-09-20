package dev.mineagent.runtime.neoforge.ui;

import dev.mineagent.runtime.api.packages.*;
import dev.mineagent.runtime.api.permission.PermissionAction;
import dev.mineagent.runtime.api.task.*;
import dev.mineagent.runtime.core.content.ContentAddressedStore;
import dev.mineagent.runtime.core.compile.NativeCoderContext;
import dev.mineagent.runtime.core.packages.*;
import dev.mineagent.runtime.core.task.*;
import dev.mineagent.runtime.neoforge.MineAgentRuntimeServices;
import dev.mineagent.runtime.neoforge.content.NativePackageCompatibility;
import dev.mineagent.runtime.scripting.studio.StudioCoderJournal;
import dev.mineagent.runtime.worker.generation.StudioCoderResult;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Server-owned Coder admission, bounded repair and explicit adoption. Candidate source is never executed here. */
public final class ServerStudioCoder implements AutoCloseable {
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON=new com.fasterxml.jackson.databind.ObjectMapper();
    private static final Map<MinecraftServer,ServerStudioCoder> LIVE=new IdentityHashMap<>();
    private static final Set<MinecraftServer> STOPPED=Collections.newSetFromMap(new WeakHashMap<>());
    private final MinecraftServer server;
    private final StudioCoderJournal journal;
    private final ContentAddressedStore content;
    private final TaskManager tasks;
    private final Map<UUID,Binding> active=new java.util.concurrent.ConcurrentHashMap<>();
    private final AutoCloseable taskChanges;
    private volatile boolean closed;
    private final class Binding {
        final StudioCoderJournal.Input input;final AtomicBoolean valid=new AtomicBoolean(true);
        Binding(StudioCoderJournal.Input input){this.input=input;}
        boolean current(){
            if(!valid.get()||closed||!server.isSameThread())return false;
            boolean ok=currentInput(input,false);if(!ok)valid.set(false);return ok;
        }
        boolean permit(){
            if(!valid.get()||closed)return false;
            if(server.isSameThread())return current();
            try{return server.submit(this::current).get(3,java.util.concurrent.TimeUnit.SECONDS);}
            catch(Exception e){valid.set(false);return false;}
        }
    }
    private ServerStudioCoder(MinecraftServer server){
        this.server=server;journal=MineAgentRuntimeServices.codeDrafts(server).coder();content=ServerPackageRuntime.get(server).worldContent();tasks=MineAgentRuntimeServices.tasks(server);
        taskChanges=tasks.onChange(task->{
            for(var b:active.values())if(b.input.task().equals(task.taskId())&&(task.revision()!=b.input.taskRevision()||task.status()!=TaskStatus.RUNNING))b.valid.set(false);
        });
        journal.interrupted().forEach(this::pause);
    }
    public static synchronized ServerStudioCoder get(MinecraftServer server){
        if(STOPPED.contains(server)||!server.isSameThread()||!server.isRunning())throw new IllegalStateException("STUDIO_CODER_SERVER_STOPPED");
        return LIVE.computeIfAbsent(server,ServerStudioCoder::new);
    }
    public static void stopServer(MinecraftServer server){
        ServerStudioCoder value;synchronized(ServerStudioCoder.class){STOPPED.add(server);value=LIVE.remove(server);}
        if(value!=null)value.close();
    }
    private boolean currentInput(StudioCoderJournal.Input in,boolean adopting){
        try{
            if(closed||!server.isRunning()||!dev.mineagent.runtime.neoforge.WorldIdentityRuntime.ready(server)||!MineAgentRuntimeServices.worldId(server).equals(in.world()))return false;
            var player=server.getPlayerList().getPlayer(in.owner());if(player==null)return false;
            ServerJavaStudio.authorize(player);
            if(!ServerPackageRuntime.get(server).mayGenerate(in.owner(),in.agent())||MineAgentRuntimeServices.config(server).snapshot().revision()!=in.configRevision())return false;
            if(MineAgentRuntimeServices.bodies(server).authorityGeneration(in.agent())!=in.agentAuthorityGeneration())return false;
            if(!NativePackageCompatibility.observe().fingerprint().equals(in.environment().fingerprint()))return false;
            var task=MineAgentRuntimeServices.tasks(server).get(in.task()).orElse(null);
            if(task==null||!task.ownerPlayerId().equals(in.owner())||!task.agentId().equals(in.agent())||task.status()!=TaskStatus.RUNNING||TaskAuthorityFence.revoked(task)||task.intentRevision()!=in.taskIntent())return false;
            boolean original=task.revision()==in.taskRevision()&&task.runnableStepIds().contains("generate");
            boolean adoptedStep=adopting&&task.revision()==in.taskRevision()+1&&task.runnableStepIds().contains("publish");
            if(!original&&!adoptedStep)return false;
            if(in.javaDependencies()!=null&&!ServerJavaDependencies.current(server,in.owner(),in.javaDependencies()))return false;
            if(in.scriptDependencies()!=null&&!ServerScriptDependencies.current(server,in.owner(),in.scriptDependencies().graph()))return false;
            if(in.nativeSelection()!=null){var latest=dev.mineagent.runtime.neoforge.compile.NativeCompilationEnvironment.latest();if(!latest.hash().equals(in.nativeSelection().snapshot())||!latest.snapshot().environment().fingerprint().equals(in.nativeSelection().environment())||in.nativeSelection().overlays().stream().anyMatch(o->!o.processEpoch().equals(dev.mineagent.runtime.neoforge.compile.NativeLiveClassAccess.state().processEpoch())))return false;}
            if(in.baseDraft()!=null){var draft=MineAgentRuntimeServices.codeDrafts(server).get(in.baseDraft()).orElse(null);
                if(draft==null||!draft.dependencies().equals(in.dependencies())||draft.dependencyDeclaration()!=in.dependencyDeclaration()||!draft.worldId().equals(in.world())||!draft.ownerPlayerId().equals(in.owner())||draft.revision()!=in.baseRevision()||!draft.packageId().equals(in.packageId())||!draft.path().equals(in.path())||!draft.additionalSources().equals(in.additionalSources())||!RuntimePackageCanonicalizer.sha256(draft.source()).equals(in.sourceHash()))return false;}
            var pack=ServerPackageRuntime.get(server).worldLibrary().get(in.packageId()).orElse(null);
            if(in.packageRevision()==0)return pack==null;
            return pack!=null&&pack.revision()==in.packageRevision()&&pack.canonicalSha256().equals(in.packageHash())
                    &&ServerPackageRuntime.get(server).ownedPackage(in.owner(),in.packageId(),in.packageRevision()).isPresent();
        }catch(Exception e){return false;}
    }
    private StudioCoderJournal.Job owned(ServerPlayer player,UUID id)throws Exception {
        ServerJavaStudio.authorize(player);var job=journal.refresh(id).orElseThrow(()->new IllegalStateException("STUDIO_CODER_JOB_MISSING"));
        if(!job.input().owner().equals(player.getUUID())||!job.input().world().equals(MineAgentRuntimeServices.worldId(server)))throw new SecurityException("STUDIO_CODER_OWNER");
        return job;
    }
    private static String hostApi(){
        return Arrays.stream(dev.mineagent.runtime.neoforge.scripting.MineAgentScriptHost.class.getDeclaredMethods())
                .filter(m->java.lang.reflect.Modifier.isPublic(m.getModifiers())).map(java.lang.reflect.Method::toGenericString).sorted().reduce("",(a,b)->a+b+"\n");
    }
    private String diagnostics(ServerPlayer player,CodeDraft draft,String kind,String id)throws Exception {
        if(id.isEmpty()){if(!kind.isEmpty())throw new IllegalArgumentException("STUDIO_CODER_DIAGNOSTIC_SOURCE");return "";}
        if(draft==null)throw new IllegalArgumentException("STUDIO_CODER_DIAGNOSTIC_SOURCE");
        String source=CodeDraftSources.fingerprint(draft);
        if(kind.equals("JAVA")){
            var record=MineAgentRuntimeServices.codeDrafts(server).javaPublications().get(UUID.fromString(id));
            if(!record.owner().equals(player.getUUID())||!record.packageId().equals(draft.packageId())||!record.sourceHash().equals(source))throw new IllegalStateException("STUDIO_CODER_DIAGNOSTIC_CHANGED");
            return trim(record.error()+"\n"+String.join("\n",record.diagnostics()),16000);
        }
        if(kind.equals("RHINO")){
            var record=MineAgentRuntimeServices.codeDrafts(server).scriptPublications().get(UUID.fromString(id));
            if(!record.owner().equals(player.getUUID())||!record.packageId().equals(draft.packageId())||!record.sourceHash().equals(source))throw new IllegalStateException("STUDIO_CODER_DIAGNOSTIC_CHANGED");
            return record.error()+"\n"+record.source()+":"+record.line()+":"+record.column();
        }
        throw new IllegalArgumentException("STUDIO_CODER_DIAGNOSTIC_SOURCE");
    }
    public Map<String,Object> submit(ServerPlayer player,UUID operation,Map<String,String> args)throws Exception {
        return submit(player,operation,args,null);
    }
    public Map<String,Object> repair(ServerPlayer player,UUID operation,Map<String,String> args)throws Exception {
        var repairKeys=new HashSet<>(Set.of("action","confirmed","jobId","revision","prompt","maxAttempts"));if(args.containsKey("shareDependencySources"))repairKeys.add("shareDependencySources");if(args.containsKey("shareNativeContext"))repairKeys.add("shareNativeContext");if(!args.keySet().equals(repairKeys))throw new IllegalArgumentException("STUDIO_CODER_ARGUMENTS");
        if(args.containsKey("shareDependencySources")&&!Set.of("true","false").contains(args.get("shareDependencySources"))||args.containsKey("shareNativeContext")&&!Set.of("true","false").contains(args.get("shareNativeContext")))throw new IllegalArgumentException("STUDIO_CODER_ARGUMENTS");
        var source=owned(player,UUID.fromString(args.get("jobId")));
        if(source.revision()!=Long.parseLong(args.get("revision"))||!source.state().equals("FAILED")||source.last()==null||!source.last().state().equals("REJECTED")||source.last().rawHash().isEmpty())throw new IllegalStateException("STUDIO_CODER_REPAIR_SOURCE");
        if(source.input().nativeSelection()!=null&&!"true".equals(args.get("shareNativeContext")))throw new IllegalArgumentException("STUDIO_CODER_NATIVE_CONTEXT_CONSENT_REQUIRED");
        if(source.input().scriptDependencies()!=null&&!"true".equals(args.get("shareDependencySources")))throw new IllegalArgumentException("STUDIO_CODER_SCRIPT_SOURCE_CONSENT_REQUIRED");
        var in=source.input();var values=new LinkedHashMap<String,String>();values.put("action","coderSubmit");values.put("confirmed",args.get("confirmed"));values.put("path",in.path());values.put("agentId",in.agent().toString());
        String prompt=in.prompt()+"\n本次明确修复要求：\n"+args.get("prompt");if(args.get("prompt").isBlank()||prompt.length()>8192)throw new IllegalArgumentException("STUDIO_CODER_REPAIR_PROMPT_LIMIT");
        values.put("prompt",prompt);values.put("baseDraft",in.baseDraft()==null?"":in.baseDraft().toString());values.put("baseRevision",Long.toString(in.baseRevision()));values.put("maxAttempts",args.get("maxAttempts"));values.put("diagnosticKind","");values.put("publicationId","");
        values.put("workspace",Boolean.toString(in.workspace()));values.put("shareDependencySources",Boolean.toString(in.scriptDependencies()!=null));
        return submit(player,operation,values,source);
    }
    private Map<String,Object> submit(ServerPlayer player,UUID operation,Map<String,String> args,StudioCoderJournal.Job repairSource)throws Exception {
        ServerJavaStudio.authorize(player);
        var keys=new HashSet<>(Set.of("action","confirmed","path","prompt","agentId","baseDraft","baseRevision","maxAttempts","diagnosticKind","publicationId"));if(args.containsKey("workspace"))keys.add("workspace");if(args.containsKey("shareDependencySources"))keys.add("shareDependencySources");for(String key:List.of("nativeSnapshot","nativeClasses","nativeLiveSelections","shareNativeContext"))if(args.containsKey(key))keys.add(key);if(!args.keySet().equals(keys))throw new IllegalArgumentException("STUDIO_CODER_ARGUMENTS");
        if(args.containsKey("workspace")&&!Set.of("true","false").contains(args.get("workspace")))throw new IllegalArgumentException("STUDIO_CODER_ARGUMENTS");boolean workspace=Boolean.parseBoolean(args.getOrDefault("workspace","false"));
        if(args.containsKey("shareDependencySources")&&!Set.of("true","false").contains(args.get("shareDependencySources")))throw new IllegalArgumentException("STUDIO_CODER_ARGUMENTS");
        boolean shareSources="true".equals(args.get("shareDependencySources"));
        if(args.containsKey("shareNativeContext")&&!Set.of("true","false").contains(args.get("shareNativeContext")))throw new IllegalArgumentException("STUDIO_CODER_ARGUMENTS");
        UUID owner=player.getUUID(),agent=UUID.fromString(args.get("agentId"));String path=args.get("path"),prompt=args.get("prompt");
        int maximum=Integer.parseInt(args.get("maxAttempts"));
        if(!"true".equals(args.get("confirmed"))||!path.matches("[A-Za-z0-9_./-]{1,128}")||path.contains("..")||!path.toLowerCase(Locale.ROOT).matches(".*\\.(java|m?js)")
                ||prompt.isBlank()||prompt.length()>8192||maximum<1||maximum>3)throw new IllegalArgumentException("STUDIO_CODER_INPUT");
        if(path.startsWith("client/"))throw new IllegalArgumentException("STUDIO_CODER_CLIENT_NOT_SUPPORTED");
        if(!ServerPackageRuntime.get(server).mayGenerate(owner,agent))throw new SecurityException("STUDIO_CODER_AGENT_AUTHORITY");
        CodeDraft base=null;String source="";UUID pkg;long packageRevision=0;String packageHash="";Map<String,CodeDraft.SourceRef> additional=Map.of();
        if(!args.get("baseDraft").isEmpty()){
            base=MineAgentRuntimeServices.codeDrafts(server).get(UUID.fromString(args.get("baseDraft"))).orElseThrow();
            if(!base.worldId().equals(MineAgentRuntimeServices.worldId(server))||!base.ownerPlayerId().equals(owner)||base.revision()!=Long.parseLong(args.get("baseRevision"))||!base.path().equals(path))throw new IllegalStateException("STUDIO_CODER_BASE_CHANGED");
            if(RuntimeStudioScriptPlan.path(base.path())&&!base.dependencies().isEmpty()&&!shareSources)throw new IllegalArgumentException("STUDIO_CODER_SCRIPT_SOURCE_CONSENT_REQUIRED");
            if(!workspace&&!base.additionalSources().isEmpty())throw new IllegalStateException("STUDIO_CODER_WORKSPACE_REQUIRED");additional=base.additionalSources();
            var baseTask=MineAgentRuntimeServices.tasks(server).get(base.taskId()).orElseThrow();
            if(!baseTask.agentId().equals(agent))throw new IllegalStateException("STUDIO_CODER_AGENT_CHANGED");
            source=base.source();pkg=base.packageId();
            var pack=ServerPackageRuntime.get(server).worldLibrary().get(pkg).orElse(null);
            if(pack!=null){if(ServerPackageRuntime.get(server).ownedPackage(owner,pkg,pack.revision()).isEmpty())throw new SecurityException("STUDIO_CODER_PACKAGE_OWNER");RuntimeStudioPlan.source(pack);if(!pack.dependencies().isEmpty()&&!base.dependencyDeclaration())throw new IllegalStateException("STUDIO_DEPENDENCY_BASE_NOT_LOADED");if(RuntimeStudioPlan.refs(pack).size()>1&&(!workspace||!base.workspace()))throw new IllegalStateException("STUDIO_CODER_WORKSPACE_REQUIRED");packageRevision=pack.revision();packageHash=pack.canonicalSha256();}
        }else{
            if(!args.get("baseRevision").equals("0"))throw new IllegalArgumentException("STUDIO_CODER_BASE_CHANGED");
            pkg=UUID.nameUUIDFromBytes(("studio-coder-package|"+MineAgentRuntimeServices.worldId(server)+"|"+owner+"|"+operation).getBytes(StandardCharsets.UTF_8));
        }
        String diagnostic=diagnostics(player,base,args.get("diagnosticKind"),args.get("publicationId"));
        StudioCoderJournal.Repair repair=null;
        if(repairSource!=null){
            var in=repairSource.input();String previous=repairSource.last().sourceHash().isEmpty()?repairSource.last().rawHash():repairSource.last().sourceHash();String prior=StudioCoderResult.read(content,previous,repairSource.last().sourceHash().isEmpty()?StudioCoderJournal.MAX_RAW_BYTES:64000);if(in.workspace()&&!repairSource.last().sourceHash().isEmpty())StudioCoderResult.readFiles(content,in.path(),prior,repairSource.last().additionalSources());
            var parent=MineAgentRuntimeServices.tasks(server).get(in.task()).orElseThrow();if(parent.intentRevision()!=in.taskIntent())throw new IllegalStateException("STUDIO_CODER_REPAIR_TASK_CHANGED");
            pkg=in.packageId();var pack=ServerPackageRuntime.get(server).worldLibrary().get(pkg).orElse(null);
            if(in.packageRevision()==0?pack!=null:pack==null||pack.revision()!=in.packageRevision()||!pack.canonicalSha256().equals(in.packageHash()))throw new IllegalStateException("STUDIO_CODER_REPAIR_PACKAGE_CHANGED");
            packageRevision=in.packageRevision();packageHash=in.packageHash();diagnostic=trim(in.diagnostics()+"\n上次确定拒绝：\n"+repairSource.last().diagnostics(),16000);
            repair=new StudioCoderJournal.Repair(repairSource.id(),repairSource.revision(),previous,repairSource.last().sourceHash().isEmpty(),repairSource.last().entry(),repairSource.last().additionalSources());
        }
        var dependencies=base==null?Map.<UUID,String>of():base.dependencies();boolean dependencyDeclaration=base!=null&&base.dependencyDeclaration();
        var dependencyGraph=dependencies.isEmpty()||RuntimeStudioScriptPlan.path(path)?null:ServerJavaDependencies.resolve(server,owner,pkg,dependencies);
        var scriptContext=dependencies.isEmpty()||!RuntimeStudioScriptPlan.path(path)?null:scriptSources(ServerScriptDependencies.resolve(server,owner,pkg,dependencies));
        shareSources=scriptContext!=null&&shareSources;String dependencyHash=dependencyGraph!=null?dependencyGraph.fingerprint():scriptContext!=null?scriptContext.graph().fingerprint():"";
        var nativeSelection=repairSource==null?nativeSelection(player,args):repairSource.input().nativeSelection();boolean shareNative=nativeSelection!=null&&(repairSource!=null||"true".equals(args.get("shareNativeContext")));
        if(nativeSelection!=null&&!shareNative)throw new IllegalArgumentException("STUDIO_CODER_NATIVE_CONTEXT_CONSENT_REQUIRED");
        if(repairSource!=null&&(!Objects.equals(repairSource.input().javaDependencies(),dependencyGraph)||!Objects.equals(repairSource.input().scriptDependencies(),scriptContext)||repairSource.input().dependencyDeclaration()!=dependencyDeclaration||!Objects.equals(repairSource.input().nativeSelection(),nativeSelection)))throw new IllegalStateException("STUDIO_CODER_DEPENDENCY_CONTEXT_CHANGED");
        var previous=journal.refresh(operation).orElse(null);
        if(previous!=null){
            var in=previous.input();if(!in.owner().equals(owner)||!in.agent().equals(agent)||!in.path().equals(path)||!in.prompt().equals(prompt)||in.maxAttempts()!=maximum
                    ||!Objects.equals(in.baseDraft(),base==null?null:base.draftId())||in.baseRevision()!=(base==null?0:base.revision())||!in.diagnostics().equals(diagnostic)||!Objects.equals(in.repair(),repair)||in.workspace()!=workspace||!in.additionalSources().equals(additional)||!Objects.equals(in.javaDependencies(),dependencyGraph)||in.dependencyDeclaration()!=dependencyDeclaration||!Objects.equals(in.scriptDependencies(),scriptContext)||in.shareScriptDependencySources()!=shareSources||!Objects.equals(in.nativeSelection(),nativeSelection)||in.shareNativeContext()!=shareNative)throw new IllegalStateException("STUDIO_CODER_OPERATION_REUSED");
            return summary(previous);
        }
        if(active.size()>=4||active.values().stream().filter(b->b.input.owner().equals(owner)).count()>=2)throw new IllegalStateException("STUDIO_CODER_BUSY");
        byte[] sourceBytes=CodeDraftSources.utf8(source);var refs=new TreeMap<>(additional);refs.put(path,CodeDraftSources.ref(source));if(workspace)CodeDraftSources.validate(path,refs);journal.capacity(sourceBytes.length+additional.values().stream().mapToInt(CodeDraft.SourceRef::bytes).sum());
        String hash=content.put(sourceBytes).sha256();
        String fingerprint=RuntimePackageCanonicalizer.sha256(path+"\n"+prompt+"\n"+CodeDraftSources.fingerprint(path,refs,dependencies)+"\n"+workspace+"\n"+maximum+"\n"+diagnostic+(dependencyDeclaration?"\njava-dependencies:"+dependencyHash+"\nexplicit:true":"")+(nativeSelection==null?"":"\nnative-selection:"+nativeSelection.fingerprint()));
        var parent=base==null?null:dev.mineagent.runtime.core.persistence.TaskBudgetLineage.Parent.of(MineAgentRuntimeServices.tasks(server).get(base.taskId()).orElseThrow(),"REPAIR");
        if(repairSource!=null)parent=new dev.mineagent.runtime.core.persistence.TaskBudgetLineage.Parent(repairSource.input().task(),repairSource.input().taskIntent(),"REPAIR");
        var task=MineAgentRuntimeServices.tasks(server).createIdempotent(operation,agent,owner,"Code Studio Coder: "+path+" · "+fingerprint,50,
                List.of(new TaskStepSpec("generate",Set.of()),new TaskStepSpec("publish",Set.of("generate"))),parent);
        var in=new StudioCoderJournal.Input(operation,MineAgentRuntimeServices.worldId(server),owner,agent,task.taskId(),task.revision(),task.intentRevision(),pkg,packageRevision,packageHash,
                base==null?null:base.draftId(),base==null?0:base.revision(),hash,sourceBytes.length,path,prompt,maximum,diagnostic,
                MineAgentRuntimeServices.config(server).snapshot().revision(),NativePackageCompatibility.observe(),hostApi(),MineAgentRuntimeServices.bodies(server).authorityGeneration(agent),repair,workspace,additional,dependencyGraph,dependencyDeclaration,scriptContext,shareSources,nativeSelection,shareNative);
        var job=journal.begin(in);var binding=new Binding(in);active.put(job.id(),binding);
        launch(job,binding);
        return summary(journal.get(job.id()));
    }
    private NativeCoderContext nativeSelection(ServerPlayer player,Map<String,String> args)throws Exception {
        boolean any=List.of("nativeSnapshot","nativeClasses","nativeLiveSelections","shareNativeContext").stream().anyMatch(args::containsKey);if(!any)return null;
        if(!args.keySet().containsAll(Set.of("nativeSnapshot","nativeClasses","shareNativeContext")))throw new IllegalArgumentException("STUDIO_CODER_NATIVE_SELECTION");
        String liveSource=args.getOrDefault("nativeLiveSelections","[]");
        if(args.get("nativeSnapshot").isEmpty()&&args.get("nativeClasses").equals("[]")&&liveSource.equals("[]")&&args.get("shareNativeContext").equals("false"))return null;
        if(!"true".equals(args.get("shareNativeContext")))throw new IllegalArgumentException("STUDIO_CODER_NATIVE_CONTEXT_CONSENT_REQUIRED");
        var latest=dev.mineagent.runtime.neoforge.compile.NativeCompilationEnvironment.latest();String hash=args.get("nativeSnapshot");
        if(!latest.hash().equals(hash))throw new IllegalStateException("NATIVE_CODER_CONTEXT_CHANGED");
        var requested=JSON.readValue(args.get("nativeClasses"),new com.fasterxml.jackson.core.type.TypeReference<List<Map<String,String>>>(){});
        var ids=JSON.readValue(liveSource,new com.fasterxml.jackson.core.type.TypeReference<List<UUID>>(){});if(requested.size()+ids.size()>NativeCoderContext.MAX_TYPES)throw new IllegalArgumentException("STUDIO_CODER_NATIVE_SELECTION");
        var contexts=new ArrayList<NativeCoderContext>();String environment=NativePackageCompatibility.observe().fingerprint();if(!latest.snapshot().environment().fingerprint().equals(environment))throw new IllegalStateException("NATIVE_CODER_CONTEXT_CHANGED");if(!requested.isEmpty())contexts.add(NativeCoderContext.resolve(latest.snapshot(),hash,environment,requested,()->true));
        if(!ids.isEmpty()){var overlays=dev.mineagent.runtime.neoforge.compile.NativeLiveClassAccess.resolve(MineAgentRuntimeServices.worldId(server),player.getUUID(),hash,ids);contexts.add(new NativeCoderContext(hash,environment,latest.snapshot().physicalSide(),latest.snapshot().mappingStatus(),List.of(),List.of(),List.of(),overlays));}
        if(contexts.isEmpty())throw new IllegalArgumentException("STUDIO_CODER_NATIVE_SELECTION");return contexts.size()==1?contexts.getFirst():NativeCoderContext.merge(contexts);
    }
    private ScriptDependencySources scriptSources(ScriptDependencyGraph graph)throws Exception {
        var files=new LinkedHashMap<UUID,Map<String,CodeDraft.SourceRef>>();var library=ServerPackageRuntime.get(server).worldLibrary();
        for(var node:graph.nodes()){
            var pkg=library.get(node.packageId()).orElseThrow(()->new IllegalStateException("STUDIO_CODER_SCRIPT_CONTEXT_CHANGED"));
            if(pkg.revision()!=node.packageRevision()||!pkg.canonicalSha256().equals(node.canonical()))throw new IllegalStateException("STUDIO_CODER_SCRIPT_CONTEXT_CHANGED");
            files.put(pkg.packageId(),RuntimeStudioPlan.refs(pkg));
        }
        return new ScriptDependencySources(graph,files);
    }
    private void launch(StudioCoderJournal.Job job,Binding binding)throws Exception {
        if(!binding.current()){end(job,"STALE","STUDIO_CODER_CONTEXT_CHANGED");return;}
        final StudioCoderJournal.Job dispatch;
        try{dispatch=journal.beginAttempt(job.id());}
        catch(Exception e){binding.valid.set(false);active.remove(job.id());throw e;}
        MineAgentRuntimeServices.worker(server).generateStudioCode(MineAgentRuntimeServices.config(server),dispatch,binding::permit,api->{
            try{boolean saved=server.submit(()->{if(!binding.current())return false;try{journal.bindDependencyApi(dispatch.id(),dispatch.last().id(),api);return true;}catch(Exception failure){throw new java.util.concurrent.CompletionException(failure);}}).get(3,java.util.concurrent.TimeUnit.SECONDS);
                if(!saved)throw new IllegalStateException("STUDIO_CODER_CANCELLED_BEFORE_DISPATCH");
            }catch(Exception failure){throw new java.util.concurrent.CompletionException(failure);}
        },sources->{
            try{boolean saved=server.submit(()->{if(!binding.current())return false;try{journal.bindScriptDependencySources(dispatch.id(),dispatch.last().id(),sources);return true;}catch(Exception failure){throw new java.util.concurrent.CompletionException(failure);}}).get(3,java.util.concurrent.TimeUnit.SECONDS);
                if(!saved)throw new IllegalStateException("STUDIO_CODER_CANCELLED_BEFORE_DISPATCH");
            }catch(Exception failure){throw new java.util.concurrent.CompletionException(failure);}
        },nativeContext->{
            try{boolean saved=server.submit(()->{if(!binding.current())return false;try{journal.bindNativeContext(dispatch.id(),dispatch.last().id(),nativeContext);return true;}catch(Exception failure){throw new java.util.concurrent.CompletionException(failure);}}).get(3,java.util.concurrent.TimeUnit.SECONDS);
                if(!saved)throw new IllegalStateException("STUDIO_CODER_CANCELLED_BEFORE_DISPATCH");
            }catch(Exception failure){throw new java.util.concurrent.CompletionException(failure);}
        })
                .whenComplete((result,error)->server.execute(()->{
                    if(closed||!server.isRunning())return;
                    var actual=result==null?StudioCoderResult.failed(dispatch.last().id(),dispatch.last().ordinal(),"STUDIO_CODER_TRANSPORT_UNKNOWN"):result;
                    try{
                        boolean current=binding.current();
                        if(!current&&actual.attempt().state().equals("VALIDATING"))actual=actual.uncertain("STUDIO_CODER_JAVA_VALIDATION_NOT_STARTED");
                        String state=!current?"STALE":actual.attempt().state().equals("VALIDATING")?"VALIDATING":actual.attempt().state().equals("ACCEPTED")?"READY":actual.retryable()?"GENERATING":"FAILED";
                        var saved=journal.result(job.id(),dispatch.last().id(),actual.attempt(),state,current?actual.error():"STUDIO_CODER_CONTEXT_CHANGED");
                        if(!Set.of("GENERATING","VALIDATING").contains(saved.state())){finishActive(saved);return;}
                        if(saved.state().equals("VALIDATING"))validateJava(saved,actual,binding);
                        else repairOrFinish(saved,binding,actual.error());
                    }catch(Exception e){receiptFailed(job.id(),binding);}
                }));
    }
    private void validateJava(StudioCoderJournal.Job job,StudioCoderResult source,Binding binding){
        MineAgentRuntimeServices.worker(server).validateStudioJava(job.input(),source,binding::permit)
                .whenComplete((response,error)->server.execute(()->{
                    if(closed||!server.isRunning())return;
                    try{
                        var old=journal.get(job.id()).last();boolean current=binding.current();
                        boolean responseKnown=error==null&&response!=null&&response.type().equals("java.compile.result");
                        boolean success=responseKnown&&Boolean.TRUE.equals(response.payload().get("success"))&&"HOT_RUNTIME".equals(response.payload().get("activationMode"));
                        String diagnostic=responseKnown?trim(Objects.toString(response.payload().get("diagnostics"),""),15000):"STUDIO_CODER_JAVA_VALIDATION_UNKNOWN";
                        if(responseKnown&&Boolean.TRUE.equals(response.payload().get("success"))&&!success)diagnostic+="\nSTUDIO_CODER_NON_HOT_CLASSIFICATION";
                        String artifact=success?Objects.toString(response.payload().get("sha256"),""):"",classpath="";
                        if(success&&response.payload().get("compileContext") instanceof Map<?,?> ctx)classpath=Objects.toString(ctx.get("nativeClasspath"),"");
                        var attempt=StudioCoderJournal.copyAttempt(old,success?"ACCEPTED":responseKnown?"REJECTED":"UNKNOWN",diagnostic,artifact,classpath);
                        String state=!current?"STALE":success?"READY":responseKnown?"GENERATING":"FAILED";
                        var saved=journal.result(job.id(),old.id(),attempt,state,!current?"STUDIO_CODER_CONTEXT_CHANGED":success?"":responseKnown?"STUDIO_CODER_JAVA_REJECTED":"STUDIO_CODER_JAVA_VALIDATION_UNKNOWN");
                        if(saved.state().equals("GENERATING"))repairOrFinish(saved,binding,saved.error());else finishActive(saved);
                    }catch(Exception e){receiptFailed(job.id(),binding);}
                }));
    }
    private void repairOrFinish(StudioCoderJournal.Job job,Binding binding,String code)throws Exception {
        if(!binding.current()){end(job,"STALE","STUDIO_CODER_CONTEXT_CHANGED");return;}
        if(job.attempts().size()>=job.input().maxAttempts()){end(job,"FAILED",code);return;}
        var pending=journal.state(job.id(),job.revision(),"PENDING","");launch(pending,binding);
    }
    private void receiptFailed(UUID id,Binding binding){
        binding.valid.set(false);active.remove(id);
        try{var job=journal.refresh(id).orElseThrow();if(Set.of("PENDING","GENERATING","VALIDATING").contains(job.state()))journal.state(id,job.revision(),"INTERRUPTED","STUDIO_CODER_RECEIPT_UNCERTAIN");pause(job);}catch(Exception ignored){}
    }
    private void finishActive(StudioCoderJournal.Job job){var binding=active.remove(job.id());if(binding!=null)binding.valid.set(false);if(!job.state().equals("READY"))pause(job);}
    private void end(StudioCoderJournal.Job job,String state,String code)throws Exception {var saved=journal.state(job.id(),job.revision(),state,code);finishActive(saved);}
    private void pause(StudioCoderJournal.Job job){
        try{var task=tasks.get(job.input().task()).orElse(null);if(task!=null&&task.revision()==job.input().taskRevision()&&task.status()==TaskStatus.RUNNING)tasks.transition(task.taskId(),task.revision(),true,TaskStatus.PAUSED);}catch(Exception ignored){}
    }
    public Map<String,Object> cancel(ServerPlayer player,UUID id,long revision)throws Exception {
        var job=owned(player,id);if(!Set.of("PENDING","GENERATING","VALIDATING","READY").contains(job.state()))return summary(job);
        var saved=journal.state(id,revision,"CANCELLED","STUDIO_CODER_CANCELLED");
        var binding=active.get(id);if(binding!=null)binding.valid.set(false);
        // Keep an in-flight binding registered until its raw result is collected; no new Provider attempt.
        pause(saved);return summary(saved);
    }
    public Map<String,Object> adopt(ServerPlayer player,UUID id,long revision,String sourceHash)throws Exception {
        var job=owned(player,id);
        if(job.last()==null||!job.last().state().equals("ACCEPTED")||!job.last().candidateHash(job.input()).equals(sourceHash))throw new IllegalStateException("STUDIO_CODER_CANDIDATE_CHANGED");
        if(job.state().equals("ADOPTED"))return Map.of("draftId",job.adoptedDraft(),"state",job.state());
        if(job.state().equals("ADOPTING")&&job.revision()==revision){
            var existing=MineAgentRuntimeServices.codeDrafts(server).get(StudioCoderJournal.targetDraft(job.input())).orElse(null);
            if(existing!=null){var in=job.input();if(!existing.worldId().equals(in.world())||!existing.ownerPlayerId().equals(in.owner())||!existing.taskId().equals(in.task())||!existing.packageId().equals(in.packageId())||!existing.path().equals(in.path()))throw new IllegalStateException("STUDIO_CODER_ADOPTION_CHANGED");journal.adopted(id,existing.draftId());return Map.of("draftId",existing.draftId(),"state","ADOPTION_RECORDED_EXISTING_DRAFT");}
        }
        if(job.revision()!=revision||!Set.of("READY","ADOPTING").contains(job.state())||!currentInput(job.input(),true))throw new IllegalStateException("STUDIO_CODER_ADOPTION_CONTEXT_CHANGED");
        String source=StudioCoderResult.read(content,job.last().sourceHash(),64000);
        if(source.isBlank()||source.length()>16000)throw new IllegalStateException("STUDIO_CODER_SOURCE_LIMIT");
        if(job.input().workspace())StudioCoderResult.readFiles(content,job.input().path(),source,job.last().additionalSources());
        if(job.state().equals("READY"))job=journal.state(id,revision,"ADOPTING","");
        var in=job.input();var tasks=MineAgentRuntimeServices.tasks(server);var task=tasks.get(in.task()).orElseThrow();
        if(task.revision()==in.taskRevision()){var result=tasks.completeStep(task.taskId(),task.revision(),"generate");if(!result.accepted())throw new IllegalStateException("STUDIO_CODER_ADOPTION_TASK");task=result.task();}
        if(task.revision()!=in.taskRevision()+1||!task.runnableStepIds().contains("publish")||!currentInput(in,true))throw new IllegalStateException("STUDIO_CODER_ADOPTION_TASK");
        UUID operation=StudioCoderJournal.adoptionOperation(in);
        var draft=MineAgentRuntimeServices.codeDrafts(server).createIdempotent(operation,in.owner(),in.task(),in.packageId(),task.revision(),Math.max(1,in.packageRevision()),in.path(),source,job.last().additionalSources(),in.workspace(),in.dependencies(),in.dependencyDeclaration());
        journal.adopted(id,draft.draftId());
        return Map.of("draftId",draft.draftId(),"state","ADOPTED_DRAFT_NOT_PUBLISHED");
    }
    public Map<String,Object> read(ServerPlayer player,Map<String,String> args)throws Exception {
        ServerJavaStudio.authorize(player);String kind=args.get("kind");
        if(kind.equals("coderList")){
            if(!args.keySet().equals(Set.of("kind","offset")))throw new IllegalArgumentException("STUDIO_CODER_ARGUMENTS");int offset=Integer.parseInt(args.get("offset"));if(offset<0||offset>4096)throw new IllegalArgumentException("STUDIO_CODER_PAGE");
            var all=journal.list(player.getUUID());var page=all.stream().skip(offset).limit(4).map(this::summary).toList();return Map.of("jobs",page,"nextOffset",offset+page.size(),"more",offset+page.size()<all.size());
        }
        if(kind.equals("coderGet")){if(!args.keySet().equals(Set.of("kind","jobId")))throw new IllegalArgumentException("STUDIO_CODER_ARGUMENTS");return summary(owned(player,UUID.fromString(args.get("jobId"))));}
        if(Set.of("coderFiles","coderFile").contains(kind))return files(player,args);
        if(!kind.equals("coderText"))throw new IllegalArgumentException("STUDIO_CODER_ARGUMENTS");
        if(!args.keySet().equals(Set.of("kind","jobId","revision","part","attempt","offset")))throw new IllegalArgumentException("STUDIO_CODER_ARGUMENTS");
        var job=owned(player,UUID.fromString(args.get("jobId")));if(job.revision()!=Long.parseLong(args.get("revision")))throw new IllegalStateException("STUDIO_CODER_JOB_CHANGED");
        String part=args.get("part"),text;
        if(part.equals("request"))text=job.input().prompt();
        else if(part.equals("nativeSelection")){if(job.input().nativeSelection()==null)throw new IllegalArgumentException("STUDIO_CODER_NATIVE_CONTEXT_MISSING");text=JSON.writeValueAsString(job.input().nativeSelection());}
        else if(part.equals("dependencies"))text=new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(Map.of("declarations",job.input().dependencies(),"explicitDeclaration",job.input().dependencyDeclaration(),"graph",job.input().javaDependencies()!=null?job.input().javaDependencies():job.input().scriptDependencies()!=null?job.input().scriptDependencies().graph():Map.of()));
        else if(part.equals("metadata"))text=new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(job);
        else if(part.equals("base"))text=StudioCoderResult.read(content,job.input().sourceHash(),64000);
        else if(part.equals("diagnostics"))text=job.input().diagnostics();
        else{
            int ordinal=Integer.parseInt(args.get("attempt"));if(ordinal<1||ordinal>job.attempts().size())throw new IllegalArgumentException("STUDIO_CODER_ATTEMPT_CHANGED");
            var attempt=job.attempts().get(ordinal-1);
            text=switch(part){case "raw"->StudioCoderResult.read(content,attempt.rawHash(),StudioCoderJournal.MAX_RAW_BYTES);case "source"->StudioCoderResult.read(content,attempt.sourceHash(),64000);case "validation"->attempt.diagnostics();case "dependencyApi"->JavaDependencyApi.read(attempt.dependencyApi(),job.input().javaDependencies(),content);case "nativeContext"->{if(job.input().nativeSelection()==null)throw new IllegalArgumentException("STUDIO_CODER_NATIVE_CONTEXT_MISSING");yield job.input().nativeSelection().read(attempt.nativeContext(),content);}case "dependencySources"->{if(job.input().scriptDependencies()==null)throw new IllegalArgumentException("STUDIO_CODER_SCRIPT_CONTEXT_MISSING");yield job.input().scriptDependencies().read(attempt.scriptDependencySources(),content);}default->throw new IllegalArgumentException("STUDIO_CODER_ARGUMENTS");};
        }
        int start=Integer.parseInt(args.get("offset")),length=text.codePointCount(0,text.length());if(start<0||start>length)throw new IllegalArgumentException("STUDIO_CODER_PAGE");int end=Math.min(length,start+1024);
        return Map.of("text",text.substring(text.offsetByCodePoints(0,start),text.offsetByCodePoints(0,end)),"nextOffset",end,"more",end<length,"hash",RuntimePackageCanonicalizer.sha256(text));
    }
    private Map<String,Object> summary(StudioCoderJournal.Job job){
        var in=job.input();return Map.ofEntries(Map.entry("id",job.id()),Map.entry("revision",job.revision()),Map.entry("state",job.state()),Map.entry("error",job.error()),
                Map.entry("path",in.path()),Map.entry("agentId",in.agent()),Map.entry("taskId",in.task()),Map.entry("packageId",in.packageId()),Map.entry("baseDraft",in.baseDraft()==null?"":in.baseDraft().toString()),
                Map.entry("sourceHash",in.sourceHash()),Map.entry("workspace",in.workspace()),Map.entry("dependencyCount",in.dependencies().size()),Map.entry("dependencyHash",in.dependencyHash()),Map.entry("dependencyContextKind",in.scriptDependencies()!=null?"RHINO_SOURCES":in.javaDependencies()!=null?"JAVA_API":"NONE"),Map.entry("nativeTypeCount",in.nativeSelection()==null?0:in.nativeSelection().types().size()),Map.entry("nativeOverlayCount",in.nativeSelection()==null?0:in.nativeSelection().overlays().size()),Map.entry("nativeSelectionHash",in.nativeSelection()==null?"":in.nativeSelection().fingerprint()),Map.entry("maxAttempts",in.maxAttempts()),Map.entry("attempts",job.attempts().stream().map(a->Map.ofEntries(
                        Map.entry("id",a.id()),Map.entry("ordinal",a.ordinal()),Map.entry("state",a.state()),Map.entry("rawHash",a.rawHash()),Map.entry("rawBytes",a.rawBytes()),Map.entry("sourceHash",a.sourceHash()),
                        Map.entry("provider",trim(a.provider(),32)),Map.entry("requestedModel",trim(a.requestedModel(),48)),Map.entry("responseModel",trim(a.responseModel(),48)),
                        Map.entry("dependencyApiHash",a.dependencyApi()==null?"":a.dependencyApi().sha256()),Map.entry("dependencyApiBytes",a.dependencyApiBytes()),Map.entry("dependencySourcesHash",a.scriptDependencySources()==null?"":a.scriptDependencySources().sha256()),Map.entry("nativeContextHash",a.nativeContext()==null?"":a.nativeContext().sha256()),Map.entry("artifact",a.artifact()),Map.entry("nativeClasspath",a.nativeClasspath()),Map.entry("candidateHash",candidateHash(a,in)),Map.entry("entry",a.entry().isEmpty()?in.path():a.entry()),Map.entry("fileCount",a.files(in.path()).size()))).toList()),
                Map.entry("adoptedDraft",job.adoptedDraft()==null?"":job.adoptedDraft().toString()),Map.entry("metadataOnlyResume",job.state().equals("ADOPTING")),Map.entry("repairOf",in.repair()==null?"":in.repair().job().toString()));
    }
    private static String candidateHash(StudioCoderJournal.Attempt attempt,StudioCoderJournal.Input input){try{return attempt.candidateHash(input);}catch(Exception e){throw new IllegalStateException("STUDIO_CODER_FINGERPRINT");}}
    private Map<String,Object> files(ServerPlayer player,Map<String,String> args)throws Exception {
        boolean contentRead=args.get("kind").equals("coderFile");var required=contentRead?Set.of("kind","jobId","revision","attempt","offset","path","hash"):Set.of("kind","jobId","revision","attempt","offset");
        if(!args.keySet().equals(required))throw new IllegalArgumentException("STUDIO_CODER_ARGUMENTS");var job=owned(player,UUID.fromString(args.get("jobId")));if(job.revision()!=Long.parseLong(args.get("revision")))throw new IllegalStateException("STUDIO_CODER_JOB_CHANGED");
        int ordinal=Integer.parseInt(args.get("attempt")),offset=Integer.parseInt(args.get("offset"));if(ordinal<0||ordinal>job.attempts().size()||offset<0)throw new IllegalArgumentException("STUDIO_CODER_PAGE");
        if(ordinal>0&&job.attempts().get(ordinal-1).sourceHash().isEmpty())throw new IllegalStateException("STUDIO_CODER_CANDIDATE_UNAVAILABLE");
        var base=job.input().baseDraft()==null?Map.<String,CodeDraft.SourceRef>of():job.input().files();var candidate=ordinal==0?base:job.attempts().get(ordinal-1).files(job.input().path());
        if(contentRead){var ref=candidate.get(args.get("path"));if(ref==null||!ref.sha256().equals(args.get("hash")))throw new IllegalStateException("STUDIO_CODER_FILE_CHANGED");String text=CodeDraftSources.read(content,ref);int length=text.codePointCount(0,text.length());if(offset>length)throw new IllegalArgumentException("STUDIO_CODER_PAGE");int end=Math.min(length,offset+1024);return Map.of("text",text.substring(text.offsetByCodePoints(0,offset),text.offsetByCodePoints(0,end)),"hash",ref.sha256(),"nextOffset",end,"more",end<length);}
        if(offset>128)throw new IllegalArgumentException("STUDIO_CODER_PAGE");var paths=new TreeSet<>(candidate.keySet());if(ordinal>0)paths.addAll(base.keySet());
        var page=paths.stream().skip(offset).limit(8).map(path->{var current=candidate.get(path);var before=base.get(path);String change=current==null?"REMOVED":before==null?"ADDED":current.equals(before)?"UNCHANGED":"CHANGED";var ref=current==null?before:current;return Map.of("path",path,"hash",ref.sha256(),"bytes",ref.bytes(),"change",ordinal==0?"BASE":change,"sourceAttempt",current==null?0:ordinal);}).toList();
        return Map.of("files",page,"nextOffset",offset+page.size(),"more",offset+page.size()<paths.size(),"candidateHash",ordinal==0?"":candidateHash(job.attempts().get(ordinal-1),job.input()),"entry",job.input().path());
    }
    private static String trim(String text,int length){return text.length()>length?text.substring(0,length-12)+" [TRUNCATED]":text;}
    @Override public void close(){
        if(closed)return;closed=true;active.values().forEach(b->b.valid.set(false));
        for(var id:List.copyOf(active.keySet()))try{var job=journal.refresh(id).orElseThrow();if(Set.of("PENDING","GENERATING","VALIDATING").contains(job.state()))journal.state(id,job.revision(),"INTERRUPTED","STUDIO_CODER_SERVER_STOPPED");pause(job);}catch(Exception ignored){}
        active.clear();try{taskChanges.close();}catch(Exception ignored){}
    }
}
