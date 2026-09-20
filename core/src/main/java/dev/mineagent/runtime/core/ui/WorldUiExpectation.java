package dev.mineagent.runtime.core.ui;
import com.fasterxml.jackson.databind.*;
import java.util.*;
/** Bounded JSON-pointer equality over the server projection plus visible UI text. No code or authority in conditions. */
public final class WorldUiExpectation {
    private static final ObjectMapper JSON=new ObjectMapper();private final Map<String,JsonNode> equals;private final String text;
    private WorldUiExpectation(Map<String,JsonNode> equals,String text){this.equals=Map.copyOf(equals);this.text=text;}
    public static WorldUiExpectation parse(String value){try{
        var root=JSON.readTree(WorldUiData.normalize(value));var names=new HashSet<String>();root.fieldNames().forEachRemaining(names::add);
        var fields=root.path("equals");var text=root.path("text");if(!root.isObject()||!names.equals(Set.of("equals","text"))||!fields.isObject()||fields.isEmpty()||fields.size()>16||!text.isTextual()||text.asText().isBlank()||text.asText().length()>256)throw new IllegalArgumentException();
        var matches=new LinkedHashMap<String,JsonNode>();for(var e:fields.properties()){
            if(e.getKey().length()>256||!e.getKey().matches("/(?:[^~]|~[01])*")||!e.getValue().isValueNode()||e.getValue().isNumber()&&!Double.isFinite(e.getValue().asDouble()))throw new IllegalArgumentException();
            com.fasterxml.jackson.core.JsonPointer.compile(e.getKey());matches.put(e.getKey(),e.getValue());
        }return new WorldUiExpectation(matches,space(text.asText()));
    }catch(Exception invalid){throw new IllegalArgumentException("WORLD_UI_EXPECTATION",invalid);}}
    public boolean matchesProjection(String value){try{var data=JSON.readTree(WorldUiData.normalize(value));for(var e:equals.entrySet()){var actual=data.at(e.getKey());var expected=e.getValue();if(actual.isMissingNode()||!(actual.isNumber()&&expected.isNumber()?actual.decimalValue().compareTo(expected.decimalValue())==0:actual.equals(expected)))return false;}return true;}catch(Exception invalid){return false;}}
    public boolean matchesUi(String observation){try{return observation!=null&&observation.length()<=65536&&space(JSON.readTree(observation).path("visibleText").asText()).contains(text);}catch(Exception invalid){return false;}}
    private static String space(String value){return value.strip().replaceAll("(?U)\\s+"," ");}
    public String text(){return text;}
}
