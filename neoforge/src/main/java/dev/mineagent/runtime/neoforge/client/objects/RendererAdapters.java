package dev.mineagent.runtime.neoforge.client.objects;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.mineagent.runtime.core.interaction.EntityAnimationSpec;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import java.util.*;

/** Client integrations register explicit render controllers; no reflective private-renderer traversal. */
public final class RendererAdapters {
    public record Track(UUID rule,EntityAnimationSpec specification,double time){}
    public interface Frame extends AutoCloseable {
        void apply(List<Track> tracks);
        /** Actual controller state after apply; parts must be serializable public data. */
        Map<String,Object> inspect(int offset,int limit);
        @Override void close();
    }
    public interface Adapter {
        String id();String version();
        boolean supports(EntityRenderer<?,?> renderer,EntityRenderState state);
        /** Capture prior shared state without mutation. close() must restore it even after a failed apply. */
        Frame capture(EntityRenderer<?,?> renderer,EntityRenderState state,PoseStack poses);
    }
    private static final Map<String,Adapter> ADAPTERS=new LinkedHashMap<>();
    private static final Map<String,String> FAILURES=new LinkedHashMap<>();
    public static synchronized AutoCloseable register(Adapter adapter){
        Objects.requireNonNull(adapter);if(!adapter.id().matches("[a-z0-9_.-]+:[a-z0-9_./-]+")||!adapter.version().matches("[A-Za-z0-9_.+-]{1,64}"))throw new IllegalArgumentException("RENDERER_ADAPTER_ID");
        if(ADAPTERS.putIfAbsent(adapter.id(),adapter)!=null)throw new IllegalStateException("RENDERER_ADAPTER_EXISTS");
        return ()->{synchronized(RendererAdapters.class){ADAPTERS.remove(adapter.id(),adapter);FAILURES.remove(adapter.id());}};
    }
    public static synchronized List<Map<String,String>> catalog(){return ADAPTERS.values().stream().map(a->Map.of("id",a.id(),"version",a.version(),"error",FAILURES.getOrDefault(a.id(),""))).toList();}
    private static void failed(Adapter adapter,Throwable failure){synchronized(RendererAdapters.class){FAILURES.put(adapter.id(),"RENDERER_ADAPTER_"+failure.getClass().getSimpleName().toUpperCase(Locale.ROOT));}}
    public static void submit(EntityRenderer<?,?> renderer,EntityRenderState state,PoseStack poses,Runnable original){
        var meta=state.getRenderData(EntityVisualClient.KEY);if(meta==null||meta.type().equals("minecraft:player")){original.run();return;}
        var matching=new ArrayList<Adapter>();synchronized(RendererAdapters.class){for(var adapter:ADAPTERS.values())if(!FAILURES.containsKey(adapter.id()))try{if(adapter.supports(renderer,state))matching.add(adapter);}catch(RuntimeException|LinkageError failure){failed(adapter,failure);}}
        if(matching.size()!=1){
            if(matching.size()>1)EntityVisualClient.adapterObserved(meta,"","",renderer.getClass().getName(),(offset,limit)->Map.of("error","RENDERER_ADAPTER_AMBIGUOUS"));
            original.run();return;
        }
        var adapter=matching.getFirst();poses.pushPose();Frame frame=null;boolean submitted=false,restored=true;
        try{
            try{frame=adapter.capture(renderer,state,poses);frame.apply(EntityVisualClient.tracks(meta));}
            catch(RuntimeException|LinkageError failure){failed(adapter,failure);}
            synchronized(RendererAdapters.class){if(!FAILURES.containsKey(adapter.id()))submitted=true;}
            if(submitted){
                original.run(); // A native renderer error is not retried as another draw.
                try{EntityVisualClient.adapterObserved(meta,adapter.id(),adapter.version(),renderer.getClass().getName(),frame::inspect);}
                catch(RuntimeException|LinkageError failure){failed(adapter,failure);}
            }
        }finally{
            try{if(frame!=null)frame.close();}catch(RuntimeException|LinkageError failure){restored=false;failed(adapter,failure);}finally{poses.popPose();}
        }
        if(!submitted&&restored)original.run();
        synchronized(RendererAdapters.class){if(FAILURES.containsKey(adapter.id()))EntityVisualClient.adapterObserved(meta,adapter.id(),adapter.version(),renderer.getClass().getName(),(offset,limit)->Map.of("error",FAILURES.get(adapter.id())));}
    }
    private RendererAdapters(){}
}
