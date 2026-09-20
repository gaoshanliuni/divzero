package dev.mineagent.runtime.neoforge.network;

import dev.mineagent.runtime.api.config.PanelSnapshot;

import java.util.Map;

public final class PanelSnapshotInbox {
    private static volatile PanelSnapshot snapshot = new PanelSnapshot(0, Map.of());
    private static volatile String lastErrorCode = "";
    private static volatile MineAgentPayloads.PromptResult promptResult =
            new MineAgentPayloads.PromptResult(false, "", "", "NOT_RUN");
    private static volatile MineAgentPayloads.DecisionState decisionState =
            new MineAgentPayloads.DecisionState("", Map.of("present", "false"));
    private static volatile MineAgentPayloads.ConversationState conversationState =
            new MineAgentPayloads.ConversationState("", Map.of("present", "false"));
    private static volatile MineAgentPayloads.ConversationReceipt conversationReceipt=new MineAgentPayloads.ConversationReceipt(new java.util.UUID(0,0),"NOT_RUN",Map.of());
    public static MineAgentPayloads.ConversationReceipt conversationReceipt(){return conversationReceipt;}
    public static void accept(MineAgentPayloads.ConversationReceipt receipt){conversationReceipt=receipt;generation++;}
    private static final dev.mineagent.runtime.client.conversation.StreamingConversationBuffer CONVERSATION_STREAMS =
            new dev.mineagent.runtime.client.conversation.StreamingConversationBuffer();
    private static volatile long conversationStreamGeneration;
    private static volatile String conversationStreamError = "";
    private static volatile MineAgentPayloads.AgentCommandResult agentCommandResult =
            new MineAgentPayloads.AgentCommandResult(true, "");
    private static volatile MineAgentPayloads.TaskState taskState =
            new MineAgentPayloads.TaskState("", Map.of("taskCount", "0"));
    private static volatile MineAgentPayloads.CodeState codeState =
            new MineAgentPayloads.CodeState("", Map.of("draftCount", "0"));
    private static volatile MineAgentPayloads.MemoryState memoryState =
            new MineAgentPayloads.MemoryState("", Map.of("memoryCount", "0"));
    private static volatile long generation;
    private static volatile boolean signatureValid;
    private static volatile MineAgentPayloads.ModKnowledgeState modKnowledgeState =
            new MineAgentPayloads.ModKnowledgeState("", Map.of("modCount", "0"));
    private static volatile MineAgentPayloads.BackupState backupState =
            new MineAgentPayloads.BackupState("", Map.of("snapshotCount", "0"));
    private static volatile MineAgentPayloads.MediaState mediaState =
            new MineAgentPayloads.MediaState("", Map.of("mediaCount", "0"));
    private static volatile MineAgentPayloads.AppearanceState appearanceState =
            new MineAgentPayloads.AppearanceState("", "", false, "NOT_RUN", 0);
    private static volatile MineAgentPayloads.PackageState packageState =
            new MineAgentPayloads.PackageState("", Map.of("packageCount", "0"));
    private static volatile MineAgentPayloads.DiagnosticsState diagnosticsState =
            new MineAgentPayloads.DiagnosticsState("", Map.of("eventCount", "0"));
    private static volatile MineAgentPayloads.PermissionState permissionState =
            new MineAgentPayloads.PermissionState("", Map.of("trustedCount", "0"));

    private PanelSnapshotInbox() {
    }

    public static PanelSnapshot snapshot() {
        return snapshot;
    }

    public static String lastErrorCode() {
        return lastErrorCode;
    }

    public static long generation() {
        return generation;
    }

    public static boolean signatureValid() {
        return signatureValid;
    }

    public static MineAgentPayloads.ModKnowledgeState modKnowledgeState() {
        return modKnowledgeState;
    }

    public static MineAgentPayloads.BackupState backupState() {
        return backupState;
    }

    public static MineAgentPayloads.MediaState mediaState() {
        return mediaState;
    }

    public static MineAgentPayloads.AppearanceState appearanceState() {
        return appearanceState;
    }

    public static MineAgentPayloads.PackageState packageState() {
        return packageState;
    }

    public static MineAgentPayloads.DiagnosticsState diagnosticsState() {
        return diagnosticsState;
    }

    public static MineAgentPayloads.PermissionState permissionState() {
        return permissionState;
    }

    public static MineAgentPayloads.PromptResult promptResult() {
        return promptResult;
    }

    public static MineAgentPayloads.DecisionState decisionState() {
        return decisionState;
    }

    public static MineAgentPayloads.ConversationState conversationState() {
        return conversationState;
    }

    public static long conversationStreamGeneration() {
        return conversationStreamGeneration;
    }

