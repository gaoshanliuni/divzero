package dev.mineagent.runtime.client.conversation;

import java.util.*;

/** An uncertain send retains its original operation and arguments; reading/restoring never submits it. */
public final class PendingConversationSends {
    public record Pending(UUID operation,UUID agent,UUID conversation,String text,Map<String,String> arguments){
        public Pending{Objects.requireNonNull(operation);Objects.requireNonNull(agent);Objects.requireNonNull(conversation);arguments=Map.copyOf(arguments);if(text==null||text.isBlank()||text.length()>16384||!text.equals(arguments.get("text"))||!agent.toString().equals(arguments.get("agentId"))||!conversation.toString().equals(arguments.get("conversationId"))||!"send".equals(arguments.get("kind")))throw new IllegalArgumentException("CONVERSATION_PENDING_SEND");}
    }
    private final Map<UUID,Pending> pending=new LinkedHashMap<>();
    public Pending prepare(UUID agent,UUID conversation,long revision,String text,String speechOperation){
        if(revision<1)throw new IllegalArgumentException("CONVERSATION_SEND_REVISION");
        var old=pending.get(conversation);if(old!=null&&old.agent.equals(agent)&&old.text.equals(text))return old;
        var args=new LinkedHashMap<String,String>();args.put("kind","send");args.put("agentId",agent.toString());args.put("conversationId",conversation.toString());args.put("expectedRevision",Long.toString(revision));args.put("text",text);if(speechOperation!=null&&!speechOperation.isBlank())args.put("speechOperation",UUID.fromString(speechOperation).toString());
        var value=new Pending(UUID.randomUUID(),agent,conversation,text,args);pending.put(conversation,value);return value;
    }
    public void accepted(UUID operation){pending.values().removeIf(p->p.operation.equals(operation));}
    public List<Pending> snapshot(){return List.copyOf(pending.values());}
    public void restore(Collection<Pending> values){for(var value:values)pending.putIfAbsent(value.conversation,value);}
}
