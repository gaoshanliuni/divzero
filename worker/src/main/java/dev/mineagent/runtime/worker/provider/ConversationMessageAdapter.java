package dev.mineagent.runtime.worker.provider;

import java.net.URI;
import java.util.*;

/** Explicit wire contracts; the archive keeps the original message including provider reasoning. */
public final class ConversationMessageAdapter {
    private ConversationMessageAdapter(){}
    public static Map<String,Object> adapt(Map<String,Object> original,URI endpoint,String model,boolean thinking){
        var value=new LinkedHashMap<String,Object>(original);String role=String.valueOf(value.get("role"));
        if(!Set.of("system","developer","user","assistant","tool").contains(role))throw new IllegalArgumentException("MODEL_MESSAGE_ROLE");
        String host=Objects.toString(endpoint.getHost(),"").toLowerCase(Locale.ROOT),name=model.toLowerCase(Locale.ROOT);
        boolean openai=host.equals("api.openai.com")||host.endsWith(".openai.azure.com");
        if(role.equals("system")||role.equals("developer"))value.put("role",openai&&name.matches("o[134](?:-.*)?")?"developer":"system");
        if(role.equals("assistant")){
            if(openai)value.remove("reasoning_content");
            else if(thinking&&(host.equals("api.deepseek.com")||host.endsWith(".deepseek.com"))&&value.containsKey("tool_calls"))value.putIfAbsent("reasoning_content","");
        }
        return Collections.unmodifiableMap(value);
    }
}
