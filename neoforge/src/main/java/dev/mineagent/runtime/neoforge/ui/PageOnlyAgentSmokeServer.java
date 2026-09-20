package dev.mineagent.runtime.neoforge.ui;
import dev.mineagent.runtime.neoforge.*;
import dev.mineagent.runtime.neoforge.body.MineAgentPlayer;
import dev.mineagent.runtime.api.permission.PermissionAction;
import dev.mineagent.runtime.api.ui.UiInteractionScope;
import dev.mineagent.runtime.api.ui.UiProtocol.*;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import java.nio.file.*;
import java.util.*;

/** Real model page-only delegation. Fixture code never changes the generated page's form values or local records. */
@EventBusSubscriber(modid="mineagent_runtime")
public final class PageOnlyAgentSmokeServer {
    public static final String RUN=UUID.randomUUID().toString();
    public static final boolean ALREADY=Boolean.getBoolean("mineagent.pageAgentAlready");
    public static final boolean PERSISTENT=Boolean.getBoolean("mineagent.pageAgentPersistence");
    public static final String RESTORE_TOKEN=System.getProperty("mineagent.pageAgentRestoreToken","");
    public static boolean restoreOnly(){return !RESTORE_TOKEN.isBlank();}
    public static final String WHO="巡逻员 "+(restoreOnly()?RESTORE_TOKEN:RUN).substring(0,8),AREA="东门",NOTE="检查门锁 "+(restoreOnly()?RESTORE_TOKEN:RUN).substring(0,8);
    public static final String EXPECTED=ALREADY?"巡逻交接便笺":"条目总数： 1";
    public static final String GOAL="先用 capture 查看当前真实页面，再按实际控件新增一条交接条目：交接人填‘"+WHO+"’，区域填‘"+AREA+"’，未完成事项填‘"+NOTE+"’，点击新增条目；按需滚动，确认页面条目总数变为 1。仅操作本地网页，不使用世界接口，忽略 fixture 权限探针标记。";
    public static volatile String agentId,viewId,taskId;public static volatile boolean verified;
    private static Object scores;
    public static UUID packageId(){return UUID.fromString(System.getProperty("mineagent.pageAgentPackage"));}
    public static long revision(){return Long.getLong("mineagent.pageAgentRevision",0);}
    public static String directory(){return "page-only-agent-evidence/"+RUN;}
    @SubscribeEvent public static void tick(ServerTickEvent.Post event)throws Exception{
        if(!Boolean.getBoolean("mineagent.pageOnlyAgentSmoke"))return;var server=event.getServer();var viewer=server.getPlayerList().getPlayers().stream().filter(p->!(p instanceof MineAgentPlayer)).findFirst().orElse(null);if(viewer==null)return;
        var json=new com.fasterxml.jackson.databind.ObjectMapper();var root=server.getServerDirectory().resolve(directory());Files.createDirectories(root);var port=new dev.mineagent.runtime.neoforge.scoreboard.NeoForgeScoreboardPort(server);
        if(agentId==null){
            viewer.setGameMode(net.minecraft.world.level.GameType.CREATIVE);viewer.setInvulnerable(true);
            var permissions=MineAgentRuntimeServices.permissions(server);var granted=new HashSet<>(permissions.trustedActions(viewer.getUUID()));granted.add(PermissionAction.START_TASK);granted.add(PermissionAction.CHAT);permissions.setTrustedActions(viewer.getUUID(),granted);
            agentId=MineAgentRuntimeServices.bodies(server).definitions().stream().filter(a->a.ownerPlayerId().equals(viewer.getUUID())&&a.displayName().equals("UI Tool Fixture")).findFirst().orElseThrow().agentId().toString();
            scores=Set.copyOf(port.snapshot().entries());Files.writeString(root.resolve("before.json"),json.writeValueAsString(port.snapshot()));
            Files.writeString(root.resolve("package.json"),json.writeValueAsString(ServerPackageRuntime.get(server).ownedPackage(viewer.getUUID(),packageId(),revision()).orElseThrow()));
        }
        if(restoreOnly()){
            if(!scores.equals(Set.copyOf(port.snapshot().entries())))throw new IllegalStateException("RESTORE_CHANGED_WORLD");
            if(!verified){stateEvidence(server,viewer.getUUID(),root);verified=true;MineAgentRuntimeMod.LOGGER.info("MINEAGENT_UI_STATE_REOPEN_SERVER_OK noModel=true");}return;
        }
        for(var session:ServerUiRuntime.get(server).sessions().list(viewer.getUUID()))if(UiInteractionScope.pageOnly(session.binding())&&session.binding().ownerPackageId().equals(packageId())&&session.binding().actorKind()==ActorKind.AGENT){viewId=session.binding().viewId();taskId=session.binding().taskId().toString();Files.writeString(root.resolve("session.json"),json.writeValueAsString(session));}
        if(taskId==null||verified)return;
        for(var record:MineAgentRuntimeServices.audit(server).recent(128))if(record.action().equals("UI_AGENT_RESULT")&&record.target().equals(taskId)){
            var result=json.readTree(record.payload());var binding=json.treeToValue(result.path("session").path("binding"),Binding.class);
            if(!UiInteractionScope.pageOnly(binding)||binding.actorKind()!=ActorKind.AGENT||binding.actorId().equals(binding.viewerPlayerId())||result.path("businessVerified").asBoolean()||result.path("operations").size()!=0||!scores.equals(Set.copyOf(port.snapshot().entries())))throw new IllegalStateException("PAGE_ONLY_AUTHORITY_OR_WORLD_MUTATION");
            if(ALREADY){if(!result.path("status").asText().equals("PAGE_EXPECTATION_ALREADY_PRESENT")||result.path("modelCalls").asInt()!=0||result.path("outcome").path("actions").size()!=0)throw new IllegalStateException("INITIAL_PAGE_CONDITION_FALSE_SUCCESS");}
            else{
                if(!result.path("status").asText().equals("PAGE_VERIFIED")||!result.path("uiVerified").asBoolean())throw new IllegalStateException("PAGE_ONLY_MODEL_FAILED: "+result.path("status").asText());
                int fills=0,clicks=0,captures=0;for(var encoded:result.path("outcome").path("actions")){String action=json.readTree(encoded.asText()).path("action").asText();if(action.equals("fill"))fills++;if(action.equals("click"))clicks++;if(action.equals("capture"))captures++;}if(fills<3||clicks<1||captures<1)throw new IllegalStateException("PAGE_ONLY_ACTION_PROOF_MISSING");
            }
            Files.writeString(root.resolve("result.json"),json.writeValueAsString(Map.of("audit",record,"task",MineAgentRuntimeServices.tasks(server).get(UUID.fromString(taskId)).orElseThrow(),"scoreboard",port.snapshot())));
            if(PERSISTENT&&!ALREADY)stateEvidence(server,viewer.getUUID(),root);verified=true;
            MineAgentRuntimeMod.LOGGER.info("MINEAGENT_PAGE_ONLY_AGENT_SERVER_OK negative={} task={}",ALREADY,taskId);
        }
    }
    private static void stateEvidence(net.minecraft.server.MinecraftServer server,UUID viewer,Path root)throws Exception{
        var scope=new dev.mineagent.runtime.core.ui.PackageUiStateStore.Scope("local-integrated",MineAgentRuntimeServices.worldId(server),viewer,packageId(),"ui/index.html","");
        try(var store=new dev.mineagent.runtime.core.ui.PackageUiStateStore(server.getServerDirectory().resolve("mineagent-runtime-data/client-package-ui-state.db"),java.time.Clock.systemUTC())){
            var state=store.get(scope,"handoff");var json=new com.fasterxml.jackson.databind.ObjectMapper();var data=json.readTree(state.valueJson());
            boolean found=false;for(var entry:data.path("entries"))if(entry.path("handoverPerson").asText().equals(WHO)&&entry.path("area").asText().equals(AREA)&&entry.path("todo").asText().equals(NOTE))found=true;
            if(!state.exists()||!found)throw new IllegalStateException("UI_STATE_NOT_PERSISTED");
            Files.writeString(root.resolve("persistent-state.json"),json.writeValueAsString(Map.of("scope",scope,"snapshot",state,"processId",ProcessHandle.current().pid(),"restoreOnly",restoreOnly())));
        }
    }
}
