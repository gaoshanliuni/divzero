package dev.mineagent.runtime.agent.ui;
import dev.mineagent.runtime.api.model.*;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
class UiAgentControllerTest {
    @Test void presentationOnlyTaskUsesHostReceiptAndNeverSendsPrivateBodyOrAllowsDomActions()throws Exception{
        var writes=new AtomicInteger();var port=new UiAgentController.Port(){public CompletableFuture<String> inspect(){return CompletableFuture.completedFuture("{\"status\":\"OBSERVED\",\"documentId\":\"doc\",\"visibleText\":\"PRIVATE_LAYOUT_ONLY_CANARY\",\"hostPresentation\":{\"canPresent\":true,\"revision\":1}}");}public CompletableFuture<String> act(String a){writes.incrementAndGet();return CompletableFuture.completedFuture("{\"status\":\"APPLIED_HOST\",\"executionMode\":\"HOST_PRESENTATION\",\"persisted\":true}");}public void cancel(){}public void onInterrupt(Runnable r){}};
        try(var controller=new UiAgentController(port,model(r->{assertFalse(r.prompt().contains("PRIVATE_LAYOUT_ONLY_CANARY"));return "{\"action\":\"present\",\"expectedLayoutRevision\":1,\"placement\":{\"anchor\":\"TOP_RIGHT\",\"width\":560,\"height\":440}}";}),true)){var result=controller.run("调整窗口",o->writes.get()==1,2,Duration.ofSeconds(2)).get();assertTrue(result.verified());assertFalse(result.toString().contains("PRIVATE_LAYOUT_ONLY_CANARY"));}
        writes.set(0);try(var controller=new UiAgentController(port,model(r->"{\"action\":\"click\",\"elementRef\":\"e1\"}"),true)){assertFalse(controller.run("只调整窗口",o->false,1,Duration.ofSeconds(2)).get().verified());assertEquals(0,writes.get());}
    }
    @Test void coordinateActionKeepsTheModelsOriginalCaptureIdentityAndPixelCoordinates()throws Exception{
        var calls=new AtomicInteger();var acts=new AtomicInteger();String viewport="{\"width\":1,\"height\":1}";var id=java.util.UUID.randomUUID();
        var image=dev.mineagent.runtime.api.ui.UiCapture.image(id,"doc",dev.mineagent.runtime.api.ui.UiCapture.sha256(viewport.getBytes(java.nio.charset.StandardCharsets.UTF_8)),"0".repeat(64),0,0,1,1,java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/lZsAAAAASUVORK5CYII="));
        var port=new UiAgentController.Port(){public CompletableFuture<String> inspect(){return CompletableFuture.completedFuture("{\"status\":\"OBSERVED\",\"documentId\":\"doc\",\"viewport\":"+viewport+"}");}
            public CompletableFuture<dev.mineagent.runtime.api.ui.UiCapture.Image> capture(){return CompletableFuture.completedFuture(image);}
            public CompletableFuture<String> act(String a){try{var n=new com.fasterxml.jackson.databind.ObjectMapper().readTree(a);assertEquals(id.toString(),n.path("captureId").asText());assertEquals(0.4,n.path("x").asDouble());assertEquals(0.3,n.path("y").asDouble());acts.incrementAndGet();return CompletableFuture.completedFuture("{\"status\":\"APPLIED_DOM\"}");}catch(Exception e){throw new AssertionError(e);}}
            public void cancel(){}public void onInterrupt(Runnable r){}
        };
        try(var controller=new UiAgentController(port,model(r->calls.incrementAndGet()==1?"{\"action\":\"capture\"}":"{\"action\":\"clickAt\",\"captureId\":\""+id+"\",\"x\":0.4,\"y\":0.3}"))){
            assertTrue(controller.run("截图坐标点击",o->acts.get()==1,3,Duration.ofSeconds(2)).get().verified());assertEquals(1,acts.get());
        }
    }
    @Test void interruptedOrStaleCaptureObservationNeverReachesTheImageProvider()throws Exception{
        for(String status:java.util.List.of("USER_INTERRUPTED","STALE_VIEW")){
            var calls=new AtomicInteger();var captured=new java.util.concurrent.atomic.AtomicBoolean();String viewport="{\"width\":1,\"height\":1}";
            var image=dev.mineagent.runtime.api.ui.UiCapture.image(java.util.UUID.randomUUID(),"d",dev.mineagent.runtime.api.ui.UiCapture.sha256(viewport.getBytes(java.nio.charset.StandardCharsets.UTF_8)),"0".repeat(64),0,0,1,1,java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/lZsAAAAASUVORK5CYII="));
            var port=new UiAgentController.Port(){public CompletableFuture<String> inspect(){return CompletableFuture.completedFuture("{\"status\":\""+(captured.get()?status:"OBSERVED")+"\",\"documentId\":\"d\",\"viewport\":"+viewport+"}");}
                public CompletableFuture<dev.mineagent.runtime.api.ui.UiCapture.Image> capture(){captured.set(true);return CompletableFuture.completedFuture(image);}public CompletableFuture<String> act(String a){throw new AssertionError("no action");}public void cancel(){}public void onInterrupt(Runnable r){}
            };
            try(var controller=new UiAgentController(port,model(r->calls.incrementAndGet()==1?"{\"action\":\"capture\"}":"{\"action\":\"done\"}"))){
                assertEquals("STALE_CAPTURE",controller.run("先截图",o->false,3,Duration.ofSeconds(2)).get().status());assertEquals(1,calls.get());
            }
        }
    }
    @Test void captureFeedsAnActualImageToTheNextModelRequestWithoutLoggingItsBytes()throws Exception{
        var calls=new AtomicInteger();var writes=new AtomicInteger();String viewport="{\"width\":1,\"height\":1}";
        byte[] png=java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/lZsAAAAASUVORK5CYII=");
        var image=dev.mineagent.runtime.api.ui.UiCapture.image(java.util.UUID.randomUUID(),"d",dev.mineagent.runtime.api.ui.UiCapture.sha256(viewport.getBytes(java.nio.charset.StandardCharsets.UTF_8)),"0".repeat(64),0,0,1,1,png);
        var port=new UiAgentController.Port(){public CompletableFuture<String> inspect(){return CompletableFuture.completedFuture("{\"status\":\"OBSERVED\",\"documentId\":\"d\",\"viewport\":"+viewport+"}");}
            @Override public CompletableFuture<dev.mineagent.runtime.api.ui.UiCapture.Image> capture(){return CompletableFuture.completedFuture(image);}
            public CompletableFuture<String> act(String a){writes.incrementAndGet();return CompletableFuture.completedFuture("{\"status\":\"APPLIED_DOM\"}");}public void cancel(){}public void onInterrupt(Runnable r){}
        };
        try(var controller=new UiAgentController(port,model(r->{if(calls.incrementAndGet()==1)return "{\"action\":\"capture\"}";assertEquals(1,r.images().size());assertArrayEquals(png,r.images().getFirst().bytes());assertFalse(r.prompt().contains("iVBOR"));return "{\"action\":\"click\",\"elementRef\":\"e1\"}";}))){
            var result=controller.run("先截图再操作",o->writes.get()==1,2,Duration.ofSeconds(2)).get();assertTrue(result.verified());assertEquals(2,calls.get());assertTrue(result.receipts().getFirst().contains("CAPTURED"));assertFalse(result.toString().contains("iVBOR"));
        }
    }
    @Test void preDispatchRefusalReobservesWithReceiptsAndSameRetryOperationId() throws Exception {
        var calls=new AtomicInteger();var attempts=new java.util.ArrayList<String>();
        var port=new UiAgentController.Port(){public CompletableFuture<String> inspect(){return CompletableFuture.completedFuture("{}");}
            public CompletableFuture<String> act(String a){attempts.add(a);return CompletableFuture.completedFuture(attempts.size()==1?"{\"status\":\"NOT_INTERACTABLE\"}":"{\"status\":\"APPLIED_DOM\"}");}
            public void cancel(){}public void onInterrupt(Runnable r){}
        };
        try(var controller=new UiAgentController(port,model(r->{if(calls.incrementAndGet()==2)assertTrue(r.prompt().contains("NOT_INTERACTABLE"));return "{\"action\":\"drag\",\"elementRef\":\"e1\",\"targetRef\":\"e2\"}";}))){
            var result=controller.run("拖动",o->attempts.size()==2,3,Duration.ofSeconds(2)).get();assertTrue(result.verified());assertEquals(2,calls.get());
            assertEquals(new com.fasterxml.jackson.databind.ObjectMapper().readTree(attempts.get(0)).path("operationId"),new com.fasterxml.jackson.databind.ObjectMapper().readTree(attempts.get(1)).path("operationId"));
        }
    }
    @Test void readOnlyConditionsHaveTheirOwnReceiptsAndStillRequireBusinessVerifier() throws Exception {
        var calls=new AtomicInteger();var writes=new AtomicInteger();
        var port=new UiAgentController.Port(){
            public CompletableFuture<String> inspect(){return CompletableFuture.completedFuture("{\"status\":\"OBSERVED\",\"documentId\":\"d\",\"visibleText\":\"ready\"}");}
            public CompletableFuture<String> act(String a){writes.incrementAndGet();return CompletableFuture.completedFuture("{\"status\":\"APPLIED_DOM\"}");}
            public void cancel(){}public void onInterrupt(Runnable r){}
        };
        try(var controller=new UiAgentController(port,model(r->switch(calls.incrementAndGet()){
            case 1 -> "{\"action\":\"verify\",\"condition\":{\"textIncludes\":\"ready\"}}";
            case 2 -> "{\"action\":\"waitFor\",\"condition\":{\"textIncludes\":\"ready\"}}";
            default -> "{\"action\":\"click\",\"elementRef\":\"e1\"}";
        }))){
            var result=controller.run("检查后保存",o->writes.get()==1,3,Duration.ofSeconds(2)).get();
            assertTrue(result.verified());assertEquals(3,calls.get());assertEquals(1,writes.get());assertEquals(3,result.receipts().size());
            var receipt=new com.fasterxml.jackson.databind.ObjectMapper().readTree(result.receipts().getFirst());
            assertEquals("OBSERVATION",receipt.path("executionMode").asText());assertEquals("MATCHED",receipt.path("status").asText());assertFalse(receipt.path("businessVerified").asBoolean());
        }
    }
    @Test void conditionsCannotCrossDocumentsWhileModelIsPlanning() throws Exception {
        for(String kind:java.util.List.of("verify","waitFor")){
            var document=new java.util.concurrent.atomic.AtomicReference<>("before");var calls=new AtomicInteger();
            var port=new UiAgentController.Port(){
                public CompletableFuture<String> inspect(){return CompletableFuture.completedFuture("{\"status\":\"OBSERVED\",\"documentId\":\""+document.get()+"\",\"visibleText\":\"expected\"}");}
                public CompletableFuture<String> act(String a){throw new AssertionError("read-only condition");}public void cancel(){}public void onInterrupt(Runnable r){}
            };
            try(var controller=new UiAgentController(port,model(r->{calls.incrementAndGet();document.set("after");return "{\"action\":\""+kind+"\",\"condition\":{\"textIncludes\":\"expected\"}}";}))){
                var outcome=controller.run("检查同一页面",o->false,3,Duration.ofSeconds(2)).get();
                assertTrue(outcome.status().endsWith("STALE_VIEW"),outcome.status());assertEquals(1,calls.get());
            }
        }
    }
    @Test void receiptsRetainFailedActionWithoutTreatingDiagnosticTextAsInterruption() throws Exception {
        var port=new UiAgentController.Port(){
            public CompletableFuture<String> inspect(){return CompletableFuture.completedFuture("{}");}
            public CompletableFuture<String> act(String a){return CompletableFuture.completedFuture("{\"status\":\"FAILED\",\"diagnostic\":\"page says USER_INTERRUPTED\"}");}
            public void cancel(){}public void onInterrupt(Runnable r){}
        };
        try(var controller=new UiAgentController(port,model(r->"{\"action\":\"click\",\"elementRef\":\"e1\"}"))){
            var result=controller.run("检查",o->false,2,Duration.ofSeconds(2)).get();
            assertEquals("ACTION_FAILED",result.status());assertEquals(1,result.receipts().size());
        }
    }
    @Test void explicitVerifyReobservesInsteadOfUsingThePrePlanningSnapshot() throws Exception {
        var value=new java.util.concurrent.atomic.AtomicReference<>("expected");var calls=new AtomicInteger();
        var port=new UiAgentController.Port(){public CompletableFuture<String> inspect(){return CompletableFuture.completedFuture("{\"status\":\"OBSERVED\",\"documentId\":\"d\",\"visibleText\":\""+value.get()+"\"}");}public CompletableFuture<String> act(String a){throw new AssertionError("no action");}public void cancel(){}public void onInterrupt(Runnable r){}};
        try(var controller=new UiAgentController(port,model(r->{calls.incrementAndGet();value.set("changed");return "{\"action\":\"verify\",\"condition\":{\"textIncludes\":\"expected\"}}";}))){
            assertEquals("CONDITION_NOT_MET",controller.run("确认",o->false,3,Duration.ofSeconds(2)).get().status());assertEquals(1,calls.get());
        }
    }
    @Test void waitsForAsynchronousPageCommitBeforeAskingForAnotherModelAction() throws Exception {
        var rendered=new java.util.concurrent.atomic.AtomicBoolean();var calls=new AtomicInteger();
        var port=new UiAgentController.Port(){
            public CompletableFuture<String> inspect(){return CompletableFuture.completedFuture(rendered.get()?"saved":"pending");}
            public CompletableFuture<String> act(String a){CompletableFuture.runAsync(()->rendered.set(true),CompletableFuture.delayedExecutor(150,java.util.concurrent.TimeUnit.MILLISECONDS));return CompletableFuture.completedFuture("{\"status\":\"APPLIED_DOM\"}");}
            public void cancel(){}public void onInterrupt(Runnable r){}
        };
        try(var controller=new UiAgentController(port,model(r->{if(calls.incrementAndGet()>1)throw new AssertionError("Do not replan while the submitted page commit is settling");return "{\"action\":\"click\",\"elementRef\":\"e1\"}";}))){
            var outcome=controller.run("保存",v->v.equals("saved"),3,Duration.ofSeconds(3)).get();assertTrue(outcome.verified());assertEquals(1,calls.get());
        }
    }
    @Test void modelWaitHonorsOverallDeadlineWithoutActingAfterTimeout() throws Exception {
        var acts=new AtomicInteger();
        var port=new UiAgentController.Port(){
            public CompletableFuture<String> inspect(){return CompletableFuture.completedFuture("{}");}
            public CompletableFuture<String> act(String a){acts.incrementAndGet();return CompletableFuture.completedFuture("{}");}
            public void cancel(){}public void onInterrupt(Runnable r){}
        };
        try(var controller=new UiAgentController(port,model(r->{try{Thread.sleep(2000);}catch(InterruptedException e){Thread.currentThread().interrupt();}return "{\"action\":\"click\",\"elementRef\":\"e1\"}";}))){
            var result=controller.run("任务",o->false,2,Duration.ofMillis(100)).get(700,java.util.concurrent.TimeUnit.MILLISECONDS);
            assertEquals("TIMEOUT",result.status());assertEquals(0,acts.get());
        }
    }
    @Test void unavailableRendererIsReportedWithoutCallingTheModel() throws Exception {
        UiAgentController.Port port=new UiAgentController.Port(){
            public CompletableFuture<String> inspect(){return CompletableFuture.failedFuture(new IllegalStateException("VIEW_NOT_RENDERED"));}
            public CompletableFuture<String> act(String a){throw new AssertionError("must not act");}
            public void cancel(){} public void onInterrupt(Runnable listener){}
        };
        try(var controller=new UiAgentController(port,model(r->{throw new AssertionError("must not call model");}))){
            assertEquals("VIEW_NOT_RENDERED",controller.run("任务",x->false,1,Duration.ofSeconds(1)).get().status());
        }
    }
    @Test void userInterruptionStopsAnInFlightModelBeforeItCanAct() throws Exception {
        var entered=new java.util.concurrent.CountDownLatch(1);
        var hook=new java.util.concurrent.atomic.AtomicReference<Runnable>(); var actions=new AtomicInteger();
        UiAgentController.Port port=new UiAgentController.Port(){
            public CompletableFuture<String> inspect(){return CompletableFuture.completedFuture("{}");}
            public CompletableFuture<String> act(String a){actions.incrementAndGet();return CompletableFuture.completedFuture("{}");}
            public void cancel(){}
            public void onInterrupt(Runnable listener){hook.set(listener);}
        };
        try(var controller=new UiAgentController(port,model(r->{
            entered.countDown();
            try{Thread.sleep(5000);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
            return "{\"action\":\"click\",\"elementRef\":\"e1\"}";
        }))){
            var result=controller.run("任务",o->false,2,Duration.ofSeconds(3));
            assertTrue(entered.await(2,java.util.concurrent.TimeUnit.SECONDS)); hook.get().run();
            assertEquals("USER_INTERRUPTED",result.get().status());assertEquals(0,actions.get());
        }
    }
    ModelProvider model(java.util.function.Function<ModelRequest, String> response) {
        return new ModelProvider() {
            public String id() { return "unit-fixture"; }
            public Set<ModelCapability> capabilities() { return Set.of(ModelCapability.PLANNING); }
            public ModelResponse complete(ModelRequest r) { return new ModelResponse(id(), response.apply(r)); }
        };
    }
    @Test void observesEveryStepAndRequiresIndependentVerificationInsteadOfModelDoneClaim() throws Exception {
        var calls = new AtomicInteger(); var actions = new AtomicInteger();
        UiAgentController.Port port = new UiAgentController.Port() {
            public CompletableFuture<String> inspect() { return CompletableFuture.completedFuture(actions.get() == 2 ? "{\"title\":\"new\"}" : "{\"title\":\"old\"}"); }
            public CompletableFuture<String> act(String action) { actions.incrementAndGet(); return CompletableFuture.completedFuture("{\"status\":\"APPLIED_DOM\"}"); }
            public void cancel() {}
            public void onInterrupt(Runnable listener) {}
        };
        try (var controller = new UiAgentController(port, model(r -> {
            if(calls.incrementAndGet()==1)return "{\"action\":\"fill\",\"elementRef\":\"e1\",\"value\":\"new\"}";
            assertTrue(r.prompt().contains("已执行动作"));assertTrue(r.prompt().contains("\"action\":\"fill\""));
            return "{\"action\":\"click\",\"elementRef\":\"e2\"}";
        }))) {
            var result = controller.run("改标题并保存", observation -> observation.contains("new"), 4, Duration.ofSeconds(3)).get();
            assertTrue(result.verified()); assertEquals(2, result.actions().size()); assertTrue(result.observations().size()>=3);
            assertEquals(2,result.receipts().size());assertTrue(result.receipts().getFirst().contains("APPLIED_DOM"));
        }
        try (var controller = new UiAgentController(port, model(r -> "{\"action\":\"done\"}"))) {
            var result = controller.run("未完成的要求", ignored -> false, 2, Duration.ofSeconds(3)).get();
            assertFalse(result.verified()); assertEquals("VERIFICATION_FAILED", result.status());
        }
    }
    @Test void forbidsArbitraryJavascriptAndLimitsRetries() throws Exception {
        var count = new AtomicInteger();
        UiAgentController.Port port = new UiAgentController.Port() {
            public CompletableFuture<String> inspect() { return CompletableFuture.completedFuture("{}"); }
            public CompletableFuture<String> act(String a) { count.incrementAndGet(); return CompletableFuture.completedFuture("{}"); }
            public void cancel() {}
            public void onInterrupt(Runnable listener) {}
        };
        try (var controller = new UiAgentController(port, model(r -> "{\"action\":\"eval\",\"value\":\"dangerous()\"}"))) {
            assertFalse(controller.run("任务", x -> false, 2, Duration.ofSeconds(3)).get().verified()); assertEquals(0, count.get());
        }
    }
}
