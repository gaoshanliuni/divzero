package dev.mineagent.runtime.neoforge.client.nativeui;

import com.lowdragmc.lowdraglib2.gui.hud.ModularHudLayer;
import dev.mineagent.runtime.core.ui.dynamic.InterfaceDefinition;
import dev.mineagent.runtime.core.ui.dynamic.InterfaceSession;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import java.util.*;

/** Passive native layers. Registering/rendering a HUD never creates a Screen or captures input. */
public final class LdHudRegistry {
    private record Entry(InterfaceSession<LdInterfaceRenderer.Rendered> session,ModularHudLayer layer,int order){}
    private static final Map<InterfaceSession.Scope,Entry> ENTRIES=new LinkedHashMap<>();
    private static Object connection,level;
    private LdHudRegistry(){}
    public static void register(RegisterGuiLayersEvent event){
        event.registerAboveAll(Identifier.fromNamespaceAndPath("mineagent_runtime","native_interfaces"),(graphics,delta)->{
            maintainContext();
            PvpMapClient.render(graphics);
            NativeAttachedLayers.entityHud(graphics,delta);
            for(var entry:ENTRIES.values().stream().sorted(Comparator.comparingInt(Entry::order).thenComparing(e->e.session.scope().agent().toString()).thenComparing(e->e.session.scope().view())).toList())entry.layer.render(graphics,delta);
        });
    }
    /** Called only after the application's scope/permission checks and successful candidate activation. */
    public static void attach(InterfaceSession<LdInterfaceRenderer.Rendered> session,int order){
        requireClientThread();maintainContext();var mc=Minecraft.getInstance();
        if(mc.player==null||mc.level==null||mc.getConnection()==null||!mc.player.getUUID().equals(session.scope().owner()))throw new IllegalStateException("HUD_OWNER_OR_WORLD_UNAVAILABLE");
        if(session.definition()==null||session.definition().surface()!=InterfaceDefinition.Surface.HUD)throw new IllegalArgumentException("HUD_SURFACE_REQUIRED");
        session.interactive(false);
        var entry=new Entry(session,()->{
            var rendered=session.rendered();
            return session.visible()&&!session.interactive()&&rendered!=null&&!rendered.ui.isRemoved()?rendered.ui:null;
        },order);
        var previous=ENTRIES.put(session.scope(),entry);
        if(previous!=null&&previous.session!=session)previous.session.close();
    }
    public static void detach(InterfaceSession.Scope scope){requireClientThread();var entry=ENTRIES.remove(scope);if(entry!=null)entry.session.close();}
    public static void clear(){requireClientThread();for(var entry:ENTRIES.values())entry.session.close();ENTRIES.clear();}
    public static void maintainContext(){
        requireClientThread();var mc=Minecraft.getInstance();
        if(connection!=mc.getConnection()||level!=mc.level){clear();connection=mc.getConnection();level=mc.level;}
        ENTRIES.values().removeIf(entry->entry.session.rendered()==null);
    }
    private static void requireClientThread(){if(!Minecraft.getInstance().isSameThread())throw new IllegalStateException("HUD_CLIENT_THREAD_REQUIRED");}
}
