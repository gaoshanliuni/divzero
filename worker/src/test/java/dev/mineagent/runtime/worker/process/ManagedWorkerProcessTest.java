package dev.mineagent.runtime.worker.process;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import dev.mineagent.runtime.api.worker.WorkerEnvelope;

import static org.junit.jupiter.api.Assertions.*;

class ManagedWorkerProcessTest {
    @Test void healthSnapshotNeverWaitsForAnInFlightRequestMonitor()throws Exception{
        String javaExecutable=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);var entered=new java.util.concurrent.CountDownLatch(1);
        try(var worker=new ManagedWorkerProcess(List.of(javaExecutable,"-cp",System.getProperty("java.class.path"),HangingWorkerMain.class.getName()),Duration.ofSeconds(3))){
            worker.start();var inFlight=pool.submit(()->{try{worker.request(new WorkerEnvelope(1,UUID.randomUUID(),"health.check",Map.of()),Duration.ofSeconds(3),()->{entered.countDown();return true;});}catch(Exception expected){}});
            assertTrue(entered.await(2,java.util.concurrent.TimeUnit.SECONDS));
            try{assertTrue(pool.submit(worker::isAlive).get(1,java.util.concurrent.TimeUnit.SECONDS));}
            finally{inFlight.get(5,java.util.concurrent.TimeUnit.SECONDS);}
        }finally{pool.shutdownNow();}
    }
    @Test void streamingPermitIsCheckedInsideTheActualRequestLaneBeforeAnyWrite()throws Exception{
        try(var worker=new ManagedWorkerProcess(List.of("not-started"),Duration.ofSeconds(5))){assertThrows(WorkerDispatchGate.Rejected.class,()->worker.streamRequest(new WorkerEnvelope(1,UUID.randomUUID(),"model.stream",Map.of()),d->{fail("no delta");},()->false));assertFalse(worker.isAlive());}
    }
    @Test void explicitDeadlineStillChecksDispatchPermitBeforeAnyWorkerWrite()throws Exception{
        try(var worker=new ManagedWorkerProcess(List.of("not-started"),Duration.ofSeconds(5))){
            assertThrows(WorkerDispatchGate.Rejected.class,()->worker.request(new WorkerEnvelope(1,UUID.randomUUID(),"model.completeOnce",Map.of()),Duration.ofSeconds(200),()->false));
            assertFalse(worker.isAlive());
        }
    }
    @Test void explicitRequestDeadlineIsBoundedWithoutKillingOtherRequests()throws Exception{
        String javaExecutable=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();
        try(var worker=new ManagedWorkerProcess(List.of(javaExecutable,"-cp",System.getProperty("java.class.path"),HangingWorkerMain.class.getName()),Duration.ofSeconds(5))){
            var request=new WorkerEnvelope(1,UUID.randomUUID(),"health.check",Map.of());
            assertThrows(IllegalArgumentException.class,()->worker.request(request,Duration.ZERO));worker.start();
            assertThrows(Exception.class,()->worker.request(request,Duration.ofMillis(100)));assertTrue(worker.isAlive());
        }
    }
    @Test
    void startsWorkerPerformsHealthCheckAndStopsCleanly() throws Exception {
        String javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java.exe").toString();
        if (!java.nio.file.Files.exists(Path.of(javaExecutable))) {
            javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        }
        var command = List.of(
                javaExecutable,
                "-cp",
                System.getProperty("java.class.path"),
                "dev.mineagent.runtime.worker.WorkerMain"
        );

        try (var worker = new ManagedWorkerProcess(command, Duration.ofSeconds(5))) {
            worker.start();
            assertTrue(worker.isAlive());
            assertEquals("READY", worker.healthCheck().payload().get("status"));
        }
    }

    @Test
    void transportsStreamingDeltasBeforeTerminalFrame(@org.junit.jupiter.api.io.TempDir Path directory) throws Exception {
        try(var config=dev.mineagent.runtime.core.config.ServerConfigService.open(directory.resolve("runtime.db"))){assertNotNull(config.snapshot());}
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] body = ("data: {\"choices\":[{\"delta\":{\"content\":\"A\"}}]}\n\n"
                    + "data: {\"choices\":[{\"delta\":{\"content\":\"B\"}}]}\n\n"
                    + "data: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        String javaExecutable = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        var command = List.of(javaExecutable, "-cp", System.getProperty("java.class.path"),
                "dev.mineagent.runtime.worker.WorkerMain");
        try (var worker = new ManagedWorkerProcess(command, Duration.ofSeconds(10))) {
            worker.start();
            assertEquals("storage.configured",worker.request(new WorkerEnvelope(1,UUID.randomUUID(),"storage.configure",Map.of("contentRoot",directory.resolve("content").toString()))).type());
            worker.request(new WorkerEnvelope(1, UUID.randomUUID(), "provider.configure", Map.of(
                    "kind", "openai-compatible",
                    "baseUrl", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/",
                    "apiKey", "key", "model", "test")));
            var deltas = new java.util.ArrayList<WorkerEnvelope>();
            WorkerEnvelope result = worker.streamRequest(new WorkerEnvelope(1, UUID.randomUUID(), "model.stream",
                    Map.of("capability", "SEMANTIC", "prompt", "stream")), deltas::add,Duration.ofSeconds(15),()->true);

            assertEquals("model.stream.result",result.type(),result.payload().toString());
            assertEquals(List.of("A", "B"), deltas.stream()
                    .map(value -> String.valueOf(value.payload().get("delta"))).toList());
            assertEquals("model.stream.result", result.type());
            assertEquals("AB", result.payload().get("text"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void timesOutOnlyOneRequestAndHandlesSequentialSoakWithoutThreadGrowth() throws Exception {
        String javaExecutable = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        var healthyCommand = List.of(javaExecutable, "-cp", System.getProperty("java.class.path"),
                "dev.mineagent.runtime.worker.WorkerMain");
        int threadsBefore = Thread.activeCount();
        try (var worker = new ManagedWorkerProcess(healthyCommand, Duration.ofSeconds(5))) {
            worker.start();
            for (int index = 0; index < 250; index++) {
                assertEquals("READY", worker.healthCheck().payload().get("status"));
            }
            assertTrue(worker.isAlive());
        }
        assertTrue(Thread.activeCount() <= threadsBefore + 8);

        var hangingCommand = List.of(javaExecutable, "-cp", System.getProperty("java.class.path"),
                HangingWorkerMain.class.getName());
        try (var worker = new ManagedWorkerProcess(hangingCommand, Duration.ofMillis(100))) {
            worker.start();
            assertThrows(Exception.class, worker::healthCheck);
            assertTrue(worker.isAlive());
        }
    }

    @Test void concurrentAiStreamsRouteByIdAndCancellingOneKeepsOtherAlive(@org.junit.jupiter.api.io.TempDir Path directory)throws Exception {
        try(var config=dev.mineagent.runtime.core.config.ServerConfigService.open(directory.resolve("runtime.db"))){assertNotNull(config.snapshot());}
        var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        var http=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var httpPool=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();http.setExecutor(httpPool);
        http.createContext("/v1/chat/completions",x->{try{
            String request=new String(x.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);boolean slow=request.contains("slow-request");
            x.sendResponseHeaders(200,0);String token=slow?"A":"B";
            x.getResponseBody().write(("data: {\"model\":\"test\",\"choices\":[{\"delta\":{\"content\":\""+token+"\"}}]}\n\n").getBytes(StandardCharsets.UTF_8));x.getResponseBody().flush();
            if(slow){entered.countDown();release.await(10,java.util.concurrent.TimeUnit.SECONDS);}
            x.getResponseBody().write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
        }catch(Exception ignored){}finally{x.close();}});http.start();
        String javaExecutable=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();
        try(var worker=new ManagedWorkerProcess(List.of(javaExecutable,"-cp",System.getProperty("java.class.path"),"dev.mineagent.runtime.worker.WorkerMain"),Duration.ofSeconds(10));var pool=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()){
            worker.start();worker.request(new WorkerEnvelope(1,UUID.randomUUID(),"storage.configure",Map.of("contentRoot",directory.resolve("content").toString())));
            worker.request(new WorkerEnvelope(1,UUID.randomUUID(),"provider.configure",Map.of("kind","openai-compatible","baseUrl","http://127.0.0.1:"+http.getAddress().getPort()+"/v1/","apiKey","fixture","model","test")));
            UUID slowId=UUID.randomUUID(),fastId=UUID.randomUUID();var live=new java.util.concurrent.atomic.AtomicBoolean(true);var slowDeltas=new java.util.concurrent.CopyOnWriteArrayList<WorkerEnvelope>();
            var slow=pool.submit(()->worker.streamRequest(new WorkerEnvelope(1,slowId,"model.stream",Map.of("capability","SEMANTIC","prompt","slow-request")),slowDeltas::add,Duration.ofSeconds(10),live::get));
            assertTrue(entered.await(5,java.util.concurrent.TimeUnit.SECONDS));
            var fast=pool.submit(()->worker.streamRequest(new WorkerEnvelope(1,fastId,"model.stream",Map.of("capability","SEMANTIC","prompt","fast-request")),d->assertEquals(fastId,d.requestId()),Duration.ofSeconds(10),()->true));
            assertEquals("B",fast.get(5,java.util.concurrent.TimeUnit.SECONDS).payload().get("text"));assertFalse(slow.isDone());
            live.set(false);assertThrows(java.util.concurrent.ExecutionException.class,()->slow.get(2,java.util.concurrent.TimeUnit.SECONDS));assertTrue(worker.isAlive());assertEquals("health.ok",worker.healthCheck().type());assertTrue(slowDeltas.stream().allMatch(d->d.requestId().equals(slowId)));
        }finally{release.countDown();http.stop(0);httpPool.shutdownNow();}
    }

    public static final class HangingWorkerMain {
        private HangingWorkerMain() {
        }

        public static void main(String[] args) throws Exception {
            System.in.read();
            Thread.sleep(10_000);
        }
    }
}
