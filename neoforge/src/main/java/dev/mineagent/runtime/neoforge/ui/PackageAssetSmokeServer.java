package dev.mineagent.runtime.neoforge.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mineagent.runtime.api.agent.AgentMode;
import dev.mineagent.runtime.api.config.ConfigPatch;
import dev.mineagent.runtime.api.packages.*;
import dev.mineagent.runtime.api.permission.PermissionAction;
import dev.mineagent.runtime.core.packages.PackageAssetMetadata;
import dev.mineagent.runtime.core.packages.RuntimePackageCanonicalizer;
import dev.mineagent.runtime.neoforge.MineAgentRuntimeServices;
import dev.mineagent.runtime.neoforge.content.WorldContentRuntime;
import dev.mineagent.runtime.worker.generation.GeneratedFile;
import dev.mineagent.runtime.worker.generation.ParsedRuntimePackage;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

/** Three-JVM user-flow fixture for library alias, copy, fixed asset reuse and a real new-world instance. */
@EventBusSubscriber(modid="mineagent_runtime")
public final class PackageAssetSmokeServer {
    public static final String SOURCE_NAME="跨世界灯塔源包",ALIAS="跨世界灯塔资产",LOCAL_COPY="本世界独立副本",CROSS_COPY="第二世界复用副本";
    private static final String SCRIPT="""
            on('instance.create',function(){
              content.state('created','1');content.state('pulse','0');
              content.placeBlock('marker',0,0,0,'minecraft:emerald_block');
            });
            on('instance.restore',function(){
              content.placeBlock('marker',0,0,0,'minecraft:emerald_block');
              content.state('restores',String(Number(content.state('restores')||'0')+1));
            });
            on('tick',function(t){if(Number(t)%20===0)content.state('pulse',String(Number(content.state('pulse')||'0')+1));});
            """;
    private static final ObjectMapper JSON=new ObjectMapper();
    public static volatile UUID sourcePackage,definition,agent,sameWorldCopy,crossWorldCopy,shelf,sourceInstance,crossInstance;
    public static volatile boolean ready,sourceActive,aliasSaved,shelfSaved,withdrawn,restored,done;
    public static volatile String failure="";
    private static int ticks;private static boolean initialized,emptyWorldChecked;
    private PackageAssetSmokeServer(){}
    public static boolean enabled(){return Boolean.getBoolean("mineagent.packageAssetSmoke");}
    public static String stage(){return System.getProperty("mineagent.packageAssetStage","prepare");}
    private static Path root(net.minecraft.server.MinecraftServer server){return server.getServerDirectory().resolve("package-asset-smoke");}
    private static Path journal(net.minecraft.server.MinecraftServer server){return root(server).resolve("journal.json");}
    private static void require(boolean value,String code){if(!value)throw new IllegalStateException(code);}
    private static void save(net.minecraft.server.MinecraftServer server,String name,Object value)throws Exception{Files.createDirectories(root(server).resolve(stage()));Files.writeString(root(server).resolve(stage()).resolve(name),JSON.writeValueAsString(value));}
    private static RuntimePackage source(net.minecraft.server.MinecraftServer server,ServerPlayer viewer)throws Exception{
        byte[] bytes=SCRIPT.getBytes(StandardCharsets.UTF_8);String path="server/main.js",hash=RuntimePackageCanonicalizer.sha256(bytes);var file=new GeneratedFile(path,RuntimeResourceSide.SERVER,"application/javascript",hash,bytes);var entry=new RuntimeEntrypoint(path,RuntimeResourceSide.SERVER,hash);
        definition=UUID.randomUUID();var def=new RuntimeDefinition(definition,"可复用灯塔标记",RuntimeDefinitionKind.OTHER,"server",Set.of(),Map.of(),1);
        var compatibility=new NativeCompatibility(1,Map.of("SERVER",new NativeCompatibility.Target("26.1.2","neoforge","26.1.2.106","official",25,Map.of())));
        return ServerPackageRuntime.get(server).importOwned(viewer,UUID.randomUUID(),new ParsedRuntimePackage(SOURCE_NAME,"1.0.0",RuntimePackageType.CONTENT,ActivationMode.HOT_RUNTIME,Map.of(),Set.of("RUN_CODE"),Map.of("server",entry,"server.restore",entry),List.of(def),List.of(file),compatibility));
    }
    private static void authorize(net.minecraft.server.MinecraftServer server,ServerPlayer viewer)throws Exception{
        var grants=new HashSet<>(MineAgentRuntimeServices.permissions(server).trustedActions(viewer.getUUID()));grants.addAll(Set.of(PermissionAction.RUN_CODE,PermissionAction.MANAGE_PACKAGES,PermissionAction.START_TASK));String encoded=grants.stream().map(Enum::name).sorted().collect(Collectors.joining(","));var config=MineAgentRuntimeServices.config(server);
        if(!"true".equals(config.snapshot().values().get("runtime.initialized"))||!encoded.equals(config.snapshot().values().get("permission.player."+viewer.getUUID())))require(config.apply(new ConfigPatch(config.snapshot().revision(),Map.of("runtime.initialized","true","voice.output.enabled","false","permission.player."+viewer.getUUID(),encoded)),true).accepted(),"PACKAGE_ASSET_CONFIG");
        MineAgentRuntimeServices.permissions(server).setTrustedActions(viewer.getUUID(),grants);
    }
    private static RuntimePackage named(net.minecraft.server.MinecraftServer server,String name){return ServerPackageRuntime.get(server).worldLibrary().all().stream().filter(p->p.name().equals(name)).findFirst().orElse(null);}
    private static boolean assetParity(RuntimePackage source,RuntimePackage copy){return copy!=null&&copy.origin()==PackageOrigin.REUSED&&copy.type()==source.type()&&copy.version().equals(source.version())&&copy.activationMode()==source.activationMode()&&copy.dependencies().equals(source.dependencies())&&copy.permissions().equals(source.permissions())&&copy.entrypoints().equals(source.entrypoints())&&copy.definitions().equals(source.definitions())&&copy.resources().equals(source.resources())&&Objects.equals(copy.nativeCompatibility(),source.nativeCompatibility());}
    private static PackageAssetMetadata.Shelf shelf(net.minecraft.server.MinecraftServer server,UUID owner,boolean active)throws Exception {var page=ServerPackageRuntime.get(server).worldLibrary().shelves(owner,active,0);return page.items().stream().filter(s->sameWorldCopy!=null&&sameWorldCopy.equals(s.packageId())).findFirst().orElse(null);}
    private static void writeJournal(net.minecraft.server.MinecraftServer server,UUID owner,UUID world)throws Exception{
        var value=new LinkedHashMap<String,Object>();value.put("owner",owner);value.put("worldA",stage().equals("prepare")?world:JSON.readTree(journal(server).toFile()).path("worldA").asText());value.put("sourcePackage",sourcePackage);value.put("definition",definition);value.put("sameWorldCopy",sameWorldCopy);value.put("shelf",shelf);value.put("sourceInstance",sourceInstance);
        if(crossWorldCopy!=null){value.put("worldB",world);value.put("crossWorldCopy",crossWorldCopy);value.put("crossInstance",crossInstance);}Files.createDirectories(root(server));Files.writeString(journal(server),JSON.writeValueAsString(value));
    }
    private static void initialize(net.minecraft.server.MinecraftServer server,ServerPlayer viewer)throws Exception{
        authorize(server,viewer);var world=MineAgentRuntimeServices.worldId(server);var runtime=ServerPackageRuntime.get(server);var content=WorldContentRuntime.get(server);
        viewer.setGameMode(net.minecraft.world.level.GameType.CREATIVE);double x=stage().equals("prepare")?650.5:700.5;viewer.teleportTo(server.overworld(),x,121,.5,Set.of(),0,0,true);if(!stage().equals("verify")){server.overworld().setBlockAndUpdate(new BlockPos((int)Math.floor(x),119,0),Blocks.STONE.defaultBlockState());server.overworld().setBlockAndUpdate(new BlockPos((int)Math.floor(x),120,0),Blocks.AIR.defaultBlockState());}
        if(stage().equals("prepare")){
            require(content.list(viewer.getUUID()).isEmpty(),"PACKAGE_ASSET_WORLD_A_NOT_EMPTY");var pack=source(server,viewer);sourcePackage=pack.packageId();agent=MineAgentRuntimeServices.bodies(server).createPersistentAt("资产管理 Agent",viewer.getUUID(),server.overworld(),new Vec3(652,120,2)).agentId();writeJournal(server,viewer.getUUID(),world);save(server,"fixture.json",Map.of("source",pack,"agent",agent,"world",world,"providerCalls",0,"systemInputInjected",false));
        }else{
            JsonNode saved=JSON.readTree(journal(server).toFile());sourcePackage=UUID.fromString(saved.path("sourcePackage").asText());definition=UUID.fromString(saved.path("definition").asText());sameWorldCopy=UUID.fromString(saved.path("sameWorldCopy").asText());shelf=UUID.fromString(saved.path("shelf").asText());sourceInstance=UUID.fromString(saved.path("sourceInstance").asText());UUID owner=UUID.fromString(saved.path("owner").asText());require(owner.equals(viewer.getUUID())&&!saved.path("worldA").asText().equals(world.toString()),"PACKAGE_ASSET_WORLD_SCOPE");
            var local=runtime.worldLibrary().get(sameWorldCopy).orElseThrow();require(runtime.ownedPackage(viewer.getUUID(),sameWorldCopy,local.revision()).isEmpty(),"PACKAGE_ASSET_CROSS_WORLD_OWNERSHIP_LEAK");
            if(stage().equals("reuse")){require(content.list(viewer.getUUID()).isEmpty(),"PACKAGE_ASSET_WORLD_STATE_COPIED");emptyWorldChecked=true;agent=MineAgentRuntimeServices.bodies(server).createPersistentAt("跨世界复用 Agent",viewer.getUUID(),server.overworld(),new Vec3(702,120,2)).agentId();}
            else {crossWorldCopy=UUID.fromString(saved.path("crossWorldCopy").asText());crossInstance=UUID.fromString(saved.path("crossInstance").asText());agent=MineAgentRuntimeServices.bodies(server).definitions().stream().filter(a->a.ownerPlayerId().equals(viewer.getUUID())).map(a->a.agentId()).findFirst().orElse(null);}
        }ready=true;
    }
    private static void prepareProgress(net.minecraft.server.MinecraftServer server,ServerPlayer viewer)throws Exception{
        var runtime=ServerPackageRuntime.get(server);var library=runtime.worldLibrary();var content=WorldContentRuntime.get(server);var source=library.get(sourcePackage).orElseThrow();aliasSaved=library.alias(viewer.getUUID(),sourcePackage).name().equals(ALIAS);sameWorldCopy=named(server,LOCAL_COPY)==null?sameWorldCopy:named(server,LOCAL_COPY).packageId();var activeShelf=shelf(server,viewer.getUUID(),true);if(activeShelf!=null){shelfSaved=true;shelf=activeShelf.id();}
        var activation=content.list(viewer.getUUID()).stream().filter(a->a.packageId().equals(sourcePackage)&&a.state().equals("ACTIVE")).findFirst().orElse(null);if(activation!=null&&content.active(activation.instanceId())&&content.verifiedBlocks(activation.instanceId())==1){sourceActive=true;sourceInstance=activation.instanceId();}
        if(!sourceActive||!aliasSaved||sameWorldCopy==null||!shelfSaved)return;var copy=library.get(sameWorldCopy).orElseThrow();require(source.name().equals(SOURCE_NAME)&&assetParity(source,copy)&&!copy.enabled()&&copy.revision()==1,"PACKAGE_ASSET_LOCAL_COPY_PARITY");require(content.list(viewer.getUUID()).stream().noneMatch(a->a.packageId().equals(sameWorldCopy)),"PACKAGE_ASSET_LOCAL_COPY_EXECUTED");require(activeShelf.packageId().equals(copy.packageId())&&activeShelf.packageRevision()==copy.revision()&&activeShelf.canonical().equals(copy.canonicalSha256())&&activeShelf.sourceWorld().equals(MineAgentRuntimeServices.worldId(server)),"PACKAGE_ASSET_SHELF_SOURCE");
        writeJournal(server,viewer.getUUID(),MineAgentRuntimeServices.worldId(server));save(server,"result.json",Map.ofEntries(Map.entry("status","PACKAGE_ALIAS_COPY_AND_FIXED_ASSET_SAVED"),Map.entry("source",source),Map.entry("copy",copy),Map.entry("shelf",activeShelf),Map.entry("sourceInstance",sourceInstance),Map.entry("providerCalls",0),Map.entry("systemInputInjected",false),Map.entry("fullV1",false)));done=true;
    }
    private static void reuseProgress(net.minecraft.server.MinecraftServer server,ServerPlayer viewer)throws Exception{
        var library=ServerPackageRuntime.get(server).worldLibrary();var content=WorldContentRuntime.get(server);var found=named(server,CROSS_COPY);if(found!=null)crossWorldCopy=found.packageId();var inactive=shelf(server,viewer.getUUID(),false);var active=shelf(server,viewer.getUUID(),true);withdrawn|=inactive!=null&&inactive.revision()>=2;restored|=active!=null&&active.revision()>=3;
        if(crossWorldCopy!=null){var activation=content.list(viewer.getUUID()).stream().filter(a->a.packageId().equals(crossWorldCopy)&&a.state().equals("ACTIVE")).findFirst().orElse(null);if(activation!=null&&activation.autoRestore()&&content.active(activation.instanceId())&&content.verifiedBlocks(activation.instanceId())==1){crossInstance=activation.instanceId();crossWorldCopy=activation.packageId();}}
        if(!emptyWorldChecked||crossWorldCopy==null||crossInstance==null||!withdrawn||!restored)return;var source=library.assetSource(active);var copy=library.get(crossWorldCopy).orElseThrow();require(assetParity(source,copy),"PACKAGE_ASSET_CROSS_COPY_PARITY");var derivation=library.derivation(viewer.getUUID(),crossWorldCopy);require(derivation!=null&&derivation.action().equals("REUSE_ASSET")&&derivation.world().equals(MineAgentRuntimeServices.worldId(server))&&Objects.equals(derivation.shelf(),shelf),"PACKAGE_ASSET_CROSS_DERIVATION");require(server.overworld().getBlockState(new BlockPos(700,120,0)).is(Blocks.EMERALD_BLOCK),"PACKAGE_ASSET_REUSED_INSTANCE_BLOCK");writeJournal(server,viewer.getUUID(),MineAgentRuntimeServices.worldId(server));save(server,"result.json",Map.ofEntries(Map.entry("status","PACKAGE_ASSET_REUSED_IN_SECOND_WORLD"),Map.entry("copy",copy),Map.entry("derivation",derivation),Map.entry("instance",content.instance(crossInstance).orElseThrow()),Map.entry("shelf",active),Map.entry("providerCalls",0),Map.entry("systemInputInjected",false),Map.entry("fullV1",false)));done=true;
    }
    private static void verifyProgress(net.minecraft.server.MinecraftServer server,ServerPlayer viewer)throws Exception{
        var runtime=ServerPackageRuntime.get(server);var library=runtime.worldLibrary();var content=WorldContentRuntime.get(server);var activeShelf=shelf(server,viewer.getUUID(),true);if(activeShelf==null||activeShelf.revision()<3)return;var activations=content.list(viewer.getUUID());if(activations.size()!=1)return;var activation=activations.getFirst();if(!activation.packageId().equals(crossWorldCopy)||!activation.state().equals("ACTIVE")||!content.active(crossInstance)||content.verifiedBlocks(crossInstance)!=1)return;var instance=content.instance(crossInstance).orElseThrow();if(Integer.parseInt(instance.state().getOrDefault("restores","0"))<1)return;
        var source=library.get(sourcePackage).orElseThrow();var local=library.get(sameWorldCopy).orElseThrow();var copy=library.get(crossWorldCopy).orElseThrow();require(runtime.ownedPackage(viewer.getUUID(),sameWorldCopy,local.revision()).isEmpty()&&runtime.ownedPackage(viewer.getUUID(),crossWorldCopy,copy.revision()).isPresent(),"PACKAGE_ASSET_VERIFY_OWNERSHIP");require(library.alias(viewer.getUUID(),sourcePackage).name().equals(ALIAS)&&source.name().equals(SOURCE_NAME)&&assetParity(library.assetSource(activeShelf),copy),"PACKAGE_ASSET_VERIFY_METADATA");require(server.overworld().getBlockState(new BlockPos(700,120,0)).is(Blocks.EMERALD_BLOCK),"PACKAGE_ASSET_VERIFY_BLOCK");
        save(server,"result.json",Map.ofEntries(Map.entry("status","REAL_CEF_PACKAGE_ALIAS_COPY_CROSS_WORLD_REUSE_INSTANCE_VERIFIED"),Map.entry("world",MineAgentRuntimeServices.worldId(server)),Map.entry("source",sourcePackage),Map.entry("sameWorldCopy",sameWorldCopy),Map.entry("crossWorldCopy",crossWorldCopy),Map.entry("crossInstance",crossInstance),Map.entry("activation",activation),Map.entry("instance",instance),Map.entry("shelf",activeShelf),Map.entry("providerCalls",0),Map.entry("systemInputInjected",false),Map.entry("fullV1",false)));done=true;
    }
    @SubscribeEvent public static void tick(ServerTickEvent.Post event)throws Exception{
        if(!enabled()||done||!failure.isEmpty())return;var server=event.getServer();ticks++;var viewer=server.getPlayerList().getPlayers().stream().filter(p->!(p instanceof dev.mineagent.runtime.neoforge.body.MineAgentPlayer)).findFirst().orElse(null);if(viewer==null)return;
        try{if(ticks>10000)throw new IllegalStateException("PACKAGE_ASSET_TIMEOUT_"+stage());if(!initialized){initialized=true;initialize(server,viewer);}switch(stage()){case "prepare"->prepareProgress(server,viewer);case "reuse"->reuseProgress(server,viewer);case "verify"->verifyProgress(server,viewer);default->throw new IllegalArgumentException("PACKAGE_ASSET_STAGE");}}
        catch(Exception error){failure=Objects.toString(error.getMessage(),error.getClass().getSimpleName());save(server,"failure.json",Map.of("stage",stage(),"ticks",ticks,"error",error.toString()));}
    }
}
