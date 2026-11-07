package dev.mineagent.runtime.core.directory;

import dev.mineagent.runtime.api.directory.ObjectRef;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class AudienceStoreTest {
    @TempDir Path dir;
    private final UUID world=UUID.randomUUID(),owner=UUID.randomUUID(),agent=UUID.randomUUID(),task=UUID.randomUUID(),server=UUID.randomUUID();
    private final LinkedHashMap<UUID,ObjectRef> roster=new LinkedHashMap<>();private final AtomicInteger selects=new AtomicInteger();
    private boolean permitted=true;private long permission=1;private Runnable duringSelect=()->{};
    private boolean changeAfterAuthorization;
    private final Clock clock=Clock.fixed(Instant.ofEpochMilli(1000),ZoneOffset.UTC);
    private AudienceStore.Context ctx(UUID operation){return new AudienceStore.Context(new ObjectDirectory.Scope(world,agent,owner,task,1,permission),server,operation);}
    private ObjectRef player(){UUID id=UUID.randomUUID();var ref=new ObjectRef(world,ObjectRef.Kind.PLAYER,id.toString(),UUID.randomUUID(),1);roster.put(id,ref);return ref;}
    private final AudienceStore.Port port=new AudienceStore.Port(){
        public void authorize(AudienceStore.Context c){if(!permitted||c.scope().authorityRevision()!=permission)throw new SecurityException("AUDIENCE_FORBIDDEN");if(changeAfterAuthorization){permission++;changeAfterAuthorization=false;}}
        public List<ObjectRef> select(AudienceStore.Context c,ObjectQuery q){selects.incrementAndGet();duringSelect.run();return List.copyOf(roster.values());}
        public AudienceStore.Recipient assess(AudienceStore.Context c,ObjectQuery q,UUID id){var ref=roster.get(id);return new AudienceStore.Recipient(id,ref==null?"UNAVAILABLE":"ELIGIBLE",ref);}
    };
    private AudienceStore open()throws Exception{return AudienceStore.open(dir.resolve("runtime.db"),world,clock,port);}
    private AudienceStore.Create request(String mode){return new AudienceStore.Create(mode,ObjectQuery.parse("{\"kind\":\"PLAYER\"}"),3600);}
    @Test void snapshotMembersStayFixedWhileEveryNewResolutionAssessesCurrentEligibility()throws Exception{
        var first=player();try(var store=open()){
            var definition=store.create(ctx(UUID.randomUUID()),request("SNAPSHOT"));var later=player();
            var a=store.resolve(ctx(UUID.randomUUID()),definition.audienceId(),1,UUID.randomUUID());assertEquals(List.of(UUID.fromString(first.id())),a.recipients().stream().map(AudienceStore.Recipient::playerId).toList());
            roster.remove(UUID.fromString(first.id()));var b=store.resolve(ctx(UUID.randomUUID()),definition.audienceId(),1,UUID.randomUUID());assertEquals("UNAVAILABLE",b.recipients().getFirst().status());assertFalse(b.recipients().stream().anyMatch(r->r.playerId().toString().equals(later.id())));assertEquals(1,selects.get());
        }
    }
    @Test void liveExpandsOnlyForANewTriggerNotForOperationOrTriggerRetries()throws Exception{
        player();try(var store=open()){
            var definition=store.create(ctx(UUID.randomUUID()),request("LIVE_AT_TRIGGER"));assertEquals(0,selects.get());UUID operation=UUID.randomUUID(),trigger=UUID.randomUUID();
            var first=store.resolve(ctx(operation),definition.audienceId(),1,trigger);player();
            assertEquals(first,store.resolve(ctx(operation),definition.audienceId(),1,trigger));assertEquals(first,store.resolve(ctx(UUID.randomUUID()),definition.audienceId(),1,trigger));assertEquals(1,selects.get());
            assertEquals(2,store.resolve(ctx(UUID.randomUUID()),definition.audienceId(),1,UUID.randomUUID()).recipients().size());assertEquals(2,selects.get());
        }
    }
    @Test void sameOperationWithDifferentParametersAndOtherTaskTriggerReuseAreRejected()throws Exception{
        try(var store=open()){
            UUID op=UUID.randomUUID();var definition=store.create(ctx(op),request("LIVE_AT_TRIGGER"));assertThrows(IllegalArgumentException.class,()->store.create(ctx(op),request("SNAPSHOT")));
            UUID trigger=UUID.randomUUID();store.resolve(ctx(UUID.randomUUID()),definition.audienceId(),1,trigger);
            var foreign=new AudienceStore.Context(new ObjectDirectory.Scope(world,agent,owner,UUID.randomUUID(),1,permission),server,UUID.randomUUID());assertThrows(IllegalArgumentException.class,()->store.resolve(foreign,definition.audienceId(),1,trigger));
        }
    }
    @Test void committedSnapshotsSurviveRestartWithoutSelectingAgainAndUnknownIntentsNeverReplay()throws Exception{
        player();UUID create=UUID.randomUUID(),resolve=UUID.randomUUID(),trigger=UUID.randomUUID(),pending=UUID.randomUUID();AudienceStore.Definition definition;AudienceStore.Snapshot snapshot;
        try(var store=open()){definition=store.create(ctx(create),request("LIVE_AT_TRIGGER"));snapshot=store.resolve(ctx(resolve),definition.audienceId(),1,trigger);store.beginCreate(ctx(pending),request("SNAPSHOT"));}
        player();try(var store=open()){
            var fresh=new AudienceStore.Context(ctx(resolve).scope(),UUID.randomUUID(),resolve);assertEquals(snapshot,store.resolve(fresh,definition.audienceId(),1,trigger));assertEquals(1,selects.get());
            assertThrows(IllegalStateException.class,()->store.create(ctx(pending),request("SNAPSHOT")));assertEquals("INTERRUPTED",store.operation(ctx(pending),pending).state());assertEquals(1,selects.get());
        }
    }
    @Test void authorityIsCheckedBeforeCacheReplayAndAgainAfterSelection()throws Exception{
        player();try(var store=open()){
            UUID op=UUID.randomUUID();var definition=store.create(ctx(op),request("LIVE_AT_TRIGGER"));permitted=false;assertThrows(SecurityException.class,()->store.create(ctx(op),request("LIVE_AT_TRIGGER")));permitted=true;
            var context=ctx(UUID.randomUUID());duringSelect=()->permission++;assertThrows(SecurityException.class,()->store.resolve(context,definition.audienceId(),1,UUID.randomUUID()));
            assertEquals("FAILED",store.operation(ctx(context.operationId()),context.operationId()).state());
        }
    }
    @Test void revocationDuringResolutionPreventsCommitAndAllOldResolutionReplay()throws Exception{
        player();try(var store=open()){
            var definition=store.create(ctx(UUID.randomUUID()),request("LIVE_AT_TRIGGER"));duringSelect=()->{try{store.revoke(ctx(UUID.randomUUID()),definition.audienceId(),1);}catch(Exception e){throw new IllegalStateException(e);}};
            UUID op=UUID.randomUUID(),trigger=UUID.randomUUID();assertThrows(IllegalStateException.class,()->store.resolve(ctx(op),definition.audienceId(),1,trigger));
            assertEquals("REVOKED",store.inspect(ctx(UUID.randomUUID()),definition.audienceId()).state());assertEquals("FAILED",store.operation(ctx(op),op).state());
        }
    }
    @Test void oversizedRecipientSetsFailRatherThanBeSilentlyTruncated()throws Exception{
        for(int i=0;i<65;i++)player();try(var store=open()){UUID op=UUID.randomUUID();assertThrows(IllegalArgumentException.class,()->store.create(ctx(op),request("SNAPSHOT")));assertEquals("FAILED",store.operation(ctx(op),op).state());}
    }
    @Test void onlyOneLiveStoreOwnsRecoveryForAWorld()throws Exception{
        try(var store=open()){assertThrows(IllegalStateException.class,this::open);}
    }
    @Test void unknownRecipientStateCannotCarryAnOtherwiseValidReference(){
        var ref=player();assertThrows(IllegalArgumentException.class,()->new AudienceStore.Recipient(UUID.fromString(ref.id()),"UNKNOWN",ref));
        assertThrows(IllegalArgumentException.class,()->new AudienceStore.Recipient(UUID.fromString(ref.id()),"FILTER_CHANGED",ref));
    }
    @Test void expiredDefinitionsDoNotReplayOldSuccessfulResolutions()throws Exception{
        UUID audience,operation=UUID.randomUUID(),trigger=UUID.randomUUID();try(var store=open()){var d=store.create(ctx(UUID.randomUUID()),new AudienceStore.Create("LIVE_AT_TRIGGER",request("LIVE_AT_TRIGGER").query(),1));audience=d.audienceId();store.resolve(ctx(operation),audience,1,trigger);}
        try(var store=AudienceStore.open(dir.resolve("runtime.db"),world,Clock.fixed(Instant.ofEpochMilli(3000),ZoneOffset.UTC),port)){assertThrows(IllegalStateException.class,()->store.resolve(ctx(operation),audience,1,trigger));}
    }
    @Test void revocationRechecksAuthorityAtTheCommitBoundary()throws Exception{
        try(var store=open()){var d=store.create(ctx(UUID.randomUUID()),request("LIVE_AT_TRIGGER"));var c=ctx(UUID.randomUUID());changeAfterAuthorization=true;
            assertThrows(SecurityException.class,()->store.revoke(c,d.audienceId(),1));assertEquals("ACTIVE",store.inspect(ctx(UUID.randomUUID()),d.audienceId()).state());}
    }
    @Test void aReentrantSelectionCannotOvercommitTheOwnerQuota()throws Exception{
        try(var store=open()){
            for(int i=0;i<63;i++)store.create(ctx(UUID.randomUUID()),request("LIVE_AT_TRIGGER"));
            duringSelect=()->{try{store.create(ctx(UUID.randomUUID()),request("LIVE_AT_TRIGGER"));}catch(Exception e){throw new IllegalStateException(e);}};
            assertThrows(IllegalStateException.class,()->store.create(ctx(UUID.randomUUID()),request("SNAPSHOT")));
        }
    }
    @Test void legacyCreateAndResolveReceiptsReuseStoredIdentitiesAndSelection()throws Exception{
        player();UUID create=UUID.randomUUID(),resolve=UUID.randomUUID(),trigger=UUID.randomUUID(),audience,snapshot;var request=request("LIVE_AT_TRIGGER");
        try(var store=open()){audience=store.create(ctx(create),request).audienceId();snapshot=store.resolve(ctx(resolve),audience,1,trigger).snapshotId();}
        int before=selects.get();
        try(var db=java.sql.DriverManager.getConnection("jdbc:sqlite:"+dir.resolve("runtime.db"));var update=db.prepareStatement("UPDATE mineagent_audience_operations_v1 SET payload=json_set(payload,'$.fingerprint',?) WHERE world=?")){
            update.setString(1,"a".repeat(64));update.setString(2,world.toString());assertEquals(2,update.executeUpdate());
        }
        try(var store=open()){
            assertEquals(audience,store.create(ctx(create),request).audienceId());assertEquals(snapshot,store.resolve(ctx(resolve),audience,1,trigger).snapshotId());assertEquals(before,selects.get());
            assertThrows(IllegalArgumentException.class,()->store.create(ctx(create),request("SNAPSHOT")));
            assertEquals(snapshot,store.resolve(ctx(UUID.randomUUID()),audience,1,trigger).snapshotId());assertEquals(before,selects.get());
        }
    }

}
