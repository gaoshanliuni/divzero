package dev.mineagent.runtime.core.content;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ObservationTokensTest {
    @Test void exactStateComparisonInvalidatesChangedOrEvictedObservations(){var cache=new ObservationTokens(2,20);String first=cache.token("stone"),second=cache.token("stone");assertEquals(first,second);assertFalse(cache.matches(first,"dirt"));cache.token("dirt");cache.token("sand");assertFalse(cache.matches(first,"stone"));assertNotEquals(first,cache.token("stone"));cache.clear();assertFalse(cache.matches(first,"stone"));assertThrows(IllegalArgumentException.class,()->cache.token("x".repeat(21)));}
}
