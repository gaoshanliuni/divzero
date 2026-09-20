package dev.mineagent.runtime.client.webui;
import java.util.*;

/** Distinct live raster tiles and their screen destinations. A flattened overlapping source is not an atlas. */
public record NativeAtlasFrame(UUID documentId,long revision,String token,int width,int height,int screenWidth,int screenHeight,List<Surface> surfaces){
    public record Rect(double x,double y,double width,double height){
        public Rect{if(!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(width)||!Double.isFinite(height)||Math.abs(x)>16384||Math.abs(y)>16384||width<=0||height<=0||width>8192||height>8192)throw new IllegalArgumentException("ATLAS_RECT");}
        boolean intersects(Rect r){return x<r.x+r.width&&x+width>r.x&&y<r.y+r.height&&y+height>r.y;}
        boolean contains(double px,double py){return px>=x&&py>=y&&px<x+width&&py<y+height;}
    }
    public record Surface(String id,Rect source,Rect destination,double opacity,int z){
        public Surface{if(id==null||id.isBlank()||id.length()>128||source==null||destination==null||!Double.isFinite(opacity)||opacity<0||opacity>1||z<0||z>1_000_000||Math.abs(source.width-destination.width)>.01||Math.abs(source.height-destination.height)>.01)throw new IllegalArgumentException("ATLAS_SURFACE");}
    }
    public record Draw(String id,int x,int y,int width,int height,int sourceX,int sourceY,int sourceWidth,int sourceHeight,int argb){}
    public NativeAtlasFrame{
        Objects.requireNonNull(documentId);surfaces=List.copyOf(surfaces);
        if(revision<1||token==null||!token.matches("[a-f0-9]{24}")||token.equals("0".repeat(24))||width<37||height<4||width>8192||height>8192||screenWidth<1||screenHeight<1||screenWidth>8192||screenHeight>8192||surfaces.size()>16||surfaces.stream().map(Surface::id).distinct().count()!=surfaces.size())throw new IllegalArgumentException("ATLAS_FRAME");
        for(int i=0;i<surfaces.size();i++){var r=surfaces.get(i).source();if(r.x<0||r.y<0||r.x+r.width>width+.01||r.y+r.height>height+.01)throw new IllegalArgumentException("ATLAS_CROP_OUTSIDE");for(int j=0;j<i;j++)if(r.intersects(surfaces.get(j).source()))throw new IllegalArgumentException("ATLAS_SOURCE_OVERLAP");}
    }
    public Optional<Surface> hit(double x,double y){if(!Double.isFinite(x)||!Double.isFinite(y))throw new IllegalArgumentException("ATLAS_INPUT");return surfaces.stream().filter(s->Math.round(s.opacity()*255)>0&&s.destination().contains(x,y)).max(Comparator.comparingInt(Surface::z));}
    public List<Draw> draws(int outputWidth,int outputHeight,int textureWidth,int textureHeight){
        if(outputWidth<1||outputHeight<1||textureWidth<1||textureHeight<1||textureWidth>8192||textureHeight>8192||(long)textureWidth*textureHeight>16_777_216)throw new IllegalArgumentException("ATLAS_TEXTURE_BUDGET");
        double sx=textureWidth/(double)width,sy=textureHeight/(double)height;if(Math.abs(sx-sy)>2.0/Math.min(width,height))throw new IllegalArgumentException("ATLAS_TEXTURE_SCALE");
        double dx=outputWidth/(double)screenWidth,dy=outputHeight/(double)screenHeight;var result=new ArrayList<Draw>();
        for(var s:surfaces.stream().sorted(Comparator.comparingInt(Surface::z)).toList()){
            if(Math.round(s.opacity()*255)==0)continue;var src=s.source();var dst=s.destination();int x=(int)Math.round(dst.x*dx),y=(int)Math.round(dst.y*dy);
            int left=(int)Math.round(src.x*sx),top=(int)Math.round(src.y*sy),right=(int)Math.round((src.x+src.width)*sx),bottom=(int)Math.round((src.y+src.height)*sy);
            result.add(new Draw(s.id(),x,y,Math.max(1,(int)Math.round((dst.x+dst.width)*dx)-x),Math.max(1,(int)Math.round((dst.y+dst.height)*dy)-y),left,top,right-left,bottom-top,((int)Math.round(s.opacity()*255)<<24)|0x00ffffff));
        }return List.copyOf(result);
    }
}
