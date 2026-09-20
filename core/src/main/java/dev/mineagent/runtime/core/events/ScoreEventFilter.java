package dev.mineagent.runtime.core.events;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Native numeric score predicates. A holder is an opaque score key, never an asserted player or modifier. */
public record ScoreEventFilter(String objective,String criteria,List<String> holders,String test,Integer value,String matchMode,String replacementPolicy){
    public static final String SOURCE="SCORE_CHANGED";
    public static final Set<String> FIELDS=Set.of("objective","criteria","holders","when","match_mode","replacement_policy");
    public ScoreEventFilter{
        name(objective,16);name(criteria,128);holders=holders.stream().sorted().toList();holders.forEach(h->name(h,40));
        if(holders.size()>64||new HashSet<>(holders).size()!=holders.size()||!Set.of("ANY_CHANGE","EXISTS","ABSENT","EQ","NEQ","LT","LTE","GT","GTE").contains(test)
                ||Set.of("ANY_CHANGE","EXISTS","ABSENT").contains(test)!=(value==null)||!Set.of("ON_CHANGE","BECOMES_TRUE").contains(matchMode)||test.equals("ANY_CHANGE")&&!matchMode.equals("ON_CHANGE")||!Set.of("PAUSE","REBASE").contains(replacementPolicy))throw new IllegalArgumentException("SCORE_EVENT_FILTER");
    }
    public static String name(String value,int maximum){if(value==null||value.isBlank()||value.length()>maximum||value.chars().anyMatch(Character::isISOControl))throw new IllegalArgumentException("SCORE_EVENT_NAME");return value;}
    public boolean selects(String holder){return holders.isEmpty()||holders.contains(holder);}
    public boolean predicate(Integer current){return switch(test){case "ANY_CHANGE"->true;case "EXISTS"->current!=null;case "ABSENT"->current==null;case "EQ"->current!=null&&current.intValue()==value.intValue();case "NEQ"->current!=null&&current.intValue()!=value.intValue();case "LT"->current!=null&&current<value;case "LTE"->current!=null&&current<=value;case "GT"->current!=null&&current>value;case "GTE"->current!=null&&current>=value;default->false;};}
    public boolean matches(Integer before,Integer after){return !Objects.equals(before,after)&&predicate(after)&&(!matchMode.equals("BECOMES_TRUE")||!predicate(before));}
    public static ScoreEventFilter parse(JsonNode node){
        var holders=new ArrayList<String>();if(node.has("holders")){if(!node.get("holders").isArray()||node.get("holders").size()>64)throw new IllegalArgumentException("SCORE_EVENT_HOLDERS");for(var h:node.get("holders")){if(!h.isTextual())throw new IllegalArgumentException("SCORE_EVENT_HOLDERS");holders.add(h.textValue());}}
        String test="ANY_CHANGE";Integer value=null;if(node.has("when")){var when=node.get("when");if(!when.isObject()||!when.has("test")||when.size()>(when.has("value")?2:1))throw new IllegalArgumentException("SCORE_EVENT_WHEN");test=text(when,"test","");
            if(when.has("value")){if(!when.get("value").isIntegralNumber()||!when.get("value").canConvertToInt())throw new IllegalArgumentException("SCORE_EVENT_VALUE");value=when.get("value").intValue();}}
        return new ScoreEventFilter(text(node,"objective",""),text(node,"criteria",""),holders,test,value,text(node,"match_mode","ON_CHANGE"),text(node,"replacement_policy","PAUSE"));
    }
    public Map<String,Object> wire(){var data=new LinkedHashMap<String,Object>();data.put("objective",objective);data.put("criteria",criteria);data.put("holders",holders);var when=new LinkedHashMap<String,Object>();when.put("test",test);if(value!=null)when.put("value",value);data.put("when",when);data.put("match_mode",matchMode);data.put("replacement_policy",replacementPolicy);return data;}
    public record Change(UUID subscriptionId,long subscriptionRevision,ScoreEventFilter filter,long incarnation,String authorityHash,String holder,Integer before,Integer after,long observedBefore,long observedAfter){
        public Change{Objects.requireNonNull(subscriptionId);Objects.requireNonNull(filter);name(holder,40);
            if(subscriptionRevision<1||incarnation<0||authorityHash==null||!authorityHash.matches("[a-f0-9]{64}")||!filter.selects(holder)||!filter.matches(before,after)||observedBefore<0||observedAfter<observedBefore)throw new IllegalArgumentException("SCORE_EVENT_CHANGE");}
    }
    public static UUID systemAuthor(UUID world){return UUID.nameUUIDFromBytes((world+"|native-score-observer-v1").getBytes(StandardCharsets.UTF_8));}
    public static boolean matches(RuntimeEventStore.Subscription sub,RuntimeEventStore.Event event){var change=event.score();return change!=null&&sub.request().score()!=null&&sub.id().equals(change.subscriptionId())&&sub.revision()==change.subscriptionRevision()&&sub.request().score().equals(change.filter());}
    private static String text(JsonNode node,String key,String fallback){if(!node.has(key))return fallback;if(!node.get(key).isTextual())throw new IllegalArgumentException("SCORE_EVENT_TEXT");return node.get(key).textValue();}
}
