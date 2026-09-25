package dev.mineagent.runtime.neoforge.ui;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mineagent.runtime.neoforge.MineAgentRuntimeServices;
import dev.mineagent.runtime.neoforge.body.MineAgentPlayer;
import dev.mineagent.runtime.neoforge.integration.TwilightBossInterop;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.*;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Opt-in isolated test. Never enabled in a normal world. No synthetic model response. */
@EventBusSubscriber(modid="mineagent_runtime")
public final class NativeBossSmokeServer {
    private static final ObjectMapper JSON=new ObjectMapper();
    public static volatile boolean done;public static volatile String renderType="";public static volatile int captureSerial;
    private static String phase="BOOT";private static int index,at,start;private static UUID agent,template;private static boolean rejectJoin;
    private static CompletableFuture<Map<String,Object>> pending;private static ServerPlayer viewer;
    private static final List<Mob> entities=new ArrayList<>();private static final List<Object> evidence=new ArrayList<>();private static final List<String> failures=new ArrayList<>();
    private static final List<String> TYPES=new ArrayList<>();
    static{TYPES.add("minecraft:zombie");TYPES.add("minecraft:cow");for(var b:TwilightBossInterop.BOSSES)TYPES.add("twilightforest:"+b.id());TYPES.add("twilightforest:quest_ram");}
    public static boolean enabled(){return Boolean.getBoolean("mineagent.nativeBossSmoke");}
    private static Path root(){return viewer.level().getServer().getServerDirectory().resolve("native-boss-smoke");}
    private static void save(String name,Object data)throws Exception{Files.createDirectories(root());Files.writeString(root().resolve(name+".json"),JSON.writerWithDefaultPrettyPrinter().writeValueAsString(data));}
    private static void check(boolean ok,String message){if(!ok)throw new IllegalStateException(message);}
    private static CompletableFuture<Map<String,Object>> call(String tool,com.fasterxml.jackson.databind.JsonNode args){return ConversationAgentTools.execute(viewer,agent,UUID.randomUUID(),tool,args.toString(),()->true);}
    @SubscribeEvent public static void join(net.neoforged.neoforge.event.entity.EntityJoinLevelEvent e){if(enabled()&&rejectJoin&&!e.getLevel().isClientSide()&&e.getEntity().getName().getString().equals("测试zombie"))e.setCanceled(true);}
    @SubscribeEvent public static void tick(ServerTickEvent.Post event){
        if(!enabled()||done)return;var server=event.getServer();viewer=server.getPlayerList().getPlayers().stream().filter(p->!(p instanceof MineAgentPlayer)).findFirst().orElse(null);if(viewer==null)return;
        try{
            if(start==0)start=server.getTickCount();if(server.getTickCount()-start>18000)throw new IllegalStateException("NATIVE_BOSS_TIMEOUT_"+phase);
            if(phase.equals("BOOT")){
                check(net.neoforged.fml.ModList.get().isLoaded("twilightforest"),"REAL_TWILIGHT_REQUIRED");server.getPlayerList().op(viewer.nameAndId());viewer.setGameMode(net.minecraft.world.level.GameType.CREATIVE);server.setDifficulty(net.minecraft.world.Difficulty.NORMAL,true);
                server.getCommands().performPrefixedCommand(viewer.createCommandSourceStack(),"ai accept");server.getCommands().performPrefixedCommand(viewer.createCommandSourceStack(),"time set midnight");
                for(int x=-40;x<=40;x++)for(int z=-40;z<=40;z++)viewer.level().setBlock(new BlockPos(x,170,z),Blocks.SMOOTH_STONE.defaultBlockState(),2);
                viewer.teleportTo(viewer.level(),0,180,32,Set.of(),180,10,true);viewer.setNoGravity(true);
                agent=MineAgentRuntimeServices.bodies(server).createPersistentAt("原生复刻师",viewer.getUUID(),viewer.level(),new Vec3(25,171,25)).agentId();save("catalog",NativeEntityTemplates.inspect(viewer,JSON.createObjectNode().put("query","twilightforest:")));phase="DEFINE";
            }
            if(phase.equals("DEFINE")){
                if(index==TYPES.size()){phase="MODEL_START";return;}
                String type=TYPES.get(index);var source=JSON.createObjectNode().put("name","测试"+type.substring(type.indexOf(':')+1)).put("type",type);
                pending=call("define_native_entity",JSON.createObjectNode().put("source",source.toString()));phase="SPAWN";return;
            }
            if(phase.equals("SPAWN")&&pending.isDone()){
                var result=pending.join();check("APPLIED".equals(result.get("status")),"DEFINE_FAILED_"+result);template=UUID.fromString(JSON.valueToTree(result).path("template").path("id").asText());
                var args=JSON.createObjectNode().put("action","spawn").put("template_id",template.toString());args.putArray("position").add(0).add(TYPES.get(index).endsWith("ur_ghast")?181:171).add(0);pending=call("control_native_entity",args);phase="OBSERVE";return;
            }
            if(phase.equals("OBSERVE")&&pending.isDone()){
                var receipt=pending.join();save("spawn-"+index,receipt);check("APPLIED".equals(receipt.get("status")),"SPAWN_FAILED_"+TYPES.get(index));
                entities.clear();for(var entry:JSON.valueToTree(receipt).path("entities")){var e=viewer.level().getEntity(UUID.fromString(entry.path("entity_id").asText()));check(e instanceof Mob,"NOT_NATIVE_MOB");entities.add((Mob)e);}
                check(entities.size()==(TYPES.get(index).endsWith("knight_phantom")?6:1),"GROUP_SIZE");at=server.getTickCount();renderType=TYPES.get(index);captureSerial++;phase="TICK";return;
            }
            if(phase.equals("TICK")&&server.getTickCount()-at>=100){
                var rows=new ArrayList<Object>();var ids=new HashSet<UUID>();
                for(var mob:entities){check(mob.isAlive()&&!mob.isNoAi()&&mob.tickCount>=90,"NATIVE_TICKS_"+TYPES.get(index));check(ids.add(mob.getUUID()),"DUPLICATE_UUID");
                    var output=TagValueOutput.createWithContext(ProblemReporter.DISCARDING,viewer.registryAccess());check(mob.save(output),"NATIVE_SAVE_FAILED");
                    Entity restored=mob.getType().create(viewer.level(),EntitySpawnReason.LOAD);check(restored!=null,"RESTORE_FACTORY");restored.load(TagValueInput.create(ProblemReporter.DISCARDING,viewer.registryAccess(),output.buildResult()));
                    check(mob.getUUID().equals(restored.getUUID())&&mob.getClass()==restored.getClass(),"NATIVE_IDENTITY_RESTORE");
                    check(NativeEntityTemplates.observe(restored).get("template_id").equals(template.toString()),"TEMPLATE_METADATA_RESTORE");
                    check((mob.getParts()==null?0:mob.getParts().length)==(restored.getParts()==null?0:restored.getParts().length),"MULTIPART_RESTORE");
                    rows.add(Map.of("live",NativeEntityTemplates.observe(mob),"restored",NativeEntityTemplates.observe(restored),"nativeSaveLoad",true));restored.discard();
                }
                evidence.add(Map.of("type",TYPES.get(index),"status","SPAWN_TICK_SERIALIZATION_PASSED","entities",rows,"fullCombatVerified",false));save("progress",evidence);
                for(var mob:entities)mob.discard();entities.clear();
                if(index==0){rejectJoin=true;var args=JSON.createObjectNode().put("action","spawn").put("template_id",template.toString());args.putArray("position").add(10).add(171).add(0);pending=call("control_native_entity",args);phase="JOIN_CANCEL";return;}
                index++;phase="DEFINE";return;
            }
            if(phase.equals("JOIN_CANCEL")&&pending.isDone()){rejectJoin=false;var r=pending.join();save("join-cancel",r);check("UNKNOWN".equals(r.get("status"))&&Boolean.FALSE.equals(r.get("replayAllowed")),"JOIN_CANCEL_FALSE_SUCCESS");index++;phase="DEFINE";return;}
            if(phase.equals("MODEL_START")){
                if(Boolean.getBoolean("mineagent.nativeBossZeroModel")){finish();return;}
                ServerConversations.get(server).submitNative(viewer,agent,"请在坐标(0,171,0)复刻一只新的暮色森林娜迦，名字叫青玉守卫。我要真实完整身体与原生行为，不是只借用头部模型；不要修改其它实体，保持原生AI开启。创建独立可再用模板，然后生成一只，读取确认真实类型、多部件和模板归属。不要用游戏命令或电脑命令。",true);phase="MODEL";return;
            }
            if(phase.equals("MODEL")){
                var store=ServerConversations.get(server).store();var cs=store.list(viewer.getUUID(),agent,"ACTIVE","",0,20).conversations();if(cs.isEmpty()||cs.getFirst().messageCount()<2||!cs.getFirst().activeOperation().isEmpty())return;
                var ctx=store.context(viewer.getUUID(),agent,cs.getFirst().conversationId(),null).orElseThrow();save("model-context",ctx);check(ctx.requestState().equals("COMPLETE"),"MODEL_"+ctx.errorCode());
                var matches=new ArrayList<Entity>();for(var e:viewer.level().getAllEntities())if(e.getName().getString().equals("青玉守卫"))matches.add(e);
                check(matches.size()==1,"MODEL_ENTITY_COUNT");var e=matches.getFirst();check(TwilightBossInterop.id(e).equals("twilightforest:naga")&&e.getParts()!=null&&e.getParts().length==12,"MODEL_FULL_NAGA");check(!((Mob)e).isNoAi(),"MODEL_DISABLED_AI");check(!NativeEntityTemplates.observe(e).get("template_id").equals(""),"MODEL_TEMPLATE_MISSING");save("model-entity",EntityLogicTools.inspect(viewer,JSON.createObjectNode().put("entity_id",e.getUUID().toString())));finish();
            }
        }catch(Exception failure){failures.add(failure.toString());try{finish();}catch(Exception ignored){done=true;}}
    }
    private static void finish()throws Exception{save("server-final",Map.of("status",failures.isEmpty()?"PASSED":"FAILED","failures",failures,"nativeTypes",evidence,"fullCombatVerified",false,"sourceModVersion",net.neoforged.fml.ModList.get().getModContainerById("twilightforest").orElseThrow().getModInfo().getVersion().toString()));done=true;}
    private NativeBossSmokeServer(){}
}