    public static boolean conversationStreaming(String conversationId) {
        try {
            return CONVERSATION_STREAMS.streaming(java.util.UUID.fromString(conversationId));
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    public static String conversationStreamText(String conversationId) {
        try {
            return CONVERSATION_STREAMS.text(java.util.UUID.fromString(conversationId));
        } catch (IllegalArgumentException invalid) {
            return "";
        }
    }

    public static String conversationStreamError() {
        return conversationStreamError;
    }

    public static MineAgentPayloads.AgentCommandResult agentCommandResult() {
        return agentCommandResult;
    }

    public static MineAgentPayloads.TaskState taskState() {
        return taskState;
    }

    public static MineAgentPayloads.CodeState codeState() {
        return codeState;
    }

    public static MineAgentPayloads.MemoryState memoryState() {
        return memoryState;
    }

    static void accept(MineAgentPayloads.PanelSnapshot payload) {
        snapshot = new PanelSnapshot(payload.revision(), payload.values());
        lastErrorCode = "";
        signatureValid = verifySnapshot(payload);
        generation++;
    }

    static void accept(MineAgentPayloads.ConfigPatchResult payload) {
        snapshot = new PanelSnapshot(payload.revision(), payload.values());
        lastErrorCode = payload.errorCode();
        generation++;
    }

    static void accept(MineAgentPayloads.PromptResult payload) {
        promptResult = payload;
    }

    static void accept(MineAgentPayloads.DecisionState payload) {
        decisionState = payload;
    }

    static void accept(MineAgentPayloads.ConversationState payload) {
        conversationState = payload;
    }

    static void accept(MineAgentPayloads.ConversationStream payload) {
        try {
            java.util.UUID conversationId = java.util.UUID.fromString(payload.conversationId());
            switch (payload.phase()) {
                case "START" -> {
                    CONVERSATION_STREAMS.begin(conversationId);
                    conversationStreamError = "";
                }
                case "DELTA" -> {
                    if (!CONVERSATION_STREAMS.streaming(conversationId) && payload.sequence() == 0) {
                        CONVERSATION_STREAMS.begin(conversationId);
                    }
                    if (!CONVERSATION_STREAMS.append(conversationId, payload.sequence(), payload.delta())) {
                        conversationStreamError = "STREAM_SEQUENCE_INVALID";
                    }
                }
                case "END" -> CONVERSATION_STREAMS.complete(conversationId);
                case "ERROR" -> {
                    CONVERSATION_STREAMS.complete(conversationId);
                    conversationStreamError = payload.errorCode();
                }
                default -> conversationStreamError = "STREAM_PHASE_INVALID";
            }
        } catch (IllegalArgumentException invalid) {
            conversationStreamError = "STREAM_PAYLOAD_INVALID";
        }
        conversationStreamGeneration++;
    }

    static void accept(MineAgentPayloads.AgentCommandResult payload) {
        agentCommandResult = payload;
    }

    static void accept(MineAgentPayloads.TaskState payload) {
        taskState = payload;
    }

    static void accept(MineAgentPayloads.CodeState payload) {
        codeState = payload;
    }

    static void accept(MineAgentPayloads.MemoryState payload) {
        memoryState = payload;
    }

    static void accept(MineAgentPayloads.ModKnowledgeState payload) {
        modKnowledgeState = payload;
    }

    static void accept(MineAgentPayloads.BackupState payload) {
        backupState = payload;
    }

    static void accept(MineAgentPayloads.MediaState payload) {
        mediaState = payload;
    }

    static void accept(MineAgentPayloads.AppearanceState payload) {
        appearanceState = payload;
    }

    static void accept(MineAgentPayloads.PackageState payload) {
        packageState = payload;
    }

    static void accept(MineAgentPayloads.PermissionState payload) {
        permissionState = payload;
    }

    static void accept(MineAgentPayloads.DiagnosticsState payload) {
        diagnosticsState = payload;
    }

    private static boolean verifySnapshot(MineAgentPayloads.PanelSnapshot payload) {
        try {
            String encodedPublic = payload.values().get("security.identityPublicKey");
            String encodedSignature = payload.values().get(
                    dev.mineagent.runtime.core.crypto.SnapshotSignature.SIGNATURE_KEY);
            String fingerprint = payload.values().get("security.identityFingerprint");
            if (encodedPublic == null || encodedSignature == null || fingerprint == null) {
                return false;
            }
            byte[] publicKey = java.util.Base64.getDecoder().decode(encodedPublic);
            String calculated = java.util.HexFormat.of().withUpperCase().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(publicKey));
            return calculated.equals(fingerprint)
                    && dev.mineagent.runtime.core.crypto.IdentitySigner.verify(
                    publicKey,
                    dev.mineagent.runtime.core.crypto.SnapshotSignature.canonicalBytes(
                            payload.revision(), payload.values()),
                    java.util.Base64.getDecoder().decode(encodedSignature));
        } catch (Exception invalid) {
            return false;
        }
    }
}
