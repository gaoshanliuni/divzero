package dev.mineagent.runtime.worker.resources;
import java.nio.file.*;import java.io.*;import javax.sound.sampled.*;
public final class ResourceAudio {
 public static byte[] wav(Path data,Path input,double start,double duration)throws Exception{
  if(!Double.isFinite(start)||start<0||!Double.isFinite(duration)||duration<1||duration>29)throw new IllegalArgumentException("AUDIO_SEGMENT_RANGE");byte[] pcm;
  try(var source=AudioSystem.getAudioInputStream(input.toFile())){var target=new AudioFormat(16000,16,1,true,false);try(var converted=AudioSystem.getAudioInputStream(target,source)){long skip=(long)(start*32000);while(skip>0){long got=converted.skip(skip);if(got<=0){if(converted.read()<0)throw new IOException("AUDIO_TIME_OUTSIDE_DURATION");got=1;}skip-=got;}pcm=converted.readNBytes((int)(duration*32000));}}
  catch(UnsupportedAudioFileException|IllegalArgumentException unsupported){pcm=NativeMediaDecoder.audio(data,input,start,duration);}
  if(pcm.length<3200)throw new IOException("AUDIO_SEGMENT_EMPTY");return dev.mineagent.runtime.core.conversation.SpeechWav.encode(pcm);
 }
 private ResourceAudio(){}
}
