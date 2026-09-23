package dev.mineagent.runtime.neoforge.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.LinkedHashMap;
import java.util.Map;

import static dev.mineagent.runtime.neoforge.MineAgentRuntimeMod.MOD_ID;

public final class MineAgentPayloads {
    private static final int MAX_MAP_ENTRIES = 256;
    private static final int MAX_KEY_LENGTH = 256;
    private static final int MAX_VALUE_LENGTH = 32_768;

    private MineAgentPayloads() {
    }
    public record AgentNamesRequest(java.util.UUID request) implements CustomPacketPayload {
        public static final Type<AgentNamesRequest> TYPE=MineAgentPayloads.type("agent_names_request");
        public static final StreamCodec<RegistryFriendlyByteBuf,AgentNamesRequest> CODEC=CustomPacketPayload.codec((p,b)->b.writeUUID(p.request()),b->new AgentNamesRequest(b.readUUID()));
        @Override public Type<AgentNamesRequest> type(){return TYPE;}
    }
    public record AgentNames(java.util.UUID request,java.util.UUID world,java.util.List<String> names,int page,boolean last) implements CustomPacketPayload {
        public static final Type<AgentNames> TYPE=MineAgentPayloads.type("agent_names");
        public static final StreamCodec<RegistryFriendlyByteBuf,AgentNames> CODEC=CustomPacketPayload.codec((p,b)->{b.writeUUID(p.request());b.writeUUID(p.world());b.writeVarInt(p.page());b.writeBoolean(p.last());b.writeVarInt(p.names().size());for(String n:p.names())b.writeUtf(n,128);},b->{var r=b.readUUID();var w=b.readUUID();int page=b.readVarInt();boolean last=b.readBoolean();int size=b.readVarInt();if(size<0||size>64)throw new IllegalArgumentException("AGENT_NAMES_SIZE");var names=new java.util.ArrayList<String>();for(int i=0;i<size;i++)names.add(b.readUtf(128));return new AgentNames(r,w,names,page,last);});
        public AgentNames(java.util.UUID request,java.util.UUID world,java.util.List<String> names){this(request,world,names,0,true);}
        public AgentNames{if(page<0)throw new IllegalArgumentException("AGENT_NAMES_PAGE");names=java.util.List.copyOf(names);if(names.size()>64||names.stream().anyMatch(n->n.isBlank()||n.length()>128))throw new IllegalArgumentException("AGENT_NAMES_SIZE");}
        @Override public Type<AgentNames> type(){return TYPE;}
    }
    public record NativeStudioRequest(java.util.UUID requestId,java.util.UUID operationId,String worldId,boolean write,Map<String,String> arguments) implements CustomPacketPayload {
        public static final Type<NativeStudioRequest> TYPE=MineAgentPayloads.type("native_studio_request");
        public static final StreamCodec<RegistryFriendlyByteBuf,NativeStudioRequest> CODEC=CustomPacketPayload.codec((p,b)->{b.writeUUID(p.requestId());b.writeUUID(p.operationId());b.writeUtf(p.worldId(),36);b.writeBoolean(p.write());writeMap(b,p.arguments());},b->new NativeStudioRequest(b.readUUID(),b.readUUID(),b.readUtf(36),b.readBoolean(),readMap(b)));
        public NativeStudioRequest { java.util.Objects.requireNonNull(requestId);java.util.Objects.requireNonNull(operationId);arguments=Map.copyOf(arguments);if(worldId==null||worldId.length()>36||arguments.size()>16||arguments.values().stream().mapToInt(String::length).sum()>20000)throw new IllegalArgumentException("STUDIO_NATIVE_REQUEST_LIMIT"); }
        @Override public Type<NativeStudioRequest> type(){return TYPE;}
    }
    public record NativeStudioResponse(java.util.UUID requestId,String worldId,String code,String state) implements CustomPacketPayload {
        public static final Type<NativeStudioResponse> TYPE=MineAgentPayloads.type("native_studio_response");
        public static final StreamCodec<RegistryFriendlyByteBuf,NativeStudioResponse> CODEC=CustomPacketPayload.codec((p,b)->{b.writeUUID(p.requestId());b.writeUtf(p.worldId(),36);b.writeUtf(p.code(),128);b.writeUtf(p.state(),32768);},b->new NativeStudioResponse(b.readUUID(),b.readUtf(36),b.readUtf(128),b.readUtf(32768)));
        @Override public Type<NativeStudioResponse> type(){return TYPE;}
    }
    public record SecretConfigWrite(java.util.UUID operation,long revision,java.util.UUID world,java.util.UUID instance,Map<String,String> envelope)implements CustomPacketPayload{
        public static final Type<SecretConfigWrite> TYPE=MineAgentPayloads.type("secret_config_write");
        public static final StreamCodec<RegistryFriendlyByteBuf,SecretConfigWrite> CODEC=CustomPacketPayload.codec((p,b)->{b.writeUUID(p.operation());b.writeVarLong(p.revision());b.writeUUID(p.world());b.writeUUID(p.instance());writeMap(b,p.envelope());},b->new SecretConfigWrite(b.readUUID(),b.readVarLong(),b.readUUID(),b.readUUID(),readMap(b)));
        @Override public Type<SecretConfigWrite> type(){return TYPE;}
    }
    public record SecretConfigResult(java.util.UUID operation,boolean accepted,String code,long revision,boolean configured)implements CustomPacketPayload{
        public static final Type<SecretConfigResult> TYPE=MineAgentPayloads.type("secret_config_result");
        public static final StreamCodec<RegistryFriendlyByteBuf,SecretConfigResult> CODEC=CustomPacketPayload.codec((p,b)->{b.writeUUID(p.operation());b.writeBoolean(p.accepted());b.writeUtf(p.code(),128);b.writeVarLong(p.revision());b.writeBoolean(p.configured());},b->new SecretConfigResult(b.readUUID(),b.readBoolean(),b.readUtf(128),b.readVarLong(),b.readBoolean()));
        @Override public Type<SecretConfigResult> type(){return TYPE;}
    }

    public record PanelRequest(int agentOffset) implements CustomPacketPayload {
        public static final Type<PanelRequest> TYPE = MineAgentPayloads.type("panel_request");
        public static final StreamCodec<RegistryFriendlyByteBuf, PanelRequest> CODEC =
                CustomPacketPayload.codec((payload, buffer) -> buffer.writeVarInt(payload.agentOffset()), buffer -> new PanelRequest(buffer.readVarInt()));
        public PanelRequest(){this(-1);}
        public PanelRequest{if(agentOffset < -1)throw new IllegalArgumentException("AGENT_PAGE_OFFSET");}

