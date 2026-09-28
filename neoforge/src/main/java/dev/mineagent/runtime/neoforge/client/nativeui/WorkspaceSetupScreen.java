package dev.mineagent.runtime.neoforge.client.nativeui;

import com.lowdragmc.lowdraglib2.gui.holder.ModularUIScreen;
import com.lowdragmc.lowdraglib2.gui.ui.*;
import com.lowdragmc.lowdraglib2.gui.ui.elements.*;
import dev.mineagent.runtime.neoforge.client.language.ClientLanguage;
import dev.mineagent.runtime.neoforge.network.PanelSnapshotInbox;
import dev.mineagent.runtime.client.trust.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import dev.vfyjxf.taffy.style.*;
import java.util.*;

/** The first-connection trust prompt and mod-list entry; never a second control center. */
public final class WorkspaceSetupScreen extends NativeInputScreen {
    private final Screen parent;
    public WorkspaceSetupScreen(Screen parent){this(parent,new UIElement());}
    private WorkspaceSetupScreen(Screen parent,UIElement root){
        super(new ModularUI(NativeUiTheme.ui(root),Minecraft.getInstance().player),Component.literal("DivZero"));this.parent=parent;
        root.getLayout().widthPercent(100).heightPercent(100).alignItems(AlignItems.CENTER).justifyContent(AlignContent.CENTER);
        var card=NativeUiTheme.card(new UIElement());card.getLayout().widthPercent(85).maxWidth(420).paddingAll(18).gapAll(9);root.addChild(card);
        card.addChild(NativeUiTheme.text("DivZero",NativeUiTheme.ACCENT,18));
        var notice=NativeUiTheme.text("",NativeUiTheme.MUTED,9);card.addChild(notice);
        var mc=Minecraft.getInstance();
        if(mc.level!=null){
            var values=PanelSnapshotInbox.snapshot().values();String fingerprint=values.getOrDefault("security.identityFingerprint","");String server=mc.getCurrentServer()==null?"local-integrated":mc.getCurrentServer().ip;
            TrustStatus trust=TrustStatus.UNKNOWN;try{trust=store().status(server,fingerprint);}catch(Exception error){notice.setText(Component.literal(error.getMessage()));}
            if(trust!=TrustStatus.TRUSTED){
                card.addChild(NativeUiTheme.text(t("服务器信任"),NativeUiTheme.TEXT,12));card.addChild(NativeUiTheme.text(server,NativeUiTheme.TEXT,9));card.addChild(NativeUiTheme.text(t("指纹: ")+fingerprint,NativeUiTheme.MUTED,8));
                if(trust==TrustStatus.MISMATCH)notice.setText(Component.literal(t("服务器指纹已变化，请核对后再确认。")));
                var confirm=NativeUiTheme.button(t(trust==TrustStatus.MISMATCH?"确认更新指纹":"信任此服务器"),()->{
                    try{var current=PanelSnapshotInbox.snapshot().values();if(!PanelSnapshotInbox.signatureValid()||!fingerprint.equals(current.get("security.identityFingerprint")))throw new IllegalStateException("SERVER_IDENTITY_CHANGED");store().confirm(server,fingerprint,Base64.getDecoder().decode(current.get("security.identityPublicKey")));NativeWorkspaceScreen.openSection(dev.mineagent.runtime.api.config.PanelSection.PROVIDERS);}catch(Exception failure){notice.setText(Component.literal(failure.getMessage()));}
                });confirm.setActive(PanelSnapshotInbox.signatureValid()&&!fingerprint.isBlank());card.addChild(confirm);
            }else card.addChild(NativeUiTheme.button(t("打开工作区设置"),()->NativeWorkspaceScreen.openSection(dev.mineagent.runtime.api.config.PanelSection.PROVIDERS)));
        }else notice.setText(Component.literal(t("进入世界后可管理 AI 与模型设置。")));
        card.addChild(NativeUiTheme.button(t("语言"),()->mc.setScreen(new dev.mineagent.runtime.neoforge.client.screen.LanguageScreen(this))));
        card.addChild(NativeUiTheme.button(t("关于"),()->mc.setScreen(new dev.mineagent.runtime.neoforge.client.screen.AboutScreen(this))));
        card.addChild(NativeUiTheme.button(t("返回"),this::onClose));NativeUiTheme.controls(root);
    }
    private static ServerTrustStore store()throws java.io.IOException{return new ServerTrustStore(Minecraft.getInstance().gameDirectory.toPath().resolve("config/mineagent-trusted-servers.properties"));}
    private static String t(String value){return ClientLanguage.t(value);}
    @Override public void onClose(){Minecraft.getInstance().setScreen(parent);}
}
