package dev.mineagent.runtime.legacy189.client;

import dev.mineagent.runtime.legacy189.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.InputEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.input.Keyboard;

public class ClientProxy extends CommonProxy {
    private static final KeyBinding SWAP = new KeyBinding("key.divzero.swap", Keyboard.KEY_F, "DivZero");
    private static final KeyBinding EQUIPMENT = new KeyBinding("key.divzero.offhand", Keyboard.KEY_V, "DivZero");
    private static NativeNetwork.State state;
    private static boolean pendingPlayer;
    @Override public void initialize() {
        ClientRegistry.registerKeyBinding(SWAP); ClientRegistry.registerKeyBinding(EQUIPMENT);
        FMLCommonHandler.instance().bus().register(this); MinecraftForge.EVENT_BUS.register(this);
    }
    @Override public void registerModels() {
        for (java.util.Map.Entry<String, net.minecraft.block.Block> entry : LegacyBlocks.REGISTERED.entrySet())
            Minecraft.getMinecraft().getRenderItem().getItemModelMesher().register(net.minecraft.item.Item.getItemFromBlock(entry.getValue()), 0,
                    new net.minecraft.client.resources.model.ModelResourceLocation(LegacyMod.ID + ":" + entry.getKey(), "inventory"));
    }
    @Override public Object equipmentScreen(int id, net.minecraft.entity.player.EntityPlayer player) {
        return id == 0 ? new EquipmentScreen(player) : null;
    }
    @Override public void receive(final NativeNetwork.State message) {
        Minecraft.getMinecraft().addScheduledTask(new Runnable() {
            @Override public void run() {
                Minecraft mc = Minecraft.getMinecraft();
                if (mc.thePlayer != null && (!mc.thePlayer.getUniqueID().equals(message.owner) || mc.thePlayer.dimension != message.dimension)) return;
                state = message; pendingPlayer = mc.thePlayer == null; apply();
            }
        });
    }
    private static void apply() {
        Minecraft mc = Minecraft.getMinecraft();
        if (state != null && mc.thePlayer != null && mc.thePlayer.getUniqueID().equals(state.owner) && mc.thePlayer.dimension == state.dimension) {
            ModernCombat.clientEnabled = state.modern;
            NativeOffhand.get(mc.thePlayer).sync(state.offhand == null ? null : state.offhand.copy(), state.revision);
        }
    }
    @SubscribeEvent public void key(InputEvent.KeyInputEvent event) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.currentScreen != null || mc.thePlayer == null || state == null) return;
        if (SWAP.isPressed()) NativeNetwork.CHANNEL.sendToServer(new NativeNetwork.Action(state.world, state.session, 0, state.revision));
        if (EQUIPMENT.isPressed()) NativeNetwork.CHANNEL.sendToServer(new NativeNetwork.Action(state.world, state.session, 1, state.revision));
    }
    @SubscribeEvent public void unload(net.minecraftforge.event.world.WorldEvent.Unload event) {
        if (event.world.isRemote) { state = null; pendingPlayer = false; ModernCombat.clientEnabled = false; }
    }
    @SubscribeEvent public void tick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            if (pendingPlayer && Minecraft.getMinecraft().thePlayer != null) { apply(); pendingPlayer = false; }
            NativeClientFixture.tick();
        }
    }
}
