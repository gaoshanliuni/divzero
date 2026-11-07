package dev.mineagent.runtime.core.packages;
import dev.mineagent.runtime.api.packages.*;import dev.mineagent.runtime.core.crypto.IdentitySigner;import dev.mineagent.runtime.core.content.ContentAddressedStore;import dev.mineagent.runtime.core.persistence.SqliteRuntimeRepository;
import java.nio.file.*;import java.time.Clock;import java.util.*;
import org.junit.jupiter.api.Test;import org.junit.jupiter.api.io.TempDir;import static org.junit.jupiter.api.Assertions.*;
class SourceSetPublicationTest {
    @TempDir Path directory;
    @Test void publishesOnlyTheExactDraftFileSetAndKeepsOriginalResourceIds()throws Exception{
        var content=new ContentAddressedStore(directory.resolve("content"));Path database=directory.resolve("db");UUID world=UUID.randomUUID(),owner=UUID.randomUUID(),pkg=UUID.randomUUID();var helper=CodeDraftSources.store(content,"exports.answer=42;");
        var draft=new CodeDraft(UUID.randomUUID(),world,owner,UUID.randomUUID(),pkg,1,1,1,"server/main.js","require('./helper.js').answer;",CodeDraftStatus.DRAFT,List.of(),1,Map.of("server/helper.js",helper),List.of(),true);CodeDraftSources.bind(draft,content);
        try(var db=new SqliteRuntimeRepository(database)){assertTrue(db.compareAndSet(world,"code_drafts",draft.draftId().toString(),0,new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(draft),1).accepted());}
        try(var signer=IdentitySigner.open(directory.resolve("identity"));var library=RuntimePackageLibrary.open(database,Clock.systemUTC(),signer,content)){
            var resources=new TreeMap<String,RuntimeResourceRef>();CodeDraftSources.refs(draft).forEach((path,ref)->resources.put(path,new RuntimeResourceRef(path,ref.sha256(),RuntimeResourceSide.SERVER,"application/javascript",ref.bytes())));
            resources.put(CodeDraftSources.MANIFEST_PATH,new RuntimeResourceRef(CodeDraftSources.MANIFEST_PATH,draft.sourceVersion(),RuntimeResourceSide.SERVER,"application/json",content.read(draft.sourceVersion()).length));
            var compatibility=new NativeCompatibility(1,Map.of("SERVER",new NativeCompatibility.Target("26.1.2","neoforge","26.1.2.106","official",25,Map.of())));
            var unsigned=new RuntimePackage(pkg,RuntimePackageType.EXTENSION,"source versions","1.0.0",ActivationMode.HOT_RUNTIME,Map.of(),Set.of("RUN_CODE"),Map.of("studio_script",new RuntimeEntrypoint(draft.path(),RuntimeResourceSide.SERVER,draft.primarySource().sha256())),Map.of(),resources,PackageOrigin.LOCAL_STUDIO,false,1,"0".repeat(64),"",1,compatibility);
            var candidate=PackageSignatures.signNew(unsigned,content::read,signer);var receipt=library.publishStudio(new JavaStudioMetadata.Input(UUID.randomUUID(),world,owner,draft.draftId(),1,pkg,0,draft.sourceVersion(),candidate.name()),candidate);
            assertEquals("SOURCE_PUBLISHED_NOT_EXECUTED",receipt.outcome());assertEquals(draft.sourceVersion(),RuntimeStudioPlan.fingerprint(candidate));assertEquals(draft.source(),RuntimeStudioPlan.readAll(candidate,content).get(draft.path()));assertEquals(helper.sha256(),RuntimeStudioPlan.refs(candidate).get("server/helper.js").sha256());
        }
    }
}
