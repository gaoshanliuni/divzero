package dev.mineagent.runtime.client.webui;

import java.util.*;

/** Screen hit regions and live-raster coordinates. No page content or authority is inferred from a rectangle. */
public record NativeAtlasInputMap(NativeAtlasFrame frame,List<Region> regions) {
    public record Region(String surfaceId,NativeAtlasFrame.Rect source){
        public Region{if(surfaceId==null||surfaceId.isBlank()||source==null)throw new IllegalArgumentException("ATLAS_HIT_REGION");}
    }
    public record Point(UUID documentId,String viewId,double sourceX,double sourceY,
                        double anchorSourceX,double anchorSourceY,double anchorDestinationX,double anchorDestinationY){}
    public NativeAtlasInputMap{
        Objects.requireNonNull(frame);regions=List.copyOf(regions);
        if(regions.size()>64)throw new IllegalArgumentException("ATLAS_HIT_BUDGET");
        for(var region:regions){var surface=frame.surfaces().stream().filter(s->s.id().equals(region.surfaceId())).findFirst().orElseThrow(()->new IllegalArgumentException("ATLAS_HIT_SURFACE"));
            if(!contains(surface.source(),region.source()))throw new IllegalArgumentException("ATLAS_HIT_OUTSIDE_SOURCE");}
    }
    private static boolean contains(NativeAtlasFrame.Rect outer,NativeAtlasFrame.Rect inner){
        return inner.x()>=outer.x()&&inner.y()>=outer.y()&&inner.x()+inner.width()<=outer.x()+outer.width()+.01&&inner.y()+inner.height()<=outer.y()+outer.height()+.01;
    }
    private NativeAtlasFrame.Surface surface(String id){return frame.surfaces().stream().filter(s->s.id().equals(id)).findFirst().orElseThrow(()->new IllegalStateException("VIEW_NOT_RENDERED"));}
    private NativeAtlasFrame.Rect destination(Region region){var s=surface(region.surfaceId());var r=region.source();return new NativeAtlasFrame.Rect(s.destination().x()+r.x()-s.source().x(),s.destination().y()+r.y()-s.source().y(),r.width(),r.height());}
    public Optional<Point> pointer(double x,double y){
        if(!Double.isFinite(x)||!Double.isFinite(y))throw new IllegalArgumentException("ATLAS_INPUT");
        if(x<0||y<0||x>=frame.screenWidth()||y>=frame.screenHeight())return Optional.empty();
        return regions.stream().filter(r->Math.round(surface(r.surfaceId()).opacity()*255)>0&&destination(r).contains(x,y))
            .max(Comparator.comparingInt(r->surface(r.surfaceId()).z())).map(r->{var s=surface(r.surfaceId());return new Point(frame.documentId(),s.id(),s.source().x()+x-s.destination().x(),s.source().y()+y-s.destination().y(),s.source().x(),s.source().y(),s.destination().x(),s.destination().y());});
    }
    /** Only a proven root pointer-capture gesture may use this mapping outside its original hit rectangle. */
    public Point held(Point first,double screenX,double screenY){
        if(!Double.isFinite(screenX)||!Double.isFinite(screenY))throw new IllegalArgumentException("ATLAS_INPUT");
        var s=surface(first.viewId());
        if(!frame.documentId().equals(first.documentId())||Math.round(s.opacity()*255)==0||s.source().x()!=first.anchorSourceX()||s.source().y()!=first.anchorSourceY())throw new IllegalStateException("STALE_ATLAS_GESTURE");
        return new Point(first.documentId(),first.viewId(),first.anchorSourceX()+screenX-first.anchorDestinationX(),first.anchorSourceY()+screenY-first.anchorDestinationY(),first.anchorSourceX(),first.anchorSourceY(),first.anchorDestinationX(),first.anchorDestinationY());
    }
    public void requireInteractive(String id){
        var target=surface(id);if(Math.round(target.opacity()*255)==0)throw new IllegalStateException("VIEW_NOT_RENDERED");
        var own=regions.stream().filter(r->r.surfaceId().equals(id)).toList();if(own.isEmpty())throw new IllegalStateException("VIEW_NOT_RENDERED");
        var screen=new NativeAtlasFrame.Rect(0,0,frame.screenWidth(),frame.screenHeight());
        for(var r:own){var area=destination(r);if(!contains(screen,area))throw new IllegalStateException("VIEW_NOT_RENDERED");
            for(var other:regions){var s=surface(other.surfaceId());if(!s.id().equals(id)&&Math.round(s.opacity()*255)>0&&s.z()>=target.z()&&area.intersects(destination(other)))throw new IllegalStateException("VIEW_OCCLUDED");}}
    }
    public String identity(String id,boolean interactive){
        surface(id);if(interactive)requireInteractive(id);
        return dev.mineagent.runtime.api.ui.UiCapture.sha256((frame.documentId()+"/"+frame.width()+"/"+frame.height()+"/"+frame.screenWidth()+"/"+frame.screenHeight()+"/"+id+"/"+frame.surfaces()+"/"+regions).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
