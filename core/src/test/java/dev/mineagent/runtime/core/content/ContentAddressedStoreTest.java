package dev.mineagent.runtime.core.content;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ContentAddressedStoreTest {
    @TempDir
    Path directory;

    @Test
    void assignsIndependentIdsAndOnlyReusesExplicitBindings() throws Exception {
        var store = new ContentAddressedStore(directory);
        byte[] content = "你好，MineAgent".getBytes(StandardCharsets.UTF_8);

        var first = store.put(content);
        var second = store.put(content);

        assertTrue(first.sha256().matches("[a-f0-9]{64}"));
        assertNotEquals(first.sha256(), second.sha256());
        assertEquals(first,store.put(first.sha256(),content));
        assertArrayEquals(content, store.read(first.sha256()));
        assertArrayEquals(content,store.read(second.sha256()));
    }

    @Test
    void rejectsInvalidIdsAndConflictingImmutableWrites() throws Exception {
        var store = new ContentAddressedStore(directory);
        var object = store.put("safe".getBytes(StandardCharsets.UTF_8));

        assertThrows(IllegalArgumentException.class, () -> store.read("../secret"));
        assertThrows(ContentIntegrityException.class, () -> store.put(object.sha256(),"evil".getBytes(StandardCharsets.UTF_8)));
        assertEquals("safe",Files.readString(object.path()));
    }

    @Test
    void rejectsObjectsLargerThanConfiguredLimit() {
        var store = new ContentAddressedStore(directory, 4);

        assertThrows(IllegalArgumentException.class, () -> store.put(new byte[5]));
    }

    @Test void concurrentPublishersNeverOverwriteOneAnother()throws Exception{
        var id=dev.mineagent.runtime.api.packages.ResourceIds.create();var gate=new java.util.concurrent.CountDownLatch(1);
        try(var executor=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()){
            var jobs=new java.util.ArrayList<java.util.concurrent.Future<Boolean>>();
            for(int n=0;n<12;n++){byte value=(byte)n;jobs.add(executor.submit(()->{gate.await();try{new ContentAddressedStore(directory).put(id,new byte[]{value});return true;}catch(ContentIntegrityException expected){return false;}}));}
            gate.countDown();int won=0;for(var job:jobs)if(job.get())won++;assertEquals(1,won);
            byte[] saved=new ContentAddressedStore(directory).read(id);assertEquals(1,saved.length);assertTrue(saved[0]>=0&&saved[0]<12);
        }
    }

    @Test void namedBindingIsCompleteAndStableAcrossInstances()throws Exception{
        var ids=java.util.List.of(dev.mineagent.runtime.api.packages.ResourceIds.create());
        try(var executor=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()){
            var jobs=new java.util.ArrayList<java.util.concurrent.Future<String>>();for(int n=0;n<20;n++)jobs.add(executor.submit(()->new ContentAddressedStore(directory).namedId("render_bundle",ids)));
            var values=new java.util.HashSet<String>();for(var job:jobs)values.add(job.get());assertEquals(1,values.size());assertEquals(values.iterator().next(),new ContentAddressedStore(directory).namedId("render_bundle",ids));
        }
    }
}