        @Override
        public Type<PanelRequest> type() {
            return TYPE;
        }
    }

    public record PanelSnapshot(long revision, Map<String, String> values) implements CustomPacketPayload {
        public static final Type<PanelSnapshot> TYPE = MineAgentPayloads.type("panel_snapshot");
        public static final StreamCodec<RegistryFriendlyByteBuf, PanelSnapshot> CODEC = CustomPacketPayload.codec(
                (payload, buffer) -> {
                    buffer.writeVarLong(payload.revision());
                    writeMap(buffer, payload.values());
                },
                buffer -> new PanelSnapshot(buffer.readVarLong(), readMap(buffer))
        );

        public PanelSnapshot {
            values = Map.copyOf(values);
        }

        @Override
        public Type<PanelSnapshot> type() {
            return TYPE;
        }
    }

    public record ConfigPatch(long expectedRevision, Map<String, String> values) implements CustomPacketPayload {
        public static final Type<ConfigPatch> TYPE = MineAgentPayloads.type("config_patch");
        public static final StreamCodec<RegistryFriendlyByteBuf, ConfigPatch> CODEC = CustomPacketPayload.codec(
                (payload, buffer) -> {
                    buffer.writeVarLong(payload.expectedRevision());
                    writeMap(buffer, payload.values());
                },
                buffer -> new ConfigPatch(buffer.readVarLong(), readMap(buffer))
        );

        public ConfigPatch {
            values = Map.copyOf(values);
        }

        @Override
        public Type<ConfigPatch> type() {
            return TYPE;
        }
    }

    public record ConfigPatchResult(
            boolean accepted,
            String errorCode,
            long revision,
            Map<String, String> values,
            Map<String, String> fieldErrors
    ) implements CustomPacketPayload {
        public static final Type<ConfigPatchResult> TYPE = MineAgentPayloads.type("config_patch_result");
        public static final StreamCodec<RegistryFriendlyByteBuf, ConfigPatchResult> CODEC = CustomPacketPayload.codec(
                (payload, buffer) -> {
                    buffer.writeBoolean(payload.accepted());
                    buffer.writeUtf(payload.errorCode(), MAX_KEY_LENGTH);
                    buffer.writeVarLong(payload.revision());
                    writeMap(buffer, payload.values());
                    writeMap(buffer, payload.fieldErrors());
                },
                buffer -> new ConfigPatchResult(
                        buffer.readBoolean(),
                        buffer.readUtf(MAX_KEY_LENGTH),
                        buffer.readVarLong(),
                        readMap(buffer),
                        readMap(buffer)
                )
        );

        public ConfigPatchResult {
            values = Map.copyOf(values);
            fieldErrors = Map.copyOf(fieldErrors);
        }

        @Override
        public Type<ConfigPatchResult> type() {
            return TYPE;
        }
    }

    public record PromptRequest(String capability, String prompt) implements CustomPacketPayload {
        public static final Type<PromptRequest> TYPE = MineAgentPayloads.type("prompt_request");
        public static final StreamCodec<RegistryFriendlyByteBuf, PromptRequest> CODEC = CustomPacketPayload.codec(
                (payload, buffer) -> {
                    buffer.writeUtf(payload.capability(), MAX_KEY_LENGTH);
                    buffer.writeUtf(payload.prompt(), MAX_VALUE_LENGTH);
                },
                buffer -> new PromptRequest(buffer.readUtf(MAX_KEY_LENGTH), buffer.readUtf(MAX_VALUE_LENGTH))
        );

        @Override
        public Type<PromptRequest> type() {
            return TYPE;
        }
    }

    public record PromptResult(boolean accepted, String providerId, String text, String errorCode)
            implements CustomPacketPayload {
        public static final Type<PromptResult> TYPE = MineAgentPayloads.type("prompt_result");
        public static final StreamCodec<RegistryFriendlyByteBuf, PromptResult> CODEC = CustomPacketPayload.codec(
                (payload, buffer) -> {
                    buffer.writeBoolean(payload.accepted());
                    buffer.writeUtf(payload.providerId(), MAX_KEY_LENGTH);
                    buffer.writeUtf(payload.text(), MAX_VALUE_LENGTH);
                    buffer.writeUtf(payload.errorCode(), MAX_KEY_LENGTH);
                },
                buffer -> new PromptResult(
                        buffer.readBoolean(), buffer.readUtf(MAX_KEY_LENGTH),
                        buffer.readUtf(MAX_VALUE_LENGTH), buffer.readUtf(MAX_KEY_LENGTH)
                )
        );

        @Override
        public Type<PromptResult> type() {
            return TYPE;
        }
    }

    public record DecisionRequestPayload() implements CustomPacketPayload {
        public static final Type<DecisionRequestPayload> TYPE = MineAgentPayloads.type("decision_request");
        public static final StreamCodec<RegistryFriendlyByteBuf, DecisionRequestPayload> CODEC =
                CustomPacketPayload.codec((payload, buffer) -> { }, buffer -> new DecisionRequestPayload());

        @Override
        public Type<DecisionRequestPayload> type() {
            return TYPE;
        }
    }

    public record DecisionCommand(String action, Map<String, String> values) implements CustomPacketPayload {
        public static final Type<DecisionCommand> TYPE = MineAgentPayloads.type("decision_command");
        public static final StreamCodec<RegistryFriendlyByteBuf, DecisionCommand> CODEC = CustomPacketPayload.codec(
                (payload, buffer) -> {
                    buffer.writeUtf(payload.action(), MAX_KEY_LENGTH);
                    writeMap(buffer, payload.values());
                },
                buffer -> new DecisionCommand(buffer.readUtf(MAX_KEY_LENGTH), readMap(buffer))
        );

        public DecisionCommand {
            values = Map.copyOf(values);
        }

        @Override
        public Type<DecisionCommand> type() {
            return TYPE;
        }
    }

    public record DecisionState(String errorCode, Map<String, String> values) implements CustomPacketPayload {
        public static final Type<DecisionState> TYPE = MineAgentPayloads.type("decision_state");
        public static final StreamCodec<RegistryFriendlyByteBuf, DecisionState> CODEC = CustomPacketPayload.codec(
                (payload, buffer) -> {
                    buffer.writeUtf(payload.errorCode(), MAX_KEY_LENGTH);
                    writeMap(buffer, payload.values());
                },
                buffer -> new DecisionState(buffer.readUtf(MAX_KEY_LENGTH), readMap(buffer))
        );

