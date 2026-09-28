package dev.mineagent.runtime.core.memory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DialogueRecallTest {
    @TempDir Path dir;
    @Test void homeAndEquipmentAreRecalledAcrossTurnsWithoutCrossingIdentity()throws Exception{
        var file=dir.resolve("memory.db");var world=UUID.randomUUID();var player=UUID.randomUUID();var ai=UUID.randomUUID();var clock=Clock.fixed(Instant.parse("2026-09-29T00:00:00Z"),ZoneOffset.UTC);
        try(var store=new DialogueMemoryStore(file,world,player,ai,clock)){
            store.remember("FACT","家","minecraft:overworld x=125 y=70 z=-32",0);
            store.remember("PREFERENCE","喜欢的装备","minecraft:diamond_chestplate 配 minecraft:iron_leggings",0);
            store.remember("FACT","临时目标","过期目标",1);
        }
        try(var store=new DialogueMemoryStore(file,world,player,ai,Clock.offset(clock,Duration.ofSeconds(2)))){
            assertTrue(store.context("我要回家",8000).contains("x=125"));assertTrue(store.context("给我喜欢的装备",8000).contains("diamond_chestplate"));assertFalse(store.context("临时目标",8000).contains("过期目标"));
            var changed=store.remember("FACT","家","minecraft:overworld x=200 y=80 z=300",0);assertFalse(store.context("回家",8000).contains("x=125"));assertTrue(store.context("回家",8000).contains("x=200"));store.forget(changed.id(),changed.revision());assertFalse(store.context("回家",8000).contains("x=200"));
        }
        try(var store=new DialogueMemoryStore(file,world,UUID.randomUUID(),ai,clock)){assertEquals("",store.context("装备",8000));}
        try(var store=new DialogueMemoryStore(file,UUID.randomUUID(),player,ai,clock)){assertEquals("",store.context("装备",8000));}
        try(var store=new DialogueMemoryStore(file,world,player,UUID.randomUUID(),clock)){assertEquals("",store.context("装备",8000));}
    }
}
