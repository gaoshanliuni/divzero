package dev.mineagent.runtime.neoforge.client.audio;

import dev.mineagent.runtime.core.conversation.SpeechWav;
import javax.sound.sampled.*;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Opened only by an explicit Native recording button, never by a page load or constructor. */
public final class NativeSpeechRecorder {
    private static final ThreadPoolExecutor INPUT=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(1),r->{var t=new Thread(r,"mineagent-microphone");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    private static final ExecutorService CLOSE=Executors.newSingleThreadExecutor(r->{var t=new Thread(r,"mineagent-microphone-close");t.setDaemon(true);return t;});
    private static final ScheduledExecutorService TIMER=Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"mineagent-microphone-limit");t.setDaemon(true);return t;});
    public static final class Recording{
        private final AtomicBoolean stopped=new AtomicBoolean(),cancelled=new AtomicBoolean();private final AtomicReference<TargetDataLine> line=new AtomicReference<>();
        public final CompletableFuture<byte[]> result=new CompletableFuture<>();private final AtomicInteger bytes=new AtomicInteger();
        public double seconds(){return bytes.get()/32000.0;}
        public void stop(){stopped.set(true);closeLine();}
        public void cancel(){cancelled.set(true);stop();result.completeExceptionally(new IllegalStateException("ASR_RECORDING_CANCELLED"));}
        private void closeLine(){var current=line.getAndSet(null);if(current!=null)CLOSE.execute(()->{try{current.stop();}catch(Exception ignored){}try{current.close();}catch(Exception ignored){}});}
    }
    private NativeSpeechRecorder(){}
    public static Recording start(){var recording=new Recording();var timeout=TIMER.schedule(recording::stop,30,TimeUnit.SECONDS);recording.result.whenComplete((v,e)->timeout.cancel(false));try{INPUT.execute(()->capture(recording));}catch(RejectedExecutionException busy){recording.result.completeExceptionally(new IllegalStateException("ASR_RECORDER_BUSY"));}return recording;}
    private static void capture(Recording recording){
        var pcm=new ByteArrayOutputStream();TargetDataLine line=null;
        try{
            if(recording.stopped.get())throw new IllegalStateException("ASR_RECORDING_CANCELLED");
            var format=new AudioFormat(16000,16,1,true,false);line=(TargetDataLine)AudioSystem.getLine(new DataLine.Info(TargetDataLine.class,format));recording.line.set(line);
            if(recording.stopped.get())throw new IllegalStateException("ASR_RECORDING_CANCELLED");line.open(format);if(recording.stopped.get())throw new IllegalStateException("ASR_RECORDING_CANCELLED");line.start();byte[] buffer=new byte[3200];
            while(!recording.stopped.get()&&pcm.size()<SpeechWav.MAX_PCM_BYTES){int got=line.read(buffer,0,Math.min(buffer.length,SpeechWav.MAX_PCM_BYTES-pcm.size()));if(got<=0)break;pcm.write(buffer,0,got);recording.bytes.set(pcm.size());}
        }catch(Exception failed){if(!recording.stopped.get())recording.result.completeExceptionally(new IllegalStateException("ASR_MICROPHONE_UNAVAILABLE"));}
        finally{recording.stopped.set(true);recording.closeLine();if(line!=null)try{line.close();}catch(Exception ignored){}}
        if(recording.cancelled.get()||recording.result.isDone())return;
        try{recording.result.complete(SpeechWav.encode(pcm.toByteArray()));}catch(IllegalArgumentException shortAudio){recording.result.completeExceptionally(shortAudio);}
    }
}
