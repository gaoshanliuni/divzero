package dev.mineagent.runtime.core.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.sql.DriverManager;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class NewWorldIdentityTest {
    @TempDir Path directory;
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
