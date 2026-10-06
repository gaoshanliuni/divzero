package dev.mineagent.runtime.legacy189.client;

import com.google.gson.*;
import dev.mineagent.runtime.legacy189.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.*;
import net.minecraft.client.resources.I18n;
import net.minecraft.item.ItemStack;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import org.lwjgl.input.Mouse;
import java.io.IOException;
import java.util.*;

public final class DuelClient {
    private static JsonObject state;
    private static boolean openPending;
    static final int MENU_BUTTON = 18930;
    private static final String[] LABELS = {"头盔", "胸甲", "护腿", "靴子", "主手", "gui.divzero.duel.supplies", "gui.divzero.duel.secondary"};
    private static String slotName(int slot){return slot>=5?I18n.format(LABELS[slot]):LABELS[slot];}
    public static void receive(String json) {
        final JsonObject value = new JsonParser().parse(json).getAsJsonObject();
        Minecraft.getMinecraft().addScheduledTask(() -> { state = value; openPending |= value.get("open").getAsBoolean(); });
    }
    public static void clear() { state = null; openPending = false; }
    public static void tick() {
        Minecraft mc = Minecraft.getMinecraft();
        if (state == null || mc.thePlayer == null) return;
        if (!mc.thePlayer.getUniqueID().toString().equals(state.get("owner").getAsString()) || mc.thePlayer.dimension != 0) { clear(); return; }
        if (openPending && mc.thePlayer.isEntityAlive()) { openPending = false; mc.displayGuiScreen(new LoadoutScreen()); }
        if (mc.currentScreen instanceof LoadoutScreen) ((LoadoutScreen) mc.currentScreen).refresh();
    }
    public static JsonObject state() { return state; }
    private static boolean available() {
        Minecraft mc = Minecraft.getMinecraft();
        return state != null && mc.theWorld != null && mc.thePlayer != null && mc.isSingleplayer()
                && mc.thePlayer.dimension == 0 && mc.thePlayer.isEntityAlive()
                && mc.thePlayer.getUniqueID().toString().equals(state.get("owner").getAsString());
    }
    public static void open() {
        if (available()) Minecraft.getMinecraft().displayGuiScreen(new LoadoutScreen());
    }
    @SubscribeEvent public void pauseMenu(GuiScreenEvent.InitGuiEvent.Post event) {
        if (event.gui instanceof GuiIngameMenu && available()) event.buttonList.add(new GuiButton(MENU_BUTTON,
                event.gui.width / 2 - 100, Math.min(event.gui.height - 24, event.gui.height / 4 + 128), 200, 20,
                I18n.format("gui.divzero.duel.menu")));
    }
    @SubscribeEvent public void pauseClick(GuiScreenEvent.ActionPerformedEvent.Post event) {
        if (event.gui instanceof GuiIngameMenu && event.button.id == MENU_BUTTON) open();
    }
    private static String menuHint() {
        return I18n.format("gui.divzero.duel.hint", net.minecraft.client.settings.GameSettings.getKeyDisplayString(ClientProxy.DUEL.getKeyCode()));
    }
    public static boolean fixtureChoose(int actor, int slot, String item) throws IOException {
        Minecraft mc = Minecraft.getMinecraft();
        if (!(mc.currentScreen instanceof LoadoutScreen)) return false;
        ((LoadoutScreen) mc.currentScreen).fixtureSelect(actor * 10 + slot);
        ItemScreen picker = (ItemScreen) mc.currentScreen;
        int index = picker.choices.indexOf(item); if (index < 0) throw new IllegalArgumentException("FIXTURE_ITEM_MISSING");
        picker.page = index / 12; picker.initGui(); picker.fixtureSelect(index); return true;
    }
    private static JsonObject request(String action) {
        JsonObject value = new JsonObject(); value.add("world", state.get("world")); value.add("session", state.get("session"));
        value.add("revision", state.get("revision")); value.addProperty("action", action); return value;
    }
    private static void send(JsonObject value) { NativeNetwork.CHANNEL.sendToServer(new DuelNetwork.Request(value.toString())); }
    private static String itemName(String id) { ItemStack item = NativeDuel.stack(id); return item == null ? "无" : item.getDisplayName() + (item.getItem() instanceof net.minecraft.item.ItemFood ? " ×16" : ""); }
    @SubscribeEvent public void overlay(RenderGameOverlayEvent.Post event) {
        if (event.type != RenderGameOverlayEvent.ElementType.ALL || state == null) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.currentScreen instanceof LoadoutScreen || mc.currentScreen instanceof ItemScreen) return;
        int x = event.resolution.getScaledWidth() - 164, y = 44;
        Gui.drawRect(x - 6, y - 6, x + 158, y + 103, 0xa0101418);
        String[] lines = {"PvP 训练场 · " + phase(), "本局剩余 " + state.get("remaining").getAsInt() + " 秒",
                "总局数 " + state.get("rounds").getAsInt() + "  胜率 " + String.format(Locale.ROOT, "%.1f%%", state.get("winRate").getAsDouble()),
                "胜 " + state.get("wins").getAsInt() + " 负 " + state.get("losses").getAsInt() + " 平 " + state.get("draws").getAsInt(),
                "平均击杀 " + seconds("averageKill"), "平均被击杀 " + seconds("averageDeath"), menuHint()};
        if(state.has("trainingActive")&&state.get("trainingActive").getAsBoolean()){
            JsonObject training=state.getAsJsonObject("training");
            lines=new String[]{I18n.format("gui.divzero.training.hud"),I18n.format("gui.divzero.training.phase."+training.get("phase").getAsString()),
                    I18n.format("gui.divzero.training.rounds",training.get("trainingRounds").getAsInt(),training.get("plannedTrainingRounds").getAsInt()),
                    I18n.format("gui.divzero.training.evaluation",training.get("evaluationRounds").getAsInt()),
                    I18n.format("gui.divzero.training.samples",training.get("samples").getAsInt()),"v"+training.get("baseVersion").getAsLong()+" → v"+training.get("candidateVersion").getAsLong(),menuHint()};
        }
        for (String line : lines) { mc.fontRendererObj.drawStringWithShadow(line, x, y, 0xffffff); y += 14; }
    }
    private static String seconds(String key) { double value = state.get(key).getAsDouble(); return value < 0 ? "—" : String.format(Locale.ROOT, "%.1f 秒", value); }
    private static String phase() {
        String phase = state.get("phase").getAsString();
        return phase.equals("READY") ? "候战" : phase.equals("CLEANING") ? "清理场地" : phase.equals("COUNTDOWN") ? "倒计时" : "对战中";
    }
    public static final class LoadoutScreen extends GuiScreen {
        private long revision = Long.MIN_VALUE;
        private boolean enabled;
        private boolean trainingActive;
        private int left, top, columnWidth, slotGap, woolY, footerY;
        @Override public void initGui() { revision = Long.MIN_VALUE; refresh(); }
        public void refresh() {
            if (state == null || revision == state.get("revision").getAsLong() && enabled == state.get("enabled").getAsBoolean() && trainingActive == state.get("trainingActive").getAsBoolean()) return;
            revision = state.get("revision").getAsLong(); enabled = state.get("enabled").getAsBoolean(); buttonList.clear();
            trainingActive=state.get("trainingActive").getAsBoolean();
            int panelWidth = Math.min(490, width - 20); columnWidth = (panelWidth - 12) / 2; left = (width - panelWidth) / 2;
            slotGap = height < 280 ? 20 : 24; top = Math.max(2, (height - (92 + slotGap * 7)) / 2); woolY = top + 32 + slotGap * 7; footerY = woolY + 34;
            boolean training = trainingActive;
            boolean ready = state.get("phase").getAsString().equals("READY") && !training;
            for (int actor = 0; actor < 2; actor++) for (int slot = 0; slot < 7; slot++) {
                String id = state.getAsJsonArray(actor == 0 ? "human" : "ai").get(slot).getAsString();
                GuiButton button = new ItemButton(actor * 10 + slot, left + actor * (columnWidth + 12), top + 29 + slot * slotGap, columnWidth,
                        fontRendererObj.trimStringToWidth(slotName(slot) + " · " + itemName(id), columnWidth - 30), NativeDuel.stack(id));
                button.enabled = ready; buttonList.add(button);
            }
            for (int actor = 0; actor < 2; actor++) {
                GuiButton button = new GuiButton(20 + actor, left + actor * (columnWidth + 12), woolY, columnWidth, 20,
                        "领取羊毛 ×64：" + (state.get(actor == 0 ? "humanWool" : "aiWool").getAsBoolean() ? "开" : "关"));
                button.enabled = ready; buttonList.add(button);
            }
            int startWidth = (panelWidth - 18) / 4, stopWidth = startWidth;
            GuiButton start = new GuiButton(30, left, footerY, startWidth, 20, I18n.format("gui.divzero.duel.start")); start.enabled = ready; buttonList.add(start);
            GuiButton stop = new GuiButton(31, left + startWidth + 6, footerY, stopWidth, 20, "停止本局"); stop.enabled = !ready && !training; buttonList.add(stop);
            GuiButton train = new GuiButton(33, left + 2 * (startWidth + 6), footerY, startWidth, 20, I18n.format(training ? "gui.divzero.training.stop" : "gui.divzero.training.start"));train.enabled=ready||training;buttonList.add(train);
            buttonList.add(new GuiButton(32, left + 3 * (startWidth + 6), footerY, panelWidth - 3 * (startWidth + 6), 20, "返回游戏"));
        }
        @Override protected void actionPerformed(GuiButton button) {
            if (button.id < 20) { mc.displayGuiScreen(new ItemScreen(this, button.id / 10, button.id % 10)); return; }
            if (button.id == 32) { mc.displayGuiScreen(null); return; }
            if (button.id == 33) { send(request(state.get("trainingActive").getAsBoolean() ? "train_stop" : "train_start")); return; }
            if (button.id == 30 || button.id == 31) { send(request(button.id == 30 ? "ready" : "stop")); mc.displayGuiScreen(null); return; }
            int actor = button.id - 20; JsonObject value = request("choose"); value.addProperty("actor", actor == 0 ? "human" : "ai");
            value.addProperty("wool", !state.get(actor == 0 ? "humanWool" : "aiWool").getAsBoolean()); send(value);
        }
        @Override public void drawScreen(int mouseX, int mouseY, float partialTicks) {
            drawDefaultBackground(); drawCenteredString(fontRendererObj, "PvP 训练场 · 双方装备", width / 2, top + 3, 0xffffff);
            drawString(fontRendererObj, "玩家", left, top + 17, 0xe2e2e2); drawString(fontRendererObj, "AI", left + columnWidth + 12, top + 17, 0xe2e2e2);
            drawCenteredString(fontRendererObj, I18n.format(enabled ? "gui.divzero.duel.rules" : "gui.divzero.duel.enableStart"), width / 2, woolY + 23, 0xc3c3c3);
            super.drawScreen(mouseX, mouseY, partialTicks);
        }
        @Override public boolean doesGuiPauseGame() { return false; }
        public boolean fixtureLayoutFits() {
            if (buttonList.size() != 20) return false;
            for (GuiButton button : buttonList) if (button.xPosition < 0 || button.yPosition < 0 || button.xPosition + button.getButtonWidth() > width || button.yPosition + 20 > height) return false;
            for(int i=0;i<buttonList.size();i++)for(int j=i+1;j<buttonList.size();j++){GuiButton a=buttonList.get(i),b=buttonList.get(j);if(a.xPosition<b.xPosition+b.getButtonWidth()&&a.xPosition+a.getButtonWidth()>b.xPosition&&a.yPosition<b.yPosition+20&&a.yPosition+20>b.yPosition)return false;}
            return true;
        }
        public void fixtureSelect(int id) throws IOException { for (GuiButton button : buttonList) if (button.id == id && button.enabled) { int x = button.xPosition + button.getButtonWidth() / 2, y = button.yPosition + 10; mouseClicked(x, y, 0); mouseReleased(x, y, 0); return; } throw new IllegalArgumentException("FIXTURE_BUTTON_MISSING"); }
    }
    private static final class ItemScreen extends GuiScreen {
        final LoadoutScreen parent; final int actor, slot; final List<String> choices; int page;
        ItemScreen(LoadoutScreen parent, int actor, int slot) { this.parent = parent; this.actor = actor; this.slot = slot; choices = NativeDuel.choices(slot); }
        @Override public void initGui() {
            buttonList.clear(); int w = Math.min(340, width - 20), x = (width - w) / 2, y = Math.max(30, (height - 216) / 2);
            for (int index = page * 12; index < Math.min(choices.size(), page * 12 + 12); index++) {
                String id = choices.get(index); int local = index - page * 12;
                buttonList.add(new ItemButton(index, x + local % 2 * (w / 2 + 2), y + local / 2 * 28, w / 2 - 2,
                        fontRendererObj.trimStringToWidth(itemName(id), w / 2 - 30), NativeDuel.stack(id)));
            }
            buttonList.add(new GuiButton(1000, width / 2 - 60, y + 178, 120, 20, "返回"));
        }
        @Override protected void actionPerformed(GuiButton button) {
            if (button.id != 1000) { JsonObject value = request("choose"); value.addProperty("actor", actor == 0 ? "human" : "ai"); value.addProperty("slot", slot); value.addProperty("item", choices.get(button.id)); send(value); }
            mc.displayGuiScreen(parent);
        }
        @Override public void handleMouseInput() throws IOException { super.handleMouseInput(); int wheel = Mouse.getEventDWheel(); if (wheel != 0) { page = Math.max(0, Math.min((choices.size() - 1) / 12, page + (wheel < 0 ? 1 : -1))); initGui(); } }
        @Override public void drawScreen(int mouseX, int mouseY, float ticks) { drawDefaultBackground(); drawCenteredString(fontRendererObj, (actor == 0 ? "玩家 · " : "AI · ") + slotName(slot), width / 2, 12, 0xffffff); super.drawScreen(mouseX, mouseY, ticks); }
        @Override public boolean doesGuiPauseGame() { return false; }
        private void fixtureSelect(int id) throws IOException { for (GuiButton button : buttonList) if (button.id == id) { int x = button.xPosition + button.getButtonWidth() / 2, y = button.yPosition + 10; mouseClicked(x, y, 0); mouseReleased(x, y, 0); return; } throw new IllegalArgumentException("FIXTURE_ITEM_BUTTON"); }
    }
    private static final class ItemButton extends GuiButton {
        final ItemStack stack;
        ItemButton(int id, int x, int y, int width, String label, ItemStack stack) { super(id, x, y, width, 20, label); this.stack = stack; }
        @Override public void drawButton(Minecraft mc, int mouseX, int mouseY) {
            String label = displayString; displayString = ""; super.drawButton(mc, mouseX, mouseY); displayString = label;
            if (visible && stack != null) { net.minecraft.client.renderer.RenderHelper.enableGUIStandardItemLighting(); mc.getRenderItem().renderItemAndEffectIntoGUI(stack, xPosition + 3, yPosition + 2); net.minecraft.client.renderer.RenderHelper.disableStandardItemLighting(); }
            if (visible) mc.fontRendererObj.drawStringWithShadow(label, xPosition + 24, yPosition + 6, enabled ? 0xffffff : 0xa0a0a0);
        }
    }
}
