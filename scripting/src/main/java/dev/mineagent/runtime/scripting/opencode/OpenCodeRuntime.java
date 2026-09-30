package dev.mineagent.runtime.scripting.opencode;

import com.fasterxml.jackson.databind.*;
import dev.mineagent.runtime.scripting.RhinoScriptRuntime;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/**
 * Executes the reviewed, vendored OpenCode pure functions. No OpenCode shell, agent,
 * permission service or filesystem discovery is enabled by this adapter.
 */
public final class OpenCodeRuntime {
    public static final String COMMIT="2fa3363c924c5c3e367b84a87ae478296a0ed59b";
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final String SOURCE=resource("runtime.js");
    private OpenCodeRuntime(){}
    private static String resource(String name){
        try(var in=OpenCodeRuntime.class.getResourceAsStream("/dev/mineagent/runtime/opencode/"+name)){
            if(in==null)throw new IllegalStateException("OPENCODE_RESOURCE_MISSING");
            return new String(in.readAllBytes(),StandardCharsets.UTF_8);
        }catch(java.io.IOException error){throw new IllegalStateException("OPENCODE_RESOURCE_READ",error);}
    }
    private static JsonNode execute(String expression,Map<String,Object> input){
        try{
            String code=SOURCE+"\nconst input = JSON.parse(inputJson);\n("+expression+");";
            Object result=new RhinoScriptRuntime(Duration.ofMillis(500)).evaluate(code,Map.of("inputJson",JSON.writeValueAsString(input)));
            return result instanceof Boolean flag?JSON.getNodeFactory().booleanNode(flag):JSON.getNodeFactory().textNode(Objects.toString(result));
        }catch(Exception error){throw new IllegalStateException("OPENCODE_CONTEXT_POLICY_FAILED",error);}
    }
    public static String skill(String name,String content,String base,List<String> resources){
        var info=Map.of("name",name,"content",content);
        return execute("OpenCodeCompat.renderSkill(input.info,input.base,input.files)",Map.of("info",info,"base",base,"files",resources.stream().map(path->Map.of("path",path)).toList())).asText();
    }
    public static String summaryPrompt(String previous,List<String> context){
        return execute("OpenCodeCompat.buildPrompt(input)",Map.of("previousSummary",previous==null?"":previous,"context",context)).asText();
    }
    public record Selection(String archived,String recent){}
    /** Each entry is already an atomic protocol group, including required reasoning and tool results. */
    public static Selection select(List<String> groups,int tokens){
        var result=execute("(() => { const s=OpenCodeCompat.selectRendered(input.groups,input.tokens) || {head:'',recent:''}; return encodeURIComponent(s.head)+'|'+encodeURIComponent(s.recent); })()",Map.of("groups",groups,"tokens",tokens)).asText().split("\\|",-1);
        return new Selection(java.net.URLDecoder.decode(result[0],StandardCharsets.UTF_8),java.net.URLDecoder.decode(result[1],StandardCharsets.UTF_8));
    }
    /** Caller must additionally compare actual context, result and progress; repeated input alone is not a deadlock. */
    public static boolean repeatedCalls(List<Map<String,Object>> recent,String name,Map<String,Object> arguments){
        return execute("OpenCodeCompat.repeats(input.parts,{name:input.name},input.args,3)",Map.of("parts",recent,"name",name,"args",arguments)).asBoolean();
    }
}
