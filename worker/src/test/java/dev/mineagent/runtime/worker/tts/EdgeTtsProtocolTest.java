package dev.mineagent.runtime.worker.tts;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class EdgeTtsProtocolTest {
    @Test
    void buildsEscapedSsmlWithValidatedVoiceAndProsody() {
        String ssml = EdgeTtsProtocol.ssml(new SpeechSynthesisRequest(
                "<你好 & '世界'>", "zh-CN-XiaoxiaoNeural", "+10%", "+2Hz", "-5%"));

        assertTrue(ssml.contains("name=\"zh-CN-XiaoxiaoNeural\""));
        assertTrue(ssml.contains("rate=\"+10%\""));
        assertTrue(ssml.contains("pitch=\"+2Hz\""));
        assertTrue(ssml.contains("volume=\"-5%\""));
        assertTrue(ssml.contains("&lt;你好 &amp; &apos;世界&apos;&gt;"));
        assertFalse(ssml.contains("<你好"));
    }

    @Test
    void extractsOnlyTheAudioPayloadFromBinaryWebSocketMessage() {
        byte[] header = "X-RequestId:abc\r\nPath:audio\r\n".getBytes(StandardCharsets.US_ASCII);
        byte[] audio = {1, 2, 3, 4};
        ByteBuffer frame = ByteBuffer.allocate(2 + header.length + audio.length);
        frame.putShort((short) header.length).put(header).put(audio).flip();

        assertArrayEquals(audio, EdgeTtsProtocol.audioPayload(frame));
    }

    @Test
    void rejectsMalformedOrNonAudioBinaryFrames() {
        assertThrows(IllegalArgumentException.class,
                () -> EdgeTtsProtocol.audioPayload(ByteBuffer.wrap(new byte[]{0, 30, 1})));
        byte[] header = "Path:metadata\r\n".getBytes(StandardCharsets.US_ASCII);
        ByteBuffer frame = ByteBuffer.allocate(2 + header.length).putShort((short) header.length).put(header).flip();
        assertThrows(IllegalArgumentException.class, () -> EdgeTtsProtocol.audioPayload(frame));
    }

    @Test
    void rejectsInvalidVoiceOrProsodyInjection() {
        assertThrows(IllegalArgumentException.class, () -> EdgeTtsProtocol.ssml(
                new SpeechSynthesisRequest("text", "bad\"voice", "+0%", "+0Hz", "+0%")));
        assertThrows(IllegalArgumentException.class, () -> EdgeTtsProtocol.ssml(
                new SpeechSynthesisRequest("text", "zh-CN-XiaoxiaoNeural", "fast", "+0Hz", "+0%")));
    }

    @Test
    void generatesStableFiveMinuteSecMsGecToken() {
        var first = EdgeTtsProtocol.secMsGec(java.time.Instant.parse("2026-09-08T00:01:00Z"));
        var sameWindow = EdgeTtsProtocol.secMsGec(java.time.Instant.parse("2026-09-08T00:04:59Z"));
        var nextWindow = EdgeTtsProtocol.secMsGec(java.time.Instant.parse("2026-09-08T00:05:00Z"));

        assertEquals(first, sameWindow);
        assertNotEquals(first, nextWindow);
        assertTrue(first.matches("[0-9A-F]{16}-[0-9A-F]{64}"));
        assertEquals("4D28066AFF07514C-6A1504F8ACAF41E01C17748BB2CD99A87EC30950E625A0F575476269FB4026C9",first);
        assertEquals("2CFFD9B1BD6CCB63-761353A6216DEFD9468A4178F09BC02929E46ED40C2C409D98642D5F8D798442",nextWindow);
    }
}
