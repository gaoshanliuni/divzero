package dev.mineagent.runtime.core.files;
import org.junit.jupiter.api.Test;import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;import java.util.*;import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
class FileLibraryConcurrencyTest {
 @TempDir Path dir;
 @Test void independentUploadsDoNotUseLibraryMonitorAndCancelIsScoped()throws Exception{
  var library=new FileLibrary(dir);var owner=UUID.randomUUID();var uploads=new ArrayList<UUID>();
  for(int i=0;i<12;i++)uploads.add(library.begin(owner,"file"+i+".txt","FILE"));
  var a=uploads.get(0);var b=uploads.get(1);var pa=library.beginFile(owner,a,"a.txt",3);var pb=library.beginFile(owner,b,"b.txt",3);
  try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
   synchronized(library){
    assertEquals(3L,pool.submit(()->library.chunk(owner,a,pa,0,new byte[]{1,2,3})).get(2,TimeUnit.SECONDS));
    assertEquals(3L,pool.submit(()->library.chunk(owner,b,pb,0,new byte[]{4,5,6})).get(2,TimeUnit.SECONDS));
    var asset=pool.submit(()->library.finish(owner,a,"TEST")).get(2,TimeUnit.SECONDS);assertArrayEquals(new byte[]{1,2,3},java.nio.file.Files.readAllBytes(library.file(owner,asset.id(),"")));
   }
   library.cancel(owner,b);assertThrows(IllegalArgumentException.class,()->library.chunk(owner,b,pb,3,new byte[]{7}));
   assertEquals(1,((java.util.List<?>)library.list(owner,"",0).get("files")).size());
  }
 }
}
