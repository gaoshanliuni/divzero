package dev.mineagent.runtime.core.interaction;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class AgentMentionTest {
    @Test void nativeCompletionAndQuotedNamesRoundTrip(){var names=List.of("小明","AI Helper","A\"B");for(var name:names){var t=AgentMention.token(name);var m=AgentMention.resolve(t+" 你好",names);assertEquals(1,m.size());assertEquals(name,m.getFirst().name());assertEquals("你好",m.getFirst().message());}assertTrue(AgentMention.complete("@",1,names).orElseThrow().values().contains("@小明 "));assertTrue(AgentMention.complete("@ai 小",5,names).orElseThrow().values().contains("@ai 小明 "));}
    @Test void noSubstringEmailOrCommandHijack(){assertTrue(AgentMention.resolve("x@Alex.org",List.of("Alex")).isEmpty());assertTrue(AgentMention.resolve("@Alexa 你好",List.of("Alex")).isEmpty());assertTrue(AgentMention.complete("/say @Alex",10,List.of("Alex")).isEmpty());assertEquals("Alex One",AgentMention.resolve("@Alex One 你好",List.of("Alex","Alex One")).getFirst().name());}
    @Test void multipleTargetsRemainExplicit(){assertEquals(2,AgentMention.resolve("@A @B 你好",List.of("A","B")).size());assertEquals(3,AgentMention.complete("hi @小",5,List.of("小明")).orElseThrow().start());}
    @Test void namesCanBeTypedWithoutManuallyQuoting(){
        assertEquals(List.of("@\"AI Helper\" "),AgentMention.complete("@AI H",5,List.of("AI Helper")).orElseThrow().values());
        assertEquals(List.of("@ai \"AI Helper\" "),AgentMention.complete("@ai AI H",8,List.of("AI Helper")).orElseThrow().values());
        assertEquals(List.of("@\"AI @Helper\" "),AgentMention.complete("@\"AI @H",7,List.of("AI @Helper","Helper")).orElseThrow().values());
        assertEquals(List.of("@\"AI Helper\" ","@小明 "),AgentMention.complete("@",1,List.of("小明","AI Helper","小明")).orElseThrow().values());
    }
    @Test void completedNamesAndNonMentionsLeaveVanillaAlone(){
        for(String text:List.of("hello","hello@小","@小明 你好","@小明 ","/say @","/tell 小","@未知"))assertTrue(AgentMention.complete(text,text.length(),List.of("小明")).isEmpty(),text);
        assertTrue(AgentMention.complete("@",-1,List.of("小明")).isEmpty());
        assertTrue(AgentMention.complete("@",2,List.of("小明")).isEmpty());
        assertTrue(AgentMention.complete(null,0,List.of("小明")).isEmpty());
    }
    @Test void completesCatalogBeyondOldSixtyFourLimit(){
        var names=java.util.stream.IntStream.range(0,257).mapToObj(i->String.format(Locale.ROOT,"助手%03d",i)).toList();
        assertEquals(257,AgentMention.complete("@",1,names).orElseThrow().values().size());
        assertEquals(List.of("@助手256 "),AgentMention.complete("@助手256",6,names).orElseThrow().values());
        assertEquals("助手256",AgentMention.resolve("@助手256 你好",names).getFirst().name());
    }
}
