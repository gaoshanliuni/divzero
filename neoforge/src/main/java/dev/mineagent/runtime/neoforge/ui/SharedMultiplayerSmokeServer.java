package dev.mineagent.runtime.neoforge.ui;

import com.fasterxml.jackson.databind.*;
import dev.mineagent.runtime.api.packages.*;
import dev.mineagent.runtime.api.permission.PermissionAction;
import dev.mineagent.runtime.api.ui.UiProtocol.*;
import dev.mineagent.runtime.api.ui.WorldUiProtocol.Launch;
import dev.mineagent.runtime.neoforge.*;
import dev.mineagent.runtime.neoforge.content.*;
import dev.mineagent.runtime.neoforge.network.UiPayloads;
import dev.mineagent.runtime.worker.generation.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** Coordinates real clients. Fixture messages never write shared state, approve a UI action or impersonate an actor. */
@EventBusSubscriber(modid="mineagent_runtime")
public final class SharedMultiplayerSmokeServer {
    private static final String RUN=UUID.randomUUID().toString();private static final ObjectMapper JSON=new ObjectMapper();
    private static final Map<String,UUID> players=new LinkedHashMap<>();private static final Map<String,Integer> joins=new LinkedHashMap<>();
    private static final Set<String> ready=new HashSet<>(),submitted=new HashSet<>(),probed=new HashSet<>(),done=new HashSet<>();
    private static final Map<String,Map<String,Object>> enters=new LinkedHashMap<>();private static final List<Map<String,Object>> requests=new ArrayList<>();
    private static UUID instance,object;private static int ticks,finishTick;private static long goAt;private static boolean finished,failed;
    private SharedMultiplayerSmokeServer(){}
    public static boolean enabled(){return Boolean.getBoolean("mineagent.sharedMultiplayerServer");}
    private static String role(ServerPlayer p){return p.nameAndId().name().equals("SharedA")?"A":p.nameAndId().name().equals("SharedB")?"B":"";}
    private static Path root(MinecraftServer s){return s.getServerDirectory().resolve("shared-multiplayer-evidence").resolve(RUN);}
    private static void write(MinecraftServer s,String name,Object value)throws Exception{Files.createDirectories(root(s));Files.writeString(root(s).resolve(name),JSON.writeValueAsString(value));}
    @SubscribeEvent public static void login(PlayerEvent.PlayerLoggedInEvent e){if(enabled()&&e.getEntity() instanceof ServerPlayer p&&!role(p).isEmpty()){players.put(role(p),p.getUUID());joins.merge(role(p),1,Integer::sum);}}
    private static GeneratedFile file(String path,RuntimeResourceSide side,String media,String value){byte[] bytes=value.getBytes(StandardCharsets.UTF_8);return new GeneratedFile(path,side,media,dev.mineagent.runtime.core.objects.RuntimeModelBundle.hash(bytes),bytes);}
    private static void prepare(MinecraftServer server,ServerPlayer owner)throws Exception{
        if(!server.isDedicatedServer())throw new IllegalStateException("SHARED_MULTI_REQUIRES_DEDICATED");
        var config=MineAgentRuntimeServices.config(server);var actions=new HashSet<>(MineAgentRuntimeServices.permissions(server).trustedActions(owner.getUUID()));actions.addAll(Set.of(PermissionAction.RUN_CODE,PermissionAction.MANAGE_PACKAGES));
        if(!config.apply(new dev.mineagent.runtime.api.config.ConfigPatch(config.snapshot().revision(),Map.of("runtime.initialized","true","voice.output.enabled","false","permission.player."+owner.getUUID(),actions.stream().map(Enum::name).sorted().collect(java.util.stream.Collectors.joining(",")))),true).accepted())throw new IllegalStateException("SHARED_MULTI_SETUP_CONFIG");
        MineAgentRuntimeServices.permissions(server).setTrustedActions(owner.getUUID(),actions);
        for(int x=0;x<=15;x++)for(int z=0;z<=13;z++){server.overworld().setBlockAndUpdate(new BlockPos(x,99,z),net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());for(int y=100;y<=106;y++)server.overworld().setBlockAndUpdate(new BlockPos(x,y,z),net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());}
        for(String role:List.of("A","B")){var p=server.getPlayerList().getPlayer(players.get(role));p.setGameMode(net.minecraft.world.level.GameType.CREATIVE);p.getAbilities().flying=true;p.onUpdateAbilities();p.teleportTo(server.overworld(),role.equals("A")?7.2:9.8,100,9,Set.of(),180,8,true);}
        var source=SharedMultiplayerFixture.SCRIPT.replace("OWNER_SENTINEL","OWNER_ONLY_"+RUN);
        var script=file("server/main.js",RuntimeResourceSide.SERVER,"application/javascript",source);var model=file("models/console.json",RuntimeResourceSide.COMMON,"application/json",SharedMultiplayerFixture.MODEL);var page=file("ui/index.html",RuntimeResourceSide.CLIENT,"text/html",SharedMultiplayerFixture.PAGE);
        var placement=file("ui/view-settings.json",RuntimeResourceSide.CLIENT,"application/json","{\"schema\":1,\"entries\":{\"ui/index.html\":{\"anchor\":\"CENTER\",\"width\":650,\"height\":550,\"appearance\":\"GLASS_SAGE\"}}}");
        var entry=new RuntimeEntrypoint(script.path(),script.side(),script.sha256());var definition=new RuntimeDefinition(UUID.randomUUID(),"Shared transaction test object",RuntimeDefinitionKind.ENTITY,"server",Set.of(model.path()),Map.of(),1);
        var pack=ServerPackageRuntime.get(server).importOwned(owner,UUID.randomUUID(),new ParsedRuntimePackage("双玩家共享状态 · 显式验收包","1",RuntimePackageType.CONTENT,ActivationMode.HOT_RUNTIME,Map.of(),Set.of("RUN_CODE","state.shared"),Map.of("server",entry,"server.restore",entry,"ui",new RuntimeEntrypoint(page.path(),page.side(),page.sha256())),List.of(definition),List.of(script,model,page,placement)));
        var world=WorldContentRuntime.get(server);var activation=world.activate(owner,UUID.randomUUID(),pack.packageId(),pack.revision(),definition.definitionId(),new RuntimeInstanceLocation("minecraft:overworld",8.5,100,6.5,0,0),true,false);
        if(!activation.state().equals("ACTIVE"))throw new IllegalStateException("SHARED_MULTI_ACTIVATION_FAILED");instance=activation.instanceId();object=JSON.readValue(world.instance(instance).orElseThrow().state().get("_object.console"),WorldContentRuntime.ObjectPart.class).entity();
        write(server,"fixture.json",Map.of("run",RUN,"world",MineAgentRuntimeServices.worldId(server),"instance",instance,"object",object,"package",pack,"players",players,"dedicated",true,"source","EXPLICIT_IMPORT_NOT_MODEL_GENERATED","providerCalls",0));
        Files.writeString(root(server).resolve("server.js"),source);Files.writeString(root(server).resolve("page.html"),SharedMultiplayerFixture.PAGE);
    }
    @SubscribeEvent public static void tick(ServerTickEvent.Post event)throws Exception{
        if(!enabled()||failed)return;var server=event.getServer();ticks++;
        try{
            if(instance==null&&players.size()==2&&players.values().stream().allMatch(id->server.getPlayerList().getPlayer(id)!=null))prepare(server,server.getPlayerList().getPlayer(players.get("A")));
            if(ready.size()==2&&goAt==0){goAt=System.currentTimeMillis()+3000;write(server,"barrier.json",Map.of("goAt",goAt,"ready",ready,"players",players));}
            if(done.size()==2&&!finished){var database=verifyDatabase(server,true);if(joins.getOrDefault("A",0)!=2||joins.getOrDefault("B",0)!=1||enters.size()!=2)throw new IllegalStateException("SHARED_MULTI_SEQUENCE");
                long min=enters.values().stream().mapToLong(x->((Number)x.get("receivedAt")).longValue()).min().orElseThrow(),max=enters.values().stream().mapToLong(x->((Number)x.get("receivedAt")).longValue()).max().orElseThrow();
                if(max-min>1000||min<goAt||enters.values().stream().map(x->x.get("worldRevision")).distinct().count()!=1)throw new IllegalStateException("SHARED_MULTI_SUBMISSIONS_NOT_CONCURRENT_WINDOW");
                write(server,"result.json",Map.of("status","DEDICATED_TWO_REAL_CLIENT_SHARED_STATE_VERIFIED","database",database,"joins",joins,"enters",enters,"requestTelemetry",requests,"submissionWindowMillis",max-min,"goAt",goAt,"providerCalls",0,"systemInputInjected",false,"fullV1",false));finished=true;finishTick=ticks;MineAgentRuntimeMod.LOGGER.info("MINEAGENT_SHARED_MULTI_SERVER_OK run={}",RUN);
            }
            if(finished&&ticks-finishTick>80)server.halt(false);if(ticks>14000)throw new IllegalStateException("SHARED_MULTI_SERVER_TIMEOUT");
        }catch(Exception e){failed=true;write(server,"failure.json",Map.of("error",e.toString(),"players",players,"ready",ready,"submitted",submitted,"done",done));server.halt(false);throw e;}
    }
    public static void observe(MinecraftServer server,Launch launch,Request request)throws Exception{
        if(!enabled()||!Objects.equals(instance,launch.instanceId()))return;var player=server.getPlayerList().getPlayer(launch.viewerId());String role=player==null?"":role(player);if(role.isEmpty()||!launch.actorId().equals(launch.viewerId()))throw new IllegalStateException("SHARED_MULTI_ACTOR");
        String action=request.arguments().get("name");if(requests.size()>=64)throw new IllegalStateException("SHARED_MULTI_REQUEST_BUDGET");var data=Map.<String,Object>of("role",role,"viewer",player.getUUID(),"actor",launch.actorId(),"operationId",request.operationId(),"sessionId",request.sessionId(),"action",action,"worldRevision",request.arguments().get("expectedRevision"),"tick",server.getTickCount(),"receivedAt",System.currentTimeMillis());requests.add(data);if(action.equals("enter"))enters.putIfAbsent(role,data);
        write(server,"request-telemetry.json",requests);
    }
    public static void handle(ServerPlayer player,UiPayloads.Command packet)throws Exception{
        try{handleMessage(player,packet);}catch(Exception e){failed=true;write(player.level().getServer(),"coordination-failure.json",Map.of("error",e.toString()));player.level().getServer().halt(false);throw e;}
    }
    private static void handleMessage(ServerPlayer player,UiPayloads.Command packet)throws Exception{
        String role=role(player);if(!enabled()||role.isEmpty())throw new SecurityException("SHARED_MULTI_FIXTURE_SOURCE");
        var server=player.level().getServer();var args=JSON.readTree(packet.json());if(!args.isObject()||args.size()!=1||!args.path("action").isTextual())throw new IllegalArgumentException("SHARED_MULTI_FIXTURE_ARGUMENTS");
        String action=args.path("action").asText();if(!Set.of("info","ready","submitted","probed","done").contains(action))throw new IllegalArgumentException("SHARED_MULTI_FIXTURE_ACTION");
        if(action.equals("ready")){if(instance==null)throw new IllegalStateException("SHARED_MULTI_NOT_READY");ready.add(role);}if(action.equals("submitted")){submitted.add(role);if(submitted.size()==2)write(server,"before-cas-probes.json",verifyDatabase(server,false));}if(action.equals("probed")){probed.add(role);if(probed.size()==2)write(server,"before-reconnect.json",verifyDatabase(server,true));}if(action.equals("done")){verifyDatabase(server,true);done.add(role);}
        var value=new LinkedHashMap<String,Object>();value.put("run",RUN);value.put("role",role);value.put("ready",instance!=null);value.put("goAt",goAt);value.put("bothSubmitted",submitted.size()==2);value.put("bothProbed",probed.size()==2);value.put("aDone",done.contains("A"));value.put("aOnline",players.containsKey("A")&&server.getPlayerList().getPlayer(players.get("A"))!=null);
        if(instance!=null){value.put("instance",instance);value.put("object",object);var other=enters.get(role.equals("A")?"B":"A");if(other!=null)value.put("otherEnterOperation",other.get("operationId"));}
        PacketDistributor.sendToPlayer(player,new UiPayloads.Event(packet.requestId(),"sharedMultiFixture",JSON.writeValueAsString(value)));
    }
    private static Map<String,Object> verifyDatabase(MinecraftServer server,boolean includeCasProbes)throws Exception{
        String uri=server.getServerDirectory().resolve("mineagent-runtime-data/runtime.db").toAbsolutePath().toUri()+"?mode=ro";
        try(var db=DriverManager.getConnection("jdbc:sqlite:"+uri);var q=db.createStatement()){
            q.execute("PRAGMA query_only=ON");JsonNode ns;String namespace;
            try(var r=q.executeQuery("SELECT id,payload FROM mineagent_shared_namespaces_v1")){if(!r.next())throw new IllegalStateException("SHARED_MULTI_NAMESPACE_MISSING");namespace=r.getString(1);ns=JSON.readTree(r.getString(2));if(r.next())throw new IllegalStateException("SHARED_MULTI_NAMESPACE_COUNT");}
            if(!namespace.contains(instance.toString())||ns.path("revision").asInt()!=5||ns.path("records").path("count/").path("value").asInt()!=1)throw new IllegalStateException("SHARED_MULTI_CAPACITY");
            int entries=0;var hashes=new TreeMap<String,String>();for(String role:List.of("A","B")){var actor=players.get(role);var note=ns.path("records").path("note/"+actor).path("value");if(!note.asText().equals("PRIVATE_NOTE_"+role+"_"+RUN))throw new IllegalStateException("SHARED_MULTI_PRIVATE_NOTE");hashes.put(role,dev.mineagent.runtime.core.packages.RuntimePackageCanonicalizer.sha256(note.asText().getBytes(StandardCharsets.UTF_8)));if(ns.path("records").has("entry/"+actor))entries++;}
            if(entries!=1)throw new IllegalStateException("SHARED_MULTI_DUPLICATE_ENTRY");int operations=0,conflicts=0,revisionConflicts=0,events=0;var actualActors=new HashSet<String>();
            try(var r=q.executeQuery("SELECT payload FROM mineagent_shared_operations_v1")){while(r.next()){operations++;var op=JSON.readTree(r.getString(1));if(op.path("receipt").path("status").asText().equals("CONFLICT"))conflicts++;if(op.path("receipt").path("error").asText().equals("SHARED_REVISION_CONFLICT"))revisionConflicts++;if(op.path("context").path("actorKind").asText().equals("PLAYER"))actualActors.add(op.path("context").path("actor").asText());}}
            try(var r=q.executeQuery("SELECT COUNT(*) FROM mineagent_shared_outbox_v1")){if(r.next())events=r.getInt(1);}
            int minimum=includeCasProbes?8:6;int minimumRevisionConflicts=includeCasProbes?2:0;
            if(operations<minimum||operations>minimum+1||revisionConflicts<minimumRevisionConflicts||revisionConflicts>minimumRevisionConflicts+1||conflicts!=revisionConflicts+1||operations!=5+conflicts||events!=5||!actualActors.equals(new HashSet<>(players.values().stream().map(UUID::toString).toList())))throw new IllegalStateException("SHARED_MULTI_RECEIPT_OR_AUTHOR");
            if(includeCasProbes)for(String role:List.of("A","B")){
                var request=requests.stream().filter(x->x.get("role").equals(role)&&x.get("action").equals("cas")).findFirst().orElseThrow(()->new IllegalStateException("SHARED_MULTI_REAL_CAS_REQUEST_MISSING"));
                UUID operation=UUID.nameUUIDFromBytes(("package-shared|"+instance+"|shared_app|"+request.get("operationId")+"|form-cas").getBytes(StandardCharsets.UTF_8));
                try(var lookup=db.prepareStatement("SELECT payload FROM mineagent_shared_operations_v1 WHERE id=?")){lookup.setString(1,operation.toString());try(var row=lookup.executeQuery()){if(!row.next())throw new IllegalStateException("SHARED_MULTI_REAL_CAS_RECEIPT_MISSING");var record=JSON.readTree(row.getString(1));if(!record.path("context").path("actor").asText().equals(players.get(role).toString())||!record.path("context").path("actorKind").asText().equals("PLAYER")||!record.path("receipt").path("error").asText().equals("SHARED_REVISION_CONFLICT")||record.path("receipt").path("revision").asInt()!=5)throw new IllegalStateException("SHARED_MULTI_CAS_AUTHOR_OR_RESULT");}}
            }
            return Map.of("readOnly",true,"namespace",namespace,"revision",5,"count",1,"privateNotes",hashes,"entries",1,"operations",operations,"conflicts",conflicts,"outboxEvents",5,"realActors",actualActors);
        }
    }
}
