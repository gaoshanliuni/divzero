package dev.mineagent.runtime.neoforge.worker;

import dev.mineagent.runtime.worker.process.ManagedWorkerProcess;
import dev.mineagent.runtime.api.worker.WorkerEnvelope;
import dev.mineagent.runtime.core.config.ServerConfigService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MineAgentWorkerSupervisor implements AutoCloseable {
    private static final String RESOURCE = "/META-INF/mineagent/worker/mineagent-worker.jar";
    private volatile ManagedWorkerProcess worker;
    private Path contentRoot;
    private Path gameDirectory;
    private dev.mineagent.runtime.core.memory.PlayerPreferenceStore preferenceStore;
    private synchronized dev.mineagent.runtime.core.memory.PlayerPreferenceStore preferences()throws Exception{if(preferenceStore==null){if(gameDirectory==null)throw new IllegalStateException("PREFERENCE_STORE_UNAVAILABLE");preferenceStore=new dev.mineagent.runtime.core.memory.PlayerPreferenceStore(gameDirectory.resolve("mineagent-runtime-data/runtime.db"),java.time.Clock.systemUTC());}return preferenceStore;}

    private long restartNotBeforeEpochMillis;
    private final ExecutorService requests = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "mineagent-worker-requests");
        thread.setDaemon(true);
        return thread;
    });

    public synchronized String start(Path gameDirectory) throws Exception {
        this.gameDirectory = gameDirectory.toAbsolutePath().normalize();
        if (worker != null && worker.isAlive()) {
            return "READY";
        }
        Path directory = gameDirectory.resolve("mineagent-runtime-data").resolve("worker");
        Files.createDirectories(directory);
        Path workerJar = directory.resolve("mineagent-worker.jar");
        extractWorker(workerJar);

        String executable = Path.of(
                System.getProperty("java.home"),
                "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java"
        ).toString();
        worker = new ManagedWorkerProcess(
                List.of(executable, "--enable-native-access=ALL-UNNAMED", "-jar", workerJar.toString()),
                Duration.ofSeconds(90)
        );
        worker.start();
        String status = String.valueOf(worker.healthCheck().payload().get("status"));
        contentRoot = gameDirectory.resolve("mineagent-runtime-data").resolve("content")
                .toAbsolutePath().normalize();
        Files.createDirectories(contentRoot);
        WorkerEnvelope storage = worker.request(new WorkerEnvelope(1, UUID.randomUUID(), "storage.configure",
                Map.of("contentRoot", contentRoot.toString())));
        if (!"storage.configured".equals(storage.type())) {
            throw new IOException("worker content store rejected: " + storage.payload());
        }
        restartNotBeforeEpochMillis = 0;
        return status;
    }

    /** Native login/panel reads are observations, not Worker requests or readiness handshakes. */
    public boolean isAlive() {
        ManagedWorkerProcess current=worker;return current != null && current.isAlive();
    }

    public CompletableFuture<WorkerEnvelope> complete(
            ServerConfigService config,
            String capability,
            String prompt
    ) {
        return complete(config,new dev.mineagent.runtime.api.model.ModelRequest(dev.mineagent.runtime.api.model.ModelCapability.valueOf(capability),prompt));
    }
    public CompletableFuture<WorkerEnvelope> complete(ServerConfigService config,dev.mineagent.runtime.api.model.ModelRequest modelRequest){
        return complete(config,modelRequest,()->true);
    }
    private static Map<String,Object> taskPayload(Map<String,Object> payload,UUID world,UUID agent,UUID task,long intent){
        var result=new java.util.LinkedHashMap<String,Object>(payload);result.put("worldId",world.toString());result.put("agentId",agent.toString());result.put("taskId",task.toString());result.put("taskRevision",intent);result.put("budgetTaskRevisionKind","INTENT");return Map.copyOf(result);
    }
    private static WorkerEnvelope withTask(WorkerEnvelope request,dev.mineagent.runtime.api.task.ManagedTask task){
        return task==null?request:new WorkerEnvelope(request.protocolVersion(),request.requestId(),request.type(),taskPayload(request.payload(),task.worldId(),task.agentId(),task.taskId(),task.intentRevision()));
    }
    public CompletableFuture<WorkerEnvelope> complete(ServerConfigService config,dev.mineagent.runtime.api.model.ModelRequest modelRequest,java.util.function.BooleanSupplier permit){
        return complete(config,modelRequest,permit,null);
    }
    public CompletableFuture<WorkerEnvelope> complete(ServerConfigService config,dev.mineagent.runtime.api.model.ModelRequest modelRequest,java.util.function.BooleanSupplier permit,dev.mineagent.runtime.api.task.ManagedTask task){
        java.util.Objects.requireNonNull(permit);
        return CompletableFuture.supplyAsync(() -> {
            try {
                if(!permit.getAsBoolean())throw new dev.mineagent.runtime.worker.process.WorkerDispatchGate.Rejected();
                configureProviders(config);
                var payload=new java.util.LinkedHashMap<String,Object>();payload.put("capability",modelRequest.capability().name());payload.put("prompt",modelRequest.prompt());
                if(!modelRequest.images().isEmpty())payload.put("images",dev.mineagent.runtime.worker.WorkerModelImages.encode(modelRequest.images()));
                var request=dev.mineagent.runtime.core.config.AgentModelSettings.bind(config,withTask(new WorkerEnvelope(1,UUID.randomUUID(),"model.complete",payload),task));
                if(!modelRequest.images().isEmpty()){ensureWorker();return worker.request(request,permit);} // An uncertain image request must not be automatically billed twice.
                return requestWithRecovery(request,permit);
            } catch (Exception failure) {
                throw new java.util.concurrent.CompletionException(failure);
            }
        }, requests);
    }

    static WorkerEnvelope summaryRequest(UUID jobId,String prompt){return new WorkerEnvelope(1,jobId,"model.completeOnce",Map.of("capability","SEMANTIC","prompt",prompt));}
    static WorkerEnvelope presentationRequest(UUID id,String prompt){return new WorkerEnvelope(1,id,"model.completeOnce",Map.of("capability","PLANNING","prompt",prompt));}
    public CompletableFuture<WorkerEnvelope> planPresentation(ServerConfigService config,UUID id,String prompt,java.util.function.BooleanSupplier permit){return completeSingle(config,presentationRequest(id,prompt),permit);}
    public CompletableFuture<WorkerEnvelope> planPresentation(ServerConfigService config,UUID id,String prompt,java.util.function.BooleanSupplier permit,dev.mineagent.runtime.api.task.ManagedTask task){return completeSingle(config,withTask(presentationRequest(id,prompt),task),permit);}
    /** One durable summary batch is one Provider dispatch, including HTTP failures and uncertain transport. */
    public CompletableFuture<WorkerEnvelope> summarizeConversation(ServerConfigService config,UUID jobId,String prompt,java.util.function.BooleanSupplier permit){
        return completeSingle(config,summaryRequest(jobId,prompt),permit);
    }
    public CompletableFuture<WorkerEnvelope> summarizeConversation(ServerConfigService config,UUID jobId,String prompt,java.util.function.BooleanSupplier permit,UUID world,UUID agent){return completeSingle(config,dev.mineagent.runtime.core.config.AgentModelSettings.context(summaryRequest(jobId,prompt),world,agent),permit);}
    private CompletableFuture<WorkerEnvelope> completeSingle(ServerConfigService config,WorkerEnvelope request,java.util.function.BooleanSupplier permit){
        java.util.Objects.requireNonNull(permit);
        return CompletableFuture.supplyAsync(()->{
            try{if(!permit.getAsBoolean())throw new dev.mineagent.runtime.worker.process.WorkerDispatchGate.Rejected();configureProviders(config);ensureWorker();return worker.request(dev.mineagent.runtime.core.config.AgentModelSettings.bind(config,request),permit);}
            catch(Exception failure){throw new java.util.concurrent.CompletionException(failure);}
        },requests);
    }

    public CompletableFuture<WorkerEnvelope> streamComplete(
            ServerConfigService config,
            String capability,
            String prompt,
            java.util.function.Consumer<WorkerEnvelope> deltaConsumer
    ) {
        return streamComplete(config,capability,prompt,deltaConsumer,()->true);
    }
    public CompletableFuture<WorkerEnvelope> streamComplete(ServerConfigService config,String capability,String prompt,java.util.function.Consumer<WorkerEnvelope> deltaConsumer,java.util.function.BooleanSupplier permit){return streamComplete(config,capability,prompt,deltaConsumer,permit,null,UUID.randomUUID());}
    public CompletableFuture<WorkerEnvelope> streamComplete(ServerConfigService config,String capability,String prompt,java.util.function.Consumer<WorkerEnvelope> deltaConsumer,java.util.function.BooleanSupplier permit,dev.mineagent.runtime.core.memory.PlayerPreferenceStore.Snapshot context,UUID operation){
        return streamConversation(config,capability,prompt,deltaConsumer,permit,context,operation,null);
    }
    public CompletableFuture<WorkerEnvelope> streamConversation(ServerConfigService config,String capability,String prompt,java.util.function.Consumer<WorkerEnvelope> deltaConsumer,java.util.function.BooleanSupplier permit,dev.mineagent.runtime.core.memory.PlayerPreferenceStore.Snapshot context,UUID operation,java.util.List<java.util.Map<String,Object>> toolHistory){
        return streamConversation(config,capability,prompt,deltaConsumer,permit,context,operation,toolHistory,null,null);
    }
    public CompletableFuture<WorkerEnvelope> streamConversation(ServerConfigService config,String capability,String prompt,java.util.function.Consumer<WorkerEnvelope> deltaConsumer,java.util.function.BooleanSupplier permit,dev.mineagent.runtime.core.memory.PlayerPreferenceStore.Snapshot context,UUID operation,java.util.List<java.util.Map<String,Object>> toolHistory,UUID world,UUID agent){
        return CompletableFuture.supplyAsync(() -> {
            try {
                if(!permit.getAsBoolean())throw new dev.mineagent.runtime.worker.process.WorkerDispatchGate.Rejected();
                configureProviders(config);
                ensureWorker();
                var prefs=context==null?null:preferences();if(context!=null)prefs.prepareUse(operation,context);
                var payload=new java.util.LinkedHashMap<String,Object>();payload.put("capability",capability);payload.put("prompt",prompt);if(toolHistory!=null){payload.put("conversationTools",true);payload.put("toolHistory",toolHistory);}
                boolean returned=false;try{var result=worker.streamRequest(dev.mineagent.runtime.core.config.AgentModelSettings.bind(config,dev.mineagent.runtime.core.config.AgentModelSettings.context(new WorkerEnvelope(1,operation,"model.stream",payload),world,agent)),deltaConsumer,toolHistory==null?Duration.ofSeconds(90):Duration.ofMinutes(4),()->permit.getAsBoolean()&&(context==null||prefs.current(context)));returned=result.type().equals("model.stream.result");return result;}finally{if(context!=null)prefs.finishUse(operation,returned?"RESPONSE_RETURNED":"OUTCOME_UNKNOWN");}
            } catch (Exception failure) {
                throw new java.util.concurrent.CompletionException(failure);
            }
        }, requests);
    }

    public CompletableFuture<WorkerEnvelope> webResearch(ServerConfigService config,String kind,String value,String scope,java.util.function.BooleanSupplier permit){
        return CompletableFuture.supplyAsync(()->{
            try{
                if(!java.util.Set.of("web.search","web.read").contains(kind))throw new IllegalArgumentException("WEB_KIND");
                java.util.function.BooleanSupplier allowed=()->permit.getAsBoolean()&&Boolean.parseBoolean(config.snapshot().values().getOrDefault("web.enabled","true"));
                if(!allowed.getAsBoolean())return new WorkerEnvelope(1,UUID.randomUUID(),"error",Map.of("code","WEB_DISABLED"));
                ensureWorker();return worker.request(new WorkerEnvelope(1,UUID.randomUUID(),kind,Map.of("value",value,"scope",scope)),Duration.ofSeconds(45),allowed);
            }catch(Exception failure){throw new java.util.concurrent.CompletionException(failure);}
        },requests);
    }
    public CompletableFuture<WorkerEnvelope> synthesize(
            String text,
            String voice,
            String rate,
            String pitch,
            String volume
    ) {
        return synthesize(text,voice,rate,pitch,volume,()->true);
    }
    public CompletableFuture<WorkerEnvelope> transcribe(dev.mineagent.runtime.core.config.SpeechProviderConfig config,UUID operation,byte[] wav,java.util.function.BooleanSupplier permit){
        dev.mineagent.runtime.core.conversation.SpeechWav.validate(wav);
        return CompletableFuture.supplyAsync(()->{
            try{
                if(!permit.getAsBoolean())throw new dev.mineagent.runtime.worker.process.WorkerDispatchGate.Rejected();
                var settings=Map.of("revision",config.revision(),"enabled",config.enabled(),"baseUrl",config.baseUrl(),"model",config.model(),"language",config.language(),"apiKey",config.apiKey());
                return requestWithRecovery(new WorkerEnvelope(1,operation,"asr.transcribe",Map.of("settings",settings,"wav",java.util.Base64.getEncoder().encodeToString(wav))),permit);
            }catch(Exception failure){throw new java.util.concurrent.CompletionException(failure);}
        },requests);
    }
    public CompletableFuture<WorkerEnvelope> synthesize(String text,String voice,String rate,String pitch,String volume,java.util.function.BooleanSupplier permit){
        return CompletableFuture.supplyAsync(() -> {
            try {
                return requestWithRecovery(new WorkerEnvelope(1, UUID.randomUUID(), "tts.synthesize", Map.of(
                        "text", text,
                        "voice", voice,
                        "rate", rate,
                        "pitch", pitch,
                        "volume", volume
                )),permit);
            } catch (Exception failure) {
                throw new java.util.concurrent.CompletionException(failure);
            }
        }, requests);
    }

    /** Legacy binary/source signature retained without executing an unbudgeted generation path. */
    @Deprecated
    public CompletableFuture<WorkerEnvelope> generateCode(ServerConfigService config,String prompt,UUID worldId,UUID agentId,UUID taskId,long taskRevision,UUID packageId,long packageRevision){
        return CompletableFuture.completedFuture(new WorkerEnvelope(1,UUID.randomUUID(),"error",Map.of("code","STUDIO_CODER_ENTRY_REQUIRED","message","Use the explicit Studio candidate workflow; no Provider request was sent.")));
    }

    /** Exactly one billed attempt; repairs are separately admitted and journaled by Studio on the server thread. */
    public CompletableFuture<dev.mineagent.runtime.worker.generation.StudioCoderResult> generateStudioCode(
            ServerConfigService config,dev.mineagent.runtime.scripting.studio.StudioCoderJournal.Job job,
            java.util.function.BooleanSupplier permit) {
        if(job.input().hasDependencyContext())return CompletableFuture.failedFuture(new IllegalStateException("STUDIO_CODER_DEPENDENCY_RECEIPT_REQUIRED"));
        return generateStudioCode(config,job,permit,api->{});
    }
    public CompletableFuture<dev.mineagent.runtime.worker.generation.StudioCoderResult> generateStudioCode(
            ServerConfigService config,dev.mineagent.runtime.scripting.studio.StudioCoderJournal.Job job,java.util.function.BooleanSupplier permit,
            java.util.function.Consumer<dev.mineagent.runtime.core.packages.JavaDependencyApi.Snapshot> recordDependencyApi){
        if(job.input().scriptDependencies()!=null)return CompletableFuture.failedFuture(new IllegalStateException("STUDIO_CODER_SCRIPT_CONTEXT_RECEIPT_REQUIRED"));
        return generateStudioCode(config,job,permit,recordDependencyApi,sources->{});
    }
    public CompletableFuture<dev.mineagent.runtime.worker.generation.StudioCoderResult> generateStudioCode(
            ServerConfigService config,dev.mineagent.runtime.scripting.studio.StudioCoderJournal.Job job,java.util.function.BooleanSupplier permit,
            java.util.function.Consumer<dev.mineagent.runtime.core.packages.JavaDependencyApi.Snapshot> recordDependencyApi,
            java.util.function.Consumer<dev.mineagent.runtime.core.packages.ScriptDependencySources.Snapshot> recordScriptSources){
        if(job.input().nativeSelection()!=null)return CompletableFuture.failedFuture(new IllegalStateException("STUDIO_CODER_NATIVE_CONTEXT_RECEIPT_REQUIRED"));
        return generateStudioCode(config,job,permit,recordDependencyApi,recordScriptSources,context->{});
    }
    public CompletableFuture<dev.mineagent.runtime.worker.generation.StudioCoderResult> generateStudioCode(
            ServerConfigService config,dev.mineagent.runtime.scripting.studio.StudioCoderJournal.Job job,java.util.function.BooleanSupplier permit,
            java.util.function.Consumer<dev.mineagent.runtime.core.packages.JavaDependencyApi.Snapshot> recordDependencyApi,
            java.util.function.Consumer<dev.mineagent.runtime.core.packages.ScriptDependencySources.Snapshot> recordScriptSources,
            java.util.function.Consumer<dev.mineagent.runtime.core.compile.NativeCoderContext.Snapshot> recordNativeContext){
        return CompletableFuture.supplyAsync(()->{
            var attempt=job.last();boolean submitted=false;dev.mineagent.runtime.core.packages.JavaDependencyApi.Snapshot dependencyApi=null;dev.mineagent.runtime.core.packages.ScriptDependencySources.Snapshot scriptSources=null;dev.mineagent.runtime.core.compile.NativeCoderContext.Snapshot nativeContext=null;
            try{
                if(!permit.getAsBoolean())throw new dev.mineagent.runtime.worker.process.WorkerDispatchGate.Rejected();
                configureProviders(config);ensureWorker();
                var content=new dev.mineagent.runtime.core.content.ContentAddressedStore(contentRoot);var input=job.input();
                String primary=dev.mineagent.runtime.worker.generation.StudioCoderResult.read(content,input.sourceHash(),64000);
                Object base=input.workspace()?(input.baseDraft()==null?Map.of():dev.mineagent.runtime.worker.generation.StudioCoderResult.readFiles(content,input.path(),primary,input.additionalSources())):primary,previous="";
                String diagnostics=input.diagnostics();
                if(input.repair()!=null){var repair=input.repair();String old=dev.mineagent.runtime.worker.generation.StudioCoderResult.read(content,repair.previousHash(),repair.raw()?dev.mineagent.runtime.scripting.studio.StudioCoderJournal.MAX_RAW_BYTES:64000);
                    previous=!repair.raw()&&input.workspace()?dev.mineagent.runtime.worker.generation.StudioCoderResult.readFiles(content,repair.entry().isEmpty()?input.path():repair.entry(),old,repair.additionalSources()):old;}
                if(job.attempts().size()>1){var old=job.attempts().get(job.attempts().size()-2);previous="";diagnostics=old.diagnostics();
                    if(!old.sourceHash().isEmpty()){String text=dev.mineagent.runtime.worker.generation.StudioCoderResult.read(content,old.sourceHash(),64000);previous=input.workspace()?dev.mineagent.runtime.worker.generation.StudioCoderResult.readFiles(content,old.entry().isEmpty()?input.path():old.entry(),text,old.additionalSources()):text;}
                    else if(!old.rawHash().isEmpty())previous=dev.mineagent.runtime.worker.generation.StudioCoderResult.read(content,old.rawHash(),dev.mineagent.runtime.scripting.studio.StudioCoderJournal.MAX_RAW_BYTES);}
                String apiText="";
                if(input.javaDependencies()!=null){
                    dependencyApi=dev.mineagent.runtime.core.packages.JavaDependencyApi.capture(input.javaDependencies(),content,permit);
                    apiText=dev.mineagent.runtime.core.packages.JavaDependencyApi.read(dependencyApi,input.javaDependencies(),content);
                    recordDependencyApi.accept(dependencyApi);
                    if(!permit.getAsBoolean())throw new dev.mineagent.runtime.worker.process.WorkerDispatchGate.Rejected();
                }
                if(input.scriptDependencies()!=null){
                    if(!input.shareScriptDependencySources())throw new IllegalStateException("STUDIO_CODER_SCRIPT_SOURCE_CONSENT_REQUIRED");
                    scriptSources=input.scriptDependencies().capture(content,permit);apiText=input.scriptDependencies().read(scriptSources,content);
                    recordScriptSources.accept(scriptSources);if(!permit.getAsBoolean())throw new dev.mineagent.runtime.worker.process.WorkerDispatchGate.Rejected();
                }
                String nativeText="";
                if(input.nativeSelection()!=null){
                    var nativeSnapshot=dev.mineagent.runtime.core.compile.NativeCompilationSnapshot.read(content,input.nativeSelection().snapshot());
                    nativeContext=input.nativeSelection().capture(nativeSnapshot,content,permit);nativeText=input.nativeSelection().read(nativeContext,content);
                    recordNativeContext.accept(nativeContext);if(!permit.getAsBoolean())throw new dev.mineagent.runtime.worker.process.WorkerDispatchGate.Rejected();
                }
                String prompt=dev.mineagent.runtime.worker.generation.StudioCoderResult.promptContext(input,base,previous,diagnostics,apiText,nativeText);
                var prefs=preferences();var context=prefs.snapshot(input.owner(),input.world(),input.agent(),"GENERATION");prompt=context.append(prompt);if(prompt.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>7*1024*1024)throw new IllegalStateException("STUDIO_CODER_CONTEXT_LIMIT");prefs.prepareUse(attempt.id(),context);
                WorkerEnvelope response;boolean returned=false;dev.mineagent.runtime.worker.generation.StudioCoderResult result=null;
                try{
                    submitted=true;
                    response=worker.request(dev.mineagent.runtime.core.config.AgentModelSettings.bind(config,new WorkerEnvelope(1,attempt.id(),"studio.coder.generate",taskPayload(Map.of("prompt",prompt,"javaSource",input.javaSource(),"ordinal",attempt.ordinal(),"workspace",input.workspace(),"entryPath",input.path()),input.world(),input.agent(),input.task(),input.taskIntent()))),Duration.ofSeconds(200),()->permit.getAsBoolean()&&prefs.current(context));
                    returned=response.type().equals("studio.coder.result")&&response.requestId().equals(attempt.id());
                    if(returned){
                        result=new com.fasterxml.jackson.databind.ObjectMapper().convertValue(response.payload().get("result"),dev.mineagent.runtime.worker.generation.StudioCoderResult.class);
                        if(result.attempt().dependencyApi()!=null||result.attempt().scriptDependencySources()!=null||result.attempt().nativeContext()!=null||!result.attempt().id().equals(attempt.id())||result.attempt().ordinal()!=attempt.ordinal())throw new IllegalStateException("STUDIO_CODER_RESPONSE_CHANGED");
                        if(!result.attempt().rawHash().isEmpty()){String raw=dev.mineagent.runtime.worker.generation.StudioCoderResult.read(content,result.attempt().rawHash(),dev.mineagent.runtime.scripting.studio.StudioCoderJournal.MAX_RAW_BYTES);if(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8).length!=result.attempt().rawBytes())throw new IllegalStateException("STUDIO_CODER_RESPONSE_CHANGED");}
                        if(!result.attempt().sourceHash().isEmpty()&&!dev.mineagent.runtime.worker.generation.StudioCoderResult.read(content,result.attempt().sourceHash(),64000).equals(result.source()))throw new IllegalStateException("STUDIO_CODER_RESPONSE_CHANGED");
                        if(!result.attempt().sourceHash().isEmpty()){
                            if(input.workspace()){if(!input.path().equals(result.attempt().entry()))throw new IllegalStateException("STUDIO_CODER_RESPONSE_CHANGED");dev.mineagent.runtime.worker.generation.StudioCoderResult.readFiles(content,result.attempt().entry(),result.source(),result.attempt().additionalSources());}
                            else if(!result.attempt().additionalSources().isEmpty()||!result.attempt().entry().isEmpty())throw new IllegalStateException("STUDIO_CODER_RESPONSE_CHANGED");
                        }
                    }else result=dev.mineagent.runtime.worker.generation.StudioCoderResult.failed(attempt.id(),attempt.ordinal(),dev.mineagent.runtime.worker.generation.UiPatchTransport.responseError(attempt.id(),response).replace("UI_PATCH_","STUDIO_CODER_"));
                }
                finally{try{prefs.finishUse(attempt.id(),returned?"RESPONSE_RETURNED":"OUTCOME_UNKNOWN");}catch(Exception receipt){if(result==null)throw receipt;result=result.uncertain("STUDIO_CODER_PREFERENCE_RECEIPT_UNCERTAIN");}}
                result=result.withDependencyApi(dependencyApi).withScriptDependencySources(scriptSources).withNativeContext(nativeContext);return prefs.current(context)?result:result.uncertain("STUDIO_CODER_PREFERENCE_CHANGED");
            }catch(Exception failure){
                String code=failure instanceof dev.mineagent.runtime.worker.process.WorkerDispatchGate.Rejected?"STUDIO_CODER_CANCELLED_BEFORE_DISPATCH":java.util.Set.of("STUDIO_CODER_CONTEXT_LIMIT","STUDIO_CODER_DEPENDENCY_API_CONTEXT_LIMIT","STUDIO_CODER_SCRIPT_SOURCE_CONTEXT_LIMIT","NATIVE_CODER_CONTEXT_LIMIT","NATIVE_CODER_SYMBOL_LIMIT","NATIVE_CODER_METHOD_LIMIT","NATIVE_CODER_SOURCE_LIMIT","NATIVE_OVERLAY_LIMIT","NATIVE_OVERLAY_MODULE_LIMIT").contains(java.util.Objects.toString(failure.getMessage(),""))?failure.getMessage():!submitted?"STUDIO_CODER_PREPARE_FAILED":dev.mineagent.runtime.worker.generation.UiPatchTransport.failure(failure).replace("UI_PATCH_","STUDIO_CODER_");
                return dev.mineagent.runtime.worker.generation.StudioCoderResult.failed(attempt.id(),attempt.ordinal(),code).withDependencyApi(dependencyApi).withScriptDependencySources(scriptSources).withNativeContext(nativeContext);
            }
        },requests);
    }

    /** Native-snapshot compilation and artifact proof only; never calls RuntimeExtension.start. */
    public CompletableFuture<WorkerEnvelope> validateStudioJava(dev.mineagent.runtime.scripting.studio.StudioCoderJournal.Input input,dev.mineagent.runtime.worker.generation.StudioCoderResult result,java.util.function.BooleanSupplier permit){
        if(input.javaDependencies()==null&&input.nativeSelection()==null)return input.workspace()?validateStudioWorkspaceJava(result,permit):validateStudioJava(result.className(),result.source(),permit);
        return CompletableFuture.supplyAsync(()->{
            try{
                if(!permit.getAsBoolean())throw new dev.mineagent.runtime.worker.process.WorkerDispatchGate.Rejected();
                var content=new dev.mineagent.runtime.core.content.ContentAddressedStore(contentRoot);
                if(input.javaDependencies()!=null)dev.mineagent.runtime.core.packages.JavaDependencyApi.read(result.attempt().dependencyApi(),input.javaDependencies(),content);
                String nativeHash="";if(input.nativeSelection()!=null){input.nativeSelection().read(result.attempt().nativeContext(),content);nativeHash=result.attempt().nativeContext().compilationSnapshot().isEmpty()?input.nativeSelection().snapshot():result.attempt().nativeContext().compilationSnapshot();}
                return Map.entry(dev.mineagent.runtime.worker.generation.StudioCoderResult.readFiles(content,input.path(),result.source(),result.attempt().additionalSources()),nativeHash);
            }catch(Exception e){throw new java.util.concurrent.CompletionException(e);}
        },requests).thenCompose(prepared->compileJavaWorkspaceAtSnapshot(result.className(),input.path(),prepared.getKey(),input.javaDependencies(),prepared.getValue(),permit)).thenApplyAsync(response->{
            try{
                if(response.type().equals("java.compile.result")&&Boolean.TRUE.equals(response.payload().get("success"))){
                    String hash=String.valueOf(response.payload().get("sha256"));var path=contentPath(hash);
                    if(!hash.equals(dev.mineagent.runtime.scripting.javaext.JavaExtensionManager.sha256(path)))throw new IllegalStateException("STUDIO_CODER_ARTIFACT_CHANGED");
                    var context=(Map<?,?>)response.payload().get("compileContext");String nativeHash=String.valueOf(context.get("nativeClasspath"));
                    if(input.nativeSelection()!=null){String expected=result.attempt().nativeContext().compilationSnapshot().isEmpty()?input.nativeSelection().snapshot():result.attempt().nativeContext().compilationSnapshot();if(!expected.equals(nativeHash))throw new IllegalStateException("STUDIO_CODER_NATIVE_CONTEXT_CHANGED");}
                    dev.mineagent.runtime.core.compile.NativeCompilationSnapshot.verifyReceipt(path,result.className(),result.attempt().candidateHash(input),nativeHash,input.dependencyHash());
                }
                if(!permit.getAsBoolean())throw new dev.mineagent.runtime.worker.process.WorkerDispatchGate.Rejected();return response;
            }catch(Exception e){throw new java.util.concurrent.CompletionException(e);}
        },requests);
    }
    public CompletableFuture<WorkerEnvelope> validateStudioJava(String className,String source,java.util.function.BooleanSupplier permit){
        return compileJava(className,source,permit).thenApplyAsync(response->{
            try{
                if(response.type().equals("java.compile.result")&&Boolean.TRUE.equals(response.payload().get("success"))){
                    String hash=String.valueOf(response.payload().get("sha256"));var path=contentPath(hash);
                    if(!hash.equals(dev.mineagent.runtime.scripting.javaext.JavaExtensionManager.sha256(path)))throw new IllegalStateException("STUDIO_CODER_ARTIFACT_CHANGED");
                    var context=(Map<?,?>)response.payload().get("compileContext");
                    dev.mineagent.runtime.core.compile.NativeCompilationSnapshot.verifyReceipt(path,className,dev.mineagent.runtime.core.packages.RuntimePackageCanonicalizer.sha256(source),String.valueOf(context.get("nativeClasspath")));
                }
                return response;
            }catch(Exception e){throw new java.util.concurrent.CompletionException(e);}
        },requests);
    }
    public CompletableFuture<WorkerEnvelope> validateStudioWorkspaceJava(dev.mineagent.runtime.worker.generation.StudioCoderResult result,java.util.function.BooleanSupplier permit){
        return CompletableFuture.supplyAsync(()->{
            try{if(!permit.getAsBoolean())throw new dev.mineagent.runtime.worker.process.WorkerDispatchGate.Rejected();return dev.mineagent.runtime.worker.generation.StudioCoderResult.readFiles(new dev.mineagent.runtime.core.content.ContentAddressedStore(contentRoot),result.attempt().entry(),result.source(),result.attempt().additionalSources());}
            catch(Exception e){throw new java.util.concurrent.CompletionException(e);}
        },requests).thenCompose(files->compileJavaWorkspace(result.className(),result.attempt().entry(),files,permit)).thenApplyAsync(response->{
            try{
                if(response.type().equals("java.compile.result")&&Boolean.TRUE.equals(response.payload().get("success"))){String hash=String.valueOf(response.payload().get("sha256"));var path=contentPath(hash);if(!hash.equals(dev.mineagent.runtime.scripting.javaext.JavaExtensionManager.sha256(path)))throw new IllegalStateException("STUDIO_CODER_ARTIFACT_CHANGED");var context=(Map<?,?>)response.payload().get("compileContext");dev.mineagent.runtime.core.compile.NativeCompilationSnapshot.verifyReceipt(path,result.className(),result.attempt().candidateHash(result.attempt().entry()),String.valueOf(context.get("nativeClasspath")));}
                return response;
            }catch(Exception e){throw new java.util.concurrent.CompletionException(e);}
        },requests);
    }

    public CompletableFuture<WorkerEnvelope> compileJava(String className, String source) {return compileJava(className,source,()->true);}
    public CompletableFuture<WorkerEnvelope> compileBoot(dev.mineagent.runtime.api.packages.RuntimePackage pkg,byte[] publicKey,java.util.function.BooleanSupplier permit){return compileBoot(pkg,publicKey,permit,null);}
    public CompletableFuture<WorkerEnvelope> compileBoot(dev.mineagent.runtime.api.packages.RuntimePackage pkg,byte[] publicKey,java.util.function.BooleanSupplier permit,dev.mineagent.runtime.core.boot.BootInstallStore.Build previous){return compileBoot(pkg,publicKey,permit,previous,dev.mineagent.runtime.core.boot.BootDependencyGraph::empty);}
    public CompletableFuture<WorkerEnvelope> compileBoot(dev.mineagent.runtime.api.packages.RuntimePackage pkg,byte[] publicKey,java.util.function.BooleanSupplier permit,dev.mineagent.runtime.core.boot.BootInstallStore.Build previous,java.util.concurrent.Callable<dev.mineagent.runtime.core.boot.BootDependencyGraph> resolveDependencies) {
        return CompletableFuture.supplyAsync(()->{
            try {
                if(contentRoot==null)throw new IllegalStateException("BOOT_STORE_UNAVAILABLE");
                var snapshot=dev.mineagent.runtime.neoforge.compile.NativeCompilationEnvironment.capture(new dev.mineagent.runtime.core.content.ContentAddressedStore(contentRoot),permit);
                var dependencies=resolveDependencies.call();dependencies.require(pkg);
                var content=new dev.mineagent.runtime.core.content.ContentAddressedStore(contentRoot);
                for(var dependency:dependencies.nodes()){
                    if(!permit.getAsBoolean())throw new IllegalStateException("BOOT_AUTHORITY_CHANGED");
                    var file=net.neoforged.fml.loading.FMLPaths.MODSDIR.get().toRealPath().resolve(dependency.filename());byte[] bytes=dev.mineagent.runtime.core.boot.BootFiles.archive(file,dependency.artifact());
                    var image=dev.mineagent.runtime.core.boot.BootArtifact.inspect(bytes);dependency.require(image.metadata(),image.hash());image.verify(java.util.Base64.getDecoder().decode(dependency.publicKey()),dependency.canonical(),image.metadata().nativeClasspath(),image.metadata().environment().fingerprint());dependencies.requireEmbedded(image.metadata());
                    if(snapshot.snapshot().environment().mods().containsKey(dependency.modId())){
                        var info=net.neoforged.fml.ModList.get().getModFileById(dependency.modId());var proof=dev.mineagent.runtime.neoforge.boot.NativeBootProof.get(dependency.modId()).orElseThrow(()->new IllegalStateException("BOOT_DEPENDENCY_LOADER_UNVERIFIED"));
                        if(info==null||!info.getFile().getFilePath().toRealPath().equals(file.toRealPath())||!proof.archiveHash().equals(dependency.artifact())||!proof.canonical().equals(dependency.canonical())||!proof.packageId().equals(dependency.packageId()))throw new IllegalStateException("BOOT_DEPENDENCY_LOADER_CHANGED");
                    }
                    if(!content.put(bytes).sha256().equals(dependency.artifact()))throw new IllegalStateException("BOOT_DEPENDENCY_CHANGED");
                }
                var values=new com.fasterxml.jackson.databind.ObjectMapper().convertValue(pkg,new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){});
                var payload=new java.util.LinkedHashMap<String,Object>();payload.put("manifest",values);payload.put("publicKey",java.util.Base64.getEncoder().encodeToString(publicKey));payload.put("nativeClasspath",snapshot.hash());payload.put("dependencies",dependencies);
                if(previous!=null){
                    if(!previous.manifest().packageId().equals(pkg.packageId()))throw new IllegalStateException("BOOT_REPLACEMENT_SOURCE");
                    var file=java.nio.file.Path.of(previous.directory()).resolve(dev.mineagent.runtime.core.boot.BootFiles.filename(previous));
                    var original=dev.mineagent.runtime.core.boot.BootArtifact.inspect(dev.mineagent.runtime.core.boot.BootFiles.archive(file,previous.artifact()));original.verify(java.util.Base64.getDecoder().decode(previous.publicKey()),previous.manifest().canonicalSha256(),previous.nativeClasspath(),previous.environment());
                    var info=net.neoforged.fml.ModList.get().getModFileById(previous.modId());String moduleHash="";
                    if(info!=null){if(!info.getFile().getFilePath().toRealPath().equals(file.toRealPath()))throw new IllegalStateException("BOOT_REPLACEMENT_LOADER_SOURCE");moduleHash=snapshot.snapshot().module(previous.modId()).sha256();}
                    else if(snapshot.snapshot().environment().mods().containsKey(previous.modId()))throw new IllegalStateException("BOOT_REPLACEMENT_LOADER_SOURCE");
                    payload.put("replacement",new dev.mineagent.runtime.core.boot.BootReplacement(previous.id(),pkg.packageId(),previous.manifest().canonicalSha256(),previous.artifact(),previous.modId(),moduleHash));
                }
                var response=requestWithRecovery(new WorkerEnvelope(1,UUID.randomUUID(),"boot.compile",payload),permit);
                if(response.type().equals("error")){String code=String.valueOf(response.payload().getOrDefault("code",""));throw new IllegalStateException(code.matches("(?:BOOT|NATIVE_CLASSPATH)_[A-Z_]{1,80}")?code:"BOOT_BUILD_FAILED");}
                if(!response.type().equals("boot.compile.result")||!snapshot.hash().equals(response.payload().get("nativeClasspath")))throw new IllegalStateException("BOOT_BUILD_CONTEXT_CHANGED");
                return response;
            }catch(Exception failure){throw new java.util.concurrent.CompletionException(failure);}
        },requests);
    }
    public CompletableFuture<WorkerEnvelope> compileJava(String className, String source,java.util.function.BooleanSupplier permit) {
        return compileJavaSources(className,source,"",Map.of(),null,"",permit);
    }
    public CompletableFuture<WorkerEnvelope> compileJavaWorkspace(String className,String entry,Map<String,String> sources,java.util.function.BooleanSupplier permit){
        var snapshot=Map.copyOf(sources);return compileJavaSources(className,snapshot.get(entry),entry,snapshot,null,"",permit);
    }
    public CompletableFuture<WorkerEnvelope> compileJavaWorkspace(String className,String entry,Map<String,String> sources,dev.mineagent.runtime.core.packages.JavaDependencyGraph dependencies,java.util.function.BooleanSupplier permit){
        var snapshot=Map.copyOf(sources);return compileJavaSources(className,snapshot.get(entry),entry,snapshot,dependencies,"",permit);
    }
    public CompletableFuture<WorkerEnvelope> compileJavaWorkspace(String className,String entry,Map<String,String> sources,dev.mineagent.runtime.core.packages.JavaDependencyGraph dependencies,dev.mineagent.runtime.core.compile.NativeCoderContext selection,dev.mineagent.runtime.core.compile.NativeCoderContext.Snapshot context,java.util.function.BooleanSupplier permit){
        if(selection==null||context==null)return CompletableFuture.failedFuture(new IllegalArgumentException("JAVA_NATIVE_CONTEXT_MISSING"));return CompletableFuture.supplyAsync(()->{try{if(!permit.getAsBoolean())throw new dev.mineagent.runtime.worker.process.WorkerDispatchGate.Rejected();var content=new dev.mineagent.runtime.core.content.ContentAddressedStore(contentRoot);selection.read(context,content);var latest=dev.mineagent.runtime.neoforge.compile.NativeCompilationEnvironment.latest();if(!latest.hash().equals(selection.snapshot())||!latest.snapshot().environment().fingerprint().equals(selection.environment())||!selection.environment().equals(dev.mineagent.runtime.neoforge.content.NativePackageCompatibility.observe().fingerprint())||selection.overlays().stream().anyMatch(o->!o.processEpoch().equals(dev.mineagent.runtime.neoforge.compile.NativeLiveClassAccess.state().processEpoch())))throw new IllegalStateException("JAVA_NATIVE_CONTEXT_CHANGED");String nativeSnapshot=context.compilationSnapshot().isEmpty()?selection.snapshot():context.compilationSnapshot();if(!permit.getAsBoolean())throw new dev.mineagent.runtime.worker.process.WorkerDispatchGate.Rejected();return nativeSnapshot;}catch(Exception failure){throw new java.util.concurrent.CompletionException(failure);}},requests).thenCompose(nativeSnapshot->compileJavaWorkspaceAtSnapshot(className,entry,sources,dependencies,nativeSnapshot,permit));
    }
    private CompletableFuture<WorkerEnvelope> compileJavaWorkspaceAtSnapshot(String className,String entry,Map<String,String> sources,dev.mineagent.runtime.core.packages.JavaDependencyGraph dependencies,String nativeSnapshot,java.util.function.BooleanSupplier permit){
        var copy=Map.copyOf(sources);return compileJavaSources(className,copy.get(entry),entry,copy,dependencies,nativeSnapshot,permit);
    }
    private CompletableFuture<WorkerEnvelope> compileJavaSources(String className,String source,String entry,Map<String,String> sources,dev.mineagent.runtime.core.packages.JavaDependencyGraph dependencies,String nativeSnapshot,java.util.function.BooleanSupplier permit){
        return CompletableFuture.supplyAsync(() -> {
            try {
                if(contentRoot==null)throw new IllegalStateException("NATIVE_CLASSPATH_STORE_UNAVAILABLE");
                var content=new dev.mineagent.runtime.core.content.ContentAddressedStore(contentRoot);
                final dev.mineagent.runtime.neoforge.compile.NativeCompilationEnvironment.Captured snapshot;
                if(nativeSnapshot==null||nativeSnapshot.isEmpty())snapshot=dev.mineagent.runtime.neoforge.compile.NativeCompilationEnvironment.capture(content,permit);
                else{
                    var latest=dev.mineagent.runtime.neoforge.compile.NativeCompilationEnvironment.latest();if(!latest.snapshot().environment().fingerprint().equals(dev.mineagent.runtime.neoforge.content.NativePackageCompatibility.observe().fingerprint()))throw new IllegalStateException("STUDIO_CODER_NATIVE_CONTEXT_CHANGED");
                    var verified=dev.mineagent.runtime.core.compile.NativeCompilationSnapshot.read(content,nativeSnapshot);boolean raw=latest.hash().equals(nativeSnapshot)&&verified.equals(latest.snapshot()),derived=verified.mappingStatus().equals("RAW_MODULE_RESOURCES_WITH_EXPLICIT_LIVE_OVERLAYS")&&verified.environment().equals(latest.snapshot().environment())&&verified.namespace().equals(latest.snapshot().namespace())&&verified.physicalSide().equals(latest.snapshot().physicalSide());if(!raw&&!derived||!permit.getAsBoolean())throw new IllegalStateException("STUDIO_CODER_NATIVE_CONTEXT_CHANGED");snapshot=new dev.mineagent.runtime.neoforge.compile.NativeCompilationEnvironment.Captured(nativeSnapshot,verified);
                }
                var payload=new java.util.LinkedHashMap<String,Object>(Map.of("className",className,"source",source,"nativeClasspath",snapshot.hash()));if(!sources.isEmpty()){payload.put("entryPath",entry);payload.put("sources",sources);}
                if(dependencies!=null)payload.put("javaDependencies",dependencies);
                // Inspection happens on this background executor, not during a server-thread Native start.
                var artifacts=dependencies==null?null:dev.mineagent.runtime.core.packages.JavaDependencyArtifacts.inspect(dependencies,content);
                var result=requestWithRecovery(new WorkerEnvelope(1, UUID.randomUUID(), "java.compile", payload),permit);
                if(result.type().equals("error")){String code=String.valueOf(result.payload().getOrDefault("code",""));throw new IllegalStateException(code.matches("(?:NATIVE_CLASSPATH|JAVA_DEPENDENCY)_[A-Z_]{1,80}")?code:"JAVA_COMPILE_FAILED");}
                if(result.type().equals("java.compile.result")){
                    var context=result.payload().get("compileContext");if(!(context instanceof Map<?,?> map)||!snapshot.hash().equals(map.get("nativeClasspath")))throw new IllegalStateException("NATIVE_CLASSPATH_RESPONSE_CHANGED");
                    if(dependencies!=null){
                        if(!dependencies.receiptHash().equals(map.get("javaDependencies")))throw new IllegalStateException("JAVA_DEPENDENCY_RECEIPT");
                        dev.mineagent.runtime.core.packages.JavaDependencyArtifacts.verify(dependencies,artifacts.paths());
                        if(Boolean.TRUE.equals(result.payload().get("success")))dev.mineagent.runtime.core.packages.JavaDependencyArtifacts.rejectCollisions(List.of(contentPath(String.valueOf(result.payload().get("sha256")))),artifacts.classOwners().keySet());
                        var local=new java.util.LinkedHashMap<String,Object>(result.payload());local.put("localDependencyClassOwners",artifacts.classOwners());
                        result=new WorkerEnvelope(result.protocolVersion(),result.requestId(),result.type(),local);
                    }
                }
                return result;
            } catch (Exception failure) {
                throw new java.util.concurrent.CompletionException(failure);
            }
        }, requests);
    }

    /** Ordinary server-configured provider path. Never reads an acceptance profile or credentials from a client. */
    public CompletableFuture<dev.mineagent.runtime.worker.generation.WorkerPackageResult> generateUiPackage(
            ServerConfigService config, dev.mineagent.runtime.core.packages.PackageGenerationJob job,
            dev.mineagent.runtime.core.crypto.IdentitySigner signer,java.util.function.BooleanSupplier permit) {
        if(job.nativeSelection()!=null)return CompletableFuture.failedFuture(new IllegalStateException("GENERATION_NATIVE_CONTEXT_RECEIPT_REQUIRED"));
        return generateUiPackage(config,job,signer,permit,context->{});
    }
    public CompletableFuture<dev.mineagent.runtime.worker.generation.WorkerPackageResult> generateUiPackage(
            ServerConfigService config,dev.mineagent.runtime.core.packages.PackageGenerationJob job,
            dev.mineagent.runtime.core.crypto.IdentitySigner signer,java.util.function.BooleanSupplier permit,
            java.util.function.Consumer<dev.mineagent.runtime.core.compile.NativeCoderContext.Snapshot> recordNativeContext) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                if(!permit.getAsBoolean())throw new dev.mineagent.runtime.worker.process.WorkerDispatchGate.Rejected();
                configureProviders(config);
                // Generation is not replayed after a worker transport failure: retrying may charge the user twice.
                ensureWorker();
                String prompt=job.prompt()+(job.purpose().equals("WORLD_CONTENT")||job.repairSource()!=null?"":"\nThis task delivers an independent CLIENT HTML/CSS/JS page whose entry must be HTML. Preview must not modify the world or fabricate game data.");
                if(job.nativeSelection()!=null){
                    var content=new dev.mineagent.runtime.core.content.ContentAddressedStore(contentRoot);var source=dev.mineagent.runtime.core.compile.NativeCompilationSnapshot.read(content,job.nativeSelection().snapshot());
                    var reference=job.nativeSelection().capture(source,content,permit);String declarations=job.nativeSelection().read(reference,content);recordNativeContext.accept(reference);if(!permit.getAsBoolean())throw new dev.mineagent.runtime.worker.process.WorkerDispatchGate.Rejected();
                    String implementationBoundary=job.nativeSelection().methods().isEmpty()?"There are no constants or method bodies in this context.":"Only explicitly selected methods include bounded normalized bytecode, constants, references, line/local/handler metadata. It is not source or decompiled code and not a live AT/Mixin-transformed implementation.";
                    String sourceBoundary=job.nativeSelection().sources().isEmpty()?"There is no attached source in this context.":"Only explicitly selected attached source mappings are included. They come from a hash-locked sibling sources JAR; honor mappingConfidence and never guess when line-table mapping is unavailable. Attached source is not live transformed code.";
                    String overlayBoundary=job.nativeSelection().overlays().isEmpty()?"There are no live/transformed class overlays in this context.":"Explicit live/transformed overlays are frozen by CAS classRef and provenance. The derived compilation snapshot applies those exact bytes, but inspection and compilation still do not prove runtime behavior or future class stability.";
                    prompt+="\nThe task Agent selected the following Native context through persisted query receipts. Use only listed binary names/descriptors/signatures/flags/throws, explicitly selected method implementations, source mappings and live overlays; do not guess APIs, bodies or mappings. "+implementationBoundary+" "+sourceBoundary+" "+overlayBoundary+" There is no object state. physicalSide is not logical-side safety; SERVER/dedicated compatibility still needs actual validation. Reference APIs only; never copy class/bytecode or claim that inspection proves runtime success.\nnativeSelection:\n"+new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(job.nativeSelection())+"\nselectedNativeContext:\n"+declarations;
                }
                if(prompt.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>7*1024*1024)throw new IllegalStateException("GENERATION_CONTEXT_LIMIT");
                var payload=new java.util.LinkedHashMap<String,Object>(Map.of(
                        "prompt",prompt,"purpose",job.purpose(),
                        "worldId", job.worldId().toString(), "agentId", job.agentId().toString(), "taskId", job.taskId().toString(),
                        "taskRevision", job.taskIntentRevision(),"budgetTaskRevisionKind","INTENT", "packageId", job.packageId().toString(), "packageRevision", job.packageRevision()));
                var prefs=preferences();var context=prefs.snapshot(job.ownerPlayerId(),job.worldId(),job.agentId(),"GENERATION");payload.put("prompt",context.append(String.valueOf(payload.get("prompt"))));if(String.valueOf(payload.get("prompt")).getBytes(java.nio.charset.StandardCharsets.UTF_8).length>7*1024*1024)throw new IllegalStateException("GENERATION_CONTEXT_LIMIT");
                payload.put("nativeEnvironment",dev.mineagent.runtime.neoforge.content.NativePackageCompatibility.observe().wire());if(job.nativeSelection()!=null)payload.put("nativeSelectionHash",job.nativeSelection().fingerprint());
                if(job.repairSource()!=null)payload.put("repairSource",new com.fasterxml.jackson.databind.ObjectMapper().convertValue(job.repairSource(),java.util.Map.class));
                prefs.prepareUse(job.operationId(),context);WorkerEnvelope response;boolean returned=false;try{response=worker.request(dev.mineagent.runtime.core.config.AgentModelSettings.bind(config,new WorkerEnvelope(1,job.operationId(),"runtime_package.generate",payload)),(job.purpose().equals("WORLD_CONTENT")||job.repairSource()!=null)?Duration.ofSeconds(200):Duration.ofSeconds(90),()->permit.getAsBoolean()&&prefs.current(context));returned=java.util.Set.of("runtime_package.result","runtime_package.failure").contains(response.type());}finally{prefs.finishUse(job.operationId(),returned?"RESPONSE_RETURNED":"OUTCOME_UNKNOWN");}
                String responseError=dev.mineagent.runtime.worker.generation.PackageGenerationFailure.response(job.operationId(),response);
                if(!responseError.isEmpty())throw new dev.mineagent.runtime.worker.generation.PackageGenerationFailure(responseError);
                return dev.mineagent.runtime.worker.generation.WorkerPackageResult.prepare(job, response,
                        new dev.mineagent.runtime.core.content.ContentAddressedStore(contentRoot), signer);
            } catch (Exception failure) {
                throw new java.util.concurrent.CompletionException(new dev.mineagent.runtime.worker.generation.PackageGenerationFailure(generationContextFailure(failure)));
            }
        }, requests);
    }
    private static String generationContextFailure(Throwable failure){
        for(Throwable current=failure;current!=null;current=current.getCause()){
            String message=java.util.Objects.toString(current.getMessage(),"");
            if(current instanceof dev.mineagent.runtime.worker.process.WorkerDispatchGate.Rejected||message.equals("STUDIO_CODER_CANCELLED_BEFORE_DISPATCH"))return "GENERATION_CANCELLED_BEFORE_DISPATCH";
            if(java.util.Set.of("GENERATION_CONTEXT_LIMIT","NATIVE_CODER_CONTEXT_LIMIT","NATIVE_CODER_SYMBOL_LIMIT","NATIVE_CODER_METHOD_LIMIT","NATIVE_CODER_SOURCE_LIMIT","NATIVE_OVERLAY_LIMIT","NATIVE_OVERLAY_MODULE_LIMIT","NATIVE_API_METHOD_ELEMENT_LIMIT","NATIVE_API_METHOD_INSTRUCTION_LIMIT","NATIVE_API_METHOD_LOCAL_LIMIT","NATIVE_API_METHOD_HANDLER_LIMIT","NATIVE_API_METHOD_TEXT_LIMIT","NATIVE_SOURCE_ARCHIVE_LIMIT","NATIVE_SOURCE_TEXT_LIMIT").contains(message))return "GENERATION_CONTEXT_LIMIT";
            if(message.equals("GENERATION_CONTEXT_CHANGED")||message.matches("(?:NATIVE_CODER|NATIVE_CLASSPATH|NATIVE_API_METHOD|NATIVE_SOURCE|NATIVE_OVERLAY)_[A-Z_]{1,80}"))return "GENERATION_NATIVE_CONTEXT_CHANGED";
        }
        return dev.mineagent.runtime.worker.generation.PackageGenerationFailure.transport(failure);
    }
    public CompletableFuture<dev.mineagent.runtime.worker.generation.WorkerUiPatchResult> generateUiPatch(ServerConfigService config,dev.mineagent.runtime.core.packages.PackageUiPatchJob job,dev.mineagent.runtime.core.crypto.IdentitySigner signer){
        return CompletableFuture.supplyAsync(()->{
            try{
                configureProviders(config);ensureWorker();var store=new dev.mineagent.runtime.core.content.ContentAddressedStore(contentRoot);
                String prompt=dev.mineagent.runtime.worker.generation.UiPatchPrompt.build(job.base(),job.prompt(),store::read);var prefs=preferences();var context=prefs.snapshot(job.ownerPlayerId(),job.worldId(),job.agentId(),"GENERATION");prompt=context.append(prompt);prefs.prepareUse(job.operationId(),context);
                WorkerEnvelope response;boolean returned=false;try{response=worker.request(dev.mineagent.runtime.core.config.AgentModelSettings.bind(config,new WorkerEnvelope(1,job.operationId(),"model.completeOnce",taskPayload(Map.of("capability","CODING","prompt",prompt),job.worldId(),job.agentId(),job.taskId(),job.taskIntentRevision()))),Duration.ofSeconds(200),()->prefs.current(context));returned=response.type().equals("model.result");}finally{prefs.finishUse(job.operationId(),returned?"RESPONSE_RETURNED":"OUTCOME_UNKNOWN");}
                String error=dev.mineagent.runtime.worker.generation.UiPatchTransport.responseError(job.operationId(),response);
                if(!error.isEmpty())return new dev.mineagent.runtime.worker.generation.WorkerUiPatchResult(null,error,"","");
                return dev.mineagent.runtime.worker.generation.WorkerUiPatchResult.prepare(job.base(),String.valueOf(response.payload().get("text")),String.valueOf(response.payload().get("providerId")),store,signer);
            }catch(Exception failed){return new dev.mineagent.runtime.worker.generation.WorkerUiPatchResult(null,dev.mineagent.runtime.worker.generation.UiPatchTransport.failure(failed),"","");}
        },requests);
    }
    public CompletableFuture<dev.mineagent.runtime.worker.generation.WorkerWorldPatchResult> generateWorldPatch(ServerConfigService config,dev.mineagent.runtime.core.packages.PackageUiPatchJob job,dev.mineagent.runtime.core.crypto.IdentitySigner signer,java.util.function.BooleanSupplier permit){
        return CompletableFuture.supplyAsync(()->{
            try{
                if(!permit.getAsBoolean())throw new dev.mineagent.runtime.worker.process.WorkerDispatchGate.Rejected();
                configureProviders(config);ensureWorker();var store=new dev.mineagent.runtime.core.content.ContentAddressedStore(contentRoot);
                String prompt=dev.mineagent.runtime.worker.generation.WorldPatchPrompt.build(job.base(),job.prompt(),store::read);var prefs=preferences();var context=prefs.snapshot(job.ownerPlayerId(),job.worldId(),job.agentId(),"GENERATION");prompt=context.append(prompt);prefs.prepareUse(job.operationId(),context);
                WorkerEnvelope response;boolean returned=false;try{response=worker.request(dev.mineagent.runtime.core.config.AgentModelSettings.bind(config,new WorkerEnvelope(1,job.operationId(),"model.completeOnce",taskPayload(Map.of("capability","CODING","prompt",prompt),job.worldId(),job.agentId(),job.taskId(),job.taskIntentRevision()))),Duration.ofSeconds(200),()->permit.getAsBoolean()&&prefs.current(context));returned=response.type().equals("model.result");}finally{prefs.finishUse(job.operationId(),returned?"RESPONSE_RETURNED":"OUTCOME_UNKNOWN");}
                String error=dev.mineagent.runtime.worker.generation.UiPatchTransport.responseError(job.operationId(),response).replace("UI_PATCH_","WORLD_PATCH_");
                if(!error.isEmpty())return new dev.mineagent.runtime.worker.generation.WorkerWorldPatchResult(null,error,"","");
                return dev.mineagent.runtime.worker.generation.WorkerWorldPatchResult.prepare(job.base(),String.valueOf(response.payload().get("text")),String.valueOf(response.payload().get("providerId")),store,signer);
            }catch(Exception failure){return new dev.mineagent.runtime.worker.generation.WorkerWorldPatchResult(null,failure instanceof dev.mineagent.runtime.worker.process.WorkerDispatchGate.Rejected?"WORLD_PATCH_CANCELLED_BEFORE_DISPATCH":dev.mineagent.runtime.worker.generation.UiPatchTransport.failure(failure).replace("UI_PATCH_","WORLD_PATCH_"),"","");}
        },requests);
    }

    public CompletableFuture<WorkerEnvelope> resolveMedia(String sourceUrl) {
        return resolveMedia(sourceUrl, "");
    }

    public CompletableFuture<WorkerEnvelope> resolveMedia(String sourceUrl, String allowedHosts) {
        return requestAsync("media.resolve", Map.of("sourceUrl", sourceUrl, "allowedHosts", allowedHosts));
    }

    public CompletableFuture<WorkerEnvelope> probeMedia(String source, String allowedHosts) {
        return requestAsync("media.probe", Map.of("source", source, "allowedHosts", allowedHosts));
    }

    public CompletableFuture<WorkerEnvelope> renderMediaFrame(
            String source,
            long positionMillis,
            int width,
            int height,
            String allowedHosts
    ) {
        return requestAsync("media.frame", Map.of(
                "source", source, "positionMillis", positionMillis, "width", width, "height", height,
                "allowedHosts", allowedHosts));
    }

    public CompletableFuture<WorkerEnvelope> renderMediaAudio(
            String source,
            long positionMillis,
            long durationMillis,
            String allowedHosts
    ) {
        return requestAsync("media.audio", Map.of(
                "source", source, "positionMillis", positionMillis, "durationMillis", durationMillis,
                "allowedHosts", allowedHosts));
    }

    private CompletableFuture<WorkerEnvelope> requestAsync(String type, Map<String, Object> payload) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return requestWithRecovery(new WorkerEnvelope(1, UUID.randomUUID(), type, payload));
            } catch (Exception failure) {
                throw new java.util.concurrent.CompletionException(failure);
            }
        }, requests);
    }

    public synchronized Path contentPath(String sha256) {
        if (contentRoot == null) {
            throw new IllegalStateException("worker content store is not configured");
        }
        return new dev.mineagent.runtime.core.content.ContentAddressedStore(contentRoot).pathFor(sha256);
    }

    public synchronized byte[] contentBytes(String sha256) throws IOException {
        if (contentRoot == null) {
            throw new IllegalStateException("worker content store is not configured");
        }
        return new dev.mineagent.runtime.core.content.ContentAddressedStore(contentRoot).read(sha256);
    }

    public CompletableFuture<java.util.List<WorkerEnvelope>> indexMods(Path modsDirectory) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (!Files.isDirectory(modsDirectory)) {
                    return java.util.List.of();
                }
                var jars = new java.util.ArrayList<Path>();
                try (var paths = Files.list(modsDirectory)) {
                    paths.filter(Files::isRegularFile)
                            .filter(path -> path.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".jar"))
                            .sorted().limit(128).forEach(jars::add);
                }
                var results = new java.util.ArrayList<WorkerEnvelope>();
                for (Path jar : jars) {
                    results.add(requestWithRecovery(new WorkerEnvelope(1, UUID.randomUUID(), "mod.index",
                            Map.of("jarPath", jar.toAbsolutePath().normalize().toString()))));
                }
                return java.util.List.copyOf(results);
            } catch (Exception failure) {
                throw new java.util.concurrent.CompletionException(failure);
            }
        }, requests);
    }

    public CompletableFuture<WorkerEnvelope> generateImage(
            ServerConfigService config,
            String prompt,
            String size
    ) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                configureProviders(config);
                return requestWithRecovery(new WorkerEnvelope(1, UUID.randomUUID(), "image.generate", Map.of(
                        "prompt", prompt, "size", size)));
            } catch (Exception failure) {
                throw new java.util.concurrent.CompletionException(failure);
            }
        }, requests);
    }

    public CompletableFuture<WorkerEnvelope> planAgent(
            ServerConfigService config,
            String prompt,
            UUID worldId,
            UUID agentId,
            UUID taskId,
            long taskRevision,
            long packageRevision
    ) {
        return planAgent(config,prompt,worldId,agentId,taskId,taskRevision,packageRevision,"GENERAL");
    }
    public CompletableFuture<WorkerEnvelope> planAgent(ServerConfigService config,String prompt,UUID worldId,UUID agentId,UUID taskId,long taskRevision,long packageRevision,String toolScope) {
        return planAgent(config,prompt,worldId,agentId,taskId,taskRevision,packageRevision,toolScope,()->true);
    }
    public CompletableFuture<WorkerEnvelope> planAgent(ServerConfigService config,String prompt,UUID worldId,UUID agentId,UUID taskId,long taskRevision,long packageRevision,String toolScope,java.util.function.BooleanSupplier permit){return planAgent(config,prompt,worldId,agentId,taskId,taskRevision,packageRevision,toolScope,permit,null);}
    public CompletableFuture<WorkerEnvelope> planAgent(ServerConfigService config,String prompt,UUID worldId,UUID agentId,UUID taskId,long taskRevision,long packageRevision,String toolScope,java.util.function.BooleanSupplier permit,UUID owner) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                if(!permit.getAsBoolean())throw new dev.mineagent.runtime.worker.process.WorkerDispatchGate.Rejected();
                configureProviders(config);
                var prefs=owner==null?null:preferences();var context=owner==null?null:prefs.snapshot(owner,worldId,agentId,"PLANNING");UUID operation=UUID.randomUUID();if(context!=null)prefs.prepareUse(operation,context);
                boolean returned=false;try{var result=requestWithRecovery(dev.mineagent.runtime.core.config.AgentModelSettings.bind(config,new WorkerEnvelope(1,operation,"agent.plan",Map.of(
                        "prompt", context==null?prompt:context.append(prompt),
                        "worldId", worldId.toString(),
                        "agentId", agentId.toString(),
                        "taskId", taskId.toString(),
                        "taskRevision", taskRevision,"budgetTaskRevisionKind","MUTATION",
                          "packageRevision", packageRevision,
                          "toolScope",toolScope
                ))),()->permit.getAsBoolean()&&(context==null||prefs.current(context)));returned=result.type().equals("agent.plan.result");return result;}finally{if(context!=null)prefs.finishUse(operation,returned?"RESPONSE_RETURNED":"OUTCOME_UNKNOWN");}
            } catch (Exception failure) {
                throw new java.util.concurrent.CompletionException(failure);
            }
        }, requests);
    }

    private synchronized void configureProviders(ServerConfigService config) throws Exception {
        var snapshot=config.providerSnapshot();
        var response=requestWithRecovery(new WorkerEnvelope(1,UUID.randomUUID(),"provider.snapshot",Map.of("values",snapshot.values(),"revision",snapshot.revision())));
        if(!response.type().equals("provider.snapshotApplied"))throw new IOException("PROVIDER_CONFIG_INVALID");
    }
    private synchronized WorkerEnvelope requestWithRecovery(WorkerEnvelope request) throws Exception {
        return requestWithRecovery(request,()->true);
    }
    private synchronized WorkerEnvelope requestWithRecovery(WorkerEnvelope request,java.util.function.BooleanSupplier permit) throws Exception {
        if(!permit.getAsBoolean())throw new dev.mineagent.runtime.worker.process.WorkerDispatchGate.Rejected();
        ensureWorker();
        try {
            return worker.request(request,permit);
        } catch (Exception firstFailure) {
            if(firstFailure instanceof dev.mineagent.runtime.worker.process.WorkerDispatchGate.Rejected)throw firstFailure;
            closeWorker();
            if(!dev.mineagent.runtime.worker.process.WorkerRetryPolicy.mayRetryAfterUncertainTransport(request.type()))throw firstFailure;
            if (System.currentTimeMillis() < restartNotBeforeEpochMillis) {
                throw firstFailure;
            }
            try {
                start(gameDirectory);
                return worker.request(request,permit);
            } catch (Exception secondFailure) {
                restartNotBeforeEpochMillis = System.currentTimeMillis() + Duration.ofSeconds(30).toMillis();
                secondFailure.addSuppressed(firstFailure);
                throw secondFailure;
            }
        }
    }

    private synchronized void ensureWorker() throws Exception {
        if (worker != null && worker.isAlive()) {
            return;
        }
        if (gameDirectory == null) {
            throw new IllegalStateException("worker has not been started");
        }
        if (System.currentTimeMillis() < restartNotBeforeEpochMillis) {
            throw new IOException("worker restart circuit is open");
        }
        start(gameDirectory);
    }

    private synchronized void closeWorker() {
        if (worker == null) {
            return;
        }
        try {
            worker.close();
        } catch (Exception ignored) {
        }
        worker = null;
    }

    private static void extractWorker(Path target) throws IOException {
        try (var input = MineAgentWorkerSupervisor.class.getResourceAsStream(RESOURCE)) {
            if (input == null) {
                throw new IOException("embedded worker resource is missing");
            }
            Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
            Files.copy(input, temporary, StandardCopyOption.REPLACE_EXISTING);
            try {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    @Override
    public synchronized void close() throws Exception {
        requests.shutdownNow();
        closeWorker();if(preferenceStore!=null){preferenceStore.close();preferenceStore=null;}
    }
}
