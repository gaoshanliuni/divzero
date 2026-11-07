package dev.mineagent.runtime.core.shared;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class SharedStateStoreTest {
    @Test void exactTransactionReceiptIsReadOnlyAndPinsThePlanContextAndCausation()throws Exception{
        try(var s=open()){define(s);var c=ctx(alice);var op=UUID.randomUUID();var origin=new SharedStateStore.Provenance(UUID.randomUUID(),1,List.of());String request=enter("stored-once");
            assertTrue(s.transactionReceipt(c,op,request,origin).isEmpty());var receipt=s.transact(c,op,request,origin);var before=s.read(c);
            assertEquals(receipt,s.transactionReceipt(c,op,request,origin).orElseThrow());assertEquals(before,s.read(c));
            assertThrows(SecurityException.class,()->s.transactionReceipt(ctx(bob),op,request,origin));
            assertThrows(IllegalArgumentException.class,()->s.transactionReceipt(c,op,enter("changed-plan"),origin));
            assertThrows(IllegalArgumentException.class,()->s.transactionReceipt(c,op,request,SharedStateStore.Provenance.NONE));
            current.set(false);assertThrows(SecurityException.class,()->s.transactionReceipt(c,op,request,origin));
        }
    }
    @Test void feedbackSubjectKeepsAuthorPartitionButNeverInheritsOwnerFields()throws Exception{
        try(var s=open()){define(s);var user=ctx(owner);var f=new SharedStateStore.Context(user.scope(),owner,owner,1,user.canonical(),"FEEDBACK");
            assertFalse(s.read(f).values().containsKey("secret"));assertThrows(SecurityException.class,()->s.define(f,UUID.randomUUID(),2,SCHEMA));UUID operation=UUID.randomUUID();var receipt=s.transact(f,operation,enter("feedback-author"));assertEquals("APPLIED",receipt.status());assertEquals("feedback-author",s.read(ctx(owner)).values().get("entry").asText());assertEquals(receipt,s.receipt(f,operation));
            assertThrows(SecurityException.class,()->s.receipt(ctx(owner),operation));assertThrows(SecurityException.class,()->s.transact(f,UUID.randomUUID(),"{\"schema_version\":1,\"conditions\":[],\"writes\":[{\"key\":\"secret\",\"op\":\"PUT\",\"value\":\"forged\"}]}"));current.set(false);assertThrows(SecurityException.class,()->s.receipt(f,operation));
        }
    }
    @TempDir Path root;
    final UUID world=UUID.randomUUID(),pack=UUID.randomUUID(),instance=UUID.randomUUID(),owner=UUID.randomUUID(),alice=UUID.randomUUID(),bob=UUID.randomUUID();
    final AtomicBoolean current=new AtomicBoolean(true);
    SharedStateStore.Context ctx(UUID actor){return new SharedStateStore.Context(new SharedStateStore.Scope(world,pack,instance,"example"),owner,actor,1,"a".repeat(64));}
    SharedStateStore open()throws Exception{return SharedStateStore.open(root.resolve("runtime.db"),world,(c,write,admin)->current.get()&&(!admin||c.actor().equals(owner)));}
    static final String SCHEMA="""
        {"version":1,"fields":{"count":{"type":"INTEGER","scope":"SHARED","read":"PARTICIPANT","write":"PARTICIPANT","minimum":0,"maximum":1},"entry":{"type":"STRING"},"secret":{"type":"STRING","scope":"SHARED","read":"OWNER","write":"OWNER"}}}
        """;
    void define(SharedStateStore store)throws Exception{store.define(ctx(owner),UUID.randomUUID(),0,SCHEMA);store.transact(ctx(owner),UUID.randomUUID(),"{\"schema_version\":1,\"conditions\":[],\"writes\":[{\"key\":\"count\",\"op\":\"PUT\",\"value\":0},{\"key\":\"secret\",\"op\":\"PUT\",\"value\":\"owner-secret\"}]}");}
    String enter(String text){return "{\"schema_version\":1,\"conditions\":[{\"key\":\"count\",\"test\":\"LT\",\"value\":1},{\"key\":\"entry\",\"test\":\"ABSENT\"}],\"writes\":[{\"key\":\"count\",\"op\":\"ADD\",\"value\":1},{\"key\":\"entry\",\"op\":\"PUT_IF_ABSENT\",\"value\":\""+text+"\"}]}";}
    @Test void independentSqlConnectionsCannotOversubscribeAndPrivateRecordsDoNotCrossActors()throws Exception{
        try(var one=open();var two=open()){define(one);var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
            try{var a=pool.submit(()->{start.await();return one.transact(ctx(alice),UUID.randomUUID(),enter("alice"));});var b=pool.submit(()->{start.await();return two.transact(ctx(bob),UUID.randomUUID(),enter("bob"));});start.countDown();
                var results=List.of(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS));assertEquals(1,results.stream().filter(r->r.status().equals("APPLIED")).count());assertEquals(1,results.stream().filter(r->r.status().equals("CONFLICT")).count());
                var av=one.read(ctx(alice));var bv=two.read(ctx(bob));assertEquals(1,av.values().get("count").asInt());assertEquals(1,bv.values().get("count").asInt());assertFalse(av.values().containsKey("secret"));assertFalse(bv.values().containsKey("secret"));assertNotEquals(av.values().containsKey("entry"),bv.values().containsKey("entry"));
                UUID loser=av.values().containsKey("entry")?bob:alice;var feed=one.watch(ctx(loser),2);assertTrue(feed.events().stream().allMatch(e->!e.keys().contains("entry")&&!e.keys().contains("secret")));assertTrue(feed.events().stream().anyMatch(e->e.keys().contains("count")));
            }finally{pool.shutdownNow();}
        }
    }
    @Test void sameOperationSurvivesRestartButCannotChangeActorParametersOrReadAfterRevocation()throws Exception{
        UUID op=UUID.randomUUID();SharedStateStore.Receipt receipt;try(var store=open()){define(store);receipt=store.transact(ctx(alice),op,enter("alice"));assertEquals("APPLIED",receipt.status());}
        try(var store=open()){assertEquals(receipt,store.transact(ctx(alice),op,enter("alice")));assertEquals(1,store.read(ctx(alice)).values().get("count").asInt());assertThrows(IllegalArgumentException.class,()->store.transact(ctx(bob),op,enter("bob")));assertThrows(IllegalArgumentException.class,()->store.transact(ctx(alice),op,enter("different")));current.set(false);assertThrows(SecurityException.class,()->store.transact(ctx(alice),op,enter("alice")));assertThrows(SecurityException.class,()->store.read(ctx(alice)));assertThrows(SecurityException.class,()->store.watch(ctx(alice),0));}
    }
    @Test void lateAuthorizationFailureRollsBackDataReceiptAndOutboxTogether()throws Exception{
        try(var init=open()){define(init);}AtomicInteger checks=new AtomicInteger();UUID op=UUID.randomUUID();
        try(var store=SharedStateStore.open(root.resolve("runtime.db"),world,(c,w,a)->checks.incrementAndGet()<3)){assertThrows(SecurityException.class,()->store.transact(ctx(alice),op,enter("alice")));}
        try(var store=open()){assertEquals(0,store.read(ctx(alice)).values().get("count").asInt());assertEquals(2,store.read(ctx(alice)).revision());assertTrue(store.watch(ctx(alice),2).events().isEmpty());assertEquals("APPLIED",store.transact(ctx(alice),op,enter("alice")).status());}
    }
    @Test void conditionConflictsAreDurableAndCurrentRevisionDoesNotAuthorizeBlindOverwrite()throws Exception{
        try(var s=open()){define(s);UUID op=UUID.randomUUID();String stale="{\"schema_version\":1,\"expected_revision\":0,\"conditions\":[],\"writes\":[{\"key\":\"entry\",\"op\":\"PUT\",\"value\":\"not saved\"}]}";var r=s.transact(ctx(alice),op,stale);assertEquals("CONFLICT",r.status());assertEquals(2,r.revision());s.transact(ctx(bob),UUID.randomUUID(),enter("bob"));assertEquals(r,s.transact(ctx(alice),op,stale));assertFalse(s.read(ctx(alice)).values().containsKey("entry"));}
    }
    @Test void undeclaredFieldSecretConditionWrongSchemaAndCounterOverflowNeverPartiallyWrite()throws Exception{
        try(var s=open()){define(s);for(String tx:List.of(
            "{\"schema_version\":1,\"conditions\":[],\"writes\":[{\"key\":\"entry\",\"op\":\"PUT\",\"value\":\"leak\"},{\"key\":\"unknown\",\"op\":\"PUT\",\"value\":0}]}",
            "{\"schema_version\":1,\"conditions\":[{\"key\":\"secret\",\"test\":\"EQ\",\"value\":\"guess\"}],\"writes\":[{\"key\":\"entry\",\"op\":\"PUT\",\"value\":\"leak\"}]}",
            "{\"schema_version\":1,\"conditions\":[],\"writes\":[{\"key\":\"entry\",\"op\":\"PUT\",\"value\":\"leak\"},{\"key\":\"count\",\"op\":\"ADD\",\"value\":2}]}"))assertThrows(RuntimeException.class,()->s.transact(ctx(alice),UUID.randomUUID(),tx));
            assertEquals("CONFLICT",s.transact(ctx(alice),UUID.randomUUID(),enter("alice").replace("schema_version\":1","schema_version\":2")).status());assertEquals(2,s.read(ctx(alice)).revision());assertFalse(s.read(ctx(alice)).values().containsKey("entry"));
        }
    }
    @Test void schemaMigrationIsExplicitValidatedAndAtomic()throws Exception{
        try(var s=open()){define(s);String bad=SCHEMA.replace("\"version\":1","\"version\":2").replace("\"type\":\"INTEGER\"","\"type\":\"STRING\"");assertThrows(IllegalArgumentException.class,()->s.define(ctx(owner),UUID.randomUUID(),2,bad));assertEquals(1,s.read(ctx(owner)).schemaVersion());assertEquals(2,s.read(ctx(owner)).revision());
            String good=SCHEMA.replace("\"version\":1","\"version\":2").replace("\"maximum\":1","\"maximum\":2");assertEquals("APPLIED",s.define(ctx(owner),UUID.randomUUID(),2,good).status());assertEquals(2,s.read(ctx(alice)).schemaVersion());assertThrows(SecurityException.class,()->s.define(ctx(alice),UUID.randomUUID(),3,good));assertTrue(s.watch(ctx(alice),2).events().getFirst().snapshotRequired());}
    }
    @Test void namespacesWorldsPackagesAndInstancesAreNotCallerAliases()throws Exception{
        try(var s=open()){define(s);for(var scope:List.of(new SharedStateStore.Scope(world,pack,UUID.randomUUID(),"example"),new SharedStateStore.Scope(world,UUID.randomUUID(),instance,"example"),new SharedStateStore.Scope(world,pack,instance,"other"))){assertThrows(IllegalStateException.class,()->s.read(new SharedStateStore.Context(scope,owner,alice,1,"a".repeat(64))));}
            assertThrows(SecurityException.class,()->s.read(new SharedStateStore.Context(new SharedStateStore.Scope(UUID.randomUUID(),pack,instance,"example"),owner,alice,1,"a".repeat(64))));}
    }
    @Test void strictJsonAndBoundedChangesRejectAmbiguousOrExecutableInputs()throws Exception{
        try(var s=open()){define(s);for(String tx:List.of(enter("a")+" {}",enter("a").replace("\"schema_version\":1","\"schema_version\":1,\"schema_version\":2"),enter("a").replace("\"writes\":","\"sql\":\"DELETE FROM x\",\"writes\":"))){assertThrows(IllegalArgumentException.class,()->s.transact(ctx(alice),UUID.randomUUID(),tx));}
            assertThrows(IllegalArgumentException.class,()->s.transact(ctx(alice),UUID.randomUUID(),enter("x".repeat(9000))));assertEquals(2,s.read(ctx(alice)).revision());}
    }
    @Test void packageServiceDoesNotBecomeTheHumanOwnerOfActorPartitionedRecords()throws Exception{
        UUID service=UUID.randomUUID();var packageContext=new SharedStateStore.Context(ctx(owner).scope(),owner,service,1,"a".repeat(64),"PACKAGE");
        try(var s=open()){define(s);s.transact(ctx(owner),UUID.randomUUID(),enter("human-owner-private"));assertFalse(s.read(packageContext).values().containsKey("entry"));assertEquals("owner-secret",s.read(packageContext).values().get("secret").asText());
            String encoded=new ObjectMapper().writeValueAsString(s.watch(packageContext,2));assertFalse(encoded.contains("human-owner-private"));assertFalse(encoded.contains(owner.toString()));assertFalse(encoded.contains("entry"));}
    }
    @Test void outboxRefetchNoticesPaginateWithoutLosingOrRebroadcastingPrivateValues()throws Exception{
        try(var s=open()){define(s);for(int i=0;i<23;i++)s.transact(ctx(alice),UUID.randomUUID(),"{\"schema_version\":1,\"conditions\":[],\"writes\":[{\"key\":\"entry\",\"op\":\"PUT\",\"value\":\"private-"+i+"\"}]}");
            var first=s.watch(ctx(alice),2);assertEquals(16,first.events().size());assertTrue(first.more());assertEquals(18,first.cursor());var last=s.watch(ctx(alice),first.cursor());assertEquals(7,last.events().size());assertFalse(last.more());assertEquals(25,last.cursor());assertEquals(first,s.watch(ctx(alice),2));assertTrue(s.watch(ctx(bob),2).events().isEmpty());
            try(var db=java.sql.DriverManager.getConnection("jdbc:sqlite:"+root.resolve("runtime.db"));var st=db.createStatement();var rows=st.executeQuery("SELECT payload FROM mineagent_shared_outbox_v1 WHERE revision=25")){assertTrue(rows.next());String payload=rows.getString(1);assertTrue(payload.contains(alice.toString()));assertTrue(payload.contains("PLAYER"));assertFalse(payload.contains("private-22"));}
        }
    }
}
