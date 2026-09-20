package dev.mineagent.runtime.worker.tts;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

public final class EdgeTtsSpeechSynthesizer implements SpeechSynthesizer {
    private static final String CHROMIUM_VERSION = "143.0.3650.75";
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36 Edg/143.0.0.0";
    private static final String ENDPOINT =
            "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1";
    private final Duration timeout;
    private final HttpClient client;

    public EdgeTtsSpeechSynthesizer(Duration timeout) {
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        this.timeout = timeout;
        this.client = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    @Override
    public SpeechSynthesisResult synthesize(SpeechSynthesisRequest request) {
        String connectionId = id();
        String requestId = id();
        URI uri = URI.create(ENDPOINT + "?TrustedClientToken=" + EdgeTtsProtocol.TRUSTED_CLIENT_TOKEN
                + "&Sec-MS-GEC=" + EdgeTtsProtocol.secMsGec(java.time.Instant.now())
                + "&Sec-MS-GEC-Version=1-" + CHROMIUM_VERSION
                + "&ConnectionId=" + connectionId);
        var listener = new Listener();
        WebSocket socket = null;
        try {
            WebSocket connected = client.newWebSocketBuilder()
                    .connectTimeout(timeout)
                    .header("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold")
                    .header("Pragma", "no-cache")
                    .header("Cache-Control", "no-cache")
                    .header("User-Agent", USER_AGENT)
                    .header("Accept-Encoding", "gzip, deflate, br, zstd")
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .header("Cookie", "muid=" + id().toUpperCase(java.util.Locale.ROOT) + ";")
                    .buildAsync(uri, listener)
                    .get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            socket = connected;
            String timestamp = DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now(java.time.ZoneOffset.UTC));
            String config = "X-Timestamp:" + timestamp + "\r\n"
                    + "Content-Type:application/json; charset=utf-8\r\nPath:speech.config\r\n\r\n"
                    + "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":{"
                    + "\"sentenceBoundaryEnabled\":\"false\",\"wordBoundaryEnabled\":\"false\"},"
                    + "\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}";
            String ssml = "X-RequestId:" + requestId + "\r\nContent-Type:application/ssml+xml\r\n"
                    + "X-Timestamp:" + timestamp + "\r\nPath:ssml\r\n\r\n" + EdgeTtsProtocol.ssml(request);
            connected.sendText(config, true).thenCompose(ignored -> connected.sendText(ssml, true))
                    .get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            byte[] audio = listener.result.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            return new SpeechSynthesisResult("audio/mpeg", audio);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new SpeechSynthesisException("Edge TTS request interrupted", interrupted);
        } catch (Exception failure) {
            throw new SpeechSynthesisException("Edge TTS synthesis failed", failure);
        } finally {
            if (socket != null) {
                socket.sendClose(WebSocket.NORMAL_CLOSURE, "done");
            }
        }
    }

    private static String id() {
        ByteBuffer bytes = ByteBuffer.allocate(16)
                .putLong(UUID.randomUUID().getMostSignificantBits())
                .putLong(UUID.randomUUID().getLeastSignificantBits());
        return HexFormat.of().formatHex(bytes.array());
    }

    private static final class Listener implements WebSocket.Listener {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream audio = new ByteArrayOutputStream();
        private final ByteArrayOutputStream binaryFrame = new ByteArrayOutputStream();
        private final StringBuilder textFrame = new StringBuilder();

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            textFrame.append(data);
            if (last) {
                String message = textFrame.toString();
                textFrame.setLength(0);
                if (message.toLowerCase(java.util.Locale.ROOT).contains("path:turn.end")) {
                    byte[] bytes = audio.toByteArray();
                    if (bytes.length == 0) {
                        result.completeExceptionally(new SpeechSynthesisException("Edge TTS returned no audio"));
                    } else {
                        result.complete(bytes);
                    }
                }
            }
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            byte[] part = new byte[data.remaining()];
            data.get(part);
            binaryFrame.writeBytes(part);
            if (last) {
                try {
                    audio.writeBytes(EdgeTtsProtocol.audioPayload(ByteBuffer.wrap(binaryFrame.toByteArray())));
                } catch (IllegalArgumentException ignored) {
                    // Metadata frames are not part of the resulting MP3.
                } finally {
                    binaryFrame.reset();
                }
            }
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            result.completeExceptionally(error);
        }
    }
}
