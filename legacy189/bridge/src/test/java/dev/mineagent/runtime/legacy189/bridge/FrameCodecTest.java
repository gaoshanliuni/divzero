package dev.mineagent.runtime.legacy189.bridge;

import org.junit.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.*;
import static org.junit.Assert.*;

public class FrameCodecTest {
    private static BridgeFrame frame(byte[] data) {
        return new BridgeFrame(BridgeFrame.Kind.REQUEST, UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), 23, data);
    }
    private static byte[] encode(BridgeFrame frame) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        new FrameCodec.Writer(output).write(frame);
        return output.toByteArray();
    }
    private static void rejects(byte[] data) throws Exception {
        FrameCodec.Reader reader = new FrameCodec.Reader(new ByteArrayInputStream(data));
        try { reader.read(); fail("Malformed frame accepted"); } catch (IOException expected) { }
        try { reader.read(); fail("Poisoned stream accepted"); } catch (IOException expected) {
            assertEquals("BRIDGE_STREAM_FAILED", expected.getMessage());
        }
    }

    @Test public void sequenceRoundTripAndDefensiveCopies() throws Exception {
        byte[] payload = "多行\n𝄞\u0000".getBytes(StandardCharsets.UTF_8);
        BridgeFrame first = frame(payload), second = frame(new byte[0]);
        payload[0] = 0;
        first.payload()[0] = 0;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        FrameCodec.Writer writer = new FrameCodec.Writer(output);
        writer.write(first); writer.write(second);
        FrameCodec.Reader reader = new FrameCodec.Reader(new ByteArrayInputStream(output.toByteArray()));
        assertEquals(first, reader.read()); assertEquals(second, reader.read()); assertNull(reader.read());
        assertEquals("多行\n𝄞\u0000", new String(first.payload(), StandardCharsets.UTF_8));
    }

    @Test public void everyPartialFrameFailsInsteadOfBeingCleanEof() throws Exception {
        byte[] encoded = encode(frame(new byte[] {1, 2, 3}));
        for (int count = 1; count < encoded.length; count++) rejects(Arrays.copyOf(encoded, count));
        assertNull(new FrameCodec.Reader(new ByteArrayInputStream(new byte[0])).read());
    }

    @Test public void validatesMagicProtocolKindLengthAndRevisionBeforePayloadAllocation() throws Exception {
        byte[] encoded = encode(frame(new byte[0]));
        for (int index : new int[] {0, 4, 6, 7, 75}) {
            byte[] corrupt = encoded.clone(); corrupt[index] = (byte) 0xff; rejects(corrupt);
        }
        byte[] oversized = encoded.clone();
        oversized[7] = 0x7f; rejects(oversized);
    }

    @Test public void maximumPayloadIsSupportedAndLargerInputRefused() throws Exception {
        BridgeFrame maximum = frame(new byte[BridgeFrame.MAX_PAYLOAD_BYTES]);
        assertEquals(maximum, new FrameCodec.Reader(new ByteArrayInputStream(encode(maximum))).read());
        try { frame(new byte[BridgeFrame.MAX_PAYLOAD_BYTES + 1]); fail(); }
        catch (IllegalArgumentException expected) { assertEquals("BRIDGE_FRAME_BOUNDS", expected.getMessage()); }
    }

    @Test public void worldOwnerAndSessionMustEachStillMatch() {
        BridgeFrame value = frame(new byte[0]);
        value.requireScope(value.world(), value.owner(), value.session());
        UUID different = UUID.randomUUID();
        for (UUID[] ids : new UUID[][] {{different, value.owner(), value.session()},
                {value.world(), different, value.session()}, {value.world(), value.owner(), different}}) {
            try { value.requireScope(ids[0], ids[1], ids[2]); fail(); }
            catch (IllegalStateException expected) { assertEquals("BRIDGE_SCOPE_CHANGED", expected.getMessage()); }
        }
    }

    @Test public void failedWriteCannotBeRetriedOnTheSameStream() throws Exception {
        OutputStream broken = new OutputStream() {
            int remaining = 10;
            @Override public void write(int value) throws IOException { if (--remaining < 0) throw new IOException("disconnected"); }
        };
        FrameCodec.Writer writer = new FrameCodec.Writer(broken);
        try { writer.write(frame(new byte[3])); fail(); } catch (IOException expected) { }
        try { writer.write(frame(new byte[3])); fail(); } catch (IOException expected) {
            assertEquals("BRIDGE_STREAM_FAILED", expected.getMessage());
        }
    }

    @Test public void java25ParentExchangesFramesWithActualJava8Child() throws Exception {
        String java8 = System.getProperty("legacy189.java8", "");
        assertFalse("CI must provide an actual Java 8 executable", java8.isEmpty());
        String classPath = new File(FrameCodecTest.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getPath()
                + File.pathSeparator + new File(BridgeFrame.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getPath();
        Process child = new ProcessBuilder(java8, "-cp", classPath, Peer.class.getName()).redirectError(ProcessBuilder.Redirect.INHERIT).start();
        ExecutorService io = Executors.newSingleThreadExecutor();
        try {
            final FrameCodec.Reader reader = new FrameCodec.Reader(child.getInputStream());
            BridgeFrame request = frame("真实跨 JVM：世界/玩家/操作\n".getBytes(StandardCharsets.UTF_8));
            new FrameCodec.Writer(child.getOutputStream()).write(request);
            child.getOutputStream().close();
            Future<BridgeFrame> reply = io.submit(new Callable<BridgeFrame>() {
                @Override public BridgeFrame call() throws Exception { return reader.read(); }
            });
            assertEquals(request, reply.get(20, TimeUnit.SECONDS));
            assertTrue(child.waitFor(20, TimeUnit.SECONDS));
            assertEquals(0, child.exitValue());
        } finally {
            child.destroyForcibly(); io.shutdownNow();
        }
    }

    /** Test-only peer, not a model provider, game adapter or production service. */
    public static class Peer {
        public static void main(String[] args) throws Exception {
            if (!System.getProperty("java.specification.version").equals("1.8")) throw new IllegalStateException("JAVA_8_REQUIRED");
            FrameCodec.Reader reader = new FrameCodec.Reader(System.in);
            FrameCodec.Writer writer = new FrameCodec.Writer(System.out);
            BridgeFrame request;
            while ((request = reader.read()) != null) writer.write(request);
        }
    }
}
