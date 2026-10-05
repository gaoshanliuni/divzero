package dev.mineagent.runtime.legacy189.bridge;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;

/** Bounded binary framing over private child-process pipes, never a public socket.
 * Payload semantics, permission checks and durable operation deduplication belong
 * to the runtime; a valid frame alone is not execution authority or an APPLIED receipt.
 */
public final class FrameCodec {
    public static final int PROTOCOL = 1;
    public static final int MAGIC = 0x445A3839; // DZ89
    private FrameCodec() { }

    /** One Reader belongs to one I/O thread. Any malformed frame poisons the stream. */
    public static final class Reader {
        private final DataInputStream input;
        private boolean failed;
        public Reader(InputStream input) { this.input = new DataInputStream(input); }

        /** Null is a clean EOF between frames. A partial frame is always an error. */
        public synchronized BridgeFrame read() throws IOException {
            if (failed) throw new IOException("BRIDGE_STREAM_FAILED");
            try {
                int first = input.read();
                if (first < 0) return null;
                int magic = first << 24 | input.readUnsignedByte() << 16
                        | input.readUnsignedByte() << 8 | input.readUnsignedByte();
                if (magic != MAGIC) throw new IOException("BRIDGE_MAGIC");
                if (input.readUnsignedShort() != PROTOCOL) throw new IOException("BRIDGE_PROTOCOL");
                int kind = input.readUnsignedByte();
                if (kind >= BridgeFrame.Kind.values().length) throw new IOException("BRIDGE_KIND");
                int length = input.readInt();
                if (length < 0 || length > BridgeFrame.MAX_PAYLOAD_BYTES) throw new IOException("BRIDGE_LENGTH");
                UUID operation = uuid(input), world = uuid(input), owner = uuid(input), session = uuid(input);
                long revision = input.readLong();
                if (revision < 0) throw new IOException("BRIDGE_REVISION");
                byte[] payload = new byte[length];
                input.readFully(payload);
                return new BridgeFrame(BridgeFrame.Kind.values()[kind], operation, world, owner, session, revision, payload);
            } catch (EOFException truncated) {
                failed = true;
                throw new IOException("BRIDGE_TRUNCATED", truncated);
            } catch (IOException malformed) {
                failed = true;
                throw malformed;
            }
        }
    }

    /** Shared writers serialize whole frames; no game thread may wait on this pipe. */
    public static final class Writer {
        private final DataOutputStream output;
        private boolean failed;
        public Writer(OutputStream output) { this.output = new DataOutputStream(output); }
        public synchronized void write(BridgeFrame frame) throws IOException {
            if (failed) throw new IOException("BRIDGE_STREAM_FAILED");
            try {
                output.writeInt(MAGIC);
                output.writeShort(PROTOCOL);
                output.writeByte(frame.kind().ordinal());
                output.writeInt(frame.payloadLength());
                uuid(output, frame.operation()); uuid(output, frame.world());
                uuid(output, frame.owner()); uuid(output, frame.session());
                output.writeLong(frame.revision());
                frame.writePayload(output);
                output.flush();
            } catch (IOException error) {
                failed = true;
                // Caller records UNKNOWN for an in-flight write; never resend this frame.
                throw error;
            }
        }
    }

    private static UUID uuid(DataInputStream input) throws IOException {
        return new UUID(input.readLong(), input.readLong());
    }
    private static void uuid(DataOutputStream output, UUID value) throws IOException {
        output.writeLong(value.getMostSignificantBits());
        output.writeLong(value.getLeastSignificantBits());
    }
}
