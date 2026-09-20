package dev.mineagent.runtime.client.webui;

/** Trusted-host CSS rectangle converted inward to physical texture pixels. */
public record UiCaptureRegion(double x,double y,double width,double height,double hostWidth,double hostHeight) {
    public UiCaptureRegion {
        for(double v:new double[]{x,y,width,height,hostWidth,hostHeight})if(!Double.isFinite(v))throw new IllegalArgumentException("CAPTURE_REGION_INVALID");
        if(x<0||y<0||width<=0||height<=0||hostWidth<=0||hostHeight<=0||hostWidth>8192||hostHeight>8192||x+width>hostWidth||y+height>hostHeight)
            throw new IllegalArgumentException("CAPTURE_REGION_INVALID");
    }
    public record Pixels(int x,int y,int width,int height){}
    public Pixels pixels(int textureWidth,int textureHeight,int maxPixels){
        if(textureWidth<1||textureHeight<1||textureWidth>8192||textureHeight>8192||maxPixels<1)throw new IllegalArgumentException("CAPTURE_TEXTURE_BUDGET");
        double sx=textureWidth/hostWidth,sy=textureHeight/hostHeight;
        int left=(int)Math.ceil(x*sx),top=(int)Math.ceil(y*sy),right=(int)Math.floor((x+width)*sx),bottom=(int)Math.floor((y+height)*sy);
        if(right<=left||bottom<=top||(long)(right-left)*(bottom-top)>maxPixels)throw new IllegalArgumentException("CAPTURE_PIXEL_BUDGET");
        return new Pixels(left,top,right-left,bottom-top);
    }
}
