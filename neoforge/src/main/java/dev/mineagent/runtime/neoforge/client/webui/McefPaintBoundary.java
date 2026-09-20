package dev.mineagent.runtime.neoforge.client.webui;
import com.cinemamod.mcef.MCEFBrowser;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.mineagent.runtime.client.webui.FramePaintQueue;
import dev.mineagent.runtime.neoforge.mixin.client.McefPaintAccess;
import java.awt.Rectangle;
import java.nio.ByteBuffer;
import java.util.*;
import org.lwjgl.system.MemoryUtil;
/** Freeze an owned browser's GPU image from extraction through drawing; queued BGRA buffers are independently owned. */
public final class McefPaintBoundary {
    private static final FramePaintQueue QUEUE=new FramePaintQueue(32,128L*1024*1024);private static final long MAX_PAINT_BYTES=64L*1024*1024;
    private static boolean replaying,stopping;private static long frame,received,applied,deferred,discarded,maxBytes,violations;private static Object failedOwner;private static String failure;
    private static final Map<Object,Stamp> STAMPS=new IdentityHashMap<>();
    public record Stamp(long frame,long sequence,int width,int height,boolean partial){}
    private McefPaintBoundary(){}
    public static boolean replayingOwnedPaint(){return replaying;}
    public static void beginFrame(){RenderSystem.assertOnRenderThread();frame++;try{replaying=true;QUEUE.beginFrame();WebGuiPopupCompositor.beginFrame();}catch(RuntimeException e){failed(WebGuiHostAdapter.INSTANCE.browser(),"PAINT_UPLOAD_FAILED");}finally{replaying=false;}}
    public static void endFrame(){RenderSystem.assertOnRenderThread();QUEUE.endFrame();}
    public static boolean defer(MCEFBrowser browser,boolean popup,Rectangle[] dirty,ByteBuffer buffer,int width,int height,Rectangle popupRect,boolean shown){
        if(stopping||replaying||!WebGuiHostAdapter.INSTANCE.owns(browser))return false;if(popup&&WebGuiAtlasCompositor.active()&&(width>4096||height>4096||(long)width*height>2_097_152)){WebGuiPopupCompositor.dismiss("POPUP_PIXEL_BUDGET");return true;}received++;boolean queued=QUEUE.inFrame();
        ByteBuffer owned=null;
        try{
            long size=Math.multiplyExact(Math.multiplyExact((long)width,height),4);if(width<1||height<1||width>8192||height>8192||size>MAX_PAINT_BYTES||buffer==null||buffer.capacity()<size||dirty==null||dirty.length>512)throw new IllegalArgumentException("PAINT_SNAPSHOT_BUDGET");
            owned=MemoryUtil.memAlloc((int)size);var source=buffer.duplicate();source.clear();source.limit((int)size);owned.put(source).flip();
            var rectangles=Arrays.stream(dirty).map(r->r==null?null:new Rectangle(r)).toArray(Rectangle[]::new);var bounds=popupRect==null?null:new Rectangle(popupRect);String url=browser.getURL();long popupEpoch=WebGuiPopupCompositor.epoch();final var snapshot=owned;owned=null;
            QUEUE.submit(browser,size,()->{boolean current=WebGuiHostAdapter.INSTANCE.browser()==browser&&WebGuiHostAdapter.INSTANCE.owns(browser)&&url.equals(browser.getURL());if(!current)discarded++;return current;},()->{boolean previous=replaying;replaying=true;try{if(popup&&WebGuiAtlasCompositor.active())WebGuiPopupCompositor.paint(browser,popupEpoch,snapshot,width,height,bounds);else{((McefPaintAccess)browser).mineagent$paint(popup,rectangles,snapshot,width,height,WebGuiAtlasCompositor.active()?null:bounds,shown);WebGuiPopupCompositor.basePaintApplied(popup,shown);}}finally{replaying=previous;}},()->MemoryUtil.memFree(snapshot));
            if(queued)deferred++;maxBytes=Math.max(maxBytes,QUEUE.pendingBytes());
        }catch(RuntimeException e){if(owned!=null)MemoryUtil.memFree(owned);failed(browser,"PAINT_SNAPSHOT_BUDGET");}
        return true;
    }
    public static void painted(com.cinemamod.mcef.MCEFRenderer renderer,boolean partial){var browser=WebGuiHostAdapter.INSTANCE.browser();if(browser==null||browser.getRenderer()!=renderer||!WebGuiHostAdapter.INSTANCE.owns(browser)||renderer.getTexture()==null)return;if(QUEUE.inFrame()&&!replaying)violations++;STAMPS.put(browser,new Stamp(frame,++applied,renderer.getTextureWidth(),renderer.getTextureHeight(),partial));WebGuiPaintComposition.uploaded(renderer,applied);}
    public static Stamp stamp(Object browser){return STAMPS.get(browser);}
    public static Map<String,Object> metrics(){return Map.of("frame",frame,"received",received,"applied",applied,"deferred",deferred,"discarded",discarded,"pending",QUEUE.pendingCount(),"pendingBytes",QUEUE.pendingBytes(),"maxPendingBytes",maxBytes,"midFrameUploads",violations);}
    public static void abortPopup(MCEFBrowser browser){if(WebGuiHostAdapter.INSTANCE.owns(browser))failed(browser,"POPUP_CLOSE_TIMEOUT");}
    private static void failed(Object browser,String code){failedOwner=browser;failure=code;}
    public static String takeFailure(Object browser){if(browser!=failedOwner)return null;String value=failure;failure=null;failedOwner=null;return value;}
    public static void discard(Object browser){if(browser==null)return;int before=QUEUE.pendingCount();QUEUE.discard(browser);discarded+=before-QUEUE.pendingCount();STAMPS.remove(browser);if(failedOwner==browser){failedOwner=null;failure=null;}}
    public static void shutdown(){WebGuiPopupCompositor.clear();stopping=true;QUEUE.clear();STAMPS.clear();}
}
