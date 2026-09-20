package dev.mineagent.runtime.worker.tts;

@FunctionalInterface
public interface SpeechSynthesizer {
    SpeechSynthesisResult synthesize(SpeechSynthesisRequest request);
}
