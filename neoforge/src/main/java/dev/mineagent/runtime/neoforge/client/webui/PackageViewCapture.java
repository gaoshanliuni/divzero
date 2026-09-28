package dev.mineagent.runtime.neoforge.client.webui;

import java.io.IOException;
import java.util.concurrent.*;

/** Native isolated-view image and target-pixel comparison. No browser texture or game-framebuffer fallback. */
public final class PackageViewCapture {
    public record Captured(String documentId,String layoutIdentity,int width,int height,double frameX,double frameY,double scaleX,double scaleY,byte[] png){public Captured{png=png.clone();}@Override public byte[] png(){return png.clone();}}
    private static final ThreadPoolExecutor ENCODER=new ThreadPoolExecutor(1,1,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(1),r->{var t=new Thread(r,"mineagent-view-png");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    public static CompletableFuture<Boolean> sameTarget(byte[] before,byte[] after,dev.mineagent.runtime.api.ui.UiCapture.Manifest manifest,dev.mineagent.runtime.client.webui.UiCaptureCoordinates.Bounds bounds){
        return CompletableFuture.supplyAsync(()->{try{return dev.mineagent.runtime.client.webui.UiCaptureCoordinates.sameTarget(before,after,manifest,bounds);}catch(IOException invalid){throw new IllegalStateException("CAPTURE_PNG_DECODE",invalid);}},ENCODER);
    }
    private PackageViewCapture(){}
}
