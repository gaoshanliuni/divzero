package dev.mineagent.runtime.core.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.sql.DriverManager;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class NewWorldIdentityTest {
    @TempDir Path directory;
    @Test void reimportedTemplateGetsNewScopeWithoutDeletingOrAdoptingOldRows()throws Exception{
        var home=Files.createDirectory(directory.resolve("runtime"));var save=Files.createDirectory(directory.resolve("map"));UUID original;
        try(var id=WorldSaveIdentity.open(home,save,UUID.randomUUID())){original=id.scopeId();}
        try(var db=DriverManager.getConnection("jdbc:sqlite:"+home.resolve("runtime.db"));var s=db.createStatement()){s.execute("CREATE TABLE mineagent_fixture(world TEXT,value TEXT)");s.execute("INSERT INTO mineagent_fixture VALUES('"+original+"','keep original')");}
        Files.delete(save.resolve(WorldSaveIdentity.ANCHOR_FILE));
        try(var id=WorldSaveIdentity.open(home,save,UUID.randomUUID())){assertFalse(id.ready());assertEquals("ANCHOR_MISSING",id.status().state());}
        UUID imported;
        try(var id=WorldSaveIdentity.open(home,save,UUID.randomUUID(),true)){assertTrue(id.ready());imported=id.scopeId();assertNotEquals(original,imported);}
        try(var id=WorldSaveIdentity.open(home,save,UUID.randomUUID(),true)){assertEquals(imported,id.scopeId());}
        try(var db=DriverManager.getConnection("jdbc:sqlite:"+home.resolve("runtime.db"));var s=db.createStatement();var r=s.executeQuery("SELECT world,value FROM mineagent_fixture")){assertTrue(r.next());assertEquals(original.toString(),r.getString(1));assertEquals("keep original",r.getString(2));assertFalse(r.next());}
        try(var db=DriverManager.getConnection("jdbc:sqlite:"+home.resolve("world-identities.db"));var s=db.createStatement();var r=s.executeQuery("SELECT state,save_path FROM identity_bindings_v1 WHERE scope_id='"+original+"'")){assertTrue(r.next());assertEquals("DETACHED",r.getString(1));assertNull(r.getString(2));}
    }
    @Test void templateFlagDoesNotSilentlyReplaceInvalidOrCopiedAnchors()throws Exception{
        var home=Files.createDirectory(directory.resolve("runtime"));var save=Files.createDirectory(directory.resolve("map"));
        byte[] invalid="not a valid anchor".getBytes(java.nio.charset.StandardCharsets.UTF_8);Files.write(save.resolve(WorldSaveIdentity.ANCHOR_FILE),invalid);
        try(var id=WorldSaveIdentity.open(home,save,UUID.randomUUID(),true)){assertFalse(id.ready());assertEquals("ANCHOR_INVALID",id.status().state());assertArrayEquals(invalid,Files.readAllBytes(save.resolve(WorldSaveIdentity.ANCHOR_FILE)));}
    }
    @Test void secondNewSaveStartsImmediatelyWithoutAdoptingOldWorldRows()throws Exception{
        Path home=Files.createDirectory(directory.resolve("runtime")),first=Files.createDirectory(directory.resolve("first")),second=Files.createDirectory(directory.resolve("second"));
        UUID firstScope;
        try(var identity=WorldSaveIdentity.open(home,first,UUID.randomUUID())){assertTrue(identity.ready());firstScope=identity.scopeId();}
        try(var db=DriverManager.getConnection("jdbc:sqlite:"+home.resolve("runtime.db"));var statement=db.createStatement()){
            statement.execute("CREATE TABLE mineagent_fixture(world TEXT, value TEXT)");statement.execute("INSERT INTO mineagent_fixture VALUES('"+firstScope+"','keep old world')");
        }
        UUID secondScope;
        try(var identity=WorldSaveIdentity.open(home,second,UUID.randomUUID())){assertTrue(identity.ready());secondScope=identity.scopeId();assertNotEquals(firstScope,secondScope);}
        try(var identity=WorldSaveIdentity.open(home,second,UUID.randomUUID())){assertEquals(secondScope,identity.scopeId());}
        try(var db=DriverManager.getConnection("jdbc:sqlite:"+home.resolve("runtime.db"));var statement=db.createStatement();var result=statement.executeQuery("SELECT world,value FROM mineagent_fixture")){
            assertTrue(result.next());assertEquals(firstScope.toString(),result.getString(1));assertEquals("keep old world",result.getString(2));assertFalse(result.next());
        }
        Path copied=Files.createDirectory(directory.resolve("copied"));Files.copy(first.resolve(WorldSaveIdentity.ANCHOR_FILE),copied.resolve(WorldSaveIdentity.ANCHOR_FILE));
        try(var identity=WorldSaveIdentity.open(home,copied,UUID.randomUUID())){assertFalse(identity.ready());assertEquals("PATH_CHANGED",identity.status().state());}
    }
}
