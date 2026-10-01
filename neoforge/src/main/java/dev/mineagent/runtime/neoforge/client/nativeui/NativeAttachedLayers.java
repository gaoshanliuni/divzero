package dev.mineagent.runtime.neoforge.client.nativeui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import com.lowdragmc.lowdraglib2.gui.holder.IModularUIHolder;
import com.lowdragmc.lowdraglib2.gui.ui.*;
import com.lowdragmc.lowdraglib2.gui.ui.style.Stylesheet;
import com.lowdragmc.lowdraglib2.gui.ui.elements.*;
import dev.mineagent.runtime.core.ui.dynamic.*;
import dev.mineagent.runtime.neoforge.mixin.client.ContainerScreenGeometry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import java.util.*;

/** Native screen attachments and entity-anchored LDLib2 trees share the managed definition lifecycle. */
@EventBusSubscriber(modid="mineagent_runtime",value=Dist.CLIENT)
public final class NativeAttachedLayers {
    private static final Map<InterfaceSession.Scope,Entry> ENTRIES=new LinkedHashMap<>();
    private static Object connection,level;private static Entry focused,pressed;
    private static final class Entry {
        final InterfaceSession<LdInterfaceRenderer.Rendered> session;
        final Map<UUID,LdInterfaceRenderer.Rendered> entities=new HashMap<>();
        Screen host;UIElement styledRoot;Stylesheet hostStyle;NativeImeSupport ime;String error="";int painted;
        Entry(InterfaceSession<LdInterfaceRenderer.Rendered> session){this.session=session;}
        void detach(){if(styledRoot!=null&&hostStyle!=null)styledRoot.removeLocalStylesheet(hostStyle);styledRoot=null;hostStyle=null;host=null;if(ime!=null)ime.release();ime=null;if(focused==this)focused=null;if(pressed==this)pressed=null;if(session.rendered()!=null)session.interactive(false);}
        void close(){detach();entities.values().forEach(LdInterfaceRenderer.Rendered::close);entities.clear();}
    }
    private static Minecraft mc(){return Minecraft.getInstance();}
    private static void resize(ModularUI ui,int width,int height){ui.setTickWhileRending(true);if(ui.getScreenWidth()!=width||ui.getScreenHeight()!=height)ui.init(width,height);}
    public static boolean attached(InterfaceDefinition definition){return definition.surface()==InterfaceDefinition.Surface.SCREEN_OVERLAY||definition.surface()==InterfaceDefinition.Surface.ENTITY_HUD;}
    public static void attach(InterfaceSession<LdInterfaceRenderer.Rendered> session){maintain();var previous=ENTRIES.put(session.scope(),new Entry(session));if(previous!=null)previous.close();tick();}
    public static void clear(){for(var entry:ENTRIES.values())entry.close();ENTRIES.clear();focused=pressed=null;}
    private static void maintain(){if(connection!=mc().getConnection()||level!=mc().level){clear();connection=mc().getConnection();level=mc().level;}}
    public static boolean interacting(InterfaceSession<LdInterfaceRenderer.Rendered> session){var e=ENTRIES.get(session.scope());return e!=null&&e.host!=null&&e.host==mc().screen&&session.visible()&&matches(session.definition(),e.host);}
    public static Map<String,Object> observation(InterfaceSession<LdInterfaceRenderer.Rendered> session){var e=ENTRIES.get(session.scope());return e==null?Map.of():Map.of("attached",e.host!=null,"screenClass",e.host==null?"":e.host.getClass().getName(),"entityCopies",e.entities.size(),"entityBounds",e.entities.entrySet().stream().limit(32).map(view->Map.of("id",view.getKey().toString(),"width",view.getValue().root.getSizeWidth(),"height",view.getValue().root.getSizeHeight())).toList(),"painted",e.painted,"error",e.error);}
    private static ModularUI hostUi(Screen screen){return screen instanceof com.lowdragmc.lowdraglib2.gui.holder.ModularUIScreen modular?modular.getModularUI():screen instanceof IModularUIHolder holder?holder.getModularUI():null;}
    public static Map<String,Object> screenInfo(){
        var screen=mc().screen;if(screen==null)return Map.of("open",false);
        var out=new LinkedHashMap<String,Object>();out.put("open",true);out.put("screenClass",screen.getClass().getName());out.put("title",screen.getTitle().getString());out.put("width",screen.width);out.put("height",screen.height);out.put("ldlib2",hostUi(screen)!=null);
        if(screen instanceof ContainerScreenGeometry geo)out.put("menuBounds",List.of(geo.divzero$left(),geo.divzero$top(),geo.divzero$width(),geo.divzero$height()));
        if(screen instanceof AbstractContainerScreen<?> container)try{out.put("menu",net.minecraft.core.registries.BuiltInRegistries.MENU.getKey(container.getMenu().getType()).toString());}catch(Exception absent){}
        if(hostUi(screen)!=null)out.put("widgets",hostUi(screen).ui.rootElement.selfAndAllChildren().limit(128).map(node->Map.of("id",node.getId(),"classes",List.copyOf(node.getClasses()),"type",node.getClass().getSimpleName())).toList());
        return out;
    }
    static boolean matches(InterfaceDefinition definition,Screen screen){
        if(screen==null||screen instanceof NativeInputScreen||screen instanceof net.minecraft.client.gui.screens.ChatScreen)return false;
        var target=definition.attachment();if(!target.screenTitle().isEmpty()&&!target.screenTitle().equals(screen.getTitle().getString()))return false;if(!target.screenClass().isEmpty()&&!target.screenClass().equals(screen.getClass().getName()))return false;
        if(!target.menu().isEmpty()){
            if(!(screen instanceof AbstractContainerScreen<?> container))return false;var menu=container.getMenu();
            if(target.menu().equals("furnace"))return menu instanceof net.minecraft.world.inventory.AbstractFurnaceMenu;
            try{if(!target.menu().equals(net.minecraft.core.registries.BuiltInRegistries.MENU.getKey(menu.getType()).toString()))return false;}catch(Exception absent){return false;}
        }
        return true;
    }
    public static void tick(){
        maintain();var iterator=ENTRIES.values().iterator();while(iterator.hasNext()){
            var e=iterator.next();if(e.session.rendered()==null){e.close();iterator.remove();continue;}
            if(e.session.definition().surface()!=InterfaceDefinition.Surface.SCREEN_OVERLAY)continue;
            var target=e.session.visible()&&matches(e.session.definition(),mc().screen)?mc().screen:null;
            if(target!=e.host){e.detach();if(target!=null){e.host=target;var ui=e.session.rendered().ui;ModularUIClientAccess.setScreenAndInit(ui,target);e.ime=new NativeImeSupport(target,()->ui);e.error="";
                String lss=e.session.definition().attachment().hostStylesheet();if(!lss.isBlank()){
                    if(hostUi(target)!=null){e.styledRoot=hostUi(target).ui.rootElement;e.hostStyle=LdInterfaceRenderer.strictStyles(lss);e.styledRoot.addLocalStylesheet(e.hostStyle);}
                    else e.error="NATIVE_HOST_STYLES_REQUIRE_LDLIB2";
                }
            }}
            e.session.interactive(target!=null);if(e.ime!=null&&focused==e)e.ime.update();
        }
    }
    private static List<Entry> overlays(Screen screen){return ENTRIES.values().stream().filter(e->e.host==screen&&screen!=null&&e.session.visible()).sorted(Comparator.comparingInt(e->e.session.definition().order())).toList();}
    private static void place(Entry e){
        var rendered=e.session.rendered();var target=e.session.definition().attachment();var screen=e.host;
        float x=0,y=0,w=screen.width,h=screen.height;
        if(screen instanceof ContainerScreenGeometry geometry){x=geometry.divzero$left();y=geometry.divzero$top();w=geometry.divzero$width();h=geometry.divzero$height();}
        resize(rendered.ui,screen.width,screen.height);float rw=rendered.root.getSizeWidth(),rh=rendered.root.getSizeHeight();
        switch(target.anchor()){case "top"->{x+=(w-rw)/2;y-=rh;}case "bottom"->{x+=(w-rw)/2;y+=h;}case "left"->{x-=rw;y+=(h-rh)/2;}case "right"->{x+=w;y+=(h-rh)/2;}case "center"->{x+=(w-rw)/2;y+=(h-rh)/2;}default->{x=0;y=0;}}
        rendered.root.getLayout().left(x+target.x()).top(y+target.y());
    }
    @SubscribeEvent public static void render(ScreenEvent.Render.Post event){tick();for(var e:overlays(event.getScreen())){try{place(e);var widget=ModularUIClientAccess.getWidget(e.session.rendered().ui);widget.extractRenderState(event.getGuiGraphics(),event.getMouseX(),event.getMouseY(),event.getPartialTick());e.painted++;if(focused==e&&e.ime!=null)e.ime.render(event.getGuiGraphics(),event.getMouseX(),event.getMouseY(),event.getPartialTick());}catch(Exception error){e.error=Objects.toString(error.getMessage(),"NATIVE_ATTACHMENT_RENDER_FAILED");}}}
    private static Entry hit(Screen screen,double x,double y){
        for(var e:overlays(screen).reversed()){
            var ui=e.session.rendered().ui;ui.refreshHoveredElementAtScreen((float)x,(float)y);
            for(var node=ui.getLastHoveredElement();node!=null;node=node.getParent()){
                if(node==e.session.rendered().root.getParent())break;
                if(node instanceof Button||node instanceof TextField||node instanceof TextArea||node instanceof Toggle||node instanceof Selector<?>||node instanceof ScrollerView||!node.getBubbleListeners(com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents.MOUSE_DOWN).isEmpty())return e;
            }
        }return null;
    }
    @SubscribeEvent public static void press(ScreenEvent.MouseButtonPressed.Pre event){tick();var e=hit(event.getScreen(),event.getMouseX(),event.getMouseY());if(focused!=null&&focused!=e){focused.session.rendered().ui.clearFocus();if(focused.ime!=null)focused.ime.release();focused=null;}if(e!=null&&ModularUIClientAccess.getWidget(e.session.rendered().ui).mouseClicked(event.getMouseButtonEvent(),event.isDoubleClick())){focused=pressed=e;event.setCanceled(true);}}
    @SubscribeEvent public static void release(ScreenEvent.MouseButtonReleased.Pre event){var e=pressed;if(e==null||e.host!=event.getScreen())return;pressed=null;var ui=e.session.rendered().ui;ui.refreshHoveredElementAtScreen((float)event.getMouseX(),(float)event.getMouseY());var widget=ModularUIClientAccess.getWidget(ui);if(event.getScreen() instanceof com.lowdragmc.lowdraglib2.gui.holder.IAbstractContainerScreenExt container)((dev.mineagent.runtime.neoforge.mixin.client.AttachedWidgetReleaseAccess)(Object)widget).divzero$releaseMark(container.getLdlib2$mouseReleasedMark()-1);widget.mouseReleased(event.getMouseButtonEvent());event.setCanceled(true);}
    @SubscribeEvent public static void drag(ScreenEvent.MouseDragged.Pre event){var e=pressed;if(e!=null&&e.host==event.getScreen()){var ui=e.session.rendered().ui;ui.refreshHoveredElementAtScreen((float)event.getMouseX(),(float)event.getMouseY());ModularUIClientAccess.getWidget(ui).mouseDragged(event.getMouseButtonEvent(),event.getDragX(),event.getDragY());event.setCanceled(true);}}
    @SubscribeEvent public static void scroll(ScreenEvent.MouseScrolled.Pre event){var e=hit(event.getScreen(),event.getMouseX(),event.getMouseY());if(e!=null&&ModularUIClientAccess.getWidget(e.session.rendered().ui).mouseScrolled(event.getMouseX(),event.getMouseY(),event.getScrollDeltaX(),event.getScrollDeltaY()))event.setCanceled(true);}
    @SubscribeEvent public static void key(ScreenEvent.KeyPressed.Pre event){var e=focused;if(e!=null&&e.host==event.getScreen()&&(e.ime.consume(event.getKeyEvent())||e.ime.shortcut(event.getKeyEvent())||ModularUIClientAccess.getWidget(e.session.rendered().ui).keyPressed(event.getKeyEvent())))event.setCanceled(true);}
    @SubscribeEvent public static void keyUp(ScreenEvent.KeyReleased.Pre event){var e=focused;if(e!=null&&e.host==event.getScreen()&&ModularUIClientAccess.getWidget(e.session.rendered().ui).keyReleased(event.getKeyEvent()))event.setCanceled(true);}
    @SubscribeEvent public static void text(ScreenEvent.CharacterTyped.Pre event){var e=focused;if(e!=null&&e.host==event.getScreen()&&ModularUIClientAccess.getWidget(e.session.rendered().ui).charTyped(event.getCharacterEvent()))event.setCanceled(true);}
    @SubscribeEvent public static void preedit(ScreenEvent.Preedit.Pre event){var e=focused;if(e!=null&&e.host==event.getScreen()&&e.ime.preedit(event.getPreeditEvent()))event.setCanceled(true);}

