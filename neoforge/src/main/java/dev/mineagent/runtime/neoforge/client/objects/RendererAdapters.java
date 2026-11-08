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
    public static synchronized void register(Adapter adapter){
        Objects.requireNonNull(adapter);if(!adapter.id().matches("[a-z0-9_.-]+:[a-z0-9_./-]+")||!adapter.version().matches("[A-Za-z0-9_.+-]{1,64}"))throw new IllegalArgumentException("RENDERER_ADAPTER_ID");
        if(ADAPTERS.putIfAbsent(adapter.id(),adapter)!=null)throw new IllegalStateException("RENDERER_ADAPTER_EXISTS");
    }
    public static synchronized List<Map<String,String>> catalog(){return ADAPTERS.values().stream().map(a->Map.of("id",a.id(),"version",a.version())).toList();}
    public static void submit(EntityRenderer<?,?> renderer,EntityRenderState state,PoseStack poses,Runnable original){
        var meta=state.getRenderData(EntityVisualClient.KEY);if(meta==null||meta.type().equals("minecraft:player")){original.run();return;}
        List<Adapter> matching;synchronized(RendererAdapters.class){matching=ADAPTERS.values().stream().filter(a->a.supports(renderer,state)).toList();}
        if(matching.size()!=1){original.run();return;}
        var adapter=matching.getFirst();poses.pushPose();
        try(var frame=adapter.capture(renderer,state,poses)){
            frame.apply(EntityVisualClient.tracks(meta));original.run();
            EntityVisualClient.adapterObserved(meta,adapter.id(),adapter.version(),renderer.getClass().getName(),frame::inspect);
        }finally{poses.popPose();}
    }
    private RendererAdapters(){}
}