        public DecisionState {
            values = Map.copyOf(values);
        }

        @Override
        public Type<DecisionState> type() {
            return TYPE;
        }
    }

    public record ConversationRequest(String agentId) implements CustomPacketPayload {
        public static final Type<ConversationRequest> TYPE = MineAgentPayloads.type("conversation_request");
        public static final StreamCodec<RegistryFriendlyByteBuf, ConversationRequest> CODEC = CustomPacketPayload.codec(
                (payload, buffer) -> buffer.writeUtf(payload.agentId(), MAX_KEY_LENGTH),
                buffer -> new ConversationRequest(buffer.readUtf(MAX_KEY_LENGTH))
        );

        @Override
        public Type<ConversationRequest> type() {
            return TYPE;
        }
    }

    public record ConversationSendV2(java.util.UUID operationId,java.util.UUID worldId,java.util.UUID agentId,java.util.UUID conversationId,long expectedRevision,String text) implements CustomPacketPayload {
        public ConversationSendV2 { java.util.Objects.requireNonNull(operationId);java.util.Objects.requireNonNull(worldId);java.util.Objects.requireNonNull(agentId);java.util.Objects.requireNonNull(conversationId);if(expectedRevision<1||text==null||text.isBlank()||text.length()>16384)throw new IllegalArgumentException("CONVERSATION_SEND_INPUT"); }
        public static final Type<ConversationSendV2> TYPE=MineAgentPayloads.type("conversation_send_v2");
        public static final StreamCodec<RegistryFriendlyByteBuf,ConversationSendV2> CODEC=CustomPacketPayload.codec((p,b)->{b.writeUUID(p.operationId());b.writeUUID(p.worldId());b.writeUUID(p.agentId());b.writeUUID(p.conversationId());b.writeVarLong(p.expectedRevision());b.writeUtf(p.text(),16384);},b->new ConversationSendV2(b.readUUID(),b.readUUID(),b.readUUID(),b.readUUID(),b.readVarLong(),b.readUtf(16384)));
        @Override public Type<ConversationSendV2> type(){return TYPE;}
    }
    public record ConversationReceipt(java.util.UUID operationId,String code,Map<String,String> values) implements CustomPacketPayload {
        public ConversationReceipt {java.util.Objects.requireNonNull(operationId);java.util.Objects.requireNonNull(code);values=Map.copyOf(values);}
        public static final Type<ConversationReceipt> TYPE=MineAgentPayloads.type("conversation_receipt_v2");
        public static final StreamCodec<RegistryFriendlyByteBuf,ConversationReceipt> CODEC=CustomPacketPayload.codec((p,b)->{b.writeUUID(p.operationId());b.writeUtf(p.code(),64);writeMap(b,p.values());},b->new ConversationReceipt(b.readUUID(),b.readUtf(64),readMap(b)));
        @Override public Type<ConversationReceipt> type(){return TYPE;}
    }

    public record ConversationSend(String agentId, String text, boolean privateResponse) implements CustomPacketPayload {
        public static final Type<ConversationSend> TYPE = MineAgentPayloads.type("conversation_send");
        public static final StreamCodec<RegistryFriendlyByteBuf, ConversationSend> CODEC = CustomPacketPayload.codec(
                (payload, buffer) -> {
                    buffer.writeUtf(payload.agentId(), MAX_KEY_LENGTH);
                    buffer.writeUtf(payload.text(), MAX_VALUE_LENGTH);
                    buffer.writeBoolean(payload.privateResponse());
                },
                buffer -> new ConversationSend(
                        buffer.readUtf(MAX_KEY_LENGTH), buffer.readUtf(MAX_VALUE_LENGTH), buffer.readBoolean())
        );

        @Override
        public Type<ConversationSend> type() {
            return TYPE;
        }
    }

    public record ConversationState(String errorCode, Map<String, String> values) implements CustomPacketPayload {
        public static final Type<ConversationState> TYPE = MineAgentPayloads.type("conversation_state");
        public static final StreamCodec<RegistryFriendlyByteBuf, ConversationState> CODEC = CustomPacketPayload.codec(
                (payload, buffer) -> {
                    buffer.writeUtf(payload.errorCode(), MAX_KEY_LENGTH);
                    writeMap(buffer, payload.values());
                },
                buffer -> new ConversationState(buffer.readUtf(MAX_KEY_LENGTH), readMap(buffer))
        );

        public ConversationState {
            values = Map.copyOf(values);
        }

        @Override
        public Type<ConversationState> type() {
            return TYPE;
        }
    }

    public record ConversationStream(
            String conversationId,
            String phase,
            int sequence,
            String delta,
            String errorCode
    ) implements CustomPacketPayload {
        public static final Type<ConversationStream> TYPE = MineAgentPayloads.type("conversation_stream");
        public static final StreamCodec<RegistryFriendlyByteBuf, ConversationStream> CODEC = CustomPacketPayload.codec(
                (payload, buffer) -> {
                    buffer.writeUtf(payload.conversationId(), MAX_KEY_LENGTH);
                    buffer.writeUtf(payload.phase(), MAX_KEY_LENGTH);
                    buffer.writeVarInt(payload.sequence());
                    buffer.writeUtf(payload.delta(), MAX_VALUE_LENGTH);
                    buffer.writeUtf(payload.errorCode(), MAX_KEY_LENGTH);
                },
                buffer -> new ConversationStream(buffer.readUtf(MAX_KEY_LENGTH), buffer.readUtf(MAX_KEY_LENGTH),
                        buffer.readVarInt(), buffer.readUtf(MAX_VALUE_LENGTH), buffer.readUtf(MAX_KEY_LENGTH))
        );

        @Override
        public Type<ConversationStream> type() {
            return TYPE;
        }
    }

