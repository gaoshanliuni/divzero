package dev.mineagent.runtime.neoforge.ui;
import dev.mineagent.runtime.neoforge.*;
import dev.mineagent.runtime.neoforge.body.MineAgentPlayer;
import dev.mineagent.runtime.neoforge.scoreboard.NeoForgeScoreboardPort;
import dev.mineagent.runtime.api.permission.PermissionAction;
import dev.mineagent.runtime.api.scoreboard.NumberFormatSpec;
import dev.mineagent.runtime.core.scoreboard.ScoreViewKind;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import java.util.*;
import java.nio.file.*;
/** Fixture supplies only original scores and an existing generated package. Production GUI performs all view edits. */
@EventBusSubscriber(modid="mineagent_runtime")
public final class WorldBoardSmokeServer {
    public static final String RUN=UUID.randomUUID().toString();
    public static UUID packageId(){return UUID.fromString(System.getProperty("mineagent.worldBoardPackage"));}
    public static boolean restore(){return !System.getProperty("mineagent.worldBoardResume","").isBlank();}
    public static String directory(){return "world-board-evidence/"+RUN;}
    public static volatile String sourceId,viewId;public static volatile boolean externalRequested,externalChanged,closedBrowserRequested,closedBrowserChanged,finishRequested,verified;
    private static boolean setup;private static String objective;
    @SubscribeEvent public static void tick(ServerTickEvent.Post event)throws Exception{
        if(!Boolean.getBoolean("mineagent.worldBoardSmoke"))return;
        var server=event.getServer();var player=server.getPlayerList().getPlayers().stream().filter(p->!(p instanceof MineAgentPlayer)).findFirst().orElse(null);if(player==null)return;
        var root=server.getServerDirectory().resolve(directory());Files.createDirectories(root);var json=new com.fasterxml.jackson.databind.ObjectMapper();
        var port=new NeoForgeScoreboardPort(server);var scores=MineAgentRuntimeServices.scoreboards(server);
        if(!setup){
            var pack=ServerPackageRuntime.get(server).ownedPackage(player.getUUID(),packageId(),1).orElseThrow();Files.writeString(root.resolve("existing-package.json"),json.writeValueAsString(pack));
            var grants=new HashSet<>(MineAgentRuntimeServices.permissions(server).trustedActions(player.getUUID()));grants.add(PermissionAction.MANAGE_SCOREBOARD);MineAgentRuntimeServices.permissions(server).setTrustedActions(player.getUUID(),grants);
            player.setGameMode(net.minecraft.world.level.GameType.CREATIVE);player.getAbilities().flying=true;player.onUpdateAbilities();player.teleportTo(server.overworld(),32.5,160,0.5,Set.of(),0,0,true);
            if(restore()){
                var view=scores.view(UUID.fromString(System.getProperty("mineagent.worldBoardResume"))).orElseThrow();
                if(view.kind()!=ScoreViewKind.WORLD_BOARD||!view.ownerPackageId().equals(packageId())||view.revision()!=3)throw new IllegalStateException("WORLD_BOARD_RESUME_SCOPE");
                viewId=view.viewId().toString();sourceId=view.sourceId().toString();objective=scores.source(view.sourceId()).orElseThrow().reference();
            }else{
                objective="wb_"+RUN.substring(0,8);if(!port.createObjective(objective,"dummy","世界观测","INTEGER",true,NumberFormatSpec.defaultFormat()).accepted())throw new IllegalStateException("WORLD_BOARD_SOURCE_SETUP");
                port.setScore(objective,"Cedar",7);port.setScore(objective,"Reed",2);port.setDisplaySlot("sidebar",objective);
                sourceId=scores.refreshSources().stream().filter(s->s.reference().equals(objective)).findFirst().orElseThrow().sourceId().toString();
            }
            Files.writeString(root.resolve("before.json"),json.writeValueAsString(port.snapshot()));setup=true;
        }
        if(viewId==null){var view=scores.views().stream().filter(v->v.ownerPackageId().equals(packageId())&&v.sourceId().toString().equals(sourceId)).findFirst().orElse(null);if(view!=null)viewId=view.viewId().toString();}
        if(externalRequested&&!externalChanged){server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),"scoreboard players set Cedar "+objective+" 23");externalChanged=true;}
        if(closedBrowserRequested&&!closedBrowserChanged){server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),"scoreboard players set Cedar "+objective+" 31");closedBrowserChanged=true;}
        if(finishRequested&&!verified){
            var view=scores.view(UUID.fromString(viewId));
            if(restore()){if(view.isPresent())return;}else if(view.isEmpty()||view.get().revision()!=3||view.get().kind()!=ScoreViewKind.WORLD_BOARD)throw new IllegalStateException("WORLD_BOARD_PLACEMENT_NOT_PERSISTED");
            var rows=port.snapshot().entries().stream().filter(e->e.objectiveName().equals(objective)).toList();
            if(rows.stream().filter(e->e.holder().equals("Cedar")).findFirst().orElseThrow().score()!=31||rows.stream().filter(e->e.holder().equals("Reed")).findFirst().orElseThrow().score()!=2)throw new IllegalStateException("WORLD_BOARD_MUTATED_SCORES");
            Files.writeString(root.resolve("server-result.json"),json.writeValueAsString(Map.of("view",view.<Object>map(v->v).orElse("DELETED"),"scoreboard",port.snapshot(),"restore",restore(),"viewId",viewId,"sourceId",sourceId,"worldId",MineAgentRuntimeServices.worldId(server))));
            verified=true;MineAgentRuntimeMod.LOGGER.info("MINEAGENT_WORLD_BOARD_SERVER_OK run={} restore={}",RUN,restore());
        }
    }
}
