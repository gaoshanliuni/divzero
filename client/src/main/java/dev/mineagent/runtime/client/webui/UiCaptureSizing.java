package dev.mineagent.runtime.client.webui;
public final class UiCaptureSizing {
    private UiCaptureSizing(){}
    public record Size(int width,int height){}
    public static Size fit(int width,int height,int pixels){
        if(width<1||height<1||pixels<1)throw new IllegalArgumentException("CAPTURE_DIMENSIONS");
        double scale=Math.min(1,Math.sqrt((double)pixels/((long)width*height)));
        int w=Math.max(1,(int)Math.floor(width*scale)),h=Math.max(1,(int)Math.floor(height*scale));
        while((long)w*h>pixels){if(w>=h)w--;else h--;}
        return new Size(w,h);
    }
}
