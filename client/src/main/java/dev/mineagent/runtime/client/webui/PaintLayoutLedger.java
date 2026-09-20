package dev.mineagent.runtime.client.webui;

import java.util.*;

/** Correlates trusted geometry with the token read from actual BGRA paint, in either arrival order.
 * A match describes that paint, not the latest DOM and not a per-view isolated texture. Client-thread confined. */
public final class PaintLayoutLedger {
    public record Layer(String id,String kind,double x,double y,double width,double height,int z) {
        public Layer {
            if(id==null||id.isBlank()||id.length()>128||!Set.of("WINDOW","CHROME").contains(kind)
                    ||!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(width)||!Double.isFinite(height)
                    ||Math.abs(x)>16384||Math.abs(y)>16384||width<=0||height<=0||width>8192||height>8192||z<0)
                throw new IllegalArgumentException("PAINT_LAYOUT_LAYER");
        }
    }
    public record Frame(UUID documentId,long revision,String token,int width,int height,List<Layer> layers) {
        public Frame {
            Objects.requireNonNull(documentId);layers=List.copyOf(layers);
            if(revision<1||token==null||!token.matches("[0-9a-f]{24}")||token.equals("000000000000000000000000")
                    ||width<37||height<4||width>8192||height>8192||layers.size()>16
                    ||layers.stream().map(Layer::id).distinct().count()!=layers.size())
                throw new IllegalArgumentException("PAINT_LAYOUT_FRAME");
        }
    }
    public record Matched(Frame frame,long paintSequence,int textureWidth,int textureHeight) {}
    private record Pixels(long sequence,int width,int height,byte[] row) {}
    private UUID document;
    private long revision;
    private final Set<UUID> retiredDocuments=new HashSet<>();
    private final LinkedHashMap<String,Frame> frames=new LinkedHashMap<>();
    private Pixels pixels;

    public void bindDocument(UUID id) {
        Objects.requireNonNull(id);if(id.equals(document))return;
        if(retiredDocuments.contains(id)||retiredDocuments.size()>=64)throw new IllegalArgumentException("PAINT_LAYOUT_DOCUMENT_RETIRED");
        if(document!=null)retiredDocuments.add(document);
        document=id;revision=0;frames.clear();pixels=null;
    }
    public void register(Frame frame) {
        if(!frame.documentId().equals(document)||frame.revision()<=revision||frames.containsKey(frame.token()))
            throw new IllegalArgumentException("PAINT_LAYOUT_STALE");
        frames.put(frame.token(),frame);revision=frame.revision();
        while(frames.size()>32)frames.remove(frames.keySet().iterator().next());
    }
    public void painted(long sequence,int width,int height,byte[] firstBgraRow) {
        if(sequence<1||width<1||width>8192||height<1||height>8192||firstBgraRow==null||firstBgraRow.length!=4*width)
            throw new IllegalArgumentException("PAINT_LAYOUT_PIXELS");
        if(pixels!=null&&sequence<=pixels.sequence())throw new IllegalArgumentException("PAINT_LAYOUT_PAINT_STALE");
        pixels=new Pixels(sequence,width,height,firstBgraRow.clone());
    }
    public Optional<Matched> matched() {
        if(pixels==null)return Optional.empty();
        for(var frame:frames.values()) {
            double sx=pixels.width()/(double)frame.width(),sy=pixels.height()/(double)frame.height();
            if(Math.abs(sx-sy)>2.0/Math.min(frame.width(),frame.height()))continue;
            StringBuilder token=new StringBuilder(24);
            for(int i=0;i<4;i++){
                int offset=4*(int)Math.floor((22+4*i)*sx);
                if(offset<0||offset+3>=pixels.row().length||(pixels.row()[offset+3]&255)!=255){token.setLength(0);break;}
                token.append(String.format(Locale.ROOT,"%02x%02x%02x",pixels.row()[offset+2]&255,pixels.row()[offset+1]&255,pixels.row()[offset]&255));
            }
            if(frame.token().contentEquals(token))return Optional.of(new Matched(frame,pixels.sequence(),pixels.width(),pixels.height()));
        }
        return Optional.empty();
    }
    public int pendingFrames(){return frames.size();}
    public void invalidatePaint(){pixels=null;}
    public void clear(){document=null;revision=0;retiredDocuments.clear();frames.clear();pixels=null;}
}
