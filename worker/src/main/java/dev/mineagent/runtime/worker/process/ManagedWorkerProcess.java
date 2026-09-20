package dev.mineagent.runtime.worker.process;

import dev.mineagent.runtime.api.worker.WorkerEnvelope;
import dev.mineagent.runtime.worker.WorkerRequestHandler;
import dev.mineagent.runtime.worker.ipc.WorkerFrameCodec;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public final class ManagedWorkerProcess implements AutoCloseable {
    private static final int MAX_FRAME_LENGTH = 8 * 1024 * 1024;
    private final List<String> command;
    private final Duration timeout;
    private final WorkerFrameCodec codec = new WorkerFrameCodec(MAX_FRAME_LENGTH);
    private volatile Process process;

    public ManagedWorkerProcess(List<String> command, Duration timeout) {
        this.command = List.copyOf(command);
        this.timeout = timeout;
    }

    public synchronized void start() throws IOException {
        if (isAlive()) {
            return;
        }
        process = new ProcessBuilder(command)
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start();
    }

    /** Status observation must never join the serialized, potentially long-running IPC lane. */
    public boolean isAlive() {
        Process current=process;return current != null && current.isAlive();
    }

    public WorkerEnvelope healthCheck() throws Exception {
        return request(new WorkerEnvelope(
                WorkerRequestHandler.PROTOCOL_VERSION,
                UUID.randomUUID(),
                "health.check",
                Map.of()
        ));
    }

    public synchronized WorkerEnvelope request(WorkerEnvelope envelope) throws Exception {
        return request(envelope,timeout);
    }
    public synchronized WorkerEnvelope request(WorkerEnvelope envelope,java.util.function.BooleanSupplier permit)throws Exception{
        return WorkerDispatchGate.dispatch(permit,()->request(envelope,timeout));
    }
    public synchronized WorkerEnvelope request(WorkerEnvelope envelope,Duration requestTimeout,java.util.function.BooleanSupplier permit)throws Exception{
        return WorkerDispatchGate.dispatch(permit,()->request(envelope,requestTimeout));
    }
    public synchronized WorkerEnvelope request(WorkerEnvelope envelope,Duration requestTimeout)throws Exception{
        if(requestTimeout==null||requestTimeout.isZero()||requestTimeout.isNegative()||requestTimeout.compareTo(Duration.ofMinutes(5))>0)throw new IllegalArgumentException("WORKER_REQUEST_TIMEOUT");
        if (!isAlive()) {
            throw new IllegalStateException("worker is not running");
        }
        codec.write(process.getOutputStream(), envelope);
        try {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    return codec.read(process.getInputStream());
                } catch (IOException failure) {
                    throw new java.util.concurrent.CompletionException(failure);
                }
            }).get(requestTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception failure) {
            if (!isAlive()) {
                throw new IOException("worker exited with code " + process.exitValue(), failure);
            }
            process.destroyForcibly();
            process.waitFor(1, TimeUnit.SECONDS);
            throw failure;
        }
    }

    public synchronized WorkerEnvelope streamRequest(WorkerEnvelope envelope,java.util.function.Consumer<WorkerEnvelope> consumer,java.util.function.BooleanSupplier permit)throws Exception{
        return WorkerDispatchGate.dispatch(permit,()->streamRequest(envelope,consumer));
    }
    public synchronized WorkerEnvelope streamRequest(WorkerEnvelope envelope,java.util.function.Consumer<WorkerEnvelope> consumer,Duration requestTimeout,java.util.function.BooleanSupplier permit)throws Exception{
        return WorkerDispatchGate.dispatch(permit,()->streamRequest(envelope,consumer,requestTimeout));
    }
    public synchronized WorkerEnvelope streamRequest(WorkerEnvelope envelope,java.util.function.Consumer<WorkerEnvelope> consumer)throws Exception{return streamRequest(envelope,consumer,timeout);}
    public synchronized WorkerEnvelope streamRequest(
            WorkerEnvelope envelope,
            java.util.function.Consumer<WorkerEnvelope> deltaConsumer,Duration requestTimeout
    ) throws Exception {
        if(requestTimeout==null||requestTimeout.isNegative()||requestTimeout.isZero()||requestTimeout.compareTo(Duration.ofMinutes(15))>0)throw new IllegalArgumentException("worker stream timeout");
        java.util.Objects.requireNonNull(envelope, "envelope");
        java.util.Objects.requireNonNull(deltaConsumer, "deltaConsumer");
        if (!isAlive()) {
            throw new IllegalStateException("worker is not running");
        }
        codec.write(process.getOutputStream(), envelope);
        long deadline = System.nanoTime() + requestTimeout.toNanos();
        int deltas = 0;
        try {
            while (true) {
                long remainingNanos = deadline - System.nanoTime();
                if (remainingNanos <= 0) {
                    throw new java.util.concurrent.TimeoutException("worker stream timed out");
                }
                WorkerEnvelope response = read(Math.max(1, TimeUnit.NANOSECONDS.toMillis(remainingNanos)));
                if (!envelope.requestId().equals(response.requestId())) {
                    throw new IOException("worker stream response request id mismatch");
                }
                if ("model.stream.delta".equals(response.type())) {
                    if (++deltas > 100_000) {
                        throw new IOException("worker stream delta limit exceeded");
                    }
                    deltaConsumer.accept(response);
                    continue;
                }
                return response;
            }
        } catch (Exception failure) {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
            if (process != null && !process.isAlive()) {
                throw new IOException("worker stream failed or exited", failure);
            }
            throw failure;
        }
    }

    private WorkerEnvelope read(long timeoutMillis) throws Exception {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return codec.read(process.getInputStream());
            } catch (IOException failure) {
                throw new java.util.concurrent.CompletionException(failure);
            }
        }).get(timeoutMillis, TimeUnit.MILLISECONDS);
    }

    @Override
    public synchronized void close() throws Exception {
        if (process == null) {
            return;
        }
        try {
            process.getOutputStream().close();
        } catch (IOException ignored) {
        }
        if (!process.waitFor(Math.min(2_000, timeout.toMillis()), TimeUnit.MILLISECONDS)) {
            process.destroy();
            if (!process.waitFor(1, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(1, TimeUnit.SECONDS);
            }
        }
        process = null;
    }
}
