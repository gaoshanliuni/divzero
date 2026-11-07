package dev.mineagent.runtime.core.delivery;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class ContentDeliveryStoreTest {
    @Test void feedbackOriginAndExactPolicyPinPersistWithOffer()throws Exception{
        var c=context(UUID.randomUUID());var base=asset();var asset=new ContentDeliveryStore.Asset(base.packageId(),base.revision(),base.canonical(),base.entry(),base.mode(),base.title(),"b".repeat(64));UUID id;
        try(var s=open()){id=s.offer(c,asset,UUID.randomUUID(),List.of(new ContentDeliveryStore.Recipient(a,"ELIGIBLE")),"{}",60000,authority).deliveries().getFirst();assertEquals(c,s.recipient(a,id).origin());}
        try(var s=open()){assertEquals(c,s.recipient(a,id).origin());assertEquals("b".repeat(64),s.recipient(a,id).asset().feedbackPolicySha256());}
    }
    @TempDir Path root;final UUID world=UUID.randomUUID(),owner=UUID.randomUUID(),agent=UUID.randomUUID(),a=UUID.randomUUID(),b=UUID.randomUUID();
    final AtomicLong now=new AtomicLong(1000);String authority="grant1";boolean allowed=true;
    ContentDeliveryStore.Context context(UUID operation){return new ContentDeliveryStore.Context(world,owner,agent,UUID.randomUUID(),1,operation);}
    ContentDeliveryStore.Asset asset(){return new ContentDeliveryStore.Asset(UUID.randomUUID(),1,"a".repeat(64),"ui/index.html","CONTENT","Test content");}
    ContentDeliveryStore open()throws Exception{return ContentDeliveryStore.open(root.resolve("runtime.db"),world,now::get,new ContentDeliveryStore.Port(){public void authorize(ContentDeliveryStore.Context c){if(!allowed)throw new SecurityException("REVOKED");}public String authority(ContentDeliveryStore.Delivery d){return authority;}});}
    ContentDeliveryStore.Batch offer(ContentDeliveryStore s)throws Exception{return s.offer(context(UUID.randomUUID()),asset(),UUID.randomUUID(),List.of(new ContentDeliveryStore.Recipient(a,"ELIGIBLE"),new ContentDeliveryStore.Recipient(b,"ELIGIBLE")),"{\"value\":1}",60000,authority);}
    @Test void duplicateOfferHasFrozenRecipientsAndAcceptancesHaveIndependentLeases()throws Exception{
        try(var s=open()){var ctx=context(UUID.randomUUID());var asset=asset();var audience=UUID.randomUUID();var people=List.of(new ContentDeliveryStore.Recipient(a,"ELIGIBLE"),new ContentDeliveryStore.Recipient(b,"ELIGIBLE"));var first=s.offer(ctx,asset,audience,people,"{}",60000,authority);assertEquals(first,s.offer(ctx,asset,audience,people,"{}",60000,authority));assertThrows(IllegalArgumentException.class,()->s.offer(ctx,asset,audience,List.of(people.getFirst()),"{}",60000,authority));
            var rows=s.query(ctx,first.id());var x=s.accept(a,rows.getFirst().id(),UUID.randomUUID());var y=s.accept(b,rows.getLast().id(),UUID.randomUUID());assertNotEquals(x.leaseId(),y.leaseId());assertNotEquals(x.viewId(),y.viewId());assertThrows(SecurityException.class,()->s.accept(b,rows.getFirst().id(),UUID.randomUUID()));}
    }
    @Test void sendingAndDomAdmissionAreNotPaintAndDataUpdatesNeverReopenAClosedPage()throws Exception{
        try(var s=open()){var batch=offer(s);var ctx=context(UUID.randomUUID());var row=s.query(ctx,batch.id()).getFirst();var lease=s.accept(a,row.id(),UUID.randomUUID());var session=UUID.randomUUID();s.attach(a,row.id(),lease.leaseId(),session);
            assertEquals("OPENING",s.recipient(a,row.id()).status());assertThrows(IllegalStateException.class,()->s.painted(a,row.id(),lease.leaseId(),session,"doc",1,"b".repeat(64)));
            s.received(a,row.id(),lease.leaseId(),session);assertEquals("CLIENT_RECEIVED",s.recipient(a,row.id()).status());s.painted(a,row.id(),lease.leaseId(),session,"doc",1,"b".repeat(64));assertEquals("RENDERED",s.recipient(a,row.id()).status());
            s.closed(a,row.id(),lease.leaseId(),session);s.update(ctx,row.id(),1,"{\"value\":2}");assertEquals("CLOSED",s.recipient(a,row.id()).status());assertEquals(2,s.recipient(a,row.id()).dataRevision());assertThrows(IllegalStateException.class,()->s.accept(a,row.id(),UUID.randomUUID()));}
    }
    @Test void revokeRejectsOldRequestsButDoesNotPretendAnOfflineClientClosed()throws Exception{
        try(var s=open()){var batch=offer(s);var ctx=context(UUID.randomUUID());var row=s.query(ctx,batch.id()).getFirst();var lease=s.accept(a,row.id(),UUID.randomUUID());var session=UUID.randomUUID();s.attach(a,row.id(),lease.leaseId(),session);s.received(a,row.id(),lease.leaseId(),session);
            s.revoke(ctx,row.id());assertEquals("REVOKED",s.recipient(a,row.id()).status());assertFalse(s.recipient(a,row.id()).closeConfirmed());assertFalse(s.permits(a,row.id(),lease.leaseId(),session));s.closed(a,row.id(),lease.leaseId(),session);assertTrue(s.recipient(a,row.id()).closeConfirmed());assertEquals("REVOKED",s.recipient(a,row.id()).status());assertEquals("OFFERED",s.query(ctx,batch.id()).getLast().status());}
    }
    @Test void restartAndDisconnectRequireNewExplicitAcceptanceAndCannotReplayOldLease()throws Exception{
        UUID id,oldOp=UUID.randomUUID(),leaseId;try(var s=open()){id=s.query(context(UUID.randomUUID()),offer(s).id()).getFirst().id();var l=s.accept(a,id,oldOp);leaseId=l.leaseId();s.attach(a,id,leaseId,UUID.randomUUID());}
        try(var s=open()){assertEquals("SUSPENDED",s.recipient(a,id).status());assertThrows(IllegalStateException.class,()->s.accept(a,id,oldOp));var fresh=s.accept(a,id,UUID.randomUUID());assertNotEquals(leaseId,fresh.leaseId());s.disconnect(a);assertEquals("SUSPENDED",s.recipient(a,id).status());}
    }
    @Test void authorityChangeAndExpiryAreMonotoneAndRecipientListDoesNotExposePeers()throws Exception{
        try(var s=open()){offer(s);assertEquals(1,s.inbox(a,0).size());authority="grant2";s.reconcile();assertEquals("REVOKED",s.inbox(a,0).getFirst().status());authority="grant1";s.reconcile();assertEquals("REVOKED",s.inbox(a,0).getFirst().status());var next=offer(s);now.set(100000);s.reconcile();assertEquals("EXPIRED",s.query(context(UUID.randomUUID()),next.id()).getFirst().status());}
    }
    @Test void newAcceptanceStartsANewPaintTimelineAndRejectsPreviousSession()throws Exception{
        try(var s=open()){
            var id=offer(s).deliveries().getFirst();var old=s.accept(a,id,UUID.randomUUID());var previous=UUID.randomUUID();
            s.attach(a,id,old.leaseId(),previous);s.received(a,id,old.leaseId(),previous);s.painted(a,id,old.leaseId(),previous,"old-document",500,"b".repeat(64));
            s.disconnect(a);var fresh=s.accept(a,id,UUID.randomUUID());var current=UUID.randomUUID();var row=s.recipient(a,id);
            assertEquals(0,row.paintedAt());assertEquals(0,row.paintSequence());assertEquals("",row.documentId());assertEquals("",row.layoutHash());assertNull(row.sessionId());
            s.attach(a,id,fresh.leaseId(),current);s.received(a,id,fresh.leaseId(),current);
            assertThrows(SecurityException.class,()->s.closed(a,id,old.leaseId(),previous));
            assertThrows(SecurityException.class,()->s.painted(a,id,old.leaseId(),previous,"old-document",501,"b".repeat(64)));
            s.painted(a,id,fresh.leaseId(),current,"new-document",1,"c".repeat(64));assertEquals("RENDERED",s.recipient(a,id).status());assertEquals(2,s.recipient(a,id).attempt());
        }
    }
    @Test void terminalCloseRequestsCannotEraseRevocationExpiryOrRejection()throws Exception{
        try(var s=open()){
            var revoked=offer(s).deliveries().getFirst();s.revoke(context(UUID.randomUUID()),revoked);
            var close=context(UUID.randomUUID());s.requestClose(close,revoked);s.requestClose(close,revoked);assertEquals("REVOKED",s.recipient(a,revoked).status());
            var rejected=offer(s).deliveries().getFirst();s.reject(a,rejected,UUID.randomUUID());s.requestClose(context(UUID.randomUUID()),rejected);assertEquals("REJECTED",s.recipient(a,rejected).status());
            var expired=offer(s).deliveries().getFirst();now.set(100000);s.reconcile();s.requestClose(context(UUID.randomUUID()),expired);s.revoke(context(UUID.randomUUID()),expired);assertEquals("EXPIRED",s.recipient(a,expired).status());
        }
    }
    @Test void failedDisplayRetainsFailureAfterActualCloseAcknowledgement()throws Exception{
        try(var s=open()){
            var id=offer(s).deliveries().getFirst();var lease=s.accept(a,id,UUID.randomUUID());var session=UUID.randomUUID();s.attach(a,id,lease.leaseId(),session);
            s.fail(a,id,lease.leaseId(),"CLIENT_LOAD_FAILED");s.closed(a,id,lease.leaseId(),session);
            var row=s.recipient(a,id);assertEquals("FAILED",row.status());assertEquals("CLIENT_LOAD_FAILED",row.error());assertTrue(row.closeConfirmed());assertFalse(s.permits(a,id,lease.leaseId(),session));
        }
    }
    @Test void anotherStoreCannotRunRecoveryAgainstAnActiveWorld()throws Exception{
        UUID id;
        try(var s=open()){
            id=offer(s).deliveries().getFirst();s.accept(a,id,UUID.randomUUID());
            var failure=assertThrows(IllegalStateException.class,()->{try(var other=open()){fail("second active delivery store opened");}});
            assertEquals("DELIVERY_STORE_ALREADY_OPEN",failure.getMessage());assertEquals("OPENING",s.recipient(a,id).status());
        }
        try(var recovered=open()){assertEquals("SUSPENDED",recovered.recipient(a,id).status());}
    }
    @Test void clientCodeRequiresSeparateDownloadAndRuntimeReportsWithoutCreatingDisplayLease()throws Exception{try(var s=open()){var code=new ContentDeliveryStore.Asset(UUID.randomUUID(),1,"a".repeat(64),"client/main.js","CLIENT_RHINO","Client code");var ctx=context(UUID.randomUUID());UUID id=s.offer(ctx,code,UUID.randomUUID(),List.of(new ContentDeliveryStore.Recipient(a,"ELIGIBLE")),"{}",60000,authority).deliveries().getFirst();UUID accept=UUID.randomUUID();var accepted=s.acceptCode(a,id,accept);assertEquals(1,accepted.attempt());assertEquals("CODE_DOWNLOADING",s.recipient(a,id).status());assertNull(s.recipient(a,id).leaseId());assertThrows(IllegalStateException.class,()->s.codeState(a,id,UUID.randomUUID(),"RUNNING",""));s.codeState(a,id,UUID.randomUUID(),"DOWNLOADED","");assertEquals("CODE_DOWNLOADED",s.recipient(a,id).status());assertThrows(IllegalStateException.class,()->s.acceptCode(a,id,accept));assertThrows(IllegalStateException.class,()->s.update(context(UUID.randomUUID()),id,1,"{}"));s.codeState(a,id,UUID.randomUUID(),"RUNNING","");s.disconnect(a);assertEquals("CODE_SUSPENDED",s.recipient(a,id).status());s.acceptCode(a,id,UUID.randomUUID());s.codeState(a,id,UUID.randomUUID(),"DOWNLOADED","");s.codeState(a,id,UUID.randomUUID(),"STOPPED","");assertEquals("CODE_STOPPED",s.recipient(a,id).status());}}
    @Test void identicalDataInDifferentDeliveriesHasSeparateVersionIdentityAndLegacyUnknownInputCannotReplay()throws Exception{
        var context=context(UUID.randomUUID());var asset=asset();UUID audience=UUID.randomUUID(),id;var recipients=List.of(new ContentDeliveryStore.Recipient(a,"ELIGIBLE"),new ContentDeliveryStore.Recipient(b,"ELIGIBLE"));
        try(var store=open()){
            var batch=store.offer(context,asset,audience,recipients,"{}",60000,authority);var rows=store.query(context,batch.id());id=rows.getFirst().id();
            assertNotEquals(ContentDeliveryStore.dataReference(rows.getFirst()),ContentDeliveryStore.dataReference(rows.getLast()));
            assertEquals(ContentDeliveryStore.dataReference(id,1),ContentDeliveryStore.dataReference(rows.getFirst()));
        }
        try(var db=java.sql.DriverManager.getConnection("jdbc:sqlite:"+root.resolve("runtime.db"));var update=db.prepareStatement("UPDATE mineagent_delivery_operations_v1 SET payload=json_set(payload,'$.fingerprint',?) WHERE world=? AND id=?")){
            update.setString(1,"a".repeat(64));update.setString(2,world.toString());update.setString(3,context.operation().toString());assertEquals(1,update.executeUpdate());
        }
        try(var store=open()){
            assertEquals("OFFERED",store.inspect(context,id).status());assertEquals("{}",store.inspect(context,id).data());
            var error=assertThrows(IllegalStateException.class,()->store.offer(context,asset,audience,recipients,"{}",60000,authority));assertEquals("DELIVERY_LEGACY_INPUT_UNAVAILABLE_INSPECT_RECEIPT",error.getMessage());
            assertEquals(1,store.inbox(a,0).size());assertEquals(1,store.inbox(b,0).size());
        }
    }

}
