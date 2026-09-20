package dev.mineagent.runtime.worker.tts;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

public final class EdgeTtsProtocol {
    public static final String TRUSTED_CLIENT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4";
    private static final Pattern VOICE = Pattern.compile("[A-Za-z0-9-]{3,80}");
    private static final Pattern RATE_OR_VOLUME = Pattern.compile("[+-](?:100|[0-9]{1,2})%");
    private static final Pattern PITCH = Pattern.compile("[+-](?:100|[0-9]{1,2})Hz");

    private EdgeTtsProtocol() {
    }

    public static String ssml(SpeechSynthesisRequest request) {
        if (!VOICE.matcher(request.voice()).matches()) {
            throw new IllegalArgumentException("invalid Edge TTS voice");
        }
        if (!RATE_OR_VOLUME.matcher(request.rate()).matches()
                || !RATE_OR_VOLUME.matcher(request.volume()).matches()
                || !PITCH.matcher(request.pitch()).matches()) {
            throw new IllegalArgumentException("invalid Edge TTS prosody");
        }
        return "<speak version=\"1.0\" xmlns=\"http://www.w3.org/2001/10/synthesis\" xml:lang=\"zh-CN\">"
                + "<voice name=\"" + request.voice() + "\"><prosody pitch=\"" + request.pitch()
                + "\" rate=\"" + request.rate() + "\" volume=\"" + request.volume() + "\">"
                + escapeXml(request.text()) + "</prosody></voice></speak>";
    }

    public static byte[] audioPayload(ByteBuffer message) {
        ByteBuffer input = message.asReadOnlyBuffer();
        if (input.remaining() < 2) {
            throw new IllegalArgumentException("truncated Edge TTS binary frame");
        }
        int headerLength = Short.toUnsignedInt(input.getShort());
        if (headerLength < 1 || headerLength > input.remaining()) {
            throw new IllegalArgumentException("invalid Edge TTS binary header length");
        }
        byte[] headerBytes = new byte[headerLength];
        input.get(headerBytes);
        String header = new String(headerBytes, StandardCharsets.US_ASCII);
        if (!header.toLowerCase(java.util.Locale.ROOT).contains("path:audio")) {
            throw new IllegalArgumentException("Edge TTS frame is not audio");
        }
        byte[] audio = new byte[input.remaining()];
        input.get(audio);
        if (audio.length == 0) {
            throw new IllegalArgumentException("Edge TTS audio frame is empty");
        }
        return audio;
    }

    public static String secMsGec(java.time.Instant instant) {
        try {
            long windowsEpochSeconds = instant.getEpochSecond() + 11_644_473_600L;
            long roundedSeconds = windowsEpochSeconds - Math.floorMod(windowsEpochSeconds, 300L);
            long fileTimeTicks = Math.multiplyExact(roundedSeconds, 10_000_000L);
            byte[] value = (Long.toString(fileTimeTicks) + TRUSTED_CLIENT_TOKEN)
                    .getBytes(StandardCharsets.US_ASCII);
            return java.util.HexFormat.of().withUpperCase().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(value));
        } catch (java.security.GeneralSecurityException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String escapeXml(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}
