package dev.mineagent.runtime.neoforge.ui;
import dev.mineagent.runtime.neoforge.*;
import dev.mineagent.runtime.neoforge.body.MineAgentPlayer;
import dev.mineagent.runtime.neoforge.scoreboard.NeoForgeScoreboardPort;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
/** Restart fixture reuses the prior actual target; never recreates scores or calls a model. */
public final class HudRestoreSmokeServer {
    public static final String RUN=UUID.randomUUID().toString();
    public static volatile boolean ready,externalRequested,externalChanged;
    public static void tick(ServerTickEvent.Post event)throws Exception{
        var server=event.getServer();var player=server.getPlayerList().getPlayers().stream().filter(p->!(p instanceof MineAgentPlayer)).findFirst().orElse(null);if(player==null)return;
        var root=server.getServerDirectory().resolve("hud-restore-evidence").resolve(RUN);Files.createDirectories(root);
        var data=JsonParser.parseString(Files.readString(server.getServerDirectory().resolve("hud-restore-checkpoint.json"))).getAsJsonObject();
        var entry=data.getAsJsonObject("entry");var scores=MineAgentRuntimeServices.scoreboards(server);
        var view=scores.view(UUID.fromString(entry.get("targetId").getAsString())).orElseThrow();
        var source=scores.source(view.sourceId()).orElseThrow();
        if(!view.enabled()||!view.ownerPackageId().toString().equals(entry.get("packageId").getAsString()))throw new IllegalStateException("HUD_RESTORE_TARGET_CHANGED");
        var port=new NeoForgeScoreboardPort(server);var json=new com.fasterxml.jackson.databind.ObjectMapper();
        if(!ready){Files.writeString(root.resolve("server-before.json"),json.writeValueAsString(Map.of("view",view,"source",source,"scoreboard",port.snapshot())));ready=true;}
        if(externalRequested&&!externalChanged){
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),"scoreboard players set Alice "+source.reference()+" 27");
            Files.writeString(root.resolve("server-after.json"),json.writeValueAsString(Map.of("view",view,"source",source,"scoreboard",port.snapshot())));externalChanged=true;
        }
    }
}
