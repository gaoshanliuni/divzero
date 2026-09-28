package dev.mineagent.runtime.neoforge.client.nativeui;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.*;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import com.lowdragmc.lowdraglib2.gui.texture.GuiTexture;
import com.lowdragmc.lowdraglib2.gui.texture.renderstate.FloatBlitRenderState;
import com.lowdragmc.lowdraglib2.gui.ui.rendering.GUIContext;
import com.lowdragmc.lowdraglib2.client.shader.LDLibRenderPipelines;
import dev.mineagent.runtime.client.preview.NativeScene;
import dev.mineagent.runtime.neoforge.client.language.ClientLanguage;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.VertexConsumer;
import org.joml.Matrix3x2f;
import java.util.*;
import java.util.concurrent.*;

/** Native LDLib2 data-only model/structure preview with continuous mouse camera and no browser. */
public final class NativePreview {
    private static final ExecutorService COMPUTE=Executors.newVirtualThreadPerTaskExecutor();
    private static final Set<UUID> PENDING=new LinkedHashSet<>();private static Object pendingConnection,pendingLevel;
    private static NativePreview smokeLast;
    static Map<String,Object> smokeState(){if(!Boolean.getBoolean("mineagent.nativeUiSmoke"))throw new IllegalStateException("SMOKE_DISABLED");var view=smokeLast;return view==null||view.projection==null?Map.of("ready",false):Map.of("ready",true,"triangles",view.scene.triangleCount(),"zoom",view.zoom,"painted",view.paintedVersion==view.cameraVersion);}
    static void smokeZoom(){var event=com.lowdragmc.lowdraglib2.gui.ui.event.UIEvent.create(UIEvents.MOUSE_WHEEL);event.target=smokeLast.canvas;event.deltaY=2;com.lowdragmc.lowdraglib2.gui.ui.event.UIEventDispatcher.dispatchEvent(event);}
    private final WorkspaceWindow window;private final UIElement canvas=new UIElement();private final TextElement title=WorkspacePanels.text(t("加载预览…")),note=WorkspacePanels.text("");
    private final Object connection,level;private NativeScene scene;private NativeScene.Projection projection;private boolean projecting;private long cameraVersion,paintedVersion=-1;private int paintedWidth,paintedHeight;
    private double yaw=-.6,pitch=.3,zoom=1,panX,panY;private float dragX,dragY;private double dragYaw,dragPitch,dragPanX,dragPanY;private boolean pan;
    public static void open(String id){var value=UUID.fromString(id);var mc=Minecraft.getInstance();if(mc.level==null)return;NativeWorkspaceScreen.open();pendingConnection=mc.getConnection();pendingLevel=mc.level;PENDING.add(value);}
    public static void tick(){var mc=Minecraft.getInstance();if(pendingConnection!=mc.getConnection()||pendingLevel!=mc.level){PENDING.clear();return;}if(!NativeWorkspaceConnection.ready())return;for(var id:List.copyOf(PENDING)){PENDING.remove(id);new NativePreview(NativeWorkspaceScreen.previewHost(),id);}}
    private NativePreview(NativeWorkspaceScreen host,UUID id){
        var mc=Minecraft.getInstance();if(Boolean.getBoolean("mineagent.nativeUiSmoke"))smokeLast=this;connection=mc.getConnection();level=mc.level;window=host.window("preview-"+id,t("预览"),520,380);window.body.clearAllChildren();
        var tools=WorkspacePanels.row();tools.getLayout().height(25);window.body.addChild(tools);title.getLayout().flex(1);tools.addChild(title);tools.addChild(NativeUiTheme.button(t("复位"),()->{yaw=-.6;pitch=.3;zoom=1;panX=panY=0;cameraVersion++;}));
        canvas.getLayout().flex(1).widthPercent(100);canvas.getStyle().backgroundTexture(NativeUiTheme.inset()).overlayTexture(GuiTexture.of(this::paint));canvas.setOverflowVisible(false);window.body.addChild(canvas);window.body.addChild(WorkspacePanels.text(t("左键旋转，右键平移，滚轮缩放")));window.body.addChild(note);
        canvas.addEventListener(UIEvents.MOUSE_DOWN,event->{if(event.button<0||event.button>2)return;pan=event.button!=0;dragX=event.x;dragY=event.y;dragYaw=yaw;dragPitch=pitch;dragPanX=panX;dragPanY=panY;canvas.startDrag(this,null);event.stopPropagation();});
        canvas.addEventListener(UIEvents.DRAG_SOURCE_UPDATE,event->{double dx=event.x-dragX,dy=event.y-dragY;if(pan){panX=dragPanX+dx;panY=dragPanY+dy;}else{yaw=dragYaw+dx*.016;pitch=Math.clamp(dragPitch+dy*.016,-Math.PI/2+.001,Math.PI/2-.001);}cameraVersion++;event.stopPropagation();});
        canvas.addEventListener(UIEvents.MOUSE_WHEEL,event->{zoom=Math.clamp(zoom*Math.exp(event.deltaY*.12),.2,6);cameraVersion++;event.stopPropagation();});read(id,0,new StringBuilder());
    }
    private boolean current(){var mc=Minecraft.getInstance();return connection==mc.getConnection()&&level==mc.level&&!window.closed()&&window.body.getChildren().contains(canvas);}
    private void read(UUID id,int offset,StringBuilder text){WorkspacePanels.request("preview.read",Map.of("previewId",id.toString(),"offset",Integer.toString(offset))).whenComplete((receipt,error)->{
        if(!current())return;if(error!=null){WorkspacePanels.failure(note,error);return;}
        try{String chunk=receipt.values().get("chunk");int next=Integer.parseInt(receipt.values().get("nextOffset"));if(chunk==null||text.length()!=offset||text.length()+chunk.length()>8*1024*1024||next!=-1&&(chunk.isEmpty()||next!=offset+chunk.length()))throw new IllegalArgumentException("PREVIEW_CHUNK_SEQUENCE");text.append(chunk);if(next>=0){read(id,next,text);return;}
            CompletableFuture.supplyAsync(()->{try{return NativeScene.parse(text.toString());}catch(Exception invalid){throw new CompletionException(invalid);}},COMPUTE).whenComplete((value,failure)->Minecraft.getInstance().execute(()->{if(!current())return;if(failure!=null){WorkspacePanels.failure(note,failure);return;}scene=value;title.setText(Component.literal(value.title()));note.setText(Component.literal(value.detail()));cameraVersion++;}));
        }catch(Exception invalid){WorkspacePanels.failure(note,invalid);}
    });}
    private void paint(GUIContext context,float x,float y,float width,float height){
        if(scene==null||!current()||width<1||height<1)return;int w=(int)width,h=(int)height;
        if(!projecting&&(paintedVersion!=cameraVersion||paintedWidth!=w||paintedHeight!=h)){
            projecting=true;long version=cameraVersion;var camera=new NativeScene.Camera(yaw,pitch,zoom,panX,panY);var target=scene;
            CompletableFuture.supplyAsync(()->target.project(camera,w,h),COMPUTE).whenComplete((value,error)->Minecraft.getInstance().execute(()->{projecting=false;if(!current())return;if(error!=null){WorkspacePanels.failure(note,error);return;}projection=value;paintedVersion=version;paintedWidth=w;paintedHeight=h;}));
        }
        if(projection==null)return;context.enableScissor(x,y,width,height);try{var pose=new Matrix3x2f(context.currentPose());var clip=context.peekScissor();context.addGuiElement(new MeshState(pose,projection,x,y,clip,FloatBlitRenderState.getBounds(x,y,x+width,y+height,pose,clip)));}finally{context.disableScissor();}
    }
    private record MeshState(Matrix3x2f pose,NativeScene.Projection mesh,float x,float y,ScreenRectangle scissorArea,ScreenRectangle bounds) implements GuiElementRenderState {
        @Override public RenderPipeline pipeline(){return LDLibRenderPipelines.GUI_TRIANGLE;}
        @Override public TextureSetup textureSetup(){return TextureSetup.noTexture();}
        @Override public void buildVertices(VertexConsumer vertices){var xy=mesh.xy();var colors=mesh.colors();for(int face=0;face<colors.length;face++)for(int corner=0;corner<3;corner++){int index=face*6+corner*2;vertices.addVertexWith2DPose(pose,x+xy[index],y+xy[index+1]).setColor(colors[face]);}}
    }
    private static String t(String value){return ClientLanguage.t(value);}
}
