package dev.mineagent.runtime.neoforge.client.webui;

import com.cinemamod.mcef.MCEF;
import com.google.gson.*;
import dev.mineagent.runtime.api.ui.UiProtocol.*;
import dev.mineagent.runtime.client.webui.*;
import dev.mineagent.runtime.neoforge.network.UiPayloads;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import java.util.*;
import java.util.concurrent.*;

/** Client-owned HUD preferences. No Session, capability grant, score snapshot or model request is persisted. */
public final class HudPersistenceClient {
    private static final Gson JSON=new Gson();
    private static final Executor IO=new ThreadPoolExecutor(1,1,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(32),r->{var t=new Thread(r,"mineagent-hud-preferences");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    private static final Map<String,HudRestoreEntry> saved=new LinkedHashMap<>(),mounted=new LinkedHashMap<>();
    private static final Map<String,HudRestoreEntry> durable=new LinkedHashMap<>();
    private static final Map<String,Long> renderDeadlines=new LinkedHashMap<>();
    private static final Map<String,String> states=new LinkedHashMap<>();
    private static final Set<String> attempted=new HashSet<>();
    private static Object peer;
    private static UUID contextRequest,world,viewer;
    private static String scope;
    private static long epoch,contextDeadline,hostDeadline;
    private static boolean loaded,busy,hostAttempted;
    private static int writes;
    private HudPersistenceClient(){}
    private static UiStateStore store(){return new UiStateStore(Minecraft.getInstance().gameDirectory.toPath().resolve("mineagent-runtime-data/hud-preferences"));}
    public static void tick(){
        var mc=Minecraft.getInstance();var current=mc.getConnection();
        if(current!=peer){epoch++;peer=current;contextRequest=null;scope=null;world=null;viewer=null;loaded=false;busy=false;hostAttempted=false;writes=0;saved.clear();durable.clear();mounted.clear();renderDeadlines.clear();states.clear();attempted.clear();
            contextDeadline=System.currentTimeMillis()+15000;}
        if(current==null||mc.player==null||mc.level==null)return;
        for(var item:List.copyOf(renderDeadlines.entrySet()))if(item.getValue()<System.currentTimeMillis()&&!WebGuiHostAdapter.INSTANCE.packageHidden(item.getKey()))failed(item.getKey());
        if(contextRequest==null&&scope==null&&System.currentTimeMillis()<contextDeadline){
            contextRequest=UUID.randomUUID();ClientPacketDistributor.sendToServer(new UiPayloads.Command(contextRequest,"hudContext","{}"));
        }
        if(!loaded||saved.isEmpty()||writes>0)return;
        var host=WebGuiHostAdapter.INSTANCE;
        if(host.browser()==null){
            if(!hostAttempted){hostAttempted=true;hostDeadline=System.currentTimeMillis()+20000;}
            if(System.currentTimeMillis()>hostDeadline)return;
            if(MCEF.isInitialized()){hostDeadline=0;host.openPassive();}return;
        }
        var shell=UiClientSessions.current();
        if(shell==null||!shell.binding().worldId().equals(world)||!shell.binding().viewerPlayerId().equals(viewer)||busy||!PackagePreviewClient.idle())return;
        for(var entry:List.copyOf(saved.values())){
            if(attempted.contains(entry.key())||mounted.values().stream().anyMatch(m->m.key().equals(entry.key())))continue;
            attempted.add(entry.key());busy=true;long generation=epoch;Object connection=peer;
            states.put(entry.key(),"RESTORING");publish();
            PackagePreviewClient.openSavedHud(entry,()->epoch==generation&&peer==connection&&saved.get(entry.key())==entry)
                    .whenComplete((receipt,error)->{
                        if(epoch!=generation||peer!=connection)return;
                        busy=false;states.put(entry.key(),error==null?"LOADING":"RESTORE_FAILED");publish();
                    });
            break;
        }
    }
    public static boolean accept(UiPayloads.Event packet,Object networkConnection){
        if(!packet.requestId().equals(contextRequest))return false;
        var mc=Minecraft.getInstance();
        if(peer!=mc.getConnection()||mc.getConnection()==null||mc.getConnection().getConnection()!=networkConnection||mc.player==null)return true;
        contextRequest=null;contextDeadline=0;
        if(!packet.channel().equals("hudContext")){contextDeadline=0;return true;}
        try{
            var data=JsonParser.parseString(packet.json()).getAsJsonObject();UUID recipient=UUID.fromString(data.get("viewerId").getAsString());
            if(!recipient.equals(mc.player.getUUID()))throw new SecurityException("HUD_CONTEXT_VIEWER");
            world=UUID.fromString(data.get("worldId").getAsString());viewer=recipient;
            scope=(mc.getCurrentServer()==null?"integrated":mc.getCurrentServer().ip)+"|"+world+"|"+viewer;
            long generation=epoch;String key=scope;var storage=store();
            CompletableFuture.supplyAsync(()->{try{return storage.load(key);}catch(Exception e){throw new CompletionException(e);}},IO).whenComplete((value,error)->mc.execute(()->{
                if(epoch!=generation||!Objects.equals(scope,key))return;
                try{
                    if(error!=null)throw new IllegalStateException("HUD_PREFERENCE_LOAD_FAILED");
                    var root=JsonParser.parseString(value).getAsJsonObject();var list=root.has("entries")?root.getAsJsonArray("entries"):new JsonArray();
                    if(root.has("version")&&root.get("version").getAsInt()!=1)throw new IllegalArgumentException("HUD_PREFERENCE_VERSION");
                    if(list.size()>12)throw new IllegalArgumentException("HUD_PREFERENCE_BUDGET");
                    var parsed=new LinkedHashMap<String,HudRestoreEntry>();
                    for(var item:list){var entry=JSON.fromJson(item,HudRestoreEntry.class);if(parsed.putIfAbsent(entry.key(),entry)!=null)throw new IllegalArgumentException("HUD_PREFERENCE_DUPLICATE");}
                    saved.putAll(parsed);durable.putAll(parsed);loaded=true;for(var id:mounted.keySet())ready(id);publish();
                }catch(RuntimeException invalid){states.put("storage","HUD_PREFERENCE_LOAD_FAILED");publish();}
            }));
        }catch(RuntimeException invalid){contextDeadline=0;states.put("storage","HUD_CONTEXT_INVALID");publish();}
        return true;
    }
    public static void mounted(Session content,PackagePreviewTransfer.Resolved resolved){
        var b=content.binding();
        String named=dev.mineagent.runtime.core.packages.PackageUiEntrypoints.select(resolved.runtimePackage().entrypoints(),true).orElseThrow();
        var entry=HudRestoreEntry.from(content,resolved.runtimePackage().canonicalSha256(),HudRestoreEntry.Layout.initial());
        entry.require(content,b.worldId(),b.viewerPlayerId(),resolved.runtimePackage().canonicalSha256(),named);
        mounted.put(b.viewId(),entry);
        renderDeadlines.put(b.viewId(),System.currentTimeMillis()+20000);
    }
    public static void ready(String view){
        var entry=mounted.get(view);if(entry==null)return;
        if(PackageContentClient.session(view)!=null)renderDeadlines.remove(view);
        if(saved.containsKey(entry.key()))states.put(entry.key(),PackageContentClient.session(view)==null?"LOADING":"RENDERED");
        WebGuiHostAdapter.INSTANCE.emit("hudRememberState",Map.of("viewId",view,"enabled",durable.containsKey(entry.key()),"available",loaded&&writes==0&&PackageContentClient.session(view)!=null));publish();
    }
    public static void failed(String view){
        renderDeadlines.remove(view);var entry=mounted.remove(view);if(entry==null)return;
        if(saved.containsKey(entry.key())){attempted.add(entry.key());states.put(entry.key(),"RESTORE_FAILED");}
        // Remove this failed document, but not the saved preference. A later retry uses a new Session/frame.
        WebGuiHostAdapter.INSTANCE.emit("closeManagedView",Map.of("viewId",view));publish();
    }
    public static CompletableFuture<Map<String,String>> remember(String view,boolean enabled,HudRestoreEntry.Layout layout){
        var entry=mounted.get(view);var session=PackageContentClient.session(view);var descriptor=WebGuiHostAdapter.INSTANCE.viewPackage(view);
        if(!loaded||scope==null||entry==null||session==null||descriptor==null||!descriptor.passive())return CompletableFuture.failedFuture(new IllegalStateException("HUD_NOT_READY"));
        entry.require(session,world,viewer,entry.canonicalSha256(),descriptor.entry());
        if(enabled){if(saved.size()>=12&&!saved.containsKey(entry.key()))return CompletableFuture.failedFuture(new IllegalStateException("HUD_PREFERENCE_BUDGET"));saved.put(entry.key(),entry.withLayout(layout));}
        else{saved.remove(entry.key());states.remove(entry.key());}
        long generation=epoch;return persist().whenComplete((r,e)->{if(epoch==generation)ready(view);});
    }
    public static CompletableFuture<Map<String,String>> forget(String key){
        if(!loaded||scope==null)return CompletableFuture.failedFuture(new IllegalStateException("HUD_NOT_READY"));
        saved.remove(key);states.remove(key);publish();return persist();
    }
    public static void retry(String key){if(!loaded||!saved.containsKey(key))throw new IllegalArgumentException("HUD_BOOKMARK_MISSING");attempted.remove(key);states.remove(key);publish();}
    public static void sessionReady(Session session){
        if(scope==null&&contextRequest==null&&peer==Minecraft.getInstance().getConnection())contextDeadline=System.currentTimeMillis()+15000;
        publish();
    }
    public static CompletableFuture<Map<String,String>> resetPreferences(){
        if(scope==null||peer!=Minecraft.getInstance().getConnection())return CompletableFuture.failedFuture(new IllegalStateException("HUD_NOT_READY"));
        saved.clear();states.clear();attempted.clear();
        long generation=epoch;return persist().whenComplete((v,error)->{if(epoch==generation){loaded=error==null;publish();}});
    }
    public static void updateLayouts(JsonObject layouts){
        if(!loaded||layouts==null)return;boolean changed=false;
        for(var item:mounted.entrySet()){
            var entry=saved.get(item.getValue().key());if(entry==null||!layouts.has(item.getKey()))continue;
            var data=layouts.getAsJsonObject(item.getKey());var b=data.getAsJsonObject("bounds");
            var next=entry.withLayout(new HudRestoreEntry.Layout(b.get("x").getAsDouble(),b.get("y").getAsDouble(),b.get("width").getAsDouble(),b.get("height").getAsDouble(),data.get("minimized").getAsBoolean()));
            if(!next.equals(entry)){saved.put(entry.key(),next);changed=true;}
        }
        if(changed)persist().exceptionally(e->null);
    }
    public static void closed(String view){renderDeadlines.remove(view);var entry=mounted.remove(view);if(entry!=null&&saved.containsKey(entry.key()))forget(entry.key()).exceptionally(e->null);}
    public static void hostClosed(){mounted.clear();renderDeadlines.clear();}
    /** Wait only for queued disk writes, never for their main-thread UI callbacks. */
    public static void flushWrites(){
        try{CompletableFuture.runAsync(()->{},IO).get(2,TimeUnit.SECONDS);}
        catch(Exception failure){dev.mineagent.runtime.neoforge.MineAgentRuntimeMod.LOGGER.warn("HUD_PREFERENCE_FLUSH_FAILED code={}",failure.getClass().getSimpleName());if(failure instanceof InterruptedException)Thread.currentThread().interrupt();}
    }
    public static List<HudRestoreEntry> entries(){return List.copyOf(saved.values());}
    public static List<String> mountedViews(){return List.copyOf(mounted.keySet());}
    public static boolean settled(){return writes==0;}
    public static String status(String key){return states.getOrDefault(key,"");}
    private static CompletableFuture<Map<String,String>> persist(){
        String key=scope;long generation=epoch;var snapshot=Map.copyOf(saved);String body=JSON.toJson(Map.of("version",1,"entries",List.copyOf(saved.values())));var storage=store();writes++;
        var future=new CompletableFuture<Map<String,String>>();
        try{CompletableFuture.runAsync(()->{try{storage.save(key,body);}catch(Exception e){throw new CompletionException(e);}},IO).whenComplete((v,error)->Minecraft.getInstance().execute(()->{
            if(epoch!=generation){future.completeExceptionally(new IllegalStateException("HUD_CONTEXT_CHANGED"));return;}
            writes--;
            if(error!=null){if(saved.equals(snapshot)){saved.clear();saved.putAll(durable);}diagnostic("HUD_PREFERENCE_SAVE_FAILED");future.completeExceptionally(error);}
            else{durable.clear();durable.putAll(snapshot);future.complete(Map.of("status","HUD_PREFERENCE_SAVED"));}
            for(var id:mounted.keySet())ready(id);publish();
        }));}catch(RuntimeException busy){writes--;if(saved.equals(snapshot)){saved.clear();saved.putAll(durable);}diagnostic("HUD_PREFERENCE_SAVE_FAILED");future.completeExceptionally(busy);}return future;
    }
    private static void diagnostic(String code){WebGuiHostAdapter.INSTANCE.emit("sessionError",Map.of("code",code));}
    private static void publish(){WebGuiHostAdapter.INSTANCE.emit("hudRestoreStatus",Map.of("ready",loaded,"entries",saved.values().stream().map(e->Map.of("key",e.key(),"target",e.targetId(),"revision",e.packageRevision(),"status",states.getOrDefault(e.key(),"SAVED"))).toList(),"storageError",states.getOrDefault("storage","")));}
}
