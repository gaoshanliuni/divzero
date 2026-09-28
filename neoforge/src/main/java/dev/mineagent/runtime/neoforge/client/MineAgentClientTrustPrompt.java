package dev.mineagent.runtime.neoforge.client;

import com.mojang.brigadier.arguments.StringArgumentType;
import dev.mineagent.runtime.client.trust.*;
import dev.mineagent.runtime.neoforge.client.language.ClientLanguage;
import dev.mineagent.runtime.neoforge.client.nativeui.*;
import dev.mineagent.runtime.neoforge.network.*;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.*;
import net.minecraft.ChatFormatting;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.*;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import java.util.*;
import static net.minecraft.commands.Commands.*;

/** First-use consent lives in chat; clicking also confirms the displayed signed server identity. */
@EventBusSubscriber(modid="mineagent_runtime", value=Dist.CLIENT)
public final class MineAgentClientTrustPrompt {
    private record Prompt(UUID token, Object connection, UUID player, String scope, String challenge,
                          String server, String fingerprint, String publicKey) {}
    private static Map<String, Object> pendingWebNotice;
    private static Prompt prompt;
    private static Object connection;
    private static String scope="", state="", prompted="";
    private static boolean enabled;
    private static long retryAt;
    public static boolean enabled(){return enabled && connection==Minecraft.getInstance().getConnection();}
    private static String serverId(){var mc=Minecraft.getInstance();return mc.getCurrentServer()==null?"local-integrated":mc.getCurrentServer().ip;}
    private static ServerTrustStore store() throws java.io.IOException {
        return new ServerTrustStore(Minecraft.getInstance().gameDirectory.toPath().resolve("config/mineagent-trusted-servers.properties"));
    }
    private static String scope(Map<String,String> values){return values.getOrDefault("security.worldId","")+"/"+values.getOrDefault("security.configInstance","")+"/"+values.getOrDefault("security.identityFingerprint","");}
    public static void onSnapshot(MineAgentPayloads.PanelSnapshot payload) {
        if(!PanelSnapshotInbox.signatureValid())return;
        var mc=Minecraft.getInstance();if(mc.player==null||mc.getConnection()==null)return;
        var values=payload.values();String nextScope=scope(values);
        if(connection!=mc.getConnection()||!scope.equals(nextScope)){NativeWorkspaceConnection.activationChanged(false);NativeInterfacesClient.activationChanged();clear();connection=mc.getConnection();scope=nextScope;}
        String nextState=values.getOrDefault("runtime.activation.state","UNDECIDED");
        try {
            String fingerprint=values.getOrDefault("security.identityFingerprint","");
            var trust=store().status(serverId(),fingerprint);
            if(Boolean.getBoolean("mineagent.multiplayerSmokeClient")&&trust!=TrustStatus.TRUSTED){store().confirm(serverId(),fingerprint,Base64.getDecoder().decode(values.get("security.identityPublicKey")));trust=TrustStatus.TRUSTED;}
            boolean nextEnabled=nextState.equals("ENABLED")&&trust==TrustStatus.TRUSTED;
            boolean changed=enabled!=nextEnabled;enabled=nextEnabled;state=nextState;
            pendingWebNotice=Map.of("trust",trust.name(),"fingerprint",fingerprint,"initialized",enabled);
            if(changed){NativeWorkspaceConnection.activationChanged(enabled);NativeInterfacesClient.activationChanged();}
            if(prompt!=null&&!prompt.challenge.equals(values.get("runtime.activation.challenge")))prompt=null;
            if(!enabled&&!state.equals("DISABLED"))showChoice(false);
            flushWebNotice();
        } catch(Exception failure){message("无法读取世界启用状态：",failure.getMessage());}
    }
    public static void showChoice(boolean force) {
        var mc=Minecraft.getInstance();var values=PanelSnapshotInbox.snapshot().values();
        if(mc.player==null||connection!=mc.getConnection()||!PanelSnapshotInbox.signatureValid()||!scope.equals(scope(values))) {
            if(mc.getConnection()!=null)ClientPacketDistributor.sendToServer(new MineAgentPayloads.PanelRequest());return;
        }
        String challenge=values.getOrDefault("runtime.activation.challenge","");if(challenge.isBlank())return;
        if(!force&&prompted.equals(challenge))return;
        prompt=new Prompt(UUID.randomUUID(),connection,mc.player.getUUID(),scope,challenge,serverId(),values.get("security.identityFingerprint"),values.get("security.identityPublicKey"));
        prompted=challenge;
        var line=Component.literal("[DivZero] "+ClientLanguage.t("是否为你在这个世界中启用 DivZero？"));
        line.append(button("启用","enable",prompt,ChatFormatting.GREEN));
        line.append(button("禁用","disable",prompt,ChatFormatting.RED));
        mc.gui.getChat().addClientSystemMessage(line);
        message("按 T 点击按钮；选择仅对你在此世界生效，可用 /divzero_setup 更改。","");
        message("启用不改变服务器权限，电脑操作仍需单独确认。","");
        try{if(store().status(prompt.server,prompt.fingerprint)==TrustStatus.MISMATCH)message("服务器指纹已变化，请核对后再确认。",prompt.fingerprint);}catch(Exception ignored){}
    }
    private static MutableComponent button(String label,String action,Prompt choice,ChatFormatting color) {
        return Component.literal(" ["+ClientLanguage.t(label)+"]").withStyle(style->style.withColor(color)
                .withClickEvent(new ClickEvent.RunCommand("/divzero_setup "+action+" "+choice.token))
                .withHoverEvent(new HoverEvent.ShowText(Component.literal(choice.server+"\n"+choice.fingerprint))));
    }
    @SubscribeEvent public static void commands(RegisterClientCommandsEvent event) {
        var root=literal("divzero_setup").executes(c->{showChoice(true);return 1;});
        for(String action:List.of("enable","disable"))root.then(literal(action).then(argument("token",StringArgumentType.word()).executes(c->decide(action,StringArgumentType.getString(c,"token")))));
        event.getDispatcher().register(root);
    }
    public static int decide(String action,String token) {
        var mc=Minecraft.getInstance();var p=prompt;var values=PanelSnapshotInbox.snapshot().values();
        if(p==null||!Set.of("enable","disable").contains(action)||!p.token.toString().equals(token)||p.connection!=mc.getConnection()
                ||mc.player==null||!p.player.equals(mc.player.getUUID())||!p.scope.equals(scope(values))||!PanelSnapshotInbox.signatureValid()
                ||!p.challenge.equals(values.get("runtime.activation.challenge"))) {message("此按钮已失效，请用 /divzero_setup 重新选择。","");return 0;}
        try {
            if(action.equals("enable"))store().confirm(p.server,p.fingerprint,Base64.getDecoder().decode(p.publicKey));
            prompt=null;
            mc.player.connection.sendCommand("ai activation "+action+" "+p.challenge);
            return 1;
        }catch(Exception failure){message("无法保存世界选择：",failure.getMessage());return 0;}
    }
    @SubscribeEvent public static void tick(ClientTickEvent.Post event) {
        var mc=Minecraft.getInstance();if(connection!=null&&connection!=mc.getConnection())clear();
        if(mc.player!=null&&mc.getConnection()!=null&&connection==null&&System.currentTimeMillis()>=retryAt){retryAt=System.currentTimeMillis()+2000;ClientPacketDistributor.sendToServer(new MineAgentPayloads.PanelRequest());}
    }
    @SubscribeEvent public static void logout(ClientPlayerNetworkEvent.LoggingOut event){clear();}
    private static void clear(){prompt=null;connection=null;scope=state=prompted="";enabled=false;pendingWebNotice=null;}
    private static void message(String source,String detail){Minecraft.getInstance().gui.getChat().addClientSystemMessage(Component.literal("[DivZero] "+ClientLanguage.t(source)+Objects.toString(detail,"")).withStyle(ChatFormatting.GRAY));}
    public static void refreshWebNotice(){onSnapshot(new MineAgentPayloads.PanelSnapshot(PanelSnapshotInbox.snapshot().revision(),PanelSnapshotInbox.snapshot().values()));}
    public static void flushWebNotice(){if(pendingWebNotice!=null&&dev.mineagent.runtime.neoforge.client.webui.WebGuiHostAdapter.INSTANCE.ready()){dev.mineagent.runtime.neoforge.client.webui.WebGuiHostAdapter.INSTANCE.emit("setupNotice",pendingWebNotice);pendingWebNotice=null;}}
    public static void clearWebNotice(){pendingWebNotice=null;}
    private MineAgentClientTrustPrompt(){}
}
