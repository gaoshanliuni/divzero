package dev.mineagent.runtime.neoforge.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mineagent.runtime.core.task.*;
import dev.mineagent.runtime.neoforge.MineAgentRuntimeServices;
import dev.mineagent.runtime.neoforge.WorldActivationRuntime;
import dev.mineagent.runtime.neoforge.body.MineAgentPlayer;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.*;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Explicit local human practice, in a disposable world. Never controls the human's inputs. */
@EventBusSubscriber(modid="mineagent_runtime")
public final class NativeHumanDuel {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static boolean mapActive;
    private static final boolean ENABLED=Boolean.getBoolean("mineagent.humanDuel")&&Files.isRegularFile(Path.of("human-duel-instance.json"));
    private static final Map<MinecraftServer,Run> RUNS=new IdentityHashMap<>();
    private static final Set<MinecraftServer> OFFERED=Collections.newSetFromMap(new IdentityHashMap<>());
    private static final ExecutorService IO=Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("divzero-human-duel-data").factory());
    private static final IsolatedCombatArena.Bounds ARENA=new IsolatedCombatArena.Bounds("human_duel",0,800,100,17);
    private static final class Run {
        final MinecraftServer server;final UUID owner,id=UUID.randomUUID();final Object level;
        final HumanDuelSeries series;final List<Object> matches=new ArrayList<>();
        final List<Object> frames=new ArrayList<>(),damage=new ArrayList<>();
        final Set<UUID> roundProjectiles=new HashSet<>();
        final String model=LocalActionPolicy.pretrained().json();final String modelHash=hash(model);
        final Path directory;MineAgentPlayer ai;ServerPlayer player;UUID match;CompletableFuture<?> start;
        CompletableFuture<Void> saved=CompletableFuture.completedFuture(null);String error="",finishReason="";
        long startNanos,endNanos;int startTick,lastCountdown=-1;double humanDamage,aiDamage;int humanHits,aiHits;
        PvpMapArena.Cleanup cleanup;
        boolean woolFixtureDone;int rangedFixtureStage;double rangedSeparation,rangedCycleSeparation;long rangedSprint;int rangedAmmo=256;
        int fixtureStage;List<LocalActionPolicy.Sample> partialSamples=List.of();
        Run(ServerPlayer player){series=PvpMapSupport.enabled()?new HumanDuelSeries(0,PvpMapSupport.profile(player).rounds()):new HumanDuelSeries();this.player=player;owner=player.getUUID();server=player.level().getServer();level=player.level();directory=server.getServerDirectory().resolve("human-duel").resolve(id.toString());}
    }
    record FixtureState(ServerPlayer player,MineAgentPlayer ai,String phase,boolean cleaning){}
    static FixtureState fixtureState(MinecraftServer server){
        if(!ENABLED||!Boolean.getBoolean("mineagent.modernLifecycleFixture"))return null;var run=RUNS.get(server);
        var p=run==null?server.getPlayerList().getPlayers().stream().filter(v->!(v instanceof MineAgentPlayer)&&server.isSingleplayerOwner(v.nameAndId())).findFirst().orElse(null):server.getPlayerList().getPlayer(run.owner);
        return p==null?null:new FixtureState(p,run==null?null:run.ai,run==null?"NONE":run.series.phase().name(),run!=null&&run.cleanup!=null);
    }
    public static boolean enabled(){return ENABLED||mapActive;}
    public static boolean mapEnabled(){return mapActive||ENABLED&&Boolean.getBoolean("mineagent.pvpMap");}
    static boolean participant(MineAgentPlayer body){var run=RUNS.get(body.level().getServer());return enabled()&&run!=null&&run.ai==body;}
    public static void register(com.mojang.brigadier.CommandDispatcher<net.minecraft.commands.CommandSourceStack> dispatcher){
        dispatcher.register(Commands.literal("ai").then(Commands.literal("duel").requires(s->enabled()&&s.getPlayer()!=null)
            .executes(c->command(c.getSource().getPlayerOrException(),"status"))
            .then(Commands.literal("ready").executes(c->command(c.getSource().getPlayerOrException(),"ready")))
            .then(Commands.literal("stop").executes(c->command(c.getSource().getPlayerOrException(),"stop")))
            .then(Commands.literal("equip").executes(c->command(c.getSource().getPlayerOrException(),"equip")))
            .then(Commands.literal("loadout").then(Commands.argument("revision",com.mojang.brigadier.arguments.LongArgumentType.longArg(0)).then(Commands.argument("actor",com.mojang.brigadier.arguments.StringArgumentType.word()).then(Commands.argument("slot",com.mojang.brigadier.arguments.StringArgumentType.word()).then(Commands.argument("item",com.mojang.brigadier.arguments.StringArgumentType.greedyString()).executes(c->loadout(c.getSource().getPlayerOrException(),com.mojang.brigadier.arguments.LongArgumentType.getLong(c,"revision"),com.mojang.brigadier.arguments.StringArgumentType.getString(c,"actor"),com.mojang.brigadier.arguments.StringArgumentType.getString(c,"slot"),com.mojang.brigadier.arguments.StringArgumentType.getString(c,"item"))))))))
            .then(Commands.literal("status").executes(c->command(c.getSource().getPlayerOrException(),"status")))));
    }
    public static int command(ServerPlayer player,String action){
        if(!enabled()||player instanceof MineAgentPlayer||!player.level().getServer().isSingleplayer()||!player.level().getServer().isSingleplayerOwner(player.nameAndId()))return 0;
        var server=player.level().getServer();var run=RUNS.get(server);
        if(run!=null&&!run.owner.equals(player.getUUID()))return 0;
        try{
            if(action.equals("stop")){if(run!=null)abort(run,"USER_STOPPED");else tell(player,"当前没有对练。");return 1;}
            if(!action.equals("ready")){if(action.equals("equip")&&PvpMapSupport.enabled()&&!active(run)){PvpMapSupport.push(player,"READY",180,true);}else menu(player,run);return 1;}
            if(PvpMapSupport.enabled()&&run!=null&&run.series.phase()==HumanDuelSeries.Phase.STOPPED){RUNS.remove(server);run=null;}
            if(!player.isAlive()){tell(player,"请先重生，再准备下一场。");return 0;}
            if(run==null){
                // This bootstrap is restricted to an explicitly launched disposable single-player arena.
                if(WorldActivationRuntime.decide(player.createCommandSourceStack(),true,null)!=1)throw new IllegalStateException("世界尚未启用");
                IsolatedCombatArena.prepare(player,List.of(ARENA));if(PvpMapSupport.enabled())decorate(player);
                player.level().getGameRules().set(GameRules.SPAWN_MOBS,false,server);
                player.level().getGameRules().set(GameRules.PVP,true,server);
                player.level().getGameRules().set(GameRules.KEEP_INVENTORY,true,server);
                server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withEntity(player).withLevel(player.level()).withSuppressedOutput(),"difficulty normal");
                server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withEntity(player).withLevel(player.level()).withSuppressedOutput(),"time set day");
                run=new Run(player);RUNS.put(server,run);
            }
            if(run.saved.isCompletedExceptionally())throw new IllegalStateException("数据保存失败，已停止对练");
            if(!run.saved.isDone()||run.ai!=null){tell(player,"正在保存上一场数据，请稍候再准备。");return 0;}
            if(!run.series.ready(System.nanoTime())){menu(player,run);return 0;}
            run.player=player;player.stopUsingItem();dev.mineagent.runtime.neoforge.body.NativeInventorySync.full(player);if(PvpMapSupport.enabled()){run.cleanup=new PvpMapArena.Cleanup(player);PvpMapArena.returnToLobby(player);}else prepareRound(run);return 1;
        }catch(Exception failure){if(run!=null)abort(run,Objects.toString(failure.getMessage(),"SETUP_FAILED"));tell(player,"对练未开始："+failure.getMessage());return 0;}
    }
    private static void prepareRound(Run run){
        var p=run.player;run.match=UUID.randomUUID();run.frames.clear();run.damage.clear();run.humanDamage=run.aiDamage=0;run.humanHits=run.aiHits=0;run.lastCountdown=-1;
        equip(p);if(PvpMapSupport.enabled()){var profile=PvpMapSupport.profile(p);PvpMapSupport.apply(p,profile.human());if(profile.humanWool())p.getInventory().setItem(1,new ItemStack(Items.WHITE_WOOL,64));dev.mineagent.runtime.neoforge.body.NativeInventorySync.full(p);}placeHuman(p);p.setInvulnerable(true);
        var definition=MineAgentRuntimeServices.bodies(run.server).createPersistentAt("神经网络陪练"+run.series.round(),p.getUUID(),p.level(),new Vec3(5.5,101,800.5));
        run.ai=MineAgentRuntimeServices.bodies(run.server).body(definition.agentId()).orElseThrow();
        IsolatedCombatArena.place(p,run.ai,ARENA,1,new Vec3(5.5,101,800.5));equip(run.ai);if(PvpMapSupport.enabled()){var profile=PvpMapSupport.profile(p);PvpMapSupport.apply(run.ai,profile.ai());if(profile.aiWool())run.ai.getInventory().setItem(1,new ItemStack(Items.LIGHT_BLUE_WOOL,64));dev.mineagent.runtime.neoforge.body.NativeInventorySync.full(run.ai);}run.ai.setInvulnerable(true);
        ActorEnhancements.update(p,run.ai.agentId(),JSON.createObjectNode().put("actor","ai").put("expected_revision",0).put("boost",false).put("neural",true).put("learning",false).put("recovery",PvpMapSupport.enabled()));
        LocalPolicyRuntime.seedTrainingModel(run.ai,run.model,run.modelHash);
        tell(p,"第 "+run.series.round()+(PvpMapSupport.enabled()?" 场：已应用双方所选装备":" / 5 场：双方钻石剑、无附魔铁套")+"，满血满饥饿。5 秒后开始；/ai duel stop 随时停止。");
        persist(run);
    }
    private static void equip(ServerPlayer p){
        p.stopUsingItem();p.closeContainer();p.setGameMode(GameType.SURVIVAL);p.getInventory().clearContent();p.removeAllEffects();p.setAbsorptionAmount(0);p.clearFire();p.setHealth(p.getMaxHealth());p.getFoodData().setFoodLevel(20);p.getFoodData().setSaturation(5);
        p.getInventory().setSelectedSlot(0);p.getInventory().setItem(0,new ItemStack(Items.DIAMOND_SWORD));
        p.setItemSlot(EquipmentSlot.HEAD,new ItemStack(Items.IRON_HELMET));p.setItemSlot(EquipmentSlot.CHEST,new ItemStack(Items.IRON_CHESTPLATE));p.setItemSlot(EquipmentSlot.LEGS,new ItemStack(Items.IRON_LEGGINGS));p.setItemSlot(EquipmentSlot.FEET,new ItemStack(Items.IRON_BOOTS));p.setItemSlot(EquipmentSlot.OFFHAND,ItemStack.EMPTY);
        p.setDeltaMovement(Vec3.ZERO);p.fallDistance=0;p.invulnerableTime=0;p.resetAttackStrengthTicker();p.stopUsingItem();dev.mineagent.runtime.neoforge.body.NativeInventorySync.full(p);
    }
    private static void placeHuman(ServerPlayer p){
        p.teleportTo(p.level(),-4.5,101,800.5,Set.of(),-90,0,true);p.setDeltaMovement(Vec3.ZERO);p.fallDistance=0;
        if(!p.level().noCollision(p,p.getBoundingBox())||p.level().noCollision(p,p.getBoundingBox().move(0,-.08,0)))throw new IllegalStateException("玩家出生点没有有效地面或存在碰撞");
    }
    @SubscribeEvent public static void tick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event){
        var server=event.getServer();if(!enabled()||!server.isSingleplayer())return;
        if(!dev.mineagent.runtime.neoforge.WorldIdentityRuntime.ready(server))return;
        if(ENABLED&&Boolean.getBoolean("mineagent.modernLifecycleFixture")&&ModernLifecycleVerification.tick(server))return;
        if(Boolean.getBoolean("mineagent.humanDuelFixture"))fixtureTick(server);
        var run=RUNS.get(server);
        if(ENABLED&&(Boolean.getBoolean("mineagent.modernPvpFixture")||Boolean.getBoolean("mineagent.modernCombatFixture"))&&run==null&&server.getTickCount()>160){for(var p:server.getPlayerList().getPlayers())if(!(p instanceof MineAgentPlayer)){command(p,"ready");break;}return;}
        if(run==null){if(server.getTickCount()>100&&!OFFERED.contains(server))for(var p:server.getPlayerList().getPlayers())if(!(p instanceof MineAgentPlayer)&&server.isSingleplayerOwner(p.nameAndId())){OFFERED.add(server);if(PvpMapSupport.enabled()){PvpMapArena.lobby(p);PvpMapArena.returnToLobby(p);}menu(p,null);if(PvpMapSupport.enabled())PvpMapSupport.push(p,"READY",180,true);break;}return;}
        try{
            var online=server.getPlayerList().getPlayer(run.owner);
            if(online==null){abort(run,"DISCONNECTED");return;}
            run.player=online;
            if(run.finishReason.length()>0){finishRound(run);return;}
            if(run.series.phase()==HumanDuelSeries.Phase.STOPPED||run.series.phase()==HumanDuelSeries.Phase.COMPLETE)return;
            if(online.level()!=run.level){abort(run,"DIMENSION_CHANGED");return;}
            if(run.saved.isCompletedExceptionally()){abort(run,"DATA_WRITE_FAILED");return;}
            if(run.cleanup!=null){if(run.cleanup.advance()){run.cleanup=null;run.series.prepared(System.nanoTime());prepareRound(run);}return;}
            long now=System.nanoTime();if(PvpMapSupport.enabled()&&server.getTickCount()%20==0)PvpMapSupport.push(online,run.series.phase().name(),run.series.remainingSeconds(now),false);
            if(run.series.phase()==HumanDuelSeries.Phase.COUNTDOWN){
                int seconds=(int)run.series.remainingSeconds(now);if(seconds!=run.lastCountdown){run.lastCountdown=seconds;online.sendSystemMessage(Component.literal("对练倒计时："+seconds),true);}
                if(run.series.countdownComplete(now)){
                    placeHuman(online);IsolatedCombatArena.place(online,run.ai,ARENA,1,new Vec3(5.5,101,800.5));
                    PvpConsent.grantFromPanel(online,run.ai.agentId(),online);
                    run.series.starting();
                    var args=JSON.createObjectNode().put("id","human_duel_"+run.series.round()).put("actor","ai").put("expected_revision",0).put("target",online.getUUID().toString()).put("dimension",online.level().dimension().identifier().toString());
                    args.putObject("combat").put("engagement","SPECIFIED").put("target",online.getUUID().toString()).put("strategy","AUTO").put("awareness",32).put("leash",48);
                    run.start=SkillRuntime.get(server).start(online,run.ai.agentId(),UUID.randomUUID(),null,args,SkillSpec.Kind.COMBAT,()->run.finishReason.isEmpty()&&(run.series.phase()==HumanDuelSeries.Phase.STARTING||run.series.phase()==HumanDuelSeries.Phase.FIGHTING));
                    run.startNanos=now;
                }
            }else if(run.series.phase()==HumanDuelSeries.Phase.STARTING){
                if(now-run.startNanos>20_000_000_000L)throw new IllegalStateException("启动战斗超时");
                if(run.start.isDone()){var receipt=run.start.join();if(!(receipt instanceof Map<?,?> m)||!Objects.equals(m.get("status"),"STARTED"))throw new IllegalStateException("战斗未启动");run.series.started(now);LocalPolicyRuntime.beginRecording(run.ai);online.setInvulnerable(false);run.ai.setInvulnerable(false);run.startTick=server.getTickCount();tell(online,"开始！第 "+run.series.round()+(PvpMapSupport.enabled()?" 场，最长 3 分钟。":" / 5 场，最长 3 分钟。"));persist(run);}
            }else if(run.series.phase()==HumanDuelSeries.Phase.FIGHTING){
                if(ENABLED&&Boolean.getBoolean("mineagent.modernPvpFixture")){ModernPvpVerification.tick(online,run.ai);return;}
                if(ENABLED&&Boolean.getBoolean("mineagent.modernCombatFixture")&&ModernCombatVerification.tick(online,run.ai))return;
                String result=run.series.outcome(now,online.isAlive(),run.ai.isAlive());
                if(!result.isEmpty()){endCombat(run,result);return;}
                if(!(PvpMapSupport.enabled()?PvpMapArena.inside(online.position())&&PvpMapArena.inside(run.ai.position()):ARENA.contains(online.position())&&ARENA.contains(run.ai.position()))||run.ai.level()!=run.level){abort(run,"LEFT_ARENA");return;}
                if(server.getTickCount()%4==0)run.frames.add(Map.of("tick",server.getTickCount()-run.startTick,"seconds",run.series.elapsed(now),"human",observe(online),"ai",observe(run.ai)));
                if(server.getTickCount()%20==0)online.sendSystemMessage(Component.literal("第 "+run.series.round()+(PvpMapSupport.enabled()?" 场 · 剩余 ":" / 5 场 · 剩余 ")+run.series.remainingSeconds(now)+" 秒 · /ai duel stop 停止"),true);
                if(server.getTickCount()%100==0)persist(run);
            }
        }catch(Throwable failure){abort(run,Objects.toString(failure.getMessage(),failure.getClass().getSimpleName()));}
    }
    private static Map<String,Object> observe(ServerPlayer p){return Map.of("position",List.of(p.getX(),p.getY(),p.getZ()),"velocity",List.of(p.getDeltaMovement().x,p.getDeltaMovement().y,p.getDeltaMovement().z),"yaw",p.getYRot(),"pitch",p.getXRot(),"health",p.getHealth(),"cooldown",p.getAttackStrengthScale(0),"onGround",p.onGround(),"sprinting",p.isSprinting(),"food",p.getFoodData().getFoodLevel());}
    @SubscribeEvent public static void damage(net.neoforged.neoforge.event.entity.living.LivingDamageEvent.Post event){
        if(!enabled()||!(event.getEntity() instanceof ServerPlayer victim))return;var run=RUNS.get(victim.level().getServer());
        if(run==null||run.series.phase()!=HumanDuelSeries.Phase.FIGHTING||victim!=run.ai&&victim!=run.player)return;
        var source=event.getSource().getEntity();double amount=event.getHealthDamage();
        if(amount>0){if(victim==run.ai&&source==run.player){run.humanDamage+=amount;run.humanHits++;}if(victim==run.player&&source==run.ai){run.aiDamage+=amount;run.aiHits++;}
            run.damage.add(Map.of("tick",run.server.getTickCount()-run.startTick,"victim",victim==run.ai?"AI":"HUMAN","source",source==run.ai?"AI":source==run.player?"HUMAN":"ENVIRONMENT","healthDamage",amount));}
    }
    @SubscribeEvent(priority=net.neoforged.bus.api.EventPriority.LOWEST) public static void death(net.neoforged.neoforge.event.entity.living.LivingDeathEvent event){
        if(!enabled()||event.isCanceled()||!(event.getEntity() instanceof ServerPlayer victim))return;var run=RUNS.get(victim.level().getServer());
        if(ENABLED&&Boolean.getBoolean("mineagent.modernCombatFixture")&&run!=null&&victim==run.ai)ModernCombatVerification.died(run.player,run.ai);
        if(run!=null&&run.series.phase()==HumanDuelSeries.Phase.FIGHTING&&(victim==run.ai||victim==run.player))endCombat(run,victim==run.ai?"HUMAN_WON":"AI_WON");
    }
    @SubscribeEvent public static void roundProjectile(net.neoforged.neoforge.event.entity.EntityJoinLevelEvent event){
        if(event.loadedFromDisk()||event.isCanceled()||!(event.getEntity() instanceof net.minecraft.world.entity.projectile.Projectile projectile)||!(event.getLevel() instanceof net.minecraft.server.level.ServerLevel level))return;
        var run=RUNS.get(level.getServer());if(run!=null&&run.series.phase()==HumanDuelSeries.Phase.FIGHTING&&run.finishReason.isEmpty()&&(projectile.getOwner()==run.player||projectile.getOwner()==run.ai))run.roundProjectiles.add(projectile.getUUID());
    }
    private static void clearRoundProjectiles(Run run){
        if(run.level instanceof net.minecraft.server.level.ServerLevel level)for(var id:run.roundProjectiles){var entity=level.getEntity(id);if(entity instanceof net.minecraft.world.entity.projectile.Projectile projectile&&(projectile.getOwner()==run.player||projectile.getOwner()==run.ai))projectile.discard();}
        run.roundProjectiles.clear();
    }
    private static void endCombat(Run run,String reason){
        if(!run.finishReason.isEmpty())return;run.finishReason=reason;run.endNanos=System.nanoTime();clearRoundProjectiles(run);
        PvpConsent.revoke(run.player,run.ai.agentId());run.ai.controls().cancel();run.ai.movementController().stop();run.ai.stopUsingItem();
        // End the combat lease immediately; post-death telemetry is finalized on the next server tick.
        run.player.stopUsingItem();dev.mineagent.runtime.neoforge.body.NativeInventorySync.full(run.player);run.player.setInvulnerable(true);run.ai.setInvulnerable(true);
    }
    private static void finishRound(Run run){
        var result=new LinkedHashMap<String,Object>();result.put("match",run.match.toString());result.put("round",run.series.round());result.put("outcome",run.finishReason);result.put("seconds",Math.min(180,run.series.elapsed(run.endNanos)));result.put("observedSeconds",run.series.elapsed(run.endNanos));result.put("ticks",run.server.getTickCount()-run.startTick);
        result.put("human",Map.of("damage",run.humanDamage,"hits",run.humanHits,"health",run.player.getHealth()));result.put("ai",Map.of("damage",run.aiDamage,"hits",run.aiHits,"health",run.ai.getHealth(),"agent",run.ai.agentId().toString()));
        result.put("samples",LocalPolicyRuntime.endRecording(run.ai));result.put("initialModelHash",run.modelHash);result.put("finalModelHash",hash(LocalPolicyRuntime.snapshot(run.ai).json()));result.put("frames",List.copyOf(run.frames));result.put("damageEvents",List.copyOf(run.damage));
        if(PvpMapSupport.enabled()){var profile=PvpMapSupport.profile(run.player);result.put("loadout",Map.of("human",profile.human(),"ai",profile.ai(),"humanWool",profile.humanWool(),"aiWool",profile.aiWool()));PvpMapSupport.save(run.player,profile.finish(run.finishReason,Math.min(180,run.series.elapsed(run.endNanos))));archiveRound(run,result);run.matches.clear();}
        run.matches.add(result);run.series.finish();String text=switch(run.finishReason){case "HUMAN_WON"->"你获胜";case "AI_WON"->"AI 获胜";default->"平局";};run.finishReason="";
        retire(run);persist(run);if(PvpMapSupport.enabled())PvpMapArena.returnToLobby(run.player);run.player.sendSystemMessage(Component.empty(),true);tell(run.player,"第 "+run.series.completed()+" 场结束："+text+"。你的伤害 "+String.format(Locale.ROOT,"%.1f",run.humanDamage)+"，AI 伤害 "+String.format(Locale.ROOT,"%.1f",run.aiDamage)+"。");menu(run.player,run);
    }
    private static void retire(Run run){if(run.ai!=null){IsolatedCombatArena.retire(run.player,run.ai);run.ai=null;}}
    private static void abort(Run run,String reason){
        if(run.series.phase()==HumanDuelSeries.Phase.STOPPED||run.series.phase()==HumanDuelSeries.Phase.COMPLETE)return;
        clearRoundProjectiles(run);run.error=reason;run.cleanup=null;run.series.stop();run.finishReason="";run.player.stopUsingItem();dev.mineagent.runtime.neoforge.body.NativeInventorySync.full(run.player);
        if(run.ai!=null){PvpConsent.revoke(run.player,run.ai.agentId());run.ai.controls().cancel();run.ai.movementController().stop();run.partialSamples=LocalPolicyRuntime.endRecording(run.ai);try{retire(run);}catch(Exception e){run.ai.discard();run.ai=null;}}
        run.player.setInvulnerable(true);if(PvpMapSupport.enabled()&&run.player.level()==run.level)PvpMapArena.returnToLobby(run.player);persist(run);tell(run.player,"对练已停止，完成 "+run.series.completed()+(PvpMapSupport.enabled()?" 场。原因：":" / 5 场。原因：")+reason);
    }
    private static Map<String,Object> snapshot(Run run){
        var data=new LinkedHashMap<String,Object>();data.put("schema",1);data.put("source",(Boolean.getBoolean("mineagent.humanDuelFixture")||Boolean.getBoolean("mineagent.modernPvpFixture")||Boolean.getBoolean("mineagent.modernCombatFixture")||Boolean.getBoolean("mineagent.modernLifecycleFixture"))?"FIXTURE_ONLY":PvpMapSupport.enabled()?"PVP_MAP":"HUMAN_DUEL");data.put("series",run.id.toString());data.put("status",run.series.phase().name());data.put("error",run.error);data.put("roundLimitSeconds",180);data.put("requiredRounds",PvpMapSupport.enabled()?0:5);data.put("completedRounds",run.series.completed());data.put("human",run.owner.toString());data.put("model",run.model);data.put("initialModelHash",run.modelHash);data.put("boost",false);data.put("onlineUpdates",false);data.put("equipment",List.of("minecraft:diamond_sword","minecraft:iron_helmet","minecraft:iron_chestplate","minecraft:iron_leggings","minecraft:iron_boots"));data.put("matches",List.copyOf(run.matches));
        if(run.match!=null&&(run.series.phase()==HumanDuelSeries.Phase.FIGHTING||run.series.phase()==HumanDuelSeries.Phase.STOPPED))data.put("partial",Map.of("match",run.match.toString(),"frames",List.copyOf(run.frames),"damageEvents",List.copyOf(run.damage),"samples",run.partialSamples));
        return data;
    }
    private static void persist(Run run){
        var data=snapshot(run);run.saved=run.saved.thenRunAsync(()->{try{Files.createDirectories(run.directory);var temp=Files.createTempFile(run.directory,"duel-",".tmp");try{Files.writeString(temp,JSON.writeValueAsString(data));Files.move(temp,run.directory.resolve("series.json"),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}finally{Files.deleteIfExists(temp);}}catch(Exception e){throw new CompletionException(e);}},IO);
    }
    private static void tell(ServerPlayer p,String message){p.sendSystemMessage(Component.literal("[DivZero 对练] "+message));}
    private static void menu(ServerPlayer p,Run run){
        if(PvpMapSupport.enabled()){tell(p,"PvP 训练地图 · 不限局数 · 每局最多 3 分钟。按 T 点击装备设置，准备后开始。");p.sendSystemMessage(Component.literal("[选择双方装备 / 开始下一局]").withStyle(v->v.withColor(0x55FF55).withClickEvent(new ClickEvent.RunCommand("/ai duel equip"))).append("  ").append(Component.literal("[停止本局]").withStyle(v->v.withColor(0xFF5555).withClickEvent(new ClickEvent.RunCommand("/ai duel stop")))));PvpMapSupport.push(p,run==null?"READY":run.series.phase().name(),180,false);return;}
        if(run!=null&&run.series.phase()==HumanDuelSeries.Phase.COMPLETE){tell(p,"5 场已结束。正在保存真实对战数据，随后可进行赛后候选训练；不会自动替换当前模型。");return;}
        if(run!=null&&run.series.phase()==HumanDuelSeries.Phase.STOPPED){tell(p,"本轮已停止。已完成场次数据保留；重新开始请启动新的隔离对练世界。");return;}
        tell(p,"真人对练：钻石剑＋铁套，5 场，每场最多 3 分钟；死亡立即结束，超时平局。你自己操作角色。"+(run==null?"":"已完成 "+run.series.completed()+" 场。"));
        p.sendSystemMessage(Component.literal("[准备 / 下一场]").withStyle(s->s.withColor(0x55FF55).withClickEvent(new ClickEvent.RunCommand("/ai duel ready"))).append("  ").append(Component.literal("[停止对练]").withStyle(s->s.withColor(0xFF5555).withClickEvent(new ClickEvent.RunCommand("/ai duel stop")))));
    }
    @SubscribeEvent public static void stopping(net.neoforged.neoforge.event.server.ServerStoppingEvent event){var run=RUNS.get(event.getServer());if(run!=null){abort(run,"SERVER_STOPPED");try{run.saved.get(5,TimeUnit.SECONDS);}catch(Exception e){System.getLogger(NativeHumanDuel.class.getName()).log(System.Logger.Level.ERROR,"Human duel data save failed",e);}}}
    @SubscribeEvent public static void stopped(net.neoforged.neoforge.event.server.ServerStoppedEvent event){RUNS.remove(event.getServer());OFFERED.remove(event.getServer());mapActive=false;}
    private static boolean active(Run run){return run!=null&&Set.of(HumanDuelSeries.Phase.COUNTDOWN,HumanDuelSeries.Phase.STARTING,HumanDuelSeries.Phase.FIGHTING).contains(run.series.phase());}
    private static int loadout(ServerPlayer p,long revision,String actor,String slot,String item){
        if(!PvpMapSupport.enabled()||!p.level().getServer().isSingleplayerOwner(p.nameAndId())||active(RUNS.get(p.level().getServer())))return 0;
        try{if(slot.equals("wool")){if(!Set.of("true","false").contains(item))throw new IllegalArgumentException("羊毛选项无效");PvpMapSupport.save(p,PvpMapSupport.profile(p).wool(revision,actor,Boolean.parseBoolean(item)));PvpMapSupport.push(p,"READY",180,true);return 1;}if(!PvpMapSupport.choices(slot).contains(item))throw new IllegalArgumentException("所选物品不适合该部位");PvpMapSupport.save(p,PvpMapSupport.profile(p).gear(revision,actor,slot,item));PvpMapSupport.push(p,"READY",180,true);return 1;}catch(Exception e){tell(p,"装备未更改："+e.getMessage());PvpMapSupport.push(p,"READY",180,true);return 0;}
    }
    @SubscribeEvent public static void started(net.neoforged.neoforge.event.server.ServerStartedEvent event){
        var server=event.getServer();if(!server.isSingleplayer())return;
        var marker=server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("data/divzero-pvp-map.json");
        try{if(ENABLED&&Boolean.getBoolean("mineagent.pvpMap")){Files.createDirectories(marker.getParent());Files.writeString(marker,"{\"schema\":1,\"map\":\"divzero_pvp\"}");}
            mapActive=Files.isRegularFile(marker)&&Files.size(marker)<1024&&JSON.readTree(Files.readString(marker)).path("map").asText().equals("divzero_pvp");
        }catch(Exception e){throw new IllegalStateException("PVP_MAP_MARKER_INVALID",e);}
    }
    private static void archiveRound(Run run,Object result){
        var file=run.directory.resolve("round-"+run.match+".json");run.saved=run.saved.thenRunAsync(()->{try{Files.createDirectories(file.getParent());Files.writeString(file,JSON.writeValueAsString(result),StandardOpenOption.CREATE_NEW);}catch(Exception e){throw new CompletionException(e);}},IO);
    }
    private static void decorate(ServerPlayer p){
        for(int x=-16;x<=16;x++)for(int z=784;z<=816;z++)p.level().setBlock(new net.minecraft.core.BlockPos(x,100,z),(Math.abs(x)==16||z==784||z==816?net.minecraft.world.level.block.Blocks.CHISELED_STONE_BRICKS:(x+z)%2==0?net.minecraft.world.level.block.Blocks.SMOOTH_STONE:net.minecraft.world.level.block.Blocks.POLISHED_ANDESITE).defaultBlockState(),2);
        for(int x=-17;x<=17;x++)for(int z=783;z<=817;z++)if(Math.abs(x)==17||z==783||z==817)for(int y=101;y<=105;y++)p.level().setBlock(new net.minecraft.core.BlockPos(x,y,z),(y==105?net.minecraft.world.level.block.Blocks.SEA_LANTERN:y>=103?net.minecraft.world.level.block.Blocks.GLASS:net.minecraft.world.level.block.Blocks.STONE_BRICKS).defaultBlockState(),2);
        PvpMapArena.lobby(p);
    }
    public static boolean pvpParticipant(ServerPlayer p){var run=RUNS.get(p.level().getServer());return PvpMapSupport.enabled()&&run!=null&&run.series.phase()==HumanDuelSeries.Phase.FIGHTING&&run.finishReason.isEmpty()&&(p==run.player||p==run.ai)&&p.level()==run.level;}
    public static boolean woolAction(ServerPlayer p,net.minecraft.core.BlockPos pos){return pvpParticipant(p)&&PvpMapArena.field(pos);}
    @SubscribeEvent public static void protectArena(net.neoforged.neoforge.event.level.block.BreakBlockEvent event){if(PvpMapSupport.enabled()&&event.getLevel() instanceof net.minecraft.server.level.ServerLevel level&&level.dimension().equals(net.minecraft.world.level.Level.OVERWORLD)&&PvpMapArena.protectedArea(event.getPos())&&!(event.getPlayer() instanceof ServerPlayer p&&woolAction(p,event.getPos())&&level.getBlockState(event.getPos()).is(net.minecraft.tags.BlockTags.WOOL))){event.setCanceled(true);event.setNotifyClient(true);}}
    @SubscribeEvent public static void placing(net.neoforged.neoforge.event.level.BlockEvent.EntityPlaceEvent event){
        if(!PvpMapSupport.enabled()||!(event.getLevel() instanceof net.minecraft.server.level.ServerLevel level)||!level.dimension().equals(net.minecraft.world.level.Level.OVERWORLD))return;
        var positions=new ArrayList<net.minecraft.core.BlockPos>();if(event instanceof net.neoforged.neoforge.event.level.BlockEvent.EntityMultiPlaceEvent multi)for(var snapshot:multi.getReplacedBlockSnapshots())positions.add(snapshot.getPos());else positions.add(event.getPos());
        for(var pos:positions)if(PvpMapArena.protectedArea(pos)&&(!(event.getEntity() instanceof ServerPlayer p)||!woolAction(p,pos)||!level.getBlockState(pos).is(net.minecraft.tags.BlockTags.WOOL))){event.setCanceled(true);return;}
    }
    @SubscribeEvent public static void respawned(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerRespawnEvent event){if(PvpMapSupport.enabled()&&event.getEntity() instanceof ServerPlayer p&&!(p instanceof MineAgentPlayer)){p.stopUsingItem();PvpMapArena.returnToLobby(p);dev.mineagent.runtime.neoforge.body.NativeInventorySync.full(p);}}
    private static void fixtureTick(MinecraftServer server){
        if(!Boolean.getBoolean("mineagent.humanDuelFixture")||server.getTickCount()<160)return;
        var run=RUNS.get(server);
        try{
            if(run==null){var p=server.getPlayerList().getPlayers().stream().filter(v->!(v instanceof MineAgentPlayer)).findFirst().orElse(null);if(p!=null&&!PvpMapSupport.enabled())command(p,"ready");return;}
            if(run.fixtureStage==2)return;
            if(PvpMapSupport.enabled()&&run.series.phase()==HumanDuelSeries.Phase.FIGHTING&&(!run.player.getItemBySlot(EquipmentSlot.CHEST).is(Items.DIAMOND_CHESTPLATE)||!run.ai.getMainHandItem().is(Boolean.getBoolean("mineagent.rangedContactFixture")?Items.BOW:Items.IRON_SWORD)))throw new IllegalStateException("PVP_MAP_NATIVE_LOADOUT_MISMATCH");
            if(PvpMapSupport.enabled()&&run.series.phase()==HumanDuelSeries.Phase.FIGHTING&&!run.woolFixtureDone)checkWool(run);
            if(Boolean.getBoolean("mineagent.rangedContactFixture")&&run.series.phase()==HumanDuelSeries.Phase.FIGHTING)checkRangedContact(run);
            if((!Boolean.getBoolean("mineagent.rangedContactFixture")||run.rangedFixtureStage>=4)&&run.series.phase()==HumanDuelSeries.Phase.FIGHTING&&run.aiHits>0&&server.getTickCount()-run.startTick>50&&run.finishReason.isEmpty())run.ai.hurtServer(run.ai.level(),run.ai.damageSources().genericKill(),1000);
            if(run.series.phase()==HumanDuelSeries.Phase.BETWEEN&&run.saved.isDone()&&(!PvpMapSupport.enabled()||System.nanoTime()-run.endNanos>3_000_000_000L)){
                var data=JSON.valueToTree(run.matches.getFirst());
                if(run.series.completed()!=1||run.aiHits==0||data.path("samples").isEmpty()||!data.path("initialModelHash").equals(data.path("finalModelHash")))throw new IllegalStateException("HUMAN_FIXTURE_SAMPLES_DAMAGE_OR_FREEZE");
                if(!run.player.isAlive())throw new IllegalStateException("HUMAN_FIXTURE_UNEXPECTED_HUMAN_DEATH");
                if(PvpMapSupport.enabled()){var profile=PvpMapSupport.profile(run.player);if(profile.rounds()!=1||profile.wins()!=1||profile.averageKill()==null||!profile.human().get("chest").equals("minecraft:diamond_chestplate")||!profile.ai().get("mainhand").equals(Boolean.getBoolean("mineagent.rangedContactFixture")?"minecraft:bow":"minecraft:iron_sword"))throw new IllegalStateException("PVP_MAP_PROFILE_NOT_APPLIED");}
                command(run.player,"ready");run.fixtureStage=3;
            }
            if(run.fixtureStage==3&&run.cleanup==null&&run.ai!=null){if(PvpMapSupport.enabled()&&!run.player.level().getBlockState(new net.minecraft.core.BlockPos(-4,101,800)).isAir())throw new IllegalStateException("PVP_WOOL_NOT_CLEARED");command(run.player,"stop");run.fixtureStage=1;}
            if(run.series.phase()==HumanDuelSeries.Phase.STOPPED){
                if(run.fixtureStage!=1||run.ai!=null||run.series.completed()!=1||run.series.ready(System.nanoTime()))throw new IllegalStateException("HUMAN_FIXTURE_STOP_FAILED_"+run.error);
                run.fixtureStage=2;var proof=new LinkedHashMap<String,Object>();proof.put("source","FIXTURE_ONLY");proof.put("completedFixtureRounds",1);proof.put("aiNativeHits",JSON.valueToTree(run.matches.getFirst()).path("ai").path("hits").asInt());proof.put("frozenSamples",true);proof.put("deathImmediateStop",true);proof.put("secondCountdownCancelled",true);proof.put("woolPlacementBreakCleanup",run.woolFixtureDone);proof.put("rangedCycles",run.rangedFixtureStage/2);proof.put("maxRangedSeparation",run.rangedSeparation);proof.put("rangedSprintTicks",run.rangedSprint);fixtureResult(server,"PASS",proof);
            }
            if(server.getTickCount()>1800)throw new IllegalStateException("HUMAN_FIXTURE_TIMEOUT");
        }catch(Throwable error){if(run!=null){run.fixtureStage=2;abort(run,error.toString());}fixtureResult(server,"FAILED",Map.of("error",error.toString(),"source","FIXTURE_ONLY"));}
    }
    private static void checkWool(Run run){
        var p=run.player;var floor=new net.minecraft.core.BlockPos(-4,100,800);var wool=floor.above();
        if(p.getInventory().getItem(1).getCount()!=64||!p.getInventory().getItem(1).is(Items.WHITE_WOOL)||run.ai.getInventory().getItem(1).getCount()!=64)throw new IllegalStateException("PVP_WOOL_STACK_MISSING");
        p.getInventory().setSelectedSlot(1);var hit=new net.minecraft.world.phys.BlockHitResult(new Vec3(-3.5,101,800.5),net.minecraft.core.Direction.UP,floor,false);
        if(!p.isWithinBlockInteractionRange(floor,0))throw new IllegalStateException("PVP_WOOL_FIXTURE_REACH");
        p.gameMode.useItemOn(p,p.level(),p.getMainHandItem(),net.minecraft.world.InteractionHand.MAIN_HAND,hit);
        if(!p.level().getBlockState(wool).is(net.minecraft.tags.BlockTags.WOOL)||p.getInventory().getItem(1).getCount()!=63)throw new IllegalStateException("PVP_WOOL_NATIVE_PLACE_FAILED");
        if(!p.gameMode.destroyBlock(wool)||!p.level().getBlockState(wool).isAir())throw new IllegalStateException("PVP_WOOL_NATIVE_BREAK_FAILED");
        if(p.gameMode.destroyBlock(floor)||p.level().getBlockState(floor).isAir())throw new IllegalStateException("PVP_ARENA_FLOOR_NOT_PROTECTED");
        p.gameMode.useItemOn(p,p.level(),p.getMainHandItem(),net.minecraft.world.InteractionHand.MAIN_HAND,hit);p.getInventory().setSelectedSlot(0);dev.mineagent.runtime.neoforge.body.NativeInventorySync.full(p);run.woolFixtureDone=true;
    }
    private static void checkRangedContact(Run run){
        if(run.rangedFixtureStage>=4)return;var p=run.player;var ai=run.ai;var snapshot=JSON.valueToTree(SkillRuntime.get(run.server).snapshot(p,ai.agentId()));
        for(var skill:snapshot.path("skills"))run.rangedSprint=Math.max(run.rangedSprint,skill.path("session").path("counters").path("rangedEscapeSprintTicks").asLong());
        if(run.rangedFixtureStage%2==1){run.rangedSeparation=Math.max(run.rangedSeparation,p.distanceTo(ai));run.rangedCycleSeparation=Math.max(run.rangedCycleSeparation,p.distanceTo(ai));}
        if(run.rangedFixtureStage%2==0){double x=Math.clamp(ai.getX()-1.4,-14,14),z=Math.clamp(ai.getZ(),786,814);p.teleportTo(p.level(),x,101,z,Set.of(),-90,0,true);p.resetAttackStrengthTicker();run.rangedAmmo=java.util.stream.IntStream.range(0,36).map(i->ai.getInventory().getItem(i).is(Items.ARROW)?ai.getInventory().getItem(i).getCount():0).sum();run.rangedCycleSeparation=0;run.rangedFixtureStage++;}
        else{int arrows=java.util.stream.IntStream.range(0,36).map(i->ai.getInventory().getItem(i).is(Items.ARROW)?ai.getInventory().getItem(i).getCount():0).sum();if(arrows<run.rangedAmmo&&run.rangedCycleSeparation>=7&&run.rangedSprint>0)run.rangedFixtureStage++;}
        if(run.server.getTickCount()%40==0){var evidence=Map.of("stage",run.rangedFixtureStage,"separation",run.rangedSeparation,"sprintTicks",run.rangedSprint,"skills",snapshot);IO.execute(()->{try{Files.writeString(run.directory.getParent().getParent().resolve("ranged-contact-progress.json"),JSON.writeValueAsString(evidence));}catch(Exception ignored){}});}
        if(run.server.getTickCount()-run.startTick>1400)throw new IllegalStateException("RANGED_CONTACT_DID_NOT_RESUME_FIRE_"+run.rangedFixtureStage+"_"+run.rangedSeparation+"_"+run.rangedSprint);
    }
    private static void fixtureResult(MinecraftServer server,String status,Object detail){
        IO.execute(()->{try{Files.writeString(server.getServerDirectory().resolve("human-duel-fixture.json"),JSON.writeValueAsString(Map.of("status",status,"detail",detail)));}catch(Exception e){System.getLogger(NativeHumanDuel.class.getName()).log(System.Logger.Level.ERROR,"Fixture output failed",e);}});
    }
    private static String hash(String source){try{return dev.mineagent.runtime.core.packages.RuntimePackageCanonicalizer.sha256(source);}catch(Exception e){throw new IllegalStateException(e);}}
    private NativeHumanDuel(){}
}
