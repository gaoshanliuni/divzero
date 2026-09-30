package dev.mineagent.runtime.worker.provider;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;
class ProviderFailureDiagnosticTest {
    @Test void rejectedRequestRetainsExactFieldAndCauseWithoutKnownSecret(){
        var failure=ProviderRequestException.rejected(400,"0","{\"error\":{\"message\":\"messages[3] missing reasoning_content; credential abc123\",\"code\":\"invalid_request\",\"param\":\"messages[3].reasoning_content\"}}".getBytes(StandardCharsets.UTF_8)).withoutSecret("abc123");
        assertEquals(400,failure.statusCode());assertEquals("messages[3].reasoning_content",failure.diagnostics().get("field"));assertTrue(failure.diagnostics().get("diagnostic").toString().contains("missing reasoning_content"));assertFalse(failure.diagnostics().toString().contains("abc123"));
    }
    @Test void malformedProviderBodyStillReportsHttpStatus(){var failure=ProviderRequestException.rejected(502,"1","<html>down</html>".getBytes(StandardCharsets.UTF_8));assertEquals(1000,failure.retryAfterMillis());assertTrue(failure.diagnostics().get("diagnostic").toString().contains("502"));}
}