    public record VoiceChunk(
            String sha256,
            int chunkIndex,
            int chunkCount,
            double sourceX,
            double sourceY,
            double sourceZ,
            boolean spatial,
            byte[] data
    ) implements CustomPacketPayload {
        private static final int MAX_CHUNK_BYTES = 24 * 1024;
        public static final Type<VoiceChunk> TYPE = MineAgentPayloads.type("voice_chunk");
        public static final StreamCodec<RegistryFriendlyByteBuf, VoiceChunk> CODEC = CustomPacketPayload.codec(
                (payload, buffer) -> {
                    buffer.writeUtf(payload.sha256(), 64);
                    buffer.writeVarInt(payload.chunkIndex());
                    buffer.writeVarInt(payload.chunkCount());
                    buffer.writeDouble(payload.sourceX());
                    buffer.writeDouble(payload.sourceY());
                    buffer.writeDouble(payload.sourceZ());
                    buffer.writeBoolean(payload.spatial());
                    buffer.writeByteArray(payload.data());
                },
                buffer -> new VoiceChunk(
                        buffer.readUtf(64), buffer.readVarInt(), buffer.readVarInt(),
                        buffer.readDouble(), buffer.readDouble(), buffer.readDouble(), buffer.readBoolean(),
                        buffer.readByteArray(MAX_CHUNK_BYTES)
                )
        );

        public VoiceChunk {
            data = data.clone();
            if (chunkIndex < 0 || chunkCount < 1 || chunkIndex >= chunkCount
                    || data.length < 1 || data.length > MAX_CHUNK_BYTES) {
                throw new IllegalArgumentException("invalid voice chunk");
            }
        }

        @Override
        public byte[] data() {
            return data.clone();
        }

        @Override
        public Type<VoiceChunk> type() {
            return TYPE;
        }
    }

    public record ConversationVoiceChunk(java.util.UUID operationId,java.util.UUID worldId,java.util.UUID agentId,java.util.UUID conversationId,java.util.UUID contextId,String sha256,int index,int count,byte[] data) implements CustomPacketPayload {
        public ConversationVoiceChunk{java.util.Objects.requireNonNull(operationId);java.util.Objects.requireNonNull(worldId);java.util.Objects.requireNonNull(agentId);java.util.Objects.requireNonNull(conversationId);java.util.Objects.requireNonNull(contextId);if(sha256==null||!sha256.matches("[a-f0-9]{64}")||count<1||count>512||index<0||index>=count||data==null||data.length<1||data.length>24*1024)throw new IllegalArgumentException("CONVERSATION_VOICE_CHUNK");data=data.clone();}
        @Override public byte[] data(){return data.clone();}
        public static final Type<ConversationVoiceChunk> TYPE=MineAgentPayloads.type("conversation_voice_v2");
        public static final StreamCodec<RegistryFriendlyByteBuf,ConversationVoiceChunk> CODEC=CustomPacketPayload.codec((p,b)->{b.writeUUID(p.operationId);b.writeUUID(p.worldId);b.writeUUID(p.agentId);b.writeUUID(p.conversationId);b.writeUUID(p.contextId);b.writeUtf(p.sha256,64);b.writeVarInt(p.index);b.writeVarInt(p.count);b.writeByteArray(p.data);},b->new ConversationVoiceChunk(b.readUUID(),b.readUUID(),b.readUUID(),b.readUUID(),b.readUUID(),b.readUtf(64),b.readVarInt(),b.readVarInt(),b.readByteArray(24*1024)));
        @Override public Type<ConversationVoiceChunk> type(){return TYPE;}
    }

    public record AgentCommand(String action, Map<String, String> values) implements CustomPacketPayload {
        public static final Type<AgentCommand> TYPE = MineAgentPayloads.type("agent_command");
        public static final StreamCodec<RegistryFriendlyByteBuf, AgentCommand> CODEC = CustomPacketPayload.codec(
                (payload, buffer) -> {
                    buffer.writeUtf(payload.action(), MAX_KEY_LENGTH);
                    writeMap(buffer, payload.values());
                },
                buffer -> new AgentCommand(buffer.readUtf(MAX_KEY_LENGTH), readMap(buffer))
        );

        public AgentCommand {
            values = Map.copyOf(values);
        }

        @Override
        public Type<AgentCommand> type() {
            return TYPE;
        }
    }

    public record AgentCommandResult(boolean accepted, String errorCode) implements CustomPacketPayload {
        public static final Type<AgentCommandResult> TYPE = MineAgentPayloads.type("agent_command_result");
        public static final StreamCodec<RegistryFriendlyByteBuf, AgentCommandResult> CODEC = CustomPacketPayload.codec(
                (payload, buffer) -> {
                    buffer.writeBoolean(payload.accepted());
                    buffer.writeUtf(payload.errorCode(), MAX_KEY_LENGTH);
                },
                buffer -> new AgentCommandResult(buffer.readBoolean(), buffer.readUtf(MAX_KEY_LENGTH))
        );

        @Override
        public Type<AgentCommandResult> type() {
            return TYPE;
        }
    }

    public record BasketballScore(int points, int totalScore) implements CustomPacketPayload {
        public static final Type<BasketballScore> TYPE = MineAgentPayloads.type("basketball_score");
        public static final StreamCodec<RegistryFriendlyByteBuf, BasketballScore> CODEC = CustomPacketPayload.codec(
                (payload, buffer) -> {
                    buffer.writeVarInt(payload.points());
                    buffer.writeVarInt(payload.totalScore());
                },
                buffer -> new BasketballScore(buffer.readVarInt(), buffer.readVarInt())
        );

        public BasketballScore {
            if (points < 1 || points > 3 || totalScore < points) {
                throw new IllegalArgumentException("invalid basketball score");
            }
        }

        @Override
        public Type<BasketballScore> type() {
            return TYPE;
        }
    }

    public record TaskCommand(String action, Map<String, String> values) implements CustomPacketPayload {
        public static final Type<TaskCommand> TYPE = MineAgentPayloads.type("task_command");
        public static final StreamCodec<RegistryFriendlyByteBuf, TaskCommand> CODEC = CustomPacketPayload.codec(
                (payload, buffer) -> {
                    buffer.writeUtf(payload.action(), MAX_KEY_LENGTH);
                    writeMap(buffer, payload.values());
                },
                buffer -> new TaskCommand(buffer.readUtf(MAX_KEY_LENGTH), readMap(buffer))
        );

        public TaskCommand {
            values = Map.copyOf(values);
        }

        @Override
        public Type<TaskCommand> type() {
            return TYPE;
        }
    }

    public record TaskState(String errorCode, Map<String, String> values) implements CustomPacketPayload {
        public static final Type<TaskState> TYPE = MineAgentPayloads.type("task_state");
        public static final StreamCodec<RegistryFriendlyByteBuf, TaskState> CODEC = CustomPacketPayload.codec(
                (payload, buffer) -> {
                    buffer.writeUtf(payload.errorCode(), MAX_KEY_LENGTH);
                    writeMap(buffer, payload.values());
                },
                buffer -> new TaskState(buffer.readUtf(MAX_KEY_LENGTH), readMap(buffer))
        );

        public TaskState {
            values = Map.copyOf(values);
        }

