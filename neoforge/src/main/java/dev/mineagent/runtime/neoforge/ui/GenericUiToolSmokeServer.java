package dev.mineagent.runtime.neoforge.ui;
import dev.mineagent.runtime.neoforge.*;
import dev.mineagent.runtime.neoforge.body.MineAgentPlayer;
import dev.mineagent.runtime.api.agent.AgentMode;
import dev.mineagent.runtime.api.permission.PermissionAction;
import dev.mineagent.runtime.api.task.TaskStatus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import java.util.*;
import java.nio.file.*;

/** Fixture setup/readback only. Parent task is created by the real trusted WebGUI button. */
@EventBusSubscriber(modid="mineagent_runtime")
public final class GenericUiToolSmokeServer {
    public static final boolean RESUMING=!System.getProperty("mineagent.genericUiResumeToken","").isBlank();
    public static final String TOKEN=RESUMING?UUID.fromString(System.getProperty("mineagent.genericUiResumeToken")).toString():UUID.randomUUID().toString();
    private static final String EVIDENCE_RUN=TOKEN+(RESUMING?"-reopen-"+UUID.randomUUID():"");
    public static final String PATCH_PACKAGE=System.getProperty("mineagent.genericUiPatchPackage","");
    public static final long PATCH_REVISION=Long.getLong("mineagent.genericUiPatchRevision",0);
    public static boolean patchMode(){return !PATCH_PACKAGE.isBlank();}
    public static final String MARKER="任务工具改版 "+TOKEN.substring(0,8);
    public static final String PROMPT=Boolean.getBoolean("mineagent.genericUiStateRepair")?"为包 "+PATCH_PACKAGE+" r"+PATCH_REVISION+" 提出持久化修复候选：按宿主 window.mineagentState 契约替代全部 localStorage，固定 key=handoff，保留 entries/filterArea 结构及增删筛选逻辑。写入等待 CAS 成功再渲染，失败/冲突保留输入并可重读，禁止降级假保存；保留半透明风格及顶部‘"+MARKER+"’。编号："+TOKEN:patchMode()?"为已有网页包 "+PATCH_PACKAGE+"（base revision "+PATCH_REVISION+"）提出改版候选：只修改 HTML/CSS，在顶部加入可见文字‘"+MARKER+"’，收紧卡片间距，不修改 JS/权限/世界数据。必须调用真实改版工具，不新建包、不声称已应用。验收编号："+TOKEN:
            "创建一个新的独立网页‘巡逻交接便笺’，包含交接人、区域、未完成事项输入，可新增/删除条目并按区域筛选。只创建新 UI 包，不修改既有包、比分或世界；通过实际网页工具生成，不用说话冒充完成。验收编号："+TOKEN;
    public static volatile String agentId,operationId,parentTaskId,packageId;public static volatile long revision;public static volatile boolean verified,proposalReady;
    public static volatile long resumeParentRevision;
    private static Object initialScores;
    public static String directory(){return "generic-ui-tool-evidence/"+EVIDENCE_RUN;}
    @SubscribeEvent public static void tick(ServerTickEvent.Post event)throws Exception{
        if(!Boolean.getBoolean("mineagent.genericUiToolSmoke"))return;var server=event.getServer();var viewer=server.getPlayerList().getPlayers().stream().filter(p->!(p instanceof MineAgentPlayer)).findFirst().orElse(null);if(viewer==null)return;
        var json=new com.fasterxml.jackson.databind.ObjectMapper();Path root=server.getServerDirectory().resolve(directory());Files.createDirectories(root);
        var port=new dev.mineagent.runtime.neoforge.scoreboard.NeoForgeScoreboardPort(server);
        if(agentId==null){
            viewer.setGameMode(net.minecraft.world.level.GameType.CREATIVE);viewer.setInvulnerable(true);
            var permissions=MineAgentRuntimeServices.permissions(server);var granted=new HashSet<>(permissions.trustedActions(viewer.getUUID()));granted.add(PermissionAction.START_TASK);granted.add(PermissionAction.CHAT);permissions.setTrustedActions(viewer.getUUID(),granted);
            var bodies=MineAgentRuntimeServices.bodies(server);var agent=bodies.definitions().stream().filter(a->a.ownerPlayerId().equals(viewer.getUUID())&&a.displayName().equals("UI Tool Fixture")).findFirst().orElseGet(()->bodies.create("UI Tool Fixture",viewer,AgentMode.CREATOR));agentId=agent.agentId().toString();
            if(RESUMING){
                var link=MineAgentRuntimeServices.agentUiLinks(server).operation(UUID.fromString(System.getProperty("mineagent.genericUiResumeOperation"))).orElseThrow();
                var child=ServerPackageRuntime.get(server).patchJob(viewer.getUUID(),link.operationId()).orElseThrow();var parent=MineAgentRuntimeServices.tasks(server).get(link.parentTaskId()).orElseThrow();
                if(!link.owner().equals(viewer.getUUID())||!link.agent().toString().equals(agentId)||!parent.title().contains(TOKEN)||!child.state().equals("READY")||parent.status()!=TaskStatus.PAUSED)throw new IllegalStateException("RESUME_REQUIRES_OWNED_READY_CHILD");
                parentTaskId=parent.taskId().toString();resumeParentRevision=parent.revision();
            }
            initialScores=Set.copyOf(port.snapshot().entries());Files.writeString(root.resolve("before.json"),json.writeValueAsString(port.snapshot()));
        }
        var parents=MineAgentRuntimeServices.tasks(server).all().stream().filter(t->t.agentId().toString().equals(agentId)&&t.title().contains(TOKEN)&&t.steps().stream().anyMatch(s->s.stepId().equals("plan_ui"))).toList();
        if(parents.isEmpty())return;if(parents.size()!=1)throw new IllegalStateException("DUPLICATE_UI_PARENT_TASK");var parent=parents.getFirst();parentTaskId=parent.taskId().toString();
        var link=MineAgentRuntimeServices.agentUiLinks(server).forTask(parent.taskId(),parent.intentRevision()).orElse(null);
        if(link==null){if(parent.status()==TaskStatus.COMPLETED||parent.status()==TaskStatus.PAUSED)throw new IllegalStateException("UI_TOOL_NOT_CALLED");return;}
        operationId=link.operationId().toString();Files.writeString(root.resolve("link.json"),json.writeValueAsString(Map.of("parent",parent,"link",link)));
        if(Set.of("FAILED","STALE").contains(link.state()))throw new IllegalStateException("UI_TOOL_FAILED: "+link.error());
        var runtime=ServerPackageRuntime.get(server);
        if(patchMode()){
            var child=runtime.patchJob(viewer.getUUID(),link.operationId()).orElse(null);if(child==null)return;packageId=child.base().packageId().toString();revision=child.base().revision()+1;
            if(child.state().equals("READY")&&link.state().equals("WAITING_APPLY")){
                var head=runtime.ownedPackage(viewer.getUUID(),child.base().packageId(),child.base().revision()).orElseThrow();
                if(parent.status()==TaskStatus.COMPLETED||parent.steps().stream().noneMatch(s->s.stepId().equals("execute")&&s.status()==dev.mineagent.runtime.api.task.TaskNodeStatus.WAITING_FOR_PLAYER))throw new IllegalStateException("PROPOSAL_COMPLETED_PARENT_EARLY");
                Files.writeString(root.resolve("waiting.json"),json.writeValueAsString(Map.of("parent",parent,"link",link,"child",child,"head",head)));proposalReady=true;
            }
            if(child.state().equals("APPLIED")&&link.state().equals("COMPLETED")&&!verified){
                var head=runtime.ownedPackage(viewer.getUUID(),child.base().packageId(),child.headRevision()).orElseThrow();
                if(!proposalReady||parent.status()!=TaskStatus.COMPLETED||!initialScores.equals(Set.copyOf(port.snapshot().entries())))throw new IllegalStateException("PROPOSAL_APPLY_INVARIANT");
                Files.writeString(root.resolve("result.json"),json.writeValueAsString(Map.of("parent",parent,"link",link,"child",child,"package",head,"scoreboard",port.snapshot(),"audit",MineAgentRuntimeServices.audit(server).recent(64).stream().filter(a->a.target().equals(parent.taskId().toString())).toList())));
                var content=new dev.mineagent.runtime.core.content.ContentAddressedStore(server.getServerDirectory().resolve("mineagent-runtime-data/content"));Files.write(root.resolve("model-output.json"),content.read(child.rawOutputSha256()));
                for(var resource:head.resources().values()){var file=root.resolve("package").resolve(resource.path()).normalize();if(!file.startsWith(root.normalize()))throw new IllegalStateException("EVIDENCE_PATH");Files.createDirectories(file.getParent());Files.write(file,content.read(resource.sha256()));}
                verified=true;MineAgentRuntimeMod.LOGGER.info("MINEAGENT_GENERIC_UI_PATCH_SERVER_OK waitedForApply=true parent={}",parent.taskId());
            }
            return;
        }
        var child=runtime.generation(viewer.getUUID(),link.operationId()).orElse(null);if(child==null)return;packageId=child.packageId().toString();revision=child.packageRevision();
        if(!child.state().equals("PUBLISHED")){if(parent.status()==TaskStatus.COMPLETED)throw new IllegalStateException("PARENT_COMPLETED_BEFORE_PUBLICATION");return;}
        if(!link.state().equals("COMPLETED"))return;
        var pkg=runtime.ownedPackage(viewer.getUUID(),child.packageId(),child.packageRevision()).orElseThrow();
        if(parent.status()!=TaskStatus.COMPLETED||pkg.enabled()||!initialScores.equals(Set.copyOf(port.snapshot().entries())))throw new IllegalStateException("UI_TOOL_RESULT_INVARIANT");
        if(!verified){
            Files.writeString(root.resolve("result.json"),json.writeValueAsString(Map.of("parent",parent,"link",link,"child",child,"package",pkg,"scoreboard",port.snapshot(),"audit",MineAgentRuntimeServices.audit(server).recent(64).stream().filter(a->a.target().equals(parent.taskId().toString())).toList())));
            var content=new dev.mineagent.runtime.core.content.ContentAddressedStore(server.getServerDirectory().resolve("mineagent-runtime-data/content"));Files.write(root.resolve("model-output.json"),content.read(child.rawOutputSha256()));
            for(var resource:pkg.resources().values()){var file=root.resolve("package").resolve(resource.path()).normalize();if(!file.startsWith(root.normalize()))throw new IllegalStateException("EVIDENCE_PATH");Files.createDirectories(file.getParent());Files.write(file,content.read(resource.sha256()));}
            verified=true;MineAgentRuntimeMod.LOGGER.info("MINEAGENT_GENERIC_UI_TOOL_SERVER_OK parent={} child={} package={}",parent.taskId(),child.taskId(),pkg.packageId());
        }
    }
}
