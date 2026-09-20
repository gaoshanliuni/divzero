package dev.mineagent.runtime.core.task;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mineagent.runtime.api.task.*;
import dev.mineagent.runtime.core.persistence.SqliteRuntimeRepository;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.*;

/** Durable generic-task → UI job links. No model calls here and no replay of uncertain dispatch after restart. */
public final class AgentUiTaskLinks implements AutoCloseable {
    private static final String NS="agent_ui_task_links";
    public record Request(String tool,String prompt,UUID packageId,long baseRevision){
        public Request{
            if(!Set.of("create_ui_package","propose_ui_patch").contains(tool)||prompt==null||prompt.isBlank()||prompt.length()>8192)throw new IllegalArgumentException("UI_TOOL_ARGUMENTS");prompt=prompt.strip();
            if(tool.equals("create_ui_package")?(packageId!=null||baseRevision!=0):(packageId==null||baseRevision<1||baseRevision==Long.MAX_VALUE))throw new IllegalArgumentException("UI_TOOL_ARGUMENTS");
        }
        public static Request parse(String tool,String arguments){
            try{var node=new ObjectMapper().readTree(arguments);if(node==null||!node.isObject()||arguments.length()>32768)throw new IllegalArgumentException();
                var keys=new HashSet<String>();node.fieldNames().forEachRemaining(keys::add);boolean create=tool.equals("create_ui_package");
                if(!keys.equals(create?Set.of("prompt"):Set.of("prompt","package_id","base_revision"))||!node.path("prompt").isTextual())throw new IllegalArgumentException();
                if(!create&&(!node.path("package_id").isTextual()||!node.path("base_revision").isIntegralNumber()||!node.path("base_revision").canConvertToLong()))throw new IllegalArgumentException();
                return new Request(tool,node.get("prompt").textValue(),create?null:UUID.fromString(node.get("package_id").textValue()),create?0:node.get("base_revision").longValue());
            }catch(Exception invalid){throw new IllegalArgumentException("UI_TOOL_ARGUMENTS");}
        }
    }
    public record Link(UUID operationId,UUID worldId,UUID parentTaskId,long parentIntent,UUID owner,UUID agent,Request request,
                       UUID childTaskId,UUID packageId,long packageRevision,String state,String error,long revision,long updatedAt){}
    private final UUID world;private final Clock clock;private final TaskManager tasks;private final SqliteRuntimeRepository repo;
    private final ObjectMapper json=new ObjectMapper();private final dev.mineagent.runtime.core.persistence.RetainedRuntimeJobs<Link> links;
    private final Set<UUID> dirtyParents=java.util.concurrent.ConcurrentHashMap.newKeySet();
    private AutoCloseable taskChanges=()->{};
    private AgentUiTaskLinks(Path path,UUID world,Clock clock,TaskManager tasks)throws Exception{
        this.world=world;this.clock=clock;this.tasks=tasks;repo=new SqliteRuntimeRepository(path);
        links=new dev.mineagent.runtime.core.persistence.RetainedRuntimeJobs<>(repo,world,NS,Link.class,l->!Set.of("FAILED","STALE","COMPLETED").contains(l.state()),Link::operationId,Link::worldId,Link::revision);
        try{
            repo.initializePackageJobRetention(world);links.loadActive();
            taskChanges=tasks.onChange(t->dirtyParents.add(t.taskId()));tasks.active().forEach(t->dirtyParents.add(t.taskId()));
            for(var link:List.copyOf(links.values()))if(Set.of("SUBMITTING","WAITING","WAITING_APPLY").contains(link.state())){pause(link);save(link,"INTERRUPTED","SERVER_RESTARTED",link.childTaskId(),link.packageId(),link.packageRevision());}
        }catch(Exception failure){try{taskChanges.close();}catch(Exception ignored){}repo.close();throw failure;}
    }
    public static AgentUiTaskLinks open(Path path,UUID world,Clock clock,TaskManager tasks)throws Exception{return new AgentUiTaskLinks(path,world,clock,tasks);}
    public synchronized Optional<Link> operation(UUID operation){return Optional.ofNullable(links.get(operation));}
    public synchronized Optional<Link> forTask(UUID parent,long intent){var link=links.get(UUID.nameUUIDFromBytes(("ui-tool|"+world+"|"+parent+"|"+intent).getBytes(StandardCharsets.UTF_8)));if(link!=null&&(!link.parentTaskId().equals(parent)||link.parentIntent()!=intent))throw new IllegalStateException("UI_LINK_CONTEXT");return Optional.ofNullable(link);}
    public synchronized List<Link> all(){return links.all();}
    public synchronized List<Link> reconcileCandidates(){
        var result=new LinkedHashMap<UUID,Link>();links.values().forEach(l->result.put(l.operationId(),l));
        for(var id:dirtyParents.stream().limit(256).toList()){
            var task=tasks.get(id).orElse(null);var link=task==null?null:forTask(id,task.intentRevision()).orElse(null);
            if(link!=null)result.put(link.operationId(),link);
            if(task==null||task.status()!=TaskStatus.RUNNING||link==null||!Set.of("FAILED","STALE").contains(link.state()))dirtyParents.remove(id);
        }return List.copyOf(result.values());
    }
    public synchronized dev.mineagent.runtime.core.persistence.RetainedRuntimeJobs.Page<Link> history(UUID owner,String state,String archive,int offset){return links.history(owner,state,archive,offset,8);}
    public synchronized boolean holdFailedResume(UUID operation)throws Exception{
        var link=require(operation);var parent=parent(link);
        if(Set.of("FAILED","STALE").contains(link.state())&&parent!=null&&parent.status()==TaskStatus.RUNNING){pause(link);return true;}return false;
    }
    public synchronized Link prepare(ManagedTask parent,Request request)throws Exception{
        var prior=forTask(parent.taskId(),parent.intentRevision());if(prior.isPresent()){if(!prior.get().request().equals(request))throw new IllegalArgumentException("UI_TOOL_INTENT_REUSED");return prior.get();}
        if(!parent.worldId().equals(world)||!TaskResultFence.current(parent,tasks.get(parent.taskId()).orElse(null))||Collections.disjoint(parent.runnableStepIds(),Set.of("plan","plan_ui","replan")))throw new IllegalStateException("STALE_PARENT_TASK");
        if(links.size()>=4096)throw new IllegalStateException("UI_TOOL_LEDGER_FULL");
        UUID operation=UUID.nameUUIDFromBytes(("ui-tool|"+world+"|"+parent.taskId()+"|"+parent.intentRevision()).getBytes(StandardCharsets.UTF_8));
        var link=new Link(operation,world,parent.taskId(),parent.intentRevision(),parent.ownerPlayerId(),parent.agentId(),request,null,request.packageId(),request.baseRevision()+1,"SUBMITTING","",1,clock.millis());
        if(!repo.compareAndSet(world,NS,operation.toString(),0,json.writeValueAsString(link),clock.millis()).accepted())throw new IllegalStateException("UI_TOOL_CAS");links.put(operation,link);return link;
    }
    public synchronized Link attached(UUID operation,UUID child,UUID pkg,long revision)throws Exception{
        var link=require(operation);if(!mayProduce(operation))throw new IllegalStateException("STALE_PARENT_TASK");
        if(link.childTaskId()!=null){if(!link.childTaskId().equals(child)||!link.packageId().equals(pkg)||link.packageRevision()!=revision)throw new IllegalStateException("UI_CHILD_REUSED");return link;}
        if(child==null||pkg==null||revision!=link.packageRevision()||link.request().packageId()!=null&&!pkg.equals(link.request().packageId()))throw new IllegalArgumentException("UI_CHILD_CONTEXT");
        link=save(link,"WAITING","",child,pkg,revision);completePlan(link);return link;
    }
    public synchronized boolean mayProduce(UUID operation){var link=links.get(operation);if(link==null)return true;var parent=parent(link);return parent!=null&&parent.status()==TaskStatus.RUNNING&&!Set.of("FAILED","STALE","COMPLETED").contains(link.state());}
    public synchronized Link observe(UUID operation,String childState,boolean authorized)throws Exception{
        var link=require(operation);if(Set.of("FAILED","STALE","COMPLETED").contains(link.state()))return link;
        var parent=parent(link);
        if(parent!=null&&parent.status()==TaskStatus.COMPLETED&&link.childTaskId()!=null&&authorized
                &&(link.request().tool().equals("create_ui_package")?childState.equals("PUBLISHED"):childState.equals("APPLIED")))return save(link,"COMPLETED","RECOVERED_PARENT_COMMIT",link.childTaskId(),link.packageId(),link.packageRevision());
        if(parent==null||parent.status()==TaskStatus.CANCELLED||parent.status()==TaskStatus.COMPLETED)return save(link,"STALE","PARENT_CHANGED",link.childTaskId(),link.packageId(),link.packageRevision());
        if(!authorized){pause(link);return save(link,"FAILED","PERMISSION_DENIED",link.childTaskId(),link.packageId(),link.packageRevision());}
        if(parent.status()!=TaskStatus.RUNNING)return link;
        boolean create=link.request().tool().equals("create_ui_package");
        if((create&&childState.equals("PUBLISHED"))||(!create&&childState.equals("APPLIED"))){
            if(link.childTaskId()==null)throw new IllegalStateException("UI_CHILD_NOT_ATTACHED");completePlan(link);unwait(link);
            var live=parent(link);var step=live.steps().stream().filter(s->s.stepId().equals("execute")).findFirst().orElseThrow();
            if(step.status()!=TaskNodeStatus.COMPLETED&&!tasks.completeStep(live.taskId(),live.revision(),"execute").accepted())throw new IllegalStateException("UI_PARENT_COMPLETION_FAILED");
            return save(link,"COMPLETED","",link.childTaskId(),link.packageId(),link.packageRevision());
        }
        if(!create&&childState.equals("READY")){
            completePlan(link);var live=parent(link);var step=live.steps().stream().filter(s->s.stepId().equals("execute")).findFirst().orElseThrow();
            if(step.status()==TaskNodeStatus.PENDING&&!tasks.decisionNodes(live.taskId(),live.revision(),Set.of("execute"),true,"网页候选已生成；等待可信界面明确应用，未修改当前包").accepted())throw new IllegalStateException("UI_PARENT_WAIT_FAILED");
            return link.state().equals("WAITING_APPLY")?link:save(link,"WAITING_APPLY","",link.childTaskId(),link.packageId(),link.packageRevision());
        }
        if(Set.of("GENERATING","PENDING","APPLYING").contains(childState))return link;
        pause(link);return save(link,"FAILED",childState.matches("[A-Z_]{1,64}")?"CHILD_"+childState:"UI_CHILD_FAILED",link.childTaskId(),link.packageId(),link.packageRevision());
    }
    private ManagedTask parent(Link link){var p=tasks.get(link.parentTaskId()).orElse(null);return p!=null&&p.worldId().equals(world)&&p.intentRevision()==link.parentIntent()&&p.ownerPlayerId().equals(link.owner())&&p.agentId().equals(link.agent())?p:null;}
    private void pause(Link link)throws Exception{var p=parent(link);if(p!=null&&p.status()==TaskStatus.RUNNING&&!tasks.transition(p.taskId(),p.revision(),true,TaskStatus.PAUSED).accepted())throw new IllegalStateException("UI_PARENT_PAUSE_FAILED");}
    private void completePlan(Link link)throws Exception{var p=parent(link);if(p==null||p.status()!=TaskStatus.RUNNING)throw new IllegalStateException("STALE_PARENT_TASK");var step=p.steps().stream().filter(s->Set.of("plan","plan_ui","replan").contains(s.stepId())&&s.status()==TaskNodeStatus.PENDING).findFirst();if(step.isPresent()&&!tasks.completeStep(p.taskId(),p.revision(),step.get().stepId()).accepted())throw new IllegalStateException("UI_PARENT_PLAN_FAILED");}
    private void unwait(Link link)throws Exception{var p=parent(link);if(p.steps().stream().anyMatch(s->s.stepId().equals("execute")&&s.status()==TaskNodeStatus.WAITING_FOR_PLAYER)&&!tasks.decisionNodes(p.taskId(),p.revision(),Set.of("execute"),false,"网页包已由权威服务确认").accepted())throw new IllegalStateException("UI_PARENT_RESUME_FAILED");}
    private Link require(UUID id){return Optional.ofNullable(links.get(id)).orElseThrow(()->new IllegalArgumentException("UI_LINK_MISSING"));}
    private Link save(Link old,String state,String error,UUID child,UUID pkg,long packageRevision)throws Exception{
        var next=new Link(old.operationId(),world,old.parentTaskId(),old.parentIntent(),old.owner(),old.agent(),old.request(),child,pkg,packageRevision,state,error,old.revision()+1,clock.millis());
        if(!repo.compareAndSet(world,NS,old.operationId().toString(),old.revision(),json.writeValueAsString(next),clock.millis()).accepted())throw new IllegalStateException("UI_LINK_CAS");links.put(next.operationId(),next);return next;
    }
    @Override public synchronized void close()throws Exception{try{taskChanges.close();}finally{repo.close();}}
}
