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
    static Map<String,Object> smokeState(){
        if(!Boolean.getBoolean("mineagent.nativeUiSmoke")&&!Boolean.getBoolean("mineagent.nativeTenSmoke"))throw new IllegalStateException("SMOKE_DISABLED");var view=smokeLast;if(view==null)return Map.of("ready",false);
        if(view.nativeScene!=null){var center=view.nativeScene.getCenter();return Map.of("ready",true,"native",true,"zoom",view.nativeScene.getZoom(),"yaw",view.nativeScene.getRotationYaw(),"center",List.of(center.x,center.y,center.z),"painted",view.nativeFrames>0,"title",view.title.getText().getString());}
        return view.projection==null?Map.of("ready",false):Map.of("ready",true,"native",false,"triangles",view.scene.triangleCount(),"zoom",view.zoom,"yaw",view.yaw,"center",List.of(view.panX,view.panY),"painted",view.paintedVersion==view.cameraVersion);
    }
    static UIElement smokeCanvas(){if(!Boolean.getBoolean("mineagent.nativeTenSmoke"))throw new IllegalStateException("SMOKE_DISABLED");return smokeLast.nativeScene==null?smokeLast.canvas:smokeLast.nativeScene;}
    static void smokeZoom(){var event=com.lowdragmc.lowdraglib2.gui.ui.event.UIEvent.create(UIEvents.MOUSE_WHEEL);event.target=smokeLast.canvas;event.deltaY=2;com.lowdragmc.lowdraglib2.gui.ui.event.UIEventDispatcher.dispatchEvent(event);}
    private final WorkspaceWindow window;private final UIElement canvas=new UIElement();private final TextElement title=WorkspacePanels.text(t("加载预览…")),note=WorkspacePanels.text("");
    private Scene nativeScene;private org.joml.Vector3f nativeCenter;private float nativeZoom;private int nativeFrames;
    private final Object connection,level;private NativeScene scene;private NativeScene.Projection projection;private boolean projecting;private long cameraVersion,paintedVersion=-1;private int paintedWidth,paintedHeight;
    private double yaw=-.6,pitch=.3,zoom=1,panX,panY;private float dragX,dragY;private double dragYaw,dragPitch,dragPanX,dragPanY;private boolean pan;
    public static void open(String id){var value=UUID.fromString(id);var mc=Minecraft.getInstance();if(mc.level==null)return;NativeWorkspaceScreen.open();pendingConnection=mc.getConnection();pendingLevel=mc.level;PENDING.add(value);}
    public static void tick(){var mc=Minecraft.getInstance();if(pendingConnection!=mc.getConnection()||pendingLevel!=mc.level){PENDING.clear();return;}if(!NativeWorkspaceConnection.ready())return;for(var id:List.copyOf(PENDING)){PENDING.remove(id);new NativePreview(NativeWorkspaceScreen.previewHost(),id);}}
    private NativePreview(NativeWorkspaceScreen host,UUID id){
        var mc=Minecraft.getInstance();if(Boolean.getBoolean("mineagent.nativeUiSmoke")||Boolean.getBoolean("mineagent.nativeTenSmoke"))smokeLast=this;connection=mc.getConnection();level=mc.level;window=host.window("preview-"+id,t("预览"),520,380);window.body.clearAllChildren();
        var tools=WorkspacePanels.row();tools.getLayout().height(25);window.body.addChild(tools);title.getLayout().flex(1);tools.addChild(title);tools.addChild(NativeUiTheme.button(t("复位"),()->{yaw=-.6;pitch=.3;zoom=1;panX=panY=0;if(nativeScene!=null)nativeScene.setCenter(new org.joml.Vector3f(nativeCenter)).setZoom(nativeZoom);cameraVersion++;}));
        canvas.getLayout().flex(1).widthPercent(100);canvas.getStyle().backgroundTexture(NativeUiTheme.inset()).overlayTexture(GuiTexture.of(this::paint));canvas.setOverflowVisible(false);window.body.addChild(canvas);window.body.addChild(WorkspacePanels.text(t("左键旋转，右键平移，滚轮缩放")));window.body.addChild(note);
        canvas.addEventListener(UIEvents.MOUSE_DOWN,event->{if(nativeScene!=null)return;if(event.button<0||event.button>2)return;pan=event.button!=0;dragX=event.x;dragY=event.y;dragYaw=yaw;dragPitch=pitch;dragPanX=panX;dragPanY=panY;canvas.startDrag(this,null);event.stopPropagation();});
        canvas.addEventListener(UIEvents.DRAG_SOURCE_UPDATE,event->{if(nativeScene!=null)return;double dx=event.x-dragX,dy=event.y-dragY;if(pan){panX=dragPanX+dx;panY=dragPanY+dy;}else{yaw=dragYaw+dx*.016;pitch=Math.clamp(dragPitch+dy*.016,-Math.PI/2+.001,Math.PI/2-.001);}cameraVersion++;event.stopPropagation();});
        canvas.addEventListener(UIEvents.MOUSE_WHEEL,event->{if(nativeScene!=null)return;zoom=Math.clamp(zoom*Math.exp(event.deltaY*.12),.2,6);cameraVersion++;event.stopPropagation();});read(id,0,new StringBuilder());
    }
    private boolean current(){var mc=Minecraft.getInstance();return connection==mc.getConnection()&&level==mc.level&&!window.closed()&&window.body.getChildren().contains(canvas);}
    private void read(UUID id,int offset,StringBuilder text){WorkspacePanels.request("preview.read",Map.of("previewId",id.toString(),"offset",Integer.toString(offset))).whenComplete((receipt,error)->{
        if(!current())return;if(error!=null){WorkspacePanels.failure(note,error);return;}
        try{String chunk=receipt.values().get("chunk");int next=Integer.parseInt(receipt.values().get("nextOffset"));if(chunk==null||text.length()!=offset||text.length()+chunk.length()>8*1024*1024||next!=-1&&(chunk.isEmpty()||next!=offset+chunk.length()))throw new IllegalArgumentException("PREVIEW_CHUNK_SEQUENCE");text.append(chunk);if(next>=0){read(id,next,text);return;}
            CompletableFuture.supplyAsync(()->{try{var data=new com.fasterxml.jackson.databind.ObjectMapper().readTree(text.toString());return data.has("nativeSource")?data:NativeScene.parse(text.toString());}catch(Exception invalid){throw new CompletionException(invalid);}},COMPUTE).whenComplete((value,failure)->Minecraft.getInstance().execute(()->{if(!current())return;if(failure!=null){WorkspacePanels.failure(note,failure);return;}try{if(value instanceof com.fasterxml.jackson.databind.JsonNode data){nativeScene(data);return;}scene=(NativeScene)value;title.setText(Component.literal(scene.title()));note.setText(Component.literal(scene.detail()));cameraVersion++;}catch(Exception invalid){WorkspacePanels.failure(note,invalid);}}));
        }catch(Exception invalid){WorkspacePanels.failure(note,invalid);}
    });}
    private void nativeScene(com.fasterxml.jackson.databind.JsonNode data){
        var mc=Minecraft.getInstance();var source=data.path("nativeSource");net.minecraft.world.entity.Entity entity;
        if(source.path("kind").asText().equals("entity")){
            UUID id=UUID.fromString(source.path("entityId").asText());entity=null;for(var candidate:mc.level.entitiesForRendering())if(candidate.getUUID().equals(id)){entity=candidate;break;}
            if(entity==null||!net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString().equals(source.path("entityType").asText()))throw new IllegalStateException("PREVIEW_ENTITY_NOT_TRACKED");
        }else if(source.path("kind").asText().equals("item")){
            var stack=net.minecraft.world.item.ItemStack.CODEC.parse(mc.level.registryAccess().createSerializationContext(com.mojang.serialization.JsonOps.INSTANCE),com.google.gson.JsonParser.parseString(source.path("stack").toString())).getOrThrow();
            entity=new net.minecraft.world.entity.item.ItemEntity(mc.level,0,0,0,stack.copy());
        }else throw new IllegalArgumentException("PREVIEW_NATIVE_KIND");
        final var target=entity;
        var world=new com.lowdragmc.lowdraglib2.utils.virtuallevel.TrackedDummyWorld(mc.level){@Override public Iterable<net.minecraft.world.entity.Entity> getAllRenderedEntities(){return target.isRemoved()?List.of():List.of(target);}};
        nativeScene=new Scene().setTickWorld(false).setRenderFacing(false).setRenderSelect(false).setIntractable(true).setDraggable(true).setScalable(true).setAllowXEILookup(false);
        nativeScene.setAfterWorldRender(scene->nativeFrames++);nativeScene.getLayout().widthPercent(100).heightPercent(100);nativeScene.createScene(world,true,null);
        nativeCenter=new org.joml.Vector3f((float)entity.getX(),(float)(entity.getY()+entity.getBbHeight()/2),(float)entity.getZ());nativeZoom=Math.max(1.2f,Math.max(entity.getBbHeight(),entity.getBbWidth())*2.2f);
        nativeScene.setCenter(new org.joml.Vector3f(nativeCenter)).setZoom(nativeZoom);
        nativeScene.addEventListener(UIEvents.MOUSE_DOWN,event->{if(event.button==1)event.button=2;},true);
        canvas.addChild(nativeScene);title.setText(Component.literal(data.path("title").asText()));note.setText(Component.literal(t(data.path("detail").asText())));
    }
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
