package dev.mineagent.runtime.neoforge.client.webui;

import com.google.gson.*;
import dev.mineagent.runtime.api.ui.UiProtocol.*;
import dev.mineagent.runtime.neoforge.client.nativeui.*;
import dev.mineagent.runtime.neoforge.network.UiPayloads;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Routes connection-scoped native UI messages. The built-in workspace is the only shell session. */
@EventBusSubscriber(modid="mineagent_runtime",value=Dist.CLIENT)
public final class UiClientSessions {
    private static final Gson JSON=new Gson();
    @SubscribeEvent public static void register(RegisterClientPayloadHandlersEvent event){
        event.register(UiPayloads.Event.TYPE,(packet,context)->{var source=context.connection();context.enqueueWork(()->{
            var mc=Minecraft.getInstance();if(mc.getConnection()==null||mc.getConnection().getConnection()!=source)return;
            if(packet.channel().equals("nativeInterfaceEvent")){NativeInterfacesClient.eventReply(packet);return;}
            if(packet.channel().equals("nativeInterfaceReady")){NativeInterfacesClient.restoreAcknowledged(packet.requestId());return;}
            if(NativeWorkspaceConnection.accept(packet)||StatePushClient.accept(packet)||ContentDeliveryClient.accept(packet)||WorldUiClient.accept(packet,source)||HudPersistenceClient.accept(packet,source))return;
            accept(packet);
        });});
    }
    public static Session current(){return NativeWorkspaceConnection.current();}
    public static void open(){NativeWorkspaceConnection.open();}
    private static void accept(UiPayloads.Event packet){
        if(packet.channel().equals("agentPanelOpen")){var data=JsonParser.parseString(packet.json()).getAsJsonObject();dev.mineagent.runtime.neoforge.client.nativeui.AgentProfileScreen.open(UUID.fromString(data.get("agentId").getAsString()),data.get("name").getAsString());return;}
        if(packet.channel().equals("nativeInterface")){
            if(net.neoforged.fml.ModList.get().isLoaded("ldlib2"))dev.mineagent.runtime.neoforge.client.nativeui.NativeInterfacesClient.accept(packet);
            else ClientPacketDistributor.sendToServer(new UiPayloads.Command(packet.requestId(),"nativeInterfaceReply","{\"status\":\"REJECTED\",\"error\":\"NATIVE_UI_LDLIB2_REQUIRED\"}"));
            return;
        }
        if(packet.channel().equals("entityModelInspect")){dev.mineagent.runtime.neoforge.client.objects.EntityPartModels.inspect(packet);return;}
        if(Set.of("entityVisualReset","entityVisualRule","entityAnimationInspect").contains(packet.channel())){dev.mineagent.runtime.neoforge.client.objects.EntityVisualClient.accept(packet);return;}
        if(packet.channel().equals("nativeChatStream")){dev.mineagent.runtime.neoforge.client.chat.NativeStreamingChat.accept(packet);return;}
        if(packet.channel().equals("blockTexture")){dev.mineagent.runtime.neoforge.client.resources.BlockTextureClient.accept(packet);return;}
        if(packet.channel().equals("chatMessageDisplay")){dev.mineagent.runtime.neoforge.client.chat.ChatMessageDisplayClient.accept(packet);return;}
        if(packet.channel().equals("nativeThinkingSetting")){var mode=JsonParser.parseString(packet.json()).getAsJsonObject().get("mode").getAsString();var old=dev.mineagent.runtime.neoforge.client.chat.NativeChatPreferencesClient.view();boolean value=mode.equals("toggle")?!((Boolean)old.get("showThinking")):mode.equals("on");dev.mineagent.runtime.neoforge.client.chat.NativeChatPreferencesClient.save(value,((Number)old.get("revision")).longValue()).whenComplete((v,e)->Minecraft.getInstance().execute(()->{if(Minecraft.getInstance().player!=null)Minecraft.getInstance().gui.getChat().addClientSystemMessage(net.minecraft.network.chat.Component.literal(e==null?dev.mineagent.runtime.neoforge.client.language.ClientLanguage.t("原生聊天思考：")+(value?dev.mineagent.runtime.neoforge.client.language.ClientLanguage.t("显示"):dev.mineagent.runtime.neoforge.client.language.ClientLanguage.t("隐藏")):dev.mineagent.runtime.neoforge.client.language.ClientLanguage.t("思考显示设置失败")));}));return;}
        if(packet.channel().equals("skinUiOpen")){SkinUiClient.open(JsonParser.parseString(packet.json()).getAsJsonObject().get("agentId").getAsString());return;}
        if(packet.channel().equals("previewOpen")){PreviewClient.open(JsonParser.parseString(packet.json()).getAsJsonObject().get("previewId").getAsString());return;}
        if(packet.channel().equals("buildingFilesOpen")){var fileEvent=JsonParser.parseString(packet.json()).getAsJsonObject();BuildingFilesClient.requestOpen(fileEvent.get("agentId").getAsString(),fileEvent.has("fileId")?fileEvent.get("fileId").getAsString():"");return;}

        if(!NativeWorkspaceConnection.ready())return;
        if(packet.channel().equals("packageViewOutdated")){var data=JsonParser.parseString(packet.json()).getAsJsonObject();PackageContentClient.outdated(data.get("viewId").getAsString(),UUID.fromString(data.get("sessionId").getAsString()));return;}
        if(UiAgentClient.accept(packet))return;
        NativeWorkspaceScreen.push(packet.channel(),JsonParser.parseString(packet.json()));
    }
    public static CompletableFuture<Receipt> command(String action,Map<String,String> args,UUID operation){return NativeWorkspaceConnection.command(action,args,operation);}
    public static CompletableFuture<Receipt> contentRequest(String channel,Session session,String action,Map<String,String> args,UUID operation){return NativeWorkspaceConnection.contentRequest(channel,session,action,args,operation);}
    public static void tick(){PackagePreviewClient.tick();ClientResourcePacks.tick();ClientScriptPackages.tick();}
    public static void reset(boolean notify){
        var session=current();NativePackageViews.clear();PackageContentClient.clear();PackageUiStateClient.clear();ContentDeliveryClient.clear();PackagePreviewClient.cancel();ClientResourcePacks.cancelDownload();ClientScriptPackages.cancelDownload();dev.mineagent.runtime.neoforge.client.audio.ConversationVoicePlayback.clear(null);
        if(notify&&session!=null&&Minecraft.getInstance().getConnection()!=null){var request=new Request(UUID.randomUUID(),session.sessionId(),session.pageGeneration(),session.controlEpoch(),session.binding().taskRevision(),"close",Map.of());ClientPacketDistributor.sendToServer(new UiPayloads.Command(request.operationId(),"close",JSON.toJson(request)));}
        NativeWorkspaceConnection.reset();
    }
    private UiClientSessions(){}
}
