package dev.mineagent.runtime.worker;

import dev.mineagent.runtime.worker.ipc.WorkerFrameCodec;

import java.io.EOFException;
import java.io.IOException;

public final class WorkerMain {
    private static final int MAX_FRAME_LENGTH = 8 * 1024 * 1024;

    private WorkerMain() {
    }

    public static void main(String[] args) {
        var codec = new WorkerFrameCodec(MAX_FRAME_LENGTH);
        try (var handler = new WorkerRequestHandler()) {
            while (true) {
                try {
                    var request = codec.read(System.in);
                    if ("model.stream".equals(request.type())) {
                        var result = handler.handleStreaming(request, delta -> {
                            try {
                                codec.write(System.out, delta);
                            } catch (IOException failure) {
                                throw new java.io.UncheckedIOException(failure);
                            }
                        });
                        codec.write(System.out, result);
                    } else {
                        codec.write(System.out, handler.handle(request));
                    }
                } catch (EOFException endOfInput) {
                    return;
                }
            }
        } catch (IOException | java.io.UncheckedIOException failure) {
            System.err.println("MineAgent Worker IPC failure: " + failure.getMessage());
            System.exit(2);
        }
    }
}
