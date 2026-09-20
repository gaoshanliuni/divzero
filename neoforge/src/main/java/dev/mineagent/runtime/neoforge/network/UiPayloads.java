package dev.mineagent.runtime.neoforge.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import java.util.UUID;

public final class UiPayloads {
    private UiPayloads() {}
    private static void validate(UUID id, String channel, String json) {
        if (id == null || channel == null || !channel.matches("[a-zA-Z.]{1,40}") || json == null || json.length() > 131_072)
            throw new IllegalArgumentException("UI_WIRE_LIMIT");
    }
    public record Command(UUID requestId, String channel, String json) implements CustomPacketPayload {
        public Command { validate(requestId, channel, json); }
        public static final Type<Command> TYPE = new Type<>(Identifier.fromNamespaceAndPath("mineagent_runtime", "ui_command"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Command> CODEC = CustomPacketPayload.codec(
                (p, b) -> { b.writeUUID(p.requestId); b.writeUtf(p.channel, 40); b.writeUtf(p.json, 131_072); },
                b -> new Command(b.readUUID(), b.readUtf(40), b.readUtf(131_072)));
        @Override public Type<Command> type() { return TYPE; }
    }
    public record Event(UUID requestId, String channel, String json) implements CustomPacketPayload {
        public Event { validate(requestId, channel, json); }
        public static final Type<Event> TYPE = new Type<>(Identifier.fromNamespaceAndPath("mineagent_runtime", "ui_event"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Event> CODEC = CustomPacketPayload.codec(
                (p, b) -> { b.writeUUID(p.requestId); b.writeUtf(p.channel, 40); b.writeUtf(p.json, 131_072); },
                b -> new Event(b.readUUID(), b.readUtf(40), b.readUtf(131_072)));
        @Override public Type<Event> type() { return TYPE; }
    }
}
