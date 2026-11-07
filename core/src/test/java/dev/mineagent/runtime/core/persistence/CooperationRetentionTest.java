package dev.mineagent.runtime.core.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CooperationRetentionTest {
    @TempDir Path root;
    private static final ObjectMapper JSON=new ObjectMapper();
    private static String payload(UUID id,UUID owner,UUID leader,String state)throws Exception{return JSON.writeValueAsString(Map.of("id",id,"owner",owner,"leader",leader,"state",state));}
    @Test void completedHistoryDoesNotEnterSchedulerAndRemainsPaginatedAfterReopen()throws Exception{
        Path path=root.resolve("runtime.db");UUID world=UUID.randomUUID(),owner=UUID.randomUUID(),leader=UUID.randomUUID(),running=UUID.randomUUID(),paused=UUID.randomUUID();var history=new ArrayList<UUID>();
        try(var db=new SqliteRuntimeRepository(path)){
            for(int i=0;i<83;i++){UUID id=UUID.randomUUID();history.add(id);assertTrue(db.compareAndSet(world,"cooperation_v2",id.toString(),0,payload(id,owner,leader,i%2==0?"COMPLETED":"CANCELLED"),i+1).accepted());}
            assertTrue(db.compareAndSet(world,"cooperation_v2",running.toString(),0,payload(running,owner,leader,"RUNNING"),100).accepted());
            assertTrue(db.compareAndSet(world,"cooperation_v2",paused.toString(),0,payload(paused,owner,leader,"PAUSED"),101).accepted());
            // Indexing migrates existing records in place, without new identities or replay.
            db.initializeCooperationHistory();assertEquals(Set.of(running.toString(),paused.toString()),new HashSet<>(db.activeCooperations(world).stream().map(RuntimeRecord::recordId).toList()));
        }
        try(var db=new SqliteRuntimeRepository(path)){
            db.initializeCooperationHistory();var ids=new ArrayList<String>();for(int offset=0;;offset+=16){var page=db.cooperationHistory(world,owner,leader,offset,16);ids.addAll(page.stream().map(RuntimeRecord::recordId).toList());if(page.size()<16)break;}
            assertEquals(85,ids.size());assertEquals(85,new HashSet<>(ids).size());assertEquals(paused.toString(),ids.getFirst());assertEquals(running.toString(),ids.get(1));
            assertTrue(ids.containsAll(history.stream().map(UUID::toString).toList()));assertEquals(1,db.get(world,"cooperation_v2",history.getFirst().toString()).orElseThrow().revision());
            var old=db.get(world,"cooperation_v2",running.toString()).orElseThrow();assertTrue(db.compareAndSet(world,"cooperation_v2",running.toString(),old.revision(),payload(running,owner,leader,"COMPLETED"),102).accepted());
            assertEquals(List.of(paused.toString()),db.activeCooperations(world).stream().map(RuntimeRecord::recordId).toList());
            assertEquals(running.toString(),db.cooperationHistory(world,owner,leader,0,1).getFirst().recordId());
        }
    }
    @Test void pagesCannotMixWorldsOwnersLeadersOrOtherRecordNamespaces()throws Exception{
        UUID world=UUID.randomUUID(),otherWorld=UUID.randomUUID(),owner=UUID.randomUUID(),leader=UUID.randomUUID(),id=UUID.randomUUID();
        try(var db=new SqliteRuntimeRepository(root.resolve("scope.db"))){
            db.compareAndSet(world,"cooperation_v2",id.toString(),0,payload(id,owner,leader,"COMPLETED"),1);
            db.compareAndSet(otherWorld,"cooperation_v2",id.toString(),0,payload(id,owner,leader,"RUNNING"),1);
            db.compareAndSet(world,"unrelated",id.toString(),0,"non-json source",1);db.initializeCooperationHistory();
            assertTrue(db.activeCooperations(world).isEmpty());assertEquals(1,db.activeCooperations(otherWorld).size());
            assertTrue(db.cooperationHistory(world,UUID.randomUUID(),leader,0,16).isEmpty());assertTrue(db.cooperationHistory(world,owner,UUID.randomUUID(),0,16).isEmpty());
            assertThrows(IllegalArgumentException.class,()->db.cooperationHistory(world,owner,leader,-1,16));assertThrows(IllegalArgumentException.class,()->db.cooperationHistory(world,owner,leader,0,34));
        }
    }
}
