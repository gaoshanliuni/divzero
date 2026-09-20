package dev.mineagent.runtime.neoforge.client.audio;

import dev.mineagent.runtime.client.audio.VoiceChunkAssembler;
import dev.mineagent.runtime.neoforge.MineAgentRuntimeMod;
import dev.mineagent.runtime.neoforge.network.MineAgentPayloads;
import javazoom.jl.decoder.Bitstream;
import javazoom.jl.decoder.Decoder;
import javazoom.jl.decoder.Header;
import javazoom.jl.decoder.SampleBuffer;
import net.minecraft.client.Minecraft;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.FloatControl;
import javax.sound.sampled.SourceDataLine;
import java.io.ByteArrayInputStream;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MineAgentVoicePlayback {
    private static final VoiceChunkAssembler ASSEMBLER =
            new VoiceChunkAssembler(8 * 1024 * 1024, 512, Clock.systemUTC());
    private static final Map<String, SpatialMetadata> METADATA = new LinkedHashMap<>();
    private static final ExecutorService AUDIO = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "mineagent-tts-audio");
        thread.setDaemon(true);
        return thread;
    });

    private MineAgentVoicePlayback() {
    }

    public static synchronized void accept(MineAgentPayloads.VoiceChunk chunk) {
        acceptAudio(chunk.sha256(), chunk.chunkIndex(), chunk.chunkCount(), chunk.data(),
                new SpatialMetadata(chunk.sourceX(), chunk.sourceY(), chunk.sourceZ(), chunk.spatial()), "TTS");
    }

    public static synchronized void acceptMedia(MineAgentPayloads.MediaAudioChunk chunk) {
        acceptAudio(chunk.sha256(), chunk.chunkIndex(), chunk.chunkCount(), chunk.data(),
                new SpatialMetadata(chunk.sourceX(), chunk.sourceY(), chunk.sourceZ(), true), "MEDIA");
    }

    private static void acceptAudio(
            String sha256,
            int chunkIndex,
            int chunkCount,
            byte[] data,
            SpatialMetadata metadata,
            String sourceType
    ) {
        SpatialMetadata previous = METADATA.putIfAbsent(sha256, metadata);
        if (previous != null && !previous.equals(metadata)) {
            METADATA.remove(sha256);
            MineAgentRuntimeMod.LOGGER.warn("Rejected inconsistent {} spatial metadata for {}", sourceType, sha256);
            return;
        }
        try {
            ASSEMBLER.accept(sha256, chunkIndex, chunkCount, data)
                    .ifPresent(audio -> {
                        SpatialMetadata complete = METADATA.remove(sha256);
                        ListenerSnapshot listener = listenerSnapshot();
                        if (Boolean.getBoolean("mineagent.multiplayerSmokeClient")) {
                            MineAgentRuntimeMod.LOGGER.info(
                                    "MINEAGENT_SMOKE_TTS_AUDIO_RECEIVED spatial={} bytes={}",
                                    complete != null && complete.spatial(), audio.length);
                        }
                        AUDIO.execute(() -> playMp3(audio, complete, listener));
                    });
        } catch (IllegalArgumentException invalid) {
            METADATA.remove(sha256);
            MineAgentRuntimeMod.LOGGER.warn("Rejected invalid {} audio transfer: {}", sourceType, invalid.getMessage());
        }
    }

    private static ListenerSnapshot listenerSnapshot() {
        var player = Minecraft.getInstance().player;
        if (player == null) {
            return new ListenerSnapshot(0, 0, 0, 0);
        }
        return new ListenerSnapshot(player.getX(), player.getY(), player.getZ(), player.getYRot());
    }

    private static void playMp3(byte[] mp3, SpatialMetadata source, ListenerSnapshot listener) {
        playMp3(mp3,source,listener,()->true);
    }
    public static void playConversation(byte[] mp3,java.util.function.BooleanSupplier current){var listener=listenerSnapshot();AUDIO.execute(()->{if(current.getAsBoolean())playMp3(mp3,new SpatialMetadata(0,0,0,false),listener,current);});}
    private static void playMp3(byte[] mp3, SpatialMetadata source, ListenerSnapshot listener,java.util.function.BooleanSupplier current) {
        try (var input = new ByteArrayInputStream(mp3)) {
            var bitstream = new Bitstream(input);
            var decoder = new Decoder();
            SourceDataLine line = null;
            try {
                Header header;
                while (current.getAsBoolean()&&(header = bitstream.readFrame()) != null) {
                    SampleBuffer decoded = (SampleBuffer) decoder.decodeFrame(header, bitstream);
                    if (line == null) {
                        AudioFormat format = new AudioFormat(
                                decoded.getSampleFrequency(), 16, decoded.getChannelCount(), true, false);
                        line = AudioSystem.getSourceDataLine(format);
                        line.open(format);
                        applySpatialControls(line, source, listener);
                        line.start();
                    }
                    short[] samples = decoded.getBuffer();
                    int length = decoded.getBufferLength();
                    byte[] pcm = new byte[length * 2];
                    for (int index = 0; index < length; index++) {
                        pcm[index * 2] = (byte) samples[index];
                        pcm[index * 2 + 1] = (byte) (samples[index] >>> 8);
                    }
                    if(current.getAsBoolean())line.write(pcm, 0, pcm.length);
                    bitstream.closeFrame();
                }
                if (line != null) {
                    if(current.getAsBoolean())line.drain();else line.flush();
                    if (Boolean.getBoolean("mineagent.multiplayerSmokeClient")) {
                        MineAgentRuntimeMod.LOGGER.info("MINEAGENT_SMOKE_TTS_PLAYBACK_OK spatial={}",
                                source != null && source.spatial());
                    }
                }
            } finally {
                if (line != null) {
                    line.stop();
                    line.close();
                }
                bitstream.close();
            }
        } catch (Exception failure) {
            MineAgentRuntimeMod.LOGGER.warn("Failed to play Edge TTS audio", failure);
        }
    }

    private static void applySpatialControls(
            SourceDataLine line,
            SpatialMetadata source,
            ListenerSnapshot listener
    ) {
        if (source == null || !source.spatial()) {
            return;
        }
        double dx = source.x() - listener.x();
        double dz = source.z() - listener.z();
        double distance = Math.sqrt(dx * dx + (source.y() - listener.y()) * (source.y() - listener.y()) + dz * dz);
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        float pan = 0;
        if (horizontal > 0.001) {
            double yaw = Math.toRadians(listener.yawDegrees());
            double rightX = Math.cos(yaw);
            double rightZ = Math.sin(yaw);
            pan = (float) Math.max(-1, Math.min(1, (dx * rightX + dz * rightZ) / horizontal));
        }
        float linearGain = (float) Math.max(0.01, 1.0 - Math.min(64.0, distance) / 64.0);
        float gainDb = (float) (20.0 * Math.log10(linearGain));
        if (line.isControlSupported(FloatControl.Type.PAN)) {
            FloatControl control = (FloatControl) line.getControl(FloatControl.Type.PAN);
            control.setValue(Math.max(control.getMinimum(), Math.min(control.getMaximum(), pan)));
        }
        if (line.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
            FloatControl control = (FloatControl) line.getControl(FloatControl.Type.MASTER_GAIN);
            control.setValue(Math.max(control.getMinimum(), Math.min(control.getMaximum(), gainDb)));
        }
    }

    private record SpatialMetadata(double x, double y, double z, boolean spatial) {
    }

    private record ListenerSnapshot(double x, double y, double z, float yawDegrees) {
    }
}
