package dev.mineagent.runtime.core.objects;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
public record WorldFootprint(int blocks,int objects) {
    public WorldFootprint{if(blocks<0||blocks>128||objects<0||objects>32||blocks+objects<1)throw new IllegalArgumentException("WORLD_FOOTPRINT");}
    public static WorldFootprint parse(JsonNode node){return new WorldFootprint(number(node,"minimum_blocks"),number(node,"minimum_objects"));}
    public static WorldFootprint parse(Map<String,String> values){return new WorldFootprint(Integer.parseInt(values.getOrDefault("minimum_blocks","0")),Integer.parseInt(values.getOrDefault("minimum_objects","0")));}
    private static int number(JsonNode node,String key){if(!node.has(key))return 0;var value=node.path(key);if(!value.isIntegralNumber()||!value.canConvertToInt())throw new IllegalArgumentException("WORLD_FOOTPRINT");return value.intValue();}
}
