package dev.mineagent.runtime.worker.resources;
import java.nio.file.*;import java.io.*;import java.util.*;import java.util.concurrent.*;
/** Fixed decoder invocations owned by Java. Inputs are already downloaded resources, never shell scripts or network URLs. */
public final class NativeMediaDecoder {
 private static final ExecutorService IO=Executors.newVirtualThreadPerTaskExecutor();
 public static byte[] frame(Path gameData,Path file,double seconds)throws Exception{if(!Double.isFinite(seconds)||seconds<0)throw new IllegalArgumentException("VIDEO_FRAME_TIME");return run(gameData,file,List.of("-ss",Double.toString(seconds),"-i",file.toString(),"-frames:v","1","-vf","scale=min(2048\\,iw):-2","-f","image2pipe","-vcodec","png","pipe:1"),8*1024*1024);}
 public static byte[] audio(Path gameData,Path file,double start,double duration)throws Exception{if(!Double.isFinite(start)||start<0||!Double.isFinite(duration)||duration<1||duration>29)throw new IllegalArgumentException("AUDIO_SEGMENT_RANGE");return run(gameData,file,List.of("-ss",Double.toString(start),"-i",file.toString(),"-t",Double.toString(duration),"-vn","-ac","1","-ar","16000","-c:a","pcm_s16le","-f","s16le","pipe:1"),29*32000+32000);}
 private static byte[] run(Path data,Path file,List<String> args,int max)throws Exception{
  if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS))throw new IOException("MEDIA_RESOURCE_NOT_FOUND");var installation=dev.mineagent.runtime.worker.media.BundledMediaTools.windowsX64(data.resolve("media-tools")).ensureInstalled();var command=new ArrayList<String>();command.add(installation.ffmpeg().toString());command.addAll(List.of("-nostdin","-v","error","-protocol_whitelist","file,pipe"));command.addAll(args);var process=new ProcessBuilder(command).directory(file.getParent().toFile()).start();process.getOutputStream().close();
  var stdout=CompletableFuture.supplyAsync(()->{try(var input=process.getInputStream()){var bytes=input.readNBytes(max+1);if(bytes.length>max){process.destroyForcibly();throw new IOException("MEDIA_DECODE_SIZE");}return bytes;}catch(Exception e){throw new CompletionException(e);}},IO);
  var stderr=CompletableFuture.runAsync(()->{try(var input=process.getErrorStream()){byte[] buffer=new byte[4096];while(input.read(buffer)>=0){}}catch(IOException ignored){}});
  try{if(!process.waitFor(25,TimeUnit.SECONDS))throw new IOException("MEDIA_DECODE_TIMEOUT");byte[] bytes=stdout.get(2,TimeUnit.SECONDS);if(process.exitValue()!=0||bytes.length==0)throw new IOException("MEDIA_FORMAT_UNSUPPORTED_OR_EMPTY");return bytes;}finally{if(process.isAlive())process.destroyForcibly();stdout.cancel(true);stderr.cancel(true);}
 }
 private NativeMediaDecoder(){}
}
