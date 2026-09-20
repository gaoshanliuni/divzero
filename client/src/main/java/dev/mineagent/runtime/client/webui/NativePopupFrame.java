package dev.mineagent.runtime.client.webui;
import java.util.*;

/** A real CEF popup raster is separate from the atlas. Inputs map back to the original native menu coordinates. */
public record NativePopupFrame(UUID documentId,String owner,long epoch,NativeAtlasFrame.Rect source,
                               NativeAtlasFrame.Rect destination,int alpha) {
    public record Point(int x,int y){}
    public NativePopupFrame{
        Objects.requireNonNull(documentId);Objects.requireNonNull(source);Objects.requireNonNull(destination);
        if(owner==null||owner.isBlank()||epoch<1||alpha<1||alpha>255||source.width()>4096||source.height()>4096||source.width()*source.height()>2_097_152)throw new IllegalArgumentException("POPUP_BUDGET");
    }
    public static NativePopupFrame place(NativeAtlasFrame atlas,String owner,long epoch,NativeAtlasFrame.Rect nativeRect,int textureWidth,int textureHeight){
        atlas.draws(atlas.screenWidth(),atlas.screenHeight(),textureWidth,textureHeight);
        var s=atlas.surfaces().stream().filter(v->v.id().equals(owner)).findFirst().orElseThrow(()->new IllegalArgumentException("POPUP_OWNER"));
        double sx=textureWidth/(double)atlas.width(),sy=textureHeight/(double)atlas.height();
        double w=nativeRect.width()/sx,h=nativeRect.height()/sy;
        double fit=Math.min(1,Math.min(atlas.screenWidth()/w,atlas.screenHeight()/h));w*=fit;h*=fit;
        double x=s.destination().x()+nativeRect.x()/sx-s.source().x(),y=s.destination().y()+nativeRect.y()/sy-s.source().y();
        var destination=new NativeAtlasFrame.Rect(Math.max(0,Math.min(x,atlas.screenWidth()-w)),Math.max(0,Math.min(y,atlas.screenHeight()-h)),w,h);
        return new NativePopupFrame(atlas.documentId(),owner,epoch,nativeRect,destination,(int)Math.round(s.opacity()*255));
    }
    public Optional<Point> point(double screenCssX,double screenCssY){
        if(!Double.isFinite(screenCssX)||!Double.isFinite(screenCssY))throw new IllegalArgumentException("POPUP_INPUT");
        if(!destination.contains(screenCssX,screenCssY))return Optional.empty();
        return Optional.of(new Point((int)Math.floor(source.x()+(screenCssX-destination.x())*source.width()/destination.width()),
                (int)Math.floor(source.y()+(screenCssY-destination.y())*source.height()/destination.height())));
    }
}
