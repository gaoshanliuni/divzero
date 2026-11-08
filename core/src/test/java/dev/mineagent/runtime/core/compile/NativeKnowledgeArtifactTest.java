package dev.mineagent.runtime.core.compile;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class NativeKnowledgeArtifactTest {
    @Test void fingerprintIgnoresSnapshotSelectionAndModuleBundleButNotSelectedClassContent(){
        String hash="a".repeat(64),one="{\"schema\":1,\"kind\":\"x\",\"selection\":{\"snapshot\":\"old\"},\"classes\":[{\"module\":\"minecraft\",\"moduleHash\":\"old\",\"classHash\":\""+hash+"\",\"symbols\":{\"name\":\"C\",\"members\":[]}}]}",two="{\"classes\":[{\"symbols\":{\"members\":[],\"name\":\"C\"},\"classHash\":\""+hash+"\",\"moduleHash\":\"new\",\"module\":\"minecraft\"}],\"selection\":{\"snapshot\":\"new\"},\"kind\":\"y\",\"schema\":1}";
        assertEquals(NativeKnowledgeArtifact.semanticHash(one),NativeKnowledgeArtifact.semanticHash(two));assertNotEquals(NativeKnowledgeArtifact.semanticHash(one),NativeKnowledgeArtifact.semanticHash(two.replace("\"C\"","\"D\"")));
    }
}
