package dev.mineagent.runtime.neoforge.client.webui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Explicit failure/recovery UI, not a successful WebGUI fallback. */
public final class WebGuiDiagnosticScreen extends Screen {
    private final String diagnostic;
    public WebGuiDiagnosticScreen(String diagnostic) { super(Component.literal(dev.mineagent.runtime.neoforge.client.language.ClientLanguage.t("WebGUI 诊断"))); this.diagnostic = diagnostic; }
    @Override protected void init() {
        addRenderableWidget(new StringWidget(12, 30, width - 24, 25, Component.literal(diagnostic), font));
        addRenderableWidget(Button.builder(Component.literal(dev.mineagent.runtime.neoforge.client.language.ClientLanguage.t("重试 WebGUI")), b -> WebGuiHostAdapter.INSTANCE.open())
                .bounds(width / 2 - 100, 90, 200, 22).build());
        addRenderableWidget(Button.builder(Component.literal(dev.mineagent.runtime.neoforge.client.language.ClientLanguage.t("迁移期原生管理（不是 WebGUI）")), b -> Minecraft.getInstance()
                .setScreen(dev.mineagent.runtime.neoforge.client.screen.ControlCenterScreen.create(this)))
                .bounds(width / 2 - 130, 120, 260, 22).build());
        addRenderableWidget(Button.builder(Component.literal(dev.mineagent.runtime.neoforge.client.language.ClientLanguage.t("返回")), b -> onClose()).bounds(width / 2 - 100, 150, 200, 22).build());
    }
    @Override public boolean isPauseScreen() { return false; }
}
