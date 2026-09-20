package dev.mineagent.runtime.worker.media;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public final class ExternalToolRunner implements CommandRunner {
    private final List<String> commandPrefix;
    private final Duration timeout;
    private final int maximumOutputBytes;

    public ExternalToolRunner(List<String> commandPrefix, Duration timeout, int maximumOutputBytes) {
        if (commandPrefix == null || commandPrefix.isEmpty() || commandPrefix.stream().anyMatch(String::isBlank)
                || timeout == null || timeout.isNegative() || timeout.isZero() || maximumOutputBytes < 1) {
            throw new IllegalArgumentException("invalid external tool configuration");
        }
        this.commandPrefix = List.copyOf(commandPrefix);
        this.timeout = timeout;
        this.maximumOutputBytes = maximumOutputBytes;
    }

    @Override
    public ToolResult run(List<String> arguments) {
        var command = new ArrayList<>(commandPrefix);
        command.addAll(arguments);
        Process process = null;
        try {
            process = new ProcessBuilder(command).start();
            Process running = process;
            var stdout = CompletableFuture.supplyAsync(() -> read(running.getInputStream()));
            var stderr = CompletableFuture.supplyAsync(() -> read(running.getErrorStream()));
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroy();
                if (!process.waitFor(250, TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly();
                    process.waitFor(1, TimeUnit.SECONDS);
                }
                throw new MediaToolException("external media tool timed out");
            }
            return new ToolResult(process.exitValue(),
                    stdout.get(2, TimeUnit.SECONDS), stderr.get(2, TimeUnit.SECONDS));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new MediaToolException("external media tool interrupted", interrupted);
        } catch (MediaToolException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new MediaToolException("external media tool failed", failure);
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private String read(InputStream input) {
        try (input; var output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8 * 1024];
            int total = 0;
            int count;
            while ((count = input.read(buffer)) >= 0) {
                if (count == 0) {
                    continue;
                }
                total += count;
                if (total > maximumOutputBytes) {
                    throw new MediaToolException("external media tool output exceeds limit");
                }
                output.write(buffer, 0, count);
            }
            return output.toString(StandardCharsets.UTF_8);
        } catch (MediaToolException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new MediaToolException("cannot read external media tool output", failure);
        }
    }
}
