package dev.mineagent.runtime.agent.ui;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class UiPageVerificationTest {
    @Test void nativeWidgetMutationNeedsMatchingDocumentAndVisibleResult(){
        var proof=new UiPageVerification("已登记");assertFalse(proof.matches(observation("native","等待")));
        proof.action("{\"action\":\"click\"}","{\"status\":\"APPLIED_NATIVE\",\"executionMode\":\"LDLIB2\",\"businessVerified\":false}");
        assertFalse(proof.matches(observation("native","等待")));assertTrue(proof.matches(observation("native","已登记")));
        assertThrows(IllegalStateException.class,()->proof.matches(observation("other","已登记")));
    }
    String observation(String document,String text){return "{\"status\":\"OBSERVED\",\"documentId\":\""+document+"\",\"visibleText\":\""+text+"\",\"elements\":[]}";}
    @Test void textMustAppearAfterAnActualPageMutationWithoutBecomingBusinessProof(){
        var proof=new UiPageVerification("已登记");assertFalse(proof.matches(observation("doc","0 条")));
        assertFalse(proof.matches(observation("doc","已登记")));
        proof.action("{\"action\":\"click\"}","{\"status\":\"APPLIED_DOM\",\"executionMode\":\"DOM\"}");
        assertTrue(proof.matches(observation("doc","已登记")));assertEquals(1,proof.appliedActions());
    }
    @Test void initialTextAndChangedDocumentsCannotBeUsedAsTaskCompletion(){
        var already=new UiPageVerification("存在");assertThrows(IllegalStateException.class,()->already.matches(observation("doc","已经存在")));
        var proof=new UiPageVerification("结果");proof.matches(observation("before","等待"));
        proof.action("{\"action\":\"click\"}","{\"status\":\"APPLIED_DOM\",\"executionMode\":\"DOM\"}");
        assertThrows(IllegalStateException.class,()->proof.matches(observation("after","结果")));
    }
    @Test void failedOrObservationOnlyReceiptsDoNotCountAsMutation(){
        var proof=new UiPageVerification("结果");proof.matches(observation("doc","等待"));
        proof.action("{\"action\":\"click\"}","{\"status\":\"PERMISSION_DENIED\",\"executionMode\":\"DOM\"}");
        proof.action("{\"action\":\"scroll\"}","{\"status\":\"APPLIED_DOM\",\"executionMode\":\"DOM\"}");
        assertFalse(proof.matches(observation("doc","结果")));assertEquals(0,proof.appliedActions());
    }
}
