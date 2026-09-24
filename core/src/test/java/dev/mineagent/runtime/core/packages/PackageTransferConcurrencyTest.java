package dev.mineagent.runtime.core.packages;
import org.junit.jupiter.api.Test;import java.time.Clock;import java.util.*;import static org.junit.jupiter.api.Assertions.*;
class PackageTransferConcurrencyTest {
 @Test void manyPackagesForOneViewerRemainIndependent()throws Exception{
  var leases=new PackageTransferLeases(Clock.systemUTC());var viewer=UUID.randomUUID();var session=UUID.randomUUID();var offers=new ArrayList<PackageTransferLeases.Offer>();
  for(int i=0;i<24;i++)offers.add(leases.offer(viewer,session,UUID.randomUUID(),1,new byte[]{(byte)i}));
  assertEquals(24,leases.count());leases.release(viewer,session,offers.getFirst().transferId());
  for(int i=1;i<24;i++)assertArrayEquals(new byte[]{(byte)i},leases.chunk(viewer,session,offers.get(i).transferId(),0,o->true));
  assertThrows(SecurityException.class,()->leases.chunk(UUID.randomUUID(),session,offers.get(1).transferId(),0,o->true));
 }
}
