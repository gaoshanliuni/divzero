package dev.mineagent.runtime.neoforge.ui;

import com.fasterxml.jackson.databind.*;
import dev.mineagent.runtime.api.task.TaskStatus;
import dev.mineagent.runtime.api.task.ManagedTask;
import dev.mineagent.runtime.api.ui.DeliveryProtocol;
import dev.mineagent.runtime.api.ui.UiProtocol.*;
import dev.mineagent.runtime.core.feedback.*;
import dev.mineagent.runtime.core.delivery.ContentDeliveryStore;
import dev.mineagent.runtime.core.ui.UiSessionService;
import dev.mineagent.runtime.neoforge.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Native-only identity/routing adapter. Plain feedback cannot approve a Decision or reserve model work. */
public final class ServerUiFeedback implements AutoCloseable {
    private final MinecraftServer server;private final UiSessionService sessions;private final ServerContentDeliveries deliveries;
    private final UiFeedbackStore store;private final UiFeedbackOutbox outbox;private final NativeFeedbackData data;private final UiFeedbackDataPump dataPump;
    private final ObjectMapper json=new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    private final Set<UUID> dirty=new LinkedHashSet<>();private boolean closed;private String diagnostic="READY";private final Map<UUID,Long> notified=new HashMap<>();
    ServerUiFeedback(MinecraftServer server,UiSessionService sessions,ServerContentDeliveries deliveries)throws Exception{
        this.server=server;this.sessions=sessions;this.deliveries=deliveries;
        data=new NativeFeedbackData(server,this::standing);
        try{store=UiFeedbackStore.open(server.getServerDirectory().resolve("mineagent-runtime-data/runtime.db"),MineAgentRuntimeServices.worldId(server),System::currentTimeMillis,new UiFeedbackStore.Port(){
            public void authorizeSubmission(UiFeedbackStore.Scope scope,UiFeedbackStore.Document document){if(!standing(scope)||!documentCurrent(scope,document))throw new SecurityException("FEEDBACK_CURRENT_VIEW_REQUIRED");}
            public boolean standing(UiFeedbackStore.Scope scope){return ServerUiFeedback.this.standing(scope);}
            public boolean documentCurrent(UiFeedbackStore.Scope scope,UiFeedbackStore.Document document){return ServerUiFeedback.this.documentCurrent(scope,document);}
            public boolean verifyCompletion(UiFeedbackStore.Item item,UiFeedbackStore.Completion proof){return verifyReply(item,proof)||data.verify(item,proof);}
            public void changed(UUID id){if(dirty.size()<4096)dirty.add(id);}
        });}catch(Exception failure){try{data.close();}catch(Exception close){failure.addSuppressed(close);}throw failure;}
        dataPump=new UiFeedbackDataPump(store,new UiFeedbackDataPump.Port(){
            public FeedbackTransactionPlan plan(UiFeedbackStore.Item item)throws Exception{return data.plan(item,()->dataCurrent(item.id(),item.consumerOperationId()));}
            public UiFeedbackStore.Completion apply(UiFeedbackStore.Item item)throws Exception{
                var receipt=data.apply(item,item.dataPlan(),()->dataCurrent(item.id(),item.consumerOperationId()));
                return new UiFeedbackStore.Completion(item.id(),"SHARED_TRANSACTION_"+receipt.status(),json.writeValueAsString(Map.of("receipt",receipt,"planSha256",item.dataPlan().sha256())));
            }
            public void failed(UUID id,Exception failure){diagnostic="FEEDBACK_DATA_PROCESSING_INTERRUPTED";MineAgentRuntimeMod.LOGGER.warn("Feedback deterministic consumer failed code={}",failure.getClass().getSimpleName());}
        });
        outbox=new UiFeedbackOutbox(store,item->{
            var s=item.scope();if(!standing(s))throw new SecurityException("FEEDBACK_EXPORT_AUTHORITY");
            String original=json.writeValueAsString(Map.of("source","UI_FEEDBACK","feedbackId",item.id(),"event",s.eventName(),"payload",json.readTree(item.payload())));
            ServerConversations.get(server).store().appendFeedback(s.authorId(),s.agentId(),s.conversationId(),item.id(),original);
            MineAgentRuntimeServices.events(server).ingestDurable(dev.mineagent.runtime.core.events.RuntimeEventStore.Event.feedback(item));
        });
    }
    private void thread(){if(closed||!server.isSameThread())throw new IllegalStateException("FEEDBACK_RUNTIME_THREAD");}
    private boolean standing(UiFeedbackStore.Scope scope){
        try{
            thread();var row=deliveries.feedbackRow(scope.authorId(),scope.deliveryId());var origin=row.origin();
            if(origin==null||!scope.worldId().equals(origin.world())||!scope.ownerId().equals(row.owner())||!scope.agentId().equals(row.agent())||!scope.originTaskId().equals(origin.task())||scope.originIntentRevision()!=origin.intent()
                    ||!scope.authorId().equals(row.recipient())||!scope.packageId().equals(row.asset().packageId())||scope.packageRevision()!=row.asset().revision()||!scope.canonicalSha256().equals(row.asset().canonical())||!scope.entry().equals(row.asset().entry())
                    ||!scope.policySha256().equals(row.asset().feedbackPolicySha256())||!scope.authority().equals(deliveries.feedbackToken(row)))return false;
            var task=MineAgentRuntimeServices.tasks(server).get(origin.task()).orElse(null);
            if(task==null||task.intentRevision()!=origin.intent()||!Set.of(TaskStatus.RUNNING,TaskStatus.COMPLETED).contains(task.status()))return false;
            var policy=deliveries.feedbackPolicy(row);
            if(!policy.event(scope.eventName()).equals(scope.policy())||!Set.of("RECORD_ONLY","AGENT_WAKE","DETERMINISTIC").contains(scope.policy().mode()))return false;
            if(scope.policy().mode().equals("AGENT_WAKE")&&!MineAgentRuntimeServices.events(server).feedbackConsumerCurrent(scope.consumer()))return false;
            if(scope.policy().mode().equals("DETERMINISTIC")&&(scope.dataBinding()==null||!scope.dataBinding().equals(row.asset().feedbackData())||data.token(row.owner(),row.agent(),row.asset())==null))return false;
            var conversation=ServerConversations.get(server).store().get(scope.authorId(),scope.agentId(),scope.conversationId());
            return conversation.state().equals("ACTIVE");
        }catch(Exception denied){return false;}
    }
    private boolean documentCurrent(UiFeedbackStore.Scope scope,UiFeedbackStore.Document document){
        try{
            var session=sessions.get(scope.authorId(),document.sessionId()).orElseThrow();var row=deliveries.feedbackRow(scope.authorId(),scope.deliveryId());var b=session.binding();
            return session.status()==Status.RENDERED&&DeliveryProtocol.feedbackEnabled(b)&&session.pageGeneration()==document.pageGeneration()&&session.controlEpoch()==document.controlEpoch()
                    &&b.viewerPlayerId().equals(scope.viewerId())&&b.actorId().equals(scope.actorId())&&b.actorKind().name().equals(scope.actorKind())
                    &&b.viewId().equals(document.viewId())&&DeliveryProtocol.deliveryId(b).equals(scope.deliveryId())&&DeliveryProtocol.leaseId(b).equals(document.leaseId())
                    &&Objects.equals(row.sessionId(),document.sessionId())&&Objects.equals(row.leaseId(),document.leaseId())&&row.documentId().equals(document.documentId())&&row.paintedAt()>0
                    &&Set.of("RENDERED","INTERACTED").contains(row.status())&&deliveries.feedbackVisible(session);
        }catch(Exception denied){return false;}
    }
    /** Runs before UiSessionService.begin so cached receipts cannot bypass current document/visibility checks. */
    public void authorizeRequest(ServerPlayer viewer,Request request)throws Exception{
        thread();if(!request.arguments().keySet().equals(Set.of("event","payload","documentId")))throw new IllegalArgumentException("FEEDBACK_ARGUMENTS");
        if(sessions.checkRead(viewer.getUUID(),request,"feedback.submit")!=Code.OK)throw new SecurityException("FEEDBACK_SESSION");
        var session=sessions.get(viewer.getUUID(),request.sessionId()).orElseThrow();var row=deliveries.feedbackRow(viewer.getUUID(),DeliveryProtocol.deliveryId(session.binding()));
        if(!DeliveryProtocol.feedbackEnabled(session.binding())||row.origin()==null||deliveries.feedbackToken(row)==null||!deliveries.feedbackVisible(session)
                ||!Set.of("RENDERED","INTERACTED").contains(row.status())||!row.documentId().equals(request.arguments().get("documentId"))||row.paintedAt()<1)throw new SecurityException("FEEDBACK_CURRENT_PAINT_REQUIRED");
        var originTask=MineAgentRuntimeServices.tasks(server).get(row.origin().task()).orElseThrow();
        if(originTask.intentRevision()!=row.origin().intent()||!Set.of(TaskStatus.RUNNING,TaskStatus.COMPLETED).contains(originTask.status())||!originTask.ownerPlayerId().equals(row.owner())||!originTask.agentId().equals(row.agent()))throw new SecurityException("FEEDBACK_ORIGIN_TASK_CHANGED");
        var policy=deliveries.feedbackPolicy(row);var event=policy.event(request.arguments().get("event"));if(!Set.of("RECORD_ONLY","AGENT_WAKE","DETERMINISTIC").contains(event.mode()))throw new IllegalStateException("FEEDBACK_CONSUMER_NOT_AVAILABLE");
        if(event.mode().equals("AGENT_WAKE")&&consumer(row,request.arguments().get("event"))==null)throw new SecurityException("FEEDBACK_CONSUMER_NOT_AVAILABLE");
        if(event.mode().equals("DETERMINISTIC")&&(row.asset().feedbackData()==null||data.token(row.owner(),row.agent(),row.asset())==null))throw new SecurityException("FEEDBACK_DATA_BINDING_REQUIRED");
        policy.validate(request.arguments().get("event"),request.arguments().get("payload"));
    }
    public Map<String,String> submit(ServerPlayer viewer,Request request)throws Exception{
        authorizeRequest(viewer,request);var session=sessions.get(viewer.getUUID(),request.sessionId()).orElseThrow();var b=session.binding();
        var row=deliveries.feedbackRow(viewer.getUUID(),DeliveryProtocol.deliveryId(b));var origin=row.origin();var policy=deliveries.feedbackPolicy(row);String name=request.arguments().get("event");
        UUID conversationOp=UUID.nameUUIDFromBytes((origin.world()+"|feedback-conversation|"+row.id()+"|"+viewer.getUUID()).getBytes(StandardCharsets.UTF_8));
        var conversation=ServerConversations.get(server).store().create(viewer.getUUID(),row.agent(),conversationOp,"页面反馈 · "+row.asset().title().substring(0,Math.min(100,row.asset().title().length())));
        var task=MineAgentRuntimeServices.tasks(server).get(origin.task()).orElseThrow();
        var scope=new UiFeedbackStore.Scope(origin.world(),row.id(),viewer.getUUID(),b.viewerPlayerId(),b.actorId(),b.actorKind().name(),row.owner(),row.agent(),origin.task(),origin.intent(),conversation.conversationId(),row.asset().packageId(),row.asset().revision(),row.asset().canonical(),row.asset().entry(),policy.sha256(),name,policy.event(name),deliveries.feedbackToken(row),origin.operation(),MineAgentRuntimeServices.events(server).causalChain(task));
        if(policy.event(name).mode().equals("AGENT_WAKE"))scope=scope.withConsumer(consumer(row,name));
        if(policy.event(name).mode().equals("DETERMINISTIC"))scope=scope.withDataBinding(row.asset().feedbackData());
        var document=new UiFeedbackStore.Document(DeliveryProtocol.leaseId(b),session.sessionId(),b.viewId(),request.arguments().get("documentId"),session.pageGeneration(),session.controlEpoch());
        var item=store.submit(scope,document,request.operationId(),request.arguments().get("payload"));deliveries.interacted(row,session,document.documentId());
        return Map.of("feedback",json.writeValueAsString(summary(item)),"businessVerified","false","modelDispatched","false");
    }
    String dataToken(UUID owner,UUID agent,ContentDeliveryStore.Asset asset){return data.token(owner,agent,asset);}
    private boolean dataCurrent(UUID id,UUID operation){try{var item=store.runtimeItem(id).orElseThrow();return item.state().equals("PROCESSING")&&operation.equals(item.consumerOperationId())&&standing(item.scope())&&(!item.scope().policy().lifecycle().equals("PAGE_BOUND")||documentCurrent(item.scope(),item.document()));}catch(Exception denied){return false;}}
    public Map<UUID,String> dataPhases(){thread();return dataPump.phases();}
    public Map<String,String> readState(ServerPlayer viewer,Request request)throws Exception{
        thread();if(!request.arguments().keySet().equals(Set.of("feedbackId")))throw new IllegalArgumentException("FEEDBACK_STATE_ARGUMENTS");var item=store.inspect(viewer.getUUID(),UUID.fromString(request.arguments().get("feedbackId")));var session=sessions.get(viewer.getUUID(),request.sessionId()).orElseThrow();
        if(!DeliveryProtocol.bound(session.binding())||!DeliveryProtocol.deliveryId(session.binding()).equals(item.scope().deliveryId())||!deliveries.feedbackVisible(session)||item.scope().dataBinding()==null||!standing(item.scope()))throw new SecurityException("FEEDBACK_STATE_SCOPE");return Map.of("state",data.state(item.scope()));
    }
    private UiFeedbackStore.ConsumerBinding consumer(ContentDeliveryStore.Delivery row,String event)throws Exception{return MineAgentRuntimeServices.events(server).feedbackConsumer(row.owner(),row.agent(),row.asset().packageId(),row.asset().revision(),row.asset().canonical(),row.asset().entry(),row.asset().feedbackPolicySha256(),event);}
    public String subscriptionToken(UUID owner,UUID agent,FeedbackSubscriptionFilter filter,String mode){try{thread();return deliveries.feedbackSubscriptionToken(owner,agent,filter,mode);}catch(Exception denied){return null;}}
    public long acceptanceHead()throws Exception{thread();return store.head();}
    public java.util.function.BooleanSupplier subscriptionWorkerPermit(UUID owner,UUID agent,FeedbackSubscriptionFilter filter,String mode){
        String expected=subscriptionToken(owner,agent,filter,mode);var valid=new java.util.concurrent.atomic.AtomicBoolean(expected!=null);
        return ()->{if(!valid.get())return false;try{String current=server.isSameThread()?subscriptionToken(owner,agent,filter,mode):server.submit(()->subscriptionToken(owner,agent,filter,mode)).get(3,java.util.concurrent.TimeUnit.SECONDS);if(!Objects.equals(expected,current))valid.set(false);return valid.get();}catch(Exception failure){valid.set(false);return false;}};
    }
    public boolean matches(dev.mineagent.runtime.core.events.RuntimeEventStore.Subscription subscription,dev.mineagent.runtime.core.events.RuntimeEventStore.Event event){try{
        var filter=subscription.request().feedback();if(filter==null||!filter.matches(subscription.creator(),event))return false;
        var item=store.forTarget(subscription.creator().scope().ownerId(),subscription.creator().scope().agentId(),event.id());if(!item.scope().equals(event.feedback().scope())||!item.document().equals(event.feedback().document())||item.sequence()!=event.feedback().sequence())return false;
        return !subscription.request().mode().equals("AGENT_WAKE")||item.scope().policy().mode().equals("AGENT_WAKE")&&item.scope().consumer()!=null&&item.scope().consumer().subscriptionId().equals(subscription.id())&&item.scope().consumer().revision()==subscription.revision();
    }catch(Exception denied){return false;}}
    public void bindTask(dev.mineagent.runtime.core.events.RuntimeEventStore.Trigger trigger)throws Exception{thread();var scope=trigger.event().feedback().scope();var item=store.forTarget(scope.ownerId(),scope.agentId(),trigger.event().id());if(item.scope().consumer()==null||!item.scope().consumer().subscriptionId().equals(trigger.subscriptionId())||item.scope().consumer().revision()!=trigger.subscriptionRevision())throw new SecurityException("FEEDBACK_CONSUMER_CHANGED");store.processing(item.id(),trigger.taskId());}
    public void taskDispatchFailed(dev.mineagent.runtime.core.events.RuntimeEventStore.Trigger trigger)throws Exception{store.interruptConsumer(trigger.event().id(),"FEEDBACK_TASK_DISPATCH_UNCERTAIN");}
    private boolean processingCurrent(UUID id,UUID task){try{var item=store.runtimeItem(id).orElse(null);return item!=null&&task.equals(item.consumerOperationId())&&Set.of("PROCESSING","COMPLETED").contains(item.state())&&standing(item.scope())&&(!item.scope().policy().lifecycle().equals("PAGE_BOUND")||documentCurrent(item.scope(),item.document()));}catch(Exception denied){return false;}}
    public java.util.function.BooleanSupplier workerPermit(UUID id,UUID task){var valid=new java.util.concurrent.atomic.AtomicBoolean(true);return ()->{if(!valid.get())return false;try{boolean allowed=server.isSameThread()?processingCurrent(id,task):server.submit(()->processingCurrent(id,task)).get(3,java.util.concurrent.TimeUnit.SECONDS);if(!allowed)valid.set(false);return allowed;}catch(Exception failure){valid.set(false);return false;}};}
    public Map<String,Object> eventForModel(UUID owner,UUID agent,dev.mineagent.runtime.core.events.RuntimeEventStore.Event event,boolean includePayload)throws Exception{
        thread();var item=store.forTarget(owner,agent,event.id());if(event.feedback()==null||!item.scope().equals(event.feedback().scope()))throw new SecurityException("FEEDBACK_EVENT_SCOPE");
        var value=new LinkedHashMap<>(summary(item));value.put("source","UI_FEEDBACK");value.put("dataNotInstructions",true);value.put("worldBusinessVerified",false);if(includePayload)value.put("payload",json.readTree(item.payload()));return value;
    }
    private UiFeedbackStore.Item taskItem(ManagedTask task)throws Exception{
        thread();var event=MineAgentRuntimeServices.events(server).store();var trigger=event.forTask(task.taskId()).orElseThrow();if(trigger.event().feedback()==null||!event.permitTask(task.taskId()))throw new SecurityException("FEEDBACK_TASK_BINDING");
        var item=store.forTarget(task.ownerPlayerId(),task.agentId(),trigger.event().id());if(!processingCurrent(item.id(),task.taskId()))throw new SecurityException("FEEDBACK_TASK_AUTHORITY");return item;
    }
    public Map<String,String> execute(ManagedTask task,UUID operation,String tool,String arguments)throws Exception{
        var request=FeedbackToolRequest.parse(tool,arguments);var item=taskItem(task);
        if(tool.equals("inspect_feedback"))return Map.of("executionMode","BOUND_FEEDBACK_ONLY","feedback",json.writeValueAsString(summary(item)),"payload",item.payload(),"worldBusinessVerified","false");
        if(!tool.equals("reply_feedback"))throw new IllegalArgumentException("FEEDBACK_ACTION");
        if(item.completion()!=null){if(item.reply()==null||!item.reply().text().equals(request.text())||!verifyReply(item,item.completion()))throw new IllegalStateException("FEEDBACK_REPLY_ALREADY_COMPLETED");return replyResult(item);}
        var intent=store.reserveReply(item.id(),task.taskId(),operation,request.text());var scope=item.scope();var message=ServerConversations.get(server).store().appendFeedbackReply(scope.authorId(),scope.agentId(),scope.conversationId(),intent.operationId(),intent.text());
        var completion=new UiFeedbackStore.Completion(intent.operationId(),"FEEDBACK_REPLY_SAVED",json.writeValueAsString(Map.of("messageId",message.messageId(),"sha256",intent.sha256(),"reply",intent.text())));
        item=store.complete(item.id(),completion);return replyResult(item);
    }
    private Map<String,String> replyResult(UiFeedbackStore.Item item)throws Exception{return Map.of("executionMode","FEEDBACK_REPLY_SAVED_NOT_WORLD_BUSINESS","feedback",json.writeValueAsString(summary(item)),"replyOperationId",item.completion().operationId().toString(),"replySha256",item.reply().sha256(),"result",item.completion().result(),"worldBusinessVerified","false");}
    private boolean verifyReply(UiFeedbackStore.Item item,UiFeedbackStore.Completion proof){try{
        if(!proof.code().equals("FEEDBACK_REPLY_SAVED")||item.reply()==null||!item.scope().policy().mode().equals("AGENT_WAKE")||!proof.operationId().equals(item.reply().operationId())||!Objects.equals(item.consumerOperationId(),item.reply().consumerId()))return false;
        var s=item.scope();var conversations=ServerConversations.get(server).store();var message=conversations.feedbackReply(s.authorId(),s.agentId(),s.conversationId(),proof.operationId());var data=json.readTree(proof.result());
        if(!message.role().equals("ASSISTANT")||!message.status().equals("COMPLETE")||!message.messageId().toString().equals(data.path("messageId").asText()))return false;
        var text=new StringBuilder();for(int offset=0;offset<message.textLength();offset+=4096)text.append(conversations.chunk(s.authorId(),s.agentId(),s.conversationId(),message.messageId(),message.revision(),offset,4096).text());
        return text.toString().equals(item.reply().text())&&text.toString().equals(data.path("reply").asText())&&item.reply().sha256().equals(data.path("sha256").asText())&&item.reply().sha256().equals(dev.mineagent.runtime.core.packages.RuntimePackageCanonicalizer.sha256(text.toString().getBytes(StandardCharsets.UTF_8)));
    }catch(Exception failed){return false;}}
    public void verifyFinish(ManagedTask task,String arguments)throws Exception{var request=FeedbackToolRequest.parse("finish_task",arguments);var item=taskItem(task);if(!item.state().equals("COMPLETED")||item.completion()==null||!request.replyOperation().equals(item.completion().operationId())||!verifyReply(item,item.completion()))throw new IllegalStateException("FEEDBACK_REPLY_NOT_VERIFIED");}
    public Map<String,String> forModel(ManagedTask task,String tool,Map<String,String> after){if(!FeedbackToolRequest.TOOLS.contains(tool))return after;try{taskItem(task);return after;}catch(Exception denied){return Map.of("executionMode","FEEDBACK_OBSERVATION_REDACTED");}}
    public Map<String,String> read(ServerPlayer viewer,Request request)throws Exception{
        thread();if(!request.arguments().keySet().equals(Set.of("feedbackId")))throw new IllegalArgumentException("FEEDBACK_READ_ARGUMENTS");var item=store.inspect(viewer.getUUID(),UUID.fromString(request.arguments().get("feedbackId")));
        var binding=sessions.get(viewer.getUUID(),request.sessionId()).orElseThrow().binding();
        if(DeliveryProtocol.bound(binding)){if(!DeliveryProtocol.feedbackEnabled(binding)||!DeliveryProtocol.deliveryId(binding).equals(item.scope().deliveryId())||!deliveries.feedbackVisible(sessions.get(viewer.getUUID(),request.sessionId()).orElseThrow()))throw new SecurityException("FEEDBACK_READ_SCOPE");}
        else if(!trustedHistory(viewer,binding))throw new SecurityException("FEEDBACK_HISTORY_SCOPE");
        return Map.of("feedback",json.writeValueAsString(summary(item)),"payload",item.payload(),"result",item.completion()==null?"{}":item.completion().result(),"recovery",json.writeValueAsString(data.recovery(item)));
    }
    private boolean trustedHistory(ServerPlayer viewer,Binding b){return b.ownerPackageId().equals(ServerUiRuntime.TRUSTED_SHELL_PACKAGE)&&b.viewId().equals("runtime-shell")&&b.entryPath().equals("trusted/shell")&&b.actorKind()==ActorKind.PLAYER&&b.actorId().equals(viewer.getUUID())&&b.taskId()==null&&!b.preview();}
    public Map<String,String> list(ServerPlayer viewer,Request request)throws Exception{
        thread();if(!request.arguments().keySet().equals(Set.of("deliveryId","offset"))||!trustedHistory(viewer,sessions.get(viewer.getUUID(),request.sessionId()).orElseThrow().binding()))throw new SecurityException("FEEDBACK_HISTORY_SCOPE");
        UUID delivery=UUID.fromString(request.arguments().get("deliveryId"));deliveries.feedbackRow(viewer.getUUID(),delivery);return historyPage(viewer.getUUID(),delivery,"ALL",Integer.parseInt(request.arguments().get("offset")));
    }
    public Map<String,String> history(ServerPlayer viewer,Request request)throws Exception{
        thread();if(!request.arguments().keySet().equals(Set.of("state","offset"))||!trustedHistory(viewer,sessions.get(viewer.getUUID(),request.sessionId()).orElseThrow().binding()))throw new SecurityException("FEEDBACK_HISTORY_SCOPE");
        return historyPage(viewer.getUUID(),null,request.arguments().get("state"),Integer.parseInt(request.arguments().get("offset")));
    }
    private Map<String,String> historyPage(UUID author,UUID delivery,String state,int offset)throws Exception{
        var values=new ArrayList<Object>();for(var item:store.authorHistory(author,delivery,state,offset,16)){var row=new LinkedHashMap<>(summary(item));row.put("archived",store.archived(item.id()));values.add(row);}long total=store.authorCount(author,delivery,state);
        return Map.of("feedback",json.writeValueAsString(values),"total",Long.toString(total),"offset",Integer.toString(offset),"nextOffset",Integer.toString(offset+values.size()),"more",Boolean.toString(total>offset+values.size()),"usage",json.writeValueAsString(store.authorUsage(author)),"diagnostic",diagnostic);
    }
    private Map<String,Object> summary(UiFeedbackStore.Item item){var value=new LinkedHashMap<String,Object>();value.put("feedbackId",item.id());value.put("deliveryId",item.scope().deliveryId());value.put("author",item.scope().authorId());value.put("agentId",item.scope().agentId());value.put("originTaskId",item.scope().originTaskId());value.put("conversationId",item.scope().conversationId());value.put("event",item.scope().eventName());value.put("state",item.state());value.put("revision",item.revision());value.put("exported",item.exported());value.put("createdAt",item.createdAt());value.put("error",item.error());value.put("businessVerified",item.state().equals("COMPLETED")&&item.completion()!=null&&item.completion().code().equals("SHARED_TRANSACTION_APPLIED"));value.put("transactionVerified",item.dataPlan()!=null&&item.completion()!=null&&Set.of("SHARED_TRANSACTION_APPLIED","SHARED_TRANSACTION_CONFLICT").contains(item.completion().code()));value.put("replyVerified",item.state().equals("COMPLETED")&&item.completion()!=null&&item.completion().code().equals("FEEDBACK_REPLY_SAVED"));return value;}
    public UiFeedbackStore store(){thread();return store;}
    public void visibilityChanged(){thread();try{store.reconcile();}catch(Exception e){diagnostic="FEEDBACK_VISIBILITY_RECONCILE_FAILED";}}
    public void tick(){if(closed)return;try{outbox.pump(4);dataPump.pump(4);
        for(var item:store.readyConsumers("AGENT_WAKE",64))for(var t:MineAgentRuntimeServices.events(server).store().forEvent(item.id()))if(Set.of("THROTTLED","BACKPRESSURE","BUDGET_EXHAUSTED","CYCLE_REJECTED").contains(t.state()))store.rejectDispatch(item.id(),"FEEDBACK_"+t.state());
        var pending=new LinkedHashMap<UUID,UiFeedbackStore.Item>();for(var item:store.liveRows())pending.put(item.id(),item);var live=new HashSet<>(pending.keySet());for(var id:List.copyOf(dirty))store.runtimeItem(id).ifPresent(item->pending.put(id,item));dirty.clear();notified.keySet().retainAll(live);
        for(var item:pending.values())if(!Objects.equals(notified.get(item.id()),item.revision())){var player=server.getPlayerList().getPlayer(item.scope().authorId());if(player!=null){net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player,new dev.mineagent.runtime.neoforge.network.UiPayloads.Event(UUID.randomUUID(),"feedbackChanged",json.writeValueAsString(Map.of("feedbackId",item.id(),"deliveryId",item.scope().deliveryId(),"revision",item.revision()))));if(live.contains(item.id()))notified.put(item.id(),item.revision());}}
        diagnostic="READY";
    }catch(Exception failure){diagnostic="FEEDBACK_EXPORT_PENDING";MineAgentRuntimeMod.LOGGER.warn("Feedback export pending code={}",failure.getClass().getSimpleName());}}
    @Override public void close()throws Exception{closed=true;try{store.close();}finally{data.close();}}
}
