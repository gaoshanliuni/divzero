package dev.mineagent.runtime.client.control;
import java.nio.file.*;import java.nio.charset.StandardCharsets;import java.util.*;
/** Local display preference only; never changes Provider thinking or stored conversation history. */
public final class NativeChatPreferences {
 public record State(boolean showThinking,long revision){}
 private final Path file;private State state;
 public NativeChatPreferences(Path file)throws Exception{this.file=file;state=load();}
 private State load()throws Exception{if(!Files.exists(file))return new State(true,0);if(Files.isSymbolicLink(file)||Files.size(file)>4096)throw new IllegalArgumentException("NATIVE_CHAT_SETTINGS_INVALID");var p=new Properties();try(var r=Files.newBufferedReader(file,StandardCharsets.UTF_8)){p.load(r);}String value=p.getProperty("showThinking","true");long rev=Long.parseLong(p.getProperty("revision","0"));if(!Set.of("true","false").contains(value)||rev<0)throw new IllegalArgumentException("NATIVE_CHAT_SETTINGS_INVALID");return new State(Boolean.parseBoolean(value),rev);}
 public synchronized State state(){return state;}
 public synchronized State save(long expected,boolean value)throws Exception{var current=load();if(expected!=current.revision){state=current;throw new IllegalStateException("NATIVE_CHAT_SETTINGS_STALE");}var next=new State(value,Math.addExact(current.revision,1));Files.createDirectories(file.toAbsolutePath().getParent());Path temp=Files.createTempFile(file.toAbsolutePath().getParent(),"native-chat-",".tmp");try{Files.writeString(temp,"showThinking="+value+"\nrevision="+next.revision+"\n",StandardCharsets.UTF_8);try{Files.move(temp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException e){Files.move(temp,file,StandardCopyOption.REPLACE_EXISTING);}state=next;return next;}finally{Files.deleteIfExists(temp);}}
}
