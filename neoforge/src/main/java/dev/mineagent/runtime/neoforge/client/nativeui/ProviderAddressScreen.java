package dev.mineagent.runtime.neoforge.client.nativeui;

import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import dev.mineagent.runtime.core.config.ProviderBaseUrls;
import dev.mineagent.runtime.neoforge.client.language.ClientLanguage;
import dev.vfyjxf.taffy.style.AlignContent;
import dev.vfyjxf.taffy.style.AlignItems;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.util.function.Consumer;

/** Scrollable MC-themed picker for settings entries outside the F2 workspace. Draft changes only. */
public final class ProviderAddressScreen extends NativeInputScreen {
    private final Screen parent;
    private ProviderAddressScreen(Screen parent, Consumer<String> selected, UIElement root) {
        super(new ModularUI(NativeUiTheme.ui(root), Minecraft.getInstance().player), Component.literal(t("地址模板")));
        this.parent = parent;
        root.getLayout().widthPercent(100).heightPercent(100).alignItems(AlignItems.CENTER).justifyContent(AlignContent.CENTER);
        var card = NativeUiTheme.card(new UIElement());
        card.getLayout().widthPercent(94).maxWidth(500).heightPercent(92).minHeight(0);
        root.addChild(card);
        var top = WorkspacePanels.row(); top.getLayout().height(25).flexShrink(0); card.addChild(top);
        var title = NativeUiTheme.text(t("地址模板"), NativeUiTheme.TEXT, 11);
        title.getLayout().widthAuto().minWidth(0).flex(1); top.addChild(title);
        top.addChild(NativeUiTheme.iconButton("×", this::onClose));
        card.addChild(WorkspacePanels.text(t("选择模板只填入地址；保存后选择或填写对应平台的模型。")));
        var list = WorkspacePanels.scroller(card); list.getLayout().minHeight(0);
        for (var preset : ProviderBaseUrls.ALL) {
            var row = WorkspacePanels.card(list, preset.baseUrl());
            var button = NativeUiTheme.button(t(preset.name()), () -> { selected.accept(preset.baseUrl()); onClose(); });
            button.getLayout().widthPercent(100); row.addChild(button);
        }
    }
    public static void open(Screen parent, Consumer<String> selected) {
        Minecraft.getInstance().setScreen(new ProviderAddressScreen(parent, selected, new UIElement()));
    }
    private static String t(String text) { return ClientLanguage.t(text); }
    @Override public void onClose() { Minecraft.getInstance().setScreen(parent); }
    @Override public boolean isPauseScreen() { return false; }
}
