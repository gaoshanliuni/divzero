package dev.mineagent.runtime.core.conversation;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
/** Explicit connection-bound focus. It never discovers a 'most recent' conversation or authorizes message reads. */
public final class ConversationFocusRegistry {
    public static final class Focus {
        private final Object connection;private final UUID viewer,agent,conversation,context;private final Clock clock;private final AtomicBoolean valid=new AtomicBoolean(true);private volatile long expires;private volatile boolean nativeInput;
        private Focus(Object connection,ConversationStore.Conversation c,UUID context,long expires,Clock clock){this.connection=connection;viewer=c.playerId();agent=c.agentId();conversation=c.conversationId();this.context=context;this.expires=expires;this.clock=clock;}
        public UUID contextId(){return context;}public UUID conversationId(){return conversation;}public UUID agentId(){return agent;}public UUID viewerId(){return viewer;}public boolean nativeInput(){return nativeInput&&current();}
        public boolean current(){if(clock.millis()>=expires)valid.set(false);return valid.get();}
    }
    private final Clock clock;private final Map<UUID,Focus> selected=new HashMap<>();private final Map<Object,Set<UUID>> seen=new IdentityHashMap<>();
    public ConversationFocusRegistry(Clock clock){this.clock=Objects.requireNonNull(clock);}
    public synchronized Focus select(Object connection,ConversationStore.Conversation c,UUID context,long ttl){
        Objects.requireNonNull(connection);Objects.requireNonNull(c);Objects.requireNonNull(context);if(ttl<1||ttl>1800000)throw new IllegalArgumentException("CONVERSATION_FOCUS_TTL");
        var old=selected.get(c.playerId());if(old!=null&&old.connection==connection&&old.context.equals(context)&&old.conversation.equals(c.conversationId())&&old.agent.equals(c.agentId())&&old.current()){old.expires=clock.millis()+ttl;return old;}
        var contexts=seen.computeIfAbsent(connection,k->new HashSet<>());if(contexts.contains(context))throw new IllegalStateException("STALE_CONVERSATION_FOCUS");if(contexts.size()>=4096||selected.size()>=128&&!selected.containsKey(c.playerId()))throw new IllegalStateException("CONVERSATION_FOCUS_BUDGET");
        contexts.add(context);if(old!=null)old.valid.set(false);var value=new Focus(connection,c,context,clock.millis()+ttl,clock);selected.put(c.playerId(),value);return value;
    }
    public synchronized Optional<Focus> current(UUID viewer,Object connection){var f=selected.get(viewer);return f!=null&&f.connection==connection&&f.current()?Optional.of(f):Optional.empty();}
    public synchronized void nativeInput(UUID viewer,Object connection,UUID context,boolean enabled){var f=current(viewer,connection).filter(v->v.context.equals(context)).orElseThrow(()->new IllegalStateException("STALE_CONVERSATION_FOCUS"));f.nativeInput=enabled;}
    public synchronized void clear(UUID viewer,Object connection,UUID context){var f=selected.get(viewer);if(f!=null&&f.connection==connection&&f.context.equals(context)){f.valid.set(false);selected.remove(viewer,f);}}
    public synchronized void disconnect(UUID viewer,Object connection){var f=selected.get(viewer);if(f!=null&&f.connection==connection){f.valid.set(false);selected.remove(viewer,f);}seen.remove(connection);}
    public synchronized void clear(){selected.values().forEach(f->f.valid.set(false));selected.clear();seen.clear();}
}
