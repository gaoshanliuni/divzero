package dev.mineagent.runtime.core.memory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DialogueRecallTest {
    @TempDir Path dir;
    @Test void editorRejectsConcurrentAiTopicUpdate()throws Exception{
        try(var store=new DialogueMemoryStore(dir.resolve("editor.db"),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),Clock.systemUTC())){
            var first=store.remember("FACT","家","旧地址",0,"player:workspace",List.of(),0L);
            var latest=store.remember("FACT","家","新地址",0);
            assertThrows(IllegalStateException.class,()->store.remember("FACT","家","过时编辑",0,"player:workspace",List.of(),first.revision()));
            assertTrue(store.context("回家",8000).contains("新地址"));
            store.remember("FACT","家","确认的新地址",0,"player:workspace",List.of(),latest.revision());
            assertTrue(store.context("回家",8000).contains("确认的新地址"));
        }
    }
    @Test void legacyRecallDoesNotExposeOtherPlayersPrivateNotes()throws Exception{
        var a=UUID.randomUUID();var b=UUID.randomUUID();
        try(var store=MemoryService.open(dir.resolve("legacy.db"),UUID.randomUUID(),Clock.systemUTC())){
            store.create(a,dev.mineagent.runtime.api.memory.MemoryKind.WORLD_FACT,"家","共享的家在 x=12");
            store.create(b,dev.mineagent.runtime.api.memory.MemoryKind.PLAYER_PREFERENCE,"家","私人地址 x=900");
            assertTrue(store.context(a,"回家",8000).contains("x=12"));assertFalse(store.context(a,"回家",8000).contains("x=900"));
            assertTrue(store.context(b,"回家",8000).contains("x=900"));assertEquals("",store.context(a,"给我装备",8000));
        }
    }
    @Test void homeAndEquipmentAreRecalledAcrossTurnsWithoutCrossingIdentity()throws Exception{
        var file=dir.resolve("memory.db");var world=UUID.randomUUID();var player=UUID.randomUUID();var ai=UUID.randomUUID();var clock=Clock.fixed(Instant.parse("2026-09-29T00:00:00Z"),ZoneOffset.UTC);
        try(var store=new DialogueMemoryStore(file,world,player,ai,clock)){
            store.remember("FACT","家","minecraft:overworld x=125 y=70 z=-32",0);
            store.remember("PREFERENCE","喜欢的装备","minecraft:diamond_chestplate 配 minecraft:iron_leggings",0);
            store.remember("FACT","临时目标","过期目标",1);
        }
        try(var store=new DialogueMemoryStore(file,world,player,ai,Clock.offset(clock,Duration.ofSeconds(2)))){
            assertTrue(store.context("我要回家",8000).contains("x=125"));assertTrue(store.context("go home",8000).contains("x=125"));assertEquals("",store.context("help with homework",8000));assertTrue(store.context("给我喜欢的装备",8000).contains("diamond_chestplate"));assertFalse(store.context("临时目标",8000).contains("过期目标"));
            var changed=store.remember("FACT","家","minecraft:overworld x=200 y=80 z=300",0);assertFalse(store.context("回家",8000).contains("x=125"));assertTrue(store.context("回家",8000).contains("x=200"));store.forget(changed.id(),changed.revision());assertFalse(store.context("回家",8000).contains("x=200"));
        }
        try(var store=new DialogueMemoryStore(file,world,UUID.randomUUID(),ai,clock)){assertEquals("",store.context("装备",8000));}
        try(var store=new DialogueMemoryStore(file,UUID.randomUUID(),player,ai,clock)){assertEquals("",store.context("装备",8000));}
        try(var store=new DialogueMemoryStore(file,world,player,UUID.randomUUID(),clock)){assertEquals("",store.context("装备",8000));}
    }
    @Test void relevanceFloorAliasesAndObservationsDoNotTurnHistoryIntoCurrentInventory()throws Exception{
        var file=dir.resolve("selective.db");var clock=Clock.fixed(Instant.parse("2026-09-30T00:00:00Z"),ZoneOffset.UTC);
        try(var store=new DialogueMemoryStore(file,UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),clock)){
            store.remember("FACT","海边小屋","x=24 y=64 z=90",0,"player:chat",List.of("蓝色基地","beach home"));
            store.remember("PREFERENCE","食物","我喜欢吃苹果",0);
            var observed=store.remember("OBSERVATION","燃料箱","烈焰棒 8 个",60,"inspect_container:operation-7",List.of("燃料储备"));
            assertEquals("",store.context("你好，今天讲一个笑话",8000));
            assertTrue(store.context("蓝色基地在哪里",8000).contains("x=24"));
            String recalled=store.context("燃料储备还有什么",8000);
            assertTrue(recalled.contains("OBSERVATION"));assertTrue(recalled.contains("inspect_container:operation-7"));
            assertEquals(clock.millis(),observed.observedAt());assertTrue(observed.expiresAt()>observed.observedAt());
            assertThrows(IllegalArgumentException.class,()->store.remember("OBSERVATION","库存","3",0));
            assertEquals(0,MemoryRelevance.score("查询故事","矿石","石头",List.of()));
        }
    }
}
