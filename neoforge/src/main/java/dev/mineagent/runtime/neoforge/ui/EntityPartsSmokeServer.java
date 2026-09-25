package dev.mineagent.runtime.neoforge.ui;
import com.fasterxml.jackson.databind.*;
import dev.mineagent.runtime.core.interaction.EntityAnimationSpec;
import dev.mineagent.runtime.neoforge.MineAgentRuntimeServices;
import dev.mineagent.runtime.neoforge.body.MineAgentPlayer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

@EventBusSubscriber(modid="mineagent_runtime")
public final class EntityPartsSmokeServer {
    private static final ObjectMapper JSON=new ObjectMapper();
    public static volatile boolean done;public static volatile String phase="BOOT";
    public static volatile UUID sheepId,controlId;public static volatile int capture,captured;
    private static Sheep sheep,control;private static ServerPlayer player;private static UUID agent,rule;private static long revision;private static int start,at;
    private static CompletableFuture<Map<String,Object>> pending;private static final List<String> failures=new ArrayList<>();
    public static boolean enabled(){return Boolean.getBoolean("mineagent.entityPartsSmoke");}
    private static Path root(){return player.level().getServer().getServerDirectory().resolve("entity-parts-smoke");}
    public static void save(String name,Object data)throws Exception{Files.createDirectories(root());Files.writeString(root().resolve(name+".json"),JSON.writerWithDefaultPrettyPrinter().writeValueAsString(data));}
    private static void check(boolean ok,String message){if(!ok)throw new IllegalStateException(message);}
    private static CompletableFuture<Map<String,Object>> call(String name,JsonNode args){return ConversationAgentTools.execute(player,agent,UUID.randomUUID(),name,args.toString(),()->true);}
    private static Sheep sheep(double x){var e=EntityType.SHEEP.create(player.level(),EntitySpawnReason.COMMAND);if(e==null)throw new IllegalStateException("SHEEP_FACTORY");e.snapTo(x,171,0,0,0);e.setNoAi(true);e.setPersistenceRequired();e.setColor(net.minecraft.world.item.DyeColor.WHITE);if(!player.level().addFreshEntity(e))throw new IllegalStateException("SHEEP_SPAWN");return e;}
    private static JsonNode probe(Map<String,Object> result){var data=JSON.valueToTree(result);check("OBSERVED_NATIVE_DRAW".equals(data.path("status").asText()),"DRAW_NOT_OBSERVED_"+result);return data;}
    @SubscribeEvent public static void tick(ServerTickEvent.Post event){if(!enabled()||done)return;var server=event.getServer();player=server.getPlayerList().getPlayers().stream().filter(p->!(p instanceof MineAgentPlayer)).findFirst().orElse(null);if(player==null)return;
        try{
            if(start==0)start=server.getTickCount();if(server.getTickCount()-start>12000)throw new IllegalStateException("ENTITY_PARTS_TIMEOUT_"+phase);
            if(phase.equals("BOOT")){
                server.getPlayerList().op(player.nameAndId());player.setGameMode(net.minecraft.world.level.GameType.CREATIVE);server.getCommands().performPrefixedCommand(player.createCommandSourceStack(),"ai accept");server.getCommands().performPrefixedCommand(player.createCommandSourceStack(),"time set noon");
                for(int x=-12;x<=12;x++)for(int z=-8;z<=12;z++)player.level().setBlock(new BlockPos(x,170,z),Blocks.SMOOTH_STONE.defaultBlockState(),2);
                player.teleportTo(player.level(),0,172,7,Set.of(),180,8,true);player.setNoGravity(true);sheep=sheep(-1.8);control=sheep(1.8);sheepId=sheep.getUUID();controlId=control.getUUID();
                agent=MineAgentRuntimeServices.bodies(server).createPersistentAt("模型工程师",player.getUUID(),player.level(),new Vec3(8,171,4)).agentId();at=server.getTickCount();phase="MODEL_START";return;
            }
            if(sheep!=null){sheep.setYRot(0);sheep.yBodyRot=0;sheep.setYHeadRot(phase.startsWith("ROTATED")?35:0);sheep.setXRot(phase.startsWith("ROTATED")?20:0);}
            if(phase.equals("MODEL_START")&&server.getTickCount()-at>40){
                if(Boolean.getBoolean("mineagent.entityPartsZeroModel")){
                    var saved=(com.fasterxml.jackson.databind.node.ObjectNode)JSON.readTree(Files.readString(server.getServerDirectory().resolve("entity-parts-saved-request.json")));
                    saved.put("entity_id",sheepId.toString());saved.remove(List.of("rule_id","expected_revision"));pending=call("replace_entity_part",saved);phase="SAVED_RULE";return;
                }
                ServerConversations.get(server).submitNative(player,agent,"请把这只原版羊（UUID "+sheepId+"）的头部替换为凋零中央头部，使用凋零原来的黑色纹理，保留羊的身体和羊毛，头部继续跟随羊转动。只修改这只羊，不要生成新的羊或真正的凋零，也不要修改旁边的羊。不用游戏命令或电脑命令。先确认源与目标部件路径，修改后读取实际渲染结果。",true);phase="MODEL";return;
            }
            if(phase.equals("SAVED_RULE")&&pending.isDone()){var r=pending.join();check("APPLIED".equals(r.get("status")),"SAVED_RULE_FAILED");rule=UUID.fromString(r.get("ruleId").toString());revision=((Number)r.get("revision")).longValue();at=server.getTickCount();phase="SAVED_WAIT";return;}
            if(phase.equals("SAVED_WAIT")&&server.getTickCount()-at>40){pending=EntityAnimationProbe.request(player,JSON.createObjectNode().put("entity_id",sheepId.toString()));phase="VERIFY";return;}
            if(phase.equals("MODEL")){
                var store=ServerConversations.get(server).store();var cs=store.list(player.getUUID(),agent,"ACTIVE","",0,20).conversations();if(cs.isEmpty()||cs.getFirst().messageCount()<2||!cs.getFirst().activeOperation().isEmpty())return;var context=store.context(player.getUUID(),agent,cs.getFirst().conversationId(),null).orElseThrow();save("model-context",context);check(context.requestState().equals("COMPLETE"),"MODEL_"+context.errorCode());
                var rows=JSON.valueToTree(ServerEntityInterop.inspectRules(player,0)).path("rules");for(var row:rows){var r=row.path("rule");if(r.path("kind").asText().equals("visual")){var spec=EntityAnimationSpec.parse(r.path("source").asText());if(spec.selector().entity().equals(sheepId.toString())&&spec.replacements().containsKey("root/head")){rule=UUID.fromString(r.path("id").asText());revision=r.path("revision").asLong();}}}
                check(rule!=null,"MODEL_DID_NOT_REPLACE_HEAD");pending=EntityAnimationProbe.request(player,JSON.createObjectNode().put("entity_id",sheepId.toString()));phase="VERIFY";return;
            }
            if(phase.equals("VERIFY")&&pending.isDone()){
                var r=probe(pending.join());save("replaced-draw",r);check(r.path("hiddenParts").toString().contains("root/head"),"ORIGINAL_HEAD_VISIBLE");check(r.path("replacementDraws").toString().contains("minecraft:wither")&&r.path("replacementDraws").toString().contains("root/center_head"),"WITHER_HEAD_NOT_DRAWN");
                long sheepCount=0,arenaSheep=0,witherCount=0;var arena=new net.minecraft.world.phys.AABB(-12,169,-8,12,180,12);for(var e:player.level().getAllEntities()){if(e.getType()==EntityType.SHEEP){sheepCount++;if(arena.contains(e.position()))arenaSheep++;}if(e.getType()==EntityType.WITHER)witherCount++;}
                save("entity-population",Map.of("allLoadedSheep",sheepCount,"arenaSheep",arenaSheep,"witherCount",witherCount));check(arenaSheep==2&&witherCount==0&&player.level().getEntity(sheepId)==sheep&&player.level().getEntity(controlId)==control,"DONOR_SPAWN_OR_SHEEP_RECREATED");check(sheep.getUUID().equals(sheepId)&&sheep.getType()==EntityType.SHEEP&&sheep.getHealth()==8&&sheep.isNoAi()&&!sheep.isSheared(),"SHEEP_GAMEPLAY_CHANGED");
                pending=EntityAnimationProbe.request(player,JSON.createObjectNode().put("entity_id",controlId.toString()));capture=1;phase="CONTROL";return;
            }
            if(phase.equals("CONTROL")&&pending.isDone()&&captured>=1){var r=probe(pending.join());save("control-draw",r);check(r.path("hiddenParts").isEmpty()&&r.path("replacementDraws").isEmpty(),"SHARED_MODEL_LEAK");sheep.setSheared(true);at=server.getTickCount();capture=2;phase="SHEARED";return;}
            if(phase.equals("SHEARED")&&server.getTickCount()-at>35&&captured>=2){pending=EntityAnimationProbe.request(player,JSON.createObjectNode().put("entity_id",sheepId.toString()));phase="SHEARED_PROBE";return;}
            if(phase.equals("SHEARED_PROBE")&&pending.isDone()){var r=probe(pending.join());save("sheared-draw",r);check(!r.path("replacementDraws").isEmpty(),"SHEARED_REPLACEMENT_LOST");sheep.setSheared(false);at=server.getTickCount();capture=3;phase="ROTATED";return;}
            if(phase.equals("ROTATED")&&server.getTickCount()-at>35&&captured>=3){pending=EntityAnimationProbe.request(player,JSON.createObjectNode().put("entity_id",sheepId.toString()));phase="ROTATED_PROBE";return;}
            if(phase.equals("ROTATED_PROBE")&&pending.isDone()){var r=probe(pending.join());save("rotated-draw",r);boolean rotated=false;for(var part:r.path("parts"))if(part.path("path").asText().equals("root/head")){var angles=part.path("renderedPose").path("rotationDegrees");rotated=Math.abs(angles.get(0).asDouble()-20)<2&&Math.abs(angles.get(1).asDouble()-35)<2;}check(rotated&&!r.path("replacementDraws").isEmpty(),"HEAD_ROTATION_NOT_RETAINED");pending=call("delete_entity_rule",JSON.createObjectNode().put("rule_id",rule.toString()).put("expected_revision",revision));phase="REMOVE";return;}
            if(phase.equals("REMOVE")&&pending.isDone()){check("REMOVED".equals(pending.join().get("status")),"RESTORE_RULE_FAILED");at=server.getTickCount();capture=4;phase="RESTORE";return;}
            if(phase.equals("RESTORE")&&server.getTickCount()-at>35&&captured>=4){pending=EntityAnimationProbe.request(player,JSON.createObjectNode().put("entity_id",sheepId.toString()));phase="RESTORE_PROBE";return;}
            if(phase.equals("RESTORE_PROBE")&&pending.isDone()){var r=probe(pending.join());save("restored-draw",r);check(r.path("hiddenParts").isEmpty()&&r.path("replacementDraws").isEmpty(),"HEAD_NOT_RESTORED");finish();}
        }catch(Exception e){failures.add(e.toString());try{finish();}catch(Exception ignored){done=true;}}
    }
    private static void finish()throws Exception{save("server-final",Map.of("status",failures.isEmpty()?"PASSED":"FAILED","failures",failures,"sheep",Objects.toString(sheepId),"control",Objects.toString(controlId),"screenshots",captured));done=true;}
    private EntityPartsSmokeServer(){}
}
