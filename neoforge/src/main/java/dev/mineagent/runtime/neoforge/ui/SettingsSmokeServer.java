package dev.mineagent.runtime.neoforge.ui;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mineagent.runtime.api.permission.PermissionAction;
import dev.mineagent.runtime.neoforge.MineAgentRuntimeServices;
import dev.mineagent.runtime.neoforge.body.MineAgentPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.level.LevelSettings;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import java.nio.file.*;
import java.util.*;

@EventBusSubscriber(modid="mineagent_runtime")
public final class SettingsSmokeServer {
    public static final String TEST_KEY="native-fixture-key-only";
    public static volatile int step;public static volatile boolean done;public static volatile String failure="";public static volatile UUID target;
    public static final Set<String> seen=java.util.concurrent.ConcurrentHashMap.newKeySet();private static final ObjectMapper JSON=new ObjectMapper();private static int started=-1;private static boolean probing;
    public static String stage(){return System.getProperty("mineagent.settingsStage","prepare");}
    public static boolean enabled(){return Boolean.getBoolean("mineagent.settingsSmoke");}
    private static void require(boolean ok,String code){if(!ok)throw new IllegalStateException(code);}
    private static Path root(net.minecraft.server.MinecraftServer s){return s.getServerDirectory().resolve("settings-evidence");}
    private static void save(net.minecraft.server.MinecraftServer s,String name,Object value)throws Exception{var path=root(s).resolve(stage()).resolve(name);Files.createDirectories(path.getParent());Files.writeString(path,JSON.writeValueAsString(value));}
    private static void finish(net.minecraft.server.MinecraftServer s)throws Exception{save(s,"result.json",Map.of("status","NATIVE_SETTINGS_FLOW_VERIFIED","stage",stage(),"revision",MineAgentRuntimeServices.config(s).snapshot().revision(),"seen",seen,"paidCalls",0,"systemInputInjected",false,"fullV1",false));done=true;s.halt(false);}
    private static void probe(net.minecraft.server.MinecraftServer s,boolean disabled,int next){if(probing)return;probing=true;MineAgentRuntimeServices.worker(s).planPresentation(MineAgentRuntimeServices.config(s),UUID.randomUUID(),"EXPLICIT_LOCAL_SETTINGS_PROBE",()->true).whenComplete((r,e)->s.execute(()->{try{require(e==null,"SETTINGS_WORKER_PROBE_ERROR");require(disabled?r.type().equals("error")&&"PROVIDER_NOT_CONFIGURED".equals(r.payload().get("message")):r.type().equals("model.result")&&"settings-probe-ok".equals(r.payload().get("text")),"SETTINGS_WORKER_PROBE_RESULT");save(s,disabled?"disabled-worker.json":"configured-worker.json",Map.of("type",r.type(),"disabled",disabled,"revision",MineAgentRuntimeServices.config(s).snapshot().revision()));step=next;probing=false;}catch(Exception problem){failure=problem.toString();}}));}
    @SubscribeEvent public static void tick(ServerTickEvent.Post event)throws Exception{
        if(!enabled()||done||!failure.isEmpty())return;var s=event.getServer();if(started<0)started=s.getTickCount();var viewer=s.getPlayerList().getPlayers().stream().filter(p->!(p instanceof MineAgentPlayer)).findFirst().orElse(null);if(viewer==null)return;
        try{require(s.getTickCount()-started<1500,"SETTINGS_TIMEOUT_"+stage()+"_"+step);var config=MineAgentRuntimeServices.config(s);var permissions=MineAgentRuntimeServices.permissions(s);
            if(step==0){
                var data=s.getWorldData();var field=data.getClass().getDeclaredField("settings");field.setAccessible(true);var old=(LevelSettings)field.get(data);field.set(data,new LevelSettings(old.levelName(),old.gameType(),old.difficultySettings(),false,old.dataConfiguration(),old.lifecycle()));s.getPlayerList().deop(viewer.nameAndId());require(!viewer.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER),"SETTINGS_VIEWER_NOT_REGULAR");
                if(stage().equals("prepare")){target=UUID.randomUUID();Files.createDirectories(root(s));Files.writeString(root(s).resolve("journal.json"),JSON.writeValueAsString(Map.of("target",target,"world",MineAgentRuntimeServices.worldId(s))));permissions.setTrustedActions(viewer.getUUID(),Set.of(PermissionAction.MANAGE_PROVIDERS,PermissionAction.MANAGE_PERMISSIONS,PermissionAction.CONTROL_PUBLIC_MEDIA));step=1;}
                else{var journal=JSON.readTree(Files.readString(root(s).resolve("journal.json")));target=UUID.fromString(journal.path("target").asText());require(journal.path("world").asText().equals(MineAgentRuntimeServices.worldId(s).toString())&&config.secretValue("provider.openai.apiKey").orElse("").equals(TEST_KEY)&&config.snapshot().values().get("provider.openai.model").equals("configured-model")&&permissions.trustedActions(target).contains(PermissionAction.CREATE_AGENT),"SETTINGS_NOT_PERSISTED");require(!permissions.allowed(viewer.getUUID(),false,PermissionAction.MANAGE_PROVIDERS),"VIEWER_PRIVILEGE_NOT_CLEARED");save(s,"persisted.json",Map.of("model",config.snapshot().values().get("provider.openai.model"),"keyConfigured",true,"targetGranted",true));step=11;}
            }else if(stage().equals("prepare")){
                if(step==1&&"configured-model".equals(config.snapshot().values().get("provider.openai.model"))){require(!Files.exists(s.getServerDirectory().resolve("settings-probe-count.json")),"SAVE_CALLED_MODEL");save(s,"public-saved.json",ServerSettings.read(viewer));step=2;}
                else if(step==2&&config.secretValue("provider.openai.apiKey").orElse("").equals(TEST_KEY)){save(s,"secret-saved.json",Map.of("configured",true,"revision",config.snapshot().revision()));step=3;}
                else if(step==3&&config.secretValue("provider.openai.apiKey").isEmpty()&&seen.contains("key-not-in-browser")){save(s,"secret-cleared.json",Map.of("configured",false,"revision",config.snapshot().revision()));step=4;}
                else if(step==4&&!probing&&config.secretValue("provider.openai.apiKey").orElse("").equals(TEST_KEY)&&permissions.trustedActions(target).contains(PermissionAction.CREATE_AGENT)){require(!Files.exists(s.getServerDirectory().resolve("settings-probe-count.json")),"CONFIGURATION_AUTOMATIC_PROBE");save(s,"permission-saved.json",ServerSettings.read(viewer));probe(s,false,5);}
                else if(step==5&&"false".equals(config.snapshot().values().get("provider.openai.enabled")))probe(s,true,6);
                else if(step==6&&"true".equals(config.snapshot().values().get("provider.openai.enabled"))&&seen.contains("reenabled"))finish(s);
            }else{
                if(step==11&&seen.containsAll(Set.of("non-owner-read-redacted","unauthorized-save-denied"))){permissions.setTrustedActions(viewer.getUUID(),Set.of(PermissionAction.MANAGE_PROVIDERS,PermissionAction.MANAGE_PERMISSIONS,PermissionAction.CONTROL_PUBLIC_MEDIA));step=12;}
                else if(step==12&&seen.contains("restored-ui"))probe(s,false,13);
                else if(step==13)finish(s);
            }
        }catch(Exception e){failure=e.toString();save(s,"failure.json",Map.of("step",step,"error",failure));}
    }
}
