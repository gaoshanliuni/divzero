package dev.mineagent.runtime.client.webui;
import dev.mineagent.runtime.api.ui.UiCapture;
import org.junit.jupiter.api.Test;
import java.util.*;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import static org.junit.jupiter.api.Assertions.*;
class UiCaptureCoordinatesTest {
    UiCapture.Manifest manifest(){return new UiCapture.Manifest(UUID.randomUUID(),"doc","a".repeat(64),"b".repeat(64),300,150,2,3,1.5,1.5,100,"c".repeat(64));}
    @Test void screenshotPixelsMapOnlyThroughTheOriginalCaptureIdentity(){
        var m=manifest();assertEquals(new UiCaptureCoordinates.Point(102,53),UiCaptureCoordinates.point(m,m.captureId(),150,75,"doc",m.viewportHash(),m.layoutHash()));
        assertThrows(IllegalStateException.class,()->UiCaptureCoordinates.point(m,UUID.randomUUID(),150,75,"doc",m.viewportHash(),m.layoutHash()));
        assertThrows(IllegalStateException.class,()->UiCaptureCoordinates.point(m,m.captureId(),150,75,"new",m.viewportHash(),m.layoutHash()));
        assertThrows(IllegalStateException.class,()->UiCaptureCoordinates.point(m,m.captureId(),150,75,"doc","d".repeat(64),m.layoutHash()));
        assertThrows(IllegalStateException.class,()->UiCaptureCoordinates.point(m,m.captureId(),150,75,"doc",m.viewportHash(),"d".repeat(64)));
    }
    @Test void outOfRangeAndNonFinitePixelsNeverBecomePageCoordinates(){
        var m=manifest();for(double x:new double[]{-1,300,Double.NaN,Double.POSITIVE_INFINITY})assertThrows(IllegalArgumentException.class,()->UiCaptureCoordinates.point(m,m.captureId(),x,0,"doc",m.viewportHash(),m.layoutHash()));
    }
    byte[] png(int changedX,int changedY)throws Exception{
        var image=new BufferedImage(300,150,BufferedImage.TYPE_INT_ARGB);if(changedX>=0)image.setRGB(changedX,changedY,0xffff0000);
        var out=new ByteArrayOutputStream();ImageIO.write(image,"png",out);return out.toByteArray();
    }
    @Test void targetPixelChangesAreRejectedButUnrelatedRefreshingRegionsDoNotInvalidateIt()throws Exception{
        var m=manifest();var bounds=new UiCaptureCoordinates.Bounds(2,3,10,10);
        assertTrue(UiCaptureCoordinates.sameTarget(png(-1,0),png(100,100),m,bounds));
        assertFalse(UiCaptureCoordinates.sameTarget(png(-1,0),png(4,4),m,bounds));
    }
}
