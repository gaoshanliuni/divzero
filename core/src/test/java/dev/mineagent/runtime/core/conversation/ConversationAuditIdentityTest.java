package dev.mineagent.runtime.core.conversation;

import dev.mineagent.runtime.core.audit.AuditLogService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ConversationAuditIdentityTest {
    @TempDir Path root;
    private final UUID world=UUID.randomUUID(),viewer=UUID.randomUUID(),agent=UUID.randomUUID(),source=UUID.randomUUID();
    private final Clock clock=Clock.fixed(Instant.parse("2026-11-07T00:00:00Z"),ZoneOffset.UTC);
    private Path database(){return root.resolve("audit.db");}
    private ConversationAuditImporter.Identity identity(){return new ConversationAuditImporter.Identity(world,viewer,agent,source,1,"LIVE_SERVER_SESSION");}
    private void append(String actor,String action,String text)throws Exception{try(var audit=AuditLogService.open(database(),world,clock)){audit.record(actor,action,source.toString(),text);}}
    private ConversationAuditImporter.Job start(ConversationStore store)throws Exception{var p=store.auditPreview(viewer,agent,source,identity());assertEquals("VERIFIED",p.state());return store.auditStart(viewer,agent,source,identity(),p.upperSequence(),p.availableRecords(),p.identityHash());}
    @Test void reusesRecordedIdsAndChecksOriginalEvidenceInsteadOfLegacyDigestMetadata()throws Exception{
        append(viewer.toString(),"CONVERSATION_PROMPT","first prompt");append("worker","CONVERSATION_RESPONSE","first reply");
        UUID archive,jobId;List<UUID> messages;
        try(var store=ConversationStore.open(database(),world,clock)){
            var job=start(store);archive=job.conversationId();jobId=job.jobId();assertEquals(4,jobId.version());assertEquals(jobId,start(store).jobId());
            job=store.auditStep(viewer,agent,job.jobId(),job.cursor());assertEquals(2,job.importedRecords());assertEquals("COMPLETE",store.auditStep(viewer,agent,job.jobId(),job.cursor()).state());
            messages=store.messages(viewer,agent,archive,0,20).messages().stream().map(ConversationStore.Message::messageId).toList();
        }
        // Existing source_hash values are opaque historical metadata. The exact protected copy is authoritative.
        try(var db=java.sql.DriverManager.getConnection("jdbc:sqlite:"+database());var q=db.prepareStatement("UPDATE mineagent_conversation_import_sources_v1 SET source_hash=?")){q.setString(1,"a".repeat(64));q.executeUpdate();}
        append("worker","CONVERSATION_RESPONSE","later reply");
        try(var store=ConversationStore.open(database(),world,clock)){
            var job=start(store);assertNotEquals(jobId,job.jobId());assertEquals(archive,job.conversationId());job=store.auditStep(viewer,agent,job.jobId(),0);assertEquals(1,job.importedRecords());
            assertEquals("COMPLETE",store.auditStep(viewer,agent,job.jobId(),job.cursor()).state());assertEquals(3,store.get(viewer,agent,archive).messageCount());
            assertTrue(store.messages(viewer,agent,archive,0,20).messages().stream().map(ConversationStore.Message::messageId).toList().containsAll(messages));
            assertEquals("a".repeat(64),store.auditSource(viewer,agent,archive,messages.getFirst()).get("sourceHash"));
        }
        try(var db=java.sql.DriverManager.getConnection("jdbc:sqlite:"+database());var q=db.prepareStatement("UPDATE mineagent_runtime_audit SET payload='changed prompt' WHERE world_id=? AND target=? AND action='CONVERSATION_PROMPT'")){q.setString(1,world.toString());q.setString(2,source.toString());q.executeUpdate();}
        append("worker","CONVERSATION_RESPONSE","must not partially import");
        try(var store=ConversationStore.open(database(),world,clock)){var job=start(store);job=store.auditStep(viewer,agent,job.jobId(),0);assertEquals("BLOCKED",job.state());assertEquals("AUDIT_SOURCE_CHANGED",job.errorCode());assertEquals(3,store.get(viewer,agent,archive).messageCount());}
    }
    @Test void rejectsForeignScopeAndMixedAuthorsBeforeImport()throws Exception{
        append(viewer.toString(),"CONVERSATION_PROMPT","own prompt");
        try(var store=ConversationStore.open(database(),world,clock)){assertThrows(SecurityException.class,()->store.auditPreview(UUID.randomUUID(),agent,source,identity()));}
        append(UUID.randomUUID().toString(),"CONVERSATION_PROMPT","foreign prompt");
        try(var store=ConversationStore.open(database(),world,clock)){assertEquals("SOURCE_IDENTITY_CONFLICT",store.auditPreview(viewer,agent,source,identity()).state());}
    }
}
