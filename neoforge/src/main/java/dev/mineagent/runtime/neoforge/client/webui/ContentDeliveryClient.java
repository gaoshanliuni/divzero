package dev.mineagent.runtime.neoforge.client.webui;

import com.google.gson.*;
import dev.mineagent.runtime.api.ui.*;
import dev.mineagent.runtime.api.ui.UiProtocol.*;
import dev.mineagent.runtime.neoforge.network.UiPayloads;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.nio.charset.StandardCharsets;

/** Recipient-side lifecycle. Incoming notices never open a browser, focus a window, or accept an offer. */
@EventBusSubscriber(modid="mineagent_runtime",value=Dist.CLIENT)
public final class ContentDeliveryClient {
    private static final Gson JSON=new Gson();private static final Map<String,Entry> entries=new HashMap<>();private static final Set<UUID> announced=new HashSet<>();private static int ticks;
    private record DataPending(dev.mineagent.runtime.client.webui.DeliveryReadWitness.Reading read,String document){}
    private static final class Entry{final DeliveryProtocol.Launch launch;final dev.mineagent.runtime.client.webui.DeliveryReadWitness witness=new dev.mineagent.runtime.client.webui.DeliveryReadWitness();Session session;boolean received,ready,painting,painted,closed;int after,attempts,dataAttempts;long sequence,visibilityRevision,readRevision;String documentId="";DataPending data;Entry(DeliveryProtocol.Launch l,Session s){launch=l;session=s;}}
    private ContentDeliveryClient(){}
    /** Add a Native nonce only to the exact successful read callback. The page cannot choose revision/hash/context. */
    public static Receipt witnessRead(Session context,Receipt receipt){
        var e=entries.get(context.binding().viewId());if(receipt.code()!=Code.OBSERVED||e==null||e.closed||!ReadOnlyUiLease.sameContext(context,e.session))return receipt;
        var state=JsonParser.parseString(receipt.values().get("state")).getAsJsonObject();if(!e.launch.deliveryId().toString().equals(state.get("deliveryId").getAsString()))throw new SecurityException("DELIVERY_READ_RESULT_CONTEXT");
        long revision=state.get("revision").getAsLong();String hash=state.get("dataSha256").getAsString();e.readRevision=Math.max(e.readRevision,revision);
        var token=e.witness.issue(context,PackageContentClient.lifecycle(e.launch.viewId()),revision,hash,ticks);var values=new LinkedHashMap<>(receipt.values());values.remove("nativeDeliveryReadToken");token.ifPresent(t->values.put("nativeDeliveryReadToken",t));return new Receipt(receipt.operationId(),receipt.code(),values);
    }
    public static CompletableFuture<Receipt> acknowledgeData(String view,Session context,Map<String,String> args,UUID operation){
        var future=new CompletableFuture<Receipt>();var e=entries.get(view);var mc=Minecraft.getInstance();
        try{
            if(e==null||e.closed||!e.ready||!args.keySet().equals(Set.of("token"))||!operation.toString().equals(args.get("token"))||!ReadOnlyUiLease.sameContext(context,e.session))throw new SecurityException("DELIVERY_DATA_ACK_CONTEXT");
            var reading=e.witness.consume(args.get("token"),context,PackageContentClient.lifecycle(view),ticks);
            PackagePageAgent.inspectManagedView(view).whenComplete((observation,error)->mc.execute(()->{
                try{
                    if(error!=null||entries.get(view)!=e||e.closed||!ReadOnlyUiLease.sameContext(context,PackageContentClient.session(view))||PackageContentClient.lifecycle(view)!=reading.lifecycle())throw new SecurityException("DELIVERY_DATA_DOCUMENT_CHANGED");
                    String document=JsonParser.parseString(observation).getAsJsonObject().get("documentId").getAsString();
                    if(e.data==null||e.data.read().revision()!=reading.revision()||!e.data.read().sha256().equals(reading.sha256())||!e.data.document().equals(document)||e.data.read().lifecycle()!=reading.lifecycle()){e.data=new DataPending(reading,document);e.dataAttempts=0;e.after=ticks+20;}
                    future.complete(new Receipt(operation,Code.OBSERVED,Map.of("dataReadWitness","NATIVE_CALLBACK_RECEIVED","applicationSemanticsVerified","false")));
                }catch(Exception failure){future.completeExceptionally(failure);}
            }));
        }catch(Exception failure){future.completeExceptionally(failure);}return future;
    }
    public static boolean accept(UiPayloads.Event packet){if(!Set.of("deliveryNotice","deliveryUpdated","deliveryRetired","feedbackChanged").contains(packet.channel()))return false;
        try{var n=JsonParser.parseString(packet.json()).getAsJsonObject();UUID id=UUID.fromString(n.get("deliveryId").getAsString());var mc=Minecraft.getInstance();
            if(packet.channel().equals("deliveryNotice")&&announced.size()<4096&&announced.add(id)&&mc.player!=null)mc.player.sendSystemMessage(net.minecraft.network.chat.Component.literal(dev.mineagent.runtime.neoforge.client.language.ClientLanguage.t("MineAgent 收到内容邀请：")+n.get("title").getAsString()+dev.mineagent.runtime.neoforge.client.language.ClientLanguage.t("。按 F2，在收件箱中选择打开或拒绝。")));
            WebGuiHostAdapter.INSTANCE.emit("deliveryInboxChanged",Map.of("deliveryId",id.toString()));
            for(var e:List.copyOf(entries.values()))if(e.launch.deliveryId().equals(id)&&!e.closed){
                if(packet.channel().equals("feedbackChanged")&&PackageContentClient.session(e.launch.viewId())!=null)notifyFeedback(e.launch.viewId(),UUID.fromString(n.get("feedbackId").getAsString()),n.get("revision").getAsLong());
                else if(packet.channel().equals("deliveryRetired"))WebGuiHostAdapter.INSTANCE.emit("closeManagedView",Map.of("viewId",e.launch.viewId()));
                else if(packet.channel().equals("deliveryUpdated")&&PackageContentClient.session(e.launch.viewId())!=null)notifyPage(e.launch.viewId(),n.get("dataRevision").getAsLong());
            }
        }catch(RuntimeException e){if(Minecraft.getInstance().player!=null)Minecraft.getInstance().player.sendSystemMessage(net.minecraft.network.chat.Component.literal(dev.mineagent.runtime.neoforge.client.language.ClientLanguage.t("MineAgent 内容通知无效")));}return true;
    }
    public static CompletableFuture<Receipt> open(UUID delivery,UUID pkg,long revision){return PackagePreviewClient.openDelivery(delivery,pkg,revision);}
    public static void mounted(DeliveryProtocol.Launch launch,Session session){if(entries.size()>=32)throw new IllegalStateException("DELIVERY_CLIENT_BUDGET");var e=new Entry(launch,session);entries.put(launch.viewId(),e);DeliveryDraftClient.mounted(launch,session);UiClientSessions.command("delivery.received",identity(e),UUID.randomUUID()).whenComplete((r,error)->Minecraft.getInstance().execute(()->{if(entries.get(launch.viewId())!=e||e.closed)return;if(error==null&&r.code()==Code.APPLIED){e.received=true;e.after=ticks+20;}else retireFailed(e,"DELIVERY_RECEIVED_REJECTED");}));}
    public static void rendered(String view){var e=entries.get(view);if(e!=null&&!e.closed){var current=PackageContentClient.rawSession(view);if(current.pageGeneration()!=e.session.pageGeneration()){e.painted=false;e.attempts=0;e.documentId="";e.data=null;}e.session=current;e.ready=true;e.after=ticks+20;visibility(view,true);DeliveryDraftClient.ready(view);if(e.readRevision>0)notifyPage(view,e.readRevision);}}
    public static void visibility(String view,boolean visible){var e=entries.get(view);if(e==null||e.closed)return;var args=new LinkedHashMap<>(identity(e));args.put("visible",Boolean.toString(visible));args.put("visibilityRevision",Long.toString(++e.visibilityRevision));UiClientSessions.command("delivery.visibility",args,UUID.randomUUID());}
    /** The page cannot choose a documentId/author/task. Obtain only the actual document identity from the Native observer. */
    public static CompletableFuture<Receipt> submitFeedback(String view,Session context,Map<String,String> args,UUID operation){
        var result=new CompletableFuture<Receipt>();var e=entries.get(view);var mc=Minecraft.getInstance();
        if(e==null||e.closed||!e.ready||!e.received||!e.painted||!DeliveryProtocol.feedbackEnabled(context.binding())||!args.keySet().equals(Set.of("event","payload"))){result.completeExceptionally(new SecurityException("FEEDBACK_CURRENT_PAINT_REQUIRED"));return result;}
        DeliveryDraftClient.beforeSubmit(view).thenCompose(ignored->PackagePageAgent.inspectManagedView(view)).whenComplete((observed,error)->mc.execute(()->{
            try{if(error!=null)throw new IllegalStateException("FEEDBACK_DOCUMENT_UNAVAILABLE",error);var current=PackageContentClient.session(view);String document=JsonParser.parseString(observed).getAsJsonObject().get("documentId").getAsString();
                if(entries.get(view)!=e||e.closed||!e.painted||current==null||!ReadOnlyUiLease.sameContext(context,current)||!document.equals(e.documentId))throw new SecurityException("FEEDBACK_DOCUMENT_CHANGED");
                var body=new LinkedHashMap<>(args);body.put("documentId",document);FeedbackRestartSmokeClient.captureWire(context,operation,body);UiClientSessions.contentRequest("command",context,"feedback.submit",body,operation).whenComplete((receipt,failure)->{if(failure!=null)result.completeExceptionally(failure);else result.complete(receipt);});
            }catch(Exception failure){result.completeExceptionally(failure);}
        }));return result;
    }
    public static void closed(String view,Session session){var e=entries.get(view);if(e==null||!e.session.sessionId().equals(session.sessionId()))return;e.closed=true;e.after=ticks+2;DeliveryDraftClient.close(view);}
    private static Map<String,String> identity(Entry e){return Map.of("deliveryId",e.launch.deliveryId().toString(),"leaseId",e.launch.leaseId().toString(),"sessionId",e.session.sessionId().toString(),"pageGeneration",Long.toString(e.session.pageGeneration()));}
    private static void notifyPage(String view,long revision){var host=WebGuiHostAdapter.INSTANCE;if(host.browser()==null)return;String url=host.packageUrl(view);for(long id:PackagePageAgent.frameIds(host.browser().getFrameIdentifiers())){var f=host.browser().getFrame(id);if(f!=null&&!f.isMain()&&Objects.equals(url,f.getURL())){f.executeJavaScript("window.dispatchEvent(new CustomEvent('mineagent:delivery-refresh',{detail:{revision:"+revision+"}}));",url,0);return;}}}
    private static void notifyFeedback(String view,UUID feedback,long revision){var host=WebGuiHostAdapter.INSTANCE;if(host.browser()==null)return;String url=host.packageUrl(view);for(long id:PackagePageAgent.frameIds(host.browser().getFrameIdentifiers())){var f=host.browser().getFrame(id);if(f!=null&&!f.isMain()&&Objects.equals(url,f.getURL())){f.executeJavaScript("window.dispatchEvent(new CustomEvent('mineagent:feedback-refresh',{detail:"+JSON.toJson(Map.of("feedbackId",feedback.toString(),"revision",revision))+"}));",url,0);return;}}}
    private static void retireFailed(Entry e,String code){WebGuiHostAdapter.INSTANCE.emit("contentError",Map.of("viewId",e.launch.viewId(),"code",code));}
    public static List<String> views(){return entries.entrySet().stream().filter(e->!e.getValue().closed&&PackageContentClient.session(e.getKey())!=null).map(Map.Entry::getKey).toList();}
    public static void clear(){entries.clear();announced.clear();DeliveryDraftClient.clear();}
    @SubscribeEvent public static void tick(ClientTickEvent.Post event){ticks++;var mc=Minecraft.getInstance();if(mc.getConnection()==null){clear();return;}DeliveryDraftClient.tick(ticks);for(var e:List.copyOf(entries.values())){
        if(e.closed){if(ticks>=e.after&&UiClientSessions.current()!=null){UiClientSessions.command("delivery.closed",identity(e),UUID.randomUUID());entries.remove(e.launch.viewId(),e);}continue;}
        if(!e.ready||!e.received||e.painting||e.painted&&e.data==null||ticks<e.after||(e.data==null?e.attempts:e.dataAttempts)>=3||PackageContentClient.session(e.launch.viewId())==null)continue;
        final var context=e.session;final long lifecycle=PackageContentClient.lifecycle(e.launch.viewId());final var data=e.data;
        e.painting=true;if(data==null)e.attempts++;else e.dataAttempts++;
        PackagePageAgent.captureManagedView(e.launch.viewId()).whenComplete((shot,error)->mc.execute(()->{
            if(entries.get(e.launch.viewId())!=e||e.closed){e.painting=false;return;}if(error!=null){e.painting=false;e.after=ticks+40;return;}
            try{
                var current=PackageContentClient.session(e.launch.viewId());if(!ReadOnlyUiLease.sameContext(context,current)||lifecycle!=PackageContentClient.lifecycle(e.launch.viewId())){e.painting=false;return;}
                if(data!=null&&(data.read().lifecycle()!=lifecycle||!data.document().equals(shot.documentId()))){if(e.data==data)e.data=null;e.painting=false;return;}
                var args=new LinkedHashMap<>(identity(e));args.put("documentId",shot.documentId());args.put("layoutHash",dev.mineagent.runtime.core.packages.RuntimePackageCanonicalizer.sha256(shot.layoutIdentity().getBytes(StandardCharsets.UTF_8)));args.put("paintSequence",Long.toString(++e.sequence));
                if(data!=null){args.put("dataRevision",Long.toString(data.read().revision()));args.put("dataSha256",data.read().sha256());}
                UiClientSessions.command("delivery.painted",args,UUID.randomUUID()).whenComplete((r,fail)->mc.execute(()->{
                    e.painting=false;var live=PackageContentClient.session(e.launch.viewId());if(entries.get(e.launch.viewId())!=e||e.closed||!ReadOnlyUiLease.sameContext(context,live)||lifecycle!=PackageContentClient.lifecycle(e.launch.viewId()))return;
                    if(fail==null&&r.code()==Code.APPLIED){e.painted=true;e.documentId=shot.documentId();if(data!=null){e.witness.confirm(data.read());if(e.data==data)e.data=null;}}
                    else if(data!=null){if(e.data==data)e.data=null;e.after=ticks+40;}else retireFailed(e,"DELIVERY_PAINT_RECEIPT_REJECTED");
                }));
            }catch(Exception failure){e.painting=false;retireFailed(e,"DELIVERY_PAINT_PROOF_FAILED");}
        }));
    }}
}
