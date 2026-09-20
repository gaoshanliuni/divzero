package dev.mineagent.runtime.neoforge.task;
import dev.mineagent.runtime.neoforge.*;
import dev.mineagent.runtime.neoforge.body.MineAgentPlayer;
import dev.mineagent.runtime.core.task.*;
import dev.mineagent.runtime.api.task.*;
import dev.mineagent.runtime.api.recovery.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Actual body/world tools: persist intent, dispatch once, wait, then verify before advancing. */
public final class WorldActionService implements AutoCloseable {
    private final MinecraftServer server;private final WorldActionJournal journal;private final NeoForgeObjectDirectory directory;private final NeoForgeAudienceRuntime audiences;private final ObjectMapper json=new ObjectMapper();
    private record SharedEpochs(long access,long events,long schedules,long delivery,long feedback,long scores,long runCode,long packages){}
    private final Map<UUID,SharedEpochs> sharedEpochs=new LinkedHashMap<>();
    private final Map<UUID,Control> controls=new LinkedHashMap<>();private boolean closed;
    // Same-JVM admission binding: a READY batch must not silently attach to a replacement body.
    // Restarted READY/EXECUTING batches are already interrupted by WorldActionJournal.
    private final Map<UUID,MineAgentPlayer> admittedBodies=new LinkedHashMap<>();
    public WorldActionService(MinecraftServer server){this.server=server;directory=new NeoForgeObjectDirectory(server);try{audiences=new NeoForgeAudienceRuntime(server,directory);journal=WorldActionJournal.open(server.getServerDirectory().resolve("mineagent-runtime-data/runtime.db"),MineAgentRuntimeServices.worldId(server));}catch(Exception e){throw new IllegalStateException("WORLD_ACTION_STORE",e);}}
    public NeoForgeObjectDirectory directory(){return directory;}
    public NeoForgeAudienceRuntime audiences(){return audiences;}
    public boolean hasPlan(UUID task,long intent){return journal.forTask(task,intent).filter(b->!b.state().equals("COMPLETED")).isPresent();}
    public void revokeTask(UUID task)throws Exception{requireThread();for(var b:journal.active())if(b.taskId().equals(task)){journal.interrupt(b.batchId(),"AUTHORITY_REVOKED");release(b.batchId());}}
    public void beginPlanning(ManagedTask task)throws Exception{journal.beginPlanning(task);}
    public void endPlanning(ManagedTask task,boolean certain){try{journal.endPlanning(task.taskId(),task.intentRevision(),certain);}catch(Exception e){throw new IllegalStateException("WORLD_PLANNING_CAS",e);}}
    public int planningAttempts(ManagedTask task){return journal.planningAttempts(task.taskId(),task.intentRevision());}
    public String receiptsContext(ManagedTask task){try{return "\n已完成的实际动作回执（不是整个请求已完成）："+json.writeValueAsString(journal.history(task.taskId(),task.intentRevision()).stream().map(b->Map.of("round",b.round(),"state",b.state(),"actions",b.actions().stream().map(this::projectAction).toList(),"receipts",b.receipts().stream().map(r->Map.of("operationId",r.operationId(),"tool",r.tool(),"verified",r.verified(),"code",r.code(),"after",projectReceipt(task,r.tool(),r.after()))).toList())).toList());}catch(Exception e){return "\n动作回执不可用，不得声称完成。";}}
    private Object projectAction(WorldActionSpec a){return Set.of("query_objects","revalidate_objects").contains(a.tool())||dev.mineagent.runtime.core.directory.AudienceToolRequest.TOOLS.contains(a.tool())||dev.mineagent.runtime.core.events.EventToolRequest.TOOLS.contains(a.tool())||dev.mineagent.runtime.core.scheduling.ScheduleToolRequest.TOOLS.contains(a.tool())||dev.mineagent.runtime.core.shared.SharedStateToolRequest.TOOLS.contains(a.tool())||dev.mineagent.runtime.core.delivery.DeliveryToolRequest.TOOLS.contains(a.tool())||dev.mineagent.runtime.core.feedback.FeedbackToolRequest.TOOLS.contains(a.tool())||nativeObservationTool(a.tool())||dev.mineagent.runtime.core.compile.NativeKnowledgeToolRequest.TOOLS.contains(a.tool())?Map.of("tool",a.tool(),"callId",a.callId()):a;}
    private Map<String,String> projectReceipt(ManagedTask task,String tool,Map<String,String> after){
        after=directory.forModel(task,tool,after);after=audiences.forModel(task,tool,after);after=MineAgentRuntimeServices.events(server).forModel(task,tool,after);after=MineAgentRuntimeServices.schedules(server).forModel(task,tool,after);after=MineAgentRuntimeServices.sharedStates(server).forModel(task,tool,after);
        var deliveries=dev.mineagent.runtime.neoforge.ui.ServerUiRuntime.get(server).deliveries();return deliveries.feedback().forModel(task,tool,deliveries.forModel(task,tool,after));
    }
    public java.util.function.BooleanSupplier planningDataPermit(ManagedTask task){
        var permits=new ArrayList<java.util.function.BooleanSupplier>();var history=journal.history(task.taskId(),task.intentRevision());var shared=MineAgentRuntimeServices.sharedStates(server);
        for(var b:history)for(var r:b.receipts())if(dev.mineagent.runtime.core.shared.SharedStateToolRequest.TOOLS.contains(r.tool())&&r.after().equals(shared.forModel(task,r.tool(),r.after()))){var request=dev.mineagent.runtime.core.shared.SharedStateToolRequest.parse(r.tool(),r.after().get("request"));permits.add(shared.workerTargetPermit(task.ownerPlayerId(),task.agentId(),request.target()));}
        var deliveries=dev.mineagent.runtime.neoforge.ui.ServerUiRuntime.get(server).deliveries();
        for(var b:history)for(var r:b.receipts())if(dev.mineagent.runtime.core.delivery.DeliveryToolRequest.TOOLS.contains(r.tool())&&r.after().equals(deliveries.forModel(task,r.tool(),r.after())))permits.add(deliveries.workerPermit(task,r.tool(),r.after()));
        var events=MineAgentRuntimeServices.events(server);
        for(var b:history)for(var r:b.receipts())if(dev.mineagent.runtime.core.events.EventToolRequest.TOOLS.contains(r.tool())&&r.after().equals(events.forModel(task,r.tool(),r.after())))permits.add(events.observationPermit(task,r.tool(),r.after()));
        var schedules=MineAgentRuntimeServices.schedules(server);
        for(var b:history)for(var r:b.receipts())if(dev.mineagent.runtime.core.scheduling.ScheduleToolRequest.TOOLS.contains(r.tool())&&r.after().equals(schedules.forModel(task,r.tool(),r.after())))permits.add(schedules.observationPermit(task,r.tool(),r.after()));
        return ()->permits.stream().allMatch(java.util.function.BooleanSupplier::getAsBoolean);
    }
    public boolean busy(UUID agent){return controls.values().stream().anyMatch(c->c.batch.agentId().equals(agent))||journal.active().stream().anyMatch(b->b.agentId().equals(agent));}
    public List<WorldActionJournal.Batch> listForTasks(UUID owner,Collection<ManagedTask> tasks){var result=new ArrayList<WorldActionJournal.Batch>();for(var task:tasks)if(task.ownerPlayerId().equals(owner))journal.forTask(task.taskId(),task.intentRevision()).ifPresent(b->{if(!b.owner().equals(owner))throw new SecurityException("WORLD_ACTION_OWNER");result.add(b);});return List.copyOf(result);}
    public List<WorldActionJournal.Batch> historyPage(UUID owner,UUID task,int offset,int limit){return journal.historyPage(owner,task,offset,limit);}
    public WorldActionJournal.Batch historyBatch(UUID owner,UUID task,UUID batch){var b=journal.get(batch);if(!b.owner().equals(owner)||!b.taskId().equals(task))throw new SecurityException("WORLD_ACTION_HISTORY_OWNER");return b;}
    public Map<String,Object> historyReceipt(ManagedTask task,WorldActionJournal.Batch batch,int index){if(!batch.owner().equals(task.ownerPlayerId())||!batch.taskId().equals(task.taskId())||index<0||index>=batch.receipts().size())throw new SecurityException("WORLD_ACTION_RECEIPT_OWNER");var r=batch.receipts().get(index);return Map.of("operationId",r.operationId(),"index",r.index(),"tool",r.tool(),"verified",r.verified(),"code",r.code(),"before",r.before(),"after",projectReceipt(task,r.tool(),r.after()),"readOnly",true);}
    public List<WorldActionJournal.Batch> list(UUID owner){return journal.ownerHistory(owner);}
    public dev.mineagent.runtime.core.persistence.TaskBudgetLineage.Parent generationBudgetParent(UUID operation){
        requireThread();var batch=journal.generationBatch(operation).orElse(null);
        return batch==null?null:new dev.mineagent.runtime.core.persistence.TaskBudgetLineage.Parent(batch.taskId(),batch.intent(),"GENERATION");
    }
    public boolean mayProduceWorldPackage(UUID operation){
        var batch=journal.generationBatch(operation).orElse(null);if(batch==null)return true;
        var control=controls.get(batch.batchId());return !closed&&batch.state().equals("EXECUTING")&&batch.operationId().equals(operation)
                &&current(tasks().get(batch.taskId()).orElse(null),batch)&&control!=null&&control.valid();
    }
    public ManagedTask resumeWorldWait(UUID taskId,long revision)throws Exception{
        requireThread();var task=tasks().get(taskId).orElseThrow();if(task.revision()!=revision||task.status()!=TaskStatus.PAUSED)throw new IllegalStateException("STALE_TASK");
        var body=MineAgentRuntimeServices.bodies(server).body(task.agentId()).orElseThrow();if(!body.canAct())throw new IllegalStateException("BODY_UNAVAILABLE");
        var batch=journal.forTask(taskId,task.intentRevision()).orElseThrow();journal.resumeWorldWait(batch.batchId());
        var resumed=tasks().transition(taskId,revision,true,TaskStatus.RUNNING);if(!resumed.accepted()){journal.interrupt(batch.batchId(),"TASK_CHANGED");throw new IllegalStateException(resumed.errorCode());}admittedBodies.put(batch.batchId(),body);return resumed.task();
    }
    private dev.mineagent.runtime.api.packages.RuntimePackage ownedWorldPackage(UUID owner,UUID id,String hash){
        var runtime=dev.mineagent.runtime.neoforge.ui.ServerPackageRuntime.get(server);var pack=runtime.worldLibrary().get(id).orElseThrow(()->new IllegalStateException("WORLD_PACKAGE_MISSING"));
        if(runtime.ownedPackage(owner,id,pack.revision()).isEmpty()||hash!=null&&!pack.canonicalSha256().equals(hash))throw new SecurityException("WORLD_PACKAGE_CHANGED_OR_UNOWNED");
        if(!pack.entrypoints().containsKey("server")||pack.definitions().isEmpty())throw new IllegalStateException("WORLD_PACKAGE_REQUIRED");return pack;
    }
    private Map<String,String> worldPackageObservation(UUID owner,UUID id,int offset,String hash)throws Exception{
        var p=ownedWorldPackage(owner,id,hash);var world=dev.mineagent.runtime.neoforge.content.WorldContentRuntime.get(server);
        var definitions=p.definitions().values().stream().sorted(Comparator.comparing(d->d.definitionId().toString())).toList();
        var activations=world.list(owner).stream().filter(a->a.packageId().equals(id)).toList();
        return Map.of("packageId",id.toString(),"packageRevision",Long.toString(p.revision()),"canonicalSha256",p.canonicalSha256(),
                "definitions",json.writeValueAsString(definitions.stream().skip(offset).limit(16).map(d->Map.of("id",d.definitionId(),"name",d.name(),"kind",d.kind())).toList()),
                "activations",json.writeValueAsString(activations.stream().skip(offset).limit(16).map(a->Map.of("activationId",a.operationId(),"instanceId",a.instanceId(),"definitionId",a.definitionId(),"state",a.state(),"location",a.location(),"live",world.active(a.instanceId()),"verifiedBlocks",world.verifiedBlocks(a.instanceId()),"verifiedObjects",world.verifiedObjects(a.instanceId()))).toList()),
                "offset",Integer.toString(offset),"more",Boolean.toString(Math.max(definitions.size(),activations.size())>offset+16),"executionMode","NATIVE_WORLD_OBSERVATION");
    }
    private boolean verifyInstance(UUID owner,UUID instance,int minimum,int minimumObjects){
        var world=dev.mineagent.runtime.neoforge.content.WorldContentRuntime.get(server);var a=world.list(owner).stream().filter(v->v.instanceId().equals(instance)).findFirst().orElse(null);if(a==null)return false;
        var p=ownedWorldPackage(owner,a.packageId(),a.canonicalSha256());
        return dev.mineagent.runtime.core.packages.WorldInstanceProof.matches(a,p,world.instance(instance).orElse(null),MineAgentRuntimeServices.worldId(server),owner,world.active(instance),minimum,world.verifiedBlocks(instance),minimumObjects,world.verifiedObjects(instance));
    }
    private boolean verifyRule(UUID owner,UUID instance,UUID definition){
        var world=dev.mineagent.runtime.neoforge.content.WorldContentRuntime.get(server);var a=world.list(owner).stream().filter(v->v.instanceId().equals(instance)).findFirst().orElse(null);if(a==null)return false;
        var p=ownedWorldPackage(owner,a.packageId(),a.canonicalSha256());
        return dev.mineagent.runtime.core.packages.WorldInstanceProof.matchesRule(a,p,world.instance(instance).orElse(null),MineAgentRuntimeServices.worldId(server),owner,world.active(instance),definition);
    }
    public WorldActionJournal.Batch submit(ManagedTask task,Object raw)throws Exception{
        if(MineAgentRuntimeServices.events(server).feedbackTask(task.taskId()))dev.mineagent.runtime.core.feedback.FeedbackToolRequest.requireAllowed(raw);
        requireThread();if(!TaskResultFence.current(task,tasks().get(task.taskId()).orElse(null)))throw new IllegalStateException("STALE_TASK");
        var body=MineAgentRuntimeServices.bodies(server).body(task.agentId()).orElseThrow();
        if(!body.canAct())throw new IllegalStateException("BODY_UNAVAILABLE");
        var parsed=WorldActionSpec.parse(raw);var actions=new ArrayList<WorldActionSpec>();
        for(var action:parsed){
            if(action.hasPosition())position(body,action);
            if(action.tool().equals("place_block"))block(action.block());
            if(action.tool().equals("use_item"))item(action.options().get("item"));
            if(action.tool().equals("craft_recipe")){
                var recipe=body.level().recipeAccess().byKey(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.RECIPE,Identifier.parse(action.options().get("recipe")))).orElseThrow(()->new IllegalStateException("RECIPE_NOT_FOUND"));
                if(!(recipe.value() instanceof net.minecraft.world.item.crafting.CraftingRecipe))throw new IllegalStateException("RECIPE_NOT_CRAFTING");
                for(int n=0;n<Integer.parseInt(action.options().get("count"));n++)actions.add(new WorldActionSpec(action.callId()+".craft."+n,action.tool(),action.x(),action.y(),action.z(),"","",Map.of("recipe",action.options().get("recipe"),"table",action.options().get("table"),"count","1")));
                continue;
            }
            if(action.tool().equals("vein_mine")){
                var start=position(body,action);var type=body.level().getBlockState(start).getBlock();if(body.level().getBlockState(start).isAir())throw new IllegalStateException("VEIN_TARGET_EMPTY");
                int limit=Math.min(128,Math.max(1,Integer.parseInt(MineAgentRuntimeServices.config(server).snapshot().values().getOrDefault("skill.veinMining.maxBlocks","32"))));
                var positions=new dev.mineagent.runtime.agent.navigation.BoundedVeinPlanner().find(new dev.mineagent.runtime.agent.navigation.GridPos(start.getX(),start.getY(),start.getZ()),limit,p->body.level().getChunkSource().hasChunk(p.x()>>4,p.z()>>4)&&body.level().getBlockState(new BlockPos(p.x(),p.y(),p.z())).is(type));
                int i=0;for(var p:positions)actions.add(new WorldActionSpec(action.callId()+"."+(i++),"break_block",p.x(),p.y(),p.z(),"",""));
            }else actions.add(action);
        }
        int round=journal.forTask(task.taskId(),task.intentRevision()).map(b->b.round()+1).orElse(0);
        var captured=sharedAccessEpochs(task.ownerPlayerId());var batch=journal.accept(task,body.level().dimension().identifier().toString(),actions,round);sharedEpochs.putIfAbsent(batch.batchId(),captured);admittedBodies.putIfAbsent(batch.batchId(),body);completePlan(batch);return batch;
    }
    public void tick(){
        requireThread();
        for(var p:journal.planningAttention())try{var task=tasks().get(p.taskId()).orElse(null);if(task!=null&&task.intentRevision()==p.intent()&&task.status()==TaskStatus.RUNNING)tasks().transition(task.taskId(),task.revision(),true,TaskStatus.PAUSED);}catch(Exception e){MineAgentRuntimeMod.LOGGER.warn("Failed to pause uncertain planner task={}",p.taskId());}
        var work=new LinkedHashMap<UUID,WorldActionJournal.Batch>();journal.active().forEach(b->work.put(b.batchId(),b));journal.attention().forEach(b->work.put(b.batchId(),b));for(var id:List.copyOf(controls.keySet()))work.putIfAbsent(id,journal.get(id));
        for(var batch:work.values())try{
            var task=tasks().get(batch.taskId()).orElse(null);
            if(Set.of("FAILED","INTERRUPTED").contains(batch.state())){release(batch.batchId());pause(task,batch);continue;}
            if(batch.state().equals("COMPLETED")){release(batch.batchId());continue;}
            if(batch.state().equals("VERIFIED")){
                if(!current(task,batch)){journal.interrupt(batch.batchId(),"TASK_CHANGED");release(batch.batchId());continue;}
                journal.completed(batch.batchId());release(batch.batchId());continue;
            }
            if(!current(task,batch)){journal.interrupt(batch.batchId(),"TASK_CHANGED");release(batch.batchId());continue;}
            var admitted=admittedBodies.get(batch.batchId());
            if(admitted!=null&&(!admitted.canAct()||MineAgentRuntimeServices.bodies(server).body(batch.agentId()).orElse(null)!=admitted)){
                journal.interrupt(batch.batchId(),"BODY_CONTROL_CHANGED");release(batch.batchId());continue;
            }
            var control=controls.get(batch.batchId());
            if(control==null){
                if(controls.size()>=4||controls.values().stream().anyMatch(c->c.batch.agentId().equals(batch.agentId())))continue;
                var body=MineAgentRuntimeServices.bodies(server).body(batch.agentId()).orElse(null);if(body==null||!body.isAlive()||body.containerMenu!=body.inventoryMenu)continue;
                control=new Control(batch,body);if(!body.claimTaskControl(batch.batchId(),control::valid,control::stopOwned))continue;
                body.movementController().stop();body.abortMining();controls.put(batch.batchId(),control);
            }
            if(!control.valid()){journal.interrupt(batch.batchId(),"BODY_CONTROL_CHANGED");release(batch.batchId());continue;}
            if(batch.state().equals("READY")){completePlan(batch);task=tasks().get(batch.taskId()).orElseThrow();if(!task.runnableStepIds().contains("execute"))continue;control.start(batch);}
            else if(batch.state().equals("EXECUTING"))control.poll(batch);
        }catch(Exception failure){
            try{var live=journal.get(batch.batchId());if(Set.of("READY","EXECUTING").contains(live.state()))journal.interrupt(live.batchId(),safe(failure));release(batch.batchId());pause(tasks().get(batch.taskId()).orElse(null),batch);}
            catch(Exception persistence){MineAgentRuntimeMod.LOGGER.warn("World action recovery failed batch={} code={}",batch.batchId(),persistence.getClass().getSimpleName());}
        }
    }
    public void finishTask(ManagedTask task,String arguments)throws Exception{
        requireThread();if(!TaskResultFence.current(task,tasks().get(task.taskId()).orElse(null)))throw new IllegalStateException("STALE_TASK");
        var history=journal.history(task.taskId(),task.intentRevision()).stream().toList();
        if(history.isEmpty()||history.stream().anyMatch(b->!b.state().equals("COMPLETED")))throw new IllegalStateException("WORLD_ACTIONS_NOT_VERIFIED");
        if(MineAgentRuntimeServices.events(server).feedbackTask(task.taskId())){if(!current(task,history.getLast()))throw new SecurityException("FEEDBACK_TASK_AUTHORITY");dev.mineagent.runtime.neoforge.ui.ServerUiRuntime.get(server).deliveries().feedback().verifyFinish(task,arguments);var live=tasks().get(task.taskId()).orElseThrow();if(!live.runnableStepIds().contains("execute")||!tasks().completeStep(live.taskId(),live.revision(),"execute").accepted())throw new IllegalStateException("FEEDBACK_TASK_COMMIT");MineAgentRuntimeServices.audit(server).record(task.agentId().toString(),"FEEDBACK_REPLY_GOAL_VERIFIED",task.taskId().toString(),"conversation reply verified; worldBusinessVerified=false");return;}
        var checks=WorldGoalCheck.parse(arguments);var body=MineAgentRuntimeServices.bodies(server).body(task.agentId()).orElseThrow();var last=history.getLast();if(!current(task,last)||!body.level().dimension().identifier().toString().equals(last.dimension()))throw new IllegalStateException("WORLD_GOAL_CONTEXT");
        for(var b:history)for(var a:b.actions())if(Set.of("break_block","place_block").contains(a.tool())&&checks.stream().noneMatch(c->c.kind().equals("block")&&c.x()==a.x()&&c.y()==a.y()&&c.z()==a.z()))throw new IllegalStateException("WORLD_GOAL_COVERAGE");
        for(var b:history)for(var receipt:b.receipts())if(receipt.tool().equals("craft_recipe")&&checks.stream().noneMatch(c->c.kind().equals("inventory")&&c.id().equals(receipt.after().get("outputItem"))))throw new IllegalStateException("WORLD_GOAL_CRAFT_COVERAGE");
        for(var b:history)for(var receipt:b.receipts())if(nativeObservationTool(receipt.tool())&&checks.stream().noneMatch(c->c.kind().equals("native_api_observation")&&c.id().equals(receipt.operationId().toString())))throw new IllegalStateException("NATIVE_API_GOAL_COVERAGE");
        for(var b:history)for(var receipt:b.receipts())if(receipt.verified()&&Set.of("remember_native_api","revalidate_native_knowledge","forget_native_knowledge").contains(receipt.tool())){var result=json.readTree(receipt.after().getOrDefault("result","{}"));String id=result.path("knowledgeId").asText();if(id.isEmpty()||checks.stream().noneMatch(c->c.kind().equals("native_knowledge")&&c.id().equals(id)))throw new IllegalStateException("NATIVE_KNOWLEDGE_GOAL_COVERAGE");}
        if(history.stream().flatMap(b->b.actions().stream()).anyMatch(a->a.tool().equals("set_appearance"))&&checks.stream().noneMatch(c->c.kind().equals("appearance")))throw new IllegalStateException("WORLD_GOAL_APPEARANCE_COVERAGE");
        var worldContent=checks.stream().anyMatch(c->Set.of("world_instance","world_rule").contains(c.kind()))||history.stream().flatMap(b->b.receipts().stream()).anyMatch(r->r.tool().equals("create_world_package"))?dev.mineagent.runtime.neoforge.content.WorldContentRuntime.get(server):null;
        for(var b:history)for(var receipt:b.receipts())if(receipt.tool().equals("create_world_package")&&checks.stream().noneMatch(c->Set.of("world_instance","world_rule").contains(c.kind())&&worldContent.list(task.ownerPlayerId()).stream().anyMatch(a->a.instanceId().toString().equals(c.id())&&a.packageId().toString().equals(receipt.after().get("packageId"))&&a.canonicalSha256().equals(receipt.after().get("canonicalSha256")))))throw new IllegalStateException("WORLD_GOAL_PACKAGE_NOT_ACTIVATED");
        for(var b:history)for(var a:b.actions())if(a.tool().equals("transact_shared_state")){
            var input=dev.mineagent.runtime.core.shared.SharedStateToolRequest.parse(a.tool(),a.options().get("request"));var transaction=dev.mineagent.runtime.core.shared.SharedStateTransaction.parse(input.transaction());
            for(var write:transaction.writes()){boolean covered=false;for(var check:checks)if(check.kind().equals("shared_state")&&check.details().get("key").equals(write.key())&&dev.mineagent.runtime.core.shared.SharedStateToolRequest.parse("read_shared_state",check.details().get("request")).target().equals(input.target())){covered=true;break;}if(!covered)throw new IllegalStateException("SHARED_GOAL_COVERAGE");}
        }
        var deliveries=dev.mineagent.runtime.neoforge.ui.ServerUiRuntime.get(server).deliveries();deliveries.requireGoalCoverage(task,checks,history);
        var observed=new ArrayList<Map<String,Object>>();boolean matched=true;
        for(var c:checks){boolean ok;String actual;
            if(c.kind().equals("content_delivery")){var proof=deliveries.verifyGoal(task,c);ok=Boolean.TRUE.equals(proof.get("matched"));actual=json.writeValueAsString(proof);}
            else if(c.kind().equals("appearance")){var state=dev.mineagent.runtime.neoforge.network.MineAgentNetwork.readTaskAppearance(server,task);ok=c.matchesAppearance(state);actual=json.writeValueAsString(state);}
            else if(c.kind().equals("world_instance")){var id=UUID.fromString(c.id());ok=verifyInstance(task.ownerPlayerId(),id,c.count(),Integer.parseInt(c.details().getOrDefault("minimum_objects","0")));actual="live="+worldContent.active(id)+", verifiedBlocks="+worldContent.verifiedBlocks(id)+", verifiedObjects="+worldContent.verifiedObjects(id);}
            else if(c.kind().equals("world_rule")){
                var target=WorldRuleGoal.target(c);var p=ownedWorldPackage(task.ownerPlayerId(),target.packageId(),target.canonicalSha256());
                var i=worldContent.instance(target.instanceId()).orElse(null);
                ok=p.revision()==target.packageRevision()&&i!=null&&i.packageId().equals(p.packageId())&&verifyRule(task.ownerPlayerId(),target.instanceId(),UUID.fromString(c.details().get("definition_id")));
                actual="LIVE_RULE_IDENTITY_ONLY; paired shared_state checked independently";
            }
            else if(c.kind().equals("shared_state")){var value=MineAgentRuntimeServices.sharedStates(server).execute(task,UUID.randomUUID(),"read_shared_state",c.details().get("request"));var state=json.readTree(value.get("result"));ok=state.path("schemaVersion").asText().equals(c.details().get("schema_version"))&&(Boolean.parseBoolean(c.details().getOrDefault("exists","true"))?(state.path("values").has(c.details().get("key"))&&state.path("values").get(c.details().get("key")).equals(json.readTree(c.details().get("expected_value")))):!state.path("values").has(c.details().get("key")));actual="SHARED_STATE_REVALIDATED revision="+state.path("revision").asText();}
            else if(Set.of("schedule_definition","schedule_occurrence").contains(c.kind())){
                var runtime=MineAgentRuntimeServices.schedules(server);var context=runtime.context(task,UUID.randomUUID());var d=runtime.store().inspect(context,UUID.fromString(c.id()));
                if(c.kind().equals("schedule_definition")){ok=d.revision()==Long.parseLong(c.details().get("revision"))&&d.state().equals(c.details().get("state"));actual="SCHEDULE_CONFIGURATION_ONLY "+json.writeValueAsString(d);}
                else{var occurrence=runtime.store().occurrences(context,d.id()).stream().filter(o->o.index()==c.count()).findFirst().orElse(null);ok=occurrence!=null&&occurrence.state().equals(c.details().get("state"));actual="SCHEDULE_OCCURRENCE_ONLY "+json.writeValueAsString(occurrence==null||d.spec().script()==null&&d.spec().push()==null&&d.spec().delivery()==null?occurrence:runtime.occurrenceView(d,occurrence));}
            }
            else if(c.kind().equals("event_subscription")){var runtime=MineAgentRuntimeServices.events(server);var value=runtime.store().inspect(runtime.context(task,UUID.randomUUID()),UUID.fromString(c.id()));ok=value.revision()==Long.parseLong(c.details().get("revision"))&&value.state().equals(c.details().get("state"))&&System.currentTimeMillis()<value.expiresAt();actual="SUBSCRIPTION_CONFIGURATION_ONLY "+json.writeValueAsString(value);}
            else if(Set.of("audience_definition","audience_snapshot").contains(c.kind())){var value=audiences.verify(task,c);ok=Boolean.TRUE.equals(value.get("matched"));actual=json.writeValueAsString(value);}
            else if(c.kind().equals("native_api_observation")){
                var receipt=history.stream().flatMap(b->b.receipts().stream()).filter(r->r.operationId().toString().equals(c.id())&&nativeObservationTool(r.tool())&&r.verified()).findFirst().orElse(null);int count=-1;
                try{count=receipt==null?-1:Integer.parseInt(receipt.after().getOrDefault("count","-1"));}catch(NumberFormatException invalid){}
                ok=receipt!=null&&receipt.after().getOrDefault("snapshot","").equals(c.details().get("snapshot"))&&count>=c.count();actual="NATIVE_API_OBSERVATION operation="+c.id()+" snapshot="+c.details().get("snapshot")+" count="+count;
            }
            else if(c.kind().equals("native_knowledge")){
                var entry=dev.mineagent.runtime.neoforge.ui.ServerPackageRuntime.get(server).nativeKnowledge().get(task.ownerPlayerId(),task.agentId(),UUID.fromString(c.id()));ok=entry.state().equals(c.details().get("state"))&&entry.semanticHash().equals(c.details().get("semantic_hash"));if(ok&&entry.state().equals("CURRENT")){try{var latest=dev.mineagent.runtime.neoforge.compile.NativeCompilationEnvironment.latest();ok=entry.selection().snapshot().equals(latest.hash())&&entry.selection().environment().equals(dev.mineagent.runtime.neoforge.content.NativePackageCompatibility.observe().fingerprint());}catch(Exception unavailable){ok=false;}}actual="NATIVE_KNOWLEDGE state="+entry.state()+" semantic="+entry.semanticHash()+" snapshot="+entry.selection().snapshot();
            }
            else if(c.kind().equals("directory_observation")){
                var receipt=history.stream().flatMap(b->b.receipts().stream()).filter(r->r.operationId().toString().equals(c.id())&&r.tool().equals("query_objects")&&r.verified()).findFirst().orElse(null);
                var page=receipt==null?json.createObjectNode():json.readTree(receipt.after().get("result"));int size=page.path("items").isArray()?page.path("items").size():-1;
                ok=receipt!=null&&receipt.after().equals(directory.forModel(task,receipt.tool(),receipt.after()))&&size>=c.count()&&page.path("nextCursor").asText("missing").isEmpty();actual="DISCOVERY_OBSERVATION_ONLY operation="+c.id()+" count="+size;
            }
            else if(c.kind().equals("inventory")){var item=BuiltInRegistries.ITEM.getValue(Identifier.parse(c.id()));int n=item==null?-1:count(body,item);ok=n==c.count();actual=Integer.toString(n);}
            else if(c.kind().equals("food")){actual=Integer.toString(body.getFoodData().getFoodLevel());ok=body.getFoodData().getFoodLevel()>=c.count();}
            else if(c.kind().equals("health")){actual=Float.toString(body.getHealth());ok=body.getHealth()>=c.count();}
            else{var p=new BlockPos(c.x(),c.y(),c.z());if(!body.level().getChunkSource().hasChunk(p.getX()>>4,p.getZ()>>4))throw new IllegalStateException("TARGET_NOT_LOADED");
                if(c.kind().equals("block")){actual=BuiltInRegistries.BLOCK.getKey(body.level().getBlockState(p).getBlock()).toString();ok=actual.equals(c.id());}
                else{var delta=Vec3.atBottomCenterOf(p).subtract(body.position());actual=body.position().toString();ok=delta.horizontalDistanceSqr()<0.64&&Math.abs(delta.y)<1.5;}}
            observed.add(Map.of("check",c,"actual",actual,"matched",ok));matched&=ok;
        }
        MineAgentRuntimeServices.audit(server).record(task.agentId().toString(),"WORLD_GOAL_VERIFIED",task.taskId().toString(),json.writeValueAsString(Map.of("matched",matched,"checks",observed,"rounds",history.size(),"planningAttempts",planningAttempts(task))));
        if(!matched)throw new IllegalStateException("WORLD_GOAL_NOT_MATCHED");
        var live=tasks().get(task.taskId()).orElseThrow();if(!live.runnableStepIds().contains("execute")||!tasks().completeStep(live.taskId(),live.revision(),"execute").accepted())throw new IllegalStateException("WORLD_GOAL_TASK_COMMIT");
        notifyOwner(last,checks.stream().anyMatch(c->c.kind().equals("content_delivery"))?"声明的投递事实检查已通过；RECORD仅记录，DATA_PAINT仅数据接收后的绘制，均不证明人类已读或任意业务正确。":"所有计划动作及声明的原生目标检查已通过。");
    }
    private SharedEpochs sharedAccessEpochs(UUID owner){var p=MineAgentRuntimeServices.permissions(server);return new SharedEpochs(p.actionRevision(owner,dev.mineagent.runtime.api.permission.PermissionAction.ACCESS_SHARED_STATE),p.actionRevision(owner,dev.mineagent.runtime.api.permission.PermissionAction.SUBSCRIBE_EVENTS),p.actionRevision(owner,dev.mineagent.runtime.api.permission.PermissionAction.SCHEDULE_TASKS),p.actionRevision(owner,dev.mineagent.runtime.api.permission.PermissionAction.OFFER_CONTENT),p.actionRevision(owner,dev.mineagent.runtime.api.permission.PermissionAction.RECEIVE_UI_FEEDBACK),p.actionRevision(owner,dev.mineagent.runtime.api.permission.PermissionAction.MANAGE_SCOREBOARD),p.actionRevision(owner,dev.mineagent.runtime.api.permission.PermissionAction.RUN_CODE),p.actionRevision(owner,dev.mineagent.runtime.api.permission.PermissionAction.MANAGE_PACKAGES));}
    private static boolean sameSharedEpochs(SharedEpochs a,SharedEpochs b){return a!=null&&b!=null&&a.access()==b.access()&&a.events()==b.events()&&a.schedules()==b.schedules()&&a.delivery()==b.delivery()&&a.feedback()==b.feedback();}
    private boolean scoreAction(WorldActionSpec action){
        if(Set.of("inspect_score_events","subscribe_score_events").contains(action.tool()))return true;
        if(!Set.of("inspect_subscription","set_subscription_state").contains(action.tool()))return false;
        try{var request=dev.mineagent.runtime.core.events.EventToolRequest.parse(action.tool(),action.options().get("request"));if(action.tool().equals("set_subscription_state")&&!request.state().equals("ACTIVE"))return false;var sub=MineAgentRuntimeServices.events(server).store().subscriptionForRuntime(request.id());return sub!=null&&sub.request().score()!=null;}catch(Exception invalid){return true;}
    }
    private boolean scriptAction(WorldActionSpec action){
        if(dev.mineagent.runtime.core.compile.NativeApiToolRequest.TOOLS.contains(action.tool()))return true;
        if(action.tool().equals("inspect_schedule_handlers")||action.tool().equals("inspect_schedule_push_targets"))return true;
        if(dev.mineagent.runtime.core.scheduling.ScheduleToolRequest.TOOLS.contains(action.tool())){try{var request=dev.mineagent.runtime.core.scheduling.ScheduleToolRequest.parse(action.tool(),action.options().get("request"));if(request.spec()!=null)return request.spec().script()!=null||request.spec().push()!=null;if(action.tool().equals("inspect_clock")||action.tool().equals("set_schedule_state")&&!request.state().equals("ACTIVE"))return false;var d=MineAgentRuntimeServices.schedules(server).store().definitionForRuntime(request.id());return d!=null&&(d.spec().script()!=null||d.spec().push()!=null);}catch(Exception invalid){return true;}}
        if(action.tool().equals("inspect_event_handlers")||action.tool().equals("inspect_state_push_targets"))return true;if(!dev.mineagent.runtime.core.events.EventToolRequest.TOOLS.contains(action.tool()))return false;
        try{var request=dev.mineagent.runtime.core.events.EventToolRequest.parse(action.tool(),action.options().get("request"));if(request.request()!=null)return request.request().script()!=null||request.request().push()!=null;if(action.tool().equals("set_subscription_state")&&request.state().equals("ACTIVE")){var sub=MineAgentRuntimeServices.events(server).store().subscriptionForRuntime(request.id());return sub!=null&&(sub.request().script()!=null||sub.request().push()!=null);}return false;}catch(Exception invalid){return true;}
    }
    private boolean deliveryScheduleAction(WorldActionSpec action){try{if(!dev.mineagent.runtime.core.scheduling.ScheduleToolRequest.TOOLS.contains(action.tool()))return false;var r=dev.mineagent.runtime.core.scheduling.ScheduleToolRequest.parse(action.tool(),action.options().get("request"));if(r.spec()!=null)return r.spec().delivery()!=null;if(!Set.of("inspect_schedule","set_schedule_state").contains(action.tool())||action.tool().equals("set_schedule_state")&&!r.state().equals("ACTIVE"))return false;var d=MineAgentRuntimeServices.schedules(server).store().definitionForRuntime(r.id());return d!=null&&d.spec().delivery()!=null;}catch(Exception invalid){return true;}}
    private boolean current(ManagedTask task,WorldActionJournal.Batch b){
        if(b.actions().stream().anyMatch(this::deliveryScheduleAction)&&!sameSharedEpochs(sharedEpochs.get(b.batchId()),sharedAccessEpochs(b.owner())))return false;
        if(b.actions().stream().anyMatch(a->dev.mineagent.runtime.core.scheduling.ScheduleToolRequest.TOOLS.contains(a.tool()))&&(sharedEpochs.get(b.batchId())==null||sharedEpochs.get(b.batchId()).schedules()!=sharedAccessEpochs(b.owner()).schedules()))return false;
        if(b.actions().stream().anyMatch(this::scriptAction)){var captured=sharedEpochs.get(b.batchId());var live=sharedAccessEpochs(b.owner());if(captured==null||captured.runCode()!=live.runCode()||captured.packages()!=live.packages())return false;}
        if(b.actions().stream().anyMatch(this::scoreAction)&&(sharedEpochs.get(b.batchId())==null||sharedEpochs.get(b.batchId()).scores()!=sharedAccessEpochs(b.owner()).scores()))return false;
        if(b.actions().stream().anyMatch(a->dev.mineagent.runtime.core.events.EventToolRequest.TOOLS.contains(a.tool()))&&(sharedEpochs.get(b.batchId())==null||sharedEpochs.get(b.batchId()).events()!=sharedAccessEpochs(b.owner()).events()))return false;
        if(b.actions().stream().anyMatch(a->dev.mineagent.runtime.core.shared.SharedStateToolRequest.TOOLS.contains(a.tool())||a.tool().equals("subscribe_shared_state")||a.tool().equals("subscribe_ui_feedback")||dev.mineagent.runtime.core.feedback.FeedbackToolRequest.TOOLS.contains(a.tool())||dev.mineagent.runtime.core.delivery.DeliveryToolRequest.TOOLS.contains(a.tool()))&&!sameSharedEpochs(sharedEpochs.get(b.batchId()),sharedAccessEpochs(b.owner())))return false;
        if(task==null||task.status()!=TaskStatus.RUNNING||task.intentRevision()!=b.intent()||!task.worldId().equals(b.worldId())||!task.agentId().equals(b.agentId())||!task.ownerPlayerId().equals(b.owner()))return false;
        var definition=MineAgentRuntimeServices.bodies(server).definitions().stream().filter(d->d.agentId().equals(b.agentId())).findFirst().orElse(null);var viewer=server.getPlayerList().getPlayer(b.owner());
        boolean op=viewer!=null&&viewer.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
        return MineAgentRuntimeServices.permissions(server).canMutateAgent(definition,b.owner(),op)&&MineAgentRuntimeServices.events(server).permitTask(task)&&MineAgentRuntimeServices.schedules(server).permitTask(task);
    }
    private void completePlan(WorldActionJournal.Batch b)throws Exception{var task=tasks().get(b.taskId()).orElseThrow();if(!current(task,b))throw new IllegalStateException("STALE_TASK");for(String step:List.of("plan","replan"))if(task.runnableStepIds().contains(step)){if(!tasks().completeStep(task.taskId(),task.revision(),step).accepted())throw new IllegalStateException("PLAN_COMMIT_FAILED");break;}}
    private void pause(ManagedTask task,WorldActionJournal.Batch b)throws Exception{if(task!=null&&task.intentRevision()==b.intent()&&task.status()==TaskStatus.RUNNING){tasks().transition(task.taskId(),task.revision(),true,TaskStatus.PAUSED);notifyOwner(b,"世界动作未完成："+journal.get(b.batchId()).error()+"。已停止本批动作，不自动重放；请明确重规划。");}}
    private void notifyOwner(WorldActionJournal.Batch b,String message){var owner=server.getPlayerList().getPlayer(b.owner());if(owner!=null)owner.sendSystemMessage(net.minecraft.network.chat.Component.literal("[MineAgent] "+message+" task="+b.taskId()));}
    private TaskManager tasks(){return MineAgentRuntimeServices.tasks(server);}
    private void release(UUID id){admittedBodies.remove(id);var c=controls.remove(id);if(c!=null){c.stopOwned();c.body.releaseTaskControl(id);}}
    private dev.mineagent.runtime.core.compile.NativeCoderContext generationNativeContext(WorldActionJournal.Batch batch,WorldActionSpec action)throws Exception{
        String observations=action.options().get("native_observations"),knowledge=action.options().get("native_knowledge");if(observations==null&&knowledge==null)return null;var observed=observations==null?null:dev.mineagent.runtime.core.compile.NativeObservationSelection.parseCanonical(observations);var learned=knowledge==null?null:dev.mineagent.runtime.core.compile.NativeObservationSelection.parseCanonical(knowledge);if((observed==null?0:observed.operations().size())+(learned==null?0:learned.operations().size())>16)throw new IllegalArgumentException("NATIVE_GENERATION_SELECTION_LIMIT");var latest=dev.mineagent.runtime.neoforge.compile.NativeCompilationEnvironment.latest();String environment=dev.mineagent.runtime.neoforge.content.NativePackageCompatibility.observe().fingerprint();var contexts=new ArrayList<dev.mineagent.runtime.core.compile.NativeCoderContext>();
        if(observed!=null)contexts.add(dev.mineagent.runtime.core.compile.NativeGenerationContext.resolve(journal.history(batch.taskId(),batch.intent()),observed,latest.snapshot(),latest.hash(),environment,dev.mineagent.runtime.neoforge.compile.NativeLiveClassAccess.state().processEpoch()));
        if(learned!=null)contexts.addAll(dev.mineagent.runtime.neoforge.ui.ServerPackageRuntime.get(server).nativeKnowledge().current(batch.owner(),batch.agentId(),learned.operations(),latest.hash(),environment));
        return dev.mineagent.runtime.core.compile.NativeCoderContext.merge(contexts);
    }
    private Map<String,String> appearanceEvidence(Map<String,Object> state)throws Exception{
        var result=new LinkedHashMap<String,String>();result.put("executionMode","NATIVE_AGENT_TASK_APPEARANCE");result.put("state",json.writeValueAsString(state));
        for(String key:List.of("agentId","revision","model","texture","animation","diagnostic"))result.put(key,String.valueOf(state.get(key)));return Map.copyOf(result);
    }
    private static String safe(Exception e){String message=e.getMessage();return message!=null&&message.matches("[A-Z0-9_]{1,80}")?message:"WORLD_ACTION_FAILED";}
    private static boolean nativeObservationTool(String tool){return dev.mineagent.runtime.core.compile.NativeApiToolRequest.TOOLS.contains(tool)||dev.mineagent.runtime.core.compile.NativeLiveToolRequest.TOOLS.contains(tool);}
    private void requireThread(){if(closed||!server.isSameThread())throw new IllegalStateException("WORLD_ACTION_UNAVAILABLE");}
    private static net.minecraft.world.level.block.Block block(String name){dev.mineagent.runtime.neoforge.content.LegacyContentBoundary.requireGenerativePrimitive(name);var id=Identifier.tryParse(name);var value=id==null?null:BuiltInRegistries.BLOCK.getValue(id);if(value==null||value==net.minecraft.world.level.block.Blocks.AIR||value.asItem()==net.minecraft.world.item.Items.AIR)throw new IllegalArgumentException("BLOCK_UNAVAILABLE");return value;}
    private static net.minecraft.world.item.Item item(String name){var id=Identifier.parse(name);var item=BuiltInRegistries.ITEM.getValue(id);if(item==null||item==net.minecraft.world.item.Items.AIR)throw new IllegalArgumentException("ITEM_UNAVAILABLE");return item;}
    private static BlockPos position(MineAgentPlayer body,WorldActionSpec a){var p=new BlockPos(a.x(),a.y(),a.z());if(!body.level().isInWorldBounds(p)||!body.level().getWorldBorder().isWithinBounds(p)||!body.level().getChunkSource().hasChunk(p.getX()>>4,p.getZ()>>4))throw new IllegalStateException("TARGET_NOT_LOADED");return p;}
    private static int count(MineAgentPlayer body,net.minecraft.world.item.Item item){int total=0;for(int i=0;i<body.getInventory().getContainerSize();i++){var stack=body.getInventory().getItem(i);if(stack.is(item))total+=stack.getCount();}return total;}
    private static String state(MineAgentPlayer body,BlockPos pos){return net.minecraft.commands.arguments.blocks.BlockStateParser.serialize(body.level().getBlockState(pos));}
    private BlockSnapshot snapshot(MineAgentPlayer body,BlockPos pos){var entity=body.level().getBlockEntity(pos);return new BlockSnapshot(body.level().dimension().identifier().toString(),pos.getX(),pos.getY(),pos.getZ(),state(body,pos),entity==null?"":entity.saveWithFullMetadata(body.level().registryAccess()).toString());}
    @Override public void close()throws Exception{closed=true;for(var id:List.copyOf(controls.keySet()))release(id);admittedBodies.clear();audiences.close();directory.close();journal.close();}
    private final class Control {
        private final WorldActionJournal.Batch batch;private final MineAgentPlayer body;private final long directoryEpoch;private final net.minecraft.world.level.GameType mode;
        private WorldActionSpec action;private UUID operation;private long navigation=-1;private int startedTick;private BlockSnapshot beforeBlock;
        private long startedMillis;private dev.mineagent.runtime.neoforge.ui.ServerPackageRuntime generationRuntime;
        private java.util.concurrent.CompletableFuture<Map<String,String>> nativeApiFuture;
        private boolean useStarted,useAccepted,timedUse;private Map<String,Integer> useInventoryBefore;private int foodBefore,useStatBefore;private float healthBefore;private net.minecraft.world.item.Item useItem;
        Control(WorldActionJournal.Batch b,MineAgentPlayer body){batch=b;this.body=body;directoryEpoch=directory.epoch(b.owner());mode=body.gameMode.getGameModeForPlayer();}
        boolean valid(){return !closed&&(batch.actions().stream().noneMatch(a->(Set.of("query_objects","revalidate_objects").contains(a.tool())||dev.mineagent.runtime.core.directory.AudienceToolRequest.TOOLS.contains(a.tool())||dev.mineagent.runtime.core.events.EventToolRequest.TOOLS.contains(a.tool())||dev.mineagent.runtime.core.scheduling.ScheduleToolRequest.TOOLS.contains(a.tool())||dev.mineagent.runtime.core.shared.SharedStateToolRequest.TOOLS.contains(a.tool())||dev.mineagent.runtime.core.delivery.DeliveryToolRequest.TOOLS.contains(a.tool())))||directory.sameEpoch(batch.owner(),directoryEpoch))&&current(tasks().get(batch.taskId()).orElse(null),batch)&&MineAgentRuntimeServices.bodies(server).body(batch.agentId()).orElse(null)==body&&body.isAlive()&&body.gameMode.getGameModeForPlayer()==mode&&body.level().dimension().identifier().toString().equals(batch.dimension())&&body.containerMenu==body.inventoryMenu&&(navigation<0||body.movementController().commandRevision()==navigation)&&(operation==null||action==null||!action.tool().equals("break_block")||operation.equals(body.miningOperation()))&&(!useStarted||body.taskItemUseCurrent(operation));}
        void stopOwned(){if(navigation>=0&&body.movementController().target().isPresent())body.movementController().stopIfCurrent(navigation);if(operation!=null&&action!=null&&action.tool().equals("break_block"))body.abortMiningIfCurrent(operation);if(useStarted)body.abortItemUseIfCurrent(operation);
            if(nativeApiFuture!=null&&!nativeApiFuture.isDone())nativeApiFuture.cancel(false);nativeApiFuture=null;
            if(generationRuntime!=null&&operation!=null)try{if(generationRuntime.generation(batch.owner(),operation).filter(j->j.state().equals("GENERATING")).isPresent())generationRuntime.cancel(batch.owner(),operation);}catch(Exception e){MineAgentRuntimeMod.LOGGER.warn("World generation cancellation failed operation={} code={}",operation,e.getClass().getSimpleName());}
        }
        void start(WorldActionJournal.Batch b)throws Exception{
            action=b.actions().get(b.cursor());operation=null;navigation=-1;beforeBlock=null;useStarted=false;generationRuntime=null;nativeApiFuture=null;startedTick=server.getTickCount();startedMillis=System.currentTimeMillis();
            var before=new LinkedHashMap<String,String>();before.put("actorId",body.getUUID().toString());before.put("entityId",Integer.toString(body.getId()));before.put("dimension",batch.dimension());before.put("mode",mode.name());before.put("position",body.position().toString());
            if(action.hasPosition()){var pos=position(body,action);beforeBlock=snapshot(body,pos);before.put("blockSnapshot",json.writeValueAsString(beforeBlock));}
            if(Set.of("craft_recipe","use_item").contains(action.tool()))before.put("actorInventory",json.writeValueAsString(NativeCraftingAction.inventory(body)));
            if(action.tool().equals("place_block"))before.put("inventoryCount",Integer.toString(count(body,block(action.block()).asItem())));
            var started=journal.begin(batch.batchId(),before);operation=started.operationId();
            switch(action.tool()){
                case "inspect_content_contract","offer_content","query_deliveries","update_view","close_view","revoke_content"->{finish(true,"",dev.mineagent.runtime.neoforge.ui.ServerUiRuntime.get(server).deliveries().execute(tasks().get(batch.taskId()).orElseThrow(),operation,action.tool(),action.options().get("request")));}
                case "list_shared_namespaces","read_shared_state","transact_shared_state","watch_shared_state"->{finish(true,"",MineAgentRuntimeServices.sharedStates(server).execute(tasks().get(batch.taskId()).orElseThrow(),operation,action.tool(),action.options().get("request")));}
                case "create_schedule","inspect_schedule","set_schedule_state","inspect_clock","inspect_schedule_handlers","inspect_schedule_push_targets"->{finish(true,"",MineAgentRuntimeServices.schedules(server).execute(tasks().get(batch.taskId()).orElseThrow(),operation,action.tool(),action.options().get("request")));}
                case "inspect_feedback","reply_feedback"->{finish(true,"",dev.mineagent.runtime.neoforge.ui.ServerUiRuntime.get(server).deliveries().feedback().execute(tasks().get(batch.taskId()).orElseThrow(),operation,action.tool(),action.options().get("request")));}
                case "inspect_state_push_targets","inspect_event_handlers","inspect_score_events","subscribe_score_events","inspect_object_events","subscribe_object_events","subscribe_events","subscribe_shared_state","subscribe_ui_feedback","inspect_subscription","set_subscription_state"->{finish(true,"",MineAgentRuntimeServices.events(server).execute(tasks().get(batch.taskId()).orElseThrow(),operation,action.tool(),action.options().get("request")));}
                case "create_audience","resolve_audience","inspect_audience","revoke_audience"->{finish(true,"",audiences.execute(tasks().get(batch.taskId()).orElseThrow(),operation,action.tool(),action.options().get("request")));}
                case "query_objects"->{finish(true,"",directory.query(tasks().get(batch.taskId()).orElseThrow(),operation,action.options().get("query")));}
                case "revalidate_objects"->{finish(true,"",directory.revalidate(tasks().get(batch.taskId()).orElseThrow(),operation,action.options().get("request")));}
                case "inspect_native_environment","refresh_native_api","inspect_native_modules","inspect_native_classes","inspect_native_members","inspect_native_method_body","inspect_native_source"->{
                    var task=tasks().get(batch.taskId()).orElseThrow();var request=dev.mineagent.runtime.core.compile.NativeApiToolRequest.parse(action.tool(),action.options().get("request"));
                    if(action.tool().equals("inspect_native_method_body"))dev.mineagent.runtime.core.compile.NativeMethodObservation.require(journal.history(batch.taskId(),batch.intent()),request);
                    if(action.tool().equals("inspect_native_source"))dev.mineagent.runtime.core.compile.NativeSourceObservation.require(journal.history(batch.taskId(),batch.intent()),request);
                    long run=MineAgentRuntimeServices.config(server).permissionGeneration(batch.owner(),dev.mineagent.runtime.api.permission.PermissionAction.RUN_CODE),manage=MineAgentRuntimeServices.config(server).permissionGeneration(batch.owner(),dev.mineagent.runtime.api.permission.PermissionAction.MANAGE_PACKAGES);
                    java.util.function.BooleanSupplier permit=()->{try{return server.submit(()->{var live=tasks().get(batch.taskId()).orElse(null);return current(live,batch)&&run==MineAgentRuntimeServices.config(server).permissionGeneration(batch.owner(),dev.mineagent.runtime.api.permission.PermissionAction.RUN_CODE)&&manage==MineAgentRuntimeServices.config(server).permissionGeneration(batch.owner(),dev.mineagent.runtime.api.permission.PermissionAction.MANAGE_PACKAGES);}).get(3,java.util.concurrent.TimeUnit.SECONDS);}catch(Exception denied){return false;}};
                    nativeApiFuture=dev.mineagent.runtime.neoforge.compile.NativeApiCatalog.readTask(server,task,request.arguments(),permit,operation);
                }
                case "inspect_native_live_environment","inspect_native_loaded_classes","inspect_native_live_members","inspect_native_live_method_body","inspect_native_transformed_members","inspect_native_transformed_method_body"->{
                    var task=tasks().get(batch.taskId()).orElseThrow();var request=dev.mineagent.runtime.core.compile.NativeLiveToolRequest.parse(action.tool(),action.options().get("request"));if(Set.of("live_method_body","transformed_method_body").contains(request.kind()))dev.mineagent.runtime.core.compile.NativeLiveObservation.requireMethod(journal.history(batch.taskId(),batch.intent()),request);long run=MineAgentRuntimeServices.config(server).permissionGeneration(batch.owner(),dev.mineagent.runtime.api.permission.PermissionAction.RUN_CODE),manage=MineAgentRuntimeServices.config(server).permissionGeneration(batch.owner(),dev.mineagent.runtime.api.permission.PermissionAction.MANAGE_PACKAGES);java.util.function.BooleanSupplier permit=()->{try{return server.submit(()->{var live=tasks().get(batch.taskId()).orElse(null);return current(live,batch)&&run==MineAgentRuntimeServices.config(server).permissionGeneration(batch.owner(),dev.mineagent.runtime.api.permission.PermissionAction.RUN_CODE)&&manage==MineAgentRuntimeServices.config(server).permissionGeneration(batch.owner(),dev.mineagent.runtime.api.permission.PermissionAction.MANAGE_PACKAGES);}).get(3,java.util.concurrent.TimeUnit.SECONDS);}catch(Exception denied){return false;}};nativeApiFuture=dev.mineagent.runtime.neoforge.compile.NativeApiCatalog.liveTask(server,task,request,permit,operation);
                }
                case "remember_native_api","search_native_knowledge","inspect_native_knowledge","revalidate_native_knowledge","forget_native_knowledge"->{
                    var task=tasks().get(batch.taskId()).orElseThrow();var request=dev.mineagent.runtime.core.compile.NativeKnowledgeToolRequest.parse(action.tool(),action.options().get("request"));dev.mineagent.runtime.core.compile.NativeCoderContext remembered=null;
                    if(action.tool().equals("remember_native_api")){var latest=dev.mineagent.runtime.neoforge.compile.NativeCompilationEnvironment.latest();remembered=dev.mineagent.runtime.core.compile.NativeGenerationContext.resolve(journal.history(batch.taskId(),batch.intent()),new dev.mineagent.runtime.core.compile.NativeObservationSelection(List.of(request.observation())),latest.snapshot(),latest.hash(),dev.mineagent.runtime.neoforge.content.NativePackageCompatibility.observe().fingerprint());}
                    long run=MineAgentRuntimeServices.config(server).permissionGeneration(batch.owner(),dev.mineagent.runtime.api.permission.PermissionAction.RUN_CODE),manage=MineAgentRuntimeServices.config(server).permissionGeneration(batch.owner(),dev.mineagent.runtime.api.permission.PermissionAction.MANAGE_PACKAGES);java.util.function.BooleanSupplier permit=()->{try{return server.submit(()->{var live=tasks().get(batch.taskId()).orElse(null);return current(live,batch)&&run==MineAgentRuntimeServices.config(server).permissionGeneration(batch.owner(),dev.mineagent.runtime.api.permission.PermissionAction.RUN_CODE)&&manage==MineAgentRuntimeServices.config(server).permissionGeneration(batch.owner(),dev.mineagent.runtime.api.permission.PermissionAction.MANAGE_PACKAGES);}).get(3,java.util.concurrent.TimeUnit.SECONDS);}catch(Exception denied){return false;}};
                    nativeApiFuture=dev.mineagent.runtime.neoforge.compile.NativeApiCatalog.knowledgeTask(server,task,request,remembered,permit,operation);
                }
                case "inspect_appearance"->{var state=dev.mineagent.runtime.neoforge.network.MineAgentNetwork.readTaskAppearance(server,tasks().get(batch.taskId()).orElseThrow());finish(true,"",appearanceEvidence(state));}
                case "set_appearance"->{
                    var task=tasks().get(batch.taskId()).orElseThrow();var result=dev.mineagent.runtime.neoforge.network.MineAgentNetwork.applyTaskAppearance(server,task,action.options(),operation);
                    var state=dev.mineagent.runtime.neoforge.network.MineAgentNetwork.readTaskAppearance(server,task);var evidence=new LinkedHashMap<>(appearanceEvidence(state));evidence.put("nativeAccepted",Boolean.toString(result.accepted()));evidence.put("requestId",operation.toString());
                    finish(result.accepted(),result.errorCode(),evidence);
                }
                case "create_world_package"->{
                    generationRuntime=dev.mineagent.runtime.neoforge.ui.ServerPackageRuntime.get(server);var viewer=server.getPlayerList().getPlayer(batch.owner());
                    if(viewer==null||!generationRuntime.mayGenerate(batch.owner(),batch.agentId()))throw new SecurityException("WORLD_GENERATION_DENIED");
                    var nativeSelection=generationNativeContext(batch,action);var child=generationRuntime.submit(viewer,batch.agentId(),operation,action.options().get("prompt"),"WORLD_CONTENT",nativeSelection).job();
                    MineAgentRuntimeServices.audit(server).record(batch.agentId().toString(),"AGENT_WORLD_PACKAGE_SUBMITTED",batch.taskId().toString(),json.writeValueAsString(Map.of("operationId",operation,"childTaskId",child.taskId(),"packageId",child.packageId())));
                    notifyOwner(batch,"世界包生成已提交，尚未在世界执行。发布后可在内容生成面板检查并明确确认原生启用。");
                }
                case "inspect_world_content"->finish(true,"",worldPackageObservation(batch.owner(),UUID.fromString(action.options().get("package_id")),Integer.parseInt(action.options().get("offset")),null));
                case "await_world_activation"->{ownedWorldPackage(batch.owner(),UUID.fromString(action.options().get("package_id")),action.options().get("canonical_sha256"));notifyOwner(batch,"等待玩家在内容生成面板检查并确认原生启用；模型不能替你批准，当前任务尚未完成。");pollWorldActivation();}
                case "move_to"->{body.movementController().movePreciselyTo(Vec3.atBottomCenterOf(position(body,action)));navigation=body.movementController().commandRevision();}
                case "break_block"->{
                    if(!(body.gameMode instanceof dev.mineagent.runtime.neoforge.mixin.AgentMiningAccess))throw new IllegalStateException("NATIVE_MINING_BRIDGE_UNAVAILABLE");
                    var pos=position(body,action);if(body.level().getBlockState(pos).isAir()){finish(false,"TARGET_EMPTY",Map.of());return;}
                    if(!body.beginMining(pos,operation)){finish(false,"NATIVE_START_REJECTED",Map.of());return;}
                }
                case "place_block"->{
                    var block=block(action.block());int beforeCount=count(body,block.asItem());boolean accepted=MineAgentRuntimeServices.bodies(server).placeBlock(batch.agentId(),position(body,action),block);
                    int afterCount=count(body,block.asItem());boolean matched=accepted&&body.level().getBlockState(position(body,action)).is(block)&&(body.hasInfiniteMaterials()||afterCount==beforeCount-1);
                    finish(matched,matched?"":"PLACEMENT_NOT_VERIFIED",Map.of("blockAfter",state(body,position(body,action)),"inventoryBefore",Integer.toString(beforeCount),"inventoryAfter",Integer.toString(afterCount),"nativeAccepted",Boolean.toString(accepted)));
                }
                case "craft_recipe"->{var result=NativeCraftingAction.execute(body,action);finish(result.verified(),result.error(),result.evidence());}
                case "find_recipes"->{var query=action.options().get("query").toLowerCase(java.util.Locale.ROOT);var ids=body.level().recipeAccess().getRecipes().stream().filter(r->r.value() instanceof net.minecraft.world.item.crafting.CraftingRecipe).map(r->r.id().identifier().toString()).filter(id->id.contains(query)).sorted().limit(24).toList();finish(true,"",Map.of("mode","NATIVE_RECIPE_DISCOVERY","recipes",json.writeValueAsString(ids)));}
                case "use_item"->{
                    useItem=item(action.options().get("item"));var hand=net.minecraft.world.InteractionHand.valueOf(action.options().get("hand"));
                    if(body.isUsingItem())throw new IllegalStateException("ITEM_USE_BUSY");
                    if(hand==net.minecraft.world.InteractionHand.MAIN_HAND){int slot=-1;for(int i=0;i<36;i++)if(body.getInventory().getItem(i).is(useItem)){slot=i;break;}if(slot<0)throw new IllegalStateException("ITEM_NOT_IN_ACTOR_INVENTORY");if(slot<9)body.getInventory().setSelectedSlot(slot);else body.getInventory().pickSlot(slot);}
                    else if(!body.getOffhandItem().is(useItem))throw new IllegalStateException("ITEM_NOT_IN_ACTOR_HAND");
                    if(body.getItemInHand(hand).useOnRelease()||body.getItemInHand(hand).getUseDuration(body)>200)throw new IllegalStateException("USE_REQUIRES_RELEASE_TOOL");
                    useInventoryBefore=NativeCraftingAction.inventory(body);foodBefore=body.getFoodData().getFoodLevel();healthBefore=body.getHealth();useStatBefore=body.getStats().getValue(net.minecraft.stats.Stats.ITEM_USED.get(useItem));
                    useAccepted=body.beginTaskItemUse(operation,hand);useStarted=true;timedUse=body.isUsingItem();pollUse();
                }
                case "say"->{server.getPlayerList().broadcastSystemMessage(net.minecraft.network.chat.Component.literal("<"+body.getDisplayName().getString()+"> "+action.message()),false);finish(true,"",Map.of("mode","CHAT_TOOL_NOT_WORLD_MUTATION"));}
                default->throw new IllegalStateException("WORLD_ACTION_UNSUPPORTED");
            }
        }
        void poll(WorldActionJournal.Batch b)throws Exception{
            if(action==null||operation==null){journal.interrupt(b.batchId(),"NATIVE_OUTCOME_UNKNOWN");return;}
            if(nativeObservationTool(action.tool())||dev.mineagent.runtime.core.compile.NativeKnowledgeToolRequest.TOOLS.contains(action.tool())){
                if(nativeApiFuture==null)throw new IllegalStateException("NATIVE_API_OUTCOME_UNKNOWN");if(System.currentTimeMillis()-startedMillis>180000){finish(false,"NATIVE_API_TIMEOUT",Map.of());return;}if(!nativeApiFuture.isDone())return;
                try{var result=nativeApiFuture.join();String audit=dev.mineagent.runtime.core.compile.NativeKnowledgeToolRequest.TOOLS.contains(action.tool())?"AGENT_NATIVE_KNOWLEDGE":dev.mineagent.runtime.core.compile.NativeLiveToolRequest.TOOLS.contains(action.tool())?"AGENT_NATIVE_LIVE_INSPECTED":"AGENT_NATIVE_API_INSPECTED";MineAgentRuntimeServices.audit(server).record(batch.agentId().toString(),audit,batch.taskId().toString(),json.writeValueAsString(Map.of("tool",action.tool(),"operation",operation,"snapshot",result.getOrDefault("snapshot",""),"count",result.getOrDefault("count","0"))));finish(true,"",result);}
                catch(java.util.concurrent.CompletionException failed){String code=safe(failed.getCause() instanceof Exception e?e:new IllegalStateException(failed.getCause()));finish(false,code,Map.of());}return;
            }
            if(action.tool().equals("create_world_package")){
                if(System.currentTimeMillis()-startedMillis>240000){finish(false,"WORLD_GENERATION_TIMEOUT",Map.of());return;}
                var child=generationRuntime.generation(batch.owner(),operation).orElseThrow(()->new IllegalStateException("WORLD_GENERATION_MISSING"));
                if(child.state().equals("GENERATING"))return;
                if(!child.state().equals("PUBLISHED")){finish(false,"WORLD_GENERATION_FAILED",Map.of("childState",child.state(),"childTaskId",child.taskId().toString()));return;}
                var observed=new LinkedHashMap<>(worldPackageObservation(batch.owner(),child.packageId(),0,child.canonicalSha256()));observed.put("childTaskId",child.taskId().toString());observed.put("generationOperationId",operation.toString());observed.put("state","PUBLISHED");observed.put("executionMode","CODER_PUBLICATION_NOT_WORLD_COMPLETION");observed.put("nativeSnapshot",child.nativeSelection()==null?"":child.nativeSelection().snapshot());observed.put("nativeContextHash",child.nativeContext()==null?"":child.nativeContext().sha256());observed.put("nativeCompilationSnapshot",child.nativeContext()==null?"":child.nativeContext().compilationSnapshot());observed.put("nativeKnowledgeIds",action.options().getOrDefault("native_knowledge",""));finish(true,"",observed);return;
            }
            if(action.tool().equals("await_world_activation")){
                if(System.currentTimeMillis()-startedMillis>900000){finish(false,"WORLD_ACTIVATION_WAIT_TIMEOUT",Map.of());return;}
                if(server.getTickCount()%10==0)pollWorldActivation();return;
            }
            if(server.getTickCount()-startedTick>1200){finish(false,"WORLD_ACTION_TIMEOUT",Map.of());return;}
            if(action.tool().equals("move_to")){
                var controller=body.movementController();if(controller.outcome().equals("MOVING"))return;
                var delta=Vec3.atBottomCenterOf(position(body,action)).subtract(body.position());boolean reached=controller.outcome().equals("ARRIVED")&&delta.horizontalDistanceSqr()<0.04&&Math.abs(delta.y)<1.5;
                finish(reached,reached?"":"NAVIGATION_NOT_REACHED",Map.of("position",body.position().toString(),"outcome",controller.outcome(),"nativeSteps",Integer.toString(controller.executedSteps()),"elapsedTicks",Integer.toString(server.getTickCount()-startedTick)));
            }else if(action.tool().equals("break_block")){
                var result=body.miningReceipt(operation).orElse(null);if(result==null)return;
                boolean changed=result.removed()&&!result.before().equals(result.after())&&state(body,position(body,action)).equals(result.after());
                if(changed)MineAgentRuntimeServices.changeJournal(server).record(batch.owner(),"AGENT_TASK_BREAK",List.of(new BlockChange(beforeBlock,snapshot(body,position(body,action)))));
                finish(changed,changed?"":result.state(),Map.of("nativeResult",json.writeValueAsString(result),"elapsedTicks",Integer.toString(server.getTickCount()-startedTick),"blockAfter",state(body,position(body,action))));
            }else if(action.tool().equals("use_item"))pollUse();
        }
        private void pollWorldActivation()throws Exception{
            var id=UUID.fromString(action.options().get("package_id"));var p=ownedWorldPackage(batch.owner(),id,action.options().get("canonical_sha256"));var world=dev.mineagent.runtime.neoforge.content.WorldContentRuntime.get(server);
            if(action.options().containsKey("definition_id")){
                var definition=UUID.fromString(action.options().get("definition_id"));var d=p.definitions().get(definition);
                if(d==null||d.kind()!=dev.mineagent.runtime.api.packages.RuntimeDefinitionKind.RULE)throw new IllegalArgumentException("WORLD_RULE_DEFINITION_REQUIRED");
                for(var a:world.list(batch.owner()))if(a.packageId().equals(id)&&a.definitionId().equals(definition)&&verifyRule(batch.owner(),a.instanceId(),definition)){
                    finish(true,"",Map.of("packageId",id.toString(),"packageRevision",Long.toString(p.revision()),"canonicalSha256",p.canonicalSha256(),"activationId",a.operationId().toString(),"instanceId",a.instanceId().toString(),"definitionId",definition.toString(),"state","ACTIVE","executionMode","PLAYER_CONFIRMED_NATIVE_RULE","businessVerified","false"));return;
                }
                return;
            }
            int minimum=Integer.parseInt(action.options().get("minimum_blocks"));
            for(var a:world.list(batch.owner()))if(a.packageId().equals(id)&&a.canonicalSha256().equals(p.canonicalSha256())&&verifyInstance(batch.owner(),a.instanceId(),minimum,Integer.parseInt(action.options().getOrDefault("minimum_objects","0")))){
                finish(true,"",Map.of("packageId",id.toString(),"packageRevision",Long.toString(p.revision()),"canonicalSha256",p.canonicalSha256(),"activationId",a.operationId().toString(),"instanceId",a.instanceId().toString(),"verifiedBlocks",Integer.toString(world.verifiedBlocks(a.instanceId())),"verifiedObjects",Integer.toString(world.verifiedObjects(a.instanceId())),"state","ACTIVE","executionMode","PLAYER_CONFIRMED_NATIVE_RHINO"));return;
            }
        }
        private void pollUse()throws Exception{
            var after=NativeCraftingAction.inventory(body);int stat=body.getStats().getValue(net.minecraft.stats.Stats.ITEM_USED.get(useItem));
            boolean changed=!after.equals(useInventoryBefore)||body.getFoodData().getFoodLevel()!=foodBefore||body.getHealth()!=healthBefore||stat>useStatBefore;
            String state=ItemUseProof.status(useAccepted,timedUse,body.isUsingItem(),body.itemUseFinished(operation),changed);if(state.equals("PENDING"))return;
            finish(state.equals("VERIFIED"),state.equals("VERIFIED")?"":state,Map.of("item",action.options().get("item"),"nativeFinished",Boolean.toString(body.itemUseFinished(operation)),"timed",Boolean.toString(timedUse),"inventoryAfter",json.writeValueAsString(after),"foodBefore",Integer.toString(foodBefore),"foodAfter",Integer.toString(body.getFoodData().getFoodLevel()),"healthAfter",Float.toString(body.getHealth()),"itemUseStatDelta",Integer.toString(stat-useStatBefore),"elapsedTicks",Integer.toString(server.getTickCount()-startedTick)));
        }
        void finish(boolean success,String code,Map<String,String> after)throws Exception{stopOwned();journal.completeAction(batch.batchId(),success,code,after);action=null;operation=null;navigation=-1;useStarted=false;}
    }
}
