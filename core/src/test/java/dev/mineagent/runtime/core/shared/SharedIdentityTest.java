package dev.mineagent.runtime.core.shared;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SharedIdentityTest {
    @TempDir Path root;
    private final UUID world=UUID.randomUUID(),owner=UUID.randomUUID(),pack=UUID.randomUUID(),instance=UUID.randomUUID();
    private SharedStateStore.Context context(){return new SharedStateStore.Context(new SharedStateStore.Scope(world,pack,instance,"state"),owner,owner,1,"a".repeat(64),"PACKAGE");}
    private SharedStateStore open()throws Exception{return SharedStateStore.open(root.resolve("shared.db"),world,(c,w,a)->true);}
    private static final String SCHEMA="{\"version\":1,\"fields\":{\"count\":{\"type\":\"INTEGER\",\"scope\":\"SHARED\",\"read\":\"PARTICIPANT\",\"write\":\"PARTICIPANT\"}}}";
    @Test void keepsStreamIdentityAndComparesAcknowledgedEventsDirectly()throws Exception{
        UUID epoch,eventId;
        try(var store=open()){
            epoch=store.streamEpoch();assertEquals(4,epoch.version());store.define(context(),UUID.randomUUID(),0,SCHEMA);
            var pending=store.pendingExports(8).getFirst();eventId=pending.event().id();assertEquals(4,eventId.version());assertTrue(pending.payloadHash().startsWith("ref-v2:"));
            var e=pending.event();var changed=new SharedStateStore.Event(e.id(),e.revision()+1,e.schema(),e.author(),e.actorKind(),e.packageRevision(),e.canonical(),e.changes(),e.operation(),e.occurredAt(),e.origin());
            assertThrows(IllegalStateException.class,()->store.acknowledgeExport(new SharedStateStore.Export(pending.scope(),pending.payloadHash(),changed)));
            try(var db=DriverManager.getConnection("jdbc:sqlite:"+root.resolve("shared.db"));var update=db.prepareStatement("UPDATE mineagent_shared_outbox_v1 SET payload='{}' WHERE event=?")){update.setString(1,eventId.toString());assertThrows(SQLException.class,update::executeUpdate);}
            assertEquals(pending,store.pendingExports(8).getFirst());store.acknowledgeExport(pending);assertTrue(store.pendingExports(8).isEmpty());
        }
        try(var store=open()){assertEquals(epoch,store.streamEpoch());assertTrue(store.pendingExports(8).isEmpty());}
    }
    @Test void adoptsLegacySystemIdentityOnceWhenExistingNamespacesHaveNoMapping()throws Exception{
        Path file=root.resolve("shared.db");
        var payload=SharedJson.JSON.writeValueAsString(Map.of("owner",owner,"revision",1,"schema",SharedStateSchema.parse(SCHEMA),"records",Map.of()));
        try(var db=DriverManager.getConnection("jdbc:sqlite:"+file);var schema=db.createStatement()){
            schema.execute("CREATE TABLE mineagent_shared_namespaces_v1(world TEXT NOT NULL,id TEXT NOT NULL,owner TEXT NOT NULL,payload TEXT NOT NULL,PRIMARY KEY(world,id))");
            try(var insert=db.prepareStatement("INSERT INTO mineagent_shared_namespaces_v1 VALUES(?,?,?,?)")){insert.setString(1,world.toString());insert.setString(2,pack+"/"+instance+"/state");insert.setString(3,owner.toString());insert.setString(4,payload);insert.executeUpdate();}
        }
        UUID epoch;try(var store=open()){epoch=store.streamEpoch();assertEquals(3,epoch.version());assertEquals(1,store.read(context()).revision());}
        try(var store=open()){assertEquals(epoch,store.streamEpoch());assertEquals(1,store.read(context()).revision());}
        try(var db=DriverManager.getConnection("jdbc:sqlite:"+file);var query=db.createStatement();var rows=query.executeQuery("SELECT COUNT(*) FROM mineagent_scope_ids_v2")){assertTrue(rows.next());assertEquals(2,rows.getInt(1));}
    }
}
