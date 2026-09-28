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

/** Mod-list settings entry. First-use participation is handled by the chat buttons. */
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
            card.addChild(NativeUiTheme.button(t("此世界启用设置"),()->{mc.setScreen(new net.minecraft.client.gui.screens.ChatScreen("",false));dev.mineagent.runtime.neoforge.client.MineAgentClientTrustPrompt.showChoice(true);}));
            if(dev.mineagent.runtime.neoforge.client.MineAgentClientTrustPrompt.enabled())card.addChild(NativeUiTheme.button(t("打开工作区设置"),()->NativeWorkspaceScreen.openSection(dev.mineagent.runtime.api.config.PanelSection.PROVIDERS)));
        }else {
            notice.setText(Component.literal(t("进入世界后可管理 AI 与模型设置。")));
            card.addChild(NativeUiTheme.button(t("本机资源包"),()->mc.setScreen(new dev.mineagent.runtime.neoforge.client.screen.LocalResourcePackScreen(this))));
            card.addChild(NativeUiTheme.button(t("世界重开恢复"),()->mc.setScreen(new dev.mineagent.runtime.neoforge.client.screen.WorldReopenRecoveryScreen(this))));
        }
        card.addChild(NativeUiTheme.button(t("语言"),()->mc.setScreen(new dev.mineagent.runtime.neoforge.client.screen.LanguageScreen(this))));
        card.addChild(NativeUiTheme.button(t("关于"),()->mc.setScreen(new dev.mineagent.runtime.neoforge.client.screen.AboutScreen(this))));
        card.addChild(NativeUiTheme.button(t("返回"),this::onClose));NativeUiTheme.controls(root);
    }
    private static ServerTrustStore store()throws java.io.IOException{return new ServerTrustStore(Minecraft.getInstance().gameDirectory.toPath().resolve("config/mineagent-trusted-servers.properties"));}
    private static String t(String value){return ClientLanguage.t(value);}
    @Override public void onClose(){Minecraft.getInstance().setScreen(parent);}
}
