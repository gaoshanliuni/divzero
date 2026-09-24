package dev.mineagent.runtime.core.config;
import org.junit.jupiter.api.Test;import java.time.*;import static org.junit.jupiter.api.Assertions.*;
class ChatMessageDisplayTest {
 @Test void defaultsAndBoundaries(){assertEquals(1024,ChatMessageDisplay.DEFAULT_LIMIT);assertDoesNotThrow(()->new ChatMessageDisplay.State(16384,"time",0));assertThrows(IllegalArgumentException.class,()->new ChatMessageDisplay.State(16385,"time",0));assertThrows(IllegalArgumentException.class,()->new ChatMessageDisplay.State(0,"time",0));}
 @Test void fullDateHoverAndTemplatesUseReceiptTimeNotRefreshTime(){var at=Instant.parse("2026-09-25T05:06:07Z");var zone=ZoneId.of("Asia/Shanghai");assertEquals("2026-09-25 13:06:07",ChatMessageDisplay.hover("time",at,zone));assertEquals("2026-09-25",ChatMessageDisplay.hover("date",at,zone));assertEquals("",ChatMessageDisplay.hover("off",at,zone));assertEquals("消息时间：2026年09月25日 13:06:07",ChatMessageDisplay.hover("消息时间：{yyyy年MM月dd日 HH:mm:ss}",at,zone));assertEquals("13:06:07",ChatMessageDisplay.hover("HH:mm:ss",at,zone));}
 @Test void rejectUnsupportedSyntaxAndControlCharacters(){for(String bad:new String[]{"","{HH:mm:ss","bad pattern?","time\nspoof","x".repeat(257)})assertThrows(IllegalArgumentException.class,()->ChatMessageDisplay.validateMark(bad),bad);}
}
