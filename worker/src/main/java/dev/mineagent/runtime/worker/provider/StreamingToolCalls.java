package dev.mineagent.runtime.worker.provider;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
/** Reassembles real OpenAI-compatible SSE tool deltas; incomplete/oversized calls are never executable. */
public final class StreamingToolCalls {
    private static final class Call{StringBuilder id=new StringBuilder(),name=new StringBuilder(),args=new StringBuilder();}
    private final Map<Integer,Call> calls=new TreeMap<>();private int length;
    public void accept(JsonNode delta){if(delta.has("tool_calls")&&!delta.path("tool_calls").isNull()&&!delta.path("tool_calls").isArray())throw new IllegalArgumentException("TOOL_STREAM_TYPE");for(var part:delta.path("tool_calls")){if(!part.path("index").isIntegralNumber()||!part.path("index").canConvertToInt())throw new IllegalArgumentException("TOOL_STREAM_INDEX");int index=part.path("index").asInt();if(index<0||index>=dev.mineagent.runtime.core.conversation.ConversationTools.MAX_CALLS_PER_ROUND)throw new IllegalArgumentException("TOOL_STREAM_BUDGET");var c=calls.computeIfAbsent(index,k->new Call());append(c.id,part.path("id"));append(c.name,part.path("function").path("name"));append(c.args,part.path("function").path("arguments"));if(c.id.length()>160||c.name.length()>64||c.args.length()>16384||length>32768)throw new IllegalArgumentException("TOOL_STREAM_BUDGET");}}
    private void append(StringBuilder out,JsonNode n){if(n.isMissingNode()||n.isNull())return;if(!n.isTextual())throw new IllegalArgumentException("TOOL_STREAM_TYPE");out.append(n.textValue());length+=n.textValue().length();}
    public List<ToolCall> finish(){var result=new ArrayList<ToolCall>();var ids=new HashSet<String>();for(var c:calls.values()){String id=c.id.toString(),name=c.name.toString(),args=c.args.toString();if(id.isBlank()||!ids.add(id)||!name.matches("[a-z_]{1,64}")||args.isBlank())throw new IllegalArgumentException("TOOL_STREAM_INCOMPLETE");result.add(new ToolCall(id,name,args));}return List.copyOf(result);}
}
