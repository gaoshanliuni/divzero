package dev.mineagent.runtime.neoforge.ui;
import com.fasterxml.jackson.databind.*;
import dev.mineagent.runtime.neoforge.MineAgentRuntimeServices;
import net.minecraft.server.MinecraftServer;
import java.nio.file.*;
import java.util.*;
/** No command plan, app path or geometry is supplied to the model by the fixture. */
public final class ConversationHostSmokeServer {
    public static volatile boolean ready;public static volatile int verified;public static volatile String failure="",url="";public static volatile UUID agent;public static volatile long configRevision;
    private static final ObjectMapper JSON=new ObjectMapper();private static final List<Map<String,Object>> commands=new ArrayList<>(),geometry=new ArrayList<>();
    public static boolean geometryOnly(){return System.getProperty("mineagent.conversationAgentScenario","").equals("primitives");}
    public static boolean active(){return Boolean.getBoolean("mineagent.conversationAgentReal")&&(geometryOnly()||System.getProperty("mineagent.conversationAgentScenario","").equals("host"));}
    public static void observe(String tool,JsonNode args,Map<String,Object> result){if(active())commands.add(Map.of("tool",tool,"arguments",args,"result",result));}
    public static void geometry(JsonNode args,Map<String,Object> result){if(active())geometry.add(Map.of("arguments",args,"result",result));}
    private static void save(MinecraftServer s,String n,Object v)throws Exception{Files.writeString(Files.createDirectories(s.getServerDirectory().resolve("host-command-smoke")).resolve(n+".json"),JSON.writeValueAsString(v));}
    public static void tick(MinecraftServer s){if(!failure.isEmpty())return;var p=s.getPlayerList().getPlayers().stream().filter(v->!(v instanceof dev.mineagent.runtime.neoforge.body.MineAgentPlayer)).findFirst().orElse(null);if(p==null)return;try{
        var config=MineAgentRuntimeServices.config(s).snapshot();url=config.values().getOrDefault("provider.openai.baseUrl","");configRevision=config.revision();
        if(!ready){if(!geometryOnly())s.getPlayerList().op(p.nameAndId());agent=MineAgentRuntimeServices.bodies(s).createPersistentAt("工具助手",p.getUUID(),p.level(),p.position().add(4,0,0)).agentId();ready=true;return;}
        var store=ServerConversations.get(s).store();var list=store.list(p.getUUID(),agent,"ALL","",0,20).conversations();if(list.isEmpty())return;var c=list.getFirst();if(c.messageCount()<2||!c.activeOperation().isEmpty()||c.messageCount()/2<=verified)return;var context=store.context(p.getUUID(),agent,c.conversationId(),null).orElseThrow();save(s,"conversation-"+c.messageCount()/2,context);save(s,"commands",commands);save(s,"geometry",geometry);
        if(!context.requestState().equals("COMPLETE")||context.modelReceipt()==null||!context.modelReceipt().requestedModel().equals("deepseek-flash"))throw new IllegalStateException("HOST_REAL_CONVERSATION_FAILED");
        if(verified==0&&!geometryOnly()){if(commands.stream().noneMatch(v->v.get("tool").equals("host_command")&&((Map<?,?>)v.get("result")).get("status").equals("EXECUTED")))throw new IllegalStateException("HOST_MODEL_DID_NOT_EXECUTE");if(commands.stream().anyMatch(v->!v.get("tool").equals("host_command")))throw new IllegalStateException("HOST_UNRELATED_WORLD_ACTION");verified=1;return;}
        if(geometryOnly()&&!commands.isEmpty())throw new IllegalStateException("GEOMETRY_READ_ONLY_SCOPE_VIOLATED");Set<String> types=new HashSet<>();for(var v:geometry){if(!"GEOMETRY_VALIDATED_NOT_EXECUTED".equals(((Map<?,?>)v.get("result")).get("status")))continue;var args=(JsonNode)v.get("arguments");for(var shape:JSON.readTree(args.path("source").asText()).path("primitives"))types.add(shape.path("type").asText());}
        if(!types.containsAll(Set.of("box","plane","disc","annulus","cylinder","cone","frustum","prism","pyramid","ellipsoid","capsule")))throw new IllegalStateException("HOST_MODEL_GEOMETRY_COVERAGE_"+types);
        save(s,"result",Map.of("status",geometryOnly()?"REAL_PRIMITIVE_TOOL_CONTRACT_VERIFIED":"REAL_HOST_COMMAND_AND_PRIMITIVE_TOOLS_VERIFIED","hostOperations",commands.size(),"validatedPrimitiveTypes",types,"geometryToolCalls",geometry.size(),"createsWorldGeometry",false));verified=2;
    }catch(Exception e){failure=e.toString();try{save(s,"failure",Map.of("error",failure));}catch(Exception ignored){}}}
}
