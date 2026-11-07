package dev.mineagent.runtime.scripting.studio;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import dev.mineagent.runtime.scripting.packagehost.RuntimePackageManager;
import dev.mineagent.runtime.core.packages.CodeDraftSources;
import dev.mineagent.runtime.core.packages.RuntimePackageCanonicalizer;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CodeDraftServiceTest {
    @TempDir
    Path temporaryDirectory;
    private final UUID world = UUID.randomUUID();
    private final UUID owner = UUID.randomUUID();
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-08T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void editsWithPreflightAndPreservesVersionHistoryAcrossRestart() throws Exception {
        Path database = temporaryDirectory.resolve("runtime.db");
        UUID draftId;
        try (var packages = new RuntimePackageManager();
             var service = CodeDraftService.open(database, world, clock, packages)) {
            var draft = service.create(owner, UUID.randomUUID(), UUID.randomUUID(), 2, 1,
                    "main.js", "1 + 1;");
            draftId = draft.draftId();

            var rejected = service.update(draftId, owner, draft.revision(), "while (true) {}");
            var updated = service.update(draftId, owner, draft.revision(), "2 + 2;");

            assertFalse(rejected.accepted());
            assertEquals("PREFLIGHT_REJECTED", rejected.errorCode());
            assertTrue(updated.accepted());
            assertEquals(2, updated.draft().revision());
            assertEquals("1 + 1;", updated.draft().history().getFirst());
        }

        try (var packages = new RuntimePackageManager();
             var reopened = CodeDraftService.open(database, world, clock, packages)) {
            assertEquals("2 + 2;", reopened.get(draftId).orElseThrow().source());
            assertEquals(1, reopened.get(draftId).orElseThrow().history().size());
        }
    }

    @Test
    void publishesOnlyMatchingTaskAndPackageRevision() throws Exception {
        try (var packages = new RuntimePackageManager();
             var service = CodeDraftService.open(
                     temporaryDirectory.resolve("runtime.db"), world, clock, packages)) {
            UUID task = UUID.randomUUID();
            UUID packageId = UUID.randomUUID();
            var draft = service.create(owner, task, packageId, 3, 1, "main.js", "host.value();");

            var stale = service.publish(draft.draftId(), owner, draft.revision(), 4, Map.of(
                    "host", new Host()));
            var published = service.publish(draft.draftId(), owner, draft.revision(), 3, Map.of(
                    "host", new Host()));

            assertFalse(stale.accepted());
            assertEquals("STALE_TASK_REVISION", stale.errorCode());
            assertTrue(published.accepted());
            assertEquals("PUBLISHED", published.draft().status().name());
            assertEquals(7.0, ((Number) published.executionResult()).doubleValue());
        }
    }

    @Test
    void persistsJavaDraftsForWorkerCompilationAndMarksExternalPublish() throws Exception {
        try (var packages = new RuntimePackageManager();
             var service = CodeDraftService.open(
                     temporaryDirectory.resolve("runtime.db"), world, clock, packages)) {
            UUID task = UUID.randomUUID();
            var draft = service.create(owner, task, UUID.randomUUID(), 5, 1, "dev/example/Extension.java", """
                    package dev.example;
                    public final class Extension { public void run() { while (true) { break; } } }
                    """);

            var updated = service.update(draft.draftId(), owner, draft.revision(), """
                    package dev.example;
                    public final class Extension { public int value() { return 7; } }
                    """);
            var staleTask = service.markExternalPublished(
                    draft.draftId(), owner, updated.draft().revision(), 6);
            var published = service.markExternalPublished(
                    draft.draftId(), owner, updated.draft().revision(), 5);

            assertTrue(updated.accepted());
            assertEquals("STALE_TASK_REVISION", staleTask.errorCode());
            assertTrue(published.accepted());
            assertEquals("PUBLISHED", published.draft().status().name());
        }
    }

    @Test
    void javaPublicationPersistsAndLocksAnExplicitNativeClasspath() throws Exception {
        Path database=temporaryDirectory.resolve("native-publication.db");UUID draftId,publication;String nativeClasspath="a".repeat(64);
        try(var packages=new RuntimePackageManager();var service=CodeDraftService.open(database,world,clock,packages)){
            var draft=service.create(owner,UUID.randomUUID(),UUID.randomUUID(),1,1,"dev/example/Extension.java","package dev.example; public final class Extension {}");draftId=draft.draftId();var prepared=service.javaPublications().prepare(draft,"dev.example.Extension",null,nativeClasspath);publication=prepared.record().id();assertTrue(prepared.dispatch());assertEquals(nativeClasspath,prepared.record().nativeClasspath());assertFalse(service.javaPublications().prepare(draft,"dev.example.Extension",null,nativeClasspath).dispatch());assertThrows(IllegalStateException.class,()->service.javaPublications().prepare(draft,"dev.example.Extension",null,"b".repeat(64)));assertDoesNotThrow(()->service.javaPublications().bindClasspath(publication,nativeClasspath));assertThrows(IllegalStateException.class,()->service.javaPublications().bindClasspath(publication,"b".repeat(64)));
        }
        try(var packages=new RuntimePackageManager();var service=CodeDraftService.open(database,world,clock,packages)){var record=service.javaPublications().get(publication);assertEquals(draftId,record.draft());assertEquals("INTERRUPTED",record.state());assertEquals(nativeClasspath,record.nativeClasspath());}
    }

    @Test
    void publishedDraftRunsOnTheInjectedServerLifecycleManager() throws Exception {
        try (var packages = new RuntimePackageManager();
             var service = CodeDraftService.open(
                     temporaryDirectory.resolve("shared.db"), world, clock, packages)) {
            UUID taskId = UUID.randomUUID();
            var host = new CountingHost();
            var draft = service.create(owner, taskId, UUID.randomUUID(), 1, 1, "main.js", """
                    on('server.shared.tick', function () { host.run(); });
                    'ready';
                    """);

            assertTrue(service.publish(draft.draftId(), owner, 1, 1, Map.of("host", host)).accepted());
            packages.fire("server.shared.tick", 1);

            assertEquals(1, host.invocations);
        }
    }

    @Test
    void clientDraftFingerprintIsSideBoundAndWorkspaceCannotCrossSides() throws Exception {
        try (var packages = new RuntimePackageManager();
             var service = CodeDraftService.open(
                     temporaryDirectory.resolve("client-workspace.db"), world, clock, packages)) {
            String source = "client.status('manual');";
            var serverDraft = service.create(owner, UUID.randomUUID(), UUID.randomUUID(), 1, 1,
                    "server/main.js", source);
            var clientDraft = service.create(owner, UUID.randomUUID(), UUID.randomUUID(), 1, 1,
                    "client/main.js", source);

            assertNotEquals(serverDraft.sourceVersion(),clientDraft.sourceVersion());
            assertEquals("server/main.js",CodeDraftSources.set(serverDraft).entry());
            assertEquals("client/main.js",CodeDraftSources.set(clientDraft).entry());
            assertNotEquals(serverDraft.primarySource().sha256(),clientDraft.primarySource().sha256());

            var withHelper = service.file(clientDraft.draftId(), owner, clientDraft.revision(),
                    "add", "client/helper.js", "", "exports.value = 'ok';").draft();
            assertThrows(IllegalArgumentException.class, () -> service.file(withHelper.draftId(), owner,
                    withHelper.revision(), "add", "server/crossed.js", "", "'bad';"));
            assertThrows(IllegalArgumentException.class, () -> service.file(withHelper.draftId(), owner,
                    withHelper.revision(), "rename", "client/helper.js", "server/helper.js", ""));
            assertEquals("client/main.js", service.get(clientDraft.draftId()).orElseThrow().path());
            assertTrue(service.get(clientDraft.draftId()).orElseThrow().additionalSources()
                    .containsKey("client/helper.js"));
        }
    }

    public static final class Host {
        public int value() {
            return 7;
        }
    }

    public static final class CountingHost {
        private int invocations;

        public int run() {
            return ++invocations;
        }
    }
}
