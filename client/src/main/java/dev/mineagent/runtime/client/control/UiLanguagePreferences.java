package dev.mineagent.runtime.client.control;
import java.nio.file.*;import java.nio.charset.StandardCharsets;import java.util.*;
/** Per-client UI only. Never changes Minecraft locale, model messages or world settings. */
public final class UiLanguagePreferences {
 public static final Set<String> LANGUAGES=Set.of("zh_cn","en_us");public record State(String language,long revision){}
 private final Path file;private State state;
 public UiLanguagePreferences(Path file)throws Exception{this.file=file;state=load();}
 private State load()throws Exception{if(!Files.exists(file))return new State("zh_cn",0);if(Files.isSymbolicLink(file)||Files.size(file)>4096)throw new IllegalArgumentException("UI_LANGUAGE_SETTINGS_INVALID");var p=new Properties();try(var r=Files.newBufferedReader(file,StandardCharsets.UTF_8)){p.load(r);}if(!Set.of("language","revision").containsAll(p.stringPropertyNames()))throw new IllegalArgumentException("UI_LANGUAGE_SETTINGS_INVALID");String lang=p.getProperty("language","zh_cn");long rev=Long.parseLong(p.getProperty("revision","0"));if(!LANGUAGES.contains(lang)||rev<0)throw new IllegalArgumentException("UI_LANGUAGE_SETTINGS_INVALID");return new State(lang,rev);}
 public synchronized State state(){return state;}
 public synchronized State save(long expected,String language)throws Exception{if(!LANGUAGES.contains(language))throw new IllegalArgumentException("UI_LANGUAGE_INVALID");var current=load();if(expected!=current.revision()){state=current;throw new IllegalStateException("UI_LANGUAGE_STALE");}var next=new State(language,Math.addExact(current.revision(),1));var parent=file.toAbsolutePath().getParent();Files.createDirectories(parent);var temp=Files.createTempFile(parent,"ui-language-",".tmp");try{Files.writeString(temp,"language="+language+"\nrevision="+next.revision()+"\n",StandardCharsets.UTF_8);try{Files.move(temp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException ex){Files.move(temp,file,StandardCopyOption.REPLACE_EXISTING);}state=next;return next;}finally{Files.deleteIfExists(temp);}}
}
