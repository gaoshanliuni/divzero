package dev.mineagent.runtime.neoforge.client.webui;

import com.cinemamod.mcef.*;
import com.google.gson.*;
import dev.mineagent.runtime.client.webui.PaintLayoutLedger;
import net.minecraft.client.Minecraft;
import org.cef.browser.*;
import org.cef.callback.CefQueryCallback;
import org.cef.handler.CefMessageRouterHandlerAdapter;
import java.nio.ByteBuffer;
import java.util.*;

/** Local trusted geometry/paint correlation. Does not grant input, replace capture fences, or enable opacity. */
public final class WebGuiPaintComposition {
    private static final PaintLayoutLedger LEDGER=new PaintLayoutLedger();
    private static CefMessageRouter router;
    private record Prepared(MCEFBrowser browser,int width,int height,byte[] firstRow) {}
    private static Prepared prepared;
    private static long plans,pairedUploads,unpairedUploads,windowStart;
    private static int requests;
    private WebGuiPaintComposition(){}
    public static void register(){
        if(router!=null)return;
        router=CefMessageRouter.create(new CefMessageRouter.CefMessageRouterConfig("mineagentPaintPlanQuery","mineagentPaintPlanCancel"));
        router.addHandler(new CefMessageRouterHandlerAdapter(){
            @Override public boolean onQuery(CefBrowser browser,CefFrame frame,long id,String request,boolean persistent,CefQueryCallback callback){
                if(request==null||persistent||request.length()>16384||!WebGuiHostAdapter.INSTANCE.acceptsTrustedFrame(browser,frame,request.length())||!allow()){
                    callback.failure(403,"PAINT_LAYOUT_SOURCE");return true;
                }
                String url=frame.getURL();Minecraft.getInstance().execute(()->{
                    try{
                        var host=WebGuiHostAdapter.INSTANCE;
                        if(host.browser()!=browser||!host.ready()||!url.equals(browser.getURL()))throw new IllegalArgumentException("PAINT_LAYOUT_STALE");
                        acceptPlan(JsonParser.parseString(request).getAsJsonObject());
                        plans++;callback.success("{\"status\":\"PAINT_LAYOUT_REGISTERED\"}");
                    }catch(RuntimeException invalid){callback.failure(409,"PAINT_LAYOUT_REJECTED");}
                });return true;
            }
        },true);MCEF.getClient().getHandle().addMessageRouter(router);
    }
    static void acceptPlan(JsonObject data){
        var layers=new ArrayList<PaintLayoutLedger.Layer>();var array=data.getAsJsonArray("layers");if(array.size()>16)throw new IllegalArgumentException("PAINT_LAYOUT_BUDGET");
        for(var value:array){var l=value.getAsJsonObject();layers.add(new PaintLayoutLedger.Layer(string(l,"id"),string(l,"kind"),number(l,"x"),number(l,"y"),number(l,"width"),number(l,"height"),Math.toIntExact(integer(l,"z"))));}
        var atlas=data.has("atlas")&&data.get("atlas").getAsBoolean()?WebGuiAtlasCompositor.parse(data):null;
        LEDGER.register(new PaintLayoutLedger.Frame(UUID.fromString(string(data,"documentId")),integer(data,"revision"),string(data,"token"),Math.toIntExact(integer(data,"width")),Math.toIntExact(integer(data,"height")),layers));
        if(atlas!=null)WebGuiAtlasCompositor.register(atlas);
    }
    private static synchronized boolean allow(){long now=System.nanoTime();if(now-windowStart>1_000_000_000L){windowStart=now;requests=0;}return ++requests<=256;}
    private static String string(JsonObject o,String key){var p=o.getAsJsonPrimitive(key);if(p==null||!p.isString()||p.getAsString().length()>128)throw new IllegalArgumentException("PAINT_LAYOUT_STRING");return p.getAsString();}
    private static double number(JsonObject o,String key){var p=o.getAsJsonPrimitive(key);if(p==null||!p.isNumber())throw new IllegalArgumentException("PAINT_LAYOUT_NUMBER");return p.getAsDouble();}
    private static long integer(JsonObject o,String key){var p=o.getAsJsonPrimitive(key);if(p==null||!p.isNumber())throw new IllegalArgumentException("PAINT_LAYOUT_INTEGER");return p.getAsBigDecimal().longValueExact();}
    public static void bindDocument(UUID document){LEDGER.bindDocument(document);}
    public static Optional<PaintLayoutLedger.Matched> matched(){return LEDGER.matched();}
    public static void beforePaint(MCEFBrowser browser,boolean popup,ByteBuffer bytes,int width,int height){
        prepared=null;if(!WebGuiHostAdapter.INSTANCE.owns(browser))return;
        if(!McefPaintBoundary.replayingOwnedPaint()||popup||bytes==null||width<1||width>8192||height<1||height>8192||bytes.capacity()<(long)width*height*4){
            prepared=new Prepared(browser,width,height,null);return;
        }
        byte[] row=new byte[width*4];var source=bytes.duplicate();source.clear();source.get(row);
        // Trusted shell reserves the first physical scanline for metadata. Mutate only our independently owned paint copy.
        for(int i=0;i<row.length;i++)bytes.put(i,(byte)0);
        prepared=new Prepared(browser,width,height,row);
    }
    public static void uploaded(MCEFRenderer renderer,long sequence){
        var browser=WebGuiHostAdapter.INSTANCE.browser();if(browser==null||browser.getRenderer()!=renderer)return;
        if(prepared==null||prepared.browser()!=browser||prepared.firstRow()==null||prepared.width()!=renderer.getTextureWidth()||prepared.height()!=renderer.getTextureHeight()){
            LEDGER.invalidatePaint();unpairedUploads++;return;
        }
        LEDGER.painted(sequence,prepared.width(),prepared.height(),prepared.firstRow());pairedUploads++;
    }
    public static void afterPaint(){prepared=null;}
    public static Map<String,Object> snapshot(){
        var result=new LinkedHashMap<String,Object>();result.put("plans",plans);result.put("pairedUploads",pairedUploads);result.put("unpairedUploads",unpairedUploads);result.put("pendingFrames",LEDGER.pendingFrames());
        var match=LEDGER.matched();result.put("status",match.isPresent()?"PAINT_LAYOUT_MATCHED":"PAINT_LAYOUT_PENDING");
        match.ifPresent(value->{result.put("frame",value.frame());result.put("paintSequence",value.paintSequence());result.put("textureWidth",value.textureWidth());result.put("textureHeight",value.textureHeight());});return result;
    }
    public static void clear(){LEDGER.clear();prepared=null;WebGuiAtlasCompositor.clear();}
}
