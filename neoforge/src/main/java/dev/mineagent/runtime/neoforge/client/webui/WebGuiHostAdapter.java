package dev.mineagent.runtime.neoforge.client.webui;

import com.google.gson.*;
import dev.mineagent.runtime.api.packages.RuntimePackage;
import dev.mineagent.runtime.api.ui.UiProtocol.Session;
import dev.mineagent.runtime.client.webui.*;
import dev.mineagent.runtime.neoforge.client.nativeui.*;
import net.minecraft.client.Minecraft;
import java.io.IOException;
import java.util.*;

/** Compatibility name for the single native workspace host. It owns no browser or alternative renderer. */
public final class WebGuiHostAdapter implements AutoCloseable {
    public static final WebGuiHostAdapter INSTANCE=new WebGuiHostAdapter();private static final Gson JSON=new Gson();private boolean openedStandalone;private long openedAt;
    public record ViewPackage(UUID packageId,long revision,String entry,boolean passive){}
    private WebGuiHostAdapter(){}
    public String diagnostic(){return ready()?"NATIVE_READY":"VIEW_NOT_RENDERED";}
    public boolean ready(){return NativeWorkspaceConnection.ready();}
    public boolean compositionActive(){return Minecraft.getInstance().screen instanceof NativeComposition composition&&composition.nativeComposing();}
    public boolean workspaceShown(){return NativeWorkspaceScreen.visible();}
    public long readyMillis(){return openedAt==0?0:System.currentTimeMillis()-openedAt;}
    public String packageUrl(String view){return NativePackageViews.owns(view)?"native:"+view:null;}
    public String viewForPackageUrl(String uri){return uri!=null&&uri.startsWith("native:")&&NativePackageViews.owns(uri.substring(7))?uri.substring(7):null;}
    public boolean packageLoaded(String view){return NativePackageViews.owns(view);}
    public boolean packageHidden(String view){return !NativePackageViews.visible(view);}
    public List<String> loadedPreviewViews(){return NativePackageViews.ids().stream().filter(id->!PackageContentClient.owns(id)).sorted().toList();}
    public ViewPackage viewPackage(String view){if(!NativePackageViews.owns(view))return null;var asset=NativePackageViews.asset(view);return new ViewPackage(asset.runtimePackage().packageId(),asset.runtimePackage().revision(),asset.entry(),NativePackageViews.passive(view));}
    public JsonObject viewLayout(String view){return NativePackageViews.owns(view)?NativePackageViews.layout(view):null;}
    public void open(){openedAt=System.currentTimeMillis();NativeWorkspaceScreen.open();}
    public void toggleWorkspace(){NativeWorkspaceScreen.toggle();}
    public void hideWorkspace(){if(Minecraft.getInstance().screen instanceof NativeWorkspaceScreen screen)screen.onClose();}
    public void openPassive(){NativeWorkspaceConnection.open();}
    public void openStandalone(){if(!NativeWorkspaceScreen.visible())openedStandalone=true;open();}
    public void cancelPendingStandalone(){if(openedStandalone&&NativePackageViews.ids().isEmpty())hideWorkspace();openedStandalone=false;}
    public void revealWorldView(String view){openStandalone();NativePackageViews.show(view);openedStandalone=false;}
    public void interactionMode(boolean active){if(active)open();else hideWorkspace();}
    public String openPackagePreview(RuntimePackage pkg,PackageUiResolver.ContentReader reader,String entry,boolean passive)throws IOException{
        if(!ready())throw new IllegalStateException("VIEW_NOT_RENDERED");if(!entry.startsWith("ui/")||!entry.endsWith(".json"))throw new IllegalArgumentException("LEGACY_UI_REWRITE_REQUIRED");
        return openResolvedPreview(new PackagePreviewTransfer.Resolved(pkg,entry,PackageUiResolver.resolve(pkg.resources(),reader,8L*1024*1024)),passive);
    }
    public String openResolvedPreview(PackagePreviewTransfer.Resolved resolved,boolean passive){return openResolved(resolved,null,passive);}
    public String openResolvedContent(PackagePreviewTransfer.Resolved resolved,Session session){return openResolvedContent(resolved,session,false);}
    public String openResolvedContent(PackagePreviewTransfer.Resolved resolved,Session session,boolean passive){
        if(passive&&!dev.mineagent.runtime.api.ui.DeliveryProtocol.bound(session.binding())&&(session.binding().preview()||session.binding().actorKind()!=dev.mineagent.runtime.api.ui.UiProtocol.ActorKind.PLAYER||!session.binding().capabilities().equals(Set.of("scoreview.read"))||dev.mineagent.runtime.core.packages.PackageUiEntrypoints.select(resolved.runtimePackage().entrypoints(),true).filter(resolved.entry()::equals).isEmpty()))throw new SecurityException("HUD_SESSION_CONTEXT");
        return openResolved(resolved,session,passive);
    }
    private String openResolved(PackagePreviewTransfer.Resolved asset,Session session,boolean passive){
        try{String view=NativePackageViews.open(asset,session,passive,PackageContentClient::request);if(session!=null&&!PackageContentClient.owns(view))PackageContentClient.mount(session,"native:"+view);openedStandalone=false;return view;}
        catch(RuntimeException error){throw error;}catch(Exception error){throw new IllegalArgumentException("NATIVE_PACKAGE_BUILD_FAILED: "+error.getMessage(),error);}
    }
    public void emit(String channel,Object data){if(!Minecraft.getInstance().isSameThread())throw new IllegalStateException("CLIENT_THREAD_REQUIRED");var json=JSON.toJsonTree(data);NativeWorkspaceScreen.push(channel,json);NativePackageViews.hostEvent(channel,json);}
    public void tick(){UiClientSessions.tick();}
    @Override public void close(){NativePackagePlacement.flush();HudPersistenceClient.flushWrites();HudPersistenceClient.hostClosed();UiClientSessions.reset(true);PageControlClient.clear();UiPresentationClient.clear();PackagePageAgent.clear();dev.mineagent.runtime.neoforge.client.MineAgentClientTrustPrompt.clearWebNotice();openedStandalone=false;hideWorkspace();}
}
