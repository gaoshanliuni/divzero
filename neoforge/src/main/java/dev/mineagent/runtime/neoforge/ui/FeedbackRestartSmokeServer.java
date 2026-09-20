package dev.mineagent.runtime.neoforge.ui;

import com.fasterxml.jackson.databind.*;
import dev.mineagent.runtime.neoforge.*;
import dev.mineagent.runtime.neoforge.network.UiPayloads;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** Explicit interruption/restart fixture; observes real durable stages, never creates business receipts. */
public final class FeedbackRestartSmokeServer {
    private static final ObjectMapper JSON=new ObjectMapper();private static final Set<UUID> done=new HashSet<>();
    private static JsonNode journal;private static int sinceReady;private static boolean stopped,verified;
    public static String stage(){return System.getProperty("mineagent.feedbackRestartStage","");}
    public static boolean enabled(){return !stage().isEmpty();}
    public static boolean resume(){return stage().equals("resume");}
    public static boolean reaccept(){return Boolean.getBoolean("mineagent.feedbackReacceptSmoke");}
    public static Map<String,Object> bindings(MinecraftServer s){return enabled()&&DeliverySmokeServer.enabled()?Map.of("feedbackTrace",new Trace(s)):Map.of();}
    public static final class Trace {
        private final MinecraftServer server;Trace(MinecraftServer server){this.server=server;}
        public void record(String kind,String id)throws Exception{trace(server,kind,id);}
    }
    private static Path root(MinecraftServer s){return s.getServerDirectory().resolve("feedback-restart-evidence");}
    private static void write(MinecraftServer s,String name,Object value)throws Exception{Files.createDirectories(root(s));Files.writeString(root(s).resolve(name),JSON.writeValueAsString(value));}
    public static void trace(MinecraftServer s,String kind,String id)throws Exception{
        if(!enabled()||!DeliverySmokeServer.enabled()||!s.isSameThread()||!Set.of("create","restore","plan").contains(kind))throw new SecurityException("FEEDBACK_RESTART_TRACE_SCOPE");
        Path path=root(s).resolve("trace.json");var events=Files.isRegularFile(path)?(com.fasterxml.jackson.databind.node.ArrayNode)JSON.readTree(path.toFile()):JSON.createArrayNode();events.add(JSON.valueToTree(Map.of("kind",kind,"id",id)));write(s,"trace.json",events);
    }
    private static Map<String,Object> database(MinecraftServer s)throws Exception{
        var result=new LinkedHashMap<String,Object>();
        try(var db=DriverManager.getConnection("jdbc:sqlite:"+s.getServerDirectory().resolve("mineagent-runtime-data/runtime.db").toAbsolutePath());var q=db.createStatement()){
            q.execute("PRAGMA query_only=ON");
            for(String table:List.of("mineagent_shared_namespaces_v1","mineagent_shared_operations_v1","mineagent_shared_outbox_v1")){
                var rows=JSON.createArrayNode();try(var r=q.executeQuery("SELECT payload FROM "+table+" ORDER BY rowid")){while(r.next())rows.add(JSON.readTree(r.getString(1)));}result.put(table,rows);
            }
            try(var r=q.executeQuery("SELECT count(*) FROM mineagent_conversation_messages_v1 WHERE role='ASSISTANT'")){r.next();result.put("assistantMessages",r.getInt(1));}
            try(var r=q.executeQuery("SELECT count(*) FROM mineagent_event_triggers_v1")){r.next();result.put("modelTriggers",r.getInt(1));}
        }return result;
    }
    public static boolean prepareTick(MinecraftServer s)throws Exception{
        if(!stage().equals("prepare"))return false;if(stopped)return true;
        var feedback=ServerUiRuntime.get(s).deliveries().feedback();var rows=feedback.store().rows();var phases=feedback.dataPhases();String boundary=System.getProperty("mineagent.feedbackRestartBoundary","afterPlan");String wanted=boundary.equals("afterPlan")?"APPLY":"VERIFY";
        if(rows.size()!=2||rows.stream().anyMatch(i->i.dataPlan()==null))return false;
        var observedDb=database(s);
        var selected=phases.entrySet().stream().filter(e->e.getValue().equals(wanted)).filter(e->{if(!boundary.equals("afterConflict"))return true;var item=rows.stream().filter(i->i.id().equals(e.getKey())).findFirst().orElseThrow();for(var operation:JSON.valueToTree(observedDb).path("mineagent_shared_operations_v1"))if(operation.path("context").path("actorKind").asText().equals("FEEDBACK")&&operation.path("context").path("actor").asText().equals(item.scope().authorId().toString())&&operation.path("receipt").path("status").asText().equals("CONFLICT"))return true;return false;}).findFirst();if(selected.isEmpty())return false;
        var deliveries=JSON.createArrayNode();for(var item:rows)deliveries.add(JSON.valueToTree(ServerUiRuntime.get(s).deliveries().feedbackRow(item.scope().authorId(),item.scope().deliveryId())));
        var value=new LinkedHashMap<String,Object>();value.put("boundary",boundary);value.put("selectedFeedback",selected.get().getKey());value.put("feedback",rows);value.put("deliveries",deliveries);value.put("phases",phases);value.put("database",observedDb);value.put("trace",JSON.readTree(root(s).resolve("trace.json").toFile()));value.put("serverTick",s.getTickCount());value.put("source","REAL_NATIVE_DURABLE_STAGE_BEFORE_PLANNED_PROCESS_STOP");
        write(s,"cutoff.json",value);write(s,"prepare-result.json",Map.of("status","NATIVE_FEEDBACK_STOP_BOUNDARY_CAPTURED","boundary",boundary,"selected",selected.get().getKey(),"systemInputInjected",false));
        stopped=true;MineAgentRuntimeMod.LOGGER.info("MINEAGENT_DELIVERY_SERVER_OK feedbackRestart={} boundary={}",stage(),boundary);s.halt(false);return true;
    }
    private static void require(boolean value,String code){if(!value)throw new IllegalStateException(code);}
    public static void tick(MinecraftServer s)throws Exception{
        if(!resume()||stopped)return;
        try{
            if(journal==null)journal=JSON.readTree(root(s).resolve("cutoff.json").toFile());
            var feedback=ServerUiRuntime.get(s).deliveries().feedback();var items=feedback.store().rows();
            require(items.size()==2,"FEEDBACK_RESTART_ROW_COUNT");
            for(var before:journal.path("feedback")){
                var item=items.stream().filter(i->i.id().toString().equals(before.path("id").asText())).findFirst().orElseThrow();String expected=before.path("state").asText().equals("PROCESSING")?"INTERRUPTED":before.path("state").asText();
                require(item.state().equals(expected),"FEEDBACK_RESTART_STATE");require(JSON.readTree(JSON.writeValueAsString(item.dataPlan())).equals(before.path("dataPlan")),"FEEDBACK_RESTART_PLAN_CHANGED");
                require(JSON.readTree(JSON.writeValueAsString(item.completion())).equals(before.path("completion")),"FEEDBACK_RESTART_COMPLETION_CHANGED");
            }
            require(feedback.dataPhases().isEmpty(),"FEEDBACK_RESTART_WORK_REPLAY");require(JSON.valueToTree(database(s)).equals(journal.path("database")),"FEEDBACK_RESTART_DATABASE_CHANGED");
            var trace=JSON.readTree(root(s).resolve("trace.json").toFile());require(java.util.stream.StreamSupport.stream(trace.spliterator(),false).filter(n->n.path("kind").asText().equals("plan")).count()==2,"FEEDBACK_RESTART_HANDLER_REPLAY");
            var binding=items.getFirst().scope().dataBinding();
            if(!dev.mineagent.runtime.neoforge.content.WorldContentRuntime.get(s).active(binding.instanceId()))return;
            if(sinceReady==0)sinceReady=s.getTickCount();if(s.getTickCount()-sinceReady<80)return;
            verified=true;
            if(done.size()==2){write(s,"resume-result.json",Map.of("status",reaccept()?"NATIVE_FEEDBACK_REACCEPT_NO_REPLAY_VERIFIED":"NATIVE_FEEDBACK_RESTART_NO_REPLAY_VERIFIED","reacceptVerified",reaccept(),"feedback",items,"database",database(s),"trace",trace,"ticksAfterRestore",s.getTickCount()-sinceReady,"clientAuthors",done,"systemInputInjected",false,"fullV1",false));stopped=true;MineAgentRuntimeMod.LOGGER.info("MINEAGENT_DELIVERY_SERVER_OK feedbackRestart=resume");s.halt(false);}
        }catch(Exception failure){stopped=true;write(s,"resume-failure.json",Map.of("error",failure.toString()));s.halt(false);throw failure;}
    }
    public static void handle(ServerPlayer p,UiPayloads.Command packet)throws Exception{
        var s=p.level().getServer();var args=JSON.readTree(packet.json());String action=args.path("action").asText();if(!args.isObject()||args.size()!=1||!Set.of("info","done").contains(action))throw new IllegalArgumentException("FEEDBACK_RESTART_FIXTURE_ACTION");
        if(journal==null){PacketDistributor.sendToPlayer(p,new UiPayloads.Event(packet.requestId(),"deliveryFixture","{\"run\":\"restart\",\"ready\":false}"));return;}
        var prior=java.util.stream.StreamSupport.stream(journal.path("feedback").spliterator(),false).filter(i->i.path("scope").path("authorId").asText().equals(p.getUUID().toString())).findFirst().orElseThrow();
        if(action.equals("done"))done.add(p.getUUID());
        var other=java.util.stream.StreamSupport.stream(journal.path("feedback").spliterator(),false).filter(i->!i.path("id").equals(prior.path("id"))).findFirst().orElseThrow();
        var row=ServerUiRuntime.get(s).deliveries().feedbackRow(p.getUUID(),UUID.fromString(prior.path("scope").path("deliveryId").asText()));
        var result=new LinkedHashMap<String,Object>();result.put("run","restart");result.put("ready",verified);result.put("own",Map.of("deliveryId",row.id(),"packageId",row.asset().packageId(),"packageRevision",row.asset().revision(),"status",row.status(),"closeConfirmed",row.closeConfirmed(),"dataRevision",row.dataRevision(),"receivedDataRevision",row.dataReceipt()==null?0:row.dataReceipt().revision()));result.put("feedback",ServerUiRuntime.get(s).deliveries().feedback().store().inspect(p.getUUID(),UUID.fromString(prior.path("id").asText())));result.put("otherFeedbackId",other.path("id").asText());
        if(reaccept()){
            String expected="NOT_RECORDED";for(var op:journal.path("database").path("mineagent_shared_operations_v1"))if(op.path("context").path("actorKind").asText().equals("FEEDBACK")&&op.path("context").path("actor").asText().equals(p.getUUID().toString()))expected=op.path("receipt").path("status").asText();
            result.put("expectedOutcome",expected);result.put("expectedCount",journal.path("database").path("mineagent_shared_namespaces_v1").get(0).path("records").path("count/").path("value").asInt());
        }
        PacketDistributor.sendToPlayer(p,new UiPayloads.Event(packet.requestId(),"deliveryFixture",JSON.writeValueAsString(result)));
    }
}
