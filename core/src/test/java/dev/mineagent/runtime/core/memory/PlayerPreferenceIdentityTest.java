package dev.mineagent.runtime.core.memory;
import java.nio.file.*;import java.time.Clock;import java.util.*;
import org.junit.jupiter.api.Test;import org.junit.jupiter.api.io.TempDir;import static org.junit.jupiter.api.Assertions.*;
class PlayerPreferenceIdentityTest {
    @TempDir Path directory;
    @Test void snapshotsAndRetriesKeepExactScopedIdentityAcrossRestart()throws Exception{
        Path file=directory.resolve("preferences.db");UUID owner=UUID.randomUUID(),world=UUID.randomUUID(),agent=UUID.randomUUID(),operation=UUID.randomUUID();var input=new PlayerPreferenceStore.Input(operation,"SAVE",null,0,"装备","钻石胸甲和铁裤子","CURRENT_WORLD",agent,Set.of("CONVERSATION"),true);PlayerPreferenceStore.Snapshot before;UUID id;
        try(var store=new PlayerPreferenceStore(file,Clock.systemUTC())){var receipt=store.change(owner,world,input);id=receipt.id();assertEquals(receipt,store.change(owner,world,input));before=store.snapshot(owner,world,agent,"CONVERSATION");assertTrue(before.section().contains("钻石胸甲"));assertTrue(store.snapshot(UUID.randomUUID(),world,agent,"CONVERSATION").refs().isEmpty());store.prepareUse(UUID.randomUUID(),before);}
        try(var store=new PlayerPreferenceStore(file,Clock.systemUTC())){assertEquals(before,store.snapshot(owner,world,agent,"CONVERSATION"));assertTrue(store.current(before));store.change(owner,world,new PlayerPreferenceStore.Input(UUID.randomUUID(),"SAVE",id,1,"装备","铁套","CURRENT_WORLD",agent,Set.of("CONVERSATION"),true));assertFalse(store.current(before));assertNotEquals(before.hash(),store.snapshot(owner,world,agent,"CONVERSATION").hash());}
    }
}
