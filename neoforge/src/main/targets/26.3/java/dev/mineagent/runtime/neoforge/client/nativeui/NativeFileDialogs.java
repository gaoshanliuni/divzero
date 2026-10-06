package dev.mineagent.runtime.neoforge.client.nativeui;
import net.minecraft.client.Minecraft;
import org.lwjgl.sdl.*;
import org.lwjgl.system.MemoryUtil;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** SDL's asynchronous native selector. Only the existing transfer worker waits, never the render thread. */
public final class NativeFileDialogs {
    private static final AtomicLong SERIAL=new AtomicLong();
    private static final ConcurrentHashMap<Long,CompletableFuture<String>> PENDING=new ConcurrentHashMap<>();
    // One process-lifetime native callback, shared by all dialogs; no per-request native callback leak.
    private static final SDL_DialogFileCallback CALLBACK=SDL_DialogFileCallback.create((id,files,filter)->{
        var result=PENDING.remove(id);if(result==null)return;
        try{
            if(files==0){result.completeExceptionally(new IllegalStateException("NATIVE_FILE_DIALOG_FAILED"));return;}
            var paths=new java.util.ArrayList<String>();for(int i=0;i<20000;i++){long value=MemoryUtil.memGetAddress(files+(long)i*org.lwjgl.system.Pointer.POINTER_SIZE);if(value==0){result.complete(paths.isEmpty()?null:String.join("|",paths));return;}paths.add(MemoryUtil.memUTF8(value));}
            result.completeExceptionally(new IllegalArgumentException("FILE_SELECTION_TOO_LARGE"));
        }catch(Throwable error){result.completeExceptionally(error);}
    });
    public static String folder(String title,CharSequence path){return choose(SDLDialog.SDL_FILEDIALOG_OPENFOLDER,title,path,false);}
    public static String open(String title,CharSequence path,Object filters,CharSequence description,boolean many){if(filters!=null)throw new IllegalArgumentException("FILE_FILTERS_REQUIRED");return choose(SDLDialog.SDL_FILEDIALOG_OPENFILE,title,path,many);}
    public static String save(String title,CharSequence path,Object filters,CharSequence description){if(filters!=null)throw new IllegalArgumentException("FILE_FILTERS_REQUIRED");return choose(SDLDialog.SDL_FILEDIALOG_SAVEFILE,title,path,false);}
    private static String choose(int type,String title,CharSequence path,boolean many){
        var mc=Minecraft.getInstance();if(mc.isSameThread())throw new IllegalStateException("FILE_DIALOG_RENDER_THREAD_WAIT");
        long id=SERIAL.incrementAndGet();var result=new CompletableFuture<String>();PENDING.put(id,result);
        mc.execute(()->{
            int props=SDLProperties.SDL_CreateProperties();
            try{
                if(props==0||!SDLProperties.SDL_SetStringProperty(props,SDLDialog.SDL_PROP_FILE_DIALOG_TITLE_STRING,title)
                        ||!SDLProperties.SDL_SetPointerProperty(props,SDLDialog.SDL_PROP_FILE_DIALOG_WINDOW_POINTER,mc.getWindow().handle())
                        ||!SDLProperties.SDL_SetBooleanProperty(props,SDLDialog.SDL_PROP_FILE_DIALOG_MANY_BOOLEAN,many))throw new IllegalStateException("FILE_DIALOG_PROPERTIES");
                if(path!=null&&!SDLProperties.SDL_SetStringProperty(props,SDLDialog.SDL_PROP_FILE_DIALOG_LOCATION_STRING,path))throw new IllegalStateException("FILE_DIALOG_LOCATION");
                SDLDialog.SDL_ShowFileDialogWithProperties(type,CALLBACK,id,props);
            }catch(Throwable error){PENDING.remove(id);result.completeExceptionally(error);}finally{if(props!=0)SDLProperties.SDL_DestroyProperties(props);}
        });
        return result.join();
    }
    private NativeFileDialogs(){}
}
