package dev.mineagent.runtime.core.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mineagent.runtime.core.packages.PackageAssetMetadata.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PackageAssetIdentityTest {
    @TempDir Path root;
    final UUID world=UUID.randomUUID(),owner=UUID.randomUUID(),source=UUID.randomUUID(),operation=UUID.randomUUID();
    Input input(UUID w,UUID o,String name){return new Input(operation,w,o,"COPY",source,1,"a".repeat(64),name,null,0);}
    @Test void copyIdentityIsDurableScopedAndCannotBeReusedForChangedCandidateInput()throws Exception{
        Path path=root.resolve("db");UUID target;var input=input(world,owner,"copy");
        try(var repo=new SqliteRuntimeRepository(path)){repo.initializePackageLibrary();target=repo.packageAssetCopyTarget(input);assertEquals(target,repo.packageAssetCopyTarget(input));
            assertNotEquals(target,repo.packageAssetCopyTarget(input(UUID.randomUUID(),owner,"copy")));assertNotEquals(target,repo.packageAssetCopyTarget(input(world,UUID.randomUUID(),"copy")));
            assertThrows(IllegalStateException.class,()->repo.packageAssetCopyTarget(input(world,owner,"different")));}
        try(var repo=new SqliteRuntimeRepository(path)){repo.initializePackageLibrary();assertEquals(target,repo.packageAssetCopyTarget(input));}
    }
    @Test void originalCopyReceiptAndShelfIdsAreAdoptedRatherThanRegenerated()throws Exception{
        Path path=root.resolve("legacy");var input=input(world,owner,"old copy");UUID legacy=UUID.randomUUID(),shelfId=UUID.randomUUID();var json=new ObjectMapper();
        try(var repo=new SqliteRuntimeRepository(path)){repo.initializePackageLibrary();}
        try(var db=java.sql.DriverManager.getConnection("jdbc:sqlite:"+path)){
            var receipt=new Receipt(input,"ASSET_COPY_CREATED",legacy,"b".repeat(64),null,1,null);String encoded=json.writeValueAsString(receipt);
            try(var insert=db.prepareStatement("INSERT INTO mineagent_asset_operations_v1 VALUES(?,?,?,?,?,?)")){insert.setString(1,world.toString());insert.setString(2,owner.toString());insert.setString(3,operation.toString());insert.setString(4,encoded);insert.setInt(5,encoded.length());insert.setLong(6,1);insert.executeUpdate();}
            var shelf=new Shelf(shelfId,owner,world,source,1,"a".repeat(64),"c".repeat(64),"1","old asset","GENERATED",true,1,1);String payload=json.writeValueAsString(shelf);
            try(var insert=db.prepareStatement("INSERT INTO mineagent_asset_shelf_v1 VALUES(?,?,?,?,?,?,?,?)")){insert.setString(1,shelfId.toString());insert.setString(2,owner.toString());insert.setString(3,source.toString());insert.setBoolean(4,true);insert.setLong(5,1);insert.setString(6,payload);insert.setInt(7,payload.length());insert.setLong(8,1);insert.executeUpdate();}
            assertEquals(shelf,PackageAssetPersistence.existingShelf(db,input));assertNull(PackageAssetPersistence.existingShelf(db,input(world,UUID.randomUUID(),"old copy")));
        }
        try(var repo=new SqliteRuntimeRepository(path)){repo.initializePackageLibrary();assertEquals(legacy,repo.packageAssetCopyTarget(input));assertEquals(legacy,repo.packageAssetReceipt(world,owner,operation).target());assertEquals(shelfId,repo.packageShelves(owner,true,0).items().getFirst().id());}
    }
}
