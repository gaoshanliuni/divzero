package dev.mineagent.runtime.neoforge.client.media;

import com.mojang.blaze3d.platform.NativeImage;
import dev.mineagent.runtime.api.media.MediaScreenBinding;
import dev.mineagent.runtime.client.audio.VoiceChunkAssembler;
import dev.mineagent.runtime.neoforge.MineAgentRuntimeMod;
import dev.mineagent.runtime.neoforge.network.MineAgentPayloads;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;

import java.io.ByteArrayInputStream;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;

public final class MineAgentMediaPlayback {
    private static final VoiceChunkAssembler FRAMES = new VoiceChunkAssembler(
            16 * 1024 * 1024, 512, Clock.systemUTC());
    private static final Map<String, FrameMetadata> TRANSFERS = new LinkedHashMap<>();
    private static final Map<String, TextureEntry> TEXTURES = new LinkedHashMap<>();

    private MineAgentMediaPlayback() {
    }

    public static synchronized void acceptFrame(MineAgentPayloads.MediaFrameChunk chunk) {
        try {
            MediaScreenBinding.parse(chunk.binding());
            var metadata = new FrameMetadata(chunk.mediaId(), chunk.revision(), chunk.binding(), chunk.positionMillis());
            FrameMetadata previous = TRANSFERS.putIfAbsent(chunk.sha256(), metadata);
            if (previous != null && !previous.equals(metadata)) {
                TRANSFERS.remove(chunk.sha256());
                throw new IllegalArgumentException("media frame metadata changed during transfer");
            }
            while (TRANSFERS.size() > 128) {
                TRANSFERS.remove(TRANSFERS.keySet().iterator().next());
            }
            FRAMES.accept(chunk.sha256(), chunk.chunkIndex(), chunk.chunkCount(), chunk.data())
                    .ifPresent(bytes -> {
                        FrameMetadata complete = TRANSFERS.remove(chunk.sha256());
                        if (complete != null) {
                            Minecraft.getInstance().execute(() -> upload(complete, bytes));
                        }
                    });
        } catch (IllegalArgumentException invalid) {
            TRANSFERS.remove(chunk.sha256());
            MineAgentRuntimeMod.LOGGER.warn("Rejected media frame transfer: {}", invalid.getMessage());
        }
    }

    public static synchronized void acceptState(MineAgentPayloads.MediaState state) {
        int count;
        try {
            count = Math.max(0, Math.min(20, Integer.parseInt(state.values().getOrDefault("mediaCount", "0"))));
        } catch (NumberFormatException invalid) {
            count = 0;
        }
        var active = new java.util.HashSet<String>();
        for (int index = 0; index < count; index++) {
            String prefix = "media." + index + ".";
            if (Boolean.parseBoolean(state.values().getOrDefault(prefix + "playing", "false"))) {
                active.add(state.values().getOrDefault(prefix + "id", ""));
            }
        }
        var remove = TEXTURES.entrySet().stream()
                .filter(entry -> !active.contains(entry.getValue().mediaId()))
                .map(Map.Entry::getKey).toList();
        Minecraft minecraft = Minecraft.getInstance();
        for (String binding : remove) {
            TextureEntry removed = TEXTURES.remove(binding);
            if (removed != null) {
                minecraft.getTextureManager().release(removed.texture());
            }
        }
    }

    public static synchronized Identifier texture(String dimension, BlockPos position) {
        String binding = new MediaScreenBinding(dimension, position.getX(), position.getY(), position.getZ()).encoded();
        TextureEntry entry = TEXTURES.get(binding);
        return entry == null ? null : entry.texture();
    }

    private static synchronized void upload(FrameMetadata metadata, byte[] bytes) {
        try (var input = new ByteArrayInputStream(bytes)) {
            NativeImage image = NativeImage.read(input);
            Identifier texture = Identifier.fromNamespaceAndPath(MineAgentRuntimeMod.MOD_ID,
                    "media/" + metadata.mediaId().toLowerCase(java.util.Locale.ROOT));
            Minecraft minecraft = Minecraft.getInstance();
            minecraft.getTextureManager().register(texture,
                    new DynamicTexture(() -> "MineAgent media " + metadata.mediaId(), image));
            TextureEntry oldBinding = TEXTURES.put(metadata.binding(),
                    new TextureEntry(metadata.mediaId(), metadata.revision(), metadata.positionMillis(), texture));
            if (oldBinding != null && !oldBinding.texture().equals(texture)) {
                minecraft.getTextureManager().release(oldBinding.texture());
            }
            var oldLocations = TEXTURES.entrySet().stream()
                    .filter(entry -> entry.getValue().mediaId().equals(metadata.mediaId()))
                    .filter(entry -> !entry.getKey().equals(metadata.binding()))
                    .map(Map.Entry::getKey).toList();
            oldLocations.forEach(TEXTURES::remove);
            while (TEXTURES.size() > 32) {
                String oldest = TEXTURES.keySet().iterator().next();
                TextureEntry removed = TEXTURES.remove(oldest);
                if (removed != null) {
                    minecraft.getTextureManager().release(removed.texture());
                }
            }
            if (Boolean.getBoolean("mineagent.multiplayerSmokeClient")) {
                MineAgentRuntimeMod.LOGGER.info("MINEAGENT_SMOKE_MEDIA_FRAME_OK mediaId={} bytes={}",
                        metadata.mediaId(), bytes.length);
            }
        } catch (Exception failure) {
            MineAgentRuntimeMod.LOGGER.warn("Failed to upload MineAgent media frame", failure);
        }
    }

    private record FrameMetadata(String mediaId, long revision, String binding, long positionMillis) {
    }

    private record TextureEntry(String mediaId, long revision, long positionMillis, Identifier texture) {
    }
}
