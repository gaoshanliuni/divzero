package dev.mineagent.runtime.neoforge.client.nativeui;

import com.google.gson.*;
import dev.mineagent.runtime.core.resources.ResourceLibrary;
import dev.mineagent.runtime.neoforge.network.UiPayloads;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import java.nio.file.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.*;
import java.util.concurrent.*;

/** Downloads only into private generated paths. Saving a file never executes it. */
final class NativeResourceDownloads {
    private static final ExecutorService IO=Executors.newSingleThreadExecutor(Thread.ofVirtual().name("resource-download").factory());
    private record Download(Object connection,Object level,Path directory,Path partial,Path target,long total,long offset){}
    private static final Map<UUID,Download> LIVE=new LinkedHashMap<>();
    private static final Map<UUID,Long> SEEN=new LinkedHashMap<>();
    static void tick(){var mc=Minecraft.getInstance();LIVE.entrySet().removeIf(e->e.getValue().connection()!=mc.getConnection()||e.getValue().level()!=mc.level||System.currentTimeMillis()-SEEN.getOrDefault(e.getKey(),0L)>60000);SEEN.keySet().retainAll(LIVE.keySet());}
    static boolean accept(UiPayloads.Event packet,JsonObject data){
        String kind=data.get("kind").getAsString();if(!kind.startsWith("download_"))return false;var mc=Minecraft.getInstance();Object connection=mc.getConnection(),level=mc.level;
        try{
            SEEN.put(packet.requestId(),System.currentTimeMillis());
            if(!dev.mineagent.runtime.neoforge.client.MineAgentClientTrustPrompt.enabled()||connection==null||level==null)throw new IllegalStateException("DOWNLOAD_WORLD_DISABLED");
            if(kind.equals("download_begin")){
                long total=data.get("total").getAsLong();String name=data.get("name").getAsString();if(total<0||total>ResourceLibrary.MAX_BYTES||LIVE.size()>=2||LIVE.containsKey(packet.requestId())||name.length()>240)throw new IllegalArgumentException("DOWNLOAD_LIMIT");
                name=name.replaceAll("[^\\p{L}\\p{N}._ -]","_").replaceAll("[. ]+$","");if(name.isBlank()||name.matches("(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\\..*)?"))name="resource.bin";final String filename=name;
                // Reserve before I/O; duplicate offers cannot race a second file writer.
                LIVE.put(packet.requestId(),new Download(connection,level,null,null,null,total,0));
                CompletableFuture.supplyAsync(()->{try{Path game=mc.gameDirectory.toPath().toRealPath(),root=game.resolve("mineagent-runtime-data/resource-downloads");Files.createDirectories(root);if(!root.toRealPath().equals(root)||Files.isSymbolicLink(root))throw new IllegalStateException("DOWNLOAD_ROOT_CHANGED");Path directory=Files.createDirectory(root.resolve(UUID.randomUUID().toString()));Path part=Files.createFile(directory.resolve("download.partial"));return new Download(connection,level,directory,part,directory.resolve(filename),total,0);}catch(Exception e){throw new CompletionException(e);}},IO).whenComplete((download,error)->mc.execute(()->{if(!current(packet.requestId(),connection,level))return;if(error!=null){fail(packet.requestId(),"DOWNLOAD_FILE_CREATE_FAILED");return;}LIVE.put(packet.requestId(),download);request(packet.requestId(),Map.of("offset",0));}));return true;
            }
            var transfer=LIVE.get(packet.requestId());if(transfer==null||transfer.partial()==null||!current(packet.requestId(),connection,level))return true;
            long offset=data.get("offset").getAsLong();byte[] bytes=Base64.getDecoder().decode(data.get("data").getAsString());if(offset!=transfer.offset()||data.get("total").getAsLong()!=transfer.total()||bytes.length>48*1024||bytes.length>transfer.total()-offset||bytes.length==0&&offset!=transfer.total())throw new IllegalArgumentException("DOWNLOAD_SEQUENCE");
            // In-flight marker prevents a duplicate chunk from being accepted before the write completes.
            LIVE.put(packet.requestId(),new Download(connection,level,transfer.directory(),null,transfer.target(),transfer.total(),offset));
            CompletableFuture.runAsync(()->{try{try(var channel=FileChannel.open(transfer.partial(),StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){if(channel.size()!=offset)throw new IllegalStateException("DOWNLOAD_FILE_CHANGED");channel.position(offset);var buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining())channel.write(buffer);channel.force(false);}if(offset+bytes.length==transfer.total()){Files.move(transfer.partial(),transfer.target(),StandardCopyOption.ATOMIC_MOVE);Files.writeString(transfer.directory().resolve("download-info.json"),new Gson().toJson(Map.of("bytes",transfer.total(),"savedAt",System.currentTimeMillis(),"executed",false)),StandardOpenOption.CREATE_NEW);}}catch(Exception e){throw new CompletionException(e);}},IO).whenComplete((unused,error)->mc.execute(()->{
                if(!current(packet.requestId(),connection,level))return;if(error!=null){fail(packet.requestId(),"DOWNLOAD_WRITE_FAILED");return;}long next=offset+bytes.length;
                if(next==transfer.total()){LIVE.remove(packet.requestId());request(packet.requestId(),Map.of("offset",next,"saved",true));mc.gui.getChat().addClientSystemMessage(Component.literal(dev.mineagent.runtime.neoforge.client.language.ClientLanguage.t("资源已保存：")+transfer.target()));}
                else{LIVE.put(packet.requestId(),new Download(connection,level,transfer.directory(),transfer.partial(),transfer.target(),transfer.total(),next));request(packet.requestId(),Map.of("offset",next));}
            }));
        }catch(Exception e){fail(packet.requestId(),"DOWNLOAD_REJECTED");}return true;
    }
    private static boolean current(UUID id,Object connection,Object level){var mc=Minecraft.getInstance();return LIVE.containsKey(id)&&connection==mc.getConnection()&&level==mc.level&&dev.mineagent.runtime.neoforge.client.MineAgentClientTrustPrompt.enabled();}
    private static void fail(UUID id,String code){LIVE.remove(id);request(id,Map.of("error",code));}
    private static void request(UUID id,Map<String,Object> data){if(Minecraft.getInstance().getConnection()!=null)net.neoforged.neoforge.client.network.ClientPacketDistributor.sendToServer(new UiPayloads.Command(id,"resourceViewReply",new Gson().toJson(data)));}
    private NativeResourceDownloads(){}
}
