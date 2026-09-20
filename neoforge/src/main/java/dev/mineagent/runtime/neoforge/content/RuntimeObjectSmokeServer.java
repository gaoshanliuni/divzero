package dev.mineagent.runtime.neoforge.content;
import dev.mineagent.runtime.api.packages.*;
import dev.mineagent.runtime.api.permission.PermissionAction;
import dev.mineagent.runtime.neoforge.*;
import dev.mineagent.runtime.neoforge.body.MineAgentPlayer;
import dev.mineagent.runtime.neoforge.ui.ServerPackageRuntime;
import dev.mineagent.runtime.worker.generation.*;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Explicit imported geometry/physics fixture. This is not an AI-generated artifact or a fixed production object. */
@EventBusSubscriber(modid="mineagent_runtime")
public final class RuntimeObjectSmokeServer {
    public static volatile List<UUID> objects=List.of();public static volatile UUID instance,ysmAgent;public static volatile boolean ready,closedGui,interactionDone,verified,finish,disabled;public static volatile String failure;
    private static UUID activation;private static int started,closedAt=-1;private static String beforePulse;private static final com.fasterxml.jackson.databind.ObjectMapper JSON=new com.fasterxml.jackson.databind.ObjectMapper();
    private static int disabledAt;private static boolean freezeRecorded;
    public static volatile List<RuntimeObjectFreezeProof.Pose> frozenServer=List.of();public static volatile boolean frozenServerVerified;
    public static boolean enabled(){return Boolean.getBoolean("mineagent.runtimeObjectSmoke");}
    private static final String STATIC="{\"version\":1,\"boxes\":[{\"from\":[-0.6,0,-0.6],\"to\":[0.6,0.2,0.6],\"color\":\"#538c85\"},{\"from\":[-0.15,0.2,-0.15],\"to\":[0.15,1.5,0.15],\"color\":\"#bfcab3\"}],\"vertices\":[[-0.7,1.4,-0.5,0,0],[0.7,1.4,-0.5,1,0],[0,2.2,0,0.5,1],[-0.7,1.4,0.5,0,0],[0.7,1.4,0.5,1,0]],\"triangles\":[[0,1,2,\"#ffc66d\"],[1,4,2,\"#d88476\"],[4,3,2,\"#77b9af\"],[3,0,2,\"#a5bd76\"]],\"collision\":[-0.6,0,-0.6,0.6,2.2,0.6]}";
    private static final String DYNAMIC=sphere();
    private static final String SCRIPT="""
        'use strict';
        on('instance.create',function(){
          content.createObject('stand','models/stand.json',0,0,0);
          var rejected=false;
          try{content.createObject('overlap_same_tick','models/stand.json',0.1,0,0);}catch(error){rejected=String(error).indexOf('OBJECT_POSITION_OCCUPIED')>=0;}
          if(!rejected)throw new Error('OBJECT_SAME_TICK_COLLISION_NOT_REJECTED');
          content.state('sameTickCollision','REJECTED');
          content.createObject('free','models/body.json',2,3,0);
          var spring=content.createObject('spring','models/body.json',-2,2,0);
          spring.spring(instance.location().x()-2,instance.location().y()+3,instance.location().z(),0.08,0.1);
          content.state('pulse','0');content.state('clicks','0');
          content.state('scheduled','0');schedule(3,function(){
            var rejected=false;
            try{content.createObject('overlap_loaded','models/stand.json',0.1,0,0);}catch(error){rejected=String(error).indexOf('OBJECT_POSITION_OCCUPIED')>=0;}
            if(!rejected)throw new Error('OBJECT_LOADED_COLLISION_NOT_REJECTED');
            content.state('loadedCollision','REJECTED');content.state('scheduled','1');
          });
        });
        on('instance.restore',function(){
          content.createObject('stand','models/stand.json',0,0,0);
          content.createObject('free','models/body.json',2,3,0);
          content.createObject('spring','models/body.json',-2,2,0);
        });
        on('tick',function(tick){if(content.objectCount()===3)content.object('stand').setYRot(Number(tick)*2);if(Number(tick)%20===0)content.state('pulse',String(Number(content.state('pulse'))+1));});
        on('object.interact',function(event){if(String(event.part())==='stand'){content.state('clicks',String(Number(content.state('clicks'))+1));content.object('free').velocity(0,0.45,0);}});
        """;
    private static GeneratedFile file(String path,String media,byte[] bytes,RuntimeResourceSide side){return new GeneratedFile(path,side,media,dev.mineagent.runtime.core.objects.RuntimeModelBundle.hash(bytes),bytes);}
    @SubscribeEvent public static void tick(ServerTickEvent.Post event)throws Exception{
        if(!enabled()||failure!=null)return;var server=event.getServer();var viewer=server.getPlayerList().getPlayers().stream().filter(p->!(p instanceof MineAgentPlayer)).findFirst().orElse(null);if(viewer==null)return;Path root=server.getServerDirectory().resolve("runtime-object-evidence");Files.createDirectories(root);
        if(disabled){
            var current=objects.stream().map(id->RuntimeObjectFreezeProof.sample((RuntimeObjectEntity)server.overworld().getEntity(id))).toList();
            if(!RuntimeObjectFreezeProof.sameServer(frozenServer,current)){failure="DISABLED_OBJECT_SERVER_MOVED";Files.writeString(root.resolve("freeze-server-failure.json"),JSON.writeValueAsString(Map.of("before",frozenServer,"after",current)));return;}
            if(!freezeRecorded&&server.getTickCount()-disabledAt>=50){Files.writeString(root.resolve("freeze-server-after.json"),JSON.writeValueAsString(objects.stream().map(id->state((RuntimeObjectEntity)server.overworld().getEntity(id))).toList()));freezeRecorded=true;frozenServerVerified=true;}return;
        }
        try{
            var runtime=WorldContentRuntime.get(server);
            if(instance==null){
                var policy=MineAgentRuntimeServices.permissions(server);policy.setTrustedActions(viewer.getUUID(),Set.of(PermissionAction.RUN_CODE,PermissionAction.MANAGE_PACKAGES));var config=MineAgentRuntimeServices.config(server);if(!config.apply(new dev.mineagent.runtime.api.config.ConfigPatch(config.snapshot().revision(),Map.of("permission.player."+viewer.getUUID(),"RUN_CODE,MANAGE_PACKAGES","voice.output.enabled","false")),true).accepted())throw new IllegalStateException("OBJECT_FIXTURE_PERMISSION");
                for(int x=492;x<=508;x++)for(int z=-6;z<=8;z++){server.overworld().setBlockAndUpdate(new BlockPos(x,79,z),Blocks.STONE.defaultBlockState());for(int y=80;y<=85;y++)server.overworld().setBlockAndUpdate(new BlockPos(x,y,z),Blocks.AIR.defaultBlockState());}
                viewer.setGameMode(net.minecraft.world.level.GameType.CREATIVE);viewer.getAbilities().flying=true;viewer.onUpdateAbilities();viewer.teleportTo(server.overworld(),500.5,81,4,Set.of(),180,10,true);
                var source=file("server/main.js","application/javascript",SCRIPT.getBytes(StandardCharsets.UTF_8),RuntimeResourceSide.SERVER);var stand=file("models/stand.json","application/json",STATIC.getBytes(StandardCharsets.UTF_8),RuntimeResourceSide.COMMON);var body=file("models/body.json","application/json",DYNAMIC.getBytes(StandardCharsets.UTF_8),RuntimeResourceSide.COMMON);var texture=file("models/checker.png","image/png",checker(),RuntimeResourceSide.COMMON);
                var entry=new RuntimeEntrypoint(source.path(),source.side(),source.sha256());var definition=new RuntimeDefinition(UUID.randomUUID(),"Imported kinetic geometry fixture",RuntimeDefinitionKind.ENTITY,"server",Set.of(stand.path(),body.path(),texture.path()),Map.of(),1);
                var parsed=new ParsedRuntimePackage("Explicit geometry and physics fixture","1.0",RuntimePackageType.CONTENT,ActivationMode.HOT_RUNTIME,Map.of(),Set.of("RUN_CODE"),Map.of("server",entry,"server.restore",entry),List.of(definition),List.of(source,stand,body,texture));
                var pack=ServerPackageRuntime.get(server).importOwned(viewer,UUID.randomUUID(),parsed);activation=UUID.randomUUID();var a=runtime.activate(viewer,activation,pack.packageId(),pack.revision(),definition.definitionId(),new RuntimeInstanceLocation("minecraft:overworld",500.5,80,0.5,0,0),true,true);
                Files.writeString(root.resolve("activation-attempt.json"),JSON.writeValueAsString(Map.of("activation",a,"instance",runtime.instance(a.instanceId()).orElseThrow())));
                if(!a.state().equals("ACTIVE"))throw new IllegalStateException("OBJECT_ACTIVATION_FAILED");instance=a.instanceId();
                var entities=new ArrayList<UUID>();var state=runtime.instance(instance).orElseThrow();for(String part:List.of("stand","free","spring")){var record=JSON.readValue(state.state().get("_object."+part),WorldContentRuntime.ObjectPart.class);entities.add(record.entity());}objects=List.copyOf(entities);started=server.getTickCount();
                Files.writeString(root.resolve("package.json"),JSON.writeValueAsString(pack));Files.writeString(root.resolve("source.js"),SCRIPT);Files.writeString(root.resolve("stand.json"),STATIC);Files.writeString(root.resolve("body.json"),DYNAMIC);Files.write(root.resolve("checker.png"),texture.content());Files.writeString(root.resolve("activation.json"),JSON.writeValueAsString(a));
            }
            var free=(RuntimeObjectEntity)server.overworld().getEntity(objects.get(1));var spring=(RuntimeObjectEntity)server.overworld().getEntity(objects.get(2));
            var ysm=new dev.mineagent.runtime.neoforge.integration.NeoForgeYsmRuntimeBridge(server);
            if(ysm.installed()&&ysmAgent==null&&ysm.runtimeAvailable()){
                var definition=MineAgentRuntimeServices.bodies(server).createPersistentAt("Object joint observer",viewer.getUUID(),server.overworld(),new net.minecraft.world.phys.Vec3(503.3,80,0.5));
                var result=dev.mineagent.runtime.neoforge.network.MineAgentNetwork.applyAppearanceFromUi(new dev.mineagent.runtime.neoforge.network.MineAgentPayloads.AppearanceCommand(definition.agentId().toString(),"default","blue","idle",0,UUID.randomUUID().toString()),viewer);
                if(!result.accepted())throw new IllegalStateException("OBJECT_JOINT_YSM_APPLY_FAILED");ysmAgent=definition.agentId();Files.writeString(root.resolve("ysm.json"),JSON.writeValueAsString(dev.mineagent.runtime.neoforge.network.MineAgentNetwork.readAppearanceFromUi(viewer,ysmAgent)));
            }
            if(free==null||spring==null||runtime.verifiedObjects(instance)!=3){
                if(server.getTickCount()-started<100)return;Files.writeString(root.resolve("missing-native.json"),JSON.writeValueAsString(Map.of("free",free!=null,"spring",spring!=null,"objects",runtime.verifiedObjects(instance),"active",runtime.active(instance),"activations",runtime.list(viewer.getUUID()))));throw new IllegalStateException("OBJECT_NATIVE_MISSING");
            }
            if(!ready&&server.getTickCount()-started>=100&&free.collisions()>1){var data=runtime.instance(instance).orElseThrow().state();if(!data.getOrDefault("scheduled","").equals("1"))throw new IllegalStateException("OBJECT_SCHEDULE_NOT_RUNNING");if(!data.getOrDefault("sameTickCollision","").equals("REJECTED")||!data.getOrDefault("loadedCollision","").equals("REJECTED")||data.containsKey("_object.overlap_same_tick")||data.containsKey("_object.overlap_loaded"))throw new IllegalStateException("OBJECT_COLLISION_ADMISSION_NOT_VERIFIED");ready=true;Files.writeString(root.resolve("physics.json"),JSON.writeValueAsString(Map.of("free",state(free),"spring",state(spring),"instance",runtime.instance(instance).orElseThrow(),"providerCalls",0)));}
            interactionDone=Integer.parseInt(runtime.instance(instance).orElseThrow().state().getOrDefault("clicks","0"))==1;
            if(closedGui&&closedAt<0){closedAt=server.getTickCount();beforePulse=runtime.instance(instance).orElseThrow().state().get("pulse");}
            if(closedAt>=0&&server.getTickCount()-closedAt>=80&&!verified){if(Integer.parseInt(runtime.instance(instance).orElseThrow().state().get("pulse"))<=Integer.parseInt(beforePulse)||!runtime.active(instance)||!interactionDone)throw new IllegalStateException("OBJECT_LIFECYCLE_NOT_INDEPENDENT");Files.writeString(root.resolve("verified.json"),JSON.writeValueAsString(Map.of("objects",objects,"free",state(free),"spring",state(spring),"pulseBefore",beforePulse,"instance",runtime.instance(instance).orElseThrow(),"mode","EXPLICIT_IMPORT_NATIVE_MODEL_PHYSICS_INTERACTION_NOT_GENERATION")));verified=true;}
            if(finish){runtime.disable(viewer,activation);Files.writeString(root.resolve("disabled.json"),JSON.writeValueAsString(Map.of("activation",runtime.list(viewer.getUUID()).stream().filter(a->a.operationId().equals(activation)).findFirst().orElseThrow(),"entityStillExists",server.overworld().getEntity(objects.getFirst())!=null)));Files.writeString(root.resolve("freeze-server-before.json"),JSON.writeValueAsString(objects.stream().map(id->state((RuntimeObjectEntity)server.overworld().getEntity(id))).toList()));frozenServer=objects.stream().map(id->RuntimeObjectFreezeProof.sample((RuntimeObjectEntity)server.overworld().getEntity(id))).toList();disabledAt=server.getTickCount();finish=false;disabled=true;}
        }catch(Exception e){failure=e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();Files.writeString(root.resolve("failure.json"),JSON.writeValueAsString(Map.of("error",failure)));}
    }
    private static Map<String,Object> state(RuntimeObjectEntity e){return Map.of("id",e.getUUID(),"x",e.getX(),"y",e.getY(),"z",e.getZ(),"yaw",e.getYRot(),"velocity",List.of(e.getDeltaMovement().x,e.getDeltaMovement().y,e.getDeltaMovement().z),"collisions",e.collisions(),"active",e.runtimeActive(),"header",e.header());}
    private static String sphere(){try{
        int rings=24,slices=48;var vertices=new ArrayList<List<Double>>();var triangles=new ArrayList<List<Object>>();vertices.add(List.of(0.0,0.6,0.0,0.5,0.0));
        for(int i=1;i<rings;i++)for(int j=0;j<=slices;j++){double p=Math.PI*i/rings,a=2*Math.PI*j/slices;vertices.add(List.of(0.3*Math.sin(p)*Math.cos(a),0.3+0.3*Math.cos(p),0.3*Math.sin(p)*Math.sin(a),(double)j/slices,(double)i/rings));}
        int bottom=vertices.size();vertices.add(List.of(0.0,0.0,0.0,0.5,1.0));
        for(int j=0;j<slices;j++){triangles.add(List.of(0,1+j+1,1+j,"#ffffff"));int last=1+(rings-2)*(slices+1);triangles.add(List.of(bottom,last+j,last+j+1,"#ffffff"));}
        for(int i=0;i<rings-2;i++)for(int j=0;j<slices;j++){int a=1+i*(slices+1)+j,b=a+slices+1;triangles.add(List.of(a,a+1,b,"#ffffff"));triangles.add(List.of(a+1,b+1,b,"#ffffff"));}
        return JSON.writeValueAsString(Map.of("version",1,"vertices",vertices,"triangles",triangles,"texture","models/checker.png","collision",List.of(-0.3,0,-0.3,0.3,0.6,0.3),"physics",Map.of("dynamic",true,"mass",1,"gravity",0.04,"restitution",0.7,"drag",0.99)));
    }catch(Exception e){throw new IllegalStateException(e);}}
    private static byte[] checker()throws Exception{
        var out=new java.io.ByteArrayOutputStream();out.write(new byte[]{(byte)137,80,78,71,13,10,26,10});var header=java.nio.ByteBuffer.allocate(13).putInt(2).putInt(2).put((byte)8).put((byte)6).put((byte)0).put((byte)0).put((byte)0).array();chunk(out,"IHDR",header);
        var raw=new java.io.ByteArrayOutputStream();for(int y=0;y<2;y++){raw.write(0);for(int x=0;x<2;x++)raw.write((x+y)%2==0?new byte[]{(byte)230,(byte)153,60,(byte)255}:new byte[]{40,90,(byte)160,(byte)255});}var encoded=new java.io.ByteArrayOutputStream();try(var zip=new java.util.zip.DeflaterOutputStream(encoded)){zip.write(raw.toByteArray());}chunk(out,"IDAT",encoded.toByteArray());chunk(out,"IEND",new byte[0]);return out.toByteArray();
    }
    private static void chunk(java.io.ByteArrayOutputStream out,String type,byte[] bytes)throws Exception{var d=new java.io.DataOutputStream(out);byte[] name=type.getBytes(StandardCharsets.US_ASCII);d.writeInt(bytes.length);d.write(name);d.write(bytes);var crc=new java.util.zip.CRC32();crc.update(name);crc.update(bytes);d.writeInt((int)crc.getValue());}
}
