package dev.mineagent.runtime.legacy189;

import com.google.gson.JsonObject;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.*;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.BlockPos;

/** Native GUI, inventory, arena protection, a real round and cancellation. */
public final class DuelVerification {
    public static volatile Instruction instruction;
    public static volatile String clientFailure;
    private static int sequence;
    private final NativeAgent persistentBody;
    private final JsonObject evidence;
    private final int began;
    private final NativeDuel.Session run;
    private int stage;
    public DuelVerification(EntityPlayerMP human, NativeAgent body, JsonObject evidence) {
        this.persistentBody = body; this.evidence = evidence; began = MinecraftServer.getServer().getTickCounter();
        run = NativeDuel.session(human);
        body.playerNetServerHandler.setPlayerLocation(4.5, 101, 766.5, 0, 0);
        NativeDuel.command(human, "equip"); request("human_chest");
    }
    private void request(String action) { instruction = new Instruction(++sequence, action, run.revision); }
    public boolean advance(EntityPlayerMP human) {
        if (clientFailure != null) throw new IllegalStateException("DUEL_GUI_FIXTURE: " + clientFailure);
        NativeDuel.Session run = NativeDuel.session(human);
        if (MinecraftServer.getServer().getTickCounter() - began > 2400)
            throw new IllegalStateException("DUEL_FIXTURE_TIMEOUT stage=" + stage + " phase=" + run.phase + " travel=" + run.travel + " hits=" + run.meleeHits);
        if (stage == 0 && run.humanGear[1].equals("minecraft:diamond_chestplate")) { stage++; request("ai_sword"); }
        else if (stage == 1 && run.aiGear[4].equals("minecraft:iron_sword")) { stage++; request("human_wool"); }
        else if (stage == 2 && run.humanWool) { stage++; request("ai_wool"); }
        else if (stage == 3 && run.aiWool) { evidence.addProperty("nativeLoadoutClicks", true); stage++; request("start"); }
        else if (stage == 4 && run.phase.equals("COUNTDOWN")) { stage++; request("place_wool"); }
        else if (stage == 5 && human.worldObj.getBlockState(new BlockPos(-5, 101, 801)).getBlock() == Blocks.wool
                && human.worldObj.getBlockState(new BlockPos(-4, 101, 801)).getBlock() == Blocks.wool) {
            require(human.inventory.getStackInSlot(1) != null && human.inventory.getStackInSlot(1).stackSize == 62, "NATIVE_WOOL_CONSUMPTION");
            ItemStack held = human.inventory.getStackInSlot(0); int selected = human.inventory.currentItem;
            human.inventory.setInventorySlotContents(0, new ItemStack(Items.diamond_pickaxe)); human.inventory.currentItem = 0;
            try { require(!human.theItemInWorldManager.tryHarvestBlock(new BlockPos(-5, 100, 800)), "ARENA_FLOOR_HARVESTED"); }
            finally { human.inventory.setInventorySlotContents(0, held); human.inventory.currentItem = selected; }
            require(human.worldObj.getBlockState(new BlockPos(-5, 100, 800)).getBlock() != Blocks.air, "ARENA_FLOOR_REMOVED");
            evidence.addProperty("nativeWoolConsumed", 2); evidence.addProperty("nativeFloorProtected", true); stage++; request("break_wool");
        } else if (stage == 6 && human.worldObj.isAirBlock(new BlockPos(-5, 101, 801))) { evidence.addProperty("nativeWoolBroken", true); stage++; instruction = null; }
        else if (stage == 7 && run.rounds == 1 && run.phase.equals("READY") && human.isEntityAlive()) {
            require(run.result.equals("AI_WON") && run.losses == 1 && run.wins == 0, "DUEL_DEATH_OUTCOME");
            require(run.travel > 3 && run.meleeHits > 0, "DUEL_NATIVE_MOVEMENT_AND_HITS");
            if (run.archive == null || !run.archive.isDone()) return false;
            run.archive.join(); evidence.addProperty("aiTravel", run.travel); evidence.addProperty("aiMeleeHits", run.meleeHits);
            evidence.addProperty("firstRoundSeconds", run.deathSeconds); evidence.addProperty("deathAndRespawnRound", true);
            require(human.worldObj.getBlockState(new BlockPos(-4, 101, 801)).getBlock() == Blocks.wool, "LEFTOVER_WOOL_MISSING");
            stage++; NativeDuel.command(human, "ready");
        } else if (stage == 8 && run.phase.equals("COUNTDOWN")) {
            require(human.worldObj.isAirBlock(new BlockPos(-4, 101, 801)), "NEXT_ROUND_WOOL_NOT_CLEARED");
            evidence.addProperty("nextRoundWoolCleared", true); stage++; NativeDuel.command(human, "equip"); request("stop");
        } else if (stage == 9 && run.phase.equals("READY") && run.result.equals("CANCELLED")) {
            require(run.rounds == 1 && run.losses == 1, "CANCEL_CHANGED_STATISTICS");
            if (run.archive == null || !run.archive.isDone()) return false;
            run.archive.join(); evidence.addProperty("cancelDidNotCount", true); evidence.addProperty("pvpRoundNativeVerified", true);
            NativeOffhand.get(human).set(new ItemStack(Items.iron_sword)); NativeNetwork.sync(human);
            persistentBody.playerNetServerHandler.setPlayerLocation(4.5, 101, 766.5, 0, 0);
            instruction = null; return true;
        }
        return false;
    }
    private static void require(boolean value, String code) { if (!value) throw new IllegalStateException(code); }
    public static final class Instruction {
        public final int sequence; public final String action; public final long revision;
        Instruction(int sequence, String action, long revision) { this.sequence = sequence; this.action = action; this.revision = revision; }
    }
}
