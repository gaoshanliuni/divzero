package dev.mineagent.runtime.agent.ui;
import dev.mineagent.runtime.api.ui.UiCapture;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
class UiCaptureDownloadTest {
    byte[] bytes(){return Arrays.copyOf(Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/lZsAAAAASUVORK5CYII="),40000);}
    UiCapture.Image image(){return UiCapture.image(UUID.randomUUID(),"document","0".repeat(64),"1".repeat(64),2,3,1.5,1.5,bytes());}
    @Test void assemblesSequentialBoundedChunksAndPreservesActualPngBytes()throws Exception{
        var image=image();var calls=new AtomicInteger();
        var result=UiCaptureDownload.download(image.manifest(),index->{assertEquals(calls.getAndIncrement(),index);return CompletableFuture.completedFuture(UiCapture.chunk(image,index));},()->false,Duration.ofSeconds(2)).get();
        assertArrayEquals(bytes(),result.png().bytes());assertEquals(3,calls.get());
    }
    @Test void wrongCaptureIndexLengthAndInvalidImageNeverProduceAnImage(){
        var image=image();
        assertThrows(ExecutionException.class,()->UiCaptureDownload.download(image.manifest(),i->CompletableFuture.completedFuture(new UiCapture.Chunk(UUID.randomUUID(),i,"AA==")),()->false,Duration.ofSeconds(1)).get());
        assertThrows(ExecutionException.class,()->UiCaptureDownload.download(image.manifest(),i->CompletableFuture.completedFuture(new UiCapture.Chunk(image.manifest().captureId(),i+1,UiCapture.chunk(image,i).base64())),()->false,Duration.ofSeconds(1)).get());
        assertThrows(ExecutionException.class,()->UiCaptureDownload.download(image.manifest(),i->CompletableFuture.completedFuture(new UiCapture.Chunk(image.manifest().captureId(),i,"AA==")),()->false,Duration.ofSeconds(1)).get());
        assertThrows(ExecutionException.class,()->UiCaptureDownload.download(image.manifest(),i->{var c=UiCapture.chunk(image,i);byte[] bytes=Base64.getDecoder().decode(c.base64());if(i==0)bytes[0]^=1;return CompletableFuture.completedFuture(new UiCapture.Chunk(c.captureId(),i,Base64.getEncoder().encodeToString(bytes)));},()->false,Duration.ofSeconds(1)).get());
    }
    @Test void cancellationAndOverallTimeoutStopTheNextRequest(){
        var image=image();var count=new AtomicInteger();
        assertThrows(ExecutionException.class,()->UiCaptureDownload.download(image.manifest(),i->{count.incrementAndGet();return CompletableFuture.completedFuture(UiCapture.chunk(image,i));},()->true,Duration.ofSeconds(1)).get());assertEquals(0,count.get());
        var pending=new CompletableFuture<UiCapture.Chunk>();
        assertThrows(ExecutionException.class,()->UiCaptureDownload.download(image.manifest(),i->{count.incrementAndGet();return pending;},()->false,Duration.ofMillis(30)).get());
        pending.complete(UiCapture.chunk(image,0));assertEquals(1,count.get());
    }
}
