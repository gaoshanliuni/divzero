package dev.mineagent.runtime.neoforge.client.webui;
import com.google.gson.*;
import dev.mineagent.runtime.client.webui.WebGuiRendererSettings;
import net.minecraft.client.Minecraft;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Trusted-root, local-client configuration only. Never sends a server command or changes the active renderer. */
public final class RendererSettingsClient {
    private static final ExecutorService IO=new ThreadPoolExecutor(1,1,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(8),r->{var t=new Thread(r,"mineagent-renderer-settings");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    private static final Set<AtomicBoolean> pending=new HashSet<>();
    private RendererSettingsClient(){}
    public static CompletableFuture<Map<String,Object>> handle(JsonObject request){
        var mc=Minecraft.getInstance();if(!mc.isSameThread())throw new IllegalStateException("CLIENT_THREAD_REQUIRED");
        String action=string(request,"action");var fields=action.equals("read")?Set.of("channel","action"):Set.of("channel","action","renderer","expectedRevision");
        if(!Set.of("read","save").contains(action)||!fields.equals(request.keySet())||pending.size()>=8)throw new IllegalArgumentException("WEBGUI_RENDERER_INPUT");
        String renderer=action.equals("save")?string(request,"renderer"):null,revision=action.equals("save")?string(request,"expectedRevision"):null;
        var valid=new AtomicBoolean(true);pending.add(valid);var result=new CompletableFuture<Map<String,Object>>();
        var path=mc.gameDirectory.toPath().resolve("config/mineagent-webgui.properties");
        try{CompletableFuture.supplyAsync(()->{try{if(!valid.get())throw new SecurityException("WEBGUI_RENDERER_CANCELLED");return action.equals("save")?WebGuiRendererSettings.save(path,revision,renderer,valid::get):WebGuiRendererSettings.read(path);}catch(Exception e){throw new CompletionException(e);}},IO)
            .whenComplete((saved,error)->mc.execute(()->{pending.remove(valid);if(!valid.get()){result.complete(Map.of("status","WEBGUI_RENDERER_CANCELLED"));return;}
                if(error!=null){Throwable failure=error;while(failure.getCause()!=null)failure=failure.getCause();String code=failure.getMessage();result.complete(Map.of("status",code!=null&&code.matches("WEBGUI_RENDERER_[A-Z_]{1,50}")?code:"WEBGUI_RENDERER_FAILED"));return;}
                String running=WebGuiAtlasCompositor.active()||WebGuiAtlasCompositor.automatic()?"LIVE_ATLAS":"LEGACY";
                result.complete(Map.of("status",action.equals("save")?"RENDERER_SAVED":"RENDERER_SETTINGS","configured",saved.renderer(),"running",running,"revision",saved.revision(),"requiresReopen",!saved.renderer().equals(running)));
            }));
        }catch(RejectedExecutionException busy){pending.remove(valid);result.complete(Map.of("status","WEBGUI_RENDERER_BUSY"));}
        return result;
    }
    private static String string(JsonObject n,String key){var value=n.get(key);if(value==null||!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isString()||value.getAsString().length()>128)throw new IllegalArgumentException("WEBGUI_RENDERER_INPUT");return value.getAsString();}
    public static void clear(){pending.forEach(v->v.set(false));pending.clear();}
}
