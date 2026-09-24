package dev.mineagent.runtime.worker.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.URI;
import java.nio.file.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class RealProviderSmokeAuditTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final URI URI_REAL = URI.create("https://api.deepseek.com/v1/chat/completions");
    private ObjectNode body(){return JSON.createObjectNode().put("model","deepseek-flash").put("stream",true).put("privatePrompt","never-log-this");}
    @Test void failureMetadataCannotLogProviderSecretsOrPermitRetry(@TempDir Path directory)throws Exception{
        var request=RealProviderSmokeAudit.begin(directory,URI_REAL,body());request.failed(new ProviderRequestException(429,"untrusted-provider-body-never-log-this"));
        var record=Files.readString(directory.resolve("1-failed.json"));assertFalse(record.contains("never-log-this"));assertEquals(429,JSON.readTree(record).path("httpStatus").asInt());assertFalse(JSON.readTree(record).path("completed").asBoolean());
        assertThrows(IllegalStateException.class,()->RealProviderSmokeAudit.begin(directory,URI_REAL,body()));
    }

    @Test void recordsOnlyMetadataAndStopsAtBudget(@TempDir Path directory)throws Exception{
        for(int i=1;i<=24;i++){
            var request=body();var audit=RealProviderSmokeAudit.begin(directory,URI_REAL,request);
            assertFalse(request.has("max_tokens"));assertTrue(request.path("stream_options").path("include_usage").asBoolean());
            audit.complete("deepseek-flash",JSON.readTree("{\"prompt_tokens\":123,\"completion_tokens\":45,\"untrusted\":\"never-log-this\"}"),List.of(new ToolCall("c","inspect_player","{}")),"never-log-this");
            String result=Files.readString(directory.resolve(i+"-completed.json"));assertFalse(result.contains("never-log-this"));assertEquals(123,JSON.readTree(result).path("usage").path("prompt_tokens").asInt());
        }
        assertThrows(IllegalStateException.class,()->RealProviderSmokeAudit.begin(directory,URI_REAL,body()));
    }
    @Test void thinkingEvidenceRecordsOnlyModeAndLengthNotContent(@TempDir Path directory)throws Exception{
        var request=body();request.putObject("thinking").put("type","enabled");request.put("reasoning_effort","high");
        var audit=RealProviderSmokeAudit.begin(directory,URI_REAL,request);audit.complete("deepseek-flash",null,List.of(),"private answer",321);
        String raw=Files.readString(directory.resolve("1-completed.json"));var record=JSON.readTree(raw);assertTrue(record.path("thinkingEnabled").asBoolean());assertTrue(record.path("highReasoningEffort").asBoolean());assertEquals(321,record.path("thinkingChars").asInt());assertFalse(raw.contains("private answer"));assertFalse(raw.contains("never-log-this"));
    }
    @Test void unknownOutcomeCannotSpendAgainAfterRestart(@TempDir Path directory)throws Exception{
        RealProviderSmokeAudit.begin(directory,URI_REAL,body());
        assertThrows(IllegalStateException.class,()->RealProviderSmokeAudit.begin(directory,URI_REAL,body()));
        assertFalse(Files.exists(directory.resolve("2-started.json")));
    }
    @Test void cannotAcceptMockAlternateModelOrNonstream(@TempDir Path directory){
        assertThrows(IllegalArgumentException.class,()->RealProviderSmokeAudit.begin(directory,URI.create("http://127.0.0.1/v1/chat/completions"),body()));
        assertThrows(IllegalArgumentException.class,()->RealProviderSmokeAudit.begin(directory,URI_REAL,body().put("model","other")));
        assertThrows(IllegalArgumentException.class,()->RealProviderSmokeAudit.begin(directory,URI_REAL,body().put("stream",false)));
    }
    @Test void followupRunCanUseOnlyRemainingBudget(@TempDir Path directory)throws Exception{
        var first=RealProviderSmokeAudit.begin(directory,URI_REAL,body(),1);first.complete("deepseek-flash",null,List.of(),"done");
        assertThrows(IllegalStateException.class,()->RealProviderSmokeAudit.begin(directory,URI_REAL,body(),1));
        assertThrows(IllegalArgumentException.class,()->RealProviderSmokeAudit.begin(directory,URI_REAL,body(),25));
        assertThrows(IllegalArgumentException.class,()->RealProviderSmokeAudit.begin(directory,URI_REAL,body(),0));
    }
    @Test void explicitUnlimitedStillAuditsAndFencesUnknown(@TempDir Path directory)throws Exception {
        assertEquals(-1,RealProviderSmokeAudit.parseBudget("unlimited"));
        assertEquals(0,RealProviderSmokeAudit.parseBudget("0"));
        for(int i=1;i<=25;i++) RealProviderSmokeAudit.begin(directory,URI_REAL,body(),-1).complete("deepseek-flash",null,List.of(),"done");
        assertEquals("USER_AUTHORIZED_UNLIMITED",JSON.readTree(directory.resolve("25-completed.json").toFile()).path("callBudget").asText());
        RealProviderSmokeAudit.begin(directory,URI_REAL,body(),-1);
        assertThrows(IllegalStateException.class,()->RealProviderSmokeAudit.begin(directory,URI_REAL,body(),-1));
        assertThrows(IllegalArgumentException.class,()->RealProviderSmokeAudit.begin(directory,URI_REAL,body(),0));
    }
    @Test void explicitParallelAuditSeparatesIndependentRequestsAndPreservesUnknown(@TempDir Path directory)throws Exception {
        var first=RealProviderSmokeAudit.begin(directory,URI_REAL,body(),-1,false,true);
        var second=RealProviderSmokeAudit.begin(directory,URI_REAL,body(),-1,false,true);
        second.complete("deepseek-flash",null,List.of(),"second response");
        assertTrue(JSON.readTree(directory.resolve("2-completed.json").toFile()).path("independentParallelAudit").asBoolean());
        assertFalse(Files.exists(directory.resolve("1-completed.json")));
        assertThrows(IllegalStateException.class,()->RealProviderSmokeAudit.begin(directory,URI_REAL,body(),-1,false,false));
        first.complete("deepseek-flash",null,List.of(),"first response");
        assertEquals(1,JSON.readTree(directory.resolve("1-completed.json").toFile()).path("call").asInt());
        assertEquals(2,JSON.readTree(directory.resolve("2-completed.json").toFile()).path("call").asInt());
    }
}
