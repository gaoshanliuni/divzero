package dev.mineagent.runtime.neoforge.ui;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mineagent.runtime.agent.ui.UiAgentController;
import dev.mineagent.runtime.api.model.*;
import dev.mineagent.runtime.api.task.*;
import dev.mineagent.runtime.api.ui.UiProtocol.*;
import dev.mineagent.runtime.api.ui.UiAgentRpc;
import dev.mineagent.runtime.core.task.TaskStepSpec;
import dev.mineagent.runtime.core.ui.*;
import dev.mineagent.runtime.neoforge.MineAgentRuntimeServices;
import dev.mineagent.runtime.neoforge.network.UiPayloads;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** Server-owned Agent tasks. No browser/GUI classes and no client-supplied Actor authority. */
public final class ServerUiAgentTasks implements AutoCloseable {
    private final MinecraftServer server;private final ServerUiRuntime runtime;private final UiSessionService sessions;
    private final UiDelegationService grants;private final UiAgentRpcBroker rpc;private final ObjectMapper json=new ObjectMapper();
    private final Map<UUID,Job> jobs=new LinkedHashMap<>();private boolean closed;
    private final Map<UUID,UiDispatchFence> dispatchFences=new ConcurrentHashMap<>();private final AutoCloseable taskChanges;
    private static final class Job {
        final ManagedTask task;final UiDelegationService.Lease lease;final String goal,expectedTitle;
        Session session;UiAgentController controller;boolean started,released,ended,businessVerified,uiVerified;
        final dev.mineagent.runtime.agent.ui.UiPageVerification pageProof;
        dev.mineagent.runtime.agent.ui.UiPresentationVerification presentationProof;
        ContainerExpectation containerExpected;
        WorldUiExpectation worldExpected;dev.mineagent.runtime.neoforge.body.MineAgentPlayer controlledBody;java.util.concurrent.atomic.AtomicBoolean bodyPermit;
        volatile boolean finishing;
        volatile String providerId="";volatile int modelCalls;volatile boolean modelPending;
        final UiDispatchFence dispatch;
        String status="WAIT_RENDERER",clientReportedSignal="";Object businessState;List<UiSessionService.CompletedOperation> operations=List.of();
        Job(ManagedTask task,UiDelegationService.Lease lease,Session session,String goal,String expectedTitle){this.task=task;this.lease=lease;this.session=session;this.goal=goal;this.expectedTitle=expectedTitle;this.pageProof=dev.mineagent.runtime.api.ui.UiInteractionScope.pageOnly(session.binding())?new dev.mineagent.runtime.agent.ui.UiPageVerification(expectedTitle):null;this.dispatch=new UiDispatchFence(task,session,Clock.systemUTC());}
    }
    public Session openContainer(ServerPlayer viewer,Session shell,UUID agent,dev.mineagent.runtime.api.packages.RuntimePackage pkg,String goal,ContainerExpectation expected)throws Exception{
        requireThread();if(goal==null||goal.isBlank()||goal.length()>8192||expected.carriedCount()!=0||jobs.size()>=256)throw new IllegalArgumentException("CONTAINER_AGENT_GOAL");
        if(!runtime.mayControlContainerAgent(viewer,agent))throw new SecurityException("CONTAINER_AGENT_DENIED");
        var body=MineAgentRuntimeServices.bodies(server).body(agent).orElseThrow(()->new IllegalStateException("AGENT_BODY_UNAVAILABLE"));
        var config=MineAgentRuntimeServices.config(server).snapshot().values();
        if(config.getOrDefault("provider.openai.model","").isBlank()&&config.getOrDefault("provider.ollama.model","").isBlank())throw new IllegalStateException("PROVIDER_NOT_CONFIGURED");
        var task=MineAgentRuntimeServices.tasks(server).create(agent,viewer.getUUID(),"容器 Agent: "+goal.substring(0,Math.min(80,goal.length())),50,List.of(new TaskStepSpec("ui_interact",Set.of()),new TaskStepSpec("ui_verify",Set.of("ui_interact"))));
        UUID resource=null;UiDelegationService.Lease lease=null;Session session=null;
        try{
            resource=runtime.containers().open(viewer,body,pkg.packageId(),pkg.revision());
            var binding=new Binding(UUID.randomUUID().toString(),pkg.packageId(),pkg.revision(),pkg.version(),dev.mineagent.runtime.core.packages.PackageUiEntrypoints.named(pkg.entrypoints(),"container").orElseThrow(),MineAgentRuntimeServices.worldId(server),viewer.getUUID(),agent,ActorKind.AGENT,task.taskId(),task.intentRevision(),resource.toString(),false,dev.mineagent.runtime.api.ui.ContainerProtocol.CAPABILITIES);
            lease=grants.issueContainer(viewer.getUUID(),shell,binding,task,runtime.mayDelegate(viewer,agent,binding),300000);
            session=sessions.open(binding,true,true,300000);runtime.containers().attach(resource,session);
            var initial=runtime.containers().read(binding);if(expected.matches(initial,agent.toString()))throw new IllegalStateException("CONTAINER_EXPECTATION_ALREADY_PRESENT");
            var job=new Job(task,lease,session,goal,expected.name());job.containerExpected=expected;register(job);
            MineAgentRuntimeServices.audit(server).record(viewer.getUUID().toString(),"UI_CONTAINER_AGENT_DELEGATED",task.taskId().toString(),json.writeValueAsString(Map.of("lease",lease,"expected",expected,"initial",initial,"actorMode","AGENT_OWN_BODY_NOT_VIEWER_PROXY")));
            return session;
        }catch(Exception e){if(resource!=null)runtime.containers().close(resource);if(session!=null)sessions.close(viewer.getUUID(),session.sessionId());if(lease!=null)grants.revoke(viewer.getUUID(),lease.leaseId());MineAgentRuntimeServices.tasks(server).transition(task.taskId(),task.revision(),true,TaskStatus.CANCELLED);
            String code=e.getMessage();if(code==null||!code.matches("[A-Z_]{1,80}"))code=e.getClass().getSimpleName();
            MineAgentRuntimeServices.audit(server).record(viewer.getUUID().toString(),"UI_CONTAINER_ADMISSION_FAILED",task.taskId().toString(),json.writeValueAsString(Map.of("code",code,"actor",agent,"actorPosition",List.of(body.getX(),body.getY(),body.getZ()),"actorYaw",body.getYRot(),"actorPitch",body.getXRot(),"alive",body.isAlive(),"viewerPosition",List.of(viewer.getX(),viewer.getY(),viewer.getZ()))));throw e;}
    }
    ServerUiAgentTasks(MinecraftServer server,ServerUiRuntime runtime,UiSessionService sessions){
        this.server=server;this.runtime=runtime;this.sessions=sessions;grants=new UiDelegationService(MineAgentRuntimeServices.worldId(server),Clock.systemUTC(),4);
        rpc=new UiAgentRpcBroker(Clock.systemUTC(),this::rpcAuthority,8);
        taskChanges=MineAgentRuntimeServices.tasks(server).onChange(changed->{var fence=dispatchFences.get(changed.taskId());if(fence!=null&&!fence.observeTask(changed)){
            Runnable cancel=()->{var job=jobs.get(changed.taskId());if(!closed&&job!=null&&!job.ended)stop(job.task.ownerPlayerId(),job.task.taskId(),"STALE_TASK");};if(server.isSameThread())cancel.run();else server.execute(cancel);
        }});
        // Ephemeral grants never survive a restart. Do not automatically bill/resume an orphaned UI interaction.
        for(var task:MineAgentRuntimeServices.tasks(server).active())if(task.status()==TaskStatus.RUNNING&&task.steps().stream().anyMatch(s->s.stepId().equals("ui_interact"))){
            try{MineAgentRuntimeServices.tasks(server).transition(task.taskId(),task.revision(),true,TaskStatus.PAUSED);}
            catch(Exception failure){throw new IllegalStateException("UI_TASK_RECOVERY_FAILED",failure);}
        }
    }
    public Map<String,String> delegateWorld(ServerPlayer viewer,Session source,UUID agent,String goal,String expectedJson)throws Exception{
        requireThread();if(goal==null||goal.isBlank()||goal.length()>8192||jobs.size()>=256)throw new IllegalArgumentException("WORLD_UI_AGENT_GOAL");
        var expected=WorldUiExpectation.parse(expectedJson);
        var currentSource=source==null?null:sessions.get(viewer.getUUID(),source.sessionId()).orElse(null);
        grants.requireWorldSource(viewer.getUUID(),currentSource,currentSource!=null&&currentSource.equals(source)&&runtime.worldUi().authorize(currentSource)==Code.OK&&runtime.mayDelegate(viewer,agent,currentSource.binding()));
        var body=MineAgentRuntimeServices.bodies(server).body(agent).orElseThrow(()->new IllegalStateException("AGENT_BODY_UNAVAILABLE"));
        if(!body.isAlive()||body.isSpectator()||body.isUsingItem()||body.containerMenu!=body.inventoryMenu||body.taskControlOwned())throw new IllegalStateException("WORLD_UI_AGENT_BODY_BUSY");
        var config=MineAgentRuntimeServices.config(server).snapshot().values();if(config.getOrDefault("provider.openai.model","").isBlank()&&config.getOrDefault("provider.ollama.model","").isBlank())throw new IllegalStateException("PROVIDER_NOT_CONFIGURED");
        var task=MineAgentRuntimeServices.tasks(server).create(agent,viewer.getUUID(),"World UI Agent: "+goal.substring(0,Math.min(80,goal.length())),50,List.of(new TaskStepSpec("ui_interact",Set.of()),new TaskStepSpec("ui_verify",Set.of("ui_interact"))));
        var permit=new java.util.concurrent.atomic.AtomicBoolean(true);UiDelegationService.Lease lease=null;Session delegated=null;dev.mineagent.runtime.api.ui.WorldUiProtocol.Launch launch=null;
        try{
            if(!body.claimTaskControl(task.taskId(),()->permit.get()&&MineAgentRuntimeServices.bodies(server).body(agent).orElse(null)==body&&runtime.mayDelegate(viewer,agent,source.binding())&&MineAgentRuntimeServices.tasks(server).get(task.taskId()).filter(t->t.status()==TaskStatus.RUNNING&&t.intentRevision()==task.intentRevision()).isPresent(),()->{permit.set(false);var job=jobs.get(task.taskId());if(job!=null&&!job.ended)stop(viewer.getUUID(),task.taskId(),"WORLD_UI_ACTOR_REVOKED");}))throw new IllegalStateException("WORLD_UI_AGENT_BODY_BUSY");
            body.movementController().stop();
            launch=runtime.worldUi().agentLaunch(viewer,source,body,task);
            if(!permit.get())throw new SecurityException("WORLD_UI_ACTOR_REVOKED");
            lease=grants.issueWorld(viewer.getUUID(),source,launch.binding(),task,true,300000);
            delegated=sessions.open(launch.binding(),true,true,300000);runtime.worldUi().attachAgent(launch,delegated);
            var initial=json.readTree(runtime.worldUi().read(viewer,delegated,UUID.randomUUID()).get("state"));
            if(expected.matchesProjection(initial.path("data").toString()))throw new IllegalStateException("WORLD_UI_EXPECTATION_ALREADY_PRESENT");
            var job=new Job(task,lease,delegated,goal,expected.text());job.worldExpected=expected;job.controlledBody=body;job.bodyPermit=permit;register(job);
            MineAgentRuntimeServices.audit(server).record(viewer.getUUID().toString(),"WORLD_UI_AGENT_DELEGATED",task.taskId().toString(),json.writeValueAsString(Map.of("lease",lease,"launch",launch,"initialProjection",initial,"expected",expectedJson,"freshDocument",true,"actorPosition",List.of(body.getX(),body.getY(),body.getZ()),"viewerPosition",List.of(viewer.getX(),viewer.getY(),viewer.getZ()))));
            return Map.of("sourceSessionId",source.sessionId().toString(),"launch",json.writeValueAsString(launch),"session",json.writeValueAsString(delegated),"taskId",task.taskId().toString(),"status",job.status);
        }catch(Exception|LinkageError failure){
            permit.set(false);body.releaseTaskControl(task.taskId());if(launch!=null){runtime.worldUi().cancelLaunch(viewer.getUUID(),launch);runtime.worldUi().retireSourceNotice(viewer,source);}if(delegated!=null)sessions.close(viewer.getUUID(),delegated.sessionId());if(lease!=null)grants.revoke(viewer.getUUID(),lease.leaseId());
            var current=MineAgentRuntimeServices.tasks(server).get(task.taskId()).orElseThrow();if(current.status()==TaskStatus.RUNNING)MineAgentRuntimeServices.tasks(server).transition(task.taskId(),current.revision(),true,TaskStatus.PAUSED);
            MineAgentRuntimeServices.audit(server).record(viewer.getUUID().toString(),"WORLD_UI_AGENT_ADMISSION_FAILED",task.taskId().toString(),json.writeValueAsString(Map.of("code",failure.getMessage()==null?failure.getClass().getSimpleName():failure.getMessage(),"agent",agent,"source",source.binding().viewId(),"noAutomaticReplay",true)));throw failure;
        }
    }
    public Map<String,String> delegate(ServerPlayer viewer,Session source,UUID agent,String goal,String expectedTitle) throws Exception {
        return delegate(viewer,source,agent,goal,expectedTitle,false);
    }
    public Map<String,String> delegate(ServerPlayer viewer,Session source,UUID agent,String goal,String expectedTitle,boolean presentationOnly) throws Exception {
        requireThread();
        if(presentationOnly&&!dev.mineagent.runtime.api.ui.UiInteractionScope.pageOnly(source.binding()))throw new IllegalArgumentException("UI_PRESENTATION_PAGE_SCOPE_REQUIRED");
        if(goal==null||goal.isBlank()||goal.length()>8192||expectedTitle==null||expectedTitle.isBlank()||expectedTitle.length()>256)throw new IllegalArgumentException("UI_GOAL_REQUIRED");
        if(jobs.size()>=256)throw new IllegalStateException("UI_TASK_HISTORY_BUDGET");
        if(!runtime.mayDelegate(viewer,agent,source.binding()))throw new SecurityException("UI_DELEGATION_DENIED");
        var config=MineAgentRuntimeServices.config(server);var values=config.snapshot().values();
        if((values.getOrDefault("provider.openai.baseUrl","").isBlank()||values.getOrDefault("provider.openai.model","").isBlank())
                &&(values.getOrDefault("provider.ollama.baseUrl","").isBlank()||values.getOrDefault("provider.ollama.model","").isBlank()))throw new IllegalStateException("PROVIDER_NOT_CONFIGURED");
        if(!dev.mineagent.runtime.api.ui.UiInteractionScope.pageOnly(source.binding()))runtime.scoreBridge().requireTarget(source.binding(),runtime.audience(viewer));
        String title=goal.substring(0,goal.offsetByCodePoints(0,Math.min(100,goal.codePointCount(0,goal.length()))));
        var task=MineAgentRuntimeServices.tasks(server).create(agent,viewer.getUUID(),"UI Agent: "+title,50,List.of(
                new TaskStepSpec("ui_interact",Set.of()),new TaskStepSpec("ui_verify",Set.of("ui_interact"))));
        UiDelegationService.Lease lease=null;Session delegated=null;
        try{
            lease=grants.issue(viewer.getUUID(),source,task,true,300000);
            delegated=sessions.open(lease.binding(),true,true,300000);
            if(sessions.close(viewer.getUUID(),source.sessionId()).code()!=Code.CLOSED)throw new IllegalStateException("SOURCE_SESSION_CHANGED");
            var job=new Job(task,lease,delegated,goal,expectedTitle);if(presentationOnly)job.presentationProof=new dev.mineagent.runtime.agent.ui.UiPresentationVerification();register(job);
            MineAgentRuntimeServices.audit(server).record(viewer.getUUID().toString(),"UI_AGENT_DELEGATED",task.taskId().toString(),json.writeValueAsString(Map.of("lease",lease,"expectedTitle",expectedTitle,"goal",goal)));
            return Map.of("sourceSessionId",source.sessionId().toString(),"session",json.writeValueAsString(delegated),"taskId",task.taskId().toString(),"status",job.status);
        }catch(Exception failure){
            if(delegated!=null)sessions.close(viewer.getUUID(),delegated.sessionId());
            if(lease!=null)grants.revoke(viewer.getUUID(),lease.leaseId());
            MineAgentRuntimeServices.tasks(server).transition(task.taskId(),task.revision(),true,TaskStatus.CANCELLED);throw failure;
        }
    }
    public Code authorize(Binding binding,ServerPlayer viewer){
        var live=MineAgentRuntimeServices.tasks(server).get(binding.taskId()).orElse(null);
        boolean owned=dev.mineagent.runtime.api.ui.WorldUiProtocol.bound(binding)?runtime.worldUi().known(binding):ServerPackageRuntime.get(server).ownedPackage(viewer.getUUID(),binding.ownerPackageId(),binding.packageRevision()).isPresent();
        return grants.authorize(binding,live,owned?binding.packageRevision():-1,runtime.mayDelegate(viewer,binding.actorId(),binding));
    }
    private Code rpcAuthority(Session issued){
        if(closed)return Code.VIEW_NOT_RENDERED;
        var current=sessions.get(issued.binding().viewerPlayerId(),issued.sessionId()).orElse(null);
        if(current==null)return Code.PERMISSION_DENIED;
        if(current.status()!=Status.RENDERED)return Code.VIEW_NOT_RENDERED;
        if(current.pageGeneration()!=issued.pageGeneration()||current.controlEpoch()!=issued.controlEpoch()||!current.binding().equals(issued.binding()))return Code.STALE_VIEW;
        return Code.OK;
    }
    public void tick(){
        requireThread();rpc.tick();
        for(var job:List.copyOf(jobs.values())){
            if(job.ended||job.finishing)continue;
            if(!taskCurrent(job)||sessions.get(job.task.ownerPlayerId(),job.session.sessionId()).isEmpty()){stop(job.task.ownerPlayerId(),job.task.taskId(),"STALE_TASK");continue;}
            var current=sessions.get(job.task.ownerPlayerId(),job.session.sessionId()).orElseThrow();
            if(job.started&&!job.dispatch.observeSession(current)){stop(job.task.ownerPlayerId(),job.task.taskId(),"STALE_VIEW");continue;}
            if(!job.started&&current.status()==Status.RENDERED){job.session=current;start(job);}
        }
    }
    private void start(Job job){
        if(!job.dispatch.observeTask(MineAgentRuntimeServices.tasks(server).get(job.task.taskId()).orElse(null))||!job.dispatch.observeSession(job.session)){stop(job.task.ownerPlayerId(),job.task.taskId(),"STALE_TASK");return;}
        job.started=true;job.status="RUNNING";status(job);
        ModelProvider provider=new ModelProvider(){
            public String id(){return "server-worker";}public Set<ModelCapability> capabilities(){return Set.of(ModelCapability.PLANNING);}
            public ModelResponse complete(ModelRequest request){
                try{
                    if(!job.dispatch.allowed())throw new IllegalStateException("USER_INTERRUPTED");
                    job.modelCalls++;
                    int evidence=CaptureModelSmokeEvidence.request(server,job.task.taskId(),request,MineAgentRuntimeServices.config(server).snapshot().values().getOrDefault("provider.openai.model",""));
                    var worker=MineAgentRuntimeServices.worker(server);var config=MineAgentRuntimeServices.config(server);
                    var future=job.presentationProof!=null?worker.planPresentation(config,UUID.randomUUID(),request.prompt(),job.dispatch::allowed,job.task):worker.complete(config,request,job.dispatch::allowed,job.task);job.modelPending=true;
                    var result=future.get(100,TimeUnit.SECONDS);
                    if(!job.dispatch.allowed())throw new IllegalStateException("USER_INTERRUPTED");
                    CaptureModelSmokeEvidence.response(server,job.task.taskId(),evidence,result);
                    if(!result.type().equals("model.result")){
                        String budgetError=dev.mineagent.runtime.core.config.ServiceCallBudget.responseError(result,"");
                        if(!budgetError.isEmpty())throw new IllegalStateException(budgetError);
                        if(result.payload().getOrDefault("code","").equals("MODEL_IMAGE_REQUEST_FAILED"))throw new IllegalStateException("MODEL_IMAGE_REQUEST_FAILED:"+result.payload().getOrDefault("message","PROVIDER_REQUEST_FAILED"));
                        throw new IllegalStateException("UI_MODEL_FAILED");
                    }
                    job.providerId=String.valueOf(result.payload().getOrDefault("providerId",""));
                    return new ModelResponse(String.valueOf(result.payload().getOrDefault("providerId","server-worker")),String.valueOf(result.payload().getOrDefault("text","")));
                }catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException("USER_INTERRUPTED");}
                catch(IllegalStateException failure){if(failure.getMessage()!=null&&(failure.getMessage().startsWith("MODEL_IMAGE_REQUEST_FAILED:")||dev.mineagent.runtime.core.config.ServiceCallBudget.ERRORS.contains(failure.getMessage())))throw failure;throw new IllegalStateException("UI_MODEL_FAILED");}
                catch(Exception failure){throw new IllegalStateException("UI_MODEL_FAILED");}
                finally{job.modelPending=false;}
            }
        };
        var port=new UiAgentController.Port(){
            public CompletableFuture<String> inspect(){return onServer(()->request(job,job.presentationProof!=null?"presentationInspect":"inspect",""));}
            public CompletableFuture<String> act(String action){return onServer(()->request(job,"act",action).thenApply(receipt->{if(job.presentationProof!=null)job.presentationProof.action(action,receipt);else if(job.pageProof!=null)job.pageProof.action(action,receipt);return receipt;}));}
            public CompletableFuture<dev.mineagent.runtime.api.ui.UiCapture.Image> capture(){
                return onServer(()->request(job,"capture","").thenCompose(value->{
                    try{
                        var data=json.readTree(value);if(!data.path("status").asText().equals("CAPTURED"))throw new IllegalStateException(data.path("status").asText());
                        var manifest=json.treeToValue(data.path("capture"),dev.mineagent.runtime.api.ui.UiCapture.Manifest.class);
                        return dev.mineagent.runtime.agent.ui.UiCaptureDownload.download(manifest,index->onServer(()->{
                            try{return request(job,"captureChunk",json.writeValueAsString(Map.of("captureId",manifest.captureId(),"index",index))).thenApply(chunk->{
                                try{var reply=json.readTree(chunk);if(!reply.path("status").asText().equals("CAPTURE_CHUNK"))throw new IllegalStateException(reply.path("status").asText());return json.treeToValue(reply.path("chunk"),dev.mineagent.runtime.api.ui.UiCapture.Chunk.class);}
                                catch(java.io.IOException invalid){throw new IllegalArgumentException("UI_CAPTURE_CHUNK_JSON");}
                            });}catch(Exception invalid){return CompletableFuture.failedFuture(invalid);}
                        }),()->job.finishing||job.ended||job.released,Duration.ofSeconds(20));
                    }catch(Exception invalid){return CompletableFuture.failedFuture(invalid);}
                }));
            }
            public void cancel(){job.dispatch.revoke();job.finishing=true;server.execute(()->release(job));}
            public void onInterrupt(Runnable listener){} // Incoming interrupts cancel the owning controller directly.
        };
        job.controller=new UiAgentController(port,provider,job.presentationProof!=null);
        job.controller.run(job.goal,observation->verify(job,observation),12,Duration.ofMinutes(4))
                .whenComplete((result,failure)->server.execute(()->finish(job,result,failure)));
    }
    private CompletableFuture<String> request(Job job,String kind,String action){
        requireThread();if(job.ended||job.released||!taskCurrent(job))return CompletableFuture.failedFuture(new IllegalStateException("USER_INTERRUPTED"));
        var flight=rpc.open(job.session,kind,action,10000);
        try{send(job,"uiAgentRpc",flight.command().requestId(),flight.command());}
        catch(Exception failure){rpc.cancelSession(job.session.sessionId(),"VIEW_NOT_RENDERED");}
        return flight.result();
    }
    private boolean verify(Job job,String observation){
        requireThread();if(job.ended||job.released||!taskCurrent(job))return false;
        var viewer=server.getPlayerList().getPlayer(job.task.ownerPlayerId());if(viewer==null)return false;
        if(job.presentationProof!=null){job.uiVerified=job.presentationProof.matches(observation);return job.uiVerified;}
        if(job.pageProof!=null){job.uiVerified=job.pageProof.matches(observation);return job.uiVerified;}
        if(job.worldExpected!=null){try{
            if(authorize(job.session.binding(),viewer)!=Code.OK)return false;
            var current=json.readTree(runtime.worldUi().read(viewer,job.session,UUID.randomUUID()).get("state"));
            if(!job.worldExpected.matchesProjection(current.path("data").toString())||!job.worldExpected.matchesUi(observation))return false;
            var operations=sessions.completedOperations(viewer.getUUID(),job.session.sessionId(),64);boolean action=false;
            for(var op:operations)if(op.request().action().equals("worldui.action")&&op.receipt().code()==Code.APPLIED&&job.worldExpected.matchesProjection(json.readTree(op.receipt().values().get("state")).path("data").toString()))action=true;
            if(!action)return false;job.businessState=Map.of("projection",current,"actor",job.task.agentId(),"scope","AGENT_WORLD_UI_SERVER_PROJECTION");job.businessVerified=true;job.uiVerified=true;return true;
        }catch(Exception unavailable){return false;}}
        if(job.containerExpected!=null){
            try{
                if(authorize(job.session.binding(),viewer)!=Code.OK)return false;
                var state=runtime.containers().read(job.session.binding());if(!job.containerExpected.matches(state,job.task.agentId().toString()))return false;
                boolean changed=false;for(var operation:sessions.completedOperations(viewer.getUUID(),job.session.sessionId(),64))
                    if(operation.request().action().equals("container.act")&&operation.receipt().code()==Code.APPLIED&&json.readTree(operation.receipt().values().get("state")).path("changed").asBoolean())changed=true;
                if(!changed)return false;
                var elements=json.readTree(observation).path("elements");boolean displayed=false;
                for(var slot:state.slots())if(slot.group().equals(job.containerExpected.group())&&slot.item().id().equals(job.containerExpected.itemId())&&(job.containerExpected.name().isEmpty()||slot.item().name().equals(job.containerExpected.name())))
                    for(var element:elements)if(element.path("visible").asBoolean()&&element.path("dataSlotIndex").asText().equals(Integer.toString(slot.index()))&&element.path("label").asText().contains(slot.item().name()))displayed=true;
                if(!displayed)return false;job.businessState=state;job.businessVerified=true;job.uiVerified=true;return true;
            }catch(Exception invalid){return false;}
        }
        try{
            var state=runtime.scoreBridge().read(job.session.binding(),runtime.audience(viewer));
            if(!state.snapshot().title().equals(job.expectedTitle))return false;
            boolean heading=false;for(var element:json.readTree(observation).path("elements"))
                if(element.path("role").asText().equals("heading")&&element.path("visible").asBoolean()&&element.path("label").asText().equals(job.expectedTitle))heading=true;
            if(!heading)return false;
            job.businessState=state;job.businessVerified=true;job.uiVerified=true;return true;
        }catch(Exception invalid){return false;}
    }
    private boolean taskCurrent(Job job){
        var t=MineAgentRuntimeServices.tasks(server).get(job.task.taskId()).orElse(null);
        return t!=null&&t.worldId().equals(job.task.worldId())&&t.agentId().equals(job.task.agentId())&&t.ownerPlayerId().equals(job.task.ownerPlayerId())
                &&t.intentRevision()==job.task.intentRevision()&&t.status()==TaskStatus.RUNNING;
    }
    private void finish(Job job,UiAgentController.Outcome outcome,Throwable failure){
        if(closed||job.ended)return;job.ended=true;
        try{
            if(outcome!=null&&outcome.verified()&&job.uiVerified&&(job.pageProof!=null||job.businessVerified)&&taskCurrent(job)){
                var t=MineAgentRuntimeServices.tasks(server).get(job.task.taskId()).orElseThrow();
                var acted=MineAgentRuntimeServices.tasks(server).completeStep(t.taskId(),t.revision(),"ui_interact");
                if(!acted.accepted()||!MineAgentRuntimeServices.tasks(server).completeStep(t.taskId(),acted.task().revision(),"ui_verify").accepted())throw new IllegalStateException("UI_TASK_COMMIT_FAILED");
                job.status=job.presentationProof!=null?"PRESENTATION_VERIFIED":job.pageProof==null?"VERIFIED":"PAGE_VERIFIED";
            }else{job.status=outcome==null?"FAILED":outcome.status();pause(job);}
            var evidence=new LinkedHashMap<String,Object>();evidence.put("taskId",job.task.taskId());evidence.put("session",job.session);evidence.put("lease",job.lease);
            evidence.put("outcome",outcome);evidence.put("businessState",job.businessState);evidence.put("operations",job.operations);
            evidence.put("businessVerified",job.businessVerified);evidence.put("status",job.status);
            evidence.put("uiVerified",job.uiVerified);evidence.put("verificationScope",job.presentationProof!=null?"HOST_PRESENTATION":job.worldExpected!=null?"AGENT_WORLD_UI_SERVER_PROJECTION":job.containerExpected!=null?"AGENT_NATIVE_CONTAINER":job.pageProof==null?"SCOREVIEW_LAYOUT":"PAGE_ONLY");
            evidence.put("modelCalls",job.modelCalls);evidence.put("providerId",job.providerId);evidence.put("configuredModel",MineAgentRuntimeServices.config(server).snapshot().values().getOrDefault("provider.openai.model",""));
            MineAgentRuntimeServices.audit(server).record(job.task.agentId().toString(),"UI_AGENT_RESULT",job.task.taskId().toString(),json.writeValueAsString(evidence));
        }catch(Exception persistence){job.status="UI_TASK_COMMIT_FAILED";pause(job);}
        finally{release(job);status(job);}
    }
    private void release(Job job){
        job.dispatch.revoke();if(closed||job.released)return;job.released=true;dispatchFences.remove(job.task.taskId(),job.dispatch);
        job.operations=sessions.completedOperations(job.task.ownerPlayerId(),job.session.sessionId(),64);
        if(job.worldExpected!=null){if(job.bodyPermit!=null)job.bodyPermit.set(false);if(job.controlledBody!=null)job.controlledBody.releaseTaskControl(job.task.taskId());runtime.worldUi().closeSession(job.task.ownerPlayerId(),job.session.sessionId());}
        if(job.containerExpected!=null){try{var afterClose=runtime.containers().closeSnapshot(job.session.binding());job.businessVerified=job.businessVerified&&job.containerExpected.matches(afterClose,job.task.agentId().toString());job.businessState=Map.of("beforeClose",job.businessState==null?"UNVERIFIED":job.businessState,"afterClose",afterClose);}catch(Exception invalid){job.businessVerified=false;runtime.containers().close(job.session.binding());}}
        sessions.close(job.task.ownerPlayerId(),job.session.sessionId());grants.revoke(job.task.ownerPlayerId(),job.lease.leaseId());
        rpc.cancelSession(job.session.sessionId(),"USER_INTERRUPTED");
        send(job,"uiAgentStop",UUID.randomUUID(),Map.of("sessionId",job.session.sessionId(),"viewId",job.session.binding().viewId()));
    }
    public void stop(UUID viewer,UUID taskId,String reason){
        requireThread();var job=jobs.get(taskId);if(job==null||!job.task.ownerPlayerId().equals(viewer))throw new SecurityException("UI_TASK_NOT_OWNED");
        job.dispatch.revoke();if(job.ended)return;job.ended=true;job.status=reason;release(job);if(job.controller!=null)job.controller.cancel();
        try{if(taskCurrent(job)){var t=MineAgentRuntimeServices.tasks(server).get(taskId).orElseThrow();MineAgentRuntimeServices.tasks(server).transition(taskId,t.revision(),true,TaskStatus.CANCELLED);}}
        catch(Exception failed){job.status="UI_TASK_CANCEL_FAILED";}
        try{MineAgentRuntimeServices.audit(server).record(viewer.toString(),"UI_AGENT_STOPPED",taskId.toString(),json.writeValueAsString(Map.of("status",job.status,"session",job.session,"operations",job.operations,"modelCalls",job.modelCalls,"clientReportedSignal",job.clientReportedSignal)));}
        catch(Exception failed){job.status="UI_TASK_AUDIT_FAILED";}status(job);
    }
    public void interrupt(UUID viewer,UUID sessionId){interrupt(viewer,sessionId,"CLIENT_REQUEST");}
    public void interrupt(UUID viewer,UUID sessionId,String signal){for(var job:List.copyOf(jobs.values()))if(job.session.sessionId().equals(sessionId)&&job.task.ownerPlayerId().equals(viewer)){job.clientReportedSignal=dev.mineagent.runtime.api.ui.UiInterruptSignal.normalize(signal);stop(viewer,job.task.taskId(),"USER_INTERRUPTED");}}
    public void reply(UUID viewer,UiAgentRpc.Reply reply){requireThread();rpc.accept(viewer,reply);}
    public void disconnect(UUID viewer){for(var job:List.copyOf(jobs.values()))if(job.task.ownerPlayerId().equals(viewer)&&!job.ended)stop(viewer,job.task.taskId(),"VIEW_NOT_RENDERED");grants.disconnect(viewer);}
    public List<Map<String,String>> list(UUID owner){return jobs.values().stream().filter(j->j.task.ownerPlayerId().equals(owner)).toList().reversed().stream().limit(32)
            .map(j->Map.of("taskId",j.task.taskId().toString(),"viewId",j.session.binding().viewId(),"status",j.status,"agentId",j.task.agentId().toString(),"modelRequests",Integer.toString(j.modelCalls),"modelPending",Boolean.toString(j.modelPending))).toList();}
    private void pause(Job job){try{if(taskCurrent(job)){var t=MineAgentRuntimeServices.tasks(server).get(job.task.taskId()).orElseThrow();MineAgentRuntimeServices.tasks(server).transition(t.taskId(),t.revision(),true,TaskStatus.PAUSED);}}catch(Exception ignored){}}
    private void status(Job job){send(job,"uiAgentStatus",UUID.randomUUID(),Map.of("taskId",job.task.taskId(),"viewId",job.session.binding().viewId(),"status",job.status,"agentId",job.task.agentId()));}
    private void send(Job job,String channel,UUID id,Object data){var viewer=server.getPlayerList().getPlayer(job.task.ownerPlayerId());if(viewer==null)return;
        try{PacketDistributor.sendToPlayer(viewer,new UiPayloads.Event(id,channel,json.writeValueAsString(data)));}catch(Exception failure){throw new IllegalStateException("UI_AGENT_TRANSPORT_FAILED");}}
    private <T> CompletableFuture<T> onServer(Supplier<CompletableFuture<T>> work){var f=new CompletableFuture<T>();server.execute(()->{try{work.get().whenComplete((v,e)->{if(e!=null)f.completeExceptionally(e);else f.complete(v);});}catch(Exception e){f.completeExceptionally(e);}});return f;}
    private void requireThread(){if(!server.isSameThread()||closed)throw new IllegalStateException("UI_AGENT_SERVER_UNAVAILABLE");}
    private void register(Job job){jobs.put(job.task.taskId(),job);dispatchFences.put(job.task.taskId(),job.dispatch);}
    @Override public void close(){closed=true;dispatchFences.values().forEach(UiDispatchFence::revoke);dispatchFences.clear();try{taskChanges.close();}catch(Exception e){throw new IllegalStateException("UI_TASK_LISTENER_CLOSE",e);}finally{for(var j:jobs.values()){j.dispatch.revoke();if(j.bodyPermit!=null)j.bodyPermit.set(false);if(j.controlledBody!=null)j.controlledBody.releaseTaskControl(j.task.taskId());if(j.controller!=null)j.controller.close();}rpc.clear();grants.clear();jobs.clear();}}
}
