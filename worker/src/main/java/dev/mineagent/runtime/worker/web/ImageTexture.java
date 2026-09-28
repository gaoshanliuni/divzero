package dev.mineagent.runtime.worker.web;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;

/** Decode bounded raster data and normalize it to one PNG tile, without executable asset formats. */
public final class ImageTexture {
    public record Image(byte[] png,String sha256,int sourceWidth,int sourceHeight,int size,String sourceUrl){public Image{png=png.clone();}@Override public byte[] png(){return png.clone();}}
    public static Image download(String url,int size,String fit)throws Exception{
        var page=new PublicHttpsReader(8*1024*1024).get(url);if(page.status()!=200)throw new IOException("IMAGE_DOWNLOAD_HTTP_"+page.status());
        return normalize(page.body(),size,fit,page.url().toString());
    }
    public static Image normalize(byte[] source,int size,String fit,String url)throws Exception{
        if(source==null||source.length==0||source.length>8*1024*1024||size<16||size>512||(size&(size-1))!=0||!Set.of("contain","cover","stretch").contains(fit))throw new IllegalArgumentException("IMAGE_TEXTURE_ARGUMENTS");
        BufferedImage decoded;int width,height;
        try(var input=ImageIO.createImageInputStream(new ByteArrayInputStream(source))){
            var readers=ImageIO.getImageReaders(input);if(!readers.hasNext())throw new IOException("IMAGE_RASTER_REQUIRED");var reader=readers.next();
            try{reader.setInput(input,true,true);width=reader.getWidth(0);height=reader.getHeight(0);if(width<1||height<1||width>16384||height>16384||(long)width*height>16_777_216)throw new IOException("IMAGE_PIXEL_LIMIT");decoded=reader.read(0);}finally{reader.dispose();}
        }
        if(decoded==null)throw new IOException("IMAGE_DECODE_FAILED");var tile=new BufferedImage(size,size,BufferedImage.TYPE_INT_ARGB);var graphics=tile.createGraphics();
        try{graphics.setComposite(AlphaComposite.Src);graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BICUBIC);int w=size,h=size;if(!fit.equals("stretch")){double scale=fit.equals("cover")?Math.max((double)size/width,(double)size/height):Math.min((double)size/width,(double)size/height);w=Math.max(1,(int)Math.round(width*scale));h=Math.max(1,(int)Math.round(height*scale));}graphics.drawImage(decoded,(size-w)/2,(size-h)/2,w,h,null);}finally{graphics.dispose();decoded.flush();}
        var bytes=new ByteArrayOutputStream();if(!ImageIO.write(tile,"PNG",bytes))throw new IOException("IMAGE_PNG_ENCODER");tile.flush();byte[] png=bytes.toByteArray();if(png.length>2*1024*1024)throw new IOException("IMAGE_OUTPUT_LIMIT");return new Image(png,HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(png)),width,height,size,url);
    }
    private ImageTexture(){}
}
