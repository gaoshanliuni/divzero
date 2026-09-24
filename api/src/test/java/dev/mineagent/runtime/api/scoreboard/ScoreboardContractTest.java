package dev.mineagent.runtime.api.scoreboard;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ScoreboardContractTest {
    @Test
    void snapshotSeparatesObjectivesEntriesAndDisplaySlots() {
        var objective = new ScoreObjectiveSnapshot("kills", "dummy", false, "击杀榜",
                "INTEGER", true, new NumberFormatSpec(NumberFormatKind.STYLED, "gold"));
        var entry = new ScoreEntrySnapshot("kills", "Alice", 7, "Alice",
                new NumberFormatSpec(NumberFormatKind.DEFAULT, ""));
        var snapshot = new ScoreboardSnapshot(List.of(objective), List.of(entry),
                Map.of("sidebar", "kills"));

        assertEquals("kills", snapshot.objectives().getFirst().name());
        assertEquals(7, snapshot.entries().getFirst().score());
        assertEquals("kills", snapshot.displaySlots().get("sidebar"));
    }

    @Test
    void modernNativeIdentifiersRemainVerbatimInsteadOfBeingTruncated() {
        String objective="ma_"+"0123456789abcdef".repeat(2),holder="holder_"+"h".repeat(80),team="team_"+"x".repeat(40);
        var o=new ScoreObjectiveSnapshot(objective,"minecraft.custom:"+"criterion".repeat(20),false,"标题".repeat(1500),"INTEGER",true,NumberFormatSpec.defaultFormat());
        var e=new ScoreEntrySnapshot(objective,holder,7,"",NumberFormatSpec.defaultFormat());
        var t=new ScoreTeamSnapshot(team,"Team","white",java.util.Set.of(holder));
        var snapshot=new ScoreboardSnapshot(List.of(o),List.of(e),Map.of("sidebar",objective),List.of(t));
        assertEquals(35,snapshot.objectives().getFirst().name().length());assertEquals(objective,snapshot.objectives().getFirst().name());assertEquals(holder,snapshot.entries().getFirst().holder());assertEquals(team,snapshot.teams().getFirst().name());
        assertEquals(holder,new ScoreRow(holder,holder,7,"7","").holder());
    }

    @Test
    void observationAlsoSupportsNamesTheNativeCodecAcceptsAndEmptyFixedText() {
        String name=" \t";assertEquals(name,new ScoreObjectiveSnapshot(name,"dummy",false,"","INTEGER",true,NumberFormatSpec.defaultFormat()).name());
        assertEquals("",new NumberFormatSpec(NumberFormatKind.FIXED,"").value());
        assertEquals(32767,new ScoreEntrySnapshot("long", "x".repeat(32767),0,"",NumberFormatSpec.defaultFormat()).holder().length());
        assertThrows(IllegalArgumentException.class,()->new ScoreEntrySnapshot("long","x".repeat(32768),0,"",NumberFormatSpec.defaultFormat()));
        assertThrows(IllegalArgumentException.class,()->new ScoreObjectiveSnapshot(null,"dummy",false,"","INTEGER",true,NumberFormatSpec.defaultFormat()));
        assertThrows(IllegalArgumentException.class,()->new NumberFormatSpec(NumberFormatKind.STYLED,""));
    }

    @Test
    void validatesNativeNameAndNumberFormatBounds() {
        assertThrows(IllegalArgumentException.class, () -> new ScoreObjectiveSnapshot(
                "x".repeat(NativeScoreboardText.MAX_LENGTH + 1), "dummy", false, "too long", "INTEGER", true,
                new NumberFormatSpec(NumberFormatKind.DEFAULT, "")));
        assertThrows(IllegalArgumentException.class, () -> new NumberFormatSpec(
                NumberFormatKind.FIXED, "x".repeat(NativeScoreboardText.MAX_LENGTH + 1)));
    }
}
