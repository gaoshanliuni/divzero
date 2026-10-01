package dev.mineagent.runtime.core.conversation;
import java.util.*;
/** Public display is a projection of this reply only, without private reasoning or requester controls. */
public final class PublicMentionUpdate {
    public static Map<String,Object> of(Map<String,Object> owner){var out=new LinkedHashMap<String,Object>();for(String key:List.of("agent","name","bodyOffset","body","done","state","color","nativeParts","nativeAllowed"))if(owner.containsKey(key))out.put(key,owner.get(key));out.put("thinkingOffset",0);out.put("thinking","");out.put("observer",true);return out;}
    private PublicMentionUpdate(){}
}
