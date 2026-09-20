package dev.mineagent.runtime.neoforge.client.audio;

import dev.mineagent.runtime.client.conversation.ConversationAudioScope;
import dev.mineagent.runtime.client.audio.VoiceChunkAssembler;
import dev.mineagent.runtime.neoforge.network.MineAgentPayloads.ConversationVoiceChunk;
import net.minecraft.client.Minecraft;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/** Explicitly requested, private voice only. Cached replay is local and never resynthesizes. */
public final class ConversationVoicePlayback {
    private static final ConversationAudioScope SCOPE=new ConversationAudioScope();
    private static final LinkedHashMap<UUID,Entry> entries=new LinkedHashMap<>();
    private static volatile UUID context;private static volatile Object connection;private static volatile long scopeGeneration;
    private static final class Entry {
        final UUID operation;final java.util.function.BooleanSupplier current;final AtomicLong generation=new AtomicLong();
        final VoiceChunkAssembler assembler=new VoiceChunkAssembler(8*1024*1024,512,Clock.systemUTC());
        long deadline=System.currentTimeMillis()+90000;String hash="",state="REQUESTED",error="";byte[] audio;PrivateVoicePlayer.Playback playback;
        Entry(UUID operation,java.util.function.BooleanSupplier current){this.operation=operation;this.current=current;}
    }
    private ConversationVoicePlayback(){}
    public static synchronized void focus(UUID world,UUID agent,UUID conversation,UUID nextContext){
        var c=Minecraft.getInstance().getConnection();if(c==null)return;
        if(connection==c&&Objects.equals(context,nextContext)&&SCOPE.permit(c,world,agent,conversation,nextContext).getAsBoolean())return;
        clear(null);SCOPE.select(c,world,agent,conversation,nextContext);context=nextContext;connection=c;
    }
    public static synchronized void clear(UUID expected){
        if(expected!=null&&!expected.equals(context))return;
        for(var e:entries.values())stop(e);entries.clear();SCOPE.clear(expected);scopeGeneration++;context=null;connection=null;
    }
    private static void stop(Entry e){e.generation.incrementAndGet();if(e.playback!=null)e.playback.cancel();e.assembler.clear();e.state="STOPPED";e.error="";}
    private static void requireContext(UUID expected){if(context==null||!context.equals(expected)||connection!=Minecraft.getInstance().getConnection())throw new IllegalStateException("CONVERSATION_AUDIO_CONTEXT_STALE");}
    public static synchronized Map<String,Object> stopOperations(UUID expected,List<UUID> operations){
        requireContext(expected);if(operations.size()>32)throw new IllegalArgumentException("AUDIO_STOP_BUDGET");
        for(var id:operations){var entry=entries.get(id);if(entry!=null)stop(entry);}return control(expected,"state",null);
    }
    public static synchronized Map<String,Object> control(UUID expected,String action,UUID operation){
        requireContext(expected);prune();
        switch(action){
            case "prepare"->{Objects.requireNonNull(operation);if(entries.containsKey(operation))throw new IllegalArgumentException("CONVERSATION_AUDIO_OPERATION_REUSED");
                for(var e:entries.values())if(Set.of("REQUESTED","RECEIVING","QUEUED","PLAYING").contains(e.state))stop(e);
                while(entries.size()>=16){var oldest=entries.entrySet().iterator().next();stop(oldest.getValue());entries.remove(oldest.getKey());}
                var c=Minecraft.getInstance().getConnection();UUID captured=context;long capturedGeneration=scopeGeneration;entries.put(operation,new Entry(operation,()->scopeGeneration==capturedGeneration&&connection==c&&Objects.equals(context,captured)&&Minecraft.getInstance().getConnection()==c));
            }
            case "stop"->{if(operation==null){for(var e:entries.values())stop(e);}else{var e=entries.get(operation);if(e!=null)stop(e);}}
            case "replay"->{var e=entries.get(Objects.requireNonNull(operation));if(e==null||e.audio==null)throw new IllegalStateException("CONVERSATION_AUDIO_CACHE_MISSING");for(var other:entries.values())if(other!=e&&Set.of("PLAYING","QUEUED","REQUESTED","RECEIVING").contains(other.state))stop(other);play(e);}
            case "state"->{}
            default->throw new IllegalArgumentException("CONVERSATION_AUDIO_ACTION");
        }
        return Map.of("contextId",context,"entries",entries.values().stream().map(e->Map.of("operationId",e.operation,"state",e.state,"errorCode",e.error,"cached",e.audio!=null)).toList());
    }
    private static void prune(){
        long now=System.currentTimeMillis();for(var e:entries.values())if(Set.of("REQUESTED","RECEIVING").contains(e.state)&&now>e.deadline){stop(e);e.state="FAILED";e.error="AUDIO_TRANSFER_EXPIRED";}
    }
    public static synchronized void accept(ConversationVoiceChunk p,Object source){
        var c=Minecraft.getInstance().getConnection();if(c==null||c.getConnection()!=source)return;
        if(!SCOPE.permit(c,p.worldId(),p.agentId(),p.conversationId(),p.contextId()).getAsBoolean())return;
        prune();var e=entries.get(p.operationId());if(e==null||!e.current.getAsBoolean()||!Set.of("REQUESTED","RECEIVING").contains(e.state))return;
        if(!e.hash.isEmpty()&&!e.hash.equals(p.sha256())){stop(e);e.state="FAILED";e.error="AUDIO_TRANSFER_CHANGED";return;}
        e.hash=p.sha256();e.state="RECEIVING";
        try{e.assembler.accept(p.sha256(),p.index(),p.count(),p.data()).ifPresent(audio->{
            e.audio=audio;e.state="RECEIVED";
            long total=entries.values().stream().filter(v->v.audio!=null).mapToLong(v->v.audio.length).sum();
            for(var older:entries.values())if(total>32L*1024*1024&&older!=e&&older.audio!=null){total-=older.audio.length;older.audio=null;}
            play(e);
        });}catch(IllegalArgumentException invalid){stop(e);e.state="FAILED";e.error="AUDIO_TRANSFER_INVALID";}
    }
    private static void play(Entry e){
        if(e.playback!=null)e.playback.cancel();long token=e.generation.incrementAndGet();
        e.playback=PrivateVoicePlayer.play(e.audio,()->e.current.getAsBoolean()&&e.generation.get()==token,(state,error)->{
            synchronized(ConversationVoicePlayback.class){if(e.current.getAsBoolean()&&e.generation.get()==token&&entries.get(e.operation)==e){e.state=state;e.error=error;}}
        });
    }
}
