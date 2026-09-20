package dev.mineagent.runtime.neoforge.ui;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mineagent.runtime.api.permission.PermissionAction;
import dev.mineagent.runtime.api.ui.UiProtocol.*;
import dev.mineagent.runtime.neoforge.MineAgentRuntimeServices;
import dev.mineagent.runtime.neoforge.body.MineAgentPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import java.nio.file.*;
import java.util.*;
/** Explicit reopen of a known previous test view; no model invocation or template substitution. */
@EventBusSubscriber(modid="mineagent_runtime")
public final class TakeoverResumeSmokeServer {
    public static volatile boolean prepared,verified,clientReadOnlyChecked;private static long baseline;private static Map<String,String> baselineLayout;
    private static final String RUN=UUID.randomUUID().toString();
    public static boolean readOnly(){return Boolean.getBoolean("mineagent.takeoverResumeReadOnly");}
    public static String evidenceDirectory(){return readOnly()?"revision-recovery-evidence/"+RUN:"takeover-resume-evidence";}
    @SubscribeEvent public static void tick(ServerTickEvent.Post event)throws Exception{
        if(!Boolean.getBoolean("mineagent.takeoverResumeSmoke"))return;var server=event.getServer();
        var player=server.getPlayerList().getPlayers().stream().filter(p->!(p instanceof MineAgentPlayer)).findFirst().orElse(null);if(player==null)return;
        var scores=MineAgentRuntimeServices.scoreboards(server);UUID id=UUID.fromString(System.getProperty("mineagent.takeoverResumeView"));
        var view=scores.view(id).orElseThrow();var root=server.getServerDirectory().resolve(evidenceDirectory());Files.createDirectories(root);var json=new ObjectMapper();
        if(!prepared){
            var grants=new HashSet<>(MineAgentRuntimeServices.permissions(server).trustedActions(player.getUUID()));grants.add(PermissionAction.MANAGE_SCOREBOARD);MineAgentRuntimeServices.permissions(server).setTrustedActions(player.getUUID(),grants);
            if(!readOnly())view=scores.patchLayout(id,view.ownerPackageId(),view.revision(),Map.of("title","服务器重开基线"));baseline=view.revision();baselineLayout=view.layout();
            Files.writeString(root.resolve("baseline.json"),json.writeValueAsString(Map.of("view",view,"snapshot",scores.project(id))));prepared=true;
        }
        if(verified)return;
        for(var session:ServerUiRuntime.get(server).sessions().list(player.getUUID())){
            if(!session.binding().targetObjectId().equals(id.toString())||session.status()!=Status.RENDERED)continue;
            if(session.binding().capabilities().equals(Set.of("scoreview.read"))){
                if(view.revision()!=baseline||!view.layout().equals(baselineLayout))throw new IllegalStateException("RESUME_AUTO_SUBMITTED");
                Files.writeString(root.resolve("read-only-server.json"),json.writeValueAsString(Map.of("session",session,"view",view)));
                if(readOnly()&&clientReadOnlyChecked){
                    var ops=ServerUiRuntime.get(server).sessions().completedOperations(player.getUUID(),session.sessionId(),64);
                    if(ops.stream().anyMatch(o->o.request().action().equals("scoreview.patch")&&o.receipt().code()==Code.APPLIED))throw new IllegalStateException("READ_ONLY_RECOVERY_WROTE_DATA");
                    Files.writeString(root.resolve("recovered-server.json"),json.writeValueAsString(Map.of("session",session,"view",view,"operations",ops,"snapshot",scores.project(id))));verified=true;
                    dev.mineagent.runtime.neoforge.MineAgentRuntimeMod.LOGGER.info("MINEAGENT_REVISION_RECOVERY_SERVER_OK noAutoSubmit=true noModel=true");
                }
            }
            if(session.binding().capabilities().contains("scoreview.patch")&&view.layout().get("title").equals("人工草稿")){
                var ops=ServerUiRuntime.get(server).sessions().completedOperations(player.getUUID(),session.sessionId(),64).stream().filter(o->o.request().action().equals("scoreview.patch")).toList();
                if(view.revision()!=baseline+1||ops.size()!=1||!Files.exists(root.resolve("read-only-server.json")))throw new IllegalStateException("RESUME_WRITE_SEQUENCE");
                Files.writeString(root.resolve("resumed-server.json"),json.writeValueAsString(Map.of("session",session,"view",view,"operations",ops,"snapshot",scores.project(id))));verified=true;
                dev.mineagent.runtime.neoforge.MineAgentRuntimeMod.LOGGER.info("MINEAGENT_TAKEOVER_RESUME_SERVER_OK savedDraftFromDisk=true explicitSave=true");
            }
        }
    }
}