        @Override
        public Type<TaskState> type() {
            return TYPE;
        }
    }

    public record CodeCommand(String action, Map<String, String> values) implements CustomPacketPayload {
        public static final Type<CodeCommand> TYPE = MineAgentPayloads.type("code_command");
        public static final StreamCodec<RegistryFriendlyByteBuf, CodeCommand> CODEC = CustomPacketPayload.codec(
                (payload, buffer) -> {
                    buffer.writeUtf(payload.action(), MAX_KEY_LENGTH);
                    writeMap(buffer, payload.values());
                },
                buffer -> new CodeCommand(buffer.readUtf(MAX_KEY_LENGTH), readMap(buffer))
        );

        public CodeCommand {
            values = Map.copyOf(values);
        }

        @Override
        public Type<CodeCommand> type() {
            return TYPE;
        }
    }

    public record CodeState(String errorCode, Map<String, String> values) implements CustomPacketPayload {
        public static final Type<CodeState> TYPE = MineAgentPayloads.type("code_state");
        public static final StreamCodec<RegistryFriendlyByteBuf, CodeState> CODEC = CustomPacketPayload.codec(
                (payload, buffer) -> {
                    buffer.writeUtf(payload.errorCode(), MAX_KEY_LENGTH);
                    writeMap(buffer, payload.values());
                },
                buffer -> new CodeState(buffer.readUtf(MAX_KEY_LENGTH), readMap(buffer))
        );

        public CodeState {
            values = Map.copyOf(values);
        }

        @Override
        public Type<CodeState> type() {
            return TYPE;
        }
    }

    public record MemoryCommand(String action, Map<String, String> values) implements CustomPacketPayload {
        public static final Type<MemoryCommand> TYPE = MineAgentPayloads.type("memory_command");
        public static final StreamCodec<RegistryFriendlyByteBuf, MemoryCommand> CODEC = CustomPacketPayload.codec(
                (payload, buffer) -> {
                    buffer.writeUtf(payload.action(), MAX_KEY_LENGTH);
                    writeMap(buffer, payload.values());
                },
                buffer -> new MemoryCommand(buffer.readUtf(MAX_KEY_LENGTH), readMap(buffer))
        );

        public MemoryCommand {
            values = Map.copyOf(values);
        }

        @Override
        public Type<MemoryCommand> type() {
            return TYPE;
        }
    }

    public record MemoryState(String errorCode, Map<String, String> values) implements CustomPacketPayload {
        public static final Type<MemoryState> TYPE = MineAgentPayloads.type("memory_state");
        public static final StreamCodec<RegistryFriendlyByteBuf, MemoryState> CODEC = CustomPacketPayload.codec(
                (payload, buffer) -> {
                    buffer.writeUtf(payload.errorCode(), MAX_KEY_LENGTH);
                    writeMap(buffer, payload.values());
                },
                buffer -> new MemoryState(buffer.readUtf(MAX_KEY_LENGTH), readMap(buffer))
        );

        public MemoryState {
            values = Map.copyOf(values);
        }

        @Override
        public Type<MemoryState> type() {
            return TYPE;
        }
    }

    public record OpenPanel(String section) implements CustomPacketPayload {
        public static final Type<OpenPanel> TYPE = MineAgentPayloads.type("open_panel");
        public static final StreamCodec<RegistryFriendlyByteBuf, OpenPanel> CODEC = CustomPacketPayload.codec(
                (payload, buffer) -> buffer.writeUtf(payload.section(), MAX_KEY_LENGTH),
                buffer -> new OpenPanel(buffer.readUtf(MAX_KEY_LENGTH))
        );

        @Override
        public Type<OpenPanel> type() {
            return TYPE;
        }
    }

    public record ModKnowledgeRequest() implements CustomPacketPayload {
        public static final Type<ModKnowledgeRequest> TYPE = MineAgentPayloads.type("mod_knowledge_request");
        public static final StreamCodec<RegistryFriendlyByteBuf, ModKnowledgeRequest> CODEC =
                CustomPacketPayload.codec((payload, buffer) -> { }, buffer -> new ModKnowledgeRequest());

        @Override
        public Type<ModKnowledgeRequest> type() {
            return TYPE;
        }
    }

    public record ModKnowledgeState(String errorCode, Map<String, String> values) implements CustomPacketPayload {
        public static final Type<ModKnowledgeState> TYPE = MineAgentPayloads.type("mod_knowledge_state");
        public static final StreamCodec<RegistryFriendlyByteBuf, ModKnowledgeState> CODEC = CustomPacketPayload.codec(
                (payload, buffer) -> {
                    buffer.writeUtf(payload.errorCode(), MAX_KEY_LENGTH);
                    writeMap(buffer, payload.values());
                },
                buffer -> new ModKnowledgeState(buffer.readUtf(MAX_KEY_LENGTH), readMap(buffer))
        );

        public ModKnowledgeState {
            values = Map.copyOf(values);
        }

        @Override
        public Type<ModKnowledgeState> type() {
            return TYPE;
        }
    }

    public record BackupCommand(String action, Map<String, String> values) implements CustomPacketPayload {
        public static final Type<BackupCommand> TYPE = MineAgentPayloads.type("backup_command");
        public static final StreamCodec<RegistryFriendlyByteBuf, BackupCommand> CODEC = CustomPacketPayload.codec(
                (payload, buffer) -> {
                    buffer.writeUtf(payload.action(), MAX_KEY_LENGTH);
                    writeMap(buffer, payload.values());
                },
                buffer -> new BackupCommand(buffer.readUtf(MAX_KEY_LENGTH), readMap(buffer))
        );

        public BackupCommand {
            values = Map.copyOf(values);
        }

        @Override
        public Type<BackupCommand> type() {
            return TYPE;
        }
    }

    public record BackupState(String errorCode, Map<String, String> values) implements CustomPacketPayload {
        public static final Type<BackupState> TYPE = MineAgentPayloads.type("backup_state");
        public static final StreamCodec<RegistryFriendlyByteBuf, BackupState> CODEC = CustomPacketPayload.codec(
                (payload, buffer) -> {
                    buffer.writeUtf(payload.errorCode(), MAX_KEY_LENGTH);
                    writeMap(buffer, payload.values());
                },
                buffer -> new BackupState(buffer.readUtf(MAX_KEY_LENGTH), readMap(buffer))
        );

        public BackupState {
            values = Map.copyOf(values);
        }

        @Override
        public Type<BackupState> type() {
            return TYPE;
        }
    }

