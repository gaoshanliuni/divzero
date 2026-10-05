package dev.mineagent.runtime.legacy189;

import com.google.gson.*;
import net.minecraft.block.Block;
import net.minecraft.entity.*;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.item.EntityXPOrb;
import net.minecraft.entity.projectile.EntityArrow;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.*;
import net.minecraft.item.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.*;
import net.minecraftforge.event.entity.living.*;
import net.minecraftforge.event.world.BlockEvent;
import net.minecraftforge.fml.common.eventhandler.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Single-owner arena rounds. Ordinary worlds never receive this controller. */
public final class NativeDuel {
    public static final String[] SLOTS = {"head", "chest", "legs", "feet", "mainhand", "offhand"};
    private static final Map<UUID, Session> SESSIONS = new HashMap<UUID, Session>();
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private NativeDuel() { }
    public static boolean allowed(EntityPlayerMP player) {
        MinecraftServer server = MinecraftServer.getServer();
        return !(player instanceof NativeAgent) && player.dimension == 0 && server.isSinglePlayer()
                && player.getName().equals(server.getServerOwner()) && NativeArena.ready();
    }
    public static Session session(EntityPlayerMP player) {
        if (!allowed(player)) throw new SecurityException("仅 PvP 地图的单人存档拥有者可操作对局");
        Session value = SESSIONS.get(player.getUniqueID());
        if (value == null) { value = new Session(player); SESSIONS.put(player.getUniqueID(), value); }
        value.human = player; return value;
    }
    public static List<String> choices(int slot) {
        if (slot < 0 || slot >= 6) throw new IllegalArgumentException("DUEL_SLOT");
        List<String> result = new ArrayList<String>(); result.add("minecraft:air");
        if (slot < 4) for (String material : new String[] {"leather", "chainmail", "iron", "golden", "diamond"})
            result.add("minecraft:" + material + "_" + new String[] {"helmet", "chestplate", "leggings", "boots"}[slot]);
        else if (slot == 4) {
            for (String type : new String[] {"sword", "axe"}) for (String material : new String[] {"wooden", "stone", "iron", "golden", "diamond"}) result.add("minecraft:" + material + "_" + type);
            result.add("minecraft:bow");
        } else { result.add("minecraft:golden_apple"); result.add("minecraft:cooked_beef"); }
        return result;
    }
    public static void command(EntityPlayerMP player, String action) {
        Session run = session(player);
        if (action.equals("ready")) start(run);
        else if (action.equals("stop")) finish(run, "CANCELLED");
        else if (!action.equals("equip") && !action.equals("status")) throw new IllegalArgumentException("/ai duel equip|ready|stop|status");
        push(run, action.equals("equip"));
    }
    public static void choose(EntityPlayerMP player, long revision, String actor, int slot, String item, Boolean wool) {
        Session run = session(player);
        if (run.active() || run.revision != revision) throw new IllegalStateException("对局或装备已变化，请重新打开面板");
        if (!actor.equals("human") && !actor.equals("ai")) throw new IllegalArgumentException("DUEL_ACTOR");
        if (wool != null) { if (actor.equals("human")) run.humanWool = wool; else run.aiWool = wool; }
        else { if (!choices(slot).contains(item)) throw new IllegalArgumentException("DUEL_EQUIPMENT"); (actor.equals("human") ? run.humanGear : run.aiGear)[slot] = item; }
        run.revision++; push(run, false);
    }
    private static void start(Session run) {
        if (run.active()) throw new IllegalStateException("已有对局进行中");
        if (!run.human.isEntityAlive()) throw new IllegalStateException("请先使用原生重生");
        if (!ModernCombat.serverEnabled) throw new IllegalStateException("PvP 地图需要启用现代近战规则");
        LegacyPolicy.get();
        // Starting an owned arena is explicit consent to enable its local controller.
        // Validate the map, phase and policy first; this does not grant command permissions.
        if (!NativeRuntime.enabled(run.human)) NativeRuntime.setEnabled(run.human, true);
        run.phase = "CLEANING"; run.cleanCursor = 0; run.result = ""; run.match = UUID.randomUUID(); run.revision++;
        run.humanDied = run.aiDied = false; run.pendingDeath = 0; run.travel = 0; run.meleeHits = 0;
        run.policyStart = LegacyPolicy.get().inferences(); run.archive = null;
    }
    private static void equip(EntityPlayerMP player, String[] gear, boolean wool, int woolColor) {
        player.inventory.clear(); player.inventory.setItemStack(null); NativeOffhand.get(player).set(null);
        for (int slot = 0; slot < 6; slot++) {
            if (!choices(slot).contains(gear[slot])) throw new IllegalArgumentException("DUEL_EQUIPMENT");
            ItemStack stack = stack(gear[slot]);
            if (slot < 4) player.setCurrentItemOrArmor(4 - slot, stack);
            else if (slot == 4) player.inventory.setInventorySlotContents(0, stack);
            else if (stack != null && stack.getItem() instanceof ItemFood) {
                // 1.8.9 uses food from the selected main-hand hotbar slot.
                stack.stackSize = 16; player.inventory.setInventorySlotContents(2, stack);
            } else NativeOffhand.get(player).set(stack);
        }
        if (gear[4].equals("minecraft:bow")) for (int slot = 9; slot < 13; slot++) player.inventory.setInventorySlotContents(slot, new ItemStack(Items.arrow, 64));
        if (wool) player.inventory.setInventorySlotContents(1, new ItemStack(Blocks.wool, 64, woolColor));
        player.clearActivePotions(); player.setAbsorptionAmount(0); player.extinguish();
        player.inventory.currentItem = 0; player.setHealth(player.getMaxHealth()); player.getFoodStats().setFoodLevel(20);
        player.inventoryContainer.detectAndSendChanges(); NativeNetwork.sync(player);
    }
    public static ItemStack stack(String name) {
        if (name.equals("minecraft:air")) return null;
        Item item = Item.itemRegistry.getObject(new ResourceLocation(name)); if (item == null) throw new IllegalArgumentException("DUEL_ITEM_MISSING");
        return new ItemStack(item);
    }
    public static void tick() {
        MinecraftServer server = MinecraftServer.getServer(); int tick = server.getTickCounter();
        if (!NativeArena.ready()) return;
        if (tick % 20 == 0 && SESSIONS.values().stream().noneMatch(Session::active)) clearDrops(server.worldServerForDimension(0));
        for (EntityPlayerMP player : new ArrayList<EntityPlayerMP>(server.getConfigurationManager().getPlayerList())) {
            if (allowed(player) && player.ticksExisted > 40 && !SESSIONS.containsKey(player.getUniqueID())) { Session run = session(player); NativeArena.lobby(player); push(run, true); }
        }
        for (Session run : new ArrayList<Session>(SESSIONS.values())) {
            EntityPlayerMP online = server.getConfigurationManager().getPlayerByUUID(run.owner);
            if (online == null || !allowed(online)) { finish(run, "DISCONNECTED"); SESSIONS.remove(run.owner); continue; }
            run.human = online;
            try {
                if (run.active() && (!NativeRuntime.enabled(online) || !ModernCombat.serverEnabled)) finish(run, "AUTHORITY_CHANGED");
                if (run.phase.equals("CLEANING")) {
                    for (int count = 0; count < 4096 && run.cleanCursor < 33 * 33 * 155; count++) {
                        int cursor = run.cleanCursor++; BlockPos pos = new BlockPos(-16 + cursor % 33, 101 + cursor / (33 * 33), 784 + cursor / 33 % 33);
                        if (online.worldObj.getBlockState(pos).getBlock() == Blocks.wool) online.worldObj.setBlockToAir(pos);
                    }
                    if (run.cleanCursor == 33 * 33 * 155) {
                        clearDrops(online.worldObj);
                        online.setGameType(net.minecraft.world.WorldSettings.GameType.SURVIVAL);
                        equip(online, run.humanGear, run.humanWool, 0);
                        run.ai = NativeRuntime.createTransient(online, "PvP 陪练"); equip(run.ai, run.aiGear, run.aiWool, 3);
                        run.navigation.reset(run.ai);
                        run.melee.reset();
                        place(run); run.phase = "COUNTDOWN"; run.deadline = tick + 100; run.revision++;
                    }
                } else if (run.phase.equals("COUNTDOWN")) {
                    run.ai.stopActions();
                    if (tick >= run.deadline) { place(run); run.phase = "FIGHTING"; run.started = System.nanoTime(); run.revision++; }
                } else if (run.phase.equals("FIGHTING")) {
                    if (!online.isEntityAlive()) run.humanDied = true;
                    if (run.ai == null || !run.ai.isEntityAlive()) run.aiDied = true;
                    if ((run.humanDied || run.aiDied) && (run.pendingDeath == 0 || tick > run.pendingDeath)) finish(run, run.humanDied && run.aiDied ? "DOUBLE_KO" : run.humanDied ? "AI_WON" : "HUMAN_WON");
                    else if (run.seconds() >= 180) finish(run, "TIME_LIMIT_DRAW");
                    else if (!inside(online) || !inside(run.ai)) finish(run, "LEFT_ARENA");
                    else if (!run.humanDied && !run.aiDied) brain(run, tick);
                }
                if (tick % 10 == 0) push(run, false);
            } catch (Exception failure) { LegacyMod.logger.error("Duel stopped", failure); finish(run, "FAILED"); online.addChatMessage(new ChatComponentText("[DivZero] 本局已停止：" + failure.getClass().getSimpleName())); }
        }
    }
    private static void place(Session run) {
        run.human.playerNetServerHandler.setPlayerLocation(-4.5, 101, 800.5, -90, 0); run.ai.playerNetServerHandler.setPlayerLocation(5.5, 101, 800.5, 90, 0);
        for (EntityPlayerMP actor : new EntityPlayerMP[] {run.human, run.ai}) { actor.motionX = actor.motionY = actor.motionZ = 0; actor.fallDistance = 0; }
        run.lastX = run.ai.posX; run.lastZ = run.ai.posZ;
    }
    private static boolean inside(Entity entity) { return entity != null && entity.posX > -17 && entity.posX < 18 && entity.posZ > 783 && entity.posZ < 818 && entity.posY >= 97; }
    private static void brain(Session run, int tick) {
        NativeAgent actor = run.ai; EntityPlayerMP target = run.human;
        run.travel += Math.hypot(actor.posX - run.lastX, actor.posZ - run.lastZ); run.lastX = actor.posX; run.lastZ = actor.posZ;
        double distance = actor.getDistanceToEntity(target), dx = target.posX - actor.posX, dz = target.posZ - actor.posZ;
        boolean bow = actor.getHeldItem() != null && actor.getHeldItem().getItem() instanceof ItemBow;
        double flight = bow ? distance / 3 : 0, aimX = dx + target.motionX * flight, aimZ = dz + target.motionZ * flight;
        double aimY = target.posY + target.getEyeHeight() * .8 - actor.posY - actor.getEyeHeight() + .025 * flight * flight;
        actor.rotationYaw = (float) Math.toDegrees(Math.atan2(aimZ, aimX)) - 90;
        actor.rotationPitch = (float) -Math.toDegrees(Math.atan2(aimY, Math.hypot(aimX, aimZ))); actor.rotationYawHead = actor.rotationYaw;
        if (!bow) { run.melee.tick(run, tick); return; }
        double desired = bow ? 8 : 2.5;
        boolean navigating = run.navigation.move(actor, target, tick, bow);
        if (run.navigation.recovering()) { actor.clearItemInUse(); return; }
        double best = Double.POSITIVE_INFINITY; float forward = 0, strafe = 0; boolean jump = false;
        if (!navigating) for (float[] candidate : new float[][] {{0,0},{1,0},{1,.65f},{1,-.65f},{0,1},{0,-1},{-1,0},{-1,.65f},{-1,-.65f}}) {
            double yaw = Math.toRadians(actor.rotationYaw), vx = (candidate[1] * Math.cos(yaw) - candidate[0] * Math.sin(yaw)) * 1.2;
            double vz = (candidate[0] * Math.cos(yaw) + candidate[1] * Math.sin(yaw)) * 1.2;
            int clearance = clearance(actor, vx, vz); if (clearance < 0) continue;
            double next = Math.hypot(dx - vx, dz - vz), progress = distance - next;
            double risk = Math.max(0, 2.1 - next) * 20 + (clearance > 0 ? 4 : 0);
            double[] features = {actor.getHealth() / Math.max(1, actor.getMaxHealth()), clamp(distance / 16, 0, 2), Math.hypot(actor.motionX, actor.motionZ) / .4, 0,
                    1, clamp(progress / 8, -1, 1), clamp(risk / 80, 0, 2), .125, clamp(vx / 8, -1, 1), clamp(vz / 8, -1, 1), 0,
                    1, 5d / 8, actor.onGround ? 0 : 1, ModernCombat.baseDamage(actor) / 10, 0};
            double score = Math.abs(next - desired) * 4 + risk + LegacyPolicy.get().cost(features) * 2;
            if (tick < run.retreatUntil && candidate[0] > 0) score += 10;
            if (score < best) { best = score; forward = candidate[0]; strafe = candidate[1]; jump = clearance > 0; }
        }
        if (!navigating) {
            actor.moveForward = forward; actor.moveStrafing = strafe; actor.setSprinting(forward > 0 && distance > (bow ? 10 : 3.3) && tick >= run.retreatUntil);
            if (jump && actor.onGround) actor.requestJump();
        }
        if (bow) {
            if (distance < 3 || !actor.canEntityBeSeen(target)) { actor.clearItemInUse(); return; }
            if (!actor.isUsingItem()) actor.setItemInUse(actor.getHeldItem(), 72000);
            else if (actor.getItemInUseDuration() >= 20) actor.stopUsingItem();
        }
    }
    private static int clearance(NativeAgent actor, double dx, double dz) {
        int jump = 0;
        for (int step = 1; step <= 3; step++) {
            double x = actor.posX + dx * step / 3, z = actor.posZ + dz * step / 3;
            if (x < -15.9 || x > 16.9 || z < 784.1 || z > 816.9) return -1;
            BlockPos feet = new BlockPos(x, actor.posY, z);
            if (!actor.worldObj.isBlockLoaded(feet)) return -1;
            if (!actor.worldObj.isAirBlock(feet)) {
                if (!actor.worldObj.getBlockState(feet).getBlock().isFullCube() || !actor.worldObj.isAirBlock(feet.up()) || !actor.worldObj.isAirBlock(feet.up(2))) return -1;
                jump = 1;
            } else if (!actor.worldObj.isAirBlock(feet.up())) return -1;
            if (!actor.worldObj.getBlockState(feet.down()).getBlock().isFullCube() && !actor.worldObj.getBlockState(feet.down(2)).getBlock().isFullCube()) return -1;
        }
        return jump;
    }
    private static double clamp(double value, double min, double max) { return Math.max(min, Math.min(max, value)); }
    private static void finish(Session run, String outcome) {
        if (!run.active()) return;
        double seconds = run.phase.equals("FIGHTING") ? Math.min(180, run.seconds()) : 0;
        boolean win = outcome.equals("HUMAN_WON"), loss = outcome.equals("AI_WON"), draw = outcome.equals("TIME_LIMIT_DRAW") || outcome.equals("DOUBLE_KO");
        if (win || loss || draw) { run.rounds++; if (win) { run.wins++; run.killSeconds += seconds; } else if (loss) { run.losses++; run.deathSeconds += seconds; } else run.draws++; }
        JsonObject record = snapshot(run, false); record.addProperty("outcome", outcome); record.addProperty("seconds", seconds);
        record.addProperty("source", NativeFixture.requested() ? "FIXTURE_ONLY" : "HUMAN_PVP"); record.addProperty("match", run.match.toString());
        record.addProperty("policyHash", LegacyPolicy.get().hash()); record.addProperty("policyInferences", LegacyPolicy.get().inferences() - run.policyStart);
        record.addProperty("aiTravel", run.travel); record.addProperty("aiAppliedMeleeHits", run.meleeHits); record.addProperty("boost", false);
        record.addProperty("controller", "LEGACY_ARENA_CANDIDATES_WITH_ORIGINAL_WEIGHTS"); record.addProperty("completeNeoforgeBehaviorParity", false);
        record.addProperty("navigationPlans", run.navigation.plans); record.addProperty("stuckRecoveries", run.navigation.stuckRecoveries);
        record.addProperty("escapeWoolBroken", run.navigation.woolBroken); record.addProperty("attackCooldown", false);
        record.addProperty("escapeWoolPlaced", run.navigation.placed()); record.addProperty("escapeMaterialsConsumed", run.navigation.consumed());
        record.add("meleeControl", run.melee.evidence());
        Path output = run.human.getServerForPlayer().getSaveHandler().getWorldDirectory().toPath().resolve("data/divzero-pvp-rounds").resolve(run.match + ".json");
        byte[] bytes = JSON.toJson(record).getBytes(StandardCharsets.UTF_8);
        run.archive = CompletableFuture.runAsync(() -> { try { Files.createDirectories(output.getParent()); Files.write(output, bytes, StandardOpenOption.CREATE_NEW); } catch (Exception failure) { throw new CompletionException(failure); } });
        run.archive.whenComplete((unused, failure) -> { if (failure != null) LegacyMod.logger.error("PvP result archive failed; no automatic replay", failure); });
        run.phase = "READY"; run.result = outcome; run.revision++;
        if (run.ai != null) { run.navigation.stop(run.ai); NativeRuntime.removeBody(run.ai); run.ai = null; }
        clearDrops(run.human.worldObj);
        if (run.human.isEntityAlive() && run.human.dimension == 0) NativeArena.lobby(run.human);
        push(run, false);
    }
    public static JsonObject snapshot(Session run, boolean open) {
        JsonObject value = new JsonObject(); value.addProperty("world", NativeRuntime.data().identity().toString()); value.addProperty("session", NativeRuntime.session(run.human).toString());
        value.addProperty("owner", run.owner.toString()); value.addProperty("revision", run.revision); value.addProperty("enabled", NativeRuntime.enabled(run.human)); value.addProperty("open", open); value.addProperty("phase", run.phase); value.addProperty("result", run.result);
        value.addProperty("remaining", run.phase.equals("COUNTDOWN") ? Math.max(0, (run.deadline - MinecraftServer.getServer().getTickCounter() + 19) / 20) : run.phase.equals("FIGHTING") ? Math.max(0, 180 - (int) run.seconds()) : 180);
        value.addProperty("rounds", run.rounds); value.addProperty("wins", run.wins); value.addProperty("losses", run.losses); value.addProperty("draws", run.draws);
        value.addProperty("winRate", run.rounds == 0 ? 0 : 100d * run.wins / run.rounds); value.addProperty("averageKill", run.wins == 0 ? -1 : run.killSeconds / run.wins);
        value.addProperty("averageDeath", run.losses == 0 ? -1 : run.deathSeconds / run.losses);
        value.add("human", JSON.toJsonTree(run.humanGear)); value.add("ai", JSON.toJsonTree(run.aiGear)); value.addProperty("humanWool", run.humanWool); value.addProperty("aiWool", run.aiWool);
        value.addProperty("aiEntity", run.ai == null ? -1 : run.ai.getEntityId());
        return value;
    }
    private static void push(Session run, boolean open) { DuelNetwork.send(run.human, snapshot(run, open)); }
    public static void stop() {
        for (Session run : new ArrayList<Session>(SESSIONS.values())) {
            finish(run, "WORLD_STOPPED");
            if (run.archive != null) try { run.archive.get(5, TimeUnit.SECONDS); } catch (Exception failure) { LegacyMod.logger.warn("Round archive not confirmed at shutdown", failure); }
        }
        SESSIONS.clear();
    }
    public static void respawn(EntityPlayerMP player) { if (allowed(player)) { Session run = session(player); NativeArena.lobby(player); push(run, true); } }
    public static final class Session {
        public final ArenaNavigator navigation = new ArenaNavigator();
        public final LegacyMeleeController melee = new LegacyMeleeController();
        public final UUID owner; public EntityPlayerMP human; public NativeAgent ai; public UUID match;
        public final String[] humanGear = defaults(), aiGear = defaults(); public boolean humanWool, aiWool;
        public String phase = "READY", result = ""; public long revision, started, policyStart; public int rounds, wins, losses, draws, deadline, cleanCursor, pendingDeath, retreatUntil, meleeHits;
        public double killSeconds, deathSeconds, travel, lastX, lastZ; public boolean humanDied, aiDied; public CompletableFuture<Void> archive;
        Session(EntityPlayerMP player) { owner = player.getUniqueID(); human = player; }
        public boolean active() { return phase.equals("CLEANING") || phase.equals("COUNTDOWN") || phase.equals("FIGHTING"); }
        public double seconds() { return (System.nanoTime() - started) / 1_000_000_000d; }
        private static String[] defaults() { return new String[] {"minecraft:iron_helmet", "minecraft:iron_chestplate", "minecraft:iron_leggings", "minecraft:iron_boots", "minecraft:diamond_sword", "minecraft:air"}; }
    }
    public static final class Events {
        @SubscribeEvent public void drops(LivingDropsEvent event) {
            if (event.entityLiving.worldObj.isRemote) return;
            for (Session run : SESSIONS.values()) if (run.active() && (event.entityLiving == run.human || event.entityLiving == run.ai)) {
                event.drops.clear(); event.setCanceled(true); return;
            }
        }
        @SubscribeEvent(priority = EventPriority.HIGHEST) public void protect(LivingAttackEvent event) {
            if (event.entityLiving.worldObj.isRemote || event.entityLiving.dimension != 0 || !NativeArena.ready() || !(event.entityLiving instanceof EntityPlayerMP)) return;
            if (NativeFixture.combatProbe(event.entityLiving, event.source.getEntity())) return;
            if (!NativeArena.protectedArea(new BlockPos(event.entityLiving))) return;
            boolean allowed = false;
            for (Session run : SESSIONS.values()) if (run.phase.equals("FIGHTING") && ModernCombat.serverEnabled && NativeRuntime.enabled(run.human)
                    && (event.entityLiving == run.human || event.entityLiving == run.ai)) {
                Entity source = event.source.getEntity(); allowed = source == run.human || source == run.ai || source == null; break;
            }
            if (!allowed) event.setCanceled(true);
        }
        @SubscribeEvent public void death(LivingDeathEvent event) {
            if (event.entityLiving.worldObj.isRemote) return;
            for (Session run : SESSIONS.values()) if (run.phase.equals("FIGHTING")) {
                if (event.entityLiving == run.human) run.humanDied = true;
                if (event.entityLiving == run.ai) run.aiDied = true;
                if (run.humanDied || run.aiDied) { run.pendingDeath = MinecraftServer.getServer().getTickCounter(); if (run.ai != null) run.ai.stopActions(); }
            }
        }
        @SubscribeEvent public void breaking(BlockEvent.BreakEvent event) {
            if (!event.world.isRemote && event.world.provider.getDimensionId() == 0 && NativeArena.ready() && NativeArena.protectedArea(event.pos)
                    && !(NativeArena.field(event.pos) && event.state.getBlock() == Blocks.wool)) event.setCanceled(true);
        }
        @SubscribeEvent public void placing(BlockEvent.PlaceEvent event) {
            if (!event.world.isRemote && event.world.provider.getDimensionId() == 0 && NativeArena.ready() && NativeArena.protectedArea(event.pos)
                    && !(NativeArena.field(event.pos) && event.placedBlock.getBlock() == Blocks.wool)) event.setCanceled(true);
        }
        @SubscribeEvent public void explosion(net.minecraftforge.event.world.ExplosionEvent.Detonate event) {
            if (!event.world.isRemote && event.world.provider.getDimensionId() == 0 && NativeArena.ready()) event.getAffectedBlocks().removeIf(NativeArena::protectedArea);
        }
    }
    static void clearDrops(net.minecraft.world.World world) {
        if (world == null || world.isRemote || world.provider.getDimensionId() != 0 || !NativeArena.ready()) return;
        AxisAlignedBB area = new AxisAlignedBB(-19, 0, 753, 20, 256, 820);
        for (EntityItem drop : world.getEntitiesWithinAABB(EntityItem.class, area)) drop.setDead();
        for (EntityXPOrb orb : world.getEntitiesWithinAABB(EntityXPOrb.class, area)) orb.setDead();
        for (EntityArrow arrow : world.getEntitiesWithinAABB(EntityArrow.class, area)) arrow.setDead();
    }
}
