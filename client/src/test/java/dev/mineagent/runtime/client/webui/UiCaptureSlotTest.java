package dev.mineagent.runtime.client.webui;
import dev.mineagent.runtime.api.ui.UiCapture;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class UiCaptureSlotTest {
    @Test void expiredCaptureIsReleasedRatherThanResurrectedByAChunkRetry(){
        var clock=new Clock(){long now=1000;public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return Instant.ofEpochMilli(now);}};
        var slot=new UiCaptureSlot(clock,10);var image=image();slot.publish(image);clock.now=1010;
        assertThrows(IllegalStateException.class,()->slot.chunk(image.manifest().captureId(),0));assertEquals(0,slot.bytes());
    }
    UiCapture.Image image(){return UiCapture.image(UUID.randomUUID(),"doc","0".repeat(64),"1".repeat(64),0,0,1,1,Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/lZsAAAAASUVORK5CYII="));}
    @Test void onlyCurrentCaptureCanBeReadAndClearRevokesIt(){
        var slot=new UiCaptureSlot(Clock.systemUTC(),120000);var first=image();slot.publish(first);
        assertEquals(first.manifest().captureId(),slot.chunk(first.manifest().captureId(),0).captureId());
        slot.publish(image());assertThrows(IllegalStateException.class,()->slot.chunk(first.manifest().captureId(),0));slot.clear();assertEquals(0,slot.bytes());
    }
    @Test void pngChunksStayWithinTheExistingRpcBudget(){
        var c=UiCapture.chunk(image(),0);assertTrue(c.base64().length()<22000);
        assertThrows(IllegalArgumentException.class,()->UiCapture.chunk(image(),1));
        assertThrows(IllegalArgumentException.class,()->new UiCapture.Chunk(UUID.randomUUID(),0,"A".repeat(22000)));
    }
}
