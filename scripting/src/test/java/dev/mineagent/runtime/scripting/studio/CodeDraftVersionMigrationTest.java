package dev.mineagent.runtime.scripting.studio;
import dev.mineagent.runtime.scripting.packagehost.RuntimePackageManager;
import dev.mineagent.runtime.core.packages.CodeDraftSources;
import dev.mineagent.runtime.core.content.ContentAddressedStore;
import java.nio.file.*;import java.time.Clock;import java.util.*;
import org.junit.jupiter.api.Test;import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
class CodeDraftVersionMigrationTest {
    @TempDir Path root;
    @Test void multiFileEditsRestoreAndReloadPreserveActualSources()throws Exception{
        UUID world=UUID.randomUUID(),owner=UUID.randomUUID(),id;String version;
        try(var packages=new RuntimePackageManager();var studio=CodeDraftService.open(root.resolve("runtime.db"),world,Clock.systemUTC(),packages)){
            var draft=studio.create(owner,UUID.randomUUID(),UUID.randomUUID(),1,1,"main.js","1;");id=draft.draftId();var next=studio.file(id,owner,draft.revision(),"add","helper.js","","exports.n=2;").draft();assertNotEquals(draft.sourceVersion(),next.sourceVersion());
            var edited=studio.file(id,owner,next.revision(),"put","helper.js","","exports.n=3;").draft();assertEquals("exports.n=3;",studio.sourceFile(edited,"helper.js"));
            var restored=studio.file(id,owner,edited.revision(),"restore","",Long.toString(next.revision()),"").draft();assertEquals("exports.n=2;",studio.sourceFile(restored,"helper.js"));version=restored.sourceVersion();
        }
        try(var packages=new RuntimePackageManager();var studio=CodeDraftService.open(root.resolve("runtime.db"),world,Clock.systemUTC(),packages)){
            var draft=studio.get(id).orElseThrow();assertEquals(version,draft.sourceVersion());assertEquals(Map.of("main.js","1;","helper.js","exports.n=2;"),studio.sources(draft));assertEquals(CodeDraftSources.set(draft),CodeDraftSources.readSet(new ContentAddressedStore(root.resolve("content")),version));
        }
    }
    @Test void failedCandidateCannotAlterRunningSourceVersion()throws Exception{
        UUID owner=UUID.randomUUID();try(var packages=new RuntimePackageManager();var studio=CodeDraftService.open(root.resolve("runtime.db"),UUID.randomUUID(),Clock.systemUTC(),packages)){
            var draft=studio.create(owner,UUID.randomUUID(),UUID.randomUUID(),1,1,"main.js","1;");var bad=studio.update(draft.draftId(),owner,draft.revision(),"while(true){}");assertFalse(bad.accepted());assertEquals(draft,studio.get(draft.draftId()).orElseThrow());
        }
    }
}
