package dev.mineagent.runtime.client.control;
import dev.mineagent.runtime.core.config.ChatMessageDisplay;
import java.nio.file.*;import java.nio.charset.StandardCharsets;import java.util.Properties;
/** Atomic local preference file; unrelated thinking/Provider settings are not touched. */
public final class ChatMessagePreferences {
    private final Path file;private volatile ChatMessageDisplay.State state;
    public ChatMessagePreferences(Path file)throws Exception{this.file=file;state=load();}
    private ChatMessageDisplay.State load()throws Exception{
        if(!Files.exists(file))return new ChatMessageDisplay.State(ChatMessageDisplay.DEFAULT_LIMIT,ChatMessageDisplay.DEFAULT_MARK,0);
        if(Files.isSymbolicLink(file)||Files.size(file)>8192)throw new IllegalArgumentException("CHAT_MESSAGES_SETTINGS_INVALID");
        var p=new Properties();try(var reader=Files.newBufferedReader(file,StandardCharsets.UTF_8)){p.load(reader);}
        if(!java.util.Set.of("limit","mark","revision","thinking").containsAll(p.stringPropertyNames()))throw new IllegalArgumentException("CHAT_MESSAGES_SETTINGS_INVALID");
        return new ChatMessageDisplay.State(Integer.parseInt(p.getProperty("limit","1024")),p.getProperty("mark","time"),Long.parseLong(p.getProperty("revision","0")),p.getProperty("thinking","tail"));
    }
    public ChatMessageDisplay.State state(){return state;}
    public synchronized ChatMessageDisplay.State save(Long expected,Integer limit,String mark)throws Exception{return save(expected,limit,mark,null);}
    public synchronized ChatMessageDisplay.State save(Long expected,Integer limit,String mark,String thinking)throws Exception{
        var current=load();if(expected!=null&&current.revision()!=expected){state=current;throw new IllegalStateException("CHAT_MESSAGES_SETTINGS_STALE");}
        var next=new ChatMessageDisplay.State(limit==null?current.limit():limit,mark==null?current.mark():mark,Math.addExact(current.revision(),1),thinking==null?current.thinking():thinking);
        var parent=file.toAbsolutePath().getParent();Files.createDirectories(parent);var temporary=Files.createTempFile(parent,"chat-messages-",".tmp");
        try{var values=new Properties();values.setProperty("limit",Integer.toString(next.limit()));values.setProperty("mark",next.mark());values.setProperty("revision",Long.toString(next.revision()));values.setProperty("thinking",next.thinking());try(var writer=Files.newBufferedWriter(temporary,StandardCharsets.UTF_8)){values.store(writer,"AI-name hover and native chat retention only");}try{Files.move(temporary,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException e){Files.move(temporary,file,StandardCopyOption.REPLACE_EXISTING);}state=next;return next;}finally{Files.deleteIfExists(temporary);}
    }
}
