package dev.mineagent.runtime.agent.ui;
import dev.mineagent.runtime.api.ui.UiCapture;
import dev.mineagent.runtime.api.model.ModelImage;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.*;
import java.util.function.*;

/** Sequential bounded transport with exact id/index/size/hash validation, no browser assumptions on the server. */
public final class UiCaptureDownload {
    private UiCaptureDownload(){}
    public static CompletableFuture<UiCapture.Image> download(UiCapture.Manifest manifest,IntFunction<CompletableFuture<UiCapture.Chunk>> source,BooleanSupplier cancelled,Duration timeout){
        if(timeout.isNegative()||timeout.isZero()||timeout.compareTo(Duration.ofSeconds(30))>0)throw new IllegalArgumentException("UI_CAPTURE_TIMEOUT");
        return read(manifest,source,cancelled,System.nanoTime()+timeout.toNanos(),new ByteArrayOutputStream(manifest.pngLength()),0)
                .orTimeout(Math.max(1,timeout.toMillis()),TimeUnit.MILLISECONDS);
    }
    private static CompletableFuture<UiCapture.Image> read(UiCapture.Manifest m,IntFunction<CompletableFuture<UiCapture.Chunk>> source,BooleanSupplier cancelled,long deadline,ByteArrayOutputStream out,int index){
        if(cancelled.getAsBoolean())return CompletableFuture.failedFuture(new IllegalStateException("USER_INTERRUPTED"));
        if(System.nanoTime()>=deadline)return CompletableFuture.failedFuture(new TimeoutException("UI_CAPTURE_TIMEOUT"));
        try{return source.apply(index).thenCompose(chunk->{
            if(cancelled.getAsBoolean())throw new IllegalStateException("USER_INTERRUPTED");
            if(System.nanoTime()>=deadline)throw new CompletionException(new TimeoutException("UI_CAPTURE_TIMEOUT"));
            if(!m.captureId().equals(chunk.captureId())||chunk.index()!=index)throw new IllegalArgumentException("UI_CAPTURE_CHUNK_CONTEXT");
            byte[] bytes=Base64.getDecoder().decode(chunk.base64());int expected=Math.min(UiCapture.CHUNK_BYTES,m.pngLength()-out.size());
            if(bytes.length!=expected)throw new IllegalArgumentException("UI_CAPTURE_CHUNK_SIZE");out.writeBytes(bytes);
            if(index+1==m.chunks())return CompletableFuture.completedFuture(new UiCapture.Image(m,new ModelImage("image/png",out.toByteArray())));
            return read(m,source,cancelled,deadline,out,index+1);
        });}catch(Exception failure){return CompletableFuture.failedFuture(failure);}
    }
}
