package dev.mineagent.runtime.worker.tts;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

public final class EdgeTtsLiveProbe {
    private EdgeTtsLiveProbe() {
    }

    public static void main(String[] args) throws Exception {
        var result = new EdgeTtsSpeechSynthesizer(Duration.ofSeconds(30)).synthesize(
                new SpeechSynthesisRequest("DivZero 云端朗读测试成功", "zh-CN-XiaoxiaoNeural",
                        "+0%", "+0Hz", "+0%"));
        if(!result.contentType().equals("audio/mpeg")||result.audio().length<1000)throw new IllegalStateException("EDGE_TTS_LIVE_AUDIO_MISSING");
        Path output = Path.of(args[0]);
        Files.createDirectories(output.getParent());
        Files.write(output, result.audio());
        System.out.println("MINEAGENT_EDGE_TTS_LIVE_OK bytes=" + result.audio().length);
    }
}
