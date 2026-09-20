package dev.mineagent.runtime.client.webui;
import dev.mineagent.runtime.api.ui.UiCapture;
import dev.mineagent.runtime.api.model.ModelImage;
import java.util.UUID;
import java.io.*;
import javax.imageio.ImageIO;

/** Coordinate conversion and target-local visual fence; no desktop or host-frame coordinates are accepted. */
public final class UiCaptureCoordinates {
    public record Point(double x,double y){}
    public record Bounds(double x,double y,double width,double height){
        public Bounds{for(double n:new double[]{x,y,width,height})if(!Double.isFinite(n))throw new IllegalArgumentException("CAPTURE_TARGET_BOUNDS");if(width<=0||height<=0)throw new IllegalArgumentException("CAPTURE_TARGET_BOUNDS");}
    }
    private UiCaptureCoordinates(){}
    public static Point point(UiCapture.Manifest m,UUID captureId,double x,double y,String document,String viewportHash,String layoutHash){
        if(!m.captureId().equals(captureId)||!m.documentId().equals(document)||!m.viewportHash().equals(viewportHash)||!m.layoutHash().equals(layoutHash))throw new IllegalStateException("STALE_CAPTURE");
        if(!Double.isFinite(x)||!Double.isFinite(y)||x<0||y<0||x>=m.width()||y>=m.height())throw new IllegalArgumentException("CAPTURE_COORDINATE_BOUNDS");
        return new Point(m.frameX()+x/m.scaleX(),m.frameY()+y/m.scaleY());
    }
    public static boolean sameTarget(byte[] before,byte[] after,UiCapture.Manifest m,Bounds bounds)throws IOException{
        var a=new ModelImage("image/png",before);var b=new ModelImage("image/png",after);
        if(a.width()!=m.width()||a.height()!=m.height()||b.width()!=m.width()||b.height()!=m.height())return false;
        int left=(int)Math.max(0,Math.floor((bounds.x-m.frameX())*m.scaleX())),top=(int)Math.max(0,Math.floor((bounds.y-m.frameY())*m.scaleY()));
        int right=(int)Math.min(m.width(),Math.ceil((bounds.x+bounds.width-m.frameX())*m.scaleX())),bottom=(int)Math.min(m.height(),Math.ceil((bounds.y+bounds.height-m.frameY())*m.scaleY()));
        if(right<=left||bottom<=top)throw new IllegalArgumentException("CAPTURE_TARGET_OUTSIDE");
        var oldImage=ImageIO.read(new ByteArrayInputStream(before));var newImage=ImageIO.read(new ByteArrayInputStream(after));
        if(oldImage==null||newImage==null)throw new IOException("CAPTURE_PNG_DECODE");
        try{for(int y=top;y<bottom;y++)for(int x=left;x<right;x++)if(oldImage.getRGB(x,y)!=newImage.getRGB(x,y))return false;return true;}
        finally{oldImage.flush();newImage.flush();}
    }
}