    public static void entityHud(net.minecraft.client.gui.GuiGraphicsExtractor graphics,net.minecraft.client.DeltaTracker delta){
        tick();if(mc().level==null||mc().player==null||mc().options.hideGui)return;
        var camera=mc().gameRenderer.getMainCamera();var cameraPos=camera.position();var matrix=camera.getViewRotationProjectionMatrix(new Matrix4f());int width=mc().getWindow().getGuiScaledWidth(),height=mc().getWindow().getGuiScaledHeight();
        for(var e:ENTRIES.values()){
            if(e.session.definition().surface()!=InterfaceDefinition.Surface.ENTITY_HUD)continue;
            var kept=new HashSet<UUID>();try{if(e.session.visible())for(var entity:mc().level.entitiesForRendering()){
                if(!(entity instanceof LivingEntity living)||!living.isAlive()||entity.isInvisibleTo(mc().player)||entity==mc().getCameraEntity()&&mc().options.getCameraType().isFirstPerson())continue;
                var head=entity.position().add(0,entity.getBbHeight()+.3,0);double distance=head.distanceTo(cameraPos);if(distance>e.session.definition().attachment().range())continue;
                if(!e.session.definition().attachment().throughWalls()&&mc().level.clip(new net.minecraft.world.level.ClipContext(cameraPos,head,net.minecraft.world.level.ClipContext.Block.COLLIDER,net.minecraft.world.level.ClipContext.Fluid.NONE,mc().player)).getType()!=net.minecraft.world.phys.HitResult.Type.MISS)continue;
                var projected=matrix.transform(new Vector4f((float)(head.x-cameraPos.x),(float)(head.y-cameraPos.y),(float)(head.z-cameraPos.z),1));if(!Float.isFinite(projected.w)||projected.w<=0)continue;projected.div(projected.w);if(!Float.isFinite(projected.x)||!Float.isFinite(projected.y)||!Float.isFinite(projected.z))continue;if(Math.abs(projected.x)>1||Math.abs(projected.y)>1||projected.z< -1||projected.z>1)continue;
                var data=new LinkedHashMap<String,JsonNode>(e.session.data());data.put("entity",JsonNodeFactory.instance.objectNode().put("id",entity.getUUID().toString()).put("name",entity.getName().getString()).put("health",living.getHealth()).put("maxHealth",living.getMaxHealth()).put("distance",distance));
                var view=e.entities.get(entity.getUUID());if(view==null){view=KubeInterfaceRenderer.build(e.session.definition(),data,(node,event,value)->{});e.entities.put(entity.getUUID(),view);}else view.update(data);kept.add(entity.getUUID());
                resize(view.ui,width,height);view.root.getLayout().left((projected.x*.5f+.5f)*width-view.root.getSizeWidth()/2).top((.5f-projected.y*.5f)*height-view.root.getSizeHeight());
                ModularUIClientAccess.getWidget(view.ui).extractRenderState(graphics,Integer.MIN_VALUE,Integer.MIN_VALUE,delta.getGameTimeDeltaPartialTick(false));e.painted++;
            }}catch(Exception error){e.error=Objects.toString(error.getMessage(),"NATIVE_ENTITY_HUD_FAILED");}
            var it=e.entities.entrySet().iterator();while(it.hasNext()){var entry=it.next();if(!kept.contains(entry.getKey())){entry.getValue().close();it.remove();}}
        }
    }
    static Map<String,Object> smoke(String id){
        if(!Boolean.getBoolean("mineagent.nativeTenSmoke"))throw new IllegalStateException("SMOKE_DISABLED");var entry=ENTRIES.values().stream().filter(e->e.session.scope().view().equals(id)).findFirst().orElseThrow();var out=new LinkedHashMap<String,Object>(observation(entry.session));
        out.put("entities",entry.entities.entrySet().stream().map(e->Map.of("id",e.getKey().toString(),"x",e.getValue().root.getPositionX(),"y",e.getValue().root.getPositionY(),"texts",e.getValue().root.selfAndAllChildren().filter(TextElement.class::isInstance).map(TextElement.class::cast).map(text->text.getText().getString()).toList())).toList());return out;
    }
    private NativeAttachedLayers(){}
}
