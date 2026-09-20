package dev.mineagent.runtime.client.conversation;
import java.util.*;
import java.util.function.BooleanSupplier;
/** Client routing only, not authority. Snapshot identity cancels buffered and already queued private voice. */
public final class ConversationAudioScope {
    private record Selected(Object connection,UUID world,UUID agent,UUID conversation,UUID context){}
    private volatile Selected selected;
    public void select(Object connection,UUID world,UUID agent,UUID conversation,UUID context){selected=new Selected(Objects.requireNonNull(connection),Objects.requireNonNull(world),Objects.requireNonNull(agent),Objects.requireNonNull(conversation),Objects.requireNonNull(context));}
    public BooleanSupplier permit(Object connection,UUID world,UUID agent,UUID conversation,UUID context){var captured=selected;return ()->captured!=null&&selected==captured&&captured.connection==connection&&captured.world.equals(world)&&captured.agent.equals(agent)&&captured.conversation.equals(conversation)&&captured.context.equals(context);}
    public void clear(UUID context){var current=selected;if(current!=null&&(context==null||current.context.equals(context)))selected=null;}
}
