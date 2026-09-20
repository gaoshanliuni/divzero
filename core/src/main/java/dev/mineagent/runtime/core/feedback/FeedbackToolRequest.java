package dev.mineagent.runtime.core.feedback;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** No model-selected author, delivery, task or conversation. All tools act on the current Native trigger binding. */
public record FeedbackToolRequest(String tool,String text,UUID replyOperation,String canonical) {
    public static final Set<String> TOOLS=Set.of("inspect_feedback","reply_feedback");
    public static final Set<String> ALLOWED=Set.of("inspect_feedback","reply_feedback","finish_task");
    private static final ObjectMapper JSON=new ObjectMapper(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build()).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    public static FeedbackToolRequest parse(String tool,String raw){try{
        if(!ALLOWED.contains(tool)||raw==null||raw.length()>16384)throw new IllegalArgumentException();var n=JSON.readTree(raw);if(!n.isObject())throw new IllegalArgumentException();String text="";UUID operation=null;
        if(tool.equals("inspect_feedback")){if(!n.isEmpty())throw new IllegalArgumentException();}
        else if(tool.equals("reply_feedback")){if(n.size()!=1||!n.path("text").isTextual()||(text=n.get("text").asText()).isBlank()||text.getBytes(StandardCharsets.UTF_8).length>8192)throw new IllegalArgumentException();}
        else{if(n.size()!=1||!n.path("checks").isArray()||n.get("checks").size()!=1)throw new IllegalArgumentException();var check=n.get("checks").get(0);if(!check.isObject()||check.size()!=2||!check.path("kind").asText().equals("feedback_reply")||!check.path("operation_id").isTextual())throw new IllegalArgumentException();operation=UUID.fromString(check.get("operation_id").asText());}
        return new FeedbackToolRequest(tool,text,operation,n.toString());
    }catch(Exception e){throw new IllegalArgumentException("FEEDBACK_TOOL_ARGUMENTS",e);}}
    public static void requireAllowed(Object calls){if(!(calls instanceof List<?> list)||list.isEmpty()||list.size()>16||list.stream().anyMatch(c->!(c instanceof Map<?,?> m)||!ALLOWED.contains(String.valueOf(m.get("name")))))throw new IllegalArgumentException("FEEDBACK_TOOL_SCOPE");}
}
