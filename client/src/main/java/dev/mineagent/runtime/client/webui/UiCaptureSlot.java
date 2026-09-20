package dev.mineagent.runtime.client.webui;
import dev.mineagent.runtime.api.ui.UiCapture;
import java.time.Clock;
import java.util.*;

/** One ephemeral immutable capture per already-authorized Control; never a global image lookup. */
public final class UiCaptureSlot {
    private final Clock clock;private final long ttl;
    private UiCapture.Image image;private long expires;
    public UiCaptureSlot(Clock clock,long ttl){this.clock=Objects.requireNonNull(clock);if(ttl<1||ttl>120000)throw new IllegalArgumentException("UI_CAPTURE_TTL");this.ttl=ttl;}
    public synchronized void publish(UiCapture.Image image){this.image=Objects.requireNonNull(image);expires=clock.millis()+ttl;}
    public synchronized UiCapture.Chunk chunk(UUID id,int index){
        if(image!=null&&clock.millis()>=expires)clear();
        if(image==null||!image.manifest().captureId().equals(id))throw new IllegalStateException("STALE_CAPTURE");
        return UiCapture.chunk(image,index);
    }
    public synchronized int bytes(){return image==null?0:image.manifest().pngLength();}
    public synchronized void clear(){image=null;expires=0;}
}