    public record MediaCommand(String action, Map<String, String> values) implements CustomPacketPayload {
        public static final Type<MediaCommand> TYPE = MineAgentPayloads.type("media_command");
        public static final StreamCodec<RegistryFriendlyByteBuf, MediaCommand> CODEC = CustomPacketPayload.codec(
                (payload, buffer) -> {
                    buffer.writeUtf(payload.action(), MAX_KEY_LENGTH);
                    writeMap(buffer, payload.values());
                },
                buffer -> new MediaCommand(buffer.readUtf(MAX_KEY_LENGTH), readMap(buffer))
        );

        public MediaCommand {
            values = Map.copyOf(values);
        }

        @Override
        public Type<MediaCommand> type() {
            return TYPE;
        }
    }

    public record MediaState(String errorCode, Map<String, String> values) implements CustomPacketPayload {
        public static final Type<MediaState> TYPE = MineAgentPayloads.type("media_state");
        public static final StreamCodec<RegistryFriendlyByteBuf, MediaState> CODEC = CustomPacketPayload.codec(
                (payload, buffer) -> {
                    buffer.writeUtf(payload.errorCode(), MAX_KEY_LENGTH);
                    writeMap(buffer, payload.values());
                },
                buffer -> new MediaState(buffer.readUtf(MAX_KEY_LENGTH), readMap(buffer))
        );

        public MediaState {
            values = Map.copyOf(values);
        }

        @Override
        public Type<MediaState> type() {
            return TYPE;
        }
    }

    public record MediaFrameChunk(
            String mediaId,
            long revision,
            String binding,
            long positionMillis,
            String sha256,
            int chunkIndex,
            int chunkCount,
            byte[] data
    ) implements CustomPacketPayload {
        private static final int MAX_CHUNK_BYTES = 24 * 1024;
        public static final Type<MediaFrameChunk> TYPE = MineAgentPayloads.type("media_frame_chunk");
        public static final StreamCodec<RegistryFriendlyByteBuf, MediaFrameChunk> CODEC = CustomPacketPayload.codec(
                (payload, buffer) -> {
                    buffer.writeUtf(payload.mediaId(), MAX_KEY_LENGTH);
                    buffer.writeVarLong(payload.revision());
                    buffer.writeUtf(payload.binding(), MAX_KEY_LENGTH);
                    buffer.writeVarLong(payload.positionMillis());
                    buffer.writeUtf(payload.sha256(), 64);
                    buffer.writeVarInt(payload.chunkIndex());
                    buffer.writeVarInt(payload.chunkCount());
                    buffer.writeByteArray(payload.data());
                },
                buffer -> new MediaFrameChunk(buffer.readUtf(MAX_KEY_LENGTH), buffer.readVarLong(),
                        buffer.readUtf(MAX_KEY_LENGTH), buffer.readVarLong(), buffer.readUtf(64),
                        buffer.readVarInt(), buffer.readVarInt(), buffer.readByteArray(MAX_CHUNK_BYTES))
        );

        public MediaFrameChunk {
            data = data.clone();
            if (!mediaId.matches("[0-9a-fA-F-]{36}") || revision < 1 || positionMillis < 0
                    || !sha256.matches("[0-9a-f]{64}") || chunkIndex < 0 || chunkCount < 1
                    || chunkIndex >= chunkCount || chunkCount > 512 || data.length < 1
                    || data.length > MAX_CHUNK_BYTES) {
                throw new IllegalArgumentException("invalid media frame chunk");
            }
        }

        @Override
        public byte[] data() {
            return data.clone();
        }

        @Override
        public Type<MediaFrameChunk> type() {
            return TYPE;
        }
    }

    public record MediaAudioChunk(
            String mediaId,
            String sha256,
            int chunkIndex,
            int chunkCount,
            double sourceX,
            double sourceY,
            double sourceZ,
            byte[] data
    ) implements CustomPacketPayload {
        private static final int MAX_CHUNK_BYTES = 24 * 1024;
        public static final Type<MediaAudioChunk> TYPE = MineAgentPayloads.type("media_audio_chunk");
        public static final StreamCodec<RegistryFriendlyByteBuf, MediaAudioChunk> CODEC = CustomPacketPayload.codec(
                (payload, buffer) -> {
                    buffer.writeUtf(payload.mediaId(), MAX_KEY_LENGTH);
                    buffer.writeUtf(payload.sha256(), 64);
                    buffer.writeVarInt(payload.chunkIndex());
                    buffer.writeVarInt(payload.chunkCount());
                    buffer.writeDouble(payload.sourceX());
                    buffer.writeDouble(payload.sourceY());
                    buffer.writeDouble(payload.sourceZ());
                    buffer.writeByteArray(payload.data());
                },
                buffer -> new MediaAudioChunk(buffer.readUtf(MAX_KEY_LENGTH), buffer.readUtf(64),
                        buffer.readVarInt(), buffer.readVarInt(), buffer.readDouble(), buffer.readDouble(),
                        buffer.readDouble(), buffer.readByteArray(MAX_CHUNK_BYTES))
        );

        public MediaAudioChunk {
            data = data.clone();
            if (!mediaId.matches("[0-9a-fA-F-]{36}") || !sha256.matches("[0-9a-f]{64}")
                    || chunkIndex < 0 || chunkCount < 1 || chunkIndex >= chunkCount || chunkCount > 512
                    || data.length < 1 || data.length > MAX_CHUNK_BYTES
                    || !Double.isFinite(sourceX) || !Double.isFinite(sourceY) || !Double.isFinite(sourceZ)) {
                throw new IllegalArgumentException("invalid media audio chunk");
            }
        }

        @Override
        public byte[] data() {
            return data.clone();
        }

        @Override
        public Type<MediaAudioChunk> type() {
            return TYPE;
        }
    }

    public record AppearanceCommand(
            String agentId,
            String modelId,
            String textureId,
            String animationId,
            long expectedRevision,
            String requestId
    ) implements CustomPacketPayload {
        public static final Type<AppearanceCommand> TYPE = MineAgentPayloads.type("appearance_command");
        public static final StreamCodec<RegistryFriendlyByteBuf, AppearanceCommand> CODEC = CustomPacketPayload.codec(
                (payload, buffer) -> {
                    buffer.writeUtf(payload.agentId(), MAX_KEY_LENGTH);
                    buffer.writeUtf(payload.modelId(), MAX_KEY_LENGTH);
                    buffer.writeUtf(payload.textureId(), MAX_KEY_LENGTH);
                    buffer.writeUtf(payload.animationId(), MAX_KEY_LENGTH);
                    buffer.writeVarLong(payload.expectedRevision());
                    buffer.writeUtf(payload.requestId(), MAX_KEY_LENGTH);
                },
                buffer -> new AppearanceCommand(
                        buffer.readUtf(MAX_KEY_LENGTH), buffer.readUtf(MAX_KEY_LENGTH), buffer.readUtf(MAX_KEY_LENGTH),
                        buffer.readUtf(MAX_KEY_LENGTH), buffer.readVarLong(), buffer.readUtf(MAX_KEY_LENGTH))
        );

        @Override
        public Type<AppearanceCommand> type() {
            return TYPE;
        }
    }

