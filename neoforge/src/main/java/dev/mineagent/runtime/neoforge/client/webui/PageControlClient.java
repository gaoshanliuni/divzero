package dev.mineagent.runtime.neoforge.client.webui;
import com.google.gson.Gson;
import dev.mineagent.runtime.api.ui.UiInteractionScope;
import dev.mineagent.runtime.api.ui.UiProtocol.*;
import dev.mineagent.runtime.client.webui.UiAgentClientPolicy;
import net.minecraft.client.Minecraft;
import java.util.*;
import java.util.concurrent.*;

/** Explicitly attach an existing verified ordinary preview to a page-only PLAYER source before asking for delegation. */
public final class PageControlClient {
    private static final Gson JSON=new Gson();
    private static final Map<String,Pending> pending=new HashMap<>();
    private static final class Pending {
        final String view,url;final Session shell;final WebGuiHostAdapter.ViewPackage descriptor;
        final CompletableFuture<Receipt> result=new CompletableFuture<>();Session issued;
        Pending(String view,String url,Session shell,WebGuiHostAdapter.ViewPackage descriptor){this.view=view;this.url=url;this.shell=shell;this.descriptor=descriptor;}
    }
    private PageControlClient(){}
    public static CompletableFuture<Receipt> prepare(String view){
        var prior=pending.get(view);if(prior!=null)return prior.result;
        var host=WebGuiHostAdapter.INSTANCE;var descriptor=host.viewPackage(view);var shell=UiClientSessions.current();var raw=PackageContentClient.rawSession(view);
        if(raw!=null){
            if(raw.binding().actorKind()==ActorKind.PLAYER&&UiInteractionScope.pageOnly(raw.binding())&&PackageContentClient.session(view)!=null)return CompletableFuture.completedFuture(readyReceipt(view));
            return CompletableFuture.failedFuture(new IllegalStateException("REOPEN_PAGE_REQUIRED")); // Never turn an old AGENT realm into a new PLAYER source.
        }
        if(shell==null||descriptor==null||descriptor.passive()||host.packageHidden(view)||!host.packageLoaded(view)||pending.size()>=4)return CompletableFuture.failedFuture(new IllegalStateException("VIEW_NOT_RENDERED"));
        var state=new Pending(view,host.packageUrl(view),shell,descriptor);pending.put(view,state);
        state.result.orTimeout(10,TimeUnit.SECONDS).whenComplete((r,e)->Minecraft.getInstance().execute(()->{
            if(pending.remove(view,state)&&e!=null&&state.issued!=null){
                var current=PackageContentClient.rawSession(view);
                if(current!=null&&current.sessionId().equals(state.issued.sessionId()))PackageContentClient.close(view);
                else if(current==null)closeIssued(state.issued);
            }
        }));
        UiClientSessions.command("ui.bindPage",Map.of("viewId",view,"packageId",descriptor.packageId().toString(),"packageRevision",Long.toString(descriptor.revision())),UUID.randomUUID())
                .whenComplete((receipt,error)->Minecraft.getInstance().execute(()->{
                    try{
                        if(error!=null||receipt.code()!=Code.ACCEPTED)throw new IllegalStateException("UI_PAGE_BIND_FAILED");
                        var issued=JSON.fromJson(receipt.values().get("session"),Session.class);state.issued=issued;
                        if(pending.get(view)!=state||!current(state)){closeIssued(issued);throw new IllegalStateException("STALE_VIEW");}
                        UiAgentClientPolicy.requirePageBinding(shell,issued,view,descriptor.packageId(),descriptor.revision(),descriptor.entry());
                        PackageContentClient.mount(issued,state.url);PackageContentClient.loaded(view);rendered(view);
                    }catch(Exception failure){state.result.completeExceptionally(failure);}
                }));
        return state.result;
    }
    public static void rendered(String view){var state=pending.get(view);if(state==null)return;var session=PackageContentClient.session(view);
        if(session!=null&&state.issued!=null&&session.sessionId().equals(state.issued.sessionId())&&current(state))state.result.complete(readyReceipt(view));}
    public static void closed(String view){var state=pending.remove(view);if(state!=null){state.result.completeExceptionally(new IllegalStateException("VIEW_NOT_RENDERED"));if(state.issued!=null)closeIssued(state.issued);}}
    public static void clear(){for(var view:List.copyOf(pending.keySet()))closed(view);}
    private static boolean current(Pending state){var host=WebGuiHostAdapter.INSTANCE;return UiClientSessions.current()==state.shell&&host.ready()&&Objects.equals(host.viewPackage(state.view),state.descriptor)&&Objects.equals(host.packageUrl(state.view),state.url)&&!host.packageHidden(state.view);}
    private static Receipt readyReceipt(String view){return new Receipt(UUID.randomUUID(),Code.ACCEPTED,Map.of("viewId",view,"state","PAGE_ONLY_READY"));}
    private static void closeIssued(Session session){UiClientSessions.contentRequest("close",session,"ui.observe",Map.of(),UUID.randomUUID());}
}
