package dev.mineagent.runtime.core.config;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Function;

/** Local presentation only. `mark` formats the AI-name hover, never prepends visible text. */
public final class ChatMessageDisplay {
    public static final int DEFAULT_LIMIT=1024,MAX_LIMIT=16384;
    public static final String DEFAULT_MARK="time";
    private static final Map<String,Function<ZonedDateTime,String>> CACHE=new LinkedHashMap<>();
    public record State(int limit,String mark,long revision){
        public State{requireLimit(limit);validateMark(mark);if(revision<0)throw new IllegalArgumentException("CHAT_MESSAGES_REVISION");}
    }
    public static void requireLimit(int limit){if(limit<1||limit>MAX_LIMIT)throw new IllegalArgumentException("CHAT_MESSAGES_LIMIT_1_16384");}
    public static void validateMark(String mark){formatter(mark);}
    public static String hover(String mark,Instant receivedAt,ZoneId zone){return formatter(mark).apply(receivedAt.atZone(zone));}
    private static synchronized Function<ZonedDateTime,String> formatter(String mark){
        if(mark==null||mark.isBlank()||mark.length()>256||mark.codePoints().anyMatch(Character::isISOControl))throw new IllegalArgumentException("CHAT_MESSAGES_MARK_INVALID");
        var found=CACHE.get(mark);if(found!=null)return found;
        String pattern=switch(mark){case "time","datetime"->"yyyy-MM-dd HH:mm:ss";case "date"->"yyyy-MM-dd";case "off"->"";default->mark;};
        Function<ZonedDateTime,String> result;
        try{
            if(pattern.isEmpty())result=t->"";
            else if(pattern.indexOf('{')<0&&pattern.indexOf('}')<0){var fmt=DateTimeFormatter.ofPattern(pattern,Locale.ROOT);result=fmt::format;}
            else{
                var parts=new ArrayList<Function<ZonedDateTime,String>>();int at=0;
                while(at<pattern.length()){
                    int open=pattern.indexOf('{',at);String literal=pattern.substring(at,open<0?pattern.length():open);
                    if(literal.indexOf('}')>=0)throw new IllegalArgumentException();parts.add(t->literal);if(open<0)break;
                    int end=pattern.indexOf('}',open+1);if(end<0)throw new IllegalArgumentException();String token=pattern.substring(open+1,end);
                    if(token.isEmpty()||token.indexOf('{')>=0)throw new IllegalArgumentException();
                    var fmt=DateTimeFormatter.ofPattern(token,Locale.ROOT);parts.add(fmt::format);at=end+1;
                }
                result=t->{var text=new StringBuilder();for(var part:parts)text.append(part.apply(t));return text.toString();};
            }
            if(result.apply(Instant.parse("2026-09-25T12:34:56Z").atZone(ZoneId.of("UTC"))).length()>1024)throw new IllegalArgumentException();
        }catch(RuntimeException invalid){throw new IllegalArgumentException("CHAT_MESSAGES_MARK_INVALID",invalid);}
        if(CACHE.size()>=64)CACHE.remove(CACHE.keySet().iterator().next());CACHE.put(mark,result);return result;
    }
    private ChatMessageDisplay(){}
}
