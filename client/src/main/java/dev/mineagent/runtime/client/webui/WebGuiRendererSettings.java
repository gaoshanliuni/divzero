package dev.mineagent.runtime.client.webui;
import java.nio.file.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.util.Set;
import java.util.function.BooleanSupplier;

/** Local renderer preference. Saving never mutates a running browser or creates world authority. */
public final class WebGuiRendererSettings {
    public record Snapshot(String renderer,String revision,boolean exists){}
    private WebGuiRendererSettings(){}
    private static byte[] bytes(Path path)throws IOException{
        try(var stream=Files.newInputStream(path)){byte[] value=stream.readNBytes(8193);if(value.length>8192)throw new IllegalArgumentException("WEBGUI_RENDERER_CONFIG_BUDGET");return value;}
        catch(NoSuchFileException missing){return null;}
    }
    private static String decode(byte[] bytes){try{return StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();}catch(java.nio.charset.CharacterCodingException invalid){throw new IllegalArgumentException("WEBGUI_RENDERER_CONFIG",invalid);}}
    private static String renderer(byte[] bytes){
        if(bytes==null)return "LEGACY";
        String renderer=null;
        for(String line:decode(bytes).split("\\R")){
            line=line.strip();if(line.isEmpty()||line.startsWith("#"))continue;
            String[] pair=line.split("=",2);if(pair.length!=2||!pair[0].strip().equals("renderer")||renderer!=null)throw new IllegalArgumentException("WEBGUI_RENDERER_CONFIG");
            renderer=pair[1].strip();
        }
        if(renderer==null||!Set.of("LEGACY","LIVE_ATLAS").contains(renderer))throw new IllegalArgumentException("WEBGUI_RENDERER_CONFIG");
        return renderer;
    }
    private static String hash(byte[] bytes){return dev.mineagent.runtime.api.ui.UiCapture.sha256(bytes==null?new byte[0]:bytes);}
    public static Snapshot read(Path path)throws IOException{byte[] bytes=bytes(path);return new Snapshot(renderer(bytes),hash(bytes),bytes!=null);}
    public static boolean liveAtlas(Path path)throws IOException{return read(path).renderer().equals("LIVE_ATLAS");}
    public static Snapshot save(Path path,String expectedRevision,String renderer,BooleanSupplier current)throws IOException{
        if(renderer==null||!Set.of("LEGACY","LIVE_ATLAS").contains(renderer)||expectedRevision==null||!expectedRevision.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("WEBGUI_RENDERER_INPUT");
        if(!current.getAsBoolean())throw new SecurityException("WEBGUI_RENDERER_CANCELLED");
        Path target=path.toAbsolutePath().normalize();if(Files.isSymbolicLink(target))throw new IllegalArgumentException("WEBGUI_RENDERER_PATH");Files.createDirectories(target.getParent());
        try(var channel=FileChannel.open(target.resolveSibling(target.getFileName()+".lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);var lock=channel.tryLock()){
            if(lock==null)throw new IllegalStateException("WEBGUI_RENDERER_BUSY");
            byte[] old=bytes(target);if(!hash(old).equals(expectedRevision))throw new IllegalStateException("WEBGUI_RENDERER_CONFLICT");renderer(old);
            String text=old==null?"renderer="+renderer+"\n":decode(old).replaceFirst("(?m)^[ \t]*renderer[ \t]*=[^\r\n]*","renderer="+renderer);
            if(text.getBytes(StandardCharsets.UTF_8).length>8192)throw new IllegalArgumentException("WEBGUI_RENDERER_CONFIG_BUDGET");
            Path temporary=Files.createTempFile(target.getParent(),"renderer-",".tmp");
            try{
                Files.writeString(temporary,text,StandardCharsets.UTF_8);
                if(!current.getAsBoolean())throw new SecurityException("WEBGUI_RENDERER_CANCELLED");
                if(!hash(bytes(target)).equals(expectedRevision))throw new IllegalStateException("WEBGUI_RENDERER_CONFLICT");
                Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
                return read(target);
            }finally{Files.deleteIfExists(temporary);}
        }catch(OverlappingFileLockException busy){throw new IllegalStateException("WEBGUI_RENDERER_BUSY",busy);}
    }
}
