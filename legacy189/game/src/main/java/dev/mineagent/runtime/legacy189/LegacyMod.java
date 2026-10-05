package dev.mineagent.runtime.legacy189;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.SidedProxy;
import net.minecraftforge.fml.common.event.*;
import org.apache.logging.log4j.Logger;

@Mod(modid = LegacyMod.ID, name = "DivZero", version = LegacyMod.VERSION, acceptedMinecraftVersions = "[1.8.9]",
     acceptableRemoteVersions = LegacyMod.VERSION)
public final class LegacyMod {
    public static final String ID = "mineagent_runtime";
    public static final String VERSION = "1.0.32-legacy189.5";
    @Mod.Instance(ID) public static LegacyMod instance;
    @SidedProxy(clientSide = "dev.mineagent.runtime.legacy189.client.ClientProxy", serverSide = "dev.mineagent.runtime.legacy189.CommonProxy")
    public static CommonProxy proxy;
    public static Logger logger;

    @Mod.EventHandler public void preInit(FMLPreInitializationEvent event) {
        logger = event.getModLog();
        LegacyBlocks.register();
        NativeNetwork.initialize();
        DuelNetwork.register();
        NativeOffhand.Events equipment = new NativeOffhand.Events();
        MinecraftForge.EVENT_BUS.register(equipment);
        FMLCommonHandler.instance().bus().register(equipment);
        NativeRuntime.Events runtime = new NativeRuntime.Events();
        MinecraftForge.EVENT_BUS.register(runtime);
        MinecraftForge.EVENT_BUS.register(new ModernCombat.Events());
        MinecraftForge.EVENT_BUS.register(new NativeDuel.Events());
        FMLCommonHandler.instance().bus().register(runtime);
        proxy.initialize();
        logger.info("DIVZERO_LEGACY_BOOTSTRAP protocol={} target=1.8.9-11.15.1.2318", NativeNetwork.PROTOCOL);
    }
    @Mod.EventHandler public void init(FMLInitializationEvent event) { proxy.registerModels(); }
    @Mod.EventHandler public void starting(FMLServerStartingEvent event) {
        event.registerServerCommand(new NativeCommands());
    }
    @Mod.EventHandler public void stopped(FMLServerStoppedEvent event) {
        NativeRuntime.stop();
    }
    @Mod.EventHandler public void stopping(FMLServerStoppingEvent event) { NativeDuel.stop(); }
}
