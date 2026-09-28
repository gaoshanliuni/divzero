package dev.mineagent.runtime.worker.web;
import org.junit.jupiter.api.Test;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import static org.junit.jupiter.api.Assertions.*;
class ImageTextureTest {
    @Test void rasterIsNormalizedToPngWithExactHashAndFitBounds()throws Exception{
        var image=new BufferedImage(8,4,BufferedImage.TYPE_INT_ARGB);for(int y=0;y<4;y++)for(int x=0;x<8;x++)image.setRGB(x,y,0xffee2233);var bytes=new ByteArrayOutputStream();ImageIO.write(image,"PNG",bytes);
        var result=ImageTexture.normalize(bytes.toByteArray(),32,"contain","https://example.org/art.png");var output=ImageIO.read(new ByteArrayInputStream(result.png()));assertEquals(32,output.getWidth());assertEquals(32,output.getHeight());assertEquals(0,output.getRGB(16,1));assertEquals(0xffee2233,output.getRGB(16,16));assertEquals(64,result.sha256().length());assertEquals(8,result.sourceWidth());
        assertThrows(IllegalArgumentException.class,()->ImageTexture.normalize(bytes.toByteArray(),33,"contain",""));assertThrows(IOException.class,()->ImageTexture.normalize("<svg/>".getBytes(),32,"contain",""));
    }
}
