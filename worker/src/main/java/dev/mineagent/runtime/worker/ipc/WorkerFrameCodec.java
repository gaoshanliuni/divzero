package dev.mineagent.runtime.worker.ipc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.cbor.CBORFactory;
import dev.mineagent.runtime.api.worker.WorkerEnvelope;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public final class WorkerFrameCodec {
    private final int maxFrameLength;
    private final ObjectMapper mapper;

    public WorkerFrameCodec(int maxFrameLength) {
        if (maxFrameLength < 1) {
            throw new IllegalArgumentException("maxFrameLength must be positive");
        }
        this.maxFrameLength = maxFrameLength;
        this.mapper = new ObjectMapper(new CBORFactory());
    }

    public void write(OutputStream output, WorkerEnvelope envelope) throws IOException {
        byte[] body = mapper.writeValueAsBytes(envelope);
        if (body.length > maxFrameLength) {
            throw new IOException("frame length " + body.length + " exceeds " + maxFrameLength);
        }
        var data = new DataOutputStream(output);
        data.writeInt(body.length);
        data.write(body);
        data.flush();
    }

    public WorkerEnvelope read(InputStream input) throws IOException {
        var data = new DataInputStream(input);
        final int length;
        try {
            length = data.readInt();
        } catch (EOFException eof) {
            throw eof;
        }
        if (length < 1 || length > maxFrameLength) {
            throw new IOException("invalid frame length " + length);
        }
        byte[] body = data.readNBytes(length);
        if (body.length != length) {
            throw new EOFException("incomplete worker frame");
        }
        return mapper.readValue(body, WorkerEnvelope.class);
    }
}
