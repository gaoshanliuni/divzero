package dev.mineagent.runtime.neoforge.client.nativeui;
import java.nio.file.*;import java.util.*;import net.minecraft.client.Minecraft;
/** Local opt-in cannot be enabled by a server payload. */
final class ResourceCapturePreference {
 private static final Properties VALUES=new Properties();private static Path loaded;
 private static Path file(){return Minecraft.getInstance().gameDirectory.toPath().resolve("config/divzero-resource-capture.properties");}
 private static String scope(){var mc=Minecraft.getInstance();if(mc.getCurrentServer()!=null)return "server:"+mc.getCurrentServer().ip;var server=mc.getSingleplayerServer();return server==null?"disconnected":"world:"+server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).toAbsolutePath().normalize();}
 private static void load(){Path path=file();if(path.equals(loaded))return;VALUES.clear();loaded=path;try{if(Files.isRegularFile(path))try(var in=Files.newInputStream(path)){VALUES.load(in);}}catch(Exception ignored){VALUES.clear();}}
 static synchronized boolean allowed(){load();return Boolean.parseBoolean(VALUES.getProperty(scope(),"false"));}
 static synchronized void set(boolean allowed)throws java.io.IOException{load();VALUES.setProperty(scope(),Boolean.toString(allowed));Files.createDirectories(file().getParent());try(var out=Files.newOutputStream(file())){VALUES.store(out,"Optional Minecraft-only screenshots; no desktop capture");}}
 private ResourceCapturePreference(){}
}
