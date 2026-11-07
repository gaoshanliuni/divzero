package dev.mineagent.runtime.worker.resources;

import dev.mineagent.runtime.api.model.ModelImage;
import javax.imageio.ImageIO;import java.awt.*;import java.awt.image.BufferedImage;import java.io.*;import java.util.*;

/** Decode first, select original pixel coordinates, then resize within the visual request budget. */
public final class ResourceImages {
 public record Selection(int x,int y,int width,int height,int outputWidth,int outputHeight,String detail){}
 public record Prepared(ModelImage image,int sourceWidth,int sourceHeight,Selection selection){}
 public static Prepared prepare(byte[] bytes,java.util.List<Integer> crop,String detail)throws IOException{
  if(bytes==null||bytes.length>64*1024*1024||!Set.of("auto","low","high").contains(detail))throw new IllegalArgumentException("IMAGE_INPUT_BUDGET");
  try(var in=ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))){if(in==null)throw new IOException("IMAGE_DECODE_FAILED");var readers=ImageIO.getImageReaders(in);if(!readers.hasNext())throw new IOException("IMAGE_FORMAT_UNSUPPORTED");var reader=readers.next();
   try{reader.setInput(in,true,true);int w=reader.getWidth(0),h=reader.getHeight(0);if(w<1||h<1||(long)w*h>80_000_000)throw new IOException("IMAGE_SOURCE_PIXEL_BUDGET");int x=0,y=0,cw=w,ch=h;
    if(crop!=null&&!crop.isEmpty()){if(crop.size()!=4)throw new IllegalArgumentException("IMAGE_CROP expected [x,y,width,height]");x=crop.get(0);y=crop.get(1);cw=crop.get(2);ch=crop.get(3);if(x<0||y<0||cw<1||ch<1||(long)x+cw>w||(long)y+ch>h)throw new IllegalArgumentException("IMAGE_CROP_OUTSIDE_SOURCE: source "+w+"x"+h);}
    var param=reader.getDefaultReadParam();param.setSourceRegion(new Rectangle(x,y,cw,ch));int budget=detail.equals("low")?512*512:detail.equals("high")?ModelImage.MAX_PIXELS:1536*1536;
    int sub=Math.max(1,(int)Math.floor(Math.sqrt((double)cw*ch/budget)));if(sub>1)param.setSourceSubsampling(sub,sub,0,0);var source=reader.read(0,param);
    try{var image=encode(source,budget);return new Prepared(image,w,h,new Selection(x,y,cw,ch,image.width(),image.height(),detail));}finally{source.flush();}
   }finally{reader.dispose();}
  }
 }
 public static ModelImage encode(BufferedImage source,int maxPixels)throws IOException{
  double scale=Math.min(1,Math.sqrt((double)maxPixels/((long)source.getWidth()*source.getHeight())));int w=Math.max(1,(int)(source.getWidth()*scale)),h=Math.max(1,(int)(source.getHeight()*scale));
  for(int attempt=0;attempt<8;attempt++){var output=new BufferedImage(w,h,BufferedImage.TYPE_INT_RGB);var graphics=output.createGraphics();try{graphics.setColor(Color.WHITE);graphics.fillRect(0,0,w,h);graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BICUBIC);graphics.drawImage(source,0,0,w,h,null);}finally{graphics.dispose();}var bytes=new ByteArrayOutputStream();ImageIO.write(output,"png",bytes);output.flush();if(bytes.size()<=ModelImage.MAX_BYTES)return new ModelImage("image/png",bytes.toByteArray());w=Math.max(1,w*3/4);h=Math.max(1,h*3/4);}throw new IOException("IMAGE_ENCODE_BUDGET");
 }
 private ResourceImages(){}
}
