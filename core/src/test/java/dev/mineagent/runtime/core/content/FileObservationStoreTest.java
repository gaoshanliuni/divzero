package dev.mineagent.runtime.core.content;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class FileObservationStoreTest {
    @TempDir Path root;
    @Test void filenameAndByteVersionSurviveReopenAndSameLengthChangesAreRejected()throws Exception{
        Path path=root.resolve("observations.db");UUID id;String version;
        try(var store=new FileObservationStore(path,8,32)){id=store.identify("house.nbt");version=store.observe(id,new byte[]{1,2,3},"");assertEquals(id,store.identify("house.nbt"));assertEquals(version,store.observe(id,new byte[]{1,2,3},version));}
        try(var store=new FileObservationStore(path,8,32)){
            assertEquals(id,store.identify("house.nbt"));assertEquals("house.nbt",store.name(id).orElseThrow());assertEquals(version,store.observe(id,new byte[]{1,2,3},version));
            var error=assertThrows(java.io.IOException.class,()->store.observe(id,new byte[]{3,2,1},version));assertEquals("FILE_OBSERVATION_CHANGED",error.getMessage());
            assertEquals(version,store.observe(id,new byte[]{1,2,3},version));String next=store.observe(id,new byte[]{3,2,1},"");assertNotEquals(version,next);assertEquals(next,store.observe(id,new byte[]{3,2,1},next));
            assertThrows(java.io.IOException.class,()->store.observe(id,new byte[]{3,2,1},version));
        }
        try(var other=new FileObservationStore(root.resolve("other.db"),8,32)){assertNotEquals(id,other.identify("house.nbt"));assertTrue(other.name(id).isEmpty());}
    }
    @Test void evictionExpiresOldProofButKeepsTheFileIdentity()throws Exception{
        try(var store=new FileObservationStore(root.resolve("bounded.db"),4,8)){
            var ids=new ArrayList<UUID>();var versions=new ArrayList<String>();byte[] bytes={1,2,3,4};
            for(int i=0;i<3;i++){var id=store.identify("file"+i+".nbt");ids.add(id);versions.add(store.observe(id,bytes,""));}
            int expired=0;UUID evicted=null;String oldVersion=null;
            for(int i=0;i<2;i++){try{store.observe(ids.get(i),bytes,versions.get(i));}catch(java.io.IOException error){assertEquals("FILE_OBSERVATION_EXPIRED_REOBSERVE",error.getMessage());expired++;evicted=ids.get(i);oldVersion=versions.get(i);}}
            assertEquals(1,expired);assertTrue(store.name(evicted).isPresent());assertEquals(evicted,store.identify(store.name(evicted).orElseThrow()));assertNotEquals(oldVersion,store.observe(evicted,bytes,""));
            assertThrows(IllegalArgumentException.class,()->store.identify("../house.nbt"));
        }
    }
}
