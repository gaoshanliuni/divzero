package dev.mineagent.runtime.core.task;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class PvpIntentTest {
    @Test void explicitNamesAndSelfConsentDoNotBecomeGeneralPlayerAggression(){
        assertTrue(PvpIntent.namesTarget("现在和我 1v1",List.of("Owner"),true));
        assertTrue(PvpIntent.namesTarget("允许你攻击 Alex",List.of("Alex"),false));
        assertFalse(PvpIntent.namesTarget("Alex 刚刚攻击我",List.of("Steve"),false));
        assertFalse(PvpIntent.namesTarget("Alex攻击我",List.of("Alex"),false));
        assertFalse(PvpIntent.namesTarget("Alex攻击我",List.of("Owner"),true));
        assertFalse(PvpIntent.namesTarget("不要攻击 Alex",List.of("Alex"),false));
        assertFalse(PvpIntent.namesTarget("解释如何与 Alex 1v1",List.of("Alex"),false));
        assertFalse(PvpIntent.namesTarget("攻击 Alex2",List.of("Alex"),false));
    }
}