    public record AppearanceState(
            String agentId,
            String requestId,
            boolean accepted,
            String errorCode,
            long revision
    ) implements CustomPacketPayload {
        public static final Type<AppearanceState> TYPE = MineAgentPayloads.type("appearance_state");
        public static final StreamCodec<RegistryFriendlyByteBuf, AppearanceState> CODEC = CustomPacketPayload.codec(
                (payload, buffer) -> {
                    buffer.writeUtf(payload.agentId(), MAX_KEY_LENGTH);
                    buffer.writeUtf(payload.requestId(), MAX_KEY_LENGTH);
                    buffer.writeBoolean(payload.accepted());
                    buffer.writeUtf(payload.errorCode(), MAX_KEY_LENGTH);
                    buffer.writeVarLong(payload.revision());
                },
                buffer -> new AppearanceState(buffer.readUtf(MAX_KEY_LENGTH), buffer.readUtf(MAX_KEY_LENGTH),
                        buffer.readBoolean(), buffer.readUtf(MAX_KEY_LENGTH), buffer.readVarLong())
        );

        @Override
        public Type<AppearanceState> type() {
            return TYPE;
        }
    }

    public record PackageCommand(String action, Map<String, String> values) implements CustomPacketPayload {
        public static final Type<PackageCommand> TYPE = MineAgentPayloads.type("package_command");
        public static final StreamCodec<RegistryFriendlyByteBuf, PackageCommand> CODEC = CustomPacketPayload.codec(
                (payload, buffer) -> {
                    buffer.writeUtf(payload.action(), MAX_KEY_LENGTH);
                    writeMap(buffer, payload.values());
                },
                buffer -> new PackageCommand(buffer.readUtf(MAX_KEY_LENGTH), readMap(buffer))
        );

        public PackageCommand {
            values = Map.copyOf(values);
        }

        @Override
        public Type<PackageCommand> type() {
            return TYPE;
        }
    }

    public record PackageState(String errorCode, Map<String, String> values) implements CustomPacketPayload {
        public static final Type<PackageState> TYPE = MineAgentPayloads.type("package_state");
        public static final StreamCodec<RegistryFriendlyByteBuf, PackageState> CODEC = CustomPacketPayload.codec(
                (payload, buffer) -> {
                    buffer.writeUtf(payload.errorCode(), MAX_KEY_LENGTH);
                    writeMap(buffer, payload.values());
                },
                buffer -> new PackageState(buffer.readUtf(MAX_KEY_LENGTH), readMap(buffer))
        );

        public PackageState {
            values = Map.copyOf(values);
        }

        @Override
        public Type<PackageState> type() {
            return TYPE;
        }
    }

    public record PermissionCommand(String action, Map<String, String> values) implements CustomPacketPayload {
        public static final Type<PermissionCommand> TYPE = MineAgentPayloads.type("permission_command");
        public static final StreamCodec<RegistryFriendlyByteBuf, PermissionCommand> CODEC = CustomPacketPayload.codec(
                (payload, buffer) -> {
                    buffer.writeUtf(payload.action(), MAX_KEY_LENGTH);
                    writeMap(buffer, payload.values());
                },
                buffer -> new PermissionCommand(buffer.readUtf(MAX_KEY_LENGTH), readMap(buffer))
        );

        public PermissionCommand {
            values = Map.copyOf(values);
        }

        @Override
        public Type<PermissionCommand> type() {
            return TYPE;
        }
    }

    public record PermissionState(String errorCode, Map<String, String> values) implements CustomPacketPayload {
        public static final Type<PermissionState> TYPE = MineAgentPayloads.type("permission_state");
        public static final StreamCodec<RegistryFriendlyByteBuf, PermissionState> CODEC = CustomPacketPayload.codec(
                (payload, buffer) -> {
                    buffer.writeUtf(payload.errorCode(), MAX_KEY_LENGTH);
                    writeMap(buffer, payload.values());
                },
                buffer -> new PermissionState(buffer.readUtf(MAX_KEY_LENGTH), readMap(buffer))
        );

        public PermissionState {
            values = Map.copyOf(values);
        }

        @Override
        public Type<PermissionState> type() {
            return TYPE;
        }
    }

    public record DiagnosticsRequest() implements CustomPacketPayload {
        public static final Type<DiagnosticsRequest> TYPE = MineAgentPayloads.type("diagnostics_request");
        public static final StreamCodec<RegistryFriendlyByteBuf, DiagnosticsRequest> CODEC =
                CustomPacketPayload.codec((payload, buffer) -> { }, buffer -> new DiagnosticsRequest());

        @Override
        public Type<DiagnosticsRequest> type() {
            return TYPE;
        }
    }

    public record DiagnosticsState(String errorCode, Map<String, String> values) implements CustomPacketPayload {
        public static final Type<DiagnosticsState> TYPE = MineAgentPayloads.type("diagnostics_state");
        public static final StreamCodec<RegistryFriendlyByteBuf, DiagnosticsState> CODEC = CustomPacketPayload.codec(
                (payload, buffer) -> {
                    buffer.writeUtf(payload.errorCode(), MAX_KEY_LENGTH);
                    writeMap(buffer, payload.values());
                },
                buffer -> new DiagnosticsState(buffer.readUtf(MAX_KEY_LENGTH), readMap(buffer))
        );

        public DiagnosticsState {
            values = Map.copyOf(values);
        }

        @Override
        public Type<DiagnosticsState> type() {
            return TYPE;
        }
    }

