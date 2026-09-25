package dev.mineagent.runtime.neoforge.ui;

import com.fasterxml.jackson.databind.*;
import dev.mineagent.runtime.core.interaction.*;
import dev.mineagent.runtime.core.persistence.SqliteRuntimeRepository;
import dev.mineagent.runtime.neoforge.*;
import dev.mineagent.runtime.neoforge.content.RuntimeCreatureEntity;
import dev.mineagent.runtime.neoforge.mixin.EntityGoalsAccess;
import dev.mineagent.runtime.neoforge.network.UiPayloads;
import net.minecraft.server.MinecraftServer;import net.minecraft.server.level.*;
import net.minecraft.server.permissions.Permissions;import net.minecraft.world.entity.*;import net.minecraft.world.entity.ai.goal.*;import net.minecraft.world.entity.player.Player;
import net.minecraft.core.registries.BuiltInRegistries;import net.minecraft.resources.Identifier;import net.minecraft.world.phys.Vec3;import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.*;import net.neoforged.fml.common.EventBusSubscriber;import net.neoforged.neoforge.entity.PartEntity;
import net.neoforged.neoforge.event.entity.player.*;import net.neoforged.neoforge.event.entity.living.*;
import java.util.*;import java.util.concurrent.*;

/** Persistent native event policies; absence of rules leaves the original execution path untouched. */
@EventBusSubscriber(modid="mineagent_runtime")
public final class ServerEntityInterop {
 public record Rule(UUID id,UUID owner,long revision,String kind,String source){}
 private static final ObjectMapper JSON=new ObjectMapper();private static final Map<MinecraftServer,State> LIVE=new WeakHashMap<>();private static final ExecutorService IO=Executors.newVirtualThreadPerTaskExecutor();
 private static final ThreadLocal<Boolean> CALLBACK=ThreadLocal.withInitial(()->false);
 private record RemovedGoal(GoalSelector selector,int priority,Goal goal){}
 private static final class State implements AutoCloseable {
  final SqliteRuntimeRepository db;final UUID world;final Map<UUID,Rule> rules=new LinkedHashMap<>();final Map<UUID,EntityLogicSpec> logic=new HashMap<>();final Map<UUID,Map<String,Long>> counts=new HashMap<>();final Map<String,Long> cooldown=new HashMap<>();final Map<Mob,List<RemovedGoal>> removed=new IdentityHashMap<>();final Set<UUID> writing=new HashSet<>();boolean closed;
  State(MinecraftServer server)throws Exception{world=MineAgentRuntimeServices.worldId(server);db=new SqliteRuntimeRepository(server.getServerDirectory().resolve("mineagent-runtime-data/runtime.db"));for(var row:db.list(world,"entity_interop_v1")){Rule r=JSON.readValue(row.payload(),Rule.class);install(new Rule(r.id(),r.owner(),row.revision(),r.kind(),r.source()));}}
  void install(Rule r){rules.put(r.id(),r);if(r.kind().equals("logic"))logic.put(r.id(),EntityLogicSpec.parse(r.source()));else EntityAnimationSpec.parse(r.source());}
  public void close()throws Exception{closed=true;db.close();}
 }
 private static State state(MinecraftServer server){if(!server.isSameThread())throw new IllegalStateException("ENTITY_SERVER_THREAD");return LIVE.computeIfAbsent(server,s->{try{return new State(s);}catch(Exception e){throw new IllegalStateException("ENTITY_RULE_STORE",e);}});}
 public static Entity root(Entity e){return e instanceof PartEntity<?> p?p.getParent():e;}
 public static int partIndex(Entity e){if(!(e instanceof PartEntity<?>))return -1;var parent=root(e);var parts=parent.getParts();if(parts!=null)for(int i=0;i<parts.length;i++)if(parts[i]==e)return i;return -1;}
 public static boolean matches(dev.mineagent.runtime.core.interaction.EntitySelector selector,Entity e){var r=root(e);return selector.matches(r.level().dimension().identifier().toString(),r.getUUID().toString(),BuiltInRegistries.ENTITY_TYPE.getKey(r.getType()).toString(),r instanceof RuntimeCreatureEntity c&&c.species()!=null?c.species().toString():"",partIndex(e));}
 private static boolean editable(Entity e){return !(root(e) instanceof Player);}
 public static Entity target(ServerPlayer p,String id){Entity e=p.level().getEntity(UUID.fromString(id));if(e==null||p.distanceToSqr(e)>4096*4096)throw new IllegalArgumentException("ENTITY_TARGET_UNAVAILABLE");return e;}
 private static void authority(ServerPlayer p){if(!p.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))throw new SecurityException("ENTITY_GAMEMASTER_REQUIRED");}
 public static Map<String,Object> inspectRules(ServerPlayer p,int offset){if(offset<0)throw new IllegalArgumentException("ENTITY_RULE_OFFSET");var s=state(p.level().getServer());var rows=s.rules.values().stream().filter(r->r.owner().equals(p.getUUID())).toList();return Map.of("rules",rows.stream().skip(offset).limit(16).map(r->Map.of("rule",r,"events",Map.copyOf(s.counts.getOrDefault(r.id(),Map.of())))).toList(),"nextOffset",offset+16<rows.size()?offset+16:-1,"contract",CONTRACT);}
 public static CompletableFuture<Map<String,Object>> set(ServerPlayer p,UUID operation,JsonNode args,String kind,boolean remove)throws Exception{
  authority(p);var server=p.level().getServer();var state=state(server);UUID id=args.has("rule_id")?UUID.fromString(args.path("rule_id").asText()):operation;var old=state.rules.get(id);long expected=args.path("expected_revision").asLong(0);if(args.has("expected_revision")&&(!args.get("expected_revision").isIntegralNumber()||!args.get("expected_revision").canConvertToLong()||expected<0||expected==Long.MAX_VALUE))throw new IllegalArgumentException("ENTITY_RULE_REVISION");if(old!=null&&!remove&&!old.kind().equals(kind))throw new IllegalArgumentException("ENTITY_RULE_KIND");
  if(old!=null&&!old.owner().equals(p.getUUID()))throw new SecurityException("ENTITY_RULE_NOT_OWNED");if(expected!=(old==null?0:old.revision())||remove&&old==null)throw new IllegalStateException("ENTITY_RULE_STALE");if(!state.writing.add(id))throw new IllegalStateException("ENTITY_RULE_UPDATE_PENDING");
  try{
   String source=remove?"":args.path("source").asText();if(!remove){var selector=kind.equals("logic")?EntityLogicSpec.parse(source).selector():EntityAnimationSpec.parse(source).selector();if(!selector.dimension().equals(p.level().dimension().identifier().toString()))throw new IllegalArgumentException("ENTITY_RULE_DIMENSION");if(!selector.entity().isEmpty()&&!editable(target(p,selector.entity())))throw new SecurityException("ENTITY_PLAYER_RULE_UNSUPPORTED");if(!selector.type().isEmpty()&&(!BuiltInRegistries.ENTITY_TYPE.containsKey(Identifier.parse(selector.type()))||selector.type().equals("minecraft:player")))throw new IllegalArgumentException("ENTITY_RULE_TYPE");}
   Rule next=new Rule(id,p.getUUID(),expected+1,remove?old.kind():kind,source);var future=new CompletableFuture<Map<String,Object>>();
   CompletableFuture.supplyAsync(()->{try{return remove?state.db.delete(state.world,"entity_interop_v1",id.toString(),expected,System.currentTimeMillis()):state.db.compareAndSet(state.world,"entity_interop_v1",id.toString(),expected,JSON.writeValueAsString(next),System.currentTimeMillis());}catch(Exception e){throw new CompletionException(e);}},IO).whenComplete((saved,error)->server.execute(()->{
    state.writing.remove(id);if(error!=null){future.complete(Map.of("status","UNKNOWN","error","ENTITY_RULE_WRITE_UNKNOWN","replayAllowed",false));return;}if(!saved.accepted()){future.complete(Map.of("status","REJECTED","error","ENTITY_RULE_STALE"));return;}
    if(remove){state.rules.remove(id);state.logic.remove(id);}else state.install(next);
    for(var viewer:server.getPlayerList().getPlayers())if(!(viewer instanceof dev.mineagent.runtime.neoforge.body.MineAgentPlayer))send(viewer,next,remove);
    future.complete(Map.of("status",remove?"REMOVED":"APPLIED","ruleId",id,"revision",expected+1,"kind",next.kind(),"nativeEffect","NEXT_NATIVE_EVENT","renderConfirmation",next.kind().equals("visual")?"INSPECT_CLIENT_RENDER_REQUIRED":"NOT_APPLICABLE"));
   }));return future;
  }catch(Exception e){state.writing.remove(id);throw e;}
 }
 private static void send(ServerPlayer p,Rule rule,boolean remove){if(!rule.kind().equals("visual"))return;try{net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(p,new UiPayloads.Event(rule.id(),"entityVisualRule",JSON.writeValueAsString(Map.of("remove",remove,"rule",rule))));}catch(Exception e){MineAgentRuntimeMod.LOGGER.warn("Entity visual rule sync failed: {}",rule.id());}}
 public static void sync(ServerPlayer p){var s=state(p.level().getServer());net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(p,new UiPayloads.Event(UUID.randomUUID(),"entityVisualReset","{}"));s.rules.values().forEach(r->send(p,r,false));}
 private static List<Map.Entry<UUID,EntityLogicSpec>> matching(Entity entity){if(!editable(entity)||!(entity.level() instanceof ServerLevel l))return List.of();var s=LIVE.get(l.getServer());if(s==null||s.logic.isEmpty())return List.of();var root=root(entity);return s.logic.entrySet().stream().filter(e->matches(e.getValue().selector(),root)).toList();}
 private static void event(State s,UUID id,EntityLogicSpec rule,Entity e,String event,boolean blocked){s.counts.computeIfAbsent(id,k->new HashMap<>()).merge(event+(blocked?".blocked":".observed"),1L,Long::sum);if(CALLBACK.get())return;var actions=rule.callbacks().getOrDefault(event,List.of());if(actions.isEmpty())return;long now=e.level().getGameTime();String key=id+":"+e.getUUID()+":"+event;if(now-s.cooldown.getOrDefault(key,Long.MIN_VALUE/2)<rule.cooldown())return;s.cooldown.put(key,now);CALLBACK.set(true);try{var server=((ServerLevel)e.level()).getServer();var owner=server.getPlayerList().getPlayer(s.rules.get(id).owner());for(var a:actions)switch(a.type()){
  case "message"->{if(owner!=null)owner.sendSystemMessage(Component.literal("["+rule.name()+"] "+a.value()));}
  case "sound"->{var sound=BuiltInRegistries.SOUND_EVENT.getValue(Identifier.parse(a.value()));if(sound!=null)e.level().playSound(null,e.blockPosition(),sound,net.minecraft.sounds.SoundSource.NEUTRAL,1,1);}
  case "effect"->{var effect=BuiltInRegistries.MOB_EFFECT.getValue(Identifier.parse(a.value()));if(effect!=null&&e instanceof LivingEntity living)living.addEffect(new net.minecraft.world.effect.MobEffectInstance(BuiltInRegistries.MOB_EFFECT.wrapAsHolder(effect),a.ticks(),a.amplifier()));}
  case "command"->{if(owner!=null&&owner.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER)&&owner.level()==e.level())server.getCommands().performPrefixedCommand(owner.createCommandSourceStack(),a.value().replace("{entity}",root(e).getUUID().toString()));}
 }}finally{CALLBACK.remove();}}
 private static boolean blocked(Entity e,String event){boolean result=false;for(var match:matching(e)){var s=LIVE.get(((ServerLevel)e.level()).getServer());boolean no=match.getValue().block().contains(event);event(s,match.getKey(),match.getValue(),e,event,no);result|=no;}return result;}
 public static boolean preventAttack(Entity e){for(var match:matching(e))if(match.getValue().block().contains("attack")){event(LIVE.get(((ServerLevel)e.level()).getServer()),match.getKey(),match.getValue(),e,"attack",true);return true;}return false;}
 public static Vec3 motion(Entity e,Vec3 input){var matches=matching(e);if(matches.isEmpty())return input;var s=LIVE.get(((ServerLevel)e.level()).getServer());var value=input;for(var match:matches){var rule=match.getValue();boolean no=rule.block().contains("move");event(s,match.getKey(),rule,e,"move",no);var scale=rule.movement();if(no)e.setDeltaMovement(Vec3.ZERO);value=no?Vec3.ZERO:value.multiply(scale.get(0),scale.get(1),scale.get(2));}return value;}
 @SubscribeEvent(priority=EventPriority.HIGHEST) public static void use(PlayerInteractEvent.EntityInteract e){if(!e.getLevel().isClientSide()&&blocked(e.getTarget(),"interact")){e.setCanceled(true);e.setCancellationResult(net.minecraft.world.InteractionResult.FAIL);}}
 @SubscribeEvent(priority=EventPriority.HIGHEST) public static void useAt(PlayerInteractEvent.EntityInteractSpecific e){if(!e.getLevel().isClientSide()&&blocked(e.getTarget(),"interact")){e.setCanceled(true);e.setCancellationResult(net.minecraft.world.InteractionResult.FAIL);}}
 @SubscribeEvent(priority=EventPriority.HIGHEST) public static void attacking(AttackEntityEvent e){if(!e.getEntity().level().isClientSide()&&blocked(e.getTarget(),"attacked"))e.setCanceled(true);}
 @SubscribeEvent(priority=EventPriority.HIGHEST) public static void damage(LivingIncomingDamageEvent e){if(!(e.getEntity().level() instanceof ServerLevel level))return;double amount=e.getAmount();for(var match:matching(e.getEntity())){var r=match.getValue();boolean no=r.block().contains("hurt");event(LIVE.get(level.getServer()),match.getKey(),r,e.getEntity(),"hurt",no);if(no)e.setCanceled(true);amount*=r.taken();}var source=e.getSource().getEntity();if(source!=null)for(var match:matching(source)){var r=match.getValue();boolean no=r.block().contains("attack");event(LIVE.get(level.getServer()),match.getKey(),r,source,"attack",no);if(no)e.setCanceled(true);amount*=r.dealt();}if(Double.isFinite(amount)&&amount!=e.getAmount())e.setAmount((float)Math.min(Float.MAX_VALUE,Math.max(0,amount)));}
 @SubscribeEvent(priority=EventPriority.HIGHEST) public static void knockback(LivingKnockBackEvent e){if(!(e.getEntity().level() instanceof ServerLevel level))return;float strength=e.getStrength();for(var match:matching(e.getEntity())){var r=match.getValue();boolean no=r.block().contains("knockback");event(LIVE.get(level.getServer()),match.getKey(),r,e.getEntity(),"knockback",no);if(no)e.setCanceled(true);strength*=r.knockback();}e.setStrength(strength);}
 @SubscribeEvent(priority=EventPriority.HIGHEST) public static void target(LivingChangeTargetEvent e){if(!e.getEntity().level().isClientSide()&&e.getNewAboutToBeSetTarget()!=null&&blocked(e.getEntity(),"target"))e.setNewAboutToBeSetTarget(null);}
 @SubscribeEvent(priority=EventPriority.HIGHEST) public static void tick(net.neoforged.neoforge.event.tick.EntityTickEvent.Pre e){if(!(e.getEntity().level() instanceof ServerLevel level)||!editable(e.getEntity()))return;var s=LIVE.get(level.getServer());if(s==null)return;var matches=matching(e.getEntity());if(e.getEntity() instanceof Mob mob&&(!matches.isEmpty()||s.removed.containsKey(mob))){var desired=new HashSet<String>();matches.forEach(m->desired.addAll(m.getValue().blockedGoals()));var held=s.removed.computeIfAbsent(mob,k->new ArrayList<>());for(var removed:List.copyOf(held))if(!desired.contains(removed.goal().getClass().getName())){removed.selector().addGoal(removed.priority(),removed.goal());held.remove(removed);}if(!desired.isEmpty())for(var selector:List.of(((EntityGoalsAccess)mob).mineagent$goals(),((EntityGoalsAccess)mob).mineagent$targets()))for(var goal:List.copyOf(selector.getAvailableGoals()))if(desired.contains(goal.getGoal().getClass().getName())){held.add(new RemovedGoal(selector,goal.getPriority(),goal.getGoal()));selector.removeGoal(goal.getGoal());}if(held.isEmpty())s.removed.remove(mob);}
  if(!matches.isEmpty()&&blocked(e.getEntity(),"tick"))e.setCanceled(true);
 }
 @SubscribeEvent public static void login(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent e){if(e.getEntity() instanceof ServerPlayer p&&!(p instanceof dev.mineagent.runtime.neoforge.body.MineAgentPlayer)&&WorldIdentityRuntime.ready(p.level().getServer()))sync(p);}
 @SubscribeEvent public static void leave(net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent e){if(e.getLevel() instanceof ServerLevel level&&LIVE.containsKey(level.getServer())&&e.getEntity() instanceof Mob mob)LIVE.get(level.getServer()).removed.remove(mob);}
 @SubscribeEvent public static void stop(net.neoforged.neoforge.event.server.ServerStoppedEvent e){var s=LIVE.remove(e.getServer());if(s!=null){s.closed=true;IO.submit(()->{try{s.close();}catch(Exception ignored){}});}}
 public static final String CONTRACT="""
 原版/Mod实体规则，不修改JAR或伪造原生事件。先inspect_entity_logic取得实体UUID、类型、Goal类名、multipart部件与适配器能力。
 source平面JSON：dimension及entity_id/entity_type/species_id三选一。logic字段：name，block:[interact,attack,attacked,hurt,knockback,target,move,tick]，damage_taken_multiplier，damage_dealt_multiplier(0..100)，knockback_multiplier(0..10)，movement_scale:[x,y,z](0..8)，blocked_goals:[inspect返回的完整Goal类名]。
 interact阻断玩家右键；attacked阻断玩家击打该实体；attack阻断该实体发起攻击/碰撞伤害；hurt阻断该实体受伤；target拒绝新增攻击目标；move拦截Entity.move（直接setPos/传送不属于此钩子）；tick冻结实体原生Tick（会连同寿命/AI一起暂停），不要拿暂停Tick冒充只改移动。
 callbacks可按事件配置[{type:message|sound|effect|command,value:"...",ticks:100,amplifier:0}]。每实体每事件cooldown_ticks默认20。effect作用于受规则约束的实体；command仅在创建者在线且仍有管理权限、同维度时执行，{entity}展开为主实体UUID，不借用事件玩家权限。组合规则取消优先，不授予其它Mod拒绝的交互权限。
 不修改真实玩家或MineAgent玩家本体；它们使用已有本人接管/皮肤API。multipart命中统一归主实体，受伤倍率仍经过Mod原来的部件转发。关闭特定Goal后可删除规则恢复同一Goal实例，但不复制未知Mod的私有Java状态机。
 visual source同样选择目标，可增加part_index=-1主实体(默认)、0..部件序号、-2主实体及部件；block_native冻结支持模型的原生局部动作；mode=add|replace，duration_ticks/loop，root:[{tick,translation,rotation,scale}]，parts:{"root/head":[同样关键帧]}。先inspect_entity_animation读取实际绘制的模型路径。root平移单位渲染局部方块，parts平移单位ModelPart像素，旋转度；replace基于初始姿态，add叠加当前姿态。仅Model/ModelPart提交路径支持；专有渲染器返回不支持，不能冒称已改。视觉不改变命中箱。
 set_entity_rule / set_entity_animation用rule_id与expected_revision修改；delete_entity_rule恢复，inspect_entity_rules有真实回调计数。每条规则只在所声明维度生效；类型选择器影响此维度内匹配实体。能力不等于所有第三方私有回调都已适配。
 """;
 private ServerEntityInterop(){}
}
