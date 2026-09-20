package dev.mineagent.runtime.neoforge.client.audio;

import javazoom.jl.decoder.*;
import javax.sound.sampled.*;
import java.io.ByteArrayInputStream;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;

/** Bounded private playback, independent of the shared/spatial media queue. Never captures audio. */
final class PrivateVoicePlayer {
    private static final ThreadPoolExecutor AUDIO=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(4),r->{var t=new Thread(r,"mineagent-private-voice");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    private static final ExecutorService CONTROL=Executors.newSingleThreadExecutor(r->{var t=new Thread(r,"mineagent-private-voice-stop");t.setDaemon(true);return t;});
    private PrivateVoicePlayer(){}
    static final class Playback {
        final AtomicBoolean cancelled=new AtomicBoolean(),finished=new AtomicBoolean();
        final AtomicReference<SourceDataLine> line=new AtomicReference<>();
        final BiConsumer<String,String> report;
        Playback(BiConsumer<String,String> report){this.report=report;}
        void cancel(){if(!cancelled.compareAndSet(false,true))return;var active=line.getAndSet(null);if(active!=null)CONTROL.execute(()->close(active));}
        void finish(String state,String code){if(finished.compareAndSet(false,true))report.accept(state,code);}
    }
    static Playback play(byte[] bytes,BooleanSupplier current,BiConsumer<String,String> report){
        var playback=new Playback(report);report.accept("QUEUED","");
        try{AUDIO.execute(()->decode(bytes,current,playback));}catch(RejectedExecutionException full){playback.finish("FAILED","AUDIO_QUEUE_FULL");}return playback;
    }
    private static void close(SourceDataLine line){try{line.stop();}catch(Exception ignored){}try{line.flush();}catch(Exception ignored){}try{line.close();}catch(Exception ignored){}}
    private static void decode(byte[] bytes,BooleanSupplier current,Playback playback){
        SourceDataLine device=null;Bitstream stream=null;boolean wrote=false;
        try(var input=new ByteArrayInputStream(bytes)){
            if(playback.cancelled.get()||!current.getAsBoolean()){playback.finish("STOPPED","");return;}
            stream=new Bitstream(input);var decoder=new Decoder();Header header;
            while(!playback.cancelled.get()&&current.getAsBoolean()&&(header=stream.readFrame())!=null){
                try{
                    var decoded=(SampleBuffer)decoder.decodeFrame(header,stream);
                    if(device==null){var format=new AudioFormat(decoded.getSampleFrequency(),16,decoded.getChannelCount(),true,false);device=AudioSystem.getSourceDataLine(format);device.open(format);playback.line.set(device);if(playback.cancelled.get()||!current.getAsBoolean())break;device.start();}
                    short[] samples=decoded.getBuffer();int length=decoded.getBufferLength();byte[] pcm=new byte[length*2];for(int i=0;i<length;i++){pcm[i*2]=(byte)samples[i];pcm[i*2+1]=(byte)(samples[i]>>>8);}
                    int offset=0;while(offset<pcm.length&&!playback.cancelled.get()&&current.getAsBoolean()){int sent=device.write(pcm,offset,Math.min(4096,pcm.length-offset));if(sent<=0)throw new IllegalStateException("AUDIO_DEVICE_WRITE_FAILED");offset+=sent;if(!wrote){wrote=true;playback.report.accept("PLAYING","");}}
                }finally{stream.closeFrame();}
            }
            if(playback.cancelled.get()||!current.getAsBoolean())playback.finish("STOPPED","");else if(!wrote)playback.finish("FAILED","AUDIO_EMPTY_DECODE");else{device.drain();playback.finish(playback.cancelled.get()||!current.getAsBoolean()?"STOPPED":"FINISHED","");}
        }catch(LineUnavailableException unavailable){playback.finish("FAILED","AUDIO_DEVICE_UNAVAILABLE");}
        catch(Exception invalid){playback.finish(playback.cancelled.get()||!current.getAsBoolean()?"STOPPED":"FAILED",playback.cancelled.get()||!current.getAsBoolean()?"":"AUDIO_DECODE_OR_DEVICE_FAILED");}
        finally{if(device!=null){playback.line.compareAndSet(device,null);close(device);}if(stream!=null)try{stream.close();}catch(Exception ignored){}}
    }
}