    public record ScoreboardCommand(
            String requestId,
            long expectedRevision,
            String action,
            Map<String, String> values
    ) implements CustomPacketPayload {
        public static final Type<ScoreboardCommand> TYPE = MineAgentPayloads.type("scoreboard_command");
        public static final StreamCodec<RegistryFriendlyByteBuf, ScoreboardCommand> CODEC =
                CustomPacketPayload.codec((payload, buffer) -> {
                    buffer.writeUtf(payload.requestId(), MAX_KEY_LENGTH);
                    buffer.writeVarLong(payload.expectedRevision());
                    buffer.writeUtf(payload.action(), MAX_KEY_LENGTH);
                    writeMap(buffer, payload.values());
                }, buffer -> new ScoreboardCommand(buffer.readUtf(MAX_KEY_LENGTH), buffer.readVarLong(),
                        buffer.readUtf(MAX_KEY_LENGTH), readMap(buffer)));

        public ScoreboardCommand {
            java.util.UUID.fromString(requestId);
            if (expectedRevision < 0 || action == null || !action.matches("[a-z][a-z0-9_.-]{0,63}")) {
                throw new IllegalArgumentException("invalid scoreboard command payload");
            }
            values = Map.copyOf(values);
        }

        @Override
        public Type<ScoreboardCommand> type() {
            return TYPE;
        }
    }

    public record ScoreboardState(
            String requestId,
            boolean accepted,
            String errorCode,
            long revision,
            Map<String, String> values
    ) implements CustomPacketPayload {
        public static final Type<ScoreboardState> TYPE = MineAgentPayloads.type("scoreboard_state");
        public static final StreamCodec<RegistryFriendlyByteBuf, ScoreboardState> CODEC =
                CustomPacketPayload.codec((payload, buffer) -> {
                    buffer.writeUtf(payload.requestId(), MAX_KEY_LENGTH);
                    buffer.writeBoolean(payload.accepted());
                    buffer.writeUtf(payload.errorCode(), MAX_KEY_LENGTH);
                    buffer.writeVarLong(payload.revision());
                    writeMap(buffer, payload.values());
                }, buffer -> new ScoreboardState(buffer.readUtf(MAX_KEY_LENGTH), buffer.readBoolean(),
                        buffer.readUtf(MAX_KEY_LENGTH), buffer.readVarLong(), readMap(buffer)));

        public ScoreboardState {
            values = Map.copyOf(values);
        }

        @Override
        public Type<ScoreboardState> type() {
            return TYPE;
        }
    }

    public record ScoreRowValue(
            String holder,
            String displayName,
            int score,
            String formattedScore,
            String iconSha256
    ) {
        public ScoreRowValue {
            if (holder == null || holder.isBlank() || holder.length() > 40
                    || displayName == null || displayName.length() > 2_048
                    || formattedScore == null || formattedScore.length() > 2_048
                    || iconSha256 == null || (!iconSha256.isEmpty() && !iconSha256.matches("[0-9a-f]{64}"))) {
                throw new IllegalArgumentException("invalid score row payload");
            }
        }
    }

    public record ScoreViewState(
            String viewId,
            long revision,
            boolean removed,
            String title,
            Map<String, String> layout,
            java.util.List<ScoreRowValue> rows
    ) implements CustomPacketPayload {
        private static final int MAX_ROWS = 128;
        public static final Type<ScoreViewState> TYPE = MineAgentPayloads.type("score_view_state");
        public static final StreamCodec<RegistryFriendlyByteBuf, ScoreViewState> CODEC =
                CustomPacketPayload.codec((payload, buffer) -> {
                    buffer.writeUtf(payload.viewId(), MAX_KEY_LENGTH);
                    buffer.writeVarLong(payload.revision());
                    buffer.writeBoolean(payload.removed());
                    buffer.writeUtf(payload.title(), MAX_VALUE_LENGTH);
                    writeMap(buffer, payload.layout());
                    buffer.writeVarInt(payload.rows().size());
                    for (ScoreRowValue row : payload.rows()) {
                        buffer.writeUtf(row.holder(), 40);
                        buffer.writeUtf(row.displayName(), 2_048);
                        buffer.writeInt(row.score());
                        buffer.writeUtf(row.formattedScore(), 2_048);
                        buffer.writeUtf(row.iconSha256(), 64);
                    }
                }, buffer -> {
                    String viewId = buffer.readUtf(MAX_KEY_LENGTH);
                    long revision = buffer.readVarLong();
                    boolean removed = buffer.readBoolean();
                    String title = buffer.readUtf(MAX_VALUE_LENGTH);
                    Map<String, String> layout = readMap(buffer);
                    int size = buffer.readVarInt();
                    if (size < 0 || size > MAX_ROWS) {
                        throw new IllegalArgumentException("invalid score row count");
                    }
                    var rows = new java.util.ArrayList<ScoreRowValue>(size);
                    for (int index = 0; index < size; index++) {
                        rows.add(new ScoreRowValue(buffer.readUtf(40), buffer.readUtf(2_048), buffer.readInt(),
                                buffer.readUtf(2_048), buffer.readUtf(64)));
                    }
                    return new ScoreViewState(viewId, revision, removed, title, layout, rows);
                });

        public ScoreViewState {
            java.util.UUID.fromString(viewId);
            if (revision < 1 || title == null || title.length() > MAX_VALUE_LENGTH || rows.size() > MAX_ROWS) {
                throw new IllegalArgumentException("invalid score view payload");
            }
            layout = Map.copyOf(layout);
            rows = java.util.List.copyOf(rows);
        }

        @Override
        public Type<ScoreViewState> type() {
            return TYPE;
        }
    }

    private static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> type(String path) {
        return new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(MOD_ID, path));
    }

    private static void writeMap(RegistryFriendlyByteBuf buffer, Map<String, String> values) {
        if (values.size() > MAX_MAP_ENTRIES) {
            throw new IllegalArgumentException("payload map is too large");
        }
        buffer.writeVarInt(values.size());
        values.forEach((key, value) -> {
            buffer.writeUtf(key, MAX_KEY_LENGTH);
            buffer.writeUtf(value, MAX_VALUE_LENGTH);
        });
    }

    private static Map<String, String> readMap(RegistryFriendlyByteBuf buffer) {
        int size = buffer.readVarInt();
        if (size < 0 || size > MAX_MAP_ENTRIES) {
            throw new IllegalArgumentException("invalid payload map size " + size);
        }
        var result = new LinkedHashMap<String, String>(size);
        for (int index = 0; index < size; index++) {
            String key = buffer.readUtf(MAX_KEY_LENGTH);
            String value = buffer.readUtf(MAX_VALUE_LENGTH);
            if (result.putIfAbsent(key, value) != null) {
                throw new IllegalArgumentException("duplicate payload key " + key);
            }
        }
        return Map.copyOf(result);
    }
}
