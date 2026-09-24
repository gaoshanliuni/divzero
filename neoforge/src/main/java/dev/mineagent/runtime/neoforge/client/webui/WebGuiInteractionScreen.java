package dev.mineagent.runtime.neoforge.client.webui;

import com.cinemamod.mcef.MCEFBrowser;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.PreeditEvent;
import net.minecraft.client.gui.components.IMEPreeditOverlay;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import java.util.HashSet;
import java.util.Set;

/** Input capture only: owns neither the browser nor any package/window/world state. */
public final class WebGuiInteractionScreen extends Screen {
    private final Set<Integer> pressed = new HashSet<>();
    private final Set<Integer> buttons = new HashSet<>();
    private dev.mineagent.runtime.client.webui.NativeAtlasInputMap.Point atlasGesture;private boolean popupButtonHeld;
    private IMEPreeditOverlay preedit;
    private int imeX,imeY;
    private long imeEndedAt;
    public boolean nativeComposing(){return preedit!=null;}
    private int pixelWidth = -1;
    private int pixelHeight = -1;
    public WebGuiInteractionScreen() { super(Component.literal("MineAgent WebGUI")); }
    private MCEFBrowser browser() { return WebGuiHostAdapter.INSTANCE.browser(); }
    private int x(double x) { return (int) Math.round(x * Minecraft.getInstance().getWindow().getWidth() / Math.max(1, width)); }
    private int y(double y) { return (int) Math.round(y * Minecraft.getInstance().getWindow().getHeight() / Math.max(1, height)); }
    @Override protected void init() { KeyMapping.releaseAll(); WebGuiHostAdapter.INSTANCE.interactionMode(true); if (browser() != null) browser().setFocus(true);imeX=width/2;imeY=Math.max(0,height-30);Minecraft.getInstance().onTextInputFocusChange(this,true);Minecraft.getInstance().textInputManager().setTextInputArea(imeX,imeY,imeX+1,imeY+10); }
    @Override public boolean isPauseScreen() { return false; }
    @Override public boolean shouldCloseOnEsc() { return false; }
    @Override public void extractBackground(GuiGraphicsExtractor graphics, int x, int y, float delta) {}
    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int x, int y, float delta) {
        MCEFBrowser browser = browser();
        // In-world texture rendering belongs to WebGUI's HUD; title/menu rendering needs this one draw.
        if (Minecraft.getInstance().level == null && browser != null && browser.isTextureReady()) {
            if(WebGuiAtlasCompositor.enabled()){WebGuiAtlasCompositor.render(graphics,RenderPipelines.GUI_TEXTURED,browser.getTextureIdentifier(),width,height);}else{
            int w = Minecraft.getInstance().getWindow().getWidth(), h = Minecraft.getInstance().getWindow().getHeight();
            if (w != pixelWidth || h != pixelHeight) { browser.resize(w, h); pixelWidth = w; pixelHeight = h; }
            graphics.blit(RenderPipelines.GUI_TEXTURED, browser.getTextureIdentifier(), 0, 0, 0f, 0f, width, height, width, height,dev.mineagent.runtime.client.webui.WebGuiTheme.MANAGED_TINT);
            }
        }
        if(preedit!=null){preedit.updateInputPosition(imeX,imeY);preedit.extractRenderState(graphics,x,y,delta);}
    }
    @Override public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        if(NativeAtlasInputSmokeClient.blocksExternalCallbacks())return true;
        imeX=(int)Math.clamp(event.x(),0,width);imeY=(int)Math.clamp(event.y(),0,Math.max(0,height-12));Minecraft.getInstance().textInputManager().setTextInputArea(imeX,imeY,imeX+1,imeY+10);
        int px=x(event.x()),py=y(event.y());
        if(WebGuiPopupCompositor.pointer(px,py,event.button(),"press",0)){popupButtonHeld=true;return true;}
        if(WebGuiAtlasCompositor.active()){
            if(buttons.isEmpty()&&WebGuiAtlasCompositor.worldAt(px,py)){onClose();return false;}
            WebGuiNativeInput.mapped(px,py,atlasGesture,"pointer",r->guarded(()->{buttons.add(event.button());if(atlasGesture==null)atlasGesture=r.point();browser().setFocus(true);WebGuiPopupCompositor.notePointer(r);browser().sendMousePress(r.x(),r.y(),event.button());}).run());return true;
        }
        WebGuiNativeInput.pointer(px,py,guarded(()->{buttons.add(event.button());browser().sendMousePress(px,py,event.button());}));
        return true;
    }
    @Override public boolean mouseReleased(MouseButtonEvent event) {
        if(NativeAtlasInputSmokeClient.blocksExternalCallbacks())return true;
        int px=x(event.x()),py=y(event.y());
        if(WebGuiPopupCompositor.pointer(px,py,event.button(),"release",0)){buttons.remove(event.button());atlasGesture=null;popupButtonHeld=false;WebGuiPopupCompositor.baseRelease();return true;}
        if(popupButtonHeld){popupButtonHeld=false;buttons.remove(event.button());atlasGesture=null;if(browser()!=null)browser().sendMouseRelease(0,0,event.button());return true;}
        WebGuiPopupCompositor.baseRelease();
        if(WebGuiAtlasCompositor.active()){
            WebGuiNativeInput.mapped(px,py,atlasGesture,"release",r->guarded(()->{if(buttons.remove(event.button()))browser().sendMouseRelease(r.x(),r.y(),event.button());if(buttons.isEmpty())atlasGesture=null;}).run());return true;
        }
        WebGuiNativeInput.passthrough(guarded(()->{if(buttons.remove(event.button()))browser().sendMouseRelease(px,py,event.button());}));return true;
    }
    @Override public void mouseMoved(double x, double y) {if(NativeAtlasInputSmokeClient.blocksExternalCallbacks())return;int px=x(x),py=y(y);if(WebGuiPopupCompositor.pointer(px,py,0,"motion",0))return;if(WebGuiAtlasCompositor.active()){WebGuiNativeInput.mapped(px,py,atlasGesture,"motion",r->guarded(()->browser().sendMouseMove(r.x(),r.y())).run());return;}WebGuiNativeInput.motion(guarded(()->browser().sendMouseMove(px,py)));}
    @Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        if(NativeAtlasInputSmokeClient.blocksExternalCallbacks())return true;
        int px=x(x),py=y(y);if(WebGuiPopupCompositor.pointer(px,py,0,"wheel",vertical))return true;if(WebGuiAtlasCompositor.active()){WebGuiNativeInput.mapped(px,py,null,"pointer",r->guarded(()->browser().sendMouseWheel(r.x(),r.y(),vertical,0)).run());return true;}WebGuiNativeInput.pointer(px,py,guarded(()->browser().sendMouseWheel(px,py,vertical,0)));
        return true;
    }
    @Override public boolean keyPressed(KeyEvent event) {
        int emergency = org.lwjgl.glfw.GLFW.GLFW_MOD_CONTROL | org.lwjgl.glfw.GLFW.GLFW_MOD_SHIFT;
        if (event.key() == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE
                && (!WebGuiHostAdapter.INSTANCE.ready() || (event.modifiers() & emergency) == emergency)) {
            UiAgentClient.interruptAll();WebGuiNativeInput.cancelNative();onClose(); return true;
        }
        // Candidate selection/editing belongs to GLFW's IME, not Chromium shortcuts or the existing text.
        if(preedit!=null||(imeEndedAt!=0&&System.nanoTime()-imeEndedAt<150_000_000L&&(event.key()==org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE||event.key()==org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER)))return true;
        if(NativeAtlasInputSmokeClient.blocksExternalCallbacks())return true;
        if(WebGuiPopupCompositor.keyboard(guarded(()->{pressed.add(event.key());browser().sendKeyPress(event.key(),event.scancode(),event.modifiers());})))return true;
        WebGuiNativeInput.keyboard(guarded(()->{pressed.add(event.key());browser().sendKeyPress(event.key(),event.scancode(),event.modifiers());}));
        if(event.key()==org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE)WebGuiHostAdapter.INSTANCE.emit("nativeEscape",java.util.Map.of());
        return true;
    }
    @Override public boolean keyReleased(KeyEvent event) {
        if(NativeAtlasInputSmokeClient.blocksExternalCallbacks())return true;
        if(WebGuiPopupCompositor.keyboard(guarded(()->{if(pressed.remove(event.key()))browser().sendKeyRelease(event.key(),event.scancode(),event.modifiers());})))return true;
        WebGuiNativeInput.passthrough(guarded(()->{if(pressed.remove(event.key()))browser().sendKeyRelease(event.key(),event.scancode(),event.modifiers());}));return true;
    }
    @Override public boolean charTyped(CharacterEvent event) {
        if(NativeAtlasInputSmokeClient.blocksExternalCallbacks())return true;
        if(event.isAllowedChatCharacter()&&WebGuiPopupCompositor.keyboard(guarded(()->{for(char unit:Character.toChars(event.codepoint()))browser().sendKeyTyped(unit,0);})))return true;
        if(event.isAllowedChatCharacter())WebGuiNativeInput.keyboard(guarded(()->{for(char unit:Character.toChars(event.codepoint()))browser().sendKeyTyped(unit,0);}));
        return true;
    }
    @Override public boolean preeditUpdated(PreeditEvent event) {
        boolean active=event!=null&&!event.fullText().isEmpty();
        if(!active&&preedit!=null)imeEndedAt=System.nanoTime();
        preedit=active?new IMEPreeditOverlay(event,font,10):null;
        WebGuiHostAdapter.INSTANCE.emit("nativeCompositionState",java.util.Map.of("active",active));
        return true;
    }
    @Override public void removed() {
        Minecraft.getInstance().onTextInputFocusChange(this,false);preedit=null;
        WebGuiHostAdapter.INSTANCE.interactionMode(false);
        WebGuiNativeInput.cancelNative();releasePressed();if(browser()!=null)browser().setFocus(false);
    }
    public void releasePressed(){
        if (browser() != null) {
            for (int key : pressed) browser().sendKeyRelease(key, 0, 0);
            for (int button : buttons) browser().sendMouseRelease(0, 0, button);
        }
        pressed.clear(); buttons.clear();atlasGesture=null;popupButtonHeld=false; KeyMapping.releaseAll();
    }
    private Runnable guarded(Runnable action){var target=browser();return ()->{if(target!=null&&browser()==target&&Minecraft.getInstance().screen==this)action.run();};}
    @Override public void onClose() { Minecraft.getInstance().setScreen(null); }
}
