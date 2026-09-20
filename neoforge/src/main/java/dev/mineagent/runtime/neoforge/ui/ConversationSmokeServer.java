package dev.mineagent.runtime.neoforge.ui;
import dev.mineagent.runtime.neoforge.*;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import java.nio.file.*;
import java.util.*;
/** Explicit seeded history plus two real UI-to-local-SSE generations; not real-model evidence. */
@EventBusSubscriber(modid="mineagent_runtime")
public final class ConversationSmokeServer {
    public static volatile UUID agent,otherAgent,a,b,foreign,wrongAgent,task,aOperation,interrupted;public static volatile boolean ready,switchPersona,aDone,bDone,finish,done;public static volatile String failure;private static boolean initialized,personaChanged;
    public static volatile long bRevision;public static volatile String bState="";
    public static volatile boolean nativeRoutingEnabled,nativeDone;public static volatile UUID nativeOperation;
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON=new com.fasterxml.jackson.databind.ObjectMapper();
    private static Object negativeMessage,negativeSummary;private static int negativeTick;public static volatile boolean negativeStable;
    public static boolean enabled(){return Boolean.getBoolean("mineagent.conversationSmoke");}public static String stage(){return System.getProperty("mineagent.conversationStage","prepare");}
    public static boolean nativeMode(){return Boolean.getBoolean("mineagent.conversationNativeSmoke");}
    public static boolean summaryMode(){return Boolean.getBoolean("mineagent.conversationSummarySmoke");}
    public static String summaryFailureMode(){return System.getProperty("mineagent.conversationSummaryFailureMode","");}
    public static String expectedSummaryState(){return switch(summaryFailureMode()){case "cancel"->"CANCELLED";case "provider-failure","invalid-output"->"FAILED";default->"READY";};}
    public static String expectedError(){return switch(summaryFailureMode()){case "cancel"->"USER_CANCELLED";case "provider-failure"->"SUMMARY_PROVIDER_FAILED";case "invalid-output"->"SUMMARY_OUTPUT_INVALID";case "budget-exhausted"->"SUMMARY_CALL_BUDGET_EXHAUSTED";default->"";};}
    @SubscribeEvent public static void tick(ServerTickEvent.Post event)throws Exception{
        if(!enabled()||done||failure!=null)return;var server=event.getServer();var viewer=server.getPlayerList().getPlayers().stream().filter(p->!(p instanceof dev.mineagent.runtime.neoforge.body.MineAgentPlayer)).findFirst().orElse(null);if(viewer==null)return;Path root=Files.createDirectories(server.getServerDirectory().resolve("conversation-evidence"));var store=ServerConversations.get(server).store();
        try{
            if(!initialized){initialized=true;var cfg=MineAgentRuntimeServices.config(server);cfg.apply(new dev.mineagent.runtime.api.config.ConfigPatch(cfg.snapshot().revision(),Map.of("runtime.initialized","true","voice.output.enabled","false")),true);
                if(stage().equals("prepare")){
                    var bodies=MineAgentRuntimeServices.bodies(server);agent=bodies.createPersistentAt("会话角色A",viewer.getUUID(),server.overworld(),viewer.position().add(2,0,0)).agentId();otherAgent=bodies.createPersistentAt("会话角色B",viewer.getUUID(),server.overworld(),viewer.position().add(4,0,0)).agentId();
                    a=store.create(viewer.getUUID(),agent,UUID.randomUUID(),"城堡 A").conversationId();for(int i=0;i<51;i++){var turn=store.begin(viewer.getUUID(),agent,a,UUID.randomUUID(),1,"  HISTORY_USER_"+i+"\n原文  ",0);store.finish(turn.operationId(),"COMPLETE","HISTORY_ASSISTANT_"+i,"");}
                    UUID outsider=UUID.randomUUID();foreign=store.create(outsider,agent,UUID.randomUUID(),"other viewer").conversationId();var f=store.begin(outsider,agent,foreign,UUID.randomUUID(),1,"SECRET_OTHER_VIEWER",0);store.finish(f.operationId(),"COMPLETE","private","");
                    wrongAgent=store.create(viewer.getUUID(),otherAgent,UUID.randomUUID(),"other Agent").conversationId();var g=store.begin(viewer.getUUID(),otherAgent,wrongAgent,UUID.randomUUID(),1,"SECRET_OTHER_AGENT",0);store.finish(g.operationId(),"COMPLETE","private","");
                    task=MineAgentRuntimeServices.tasks(server).create(agent,viewer.getUUID(),"Conversation-independent task",1,List.of(new dev.mineagent.runtime.core.task.TaskStepSpec("conversation_independent_wait",Set.of()))).taskId();
                    var def=bodies.definitions().stream().filter(d->d.agentId().equals(agent)).findFirst().orElseThrow();MineAgentRuntimeServices.personas(server).save(def,viewer.getUUID(),false,UUID.randomUUID(),0,"PERSONA_BEFORE_A");
                    Files.writeString(root.resolve("seed-origin.json"),JSON.writeValueAsString(Map.of("kind","EXPLICIT_TEST_HISTORY_NOT_MODEL","messages",102,"a",a,"foreign",foreign,"wrongAgent",wrongAgent)));
                }else{var j=JSON.readTree(root.resolve("journal.json").toFile());agent=UUID.fromString(j.path("agent").asText());otherAgent=UUID.fromString(j.path("otherAgent").asText());a=UUID.fromString(j.path("a").asText());b=UUID.fromString(j.path("b").asText());foreign=UUID.fromString(j.path("foreign").asText());wrongAgent=UUID.fromString(j.path("wrongAgent").asText());task=UUID.fromString(j.path("task").asText());aOperation=UUID.fromString(j.path("aOperation").asText());interrupted=UUID.fromString(j.path("interrupted").asText());if(store.pending(interrupted))throw new IllegalStateException("CONVERSATION_RESTART_REPLAYED_PENDING");}
                if(!stage().equals("prepare")&&nativeMode())nativeOperation=UUID.fromString(JSON.readTree(root.resolve("native-operation.json").toFile()).path("operationId").asText());ready=true;
            }
            if(stage().equals("prepare")){
                var ca=store.get(viewer.getUUID(),agent,a);if(!ca.activeOperation().isEmpty())aOperation=UUID.fromString(ca.activeOperation());
                for(var c:store.list(viewer.getUUID(),agent,"ALL","",0,20).conversations())if(!c.conversationId().equals(a)){b=c.conversationId();break;}
                if(switchPersona&&!personaChanged){var def=MineAgentRuntimeServices.bodies(server).definitions().stream().filter(d->d.agentId().equals(agent)).findFirst().orElseThrow();MineAgentRuntimeServices.personas(server).save(def,viewer.getUUID(),false,UUID.randomUUID(),1,"PERSONA_AFTER_A");personaChanged=true;Files.writeString(server.getServerDirectory().resolve("conversation-release-a"),"User switched to B and its unsent draft is present.");}
                aDone=ca.messageCount()==104&&ca.activeOperation().isEmpty();if(b!=null){var cb=store.get(viewer.getUUID(),agent,b);bDone=cb.messageCount()==2&&cb.activeOperation().isEmpty();bRevision=cb.revision();bState=cb.state();}
                if(summaryMode()&&aDone){
                    var last=store.messages(viewer.getUUID(),agent,a,0,20).messages().getLast();
                    if(summaryFailureMode().isEmpty()){if(last.status().equals("FAILED"))throw new IllegalStateException("SUMMARY_NATIVE_REPLY_FAILED_"+last.errorCode());}
                    else{
                        if(!last.status().equals(summaryFailureMode().equals("cancel")?"CANCELLED":"FAILED")||!last.errorCode().equals(expectedError())||last.textLength()!=0)throw new IllegalStateException("SUMMARY_FAILURE_STATUS_MISMATCH");
                        var summary=JSON.readTree(ServerConversations.get(server).read(viewer,Map.of("kind","summary","agentId",agent.toString(),"conversationId",a.toString())).get("state"));
                        if(!summary.path("state").asText().equals(expectedSummaryState()))throw new IllegalStateException("SUMMARY_FAILURE_JOB_MISMATCH");
                        if(negativeMessage==null){negativeMessage=last;negativeSummary=summary;negativeTick=server.getTickCount();Files.writeString(root.resolve("negative-before.json"),JSON.writeValueAsString(Map.of("message",last,"summary",summary)));}
                        if(!negativeMessage.equals(last)||!negativeSummary.equals(summary))throw new IllegalStateException("SUMMARY_LATE_RESULT_MUTATED_TERMINAL");
                        if(Files.exists(server.getServerDirectory().resolve("conversation-summary-response-sent"))&&server.getTickCount()-negativeTick>=80){negativeStable=true;Files.writeString(root.resolve("negative-after.json"),JSON.writeValueAsString(Map.of("message",last,"summary",summary,"stableTicks",server.getTickCount()-negativeTick)));}
                    }
                }
                nativeRoutingEnabled=ServerConversations.get(server).focused(viewer).map(dev.mineagent.runtime.core.conversation.ConversationFocusRegistry.Focus::nativeInput).orElse(false);
                if(nativeMode()&&b!=null){var cb=store.get(viewer.getUUID(),agent,b);nativeDone=cb.messageCount()==4&&cb.activeOperation().isEmpty();}
            }
            if(finish){
                if(summaryMode()){var summary=JSON.readTree(ServerConversations.get(server).read(viewer,Map.of("kind","summary","agentId",agent.toString(),"conversationId",a.toString())).get("state"));
                    if(summaryFailureMode().isEmpty()){if(!summary.path("state").asText().equals("READY")||!summary.path("sourceValid").asBoolean()||summary.path("end").path("offset").asInt()!=0||!summary.path("text").asText().contains("SUMMARY_ANCHOR"))throw new IllegalStateException("SUMMARY_NATIVE_INVALID");}
                    else{if(!summary.path("state").asText().equals(expectedSummaryState())||stage().equals("prepare")&&!negativeStable)throw new IllegalStateException("SUMMARY_FAILURE_NATIVE_INVALID");if(stage().equals("resume")&&!summary.equals(JSON.readTree(root.resolve("prepare-summary-native.json").toFile())))throw new IllegalStateException("SUMMARY_FAILURE_RESTART_CHANGED");}
                    Files.writeString(root.resolve(stage()+"-summary-native.json"),summary.toString());}
                var ca=store.get(viewer.getUUID(),agent,a);var cb=store.get(viewer.getUUID(),agent,b);var independent=MineAgentRuntimeServices.tasks(server).get(task).orElseThrow();if(!cb.title().equals("日常 B 改名")||!cb.state().equals("ACTIVE")||cb.revision()!=6||cb.messageCount()!=(nativeMode()?4:2)||independent.revision()!=1)throw new IllegalStateException("CONVERSATION_NATIVE_PARITY");
                if(stage().equals("prepare")){if(ca.messageCount()!=104||aOperation==null)throw new IllegalStateException("CONVERSATION_A_RESULT");interrupted=UUID.randomUUID();store.begin(viewer.getUUID(),agent,a,interrupted,ca.revision(),"EXPLICIT_PENDING_TEST_NO_PROVIDER",2);Files.writeString(root.resolve("journal.json"),JSON.writeValueAsString(Map.of("agent",agent,"otherAgent",otherAgent,"a",a,"b",b,"foreign",foreign,"wrongAgent",wrongAgent,"task",task,"aOperation",aOperation,"interrupted",interrupted)));}
                else if(ca.messageCount()!=106||!store.messages(viewer.getUUID(),agent,a,0,20).messages().getLast().status().equals("INTERRUPTED"))throw new IllegalStateException("CONVERSATION_RESTART_HISTORY");
                Files.writeString(root.resolve(stage()+"-native.json"),JSON.writeValueAsString(Map.of("a",store.get(viewer.getUUID(),agent,a),"b",cb,"aMessages",store.messages(viewer.getUUID(),agent,a,0,20),"bMessages",store.messages(viewer.getUUID(),agent,b,0,20),"task",independent,"providerMode","CONTROLLED_LOCAL_SSE")));done=true;
            }
        }catch(Exception e){failure=e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();Files.writeString(root.resolve(stage()+"-failure.json"),JSON.writeValueAsString(Map.of("error",failure)));}
    }
}
