package dev.mineagent.runtime.neoforge.client.webui;

import dev.mineagent.runtime.api.ui.UiProtocol.*;
import dev.mineagent.runtime.client.webui.PackagePreviewTransfer;
import dev.mineagent.runtime.client.trust.ServerTrustStore;
import net.minecraft.client.Minecraft;
import java.util.*;
import java.util.concurrent.*;

/** Actual network download. One bounded, connection-scoped assembly; no server paths or implicit trust confirmation. */
public final class PackagePreviewClient {
    private static final ExecutorService IO = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(1), r -> {
        Thread t = new Thread(r, "mineagent-preview-verify"); t.setDaemon(true); return t;
    }, new ThreadPoolExecutor.AbortPolicy());
    private static Download active;
    private PackagePreviewClient() {}
    private static final class Download {
        final Session session;
        final UUID packageId;
        final long revision;
        final CompletableFuture<Receipt> future = new CompletableFuture<>();
        final long deadline = System.currentTimeMillis() + 120_000;
        PackagePreviewTransfer.Assembler assembler;
        String transferId;
        String transferHash;
        int chunks;
        Session contentSession;
        dev.mineagent.runtime.api.ui.WorldUiProtocol.Launch worldLaunch;
        dev.mineagent.runtime.api.ui.DeliveryProtocol.Launch deliveryLaunch;
        boolean passive;
        dev.mineagent.runtime.client.webui.HudRestoreEntry savedHud;
        java.util.function.BooleanSupplier restoreCurrent=()->true;
        Download(Session session, UUID packageId, long revision) { this.session = session; this.packageId = packageId; this.revision = revision; }
    }
    public static CompletableFuture<Receipt> open(UUID packageId, long revision) {
        return open(packageId,revision,null);
    }
    public static CompletableFuture<Receipt> open(UUID packageId,long revision,UUID targetViewId) {
        return open(packageId,revision,targetViewId,false);
    }
    public static CompletableFuture<Receipt> openForRestore(UUID packageId,long revision,UUID targetViewId){return open(packageId,revision,targetViewId,true);}
    private static CompletableFuture<Receipt> open(UUID packageId,long revision,UUID targetViewId,boolean restore) {return open(packageId,revision,targetViewId,restore,null);}
    public static CompletableFuture<Receipt> openCandidate(UUID packageId,long revision,UUID targetViewId,UUID operation){return open(packageId,revision,targetViewId,false,operation);}
    public static CompletableFuture<Receipt> openHud(UUID packageId,long revision,UUID targetViewId) {
        return open(packageId,revision,Objects.requireNonNull(targetViewId),false,null,true);
    }
    public static boolean idle(){return active==null;}
    public static CompletableFuture<Receipt> openWorld(dev.mineagent.runtime.api.ui.WorldUiProtocol.Launch launch,java.util.function.BooleanSupplier current){
        if(active!=null)return CompletableFuture.failedFuture(new IllegalStateException("UI_TRANSFER_BUSY"));var session=UiClientSessions.current();if(session==null)return CompletableFuture.failedFuture(new IllegalStateException("VIEW_NOT_RENDERED"));
        var d=new Download(session,launch.packageId(),launch.packageRevision());d.worldLaunch=launch;d.restoreCurrent=current;active=d;
        UiClientSessions.command("worldui.open",Map.of("launchId",launch.id().toString()),UUID.randomUUID()).whenComplete((receipt,error)->{
            if(!current(d))return;try{
                if(error!=null||receipt.code()!=Code.ACCEPTED)throw new IllegalStateException("WORLD_UI_OPEN_FAILED");var values=receipt.values();
                if(!launch.packageId().toString().equals(values.get("packageId"))||!Long.toString(launch.packageRevision()).equals(values.get("packageRevision")))throw new SecurityException("WORLD_UI_PACKAGE_CONTEXT");
                d.contentSession=new com.google.gson.Gson().fromJson(values.get("contentSession"),Session.class);dev.mineagent.runtime.api.ui.WorldUiProtocol.require(d.contentSession,session,launch,launch.canonicalSha256());
                d.transferId=UUID.fromString(values.get("transferId")).toString();d.transferHash=values.get("sha256");d.assembler=new PackagePreviewTransfer.Assembler(Integer.parseInt(values.get("size")),d.transferHash);next(d);
            }catch(Exception failed){fail(d,failed);}
        });return d.future;
    }
    public static CompletableFuture<Receipt> openDelivery(UUID delivery,UUID packageId,long revision){
        if(active!=null)return CompletableFuture.failedFuture(new IllegalStateException("UI_TRANSFER_BUSY"));var session=UiClientSessions.current();if(session==null)return CompletableFuture.failedFuture(new IllegalStateException("VIEW_NOT_RENDERED"));var d=new Download(session,packageId,revision);active=d;
        UiClientSessions.command("delivery.accept",Map.of("deliveryId",delivery.toString()),UUID.randomUUID()).whenComplete((receipt,error)->{
            if(!current(d))return;try{if(error!=null||receipt.code()!=Code.ACCEPTED)throw new IllegalStateException("DELIVERY_ACCEPT_FAILED");var values=receipt.values();d.deliveryLaunch=new com.google.gson.Gson().fromJson(values.get("launch"),dev.mineagent.runtime.api.ui.DeliveryProtocol.Launch.class);d.contentSession=new com.google.gson.Gson().fromJson(values.get("contentSession"),Session.class);if(!d.deliveryLaunch.deliveryId().equals(delivery)||!d.deliveryLaunch.packageId().equals(packageId)||d.deliveryLaunch.packageRevision()!=revision)throw new SecurityException("DELIVERY_ASSET_CONTEXT");DeliverySmokeClient.openObserved(session,d.deliveryLaunch,d.contentSession);dev.mineagent.runtime.api.ui.DeliveryProtocol.require(d.contentSession,session,d.deliveryLaunch,d.deliveryLaunch.canonicalSha256());d.passive=d.deliveryLaunch.mode().equals("HUD");d.transferId=UUID.fromString(values.get("transferId")).toString();d.transferHash=values.get("sha256");d.assembler=new PackagePreviewTransfer.Assembler(Integer.parseInt(values.get("size")),d.transferHash);next(d);}catch(Exception failed){fail(d,failed);}
        });return d.future;
    }
    public static CompletableFuture<Receipt> openSavedHud(dev.mineagent.runtime.client.webui.HudRestoreEntry saved,java.util.function.BooleanSupplier current){
        return open(saved.packageId(),saved.packageRevision(),saved.targetId(),false,null,true,false,null,null,null,saved,current);
    }
    public static CompletableFuture<Receipt> openContainer(UUID packageId,long revision){return open(packageId,revision,null,false,null,false,true);}
    public static CompletableFuture<Receipt> openAgentContainer(UUID packageId,long revision,UUID agent,String goal,String expected){return open(packageId,revision,null,false,null,false,true,agent,goal,expected);}
    private static CompletableFuture<Receipt> open(UUID packageId,long revision,UUID targetViewId,boolean restore,UUID patchOperation) {
        return open(packageId,revision,targetViewId,restore,patchOperation,false);
    }
    private static CompletableFuture<Receipt> open(UUID packageId,long revision,UUID targetViewId,boolean restore,UUID patchOperation,boolean passive) {
        return open(packageId,revision,targetViewId,restore,patchOperation,passive,false);
    }
    private static CompletableFuture<Receipt> open(UUID packageId,long revision,UUID targetViewId,boolean restore,UUID patchOperation,boolean passive,boolean container) {
        return open(packageId,revision,targetViewId,restore,patchOperation,passive,container,null,null,null);
    }
    private static CompletableFuture<Receipt> open(UUID packageId,long revision,UUID targetViewId,boolean restore,UUID patchOperation,boolean passive,boolean container,UUID agent,String goal,String expected) {
        return open(packageId,revision,targetViewId,restore,patchOperation,passive,container,agent,goal,expected,null,()->true);
    }
    private static CompletableFuture<Receipt> open(UUID packageId,long revision,UUID targetViewId,boolean restore,UUID patchOperation,boolean passive,boolean container,UUID agent,String goal,String expected,dev.mineagent.runtime.client.webui.HudRestoreEntry saved,java.util.function.BooleanSupplier restoreCurrent) {
        if (active != null) return CompletableFuture.failedFuture(new IllegalStateException("UI_TRANSFER_BUSY"));
        Session session = UiClientSessions.current();
        if (session == null) return CompletableFuture.failedFuture(new IllegalStateException("VIEW_NOT_RENDERED"));
        Download d = new Download(session, packageId, revision); d.passive=passive; active = d;
        d.savedHud=saved;d.restoreCurrent=restoreCurrent;
        var arguments=new LinkedHashMap<String,String>();arguments.put("packageId",packageId.toString());arguments.put("packageRevision",Long.toString(revision));
        if(targetViewId!=null)arguments.put("targetViewId",targetViewId.toString());
        if(patchOperation!=null)arguments.put("patchOperationId",patchOperation.toString());
        if(container)arguments.put("confirmed","true");
        if(agent!=null){arguments.put("agentId",agent.toString());arguments.put("goal",goal);arguments.put("expected",expected);}
        UiClientSessions.command(agent!=null?"container.agent":container?"container.open":passive?"package.hud":patchOperation!=null?"package.patchPreview":targetViewId==null?"package.preview":restore?"package.restore":"package.open", arguments, UUID.randomUUID())
                .whenComplete((receipt, error) -> {
                    if (!current(d)) return;
                    try {
                        if (error != null || receipt.code() != Code.ACCEPTED) throw new IllegalStateException("UI_PREVIEW_UNAVAILABLE");
                        var values = receipt.values();
                        if (!packageId.toString().equals(values.get("packageId")) || !Long.toString(revision).equals(values.get("packageRevision")))
                            throw new IllegalArgumentException("STALE_PACKAGE");
                        d.transferId = UUID.fromString(values.get("transferId")).toString();
                        d.transferHash = values.get("sha256");
                        if(container){
                            d.contentSession=new com.google.gson.Gson().fromJson(values.get("contentSession"),Session.class);
                            if(agent==null)dev.mineagent.runtime.client.webui.ContentPreviewPolicy.requireContainer(d.contentSession,session,packageId,revision);
                            else dev.mineagent.runtime.client.webui.ContentPreviewPolicy.requireAgentContainer(d.contentSession,session,packageId,revision,agent);
                        }else if(targetViewId!=null){
                            d.contentSession=new com.google.gson.Gson().fromJson(values.get("contentSession"),Session.class);
                            dev.mineagent.runtime.client.webui.ContentPreviewPolicy.require(d.contentSession,session,packageId,revision,targetViewId,patchOperation!=null,restore||patchOperation!=null||passive);
                        }
                        d.assembler = new PackagePreviewTransfer.Assembler(Integer.parseInt(values.get("size")), values.get("sha256"));
                        next(d);
                    } catch (Exception failure) { fail(d, failure); }
                });
        return d.future;
    }
    private static void next(Download d) {
        if (!current(d)) return;
        if (d.assembler.offset() == d.assembler.size()) { verify(d); return; }
        int offset = d.assembler.offset();
        UiClientSessions.command(d.deliveryLaunch!=null?"delivery.chunk":d.worldLaunch==null?"package.chunk":"worldui.chunk", Map.of("transferId", d.transferId, "offset", Integer.toString(offset)), UUID.randomUUID())
                .whenComplete((receipt, error) -> {
                    if (!current(d)) return;
                    try {
                        if (error != null || receipt.code() != Code.OBSERVED) throw new IllegalStateException("UI_TRANSFER_FAILED");
                        if (!Integer.toString(offset).equals(receipt.values().get("offset"))) throw new IllegalArgumentException("UI_CHUNK_RANGE");
                        d.assembler.append(offset, Base64.getDecoder().decode(receipt.values().get("bytes")));
                        d.chunks++;
                        next(d);
                    } catch (Exception failure) { fail(d, failure); }
                });
    }
    private static void verify(Download d) {
        var mc = Minecraft.getInstance();
        String serverId = mc.getCurrentServer() == null ? "local-integrated" : mc.getCurrentServer().ip;
        var trustPath = mc.gameDirectory.toPath().resolve("config/mineagent-trusted-servers.properties");
        try {
            CompletableFuture.supplyAsync(() -> {
                try {
                    var trust = new ServerTrustStore(trustPath);
                    return PackagePreviewTransfer.decode(d.assembler.finish(), d.packageId, d.revision, (v, s) -> trust.verify(serverId, v, s));
                } catch (Exception error) { throw new CompletionException(error); }
            }, IO).whenComplete((resolved, error) -> mc.execute(() -> {
                if (!current(d)) return;
                if (error != null) { fail(d, new IllegalStateException("UI_PACKAGE_UNTRUSTED_OR_INVALID")); return; }
                try {
                    if(d.savedHud!=null)d.savedHud.require(d.contentSession,d.session.binding().worldId(),d.session.binding().viewerPlayerId(),resolved.runtimePackage().canonicalSha256(),
                            dev.mineagent.runtime.core.packages.PackageUiEntrypoints.select(resolved.runtimePackage().entrypoints(),true).orElseThrow());
                    if(d.deliveryLaunch!=null){dev.mineagent.runtime.api.ui.DeliveryProtocol.require(d.contentSession,d.session,d.deliveryLaunch,resolved.runtimePackage().canonicalSha256());ContentDeliveryClient.mounted(d.deliveryLaunch,d.contentSession);}
                    if(d.worldLaunch!=null)dev.mineagent.runtime.api.ui.WorldUiProtocol.require(d.contentSession,d.session,d.worldLaunch,resolved.runtimePackage().canonicalSha256());
                    String view = d.contentSession==null?WebGuiHostAdapter.INSTANCE.openResolvedPreview(resolved, false)
                            :WebGuiHostAdapter.INSTANCE.openResolvedContent(resolved,d.contentSession,d.passive);
                    if(d.passive&&d.deliveryLaunch==null){
                        HudPersistenceClient.mounted(d.contentSession,resolved);
                        if(d.savedHud!=null)WebGuiHostAdapter.INSTANCE.emit("hudRestoreLayout",Map.of("viewId",view,"layout",d.savedHud.layout()));
                    }
                    active = null; release(d);
                    d.future.complete(new Receipt(UUID.randomUUID(), Code.ACCEPTED, Map.of("viewId", view, "packageId", d.packageId.toString(),
                            "state", "PAGE_LOADING", "executionMode", d.contentSession==null?"PREVIEW_ONLY":"SERVER_BOUND", "businessVerified", "false",
                            "downloadBytes", Integer.toString(d.assembler.size()), "downloadChunks", Integer.toString(d.chunks),
                            "transferSha256", d.transferHash, "canonicalSha256", resolved.runtimePackage().canonicalSha256(),
                            "verification", "TRUSTED_SERVER_SIGNATURE_AND_RESOURCES")));
                } catch (Exception failure) { fail(d, failure); }
            }));
        } catch (RuntimeException failure) { fail(d, failure); }
    }
    private static boolean current(Download d) {
        if (active != d) return false;
        if (UiClientSessions.current() != d.session || !WebGuiHostAdapter.INSTANCE.ready() || System.currentTimeMillis() >= d.deadline || !d.restoreCurrent.getAsBoolean()) {
            fail(d, new IllegalStateException("UI_TRANSFER_EXPIRED")); return false;
        }
        return true;
    }
    private static void fail(Download d, Throwable error) {
        if (active != d) return;
        active = null; closeUnopened(d);release(d); d.future.completeExceptionally(error);if(d.deliveryLaunch!=null)DeliverySmokeClient.openFailed(error);
    }
    private static void release(Download d) {
        if (UiClientSessions.current() != null) UiClientSessions.command(d.deliveryLaunch!=null?"delivery.release":d.worldLaunch==null?"package.release":"worldui.release", Map.of(), UUID.randomUUID());
    }
    public static void tick() { if (active != null) current(active); }
    public static void cancel() {
        var d = active; active = null;
        if (d != null) {closeUnopened(d);d.future.completeExceptionally(new IllegalStateException("USER_INTERRUPTED"));}
    }
    private static void closeUnopened(Download d) {if(d.contentSession!=null)UiClientSessions.contentRequest("close",d.contentSession,"scoreview.read",Map.of(),UUID.randomUUID());}
}
