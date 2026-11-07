package dev.mineagent.runtime.core.delivery;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class DeliveryGoalCoverageTest {
    @TempDir Path root;
    @Test void everyRecipientAndTheLatestUpdateMustBeCoveredWithTheRequiredEvidence()throws Exception{
        var world=UUID.randomUUID();var owner=UUID.randomUUID();var agent=UUID.randomUUID();var task=UUID.randomUUID();var a=UUID.randomUUID();var b=UUID.randomUUID();var c=new ContentDeliveryStore.Context(world,owner,agent,task,1,UUID.randomUUID());
        try(var store=ContentDeliveryStore.open(root.resolve("db"),world,()->1000L,new ContentDeliveryStore.Port(){public void authorize(ContentDeliveryStore.Context x){}public String authority(ContentDeliveryStore.Delivery d){return "grant";}})){
            var batch=store.offer(c,new ContentDeliveryStore.Asset(UUID.randomUUID(),1,"a".repeat(64),"ui/index.html","CONTENT","fixture"),UUID.randomUUID(),List.of(new ContentDeliveryStore.Recipient(a,"ELIGIBLE"),new ContentDeliveryStore.Recipient(b,"UNAVAILABLE")),"{}",60000,"grant");var rows=store.query(c,batch.id());
            var goals=new ArrayList<DeliveryGoal>();for(var d:rows)goals.add(goal(d,"RECORD"));DeliveryGoalCoverage.require(c,rows,goals,List.of());
            assertThrows(IllegalStateException.class,()->DeliveryGoalCoverage.require(c,rows,List.of(goals.getFirst()),List.of()));
            var id=batch.deliveries().getFirst();var lease=store.accept(a,id,UUID.randomUUID());var session=UUID.randomUUID();store.attach(a,id,lease.leaseId(),session);store.received(a,id,lease.leaseId(),session);store.painted(a,id,lease.leaseId(),session,"doc",1,"b".repeat(64));
            var update=new ContentDeliveryStore.Context(world,owner,agent,task,1,UUID.randomUUID());var after=store.update(update,id,1,"{\"next\":1}");var effect=new DeliveryGoalCoverage.Effect("update_view",id,2,ContentDeliveryStore.dataReference(after));var all=store.query(c,batch.id());
            var weak=List.of(goal(after,"ASSET_PAINT"),goals.getLast());assertThrows(IllegalStateException.class,()->DeliveryGoalCoverage.require(c,all,weak,List.of(effect)));
            var good=List.of(goal(after,"DATA_PAINT"),goals.getLast());DeliveryGoalCoverage.require(c,all,good,List.of(effect));
            var old=new DeliveryGoal(id,after.revision(),1,ContentDeliveryStore.dataReference(id,1),after.status(),"DATA_PAINT");assertThrows(IllegalStateException.class,()->DeliveryGoalCoverage.require(c,all,List.of(old,goals.getLast()),List.of(effect)));
            var close=new DeliveryGoalCoverage.Effect("close_view",id,0,"");assertThrows(IllegalStateException.class,()->DeliveryGoalCoverage.require(c,all,good,List.of(close)));
        }
    }
    private static DeliveryGoal goal(ContentDeliveryStore.Delivery d,String e){return new DeliveryGoal(d.id(),d.revision(),d.dataRevision(),ContentDeliveryStore.dataReference(d),d.status(),e);}
}
