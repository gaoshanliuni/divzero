package dev.mineagent.runtime.core.delivery;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mineagent.runtime.core.task.WorldGoalCheck;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class DeliveryGoalTest {
    @TempDir Path root;final UUID world=UUID.randomUUID(),owner=UUID.randomUUID(),agent=UUID.randomUUID(),task=UUID.randomUUID(),viewer=UUID.randomUUID();
    final AtomicLong clock=new AtomicLong(1000);String authority="grant";
    ContentDeliveryStore.Context context(){return new ContentDeliveryStore.Context(world,owner,agent,task,1,UUID.randomUUID());}
    ContentDeliveryStore open()throws Exception{return ContentDeliveryStore.open(root.resolve("db"),world,clock::get,new ContentDeliveryStore.Port(){public void authorize(ContentDeliveryStore.Context c){}public String authority(ContentDeliveryStore.Delivery d){return authority;}});}
    UUID offer(ContentDeliveryStore s)throws Exception{return s.offer(context(),new ContentDeliveryStore.Asset(UUID.randomUUID(),1,"a".repeat(64),"ui/index.html","CONTENT","fixture"),UUID.randomUUID(),List.of(new ContentDeliveryStore.Recipient(viewer,"ELIGIBLE")),"{\"value\":1}",60000,authority).deliveries().getFirst();}
    DeliveryGoal goal(ContentDeliveryStore.Delivery d,String proof)throws Exception{return DeliveryGoal.parse(new ObjectMapper().valueToTree(Map.of("kind","content_delivery","delivery_id",d.id(),"expected_revision",d.revision(),"data_revision",d.dataRevision(),"data_sha256",ContentDeliveryStore.dataReference(d),"status",d.status(),"evidence",proof)));}
    @Test void renderDoesNotProveCurrentDataAndOldDataProofCannotBeBorrowedForUpdate()throws Exception{
        try(var s=open()){var id=offer(s);var lease=s.accept(viewer,id,UUID.randomUUID());var session=UUID.randomUUID();s.attach(viewer,id,lease.leaseId(),session);s.received(viewer,id,lease.leaseId(),session);
            s.painted(viewer,id,lease.leaseId(),session,"doc",1,"b".repeat(64));var row=s.recipient(viewer,id);
            assertTrue(goal(row,"ASSET_PAINT").matches(row,true));assertFalse(goal(row,"DATA_PAINT").matches(row,true));
            s.painted(viewer,id,lease.leaseId(),session,"doc",2,"b".repeat(64),1,ContentDeliveryStore.dataReference(row));row=s.recipient(viewer,id);
            assertTrue(goal(row,"DATA_PAINT").matches(row,true));assertFalse(goal(row,"DATA_PAINT").matches(row,false));var old=goal(row,"DATA_PAINT");
            s.update(context(),id,1,"{\"value\":2}");row=s.recipient(viewer,id);assertFalse(old.matches(row,true));assertFalse(goal(row,"DATA_PAINT").matches(row,true));
            assertThrows(IllegalStateException.class,()->s.painted(viewer,id,lease.leaseId(),session,"doc",3,"b".repeat(64),1,ContentDeliveryStore.dataReference(id,1)));
            assertThrows(IllegalStateException.class,()->s.painted(viewer,id,lease.leaseId(),session,"doc",3,"b".repeat(64),2,"c".repeat(64)));
            s.painted(viewer,id,lease.leaseId(),session,"doc",3,"b".repeat(64),2,ContentDeliveryStore.dataReference(row));row=s.recipient(viewer,id);assertTrue(goal(row,"DATA_PAINT").matches(row,true));
        }
    }
    @Test void dataReceiptIsDurableButNewAcceptanceAndNavigationCannotReuseIt()throws Exception{
        UUID id,leaseId,session=UUID.randomUUID();
        try(var s=open()){id=offer(s);leaseId=s.accept(viewer,id,UUID.randomUUID()).leaseId();s.attach(viewer,id,leaseId,session);s.received(viewer,id,leaseId,session);s.painted(viewer,id,leaseId,session,"doc",1,"b".repeat(64),1,ContentDeliveryStore.dataReference(s.recipient(viewer,id)));}
        try(var s=open()){var row=s.recipient(viewer,id);assertNotNull(row.dataReceipt());assertFalse(goal(row,"DATA_PAINT").matches(row,true));var fresh=s.accept(viewer,id,UUID.randomUUID());assertNull(s.recipient(viewer,id).dataReceipt());var current=UUID.randomUUID();s.attach(viewer,id,fresh.leaseId(),current);s.received(viewer,id,fresh.leaseId(),current);s.painted(viewer,id,fresh.leaseId(),current,"new",1,"c".repeat(64),1,ContentDeliveryStore.dataReference(row));s.navigated(viewer,id,fresh.leaseId(),current);assertNull(s.recipient(viewer,id).dataReceipt());assertThrows(SecurityException.class,()->s.painted(viewer,id,leaseId,session,"doc",2,"b".repeat(64),1,ContentDeliveryStore.dataReference(row)));}
    }
    @Test void closeRequestOrRevocationDoesNotProveRemoteClose()throws Exception{
        try(var s=open()){var id=offer(s);var lease=s.accept(viewer,id,UUID.randomUUID());var session=UUID.randomUUID();s.attach(viewer,id,lease.leaseId(),session);s.received(viewer,id,lease.leaseId(),session);s.revoke(context(),id);var row=s.recipient(viewer,id);assertTrue(goal(row,"RECORD").matches(row,false));assertFalse(goal(row,"CLOSE_ACK").matches(row,false));s.closed(viewer,id,lease.leaseId(),session);row=s.recipient(viewer,id);assertTrue(goal(row,"CLOSE_ACK").matches(row,false));assertFalse(goal(row,"ASSET_PAINT").matches(row,true));}
    }
    @Test void parserPinsEveryFieldAndRejectsDuplicateRowsUnknownEvidenceAndFakeHumanReading()throws Exception{
        try(var s=open()){var row=s.recipient(viewer,offer(s));var g=goal(row,"RECORD");String request=new ObjectMapper().writeValueAsString(Map.of("checks",List.of(g.wire())));assertEquals("content_delivery",WorldGoalCheck.parse(request).getFirst().kind());
            assertThrows(IllegalArgumentException.class,()->WorldGoalCheck.parse(new ObjectMapper().writeValueAsString(Map.of("checks",List.of(g.wire(),g.wire())))));
            for(String bad:List.of("HUMAN_READ","BUSINESS_COMPLETED","","rendered")){var n=new LinkedHashMap<>(g.wire());n.put("evidence",bad);assertThrows(IllegalArgumentException.class,()->DeliveryGoal.parse(new ObjectMapper().valueToTree(n)));}
            var n=new LinkedHashMap<>(g.wire());n.put("actor_id",UUID.randomUUID());assertThrows(IllegalArgumentException.class,()->DeliveryGoal.parse(new ObjectMapper().valueToTree(n)));
        }
    }
}
