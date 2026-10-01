package dev.mineagent.runtime.core.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.sql.DriverManager;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class NewWorldIdentityTest {
    @TempDir Path directory;
    @Test void explicitAcceptActivatesCopiedAnchorWithoutReopenOrTouchingOriginal()throws Exception{
        var home=Files.createDirectory(directory.resolve("runtime"));var first=Files.createDirectory(directory.resolve("first"));var copy=Files.createDirectory(directory.resolve("copy"));UUID original;
        try(var id=WorldSaveIdentity.open(home,first,UUID.randomUUID())){original=id.scopeId();}
        byte[] anchor=Files.readAllBytes(first.resolve(WorldSaveIdentity.ANCHOR_FILE));Files.write(copy.resolve(WorldSaveIdentity.ANCHOR_FILE),anchor);
        UUID independent;try(var id=WorldSaveIdentity.open(home,copy,UUID.randomUUID())){assertFalse(id.ready());var accepted=id.acceptCurrent("SERVER_COMMAND_SOURCE");assertEquals("FRESH",accepted.decision());assertTrue(id.ready());independent=id.scopeId();assertNotEquals(original,independent);assertEquals(independent,id.acceptCurrent("SERVER_COMMAND_SOURCE").scope());}
        assertArrayEquals(anchor,Files.readAllBytes(first.resolve(WorldSaveIdentity.ANCHOR_FILE)));
        try(var id=WorldSaveIdentity.open(home,copy,UUID.randomUUID())){assertTrue(id.ready());assertEquals(independent,id.scopeId());}
    }
    @Test void explicitAcceptContinuesAMovedWorldWithItsOriginalScope()throws Exception{
        var home=Files.createDirectory(directory.resolve("runtime"));var before=Files.createDirectory(directory.resolve("before"));UUID original;
        try(var id=WorldSaveIdentity.open(home,before,UUID.randomUUID())){original=id.scopeId();}
        var moved=Files.move(before,directory.resolve("moved"));
        try(var id=WorldSaveIdentity.open(home,moved,UUID.randomUUID())){assertFalse(id.ready());assertEquals("PATH_CHANGED",id.status().state());assertEquals("RELOCATE",id.acceptCurrent("SERVER_COMMAND_SOURCE").decision());assertTrue(id.ready());assertEquals(original,id.scopeId());}
    }
    @Test void validImportedAnchorCanRestorePresentWorldDataWithoutRegistryOrRestart()throws Exception{
        var home=Files.createDirectory(directory.resolve("runtime"));var save=Files.createDirectory(directory.resolve("save"));UUID scope;
        try(var id=WorldSaveIdentity.open(home,save,UUID.randomUUID())){scope=id.scopeId();}
        var newHome=Files.createDirectory(directory.resolve("restored-runtime"));
        try(var db=DriverManager.getConnection("jdbc:sqlite:"+newHome.resolve("runtime.db"));var s=db.createStatement()){s.execute("CREATE TABLE mineagent_fixture(world TEXT,value TEXT)");s.execute("INSERT INTO mineagent_fixture VALUES('"+scope+"','preserved')");}
        try(var id=WorldSaveIdentity.open(newHome,save,UUID.randomUUID())){assertEquals("ANCHOR_UNBOUND",id.status().state());assertEquals("ADOPT",id.acceptCurrent("SERVER_COMMAND_SOURCE").decision());assertTrue(id.ready());assertEquals(scope,id.scopeId());}
        try(var db=DriverManager.getConnection("jdbc:sqlite:"+newHome.resolve("runtime.db"));var s=db.createStatement();var r=s.executeQuery("SELECT value FROM mineagent_fixture")){assertTrue(r.next());assertEquals("preserved",r.getString(1));}
    }
    @Test void explicitAcceptCannotBreakAnActiveSaveLease()throws Exception{
        var home=Files.createDirectory(directory.resolve("runtime"));var save=Files.createDirectory(directory.resolve("save"));
        try(var active=WorldSaveIdentity.open(home,save,UUID.randomUUID())){assertTrue(active.ready());var error=assertThrows(IllegalStateException.class,()->WorldSaveIdentity.open(home,save,UUID.randomUUID()));assertEquals("WORLD_IDENTITY_IN_USE",error.getMessage());}
    }
    @Test void oldExplicitSelectionCanBeActivatedInTheSameProcess()throws Exception{
        var home=Files.createDirectory(directory.resolve("runtime"));var save=Files.createDirectory(directory.resolve("save"));Files.writeString(save.resolve(WorldSaveIdentity.ANCHOR_FILE),"old damaged identity");
        try(var id=WorldSaveIdentity.open(home,save,UUID.randomUUID())){id.choose("FRESH",null,id.status().challenge(),"SERVER_COMMAND_SOURCE");assertFalse(id.ready());assertEquals("REOPEN_REQUIRED",id.status().state());assertEquals("SELECTED",id.acceptCurrent("SERVER_COMMAND_SOURCE").decision());assertTrue(id.ready());}
    }
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
