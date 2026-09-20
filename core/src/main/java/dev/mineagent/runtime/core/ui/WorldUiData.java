package dev.mineagent.runtime.core.ui;
import com.fasterxml.jackson.databind.*;
import java.util.*;

/** Bounded data only: user payloads and explicit script replies, never code to evaluate. */
public final class WorldUiData {
    private static final ObjectMapper JSON=new ObjectMapper(com.fasterxml.jackson.core.JsonFactory.builder().enable(com.fasterxml.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION).streamReadConstraints(com.fasterxml.jackson.core.StreamReadConstraints.builder().maxNestingDepth(16).maxStringLength(8192).build()).build());
    private WorldUiData(){}
    public static String normalize(String data){try{
        if(data==null||data.length()>8192)throw new IllegalArgumentException();var node=JSON.readTree(data);if(node==null)throw new IllegalArgumentException();
        var pending=new ArrayDeque<JsonNode>();pending.add(node);int count=0;while(!pending.isEmpty()){var n=pending.removeFirst();if(++count>2048)throw new IllegalArgumentException();n.elements().forEachRemaining(pending::addLast);}
        String result=JSON.writeValueAsString(node);if(result.length()>8192)throw new IllegalArgumentException();return result;
    }catch(Exception invalid){throw new IllegalArgumentException("WORLD_UI_DATA_INVALID",invalid);}}
}
