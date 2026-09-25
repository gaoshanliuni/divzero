package dev.mineagent.runtime.neoforge.ui;

import com.fasterxml.jackson.databind.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Re-stage already verified model outputs with NEW operations in an isolated filming world.
 * No provider response is fabricated. Original geometry and fluid readbacks still run. */
public final class CinematicSavedSmoke {
    private static final ObjectMapper JSON=new ObjectMapper();
    public static volatile int geometryStep,cobbleStep;
    public static volatile double targetX=2,targetY=113,targetZ=2;
    private static JsonNode cobble,geometry;
    private static CompletableFuture<Map<String,Object>> command,plan,apply;
    private static String plannedId;
    private static final List<Object> receipts=new ArrayList<>();
    private static JsonNode read(ServerPlayer p,String file)throws Exception {
        return JSON.readTree(Files.readString(p.level().getServer().getServerDirectory().resolve(file)));
    }
    public static Vec3 cobbleStart(ServerPlayer p)throws Exception {
        if(cobble==null)cobble=read(p,"cinematic-saved-cobble.json");
        var c=cobble.path("initialCenter");
        if(c.size()!=3||cobble.path("steps").isEmpty())throw new IllegalStateException("SAVED_COBBLE_SOURCE_REQUIRED");
        return new Vec3(c.get(0).asDouble()+.5,c.get(1).asDouble(),c.get(2).asDouble()+.5);
    }
    public static boolean cobble(ServerPlayer p,UUID agent)throws Exception {
        if(cobble==null)cobbleStart(p);
        if(command!=null){if(!command.isDone())return false;var r=command.join();
            if(!"APPLIED".equals(r.get("status")))throw new IllegalStateException("SAVED_COBBLE_COMMAND_"+r);
            receipts.add(r);command=null;cobbleStep++;
        }
        if(cobbleStep>=cobble.path("steps").size()){save(p,"cobble",receipts);return true;}
        if(CinematicSmokeTiming.pause("saved-cobble",cobbleStep,900))return false;
        var row=cobble.path("steps").get(cobbleStep);
        if(!row.path("tool").asText().equals("run_game_command"))throw new IllegalStateException("SAVED_COBBLE_GAME_COMMAND_ONLY");
        command=ConversationAgentTools.execute(p,agent,UUID.randomUUID(),"run_game_command",row.path("arguments").toString(),()->true);
        return false;
    }
    public static boolean geometry(ServerPlayer p,UUID agent)throws Exception {
        if(geometry==null){geometry=read(p,"cinematic-saved-geometry.json");if(geometry.path("sources").size()!=14)throw new IllegalStateException("SAVED_GEOMETRY_EXACT_SOURCE_SET");}
        if(apply!=null){if(!apply.isDone())return false;var r=apply.join();if(!"APPLIED".equals(r.get("status")))throw new IllegalStateException("SAVED_GEOMETRY_APPLY_"+r);
            receipts.add(r);apply=null;plannedId=null;geometryStep++;
        }
        if(geometryStep>=geometry.path("sources").size()){save(p,"geometry",receipts);return true;}
        if(CinematicSmokeTiming.pause("saved-geometry",geometryStep,3000))return false;
        if(plan!=null){if(!plan.isDone())return false;var r=plan.join();if(!"PLANNED".equals(r.get("status")))throw new IllegalStateException("SAVED_GEOMETRY_PLAN_"+r);
            plannedId=r.get("planId").toString();plan=null;apply=ConversationAgentTools.execute(p,agent,UUID.randomUUID(),"apply_world_geometry",JSON.createObjectNode().put("plan_id",plannedId).toString(),()->true);return false;
        }
        String source=geometry.path("sources").get(geometryStep).asText();var origin=JSON.readTree(source).path("origin");
        targetX=origin.get(0).asDouble()+2;targetY=origin.get(1).asDouble()+1.5;targetZ=origin.get(2).asDouble()+2;
        plan=ConversationAgentTools.execute(p,agent,UUID.randomUUID(),"plan_world_geometry",JSON.createObjectNode().put("source",source).toString(),()->true);
        return false;
    }
    private static void save(ServerPlayer p,String name,Object data)throws Exception{
        Path dir=Files.createDirectories(p.level().getServer().getServerDirectory().resolve("cinematic-saved-evidence"));
        Files.writeString(dir.resolve(name+".json"),JSON.writeValueAsString(Map.of("savedVerifiedOutputs",true,"providerCalls",0,"receipts",data)));
    }
    private CinematicSavedSmoke(){}
}
