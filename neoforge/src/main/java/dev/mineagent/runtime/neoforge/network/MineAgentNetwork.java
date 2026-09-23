package dev.mineagent.runtime.neoforge.network;

import dev.mineagent.runtime.api.config.ConfigPatch;
import dev.mineagent.runtime.core.crypto.SecretChannel;
import dev.mineagent.runtime.core.crypto.SecretEnvelope;
import dev.mineagent.runtime.neoforge.MineAgentRuntimeMod;
import dev.mineagent.runtime.neoforge.MineAgentRuntimeServices;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import dev.mineagent.runtime.api.permission.PermissionAction;
import dev.mineagent.runtime.api.decision.AnswerSource;
import dev.mineagent.runtime.api.decision.DecisionAnswerSubmission;
import dev.mineagent.runtime.api.decision.DecisionRequest;
import dev.mineagent.runtime.api.decision.DecisionStatus;



import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.HandlerThread;

public final class MineAgentNetwork {
    private static final int MEDIA_CHUNK_BYTES = 24 * 1024;
    private static final java.util.Map<java.util.UUID, String> PENDING_MEDIA_BINDINGS =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<net.minecraft.server.MinecraftServer,
            dev.mineagent.runtime.integrations.ysm.AppearanceRequestLedger> APPEARANCE_REQUESTS =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
    private static final java.util.Map<net.minecraft.server.MinecraftServer,
            dev.mineagent.runtime.integrations.ysm.AppearanceTransactionCoordinator> APPEARANCE_TRANSACTIONS =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
    private static final java.util.Map<net.minecraft.server.MinecraftServer,
            dev.mineagent.runtime.integrations.ysm.AppearanceDecisionRegistry<AppearanceDecisionContext>>
            APPEARANCE_DECISIONS =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    private MineAgentNetwork() {
    }

    public static void openMediaPanel(ServerPlayer player, net.minecraft.core.BlockPos position) {
        String binding = new dev.mineagent.runtime.api.media.MediaScreenBinding(
                player.level().dimension().identifier().toString(), position.getX(), position.getY(), position.getZ())
                .encoded();
        PENDING_MEDIA_BINDINGS.put(player.getUUID(), binding);
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(
                player, new MineAgentPayloads.OpenPanel("MEDIA"));
    }

    public static void serverWork(net.neoforged.neoforge.network.handling.IPayloadContext context,Runnable action){
        context.enqueueWork(()->{if(context.player() instanceof ServerPlayer player&&dev.mineagent.runtime.neoforge.WorldIdentityRuntime.notifyIfPending(player))action.run();});
    }
    public static void register(RegisterPayloadHandlersEvent event) {
        SpeechInputPayloads.register(event);
        ObjectAssetPayloads.register(event);
        dev.mineagent.runtime.neoforge.ui.ServerUiRuntime.register(event);
        var registrar = event.registrar("5").versioned("5").executesOn(HandlerThread.NETWORK);
        registrar.playToServer(ProviderModelsPayloads.Request.TYPE,ProviderModelsPayloads.Request.CODEC,(p,c)->serverWork(c,()->ProviderModelsPayloads.respond(p,(ServerPlayer)c.player(),c::reply)));
        registrar.playToClient(ProviderModelsPayloads.Response.TYPE,ProviderModelsPayloads.Response.CODEC,(p,c)->c.enqueueWork(()->dev.mineagent.runtime.neoforge.client.ProviderModelsClient.accept(p)));
        registrar.playToServer(MineAgentPayloads.SecretConfigWrite.TYPE,MineAgentPayloads.SecretConfigWrite.CODEC,(p,c)->serverWork(c,()->c.reply(applyNativeSecret(p,(ServerPlayer)c.player()))));
        registrar.playToClient(MineAgentPayloads.SecretConfigResult.TYPE,MineAgentPayloads.SecretConfigResult.CODEC,(p,c)->c.enqueueWork(()->dev.mineagent.runtime.neoforge.client.screen.NativeSecretScreen.accept(p)));
        registrar.playToServer(
                MineAgentPayloads.PanelRequest.TYPE,
                MineAgentPayloads.PanelRequest.CODEC,
                (payload, context) -> serverWork(context,() -> {if(payload.agentOffset()>=0)AGENT_PAGES.put((ServerPlayer)context.player(),payload.agentOffset());sendSnapshot((ServerPlayer) context.player(), context);})
        );
        registrar.playToServer(
                MineAgentPayloads.ConfigPatch.TYPE,
                MineAgentPayloads.ConfigPatch.CODEC,
                (payload, context) -> serverWork(context,() -> applyPatch(payload, (ServerPlayer) context.player(), context))
        );
        registrar.playToClient(
                MineAgentPayloads.PanelSnapshot.TYPE,
                MineAgentPayloads.PanelSnapshot.CODEC,
                (payload, context) -> context.enqueueWork(() -> {
                    PanelSnapshotInbox.accept(payload);
                    dev.mineagent.runtime.neoforge.client.MineAgentClientTrustPrompt.onSnapshot(payload);
                })
        );
        registrar.playToServer(
                MineAgentPayloads.PromptRequest.TYPE,
                MineAgentPayloads.PromptRequest.CODEC,
                (payload, context) -> serverWork(context,() -> submitPrompt(payload, (ServerPlayer) context.player()))
        );
        registrar.playToClient(
                MineAgentPayloads.PromptResult.TYPE,
                MineAgentPayloads.PromptResult.CODEC,
                (payload, context) -> context.enqueueWork(() -> PanelSnapshotInbox.accept(payload))
        );
        registrar.playToClient(
                MineAgentPayloads.ConfigPatchResult.TYPE,
                MineAgentPayloads.ConfigPatchResult.CODEC,
                (payload, context) -> context.enqueueWork(() -> PanelSnapshotInbox.accept(payload))
        );
        registrar.playToServer(
                MineAgentPayloads.DecisionRequestPayload.TYPE,
                MineAgentPayloads.DecisionRequestPayload.CODEC,
                (payload, context) -> serverWork(context,() -> sendPendingDecision((ServerPlayer) context.player()))
        );
        registrar.playToServer(
                MineAgentPayloads.DecisionCommand.TYPE,
                MineAgentPayloads.DecisionCommand.CODEC,
                (payload, context) -> serverWork(context,() -> applyDecisionCommand(payload, (ServerPlayer) context.player()))
        );
        registrar.playToClient(
                MineAgentPayloads.DecisionState.TYPE,
                MineAgentPayloads.DecisionState.CODEC,
                (payload, context) -> context.enqueueWork(() -> PanelSnapshotInbox.accept(payload))
        );
        registrar.playToServer(
                MineAgentPayloads.ConversationRequest.TYPE,
                MineAgentPayloads.ConversationRequest.CODEC,
                (payload, context) -> serverWork(context,() -> sendConversation(payload, (ServerPlayer) context.player()))
        );
        registrar.playToServer(
                MineAgentPayloads.ConversationSend.TYPE,
                MineAgentPayloads.ConversationSend.CODEC,
                (payload, context) -> serverWork(context,() -> submitConversation(payload, (ServerPlayer) context.player()))
        );
        registrar.playToServer(MineAgentPayloads.ConversationSendV2.TYPE,MineAgentPayloads.ConversationSendV2.CODEC,(payload,context)->serverWork(context,()->submitConversationV2(payload,(ServerPlayer)context.player())));
        registrar.playToClient(MineAgentPayloads.ConversationReceipt.TYPE,MineAgentPayloads.ConversationReceipt.CODEC,(payload,context)->context.enqueueWork(()->PanelSnapshotInbox.accept(payload)));
        registrar.playToClient(MineAgentPayloads.ConversationVoiceChunk.TYPE,MineAgentPayloads.ConversationVoiceChunk.CODEC,(payload,context)->{var source=context.connection();context.enqueueWork(()->dev.mineagent.runtime.neoforge.client.audio.ConversationVoicePlayback.accept(payload,source));});
        registrar.playToClient(
                MineAgentPayloads.ConversationState.TYPE,
                MineAgentPayloads.ConversationState.CODEC,
                (payload, context) -> context.enqueueWork(() -> PanelSnapshotInbox.accept(payload))
        );
        registrar.playToClient(
                MineAgentPayloads.ConversationStream.TYPE,
                MineAgentPayloads.ConversationStream.CODEC,
                (payload, context) -> context.enqueueWork(() -> PanelSnapshotInbox.accept(payload))
        );
        registrar.playToClient(
                MineAgentPayloads.VoiceChunk.TYPE,
                MineAgentPayloads.VoiceChunk.CODEC,
                (payload, context) -> context.enqueueWork(() ->
                        dev.mineagent.runtime.neoforge.client.audio.MineAgentVoicePlayback.accept(payload))
        );
        registrar.playToServer(
                MineAgentPayloads.AgentCommand.TYPE,
                MineAgentPayloads.AgentCommand.CODEC,
                (payload, context) -> serverWork(context,() -> applyAgentCommand(payload, (ServerPlayer) context.player()))
        );
        registrar.playToClient(
                MineAgentPayloads.AgentCommandResult.TYPE,
                MineAgentPayloads.AgentCommandResult.CODEC,
                (payload, context) -> context.enqueueWork(() -> PanelSnapshotInbox.accept(payload))
        );
        registrar.playToClient(
                MineAgentPayloads.BasketballScore.TYPE,
                MineAgentPayloads.BasketballScore.CODEC,
                (payload, context) -> context.enqueueWork(() ->
                        dev.mineagent.runtime.neoforge.client.basketball.MineAgentBasketballHud.accept(payload))
        );
        registrar.playToServer(
                MineAgentPayloads.TaskCommand.TYPE,
                MineAgentPayloads.TaskCommand.CODEC,
                (payload, context) -> serverWork(context,() -> applyTaskCommand(payload, (ServerPlayer) context.player()))
        );
        registrar.playToClient(
                MineAgentPayloads.TaskState.TYPE,
                MineAgentPayloads.TaskState.CODEC,
                (payload, context) -> context.enqueueWork(() -> PanelSnapshotInbox.accept(payload))
        );
        registrar.playToServer(
                MineAgentPayloads.CodeCommand.TYPE,
                MineAgentPayloads.CodeCommand.CODEC,
                (payload, context) -> serverWork(context,() -> applyCodeCommand(payload, (ServerPlayer) context.player()))
        );
        registrar.playToClient(
                MineAgentPayloads.CodeState.TYPE,
                MineAgentPayloads.CodeState.CODEC,
                (payload, context) -> context.enqueueWork(() -> PanelSnapshotInbox.accept(payload))
        );
        registrar.playToServer(MineAgentPayloads.AgentNamesRequest.TYPE,MineAgentPayloads.AgentNamesRequest.CODEC,(payload,context)->serverWork(context,()->{
            var player=(ServerPlayer)context.player();var server=player.level().getServer();var names=MineAgentRuntimeServices.permissions(server).allowed(player.getUUID(),player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER),PermissionAction.CHAT)?MineAgentRuntimeServices.bodies(server).definitions().stream().map(dev.mineagent.runtime.api.agent.AgentDefinition::displayName).sorted().toList():java.util.List.<String>of();
            for(int start=0;start<Math.max(1,names.size());start+=64)context.reply(new MineAgentPayloads.AgentNames(payload.request(),MineAgentRuntimeServices.worldId(server),names.subList(start,Math.min(start+64,names.size())),start/64,start+64>=names.size()));
        }));
        registrar.playToClient(MineAgentPayloads.AgentNames.TYPE,MineAgentPayloads.AgentNames.CODEC,(payload,context)->{var connection=context.connection();context.enqueueWork(()->dev.mineagent.runtime.neoforge.client.chat.NativeAgentChat.accept(payload,connection));});
        registrar.playToClient(AgentPngSkinPayload.TYPE,AgentPngSkinPayload.CODEC,(payload,context)->{var wire=context.connection();context.enqueueWork(()->dev.mineagent.runtime.neoforge.client.AgentPngSkinClient.accept(payload,wire));});
        registrar.playToClient(AgentSkinPayload.TYPE,AgentSkinPayload.CODEC,(payload,context)->{var wire=context.connection();context.enqueueWork(()->dev.mineagent.runtime.neoforge.client.AgentSkinClient.accept(payload,wire));});
        registrar.playToServer(AutonomyPayloads.Input.TYPE,AutonomyPayloads.Input.CODEC,(payload,context)->serverWork(context,()->dev.mineagent.runtime.neoforge.task.AutonomousPlayerAgent.input((ServerPlayer)context.player(),payload)));
        registrar.playToClient(AutonomyPayloads.Offer.TYPE,AutonomyPayloads.Offer.CODEC,(payload,context)->{var wire=context.connection();context.enqueueWork(()->dev.mineagent.runtime.neoforge.client.body.AutonomousBodyClient.offer(payload,wire));});
        registrar.playToClient(AutonomyPayloads.Frame.TYPE,AutonomyPayloads.Frame.CODEC,(payload,context)->{var wire=context.connection();context.enqueueWork(()->dev.mineagent.runtime.neoforge.client.body.AutonomousBodyClient.frame(payload,wire));});
        registrar.playToServer(PlayerBodyPayloads.Decision.TYPE,PlayerBodyPayloads.Decision.CODEC,(payload,context)->serverWork(context,()->dev.mineagent.runtime.neoforge.task.PlayerBodyAgent.decide((ServerPlayer)context.player(),payload)));
        registrar.playToClient(PlayerBodyPayloads.Offer.TYPE,PlayerBodyPayloads.Offer.CODEC,(payload,context)->{var wire=context.connection();context.enqueueWork(()->dev.mineagent.runtime.neoforge.client.body.PlayerBodyControlClient.offer(payload,wire));});
        registrar.playToClient(PlayerBodyPayloads.Signal.TYPE,PlayerBodyPayloads.Signal.CODEC,(payload,context)->{var wire=context.connection();context.enqueueWork(()->{dev.mineagent.runtime.neoforge.client.body.PlayerBodySmokeClient.signal(payload,wire);dev.mineagent.runtime.neoforge.client.body.PlayerBodyControlClient.signal(payload,wire);});});
        registrar.playToServer(
                MineAgentPayloads.MemoryCommand.TYPE,
                MineAgentPayloads.MemoryCommand.CODEC,
                (payload, context) -> serverWork(context,() -> applyMemoryCommand(payload, (ServerPlayer) context.player()))
        );
        registrar.playToClient(
                MineAgentPayloads.MemoryState.TYPE,
                MineAgentPayloads.MemoryState.CODEC,
                (payload, context) -> context.enqueueWork(() -> PanelSnapshotInbox.accept(payload))
        );
        registrar.playToClient(
                MineAgentPayloads.OpenPanel.TYPE,
                MineAgentPayloads.OpenPanel.CODEC,
                (payload, context) -> context.enqueueWork(() ->
                        dev.mineagent.runtime.neoforge.client.MineAgentClientNavigation.open(payload))
        );
        registrar.playToServer(
                MineAgentPayloads.ModKnowledgeRequest.TYPE,
                MineAgentPayloads.ModKnowledgeRequest.CODEC,
                (payload, context) -> serverWork(context,() -> indexMods((ServerPlayer) context.player()))
        );
        registrar.playToClient(
                MineAgentPayloads.ModKnowledgeState.TYPE,
                MineAgentPayloads.ModKnowledgeState.CODEC,
                (payload, context) -> context.enqueueWork(() -> PanelSnapshotInbox.accept(payload))
        );
        registrar.playToServer(
                MineAgentPayloads.BackupCommand.TYPE,
                MineAgentPayloads.BackupCommand.CODEC,
                (payload, context) -> serverWork(context,() -> applyBackupCommand(payload, (ServerPlayer) context.player()))
        );
        registrar.playToClient(
                MineAgentPayloads.BackupState.TYPE,
                MineAgentPayloads.BackupState.CODEC,
                (payload, context) -> context.enqueueWork(() -> PanelSnapshotInbox.accept(payload))
        );
        registrar.playToServer(
                MineAgentPayloads.MediaCommand.TYPE,
                MineAgentPayloads.MediaCommand.CODEC,
                (payload, context) -> serverWork(context,() -> applyMediaCommand(payload, (ServerPlayer) context.player()))
        );
        registrar.playToClient(
                MineAgentPayloads.MediaState.TYPE,
                MineAgentPayloads.MediaState.CODEC,
                (payload, context) -> context.enqueueWork(() -> {
                    PanelSnapshotInbox.accept(payload);
                    dev.mineagent.runtime.neoforge.client.media.MineAgentMediaPlayback.acceptState(payload);
                })
        );
        registrar.playToClient(
                MineAgentPayloads.MediaFrameChunk.TYPE,
                MineAgentPayloads.MediaFrameChunk.CODEC,
                (payload, context) -> context.enqueueWork(() ->
                        dev.mineagent.runtime.neoforge.client.media.MineAgentMediaPlayback.acceptFrame(payload))
        );
        registrar.playToClient(
                MineAgentPayloads.MediaAudioChunk.TYPE,
                MineAgentPayloads.MediaAudioChunk.CODEC,
                (payload, context) -> context.enqueueWork(() ->
                        dev.mineagent.runtime.neoforge.client.audio.MineAgentVoicePlayback.acceptMedia(payload))
        );
        registrar.playToServer(
                MineAgentPayloads.AppearanceCommand.TYPE,
                MineAgentPayloads.AppearanceCommand.CODEC,
                (payload, context) -> serverWork(context,() -> applyAppearance(payload, (ServerPlayer) context.player()))
        );
        registrar.playToClient(
                MineAgentPayloads.AppearanceState.TYPE,
                MineAgentPayloads.AppearanceState.CODEC,
                (payload, context) -> context.enqueueWork(() -> PanelSnapshotInbox.accept(payload))
        );
        registrar.playToServer(
                MineAgentPayloads.PackageCommand.TYPE,
                MineAgentPayloads.PackageCommand.CODEC,
                (payload, context) -> serverWork(context,() -> applyAgentPackageCommand(payload, (ServerPlayer) context.player()))
        );
        registrar.playToClient(
                MineAgentPayloads.PackageState.TYPE,
                MineAgentPayloads.PackageState.CODEC,
                (payload, context) -> context.enqueueWork(() -> {
                    PanelSnapshotInbox.accept(payload);
                    dev.mineagent.runtime.neoforge.client.MineAgentClientPackages.accept(payload);
                })
        );
        registrar.playToServer(
                MineAgentPayloads.PermissionCommand.TYPE,
                MineAgentPayloads.PermissionCommand.CODEC,
                (payload, context) -> serverWork(context,() -> applyPermissionCommand(payload, (ServerPlayer) context.player()))
        );
        registrar.playToClient(
                MineAgentPayloads.PermissionState.TYPE,
                MineAgentPayloads.PermissionState.CODEC,
                (payload, context) -> context.enqueueWork(() -> PanelSnapshotInbox.accept(payload))
        );
        registrar.playToServer(
                MineAgentPayloads.DiagnosticsRequest.TYPE,
                MineAgentPayloads.DiagnosticsRequest.CODEC,
                (payload, context) -> serverWork(context,() -> sendDiagnostics((ServerPlayer) context.player()))
        );
        registrar.playToClient(
                MineAgentPayloads.DiagnosticsState.TYPE,
                MineAgentPayloads.DiagnosticsState.CODEC,
                (payload, context) -> context.enqueueWork(() -> PanelSnapshotInbox.accept(payload))
        );
    }

    private static void sendSnapshot(
            ServerPlayer player,
            net.neoforged.neoforge.network.handling.IPayloadContext context
    ) {
        context.reply(panelSnapshot(player));
    }

    private static final java.util.Map<ServerPlayer,Integer> AGENT_PAGES=new java.util.WeakHashMap<>();
    private static MineAgentPayloads.PanelSnapshot panelSnapshot(ServerPlayer player) {
        var snapshot = MineAgentRuntimeServices.config(player.level().getServer()).snapshot();
        var values = new java.util.LinkedHashMap<>(snapshot.values());
        boolean operator = player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
        if (!operator) {
            values.keySet().removeIf(key -> key.startsWith("permission.player."));
        }
        values.put("security.secretTransportPublicKey", java.util.Base64.getEncoder()
                .encodeToString(MineAgentRuntimeMod.SECRET_TRANSPORT_KEYS.getPublic().getEncoded()));
        var server = player.level().getServer();
        values.put("security.configInstance",MineAgentRuntimeServices.config(server).instanceId().toString());values.put("security.worldId",MineAgentRuntimeServices.worldId(server).toString());
        var allAgents = MineAgentRuntimeServices.bodies(server).definitions();
        int offset=Math.min(AGENT_PAGES.getOrDefault(player,0),Math.max(0,((allAgents.size()-1)/8)*8));
        var agents = allAgents.stream().skip(offset).limit(8).toList();
        var agentPrefixes=agents.stream().map(a->"agent."+a.agentId()+".").toList();values.keySet().removeIf(k->k.startsWith("agent.")&&agentPrefixes.stream().noneMatch(k::startsWith));
        values.put("agent.offset",Integer.toString(offset));
        values.put("agent.total",Integer.toString(allAgents.size()));
        values.put("agent.count", Integer.toString(agents.size()));
        for (int index = 0; index < agents.size(); index++) {
            var agent = agents.get(index);
            values.put("agent." + index + ".id", agent.agentId().toString());
            values.put("agent." + index + ".name", agent.displayName());
            values.put("agent." + index + ".mode", agent.mode().name());
            values.put("agent." + index + ".owner", agent.ownerPlayerId().toString());
            values.put("agent." + index + ".revision", Long.toString(
                    MineAgentRuntimeServices.bodies(server).revision(agent.agentId())));
            values.put("agent." + index + ".collaborators", agent.collaboratorPlayerIds().stream()
                    .map(java.util.UUID::toString).sorted().collect(java.util.stream.Collectors.joining(",")));
            values.put("agent." + index + ".online",
                    Boolean.toString(MineAgentRuntimeServices.bodies(server).body(agent.agentId()).isPresent()));
            values.put("agent." + index + ".mutable", Boolean.toString(
                    operatorOrOwner(player, agent)));
        }
        for (PermissionAction action : PermissionAction.values()) {
            values.put("permission." + action.name().toLowerCase(java.util.Locale.ROOT), Boolean.toString(
                    MineAgentRuntimeServices.permissions(server).allowed(player.getUUID(), operator, action)));
        }
        values.put("runtime.workerAlive", Boolean.toString(MineAgentRuntimeServices.worker(server).isAlive()));
        values.put("runtime.taskCount", Long.toString(MineAgentRuntimeServices.tasks(server).totalCount()));
        values.put("runtime.memoryCount", Integer.toString(
                MineAgentRuntimeServices.memories(server).visibleTo(player.getUUID(), operator).size()));
        values.put("runtime.codeDraftCount", Integer.toString(
                MineAgentRuntimeServices.codeDrafts(server).allFor(player.getUUID(), operator).size()));
        values.put("runtime.packageCount", Integer.toString(
                MineAgentRuntimeServices.contentPackages(server).all().size()));
        values.put("runtime.mediaCount", Integer.toString(MineAgentRuntimeServices.media(server).all().size()));
        var ysm = new dev.mineagent.runtime.neoforge.integration.NeoForgeYsmRuntimeBridge(server);
        values.put("ysm.installed", Boolean.toString(ysm.installed()));
        values.put("ysm.version", ysm.version());
        values.put("ysm.runtimeAvailable", Boolean.toString(ysm.runtimeAvailable()));
        values.put("ysm.checksumVerified", Boolean.toString(ysm.checksumVerified()));
        values.put("ysm.modelChoices", String.join(",", ysm.availableModels()));
        values.put("ysm.textureChoices", "default,blue");
        values.put("ysm.animationChoices", "idle,gui,stop");
        values.put("ysm.diagnostic", ysm.diagnosticCode());
        values.put("ysm.unloadRequiresRestart", "true");
        values.put("ysm.activationMode", "BOOT_EXTENSION");
        var identity = MineAgentRuntimeServices.identity(server);
        values.put("security.identityFingerprint", identity.fingerprint());
        values.put("security.identityPublicKey", java.util.Base64.getEncoder()
                .encodeToString(identity.publicKeyEncoded()));
        try {
            values.put(dev.mineagent.runtime.core.crypto.SnapshotSignature.SIGNATURE_KEY,
                    java.util.Base64.getEncoder().encodeToString(identity.sign(
                            dev.mineagent.runtime.core.crypto.SnapshotSignature.canonicalBytes(
                                    snapshot.revision(), values))));
        } catch (java.security.GeneralSecurityException failure) {
            throw new IllegalStateException("cannot sign panel snapshot", failure);
        }
        return new MineAgentPayloads.PanelSnapshot(snapshot.revision(), values);
    }

    public static void sendPanelSnapshot(ServerPlayer player) {
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player, panelSnapshot(player));
    }

    private static void applyPatch(
            MineAgentPayloads.ConfigPatch payload,
            ServerPlayer player,
            net.neoforged.neoforge.network.handling.IPayloadContext context
    ) {
        boolean operator = player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
        var values = new java.util.LinkedHashMap<>(payload.values());
        if (!mayApplyConfigKeys(player, operator, values.keySet())) {
            var snapshot = MineAgentRuntimeServices.config(player.level().getServer()).snapshot();
            context.reply(new MineAgentPayloads.ConfigPatchResult(
                    false, "FORBIDDEN", snapshot.revision(), snapshot.values(), java.util.Map.of()));
            return;
        }
        try {
            decryptApiKey(values);
        } catch (java.security.GeneralSecurityException | IllegalArgumentException failure) {
            var snapshot = MineAgentRuntimeServices.config(player.level().getServer()).snapshot();
            context.reply(new MineAgentPayloads.ConfigPatchResult(
                    false, "SECRET_DECRYPTION_FAILED", snapshot.revision(), snapshot.values(),
                    java.util.Map.of("provider.openai.apiKey", "密钥传输解密失败")
            ));
            return;
        }
        var result = MineAgentRuntimeServices.config(player.level().getServer())
                .apply(new ConfigPatch(payload.expectedRevision(), values), true);
        if (result.accepted()) { MineAgentRuntimeServices.bodies(player.level().getServer()).refreshResourceLimits(); dev.mineagent.runtime.neoforge.ui.ServerProviderModels.changed(player,values.keySet()); }
        context.reply(new MineAgentPayloads.ConfigPatchResult(
                result.accepted(),
                result.errorCode(),
                result.snapshot().revision(),
                result.snapshot().values(),
                result.fieldErrors()
        ));
        if (result.accepted()) {
            audit(player.level().getServer(), player.getUUID().toString(), "CONFIG_PATCH", "server",
                    "keys=" + values.keySet());
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player, panelSnapshot(player));
        }
    }
    private static MineAgentPayloads.SecretConfigResult applyNativeSecret(MineAgentPayloads.SecretConfigWrite request,ServerPlayer viewer){
        var server=viewer.level().getServer();var config=MineAgentRuntimeServices.config(server);var revision=config.snapshot().revision();
        if(viewer instanceof dev.mineagent.runtime.neoforge.body.MineAgentPlayer||!MineAgentRuntimeServices.permissions(server).allowed(viewer.getUUID(),viewer.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER),PermissionAction.MANAGE_PROVIDERS))return new MineAgentPayloads.SecretConfigResult(request.operation(),false,"FORBIDDEN",revision,false);
        boolean configured=config.secretValue("provider.openai.apiKey").filter(v->!v.isBlank()).isPresent();
        if(!request.world().equals(MineAgentRuntimeServices.worldId(server))||!request.instance().equals(config.instanceId()))return new MineAgentPayloads.SecretConfigResult(request.operation(),false,"SECRET_CONTEXT_CHANGED",revision,configured);
        if(request.revision()!=revision)return new MineAgentPayloads.SecretConfigResult(request.operation(),false,"STALE_REVISION",revision,configured);
        try{
            if(!request.envelope().keySet().equals(java.util.Set.of("ephemeral","nonce","ciphertext"))||request.envelope().values().stream().anyMatch(v->v==null||v.length()>16384))throw new IllegalArgumentException();
            var decoder=java.util.Base64.getDecoder();var clear=SecretChannel.open(MineAgentRuntimeMod.SECRET_TRANSPORT_KEYS.getPrivate(),new SecretEnvelope(decoder.decode(request.envelope().get("ephemeral")),decoder.decode(request.envelope().get("nonce")),decoder.decode(request.envelope().get("ciphertext"))));
            var input=dev.mineagent.runtime.core.config.NativeSecretPayload.decode(clear,request.operation(),request.world(),request.instance(),request.revision());
            var result=config.apply(new ConfigPatch(request.revision(),java.util.Map.of(input.targetKey(),input.value())),true);
            if(result.accepted()){audit(server,viewer.getUUID().toString(),"CONFIG_SECRET_PATCH","server",input.targetKey());dev.mineagent.runtime.neoforge.ui.ServerProviderModels.changed(viewer,java.util.Set.of(input.targetKey()));sendPanelSnapshot(viewer);}
            return new MineAgentPayloads.SecretConfigResult(request.operation(),result.accepted(),result.errorCode(),result.snapshot().revision(),config.secretValue(input.targetKey()).filter(v->!v.isBlank()).isPresent());
        }catch(Exception invalid){return new MineAgentPayloads.SecretConfigResult(request.operation(),false,"SECRET_ENVELOPE_REJECTED",config.snapshot().revision(),configured);}
    }

    private static void decryptApiKey(java.util.Map<String, String> values)
            throws java.security.GeneralSecurityException {
        String ephemeral = values.remove("provider.openai.apiKey.encrypted.ephemeral");
        String nonce = values.remove("provider.openai.apiKey.encrypted.nonce");
        String ciphertext = values.remove("provider.openai.apiKey.encrypted.ciphertext");
        if (ephemeral == null && nonce == null && ciphertext == null) {
            return;
        }
        if (ephemeral == null || nonce == null || ciphertext == null) {
            throw new IllegalArgumentException("incomplete encrypted secret");
        }
        var decoder = java.util.Base64.getDecoder();
        String cleartext = SecretChannel.open(
                MineAgentRuntimeMod.SECRET_TRANSPORT_KEYS.getPrivate(),
                new SecretEnvelope(decoder.decode(ephemeral), decoder.decode(nonce), decoder.decode(ciphertext))
        );
        values.put("provider.openai.apiKey", cleartext);
    }

    private static boolean mayApplyConfigKeys(
            ServerPlayer player,
            boolean operator,
            java.util.Set<String> keys
    ) {
        if (keys.isEmpty()) {
            return false;
        }
        var server = player.level().getServer();
        var permissions = MineAgentRuntimeServices.permissions(server);
        for (String key : keys) {
            if (key.startsWith("provider.") || key.equals("voice.input.enabled")) {
                if (!permissions.allowed(player.getUUID(), operator, PermissionAction.MANAGE_PROVIDERS)) {
                    return false;
                }
            } else if (key.startsWith("agent.") && key.endsWith(".voice")) {
                String id = key.substring("agent.".length(), key.length() - ".voice".length());
                final java.util.UUID agentId;
                try {
                    agentId = java.util.UUID.fromString(id);
                } catch (IllegalArgumentException invalid) {
                    return false;
                }
                var definition = MineAgentRuntimeServices.bodies(server).definitions().stream()
                        .filter(agent -> agent.agentId().equals(agentId)).findFirst().orElse(null);
                if (definition == null || (!operator && !operatorOrOwner(player, definition))) {
                    return false;
                }
            } else if (key.startsWith("media.")) {
                if (!permissions.allowed(player.getUUID(), operator, PermissionAction.CONTROL_PUBLIC_MEDIA)) {
                    return false;
                }
            } else if (key.startsWith("package.")) {
                if (!permissions.allowed(player.getUUID(), operator, PermissionAction.MANAGE_PACKAGES)) {
                    return false;
                }
            } else if (!permissions.allowed(player.getUUID(), operator, PermissionAction.MANAGE_PERMISSIONS)) {
                return false;
            }
        }
        return true;
    }

    private static boolean operatorOrOwner(
            ServerPlayer player,
            dev.mineagent.runtime.api.agent.AgentDefinition agent
    ) {
        return player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER)
                || agent.ownerPlayerId().equals(player.getUUID())
                || agent.collaboratorPlayerIds().contains(player.getUUID());
    }

    private static void submitPrompt(MineAgentPayloads.PromptRequest payload, ServerPlayer player) {
        if (payload.prompt().isBlank() || payload.prompt().length() > 16_384) {
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player,
                    new MineAgentPayloads.PromptResult(false, "", "", "INVALID_PROMPT"));
            return;
        }
        var server = player.level().getServer();
        boolean operator = player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
        if (!MineAgentRuntimeServices.permissions(server)
                .allowed(player.getUUID(), operator, PermissionAction.RUN_CODE)) {
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player,
                    new MineAgentPayloads.PromptResult(false, "", "", "FORBIDDEN"));
            return;
        }
        if("CODING".equals(payload.capability())){sendPromptFailure(player,"STUDIO_NATIVE_CONFIRMATION_REQUIRED");return;}
        audit(server, player.getUUID().toString(), "PROMPT", payload.capability(), payload.prompt());
        if ("IMAGE".equals(payload.capability())) {
            MineAgentRuntimeServices.worker(server)
                    .generateImage(MineAgentRuntimeServices.config(server), payload.prompt(), "1024x1024")
                    .whenComplete((response, failure) -> server.execute(() -> {
                        if (failure != null || !"image.result".equals(response.type())) {
                            sendPromptFailure(player, failure == null
                                    ? String.valueOf(response.payload().getOrDefault("code", "IMAGE_GENERATION_FAILED"))
                                    : "WORKER_REQUEST_FAILED");
                        } else {
                            net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player,
                                    new MineAgentPayloads.PromptResult(true,
                                            String.valueOf(response.payload().getOrDefault("providerId", "")),
                                            "图像已保存，SHA-256: " + response.payload().getOrDefault("sha256", ""), ""));
                        }
                    }));
            return;
        }
        MineAgentRuntimeServices.worker(server)
                .complete(MineAgentRuntimeServices.config(server), payload.capability(), payload.prompt())
                .whenComplete((response, failure) -> server.execute(() -> {
                    if (failure != null) {
                        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player,
                                new MineAgentPayloads.PromptResult(false, "", "", "WORKER_REQUEST_FAILED"));
                    } else if ("model.result".equals(response.type())) {
                        audit(server, "worker", "RESPONSE", payload.capability(),
                                String.valueOf(response.payload().getOrDefault("text", "")));
                        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player,
                                new MineAgentPayloads.PromptResult(
                                        true,
                                        String.valueOf(response.payload().getOrDefault("providerId", "")),
                                        String.valueOf(response.payload().getOrDefault("text", "")),
                                        ""
                                ));
                    } else {
                        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player,
                                new MineAgentPayloads.PromptResult(false, "", "",
                                        String.valueOf(response.payload().getOrDefault("code", "MODEL_REQUEST_FAILED"))));
                    }
                }));
    }

    private static void sendPromptFailure(ServerPlayer player, String code) {
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player,
                new MineAgentPayloads.PromptResult(false, "", "", code));
    }

    private static void sendPendingDecision(ServerPlayer player) {
        var pending = MineAgentRuntimeServices.decisions(player.level().getServer()).pendingFor(player.getUUID());
        if (pending.isEmpty()) {
            sendDecisionState(player, "", java.util.Map.of("present", "false"));
            return;
        }
        DecisionRequest request = pending.stream()
                .filter(candidate -> candidate.status() == DecisionStatus.OPEN)
                .findFirst()
                .orElse(pending.getFirst());
        sendDecisionState(player, "", decisionValues(request));
    }

    private static void applyDecisionCommand(MineAgentPayloads.DecisionCommand payload, ServerPlayer player) {
        var server = player.level().getServer();
        var service = MineAgentRuntimeServices.decisions(server);
        try {
            if ("openAppearance".equals(payload.action())) {
                openAppearanceDecision(player, java.util.UUID.fromString(required(payload.values(), "agentId")));
                return;
            }
            java.util.UUID decisionId = java.util.UUID.fromString(required(payload.values(), "decisionId"));
            long expectedRevision = Long.parseLong(required(payload.values(), "expectedRevision"));
            DecisionRequest current = service.get(decisionId).orElse(null);
            if (current == null) {
                sendDecisionState(player, "NOT_FOUND", java.util.Map.of("present", "false"));
                return;
            }
            if (!current.recipientPlayerId().equals(player.getUUID())) {
                sendDecisionState(player, "FORBIDDEN", java.util.Map.of("present", "false"));
                return;
            }
            if(java.util.Set.of("submit","resume").contains(payload.action())){String denied=decisionMutationDenial(player,decisionId);if(!denied.isEmpty()){sendDecisionState(player,denied,decisionValues(current));return;}}
            if ("submit".equals(payload.action())) {
                int selectedCount = Integer.parseInt(payload.values().getOrDefault("selectedCount", "0"));
                if (selectedCount < 0 || selectedCount > 64) {
                    throw new IllegalArgumentException("invalid selectedCount");
                }
                var selected = new java.util.ArrayList<String>(selectedCount);
                for (int index = 0; index < selectedCount; index++) {
                    selected.add(required(payload.values(), "selected." + index));
                }
                var submission = new DecisionAnswerSubmission(
                        decisionId,
                        expectedRevision,
                        java.util.UUID.fromString(required(payload.values(), "submissionId")),
                        selected,
                        payload.values().getOrDefault("customText", ""),
                        AnswerSource.UI
                );
                var result = service.submitForTask(player.getUUID(), MineAgentRuntimeServices.tasks(server), submission);
                sendDecisionState(player, result.errorCode(), decisionValues(result.request()));
                if (result.accepted() && !result.duplicate()) {
                    applyAppearanceDecision(player, submission, result.request());
                }
                return;
            }
            DecisionStatus target = switch (payload.action()) {
                case "defer" -> DecisionStatus.DEFERRED;
                case "resume" -> DecisionStatus.OPEN;
                case "cancel" -> DecisionStatus.CANCELLED;
                case "expire" -> DecisionStatus.EXPIRED;
                case "supersede" -> DecisionStatus.SUPERSEDED;
                default -> throw new IllegalArgumentException("unsupported action");
            };
            boolean operator = player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
            var result = service.transition(decisionId, player.getUUID(), expectedRevision, target, operator);
            if (result.accepted()) {
                appearanceDecisions(server).transition(decisionId, result.request().status());
            }
            sendDecisionState(player, result.errorCode(), decisionValues(result.request()));
        } catch (IllegalArgumentException failure) {
            sendDecisionState(player, "INVALID_DECISION_COMMAND", java.util.Map.of("present", "false"));
        }
    }

    private static java.util.Map<String, String> decisionValues(DecisionRequest request) {
        var values = new java.util.LinkedHashMap<String, String>();
        values.put("present", "true");
        values.put("decisionId", request.decisionId().toString());
        values.put("revision", Long.toString(request.revision()));
        values.put("taskRevision", Long.toString(request.taskRevision()));
        values.put("kind", request.kind().name());
        values.put("title", request.title());
        values.put("question", request.question());
        values.put("selectionMode", request.selectionMode().name());
        values.put("minSelections", Integer.toString(request.minSelections()));
        values.put("maxSelections", Integer.toString(request.maxSelections()));
        values.put("allowCustomInput", Boolean.toString(request.allowCustomInput()));
        values.put("status", request.status().name());
        values.put("optionCount", Integer.toString(request.options().size()));
        for (int index = 0; index < request.options().size(); index++) {
            var option = request.options().get(index);
            values.put("option." + index + ".id", option.optionId());
            values.put("option." + index + ".title", option.title());
            values.put("option." + index + ".description", option.description());
        }
        return java.util.Map.copyOf(values);
    }

    private static String required(java.util.Map<String, String> values, String key) {
        String value = values.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing " + key);
        }
        return value;
    }

    private static void sendDecisionState(ServerPlayer player, String errorCode, java.util.Map<String, String> values) {
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(
                player, new MineAgentPayloads.DecisionState(errorCode, values));
    }

    // V1 agent-only packets remain decodable, but cannot silently select or create a conversation.
    private static void sendConversation(MineAgentPayloads.ConversationRequest payload, ServerPlayer player) {
        sendConversationEmpty(player,"CONVERSATION_ID_REQUIRED");
    }
    private static void submitConversation(MineAgentPayloads.ConversationSend payload, ServerPlayer player) {
        sendConversationEmpty(player,"CONVERSATION_ID_REQUIRED");
    }
    public static void submitConversationV2(MineAgentPayloads.ConversationSendV2 payload,ServerPlayer player){
        var server=player.level().getServer();String code="ACCEPTED";java.util.Map<String,String> values;
        try{
            if(!payload.worldId().equals(MineAgentRuntimeServices.worldId(server))||!MineAgentRuntimeServices.permissions(server).allowed(player.getUUID(),player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER),PermissionAction.CHAT))throw new SecurityException("CONVERSATION_FORBIDDEN");
            var args=java.util.Map.of("kind","send","agentId",payload.agentId().toString(),"conversationId",payload.conversationId().toString(),"expectedRevision",Long.toString(payload.expectedRevision()),"text",payload.text());
            var conversations=dev.mineagent.runtime.neoforge.ui.ServerConversations.get(server);conversations.authorize(player,args);values=conversations.write(player,payload.operationId(),args);
        }catch(SecurityException denied){code="PERMISSION_DENIED";values=java.util.Map.of("errorCode","CONVERSATION_NOT_OWNED");}
        catch(Exception failure){code="FAILED";String error=failure.getMessage();if(error==null||!error.matches("(?:CONVERSATION_[A-Z_]+|STALE_CONVERSATION_REVISION|OPERATION_ID_REUSED)"))error="CONVERSATION_REQUEST_FAILED";values=java.util.Map.of("errorCode",error);}
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player,new MineAgentPayloads.ConversationReceipt(payload.operationId(),code,values));
        if(!code.equals("ACCEPTED"))player.sendSystemMessage(net.minecraft.network.chat.Component.literal(values.getOrDefault("errorCode",code)+"；请在 WebGUI 明确选择会话。"));
    }
    public static void submitChat(ServerPlayer player,java.util.UUID agentId,String text){submitChat(player,agentId,java.util.UUID.randomUUID(),text);}
    public static void submitChat(ServerPlayer player,java.util.UUID agentId,java.util.UUID operation,String text){
        var server=player.level().getServer();var conversations=dev.mineagent.runtime.neoforge.ui.ServerConversations.get(server);
        var selected=conversations.focused(player).filter(f->f.nativeInput()&&f.agentId().equals(agentId));
        if(selected.isEmpty()){player.sendSystemMessage(net.minecraft.network.chat.Component.literal("CONVERSATION_SELECTION_REQUIRED：请在对话页选择此 AI 的会话并明确启用原版聊天输入。"));return;}
        try{conversations.submitNative(player,agentId,text,false,operation);}
        catch(Exception invalid){player.sendSystemMessage(net.minecraft.network.chat.Component.literal("CONVERSATION_REQUEST_FAILED：请重新选择会话。"));}
    }
    public static void submitUiConversation(ServerPlayer player,java.util.UUID agentId,String text){sendConversationEmpty(player,"CONVERSATION_ID_REQUIRED");}
    public static void refreshUiConversation(ServerPlayer player,java.util.UUID agentId){sendConversationEmpty(player,"CONVERSATION_ID_REQUIRED");}
    public static void applyUiDecisionEffects(ServerPlayer player,DecisionAnswerSubmission answer,DecisionRequest request){
        applyAppearanceDecision(player,answer,request);
        sendDecisionState(player,"",decisionValues(request));
    }
    public static java.util.Optional<java.util.UUID> decisionAgent(net.minecraft.server.MinecraftServer server,DecisionRequest request){
        var link=MineAgentRuntimeServices.decisions(server).taskLink(request.decisionId()).orElse(null);
        if(link!=null)return MineAgentRuntimeServices.tasks(server).get(link.taskId()).map(dev.mineagent.runtime.api.task.ManagedTask::agentId);
        var appearance=appearanceContext(server,request.decisionId());return appearance==null?java.util.Optional.empty():java.util.Optional.of(appearance.agentId());
    }
    public static String decisionMutationDenial(ServerPlayer viewer,java.util.UUID id){
        var server=viewer.level().getServer();var service=MineAgentRuntimeServices.decisions(server);var q=service.get(id).orElse(null);
        if(q==null||!q.recipientPlayerId().equals(viewer.getUUID()))return "DECISION_RECIPIENT";
        var link=service.taskLink(id).orElse(null);
        if(link!=null){var task=MineAgentRuntimeServices.tasks(server).get(link.taskId()).orElse(null);if(task==null||dev.mineagent.runtime.core.task.TaskAuthorityFence.revoked(task)||!MineAgentRuntimeServices.taskExecutor(server).authorized(task))return "DECISION_AUTHORITY_REVOKED";}
        else{var context=appearanceContext(server,id);if(context!=null&&!mayManageAppearance(viewer,context.agentId()))return "DECISION_AUTHORITY_REVOKED";}
        return "";
    }
    public static dev.mineagent.runtime.core.decision.DecisionChatRouter.Routed routeDecisionChat(ServerPlayer player,java.util.UUID agent,java.util.UUID decision,Long revision,java.util.UUID operation,String text){
        var server=player.level().getServer();var service=MineAgentRuntimeServices.decisions(server);
        return new dev.mineagent.runtime.core.decision.DecisionChatRouter(service,MineAgentRuntimeServices.tasks(server),(q,a)->decisionAgent(server,q).filter(a::equals).isPresent(),(q,v)->decisionMutationDenial(player,q.decisionId()).isEmpty()).route(player.getUUID(),agent,decision,revision,operation,text);
    }
    public static boolean submitChatDecision(ServerPlayer player,String text){return submitChatDecision(player,null,text);}
    public static boolean submitChatDecision(ServerPlayer player,java.util.UUID agent,String text) {
        if(!MineAgentRuntimeServices.permissions(player.level().getServer()).allowed(player.getUUID(),player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER),PermissionAction.CHAT))return false;
        var service = MineAgentRuntimeServices.decisions(player.level().getServer());
        String normalized = text.strip();
        if(agent!=null){var chosen=agent;var definition=MineAgentRuntimeServices.bodies(player.level().getServer()).definitions().stream().filter(a->a.agentId().equals(chosen)).findFirst().orElse(null);
            if(definition!=null)normalized=normalized.replaceFirst("^(?:@)?"+java.util.regex.Pattern.quote(definition.displayName())+"[ ，,：:]*","");}
        boolean ordinal=dev.mineagent.runtime.core.decision.DecisionChatRouter.ordinalAnswer(normalized);
        java.util.UUID explicit=null;Long revision=null;
        if(!ordinal){
            var chosen=agent;String answerText=normalized;
            var choices=service.pendingFor(player.getUUID()).stream().filter(q->{var c=appearanceContext(player.level().getServer(),q.decisionId());return c!=null&&(chosen==null||c.agentId().equals(chosen));})
                    .filter(q->q.options().stream().anyMatch(o->o.optionId().equalsIgnoreCase(answerText)||o.title().equalsIgnoreCase(answerText))||dev.mineagent.runtime.integrations.ysm.AppearanceIntentParser.parse(answerText).isPresent()).toList();
            if(choices.size()!=1)return false;var q=choices.getFirst();explicit=q.decisionId();revision=q.revision();agent=decisionAgent(player.level().getServer(),q).orElseThrow();
            for(int i=0;i<q.options().size();i++)if(q.options().get(i).optionId().equalsIgnoreCase(answerText)||q.options().get(i).title().equalsIgnoreCase(answerText))normalized="第"+(i+1)+"个";
        }
        if(agent==null){var agents=service.pendingFor(player.getUUID()).stream().map(q->decisionAgent(player.level().getServer(),q)).flatMap(java.util.Optional::stream).distinct().toList();
            if(agents.size()!=1){player.sendSystemMessage(net.minecraft.network.chat.Component.literal("选择回答需要明确 Agent/问题上下文；请打开对应选择卡。"));return true;}agent=agents.getFirst();}
        var routed=routeDecisionChat(player,agent,explicit,revision,java.util.UUID.randomUUID(),normalized);if(!routed.handled())return false;
        var result=routed.result();if(result!=null){sendDecisionState(player,result.errorCode(),decisionValues(result.request()));if(result.accepted()&&!result.duplicate())applyUiDecisionEffects(player,service.acceptedAnswer(result.request().decisionId()).orElseThrow(),result.request());}
        player.sendSystemMessage(net.minecraft.network.chat.Component.literal(result!=null&&result.accepted()?"已按选择问题记录聊天回答。":"选择回答未接受："+routed.code()));return true;
    }

    private static void openAppearanceDecision(ServerPlayer player, java.util.UUID agentId) {
        try {
            var state=readAppearanceFromUi(player,agentId);
            var request=openAppearanceDecisionFromUi(player,agentId,Long.parseLong(state.get("revision").toString()),state.get("model").toString(),state.get("texture").toString(),state.get("animation").toString(),java.util.UUID.randomUUID());
            sendDecisionState(player,"",decisionValues(request));
        } catch(Exception failed){sendDecisionState(player,"APPEARANCE_DECISION_UNAVAILABLE",java.util.Map.of("present","false"));}
    }
    public static DecisionRequest openAppearanceDecisionFromUi(ServerPlayer player,java.util.UUID agent,long revision,String model,String texture,String animation,java.util.UUID operation)throws Exception{
        var server=player.level().getServer();if(!mayManageAppearance(player,agent))throw new SecurityException("FORBIDDEN");
        var service=MineAgentRuntimeServices.decisions(server);var json=new com.fasterxml.jackson.databind.ObjectMapper();
        String fingerprint=json.writeValueAsString(java.util.List.of(agent.toString(),revision,model,texture,animation));
        var prior=service.get(operation).orElse(null);if(prior!=null){if(!fingerprint.equals(service.domainContext(operation).get("fingerprint"))||!prior.recipientPlayerId().equals(player.getUUID()))throw new IllegalArgumentException("DECISION_OPERATION_REUSED");return prior;}
        var values=MineAgentRuntimeServices.config(server).snapshot().values();long current=Long.parseLong(values.getOrDefault("agent."+agent+".appearanceRevision","0"));if(revision!=current)throw new IllegalStateException("STALE_REVISION");
        var bridge=new dev.mineagent.runtime.neoforge.integration.NeoForgeYsmRuntimeBridge(server);if(!bridge.runtimeAvailable())throw new IllegalStateException("YSM_RUNTIME_UNAVAILABLE");
        var selections=new java.util.LinkedHashMap<String,dev.mineagent.runtime.integrations.ysm.AppearanceIntentParser.Selection>();
        if(!model.isBlank())selections.put("typed",dev.mineagent.runtime.integrations.ysm.AppearanceDecisionAnswer.resolve(null,model+"|"+texture+"|"+animation).orElseThrow(()->new IllegalArgumentException("APPEARANCE_INPUT")));
        int index=0;for(String id:bridge.availableModels().stream().limit(12-selections.size()).toList())selections.put("catalog_"+(index++),new dev.mineagent.runtime.integrations.ysm.AppearanceIntentParser.Selection(id,"",""));
        var options=selections.entrySet().stream().map(e->new dev.mineagent.runtime.api.decision.DecisionOption(e.getKey(),e.getValue().modelId()+"|"+e.getValue().textureId()+"|"+e.getValue().animationId(),e.getKey().equals("typed")?"当前输入（应用时仍检查资源与权限）":"实际模型目录；空纹理使用 Native 默认，空动画不额外指定")).toList();
        var request=new DecisionRequest(operation,1,player.getUUID(),revision,dev.mineagent.runtime.api.decision.DecisionKind.DESIGN,"AI 外观选择","选择模型，或填写 model=... texture=... animation=...。选项后可用这些字段补充覆盖；其他建议只记录，不会被忽略后自动应用。",options,dev.mineagent.runtime.api.decision.SelectionMode.SINGLE,options.isEmpty()?0:1,options.isEmpty()?0:1,true,DecisionStatus.OPEN);
        service.open(request,java.util.Map.of("domain","appearance","agentId",agent.toString(),"appearanceRevision",Long.toString(revision),"options",json.writeValueAsString(selections),"fingerprint",fingerprint));return request;
    }
    private static AppearanceDecisionContext appearanceContext(net.minecraft.server.MinecraftServer server,java.util.UUID id){
        var context=MineAgentRuntimeServices.decisions(server).domainContext(id);
        if("appearance".equals(context.get("domain")))try{
            var type=new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String,dev.mineagent.runtime.integrations.ysm.AppearanceIntentParser.Selection>>(){};
            return new AppearanceDecisionContext(java.util.UUID.fromString(context.get("agentId")),Long.parseLong(context.get("appearanceRevision")),new com.fasterxml.jackson.databind.ObjectMapper().readValue(context.get("options"),type));
        }catch(Exception invalid){throw new IllegalStateException("APPEARANCE_DECISION_CONTEXT_INVALID",invalid);}
        var registry=APPEARANCE_DECISIONS.get(server);return registry==null?null:registry.get(id).orElse(null);
    }
    public static void reconcileAppearanceDecisions(net.minecraft.server.MinecraftServer server){
        var service=MineAgentRuntimeServices.decisions(server);
        var online=server.getPlayerList().getPlayers().stream().map(ServerPlayer::getUUID).collect(java.util.stream.Collectors.toSet());
        for(var q:service.pendingDomainEffects("appearance",online)){
            var viewer=server.getPlayerList().getPlayer(q.recipientPlayerId());if(viewer==null)continue;
            applyAppearanceDecision(viewer,service.acceptedAnswer(q.decisionId()).orElseThrow(),q);
        }
    }
    private static void applyAppearanceDecision(
            ServerPlayer player,
            DecisionAnswerSubmission submission,
            DecisionRequest resolved
    ) {
        var server=player.level().getServer();var service=MineAgentRuntimeServices.decisions(server);
        boolean persistent="appearance".equals(service.domainContext(resolved.decisionId()).get("domain"));
        if(persistent&&!service.beginDomainEffect(resolved.decisionId(),player.getUUID(),submission.submissionId()))return;
        AppearanceDecisionContext context;
        try{context=appearanceContext(server,resolved.decisionId());}
        catch(IllegalStateException invalid){
            if(!persistent)throw invalid;
            service.finishDomainEffect(resolved.decisionId(),submission.submissionId(),"FAILED","APPEARANCE_DECISION_CONTEXT_INVALID",0);return;
        }
        if(context==null)return;
        if(!persistent){var registry=APPEARANCE_DECISIONS.get(server);if(registry!=null)registry.resolve(resolved.decisionId());}
        dev.mineagent.runtime.integrations.ysm.AppearanceIntentParser.Selection base=null;
        if(!submission.selectedOptionIds().isEmpty())base=context.options().get(submission.selectedOptionIds().getFirst());
        var selection=dev.mineagent.runtime.integrations.ysm.AppearanceDecisionAnswer.resolve(base,submission.customText());
        MineAgentPayloads.AppearanceState result;
        if(selection.isEmpty())result=new MineAgentPayloads.AppearanceState(context.agentId().toString(),submission.submissionId().toString(),false,"APPEARANCE_DETAILS_REQUIRED",context.appearanceRevision());
        else{var choice=selection.orElseThrow();result=applyAppearanceFromUi(new MineAgentPayloads.AppearanceCommand(context.agentId().toString(),choice.modelId(),choice.textureId(),choice.animationId(),context.appearanceRevision(),submission.submissionId().toString()),player);}
        if(persistent)service.finishDomainEffect(resolved.decisionId(),submission.submissionId(),result.accepted()?"APPLIED":"FAILED",result.errorCode(),result.revision());
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player,result);
    }
    private record AppearanceDecisionContext(
            java.util.UUID agentId,
            long appearanceRevision,
            java.util.Map<String, dev.mineagent.runtime.integrations.ysm.AppearanceIntentParser.Selection> options
    ) {
    }

    private static dev.mineagent.runtime.integrations.ysm.AppearanceDecisionRegistry<AppearanceDecisionContext>
    appearanceDecisions(net.minecraft.server.MinecraftServer server) {
        synchronized (APPEARANCE_DECISIONS) {
            return APPEARANCE_DECISIONS.computeIfAbsent(server,
                    ignored -> new dev.mineagent.runtime.integrations.ysm.AppearanceDecisionRegistry<>(128));
        }
    }

    private static void sendConversationEmpty(ServerPlayer player, String errorCode) {
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(
                player, new MineAgentPayloads.ConversationState(errorCode, java.util.Map.of("present", "false")));
    }

    private static void synthesizeAndSendVoice(
            net.minecraft.server.MinecraftServer server,
            java.util.UUID agentId,
            ServerPlayer recipient,
            String text,
            boolean privateResponse
    ) {
        var config = MineAgentRuntimeServices.config(server).snapshot().values();
        if (!Boolean.parseBoolean(config.getOrDefault("voice.output.enabled", "true"))) {
            return;
        }
        String voice = config.getOrDefault("agent." + agentId + ".voice",
                config.getOrDefault("voice.default", "zh-CN-XiaoxiaoNeural"));
        String rate = config.getOrDefault("voice.rate", "+0%");
        String pitch = config.getOrDefault("voice.pitch", "+0Hz");
        String volume = config.getOrDefault("voice.volume", "+0%");
        var worker = MineAgentRuntimeServices.worker(server);
        worker.synthesize(text, voice, rate, pitch, volume).whenComplete((response, failure) -> {
            if (failure != null || !"tts.result".equals(response.type())) {
                MineAgentRuntimeMod.LOGGER.warn("Edge TTS failed for agent {}: {}", agentId,
                        failure == null ? response.payload() : failure.getMessage());
                return;
            }
            try {
                String hash = String.valueOf(response.payload().get("sha256"));
                byte[] audio = java.nio.file.Files.readAllBytes(worker.contentPath(hash));
                if (audio.length < 1 || audio.length > 8 * 1024 * 1024) {
                    throw new java.io.IOException("TTS audio size is outside limits: " + audio.length);
                }
                server.execute(() -> distributeVoice(server, agentId, recipient, privateResponse, hash, audio));
            } catch (Exception readFailure) {
                MineAgentRuntimeMod.LOGGER.warn("Failed to load synthesized audio", readFailure);
            }
        });
    }

    public static void runVoiceSmoke(
            net.minecraft.server.MinecraftServer server,
            java.util.UUID agentId,
            ServerPlayer recipient,
            boolean privateResponse
    ) {
        synthesizeAndSendVoice(server, agentId, recipient,
                privateResponse ? "私有语音测试" : "公共语音测试", privateResponse);
    }

    private static void distributeVoice(
            net.minecraft.server.MinecraftServer server,
            java.util.UUID agentId,
            ServerPlayer recipient,
            boolean privateResponse,
            String hash,
            byte[] audio
    ) {
        var body = MineAgentRuntimeServices.bodies(server).body(agentId).orElse(null);
        double x = body == null ? recipient.getX() : body.getX();
        double y = body == null ? recipient.getY() : body.getY();
        double z = body == null ? recipient.getZ() : body.getZ();
        java.util.List<ServerPlayer> targets;
        if (privateResponse) {
            targets = java.util.List.of(recipient);
        } else if (body == null) {
            targets = server.getPlayerList().getPlayers().stream()
                    .filter(candidate -> !(candidate instanceof dev.mineagent.runtime.neoforge.body.MineAgentPlayer))
                    .toList();
        } else {
            targets = server.getPlayerList().getPlayers().stream()
                    .filter(candidate -> !(candidate instanceof dev.mineagent.runtime.neoforge.body.MineAgentPlayer))
                    .filter(candidate -> candidate.level() == body.level())
                    .filter(candidate -> candidate.distanceToSqr(body) <= 64.0 * 64.0)
                    .toList();
        }
        final int chunkSize = 24 * 1024;
        int chunks = (audio.length + chunkSize - 1) / chunkSize;
        for (ServerPlayer target : targets) {
            for (int index = 0; index < chunks; index++) {
                int start = index * chunkSize;
                int end = Math.min(audio.length, start + chunkSize);
                byte[] data = java.util.Arrays.copyOfRange(audio, start, end);
                net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(target,
                        new MineAgentPayloads.VoiceChunk(hash, index, chunks, x, y, z, !privateResponse, data));
            }
        }
    }

    private static void applyAgentCommand(MineAgentPayloads.AgentCommand payload, ServerPlayer player) {
        var server = player.level().getServer();
        var bodies = MineAgentRuntimeServices.bodies(server);
        boolean operator = player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
        try {
            boolean accepted;
            if (!"create".equals(payload.action()) && !"follow".equals(payload.action())) {
                java.util.UUID revisionAgentId = requiredUuid(payload.values(), "agentId");
                long expectedRevision = Long.parseLong(required(payload.values(), "expectedRevision"));
                if (bodies.revision(revisionAgentId) != expectedRevision) {
                    sendAgentResult(player, false, "STALE_AGENT_REVISION");
                    net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player, panelSnapshot(player));
                    return;
                }
            }
            switch (payload.action()) {
                case "create" -> {
                    if (!MineAgentRuntimeServices.permissions(server)
                            .allowed(player.getUUID(), operator, PermissionAction.CREATE_AGENT)) {
                        sendAgentResult(player, false, "FORBIDDEN");
                        return;
                    }
                    var mode = dev.mineagent.runtime.api.agent.AgentMode.valueOf(
                            payload.values().getOrDefault("mode", "CREATOR"));
                    var created = bodies.create(required(payload.values(), "name"), player, mode);
                    MineAgentRuntimeServices.permissions(server)
                            .registerOwnership(created.agentId(), created.ownerPlayerId());
                    accepted = true;
                }
                case "rename" -> accepted = bodies.rename(
                        requiredUuid(payload.values(), "agentId"), player.getUUID(), operator,
                        required(payload.values(), "name"));
                case "set_mode" -> accepted = bodies.setMode(
                        requiredUuid(payload.values(), "agentId"), player.getUUID(), operator,
                        dev.mineagent.runtime.api.agent.AgentMode.valueOf(required(payload.values(), "mode")));
                case "delete" -> {
                    java.util.UUID id = requiredUuid(payload.values(), "agentId");
                    accepted = bodies.remove(id, player.getUUID(), operator);
                    if (accepted) {
                        MineAgentRuntimeServices.permissions(server).removeOwnership(id);
                    }
                }
                case "follow" -> {
                    java.util.UUID id = requiredUuid(payload.values(), "agentId");
                    var definition = bodies.definitions().stream()
                            .filter(agent -> agent.agentId().equals(id)).findFirst().orElse(null);
                    accepted = definition != null && operatorOrOwner(player, definition);
                    if (accepted) {
                        bodies.moveToOwner(id);
                    }
                }
                case "collaborator" -> accepted = bodies.setCollaborator(
                        requiredUuid(payload.values(), "agentId"),
                        player.getUUID(),
                        requiredUuid(payload.values(), "playerId"),
                        Boolean.parseBoolean(required(payload.values(), "enabled")));
                default -> throw new IllegalArgumentException("unsupported agent command");
            }
            sendAgentResult(player, accepted, accepted ? "" : "FORBIDDEN_OR_NOT_FOUND");
            if (accepted) {
                net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player, panelSnapshot(player));
            }
        } catch (RuntimeException failure) {
            sendAgentResult(player, false, "INVALID_AGENT_COMMAND");
        }
    }

    private static java.util.UUID requiredUuid(java.util.Map<String, String> values, String key) {
        return java.util.UUID.fromString(required(values, key));
    }

    private static void sendAgentResult(ServerPlayer player, boolean accepted, String errorCode) {
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(
                player, new MineAgentPayloads.AgentCommandResult(accepted, errorCode));
    }

    public static void sendBasketballScore(
            net.minecraft.server.MinecraftServer server,
            java.util.UUID playerId,
            int points,
            int totalScore
    ) {
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        if (player != null && !(player instanceof dev.mineagent.runtime.neoforge.body.MineAgentPlayer)) {
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(
                    player, new MineAgentPayloads.BasketballScore(points, totalScore));
        }
    }

    private static void applyTaskCommand(MineAgentPayloads.TaskCommand payload, ServerPlayer player) {
        var server = player.level().getServer();
        var manager = MineAgentRuntimeServices.tasks(server);
        try {
            if ("refresh".equals(payload.action())) {
                sendTaskState(player, "");
                return;
            }
            if ("create".equals(payload.action())) {
                boolean operator = player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
                if (!MineAgentRuntimeServices.permissions(server)
                        .allowed(player.getUUID(), operator, PermissionAction.START_TASK)) {
                    sendTaskState(player, "FORBIDDEN");
                    return;
                }
                java.util.UUID agentId = requiredUuid(payload.values(), "agentId");
                boolean exists = MineAgentRuntimeServices.bodies(server).definitions().stream()
                        .anyMatch(agent -> agent.agentId().equals(agentId));
                if (!exists) {
                    sendTaskState(player, "UNKNOWN_AGENT");
                    return;
                }
                int priority = Integer.parseInt(payload.values().getOrDefault("priority", "50"));
                dev.mineagent.runtime.neoforge.task.ServerTaskStart.start(player,
                        java.util.UUID.fromString(payload.values().getOrDefault("operationId",java.util.UUID.randomUUID().toString())),agentId,required(payload.values(),"title"),priority,
                        "UI_PACKAGE".equals(payload.values().getOrDefault("planningScope","GENERAL")));
                sendTaskState(player, "");
                return;
            }
            java.util.UUID taskId = requiredUuid(payload.values(), "taskId");
            long expectedRevision = Long.parseLong(required(payload.values(), "expectedRevision"));
            var task = manager.get(taskId).orElseThrow(() -> new IllegalArgumentException("unknown task"));
            boolean authorized = mayMutateTask(player, task);
            dev.mineagent.runtime.api.task.TaskMutationResult result;
            result = switch (payload.action()) {
                case "pause" -> manager.transition(taskId, expectedRevision, authorized,
                        dev.mineagent.runtime.api.task.TaskStatus.PAUSED);
                case "resume" -> manager.transition(taskId, expectedRevision, authorized,
                        dev.mineagent.runtime.api.task.TaskStatus.RUNNING);
                case "cancel" -> manager.transition(taskId, expectedRevision, authorized,
                        dev.mineagent.runtime.api.task.TaskStatus.CANCELLED);
                case "replan" -> manager.replan(taskId, expectedRevision, authorized,
                        parsePlan(payload.values()), payload.values().getOrDefault("reason", "用户在 UI 中请求重规划"));
                default -> throw new IllegalArgumentException("unsupported task action");
            };
            sendTaskState(player, result.errorCode());
        } catch (Exception failure) {
            sendTaskState(player, "INVALID_TASK_COMMAND");
        }
    }

    private static java.util.List<dev.mineagent.runtime.core.task.TaskStepSpec> parsePlan(
            java.util.Map<String, String> values
    ) {
        int count = Integer.parseInt(values.getOrDefault("stepCount", "2"));
        if (count < 1 || count > 64) {
            throw new IllegalArgumentException("invalid step count");
        }
        if (!values.containsKey("step.0.id")) {
            return java.util.List.of(
                    new dev.mineagent.runtime.core.task.TaskStepSpec("replan", java.util.Set.of()),
                    new dev.mineagent.runtime.core.task.TaskStepSpec("execute", java.util.Set.of("replan"))
            );
        }
        var result = new java.util.ArrayList<dev.mineagent.runtime.core.task.TaskStepSpec>(count);
        for (int index = 0; index < count; index++) {
            String id = required(values, "step." + index + ".id");
            String dependencies = values.getOrDefault("step." + index + ".dependencies", "");
            java.util.Set<String> parsed = dependencies.isBlank() ? java.util.Set.of()
                    : java.util.Arrays.stream(dependencies.split(","))
                    .map(String::strip).filter(value -> !value.isBlank())
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            result.add(new dev.mineagent.runtime.core.task.TaskStepSpec(id, parsed));
        }
        return java.util.List.copyOf(result);
    }

    private static boolean mayMutateTask(
            ServerPlayer player,
            dev.mineagent.runtime.api.task.ManagedTask task
    ) {
        if (player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER)
                || task.ownerPlayerId().equals(player.getUUID())) {
            return true;
        }
        return MineAgentRuntimeServices.bodies(player.level().getServer()).definitions().stream()
                .filter(agent -> agent.agentId().equals(task.agentId()))
                .anyMatch(agent -> agent.ownerPlayerId().equals(player.getUUID())
                        || agent.collaboratorPlayerIds().contains(player.getUUID()));
    }

    private static void sendTaskState(ServerPlayer player, String errorCode) {
        var tasks = MineAgentRuntimeServices.tasks(player.level().getServer()).all();
        int count = Math.min(20, tasks.size());
        var values = new java.util.LinkedHashMap<String, String>();
        values.put("taskCount", Integer.toString(count));
        for (int index = 0; index < count; index++) {
            var task = tasks.get(index);
            String prefix = "task." + index + ".";
            values.put(prefix + "id", task.taskId().toString());
            values.put(prefix + "agentId", task.agentId().toString());
            values.put(prefix + "ownerId", task.ownerPlayerId().toString());
            values.put(prefix + "title", task.title());
            values.put(prefix + "priority", Integer.toString(task.priority()));
            values.put(prefix + "revision", Long.toString(task.revision()));
            values.put(prefix + "status", task.status().name());
            values.put(prefix + "runnable", String.join(",", task.runnableStepIds()));
            values.put(prefix + "changeReason", task.lastChangeReason());
        }
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(
                player, new MineAgentPayloads.TaskState(errorCode, values));
    }

    private static void applyCodeCommand(MineAgentPayloads.CodeCommand payload, ServerPlayer player) {
        var server = player.level().getServer();
        boolean operator = player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
        if (!MineAgentRuntimeServices.permissions(server)
                .allowed(player.getUUID(), operator, PermissionAction.RUN_CODE)) {
            sendCodeState(player, "FORBIDDEN");
            return;
        }
        try {
            if(payload.values().containsKey("worldId")&&!MineAgentRuntimeServices.worldId(server).toString().equals(payload.values().get("worldId")))throw new IllegalStateException("STUDIO_NATIVE_CONTEXT_CHANGED");
            if ("refresh".equals(payload.action())) {
                if(payload.values().isEmpty())sendCodeState(player, "");
                else{
                    if(!payload.values().keySet().equals(java.util.Set.of("draftId","worldId"))||!MineAgentRuntimeServices.worldId(server).toString().equals(payload.values().get("worldId")))throw new IllegalStateException("STUDIO_NATIVE_CONTEXT_CHANGED");
                    sendCodeState(player,"",java.util.Map.of(),java.util.UUID.fromString(payload.values().get("draftId")));
                }
                return;
            }
            if("create".equals(payload.action())){sendCodeState(player,"STUDIO_NATIVE_EXPLICIT_AGENT_REQUIRED");return;}
            java.util.UUID draftId = requiredUuid(payload.values(), "draftId");
            long expectedRevision = Long.parseLong(required(payload.values(), "expectedRevision"));
            var drafts = MineAgentRuntimeServices.codeDrafts(server);
            if("javaStop".equals(payload.action())){stopJavaPublication(player,draftId,expectedRevision,payload.values());return;}
            if("scriptStop".equals(payload.action())){
                dev.mineagent.runtime.neoforge.ui.ServerJavaStudio.write(player,java.util.UUID.randomUUID(),java.util.Map.of("action","stop","confirmed",required(payload.values(),"confirmed"),"draftId",draftId.toString(),"revision",Long.toString(expectedRevision),"publicationId",required(payload.values(),"publicationId"),"publicationRevision",required(payload.values(),"publicationRevision")));
                sendCodeState(player,"",java.util.Map.of(),draftId);return;
            }
            if ("save".equals(payload.action())) {
                var result = drafts.update(draftId, player.getUUID(), expectedRevision,
                        required(payload.values(), "source"));
                sendCodeState(player, result.errorCode());
                return;
            }
            if ("publish".equals(payload.action())) {
                var draft = drafts.get(draftId).orElseThrow();
                if (draft.path().toLowerCase(java.util.Locale.ROOT).endsWith(".java")) {
                    compileAndPublishJava(player, draft, expectedRevision);
                    return;
                }
                var record=dev.mineagent.runtime.neoforge.ui.StudioScriptRuntime.get(server).run(player,draft,expectedRevision);
                sendTaskState(player,"");
                sendCodeState(player,"",java.util.Map.of("scriptPublication.id",record.id().toString(),
                        "scriptPublication.state",record.state(),"scriptPublication.error",record.error()));
                return;
            }
            throw new IllegalArgumentException("unsupported code action");
        } catch (Exception failure) {
            sendCodeState(player,dev.mineagent.runtime.neoforge.ui.ServerJavaStudio.error(failure));
        }
    }

    public static void runJavaStudio(ServerPlayer player,java.util.UUID id,long revision){dev.mineagent.runtime.neoforge.ui.ServerJavaStudio.authorize(player);var draft=MineAgentRuntimeServices.codeDrafts(player.level().getServer()).get(id).orElseThrow();String result=compileAndPublishJava(player,draft,revision);if(!result.isEmpty())throw new IllegalStateException(result);}
    public static void stopJavaStudio(ServerPlayer player,java.util.UUID id,long revision,java.util.Map<String,String> values)throws Exception{dev.mineagent.runtime.neoforge.ui.ServerJavaStudio.authorize(player);stopJavaPublication(player,id,revision,values);}
    private static void stopJavaPublication(ServerPlayer player,java.util.UUID draftId,long revision,java.util.Map<String,String> values)throws Exception{
        var server=player.level().getServer();var drafts=MineAgentRuntimeServices.codeDrafts(server);var draft=drafts.get(draftId).orElseThrow();
        if(!draft.ownerPlayerId().equals(player.getUUID())||draft.revision()!=revision||!"true".equals(values.get("confirmed")))throw new SecurityException("JAVA_STOP_CONFIRMATION_REQUIRED");
        var journal=drafts.javaPublications();var record=journal.get(java.util.UUID.fromString(required(values,"publicationId")));
        if(!record.owner().equals(player.getUUID())||!record.draft().equals(draftId)||!record.world().equals(MineAgentRuntimeServices.worldId(server)))throw new SecurityException("JAVA_STOP_OWNER");
        if(java.util.Set.of("STOP_RETURNED","STOP_UNKNOWN","NOT_LOADED_THIS_PROCESS").contains(record.state())){javaPublicationResult(player,record,"JAVA_STOP_ALREADY_RECORDED",java.util.Map.of());return;}
        if(record.revision()!=Long.parseLong(required(values,"publicationRevision"))||!java.util.Set.of("PUBLISHED","OUTCOME_UNKNOWN").contains(record.state()))throw new IllegalStateException("JAVA_STOP_STATE_CHANGED");
        MineAgentRuntimeServices.javaExtensions(server).requireCanUnload(record.id());
        record=journal.finish(record.id(),"STOPPING",record.artifact(),record.activationMode(),"");
        try{
            boolean invoked=MineAgentRuntimeServices.javaExtensions(server).unload(record.id());
            var pack=MineAgentRuntimeServices.contentPackages(server).get(record.packageId()).orElse(null);
            if(pack!=null&&pack.source().equals("java-jar:"+record.artifact()+"\nentrypoint:"+record.className())&&pack.enabled()){
                var changed=MineAgentRuntimeServices.contentPackages(server).setEnabled(pack.packageId(),pack.revision(),false);if(!changed.accepted())throw new IllegalStateException("JAVA_STOP_METADATA_CHANGED");
            }
            record=journal.finish(record.id(),invoked?"STOP_RETURNED":"NOT_LOADED_THIS_PROCESS",record.artifact(),record.activationMode(),invoked?"":"NO_STOP_CALLBACK_INVOKED");
            javaPublicationResult(player,record,"",java.util.Map.of());
        }catch(Exception failed){try{record=journal.finish(record.id(),"STOP_UNKNOWN",record.artifact(),record.activationMode(),"JAVA_STOP_UNCERTAIN");javaPublicationResult(player,record,record.error(),java.util.Map.of());}catch(Exception writeFailure){sendCodeState(player,"JAVA_STOP_RECEIPT_UNCERTAIN");}}
    }
    private record JavaPublishGuard(dev.mineagent.runtime.api.packages.CodeDraft draft,long actionRevision,long permissionGeneration,boolean operator){}
    private record JavaNativeSource(dev.mineagent.runtime.core.compile.NativeCoderContext selection,dev.mineagent.runtime.core.compile.NativeCoderContext.Snapshot context,String classpath){}
    private static boolean javaPublishCurrent(ServerPlayer player,JavaPublishGuard guard){
        var server=player.level().getServer();if(!server.isSameThread()||!server.isRunning()||server.getPlayerList().getPlayer(player.getUUID())!=player)return false;
        var requested=guard.draft();var permissions=MineAgentRuntimeServices.permissions(server);
        boolean operator=player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
        if(operator!=guard.operator()||!permissions.allowed(player.getUUID(),operator,PermissionAction.RUN_CODE)
                ||permissions.actionRevision(player.getUUID(),PermissionAction.RUN_CODE)!=guard.actionRevision()
                ||MineAgentRuntimeServices.config(server).permissionGeneration(player.getUUID(),PermissionAction.RUN_CODE)!=guard.permissionGeneration()
                ||!requested.worldId().equals(MineAgentRuntimeServices.worldId(server)))return false;
        var current=MineAgentRuntimeServices.codeDrafts(server).get(requested.draftId()).orElse(null);
        var task=MineAgentRuntimeServices.tasks(server).get(requested.taskId()).orElse(null);
        return current!=null&&current.ownerPlayerId().equals(player.getUUID())&&current.revision()==requested.revision()
                &&current.status()==dev.mineagent.runtime.api.packages.CodeDraftStatus.DRAFT&&current.source().equals(requested.source())&&current.path().equals(requested.path())&&current.additionalSources().equals(requested.additionalSources())&&current.dependencies().equals(requested.dependencies())
                &&current.taskId().equals(requested.taskId())&&current.packageId().equals(requested.packageId())
                &&task!=null&&task.worldId().equals(requested.worldId())&&task.ownerPlayerId().equals(player.getUUID())
                &&task.revision()==requested.taskRevision()&&task.status()==dev.mineagent.runtime.api.task.TaskStatus.RUNNING
                &&task.runnableStepIds().contains("publish")&&!dev.mineagent.runtime.core.task.TaskAuthorityFence.revoked(task);
    }
    private static boolean javaPackageCurrent(ServerPlayer player,JavaPublishGuard guard,dev.mineagent.runtime.api.packages.RuntimePackage pkg,long manageGeneration){
        if(!javaPublishCurrent(player,guard))return false;var server=player.level().getServer();var permissions=MineAgentRuntimeServices.permissions(server);if(!permissions.allowed(player.getUUID(),player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER),PermissionAction.MANAGE_PACKAGES)||MineAgentRuntimeServices.config(server).permissionGeneration(player.getUUID(),PermissionAction.MANAGE_PACKAGES)!=manageGeneration)return false;
        var current=dev.mineagent.runtime.neoforge.ui.ServerPackageRuntime.get(server).ownedPackage(player.getUUID(),pkg.packageId(),pkg.revision()).orElse(null);return current!=null&&current.canonicalSha256().equals(pkg.canonicalSha256())&&dev.mineagent.runtime.core.packages.NativeCompatibilityPolicy.check(current,dev.mineagent.runtime.neoforge.content.NativePackageCompatibility.observe(),"SERVER").allowed();
    }
    private static boolean javaNativeCurrent(net.minecraft.server.MinecraftServer server,JavaNativeSource source){
        if(source==null)return true;try{var latest=dev.mineagent.runtime.neoforge.compile.NativeCompilationEnvironment.latest();return source.context().selectionHash().equals(source.selection().fingerprint())&&source.classpath().equals(source.context().compilationSnapshot().isEmpty()?source.selection().snapshot():source.context().compilationSnapshot())&&latest.hash().equals(source.selection().snapshot())&&latest.snapshot().environment().fingerprint().equals(source.selection().environment())&&source.selection().environment().equals(dev.mineagent.runtime.neoforge.content.NativePackageCompatibility.observe().fingerprint())&&source.selection().overlays().stream().allMatch(o->o.processEpoch().equals(dev.mineagent.runtime.neoforge.compile.NativeLiveClassAccess.state().processEpoch()));}catch(Exception unavailable){return false;}
    }
    private static JavaNativeSource javaNativeSource(net.minecraft.server.MinecraftServer server,dev.mineagent.runtime.api.packages.CodeDraft draft){
        var job=MineAgentRuntimeServices.codeDrafts(server).coder().nativePublicationSource(draft).orElse(null);if(job==null)return null;var selection=job.input().nativeSelection();var context=job.last().nativeContext();String classpath=context.compilationSnapshot().isEmpty()?selection.snapshot():context.compilationSnapshot();var source=new JavaNativeSource(selection,context,classpath);if(!javaNativeCurrent(server,source))throw new IllegalStateException("JAVA_NATIVE_CONTEXT_CHANGED");return source;
    }
    private static boolean javaDependenciesCurrent(ServerPlayer player,JavaPublishGuard guard,dev.mineagent.runtime.api.packages.RuntimePackage pkg,long generation,dev.mineagent.runtime.core.packages.JavaDependencyGraph graph,JavaNativeSource nativeSource){
        return javaPackageCurrent(player,guard,pkg,generation)&&dev.mineagent.runtime.neoforge.ui.ServerJavaDependencies.current(player.level().getServer(),player.getUUID(),graph)&&javaNativeCurrent(player.level().getServer(),nativeSource);
    }
    private static void javaPublicationResult(ServerPlayer player,dev.mineagent.runtime.scripting.studio.JavaPublicationJournal.Record record,String error,java.util.Map<String,String> extra){
        var values=new java.util.LinkedHashMap<>(extra);values.put("javaPublication.id",record.id().toString());values.put("javaPublication.state",record.state());values.put("javaPublication.draftId",record.draft().toString());
        values.put("javaPublication.error",record.error());values.put("javaPublication.uncertain",Boolean.toString(record.uncertain()));values.put("compile.nativeClasspath",record.nativeClasspath());values.put("compile.runtimePackageHash",record.runtimePackageHash());values.put("compile.sha256",record.artifact());values.put("compile.activationMode",record.activationMode());
        boolean loaded=MineAgentRuntimeServices.javaExtensions(player.level().getServer()).isLoaded(record.id());
        values.put("compile.loaded",Boolean.toString(loaded));values.put("compile.restartRequired",Boolean.toString(java.util.Set.of("BOOT_EXTENSION","WORLD_REOPEN").contains(record.activationMode())));
        sendCodeState(player,error,values);
    }
    private static String compileAndPublishJava(ServerPlayer player,dev.mineagent.runtime.api.packages.CodeDraft requestedDraft,long expectedRevision){
        var server=player.level().getServer();
        var guard=new JavaPublishGuard(requestedDraft,MineAgentRuntimeServices.permissions(server).actionRevision(player.getUUID(),PermissionAction.RUN_CODE),
                MineAgentRuntimeServices.config(server).permissionGeneration(player.getUUID(),PermissionAction.RUN_CODE),player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER));
        if(requestedDraft.revision()!=expectedRevision||!javaPublishCurrent(player,guard)){sendCodeState(player,"STALE_OR_UNAUTHORIZED_JAVA_PUBLICATION");return "JAVA_CONTEXT_CHANGED";}
        final dev.mineagent.runtime.api.packages.RuntimePackage sourcePackage;final long manageGeneration=MineAgentRuntimeServices.config(server).permissionGeneration(player.getUUID(),PermissionAction.MANAGE_PACKAGES);
        final dev.mineagent.runtime.core.packages.JavaDependencyGraph dependencyGraph;final JavaNativeSource nativeSource;
        final String className;final dev.mineagent.runtime.scripting.studio.JavaPublicationJournal journal;final dev.mineagent.runtime.scripting.studio.JavaPublicationJournal.Record ticket;
        try{
            for(var prior:MineAgentRuntimeServices.codeDrafts(server).javaPublications().forPackage(player.getUUID(),requestedDraft.packageId())){if(prior.uncertain())throw new IllegalStateException("JAVA_PREVIOUS_OUTCOME_UNKNOWN");if(java.util.Set.of("COMPILING","COMPILED","STARTING","START_RETURNED","STOPPING").contains(prior.state()))throw new IllegalStateException("JAVA_PUBLICATION_BUSY");if(MineAgentRuntimeServices.javaExtensions(server).isLoaded(prior.id()))throw new IllegalStateException("JAVA_PACKAGE_STILL_RUNNING");}
            sourcePackage=dev.mineagent.runtime.neoforge.ui.ServerJavaStudio.ensureSource(player,requestedDraft);

            dependencyGraph=dev.mineagent.runtime.neoforge.ui.ServerJavaDependencies.resolve(server,player.getUUID(),sourcePackage.packageId(),sourcePackage.dependencies());
            nativeSource=javaNativeSource(server,requestedDraft);
            className=dev.mineagent.runtime.worker.compile.JavaSourceCompiler.inferClassName(requestedDraft.source());
            journal=MineAgentRuntimeServices.codeDrafts(server).javaPublications();var prepared=journal.prepare(requestedDraft,className,dependencyGraph,nativeSource==null?"":nativeSource.classpath());ticket=prepared.record();
            if(!prepared.dispatch()){javaPublicationResult(player,ticket,"JAVA_PUBLICATION_ALREADY_RECORDED",java.util.Map.of());return "JAVA_PUBLICATION_ALREADY_RECORDED";}try{
                MineAgentRuntimeServices.javaExtensions(server).pin(ticket.id(),dependencyGraph);
                if(!dev.mineagent.runtime.neoforge.ui.ServerJavaDependencies.current(server,player.getUUID(),dependencyGraph))throw new IllegalStateException("JAVA_DEPENDENCY_CHANGED");
                journal.bindRuntimePackage(ticket.id(),sourcePackage.canonicalSha256());
            }catch(Exception invalid){MineAgentRuntimeServices.javaExtensions(server).releaseBeforeStart(ticket.id());journal.finish(ticket.id(),"CANCELLED","","","JAVA_DEPENDENCY_PREPARE_FAILED");throw invalid;}
        }catch(Exception invalid){String code=java.util.Objects.toString(invalid.getMessage(),"");sendCodeState(player,code.matches("JAVA_[A-Z_]{1,70}")?code:"JAVA_PUBLICATION_REJECTED");return code.matches("JAVA_[A-Z_]{1,70}")?code:"JAVA_PUBLICATION_REJECTED";}
        java.util.function.BooleanSupplier permit=()->{try{return server.submit(()->javaDependenciesCurrent(player,guard,sourcePackage,manageGeneration,dependencyGraph,nativeSource)).get(3,java.util.concurrent.TimeUnit.SECONDS);}catch(Exception invalid){return false;}};
        try{var sources=MineAgentRuntimeServices.codeDrafts(server).sources(requestedDraft);var compilation=nativeSource==null?MineAgentRuntimeServices.worker(server).compileJavaWorkspace(className,requestedDraft.path(),sources,dependencyGraph,permit):MineAgentRuntimeServices.worker(server).compileJavaWorkspace(className,requestedDraft.path(),sources,dependencyGraph,nativeSource.selection(),nativeSource.context(),permit);compilation.whenComplete((response,failure)->server.execute(()->{
            if(!server.isRunning())return;
            String artifact="",mode="";boolean nativeStarted=false;var extra=new java.util.LinkedHashMap<String,String>();
            try{
                if(!javaDependenciesCurrent(player,guard,sourcePackage,manageGeneration,dependencyGraph,nativeSource)){var record=journal.finish(ticket.id(),"CANCELLED","","","JAVA_CONTEXT_CHANGED");javaPublicationResult(player,record,"JAVA_CONTEXT_CHANGED",extra);return;}
                if(failure!=null||response==null||!response.type().equals("java.compile.result")){var record=journal.finish(ticket.id(),"COMPILE_FAILED","","",nativeCompileFailure(failure));javaPublicationResult(player,record,record.error(),extra);return;}
                extra.putAll(javaCompilationValues(response.payload()));
                var diagnosticLines=new java.util.ArrayList<String>();if(response.payload().get("diagnostics") instanceof java.util.List<?> diagnostics)for(var value:diagnostics.stream().limit(20).toList()){String line=String.valueOf(value);diagnosticLines.add(line.substring(0,Math.min(800,line.length())));}journal.recordDiagnostics(ticket.id(),diagnosticLines);

                if(!extra.getOrDefault("compile.nativeClasspath","").isEmpty())journal.bindClasspath(ticket.id(),extra.get("compile.nativeClasspath"));
                if(!Boolean.parseBoolean(String.valueOf(response.payload().getOrDefault("success",false)))){var record=journal.finish(ticket.id(),"COMPILE_FAILED","","","JAVA_COMPILE_REJECTED");javaPublicationResult(player,record,record.error(),extra);return;}
                String compiledHash=requiredObject(response.payload(),"sha256");if(!compiledHash.matches("[a-f0-9]{64}"))throw new IllegalStateException("JAVA_ARTIFACT_CHANGED");artifact=compiledHash;mode=dev.mineagent.runtime.api.packages.ActivationMode.valueOf(requiredObject(response.payload(),"activationMode")).name();
                var path=MineAgentRuntimeServices.worker(server).contentPath(artifact);
                if(!artifact.equals(dev.mineagent.runtime.scripting.javaext.JavaExtensionManager.sha256(path)))throw new IllegalStateException("JAVA_ARTIFACT_CHANGED");
                dev.mineagent.runtime.core.compile.NativeCompilationSnapshot.verifyReceipt(path,className,ticket.sourceHash(),journal.get(ticket.id()).nativeClasspath(),dependencyGraph.receiptHash());
                if(!extra.getOrDefault("compile.environment","").equals(dev.mineagent.runtime.neoforge.content.NativePackageCompatibility.observe().fingerprint()))throw new IllegalStateException("JAVA_CLASSPATH_ENVIRONMENT_CHANGED");
                var activation=MineAgentRuntimeServices.javaExtensions(server).classify(path);
                if(!activation.name().equals(mode))throw new IllegalStateException("JAVA_CLASSIFICATION_CHANGED");
                journal.finish(ticket.id(),"COMPILED",artifact,mode,"");
                if(activation!=dev.mineagent.runtime.api.packages.ActivationMode.HOT_RUNTIME){
                    var record=journal.finish(ticket.id(),"STAGED_NOT_ACTIVATED",artifact,mode,"JAVA_LIFECYCLE_NOT_ACTIVATED");
                    javaPublicationResult(player,record,record.error(),extra);return;
                }
                if(!javaDependenciesCurrent(player,guard,sourcePackage,manageGeneration,dependencyGraph,nativeSource))throw new IllegalStateException("JAVA_CONTEXT_CHANGED");
                var classOwners=new java.util.LinkedHashMap<String,java.util.UUID>();
                if(!(response.payload().get("localDependencyClassOwners") instanceof java.util.Map<?,?> owners))throw new IllegalStateException("JAVA_DEPENDENCY_RECEIPT");
                for(var owner:owners.entrySet()){if(!(owner.getKey() instanceof String type)||!(owner.getValue() instanceof java.util.UUID scope))throw new IllegalStateException("JAVA_DEPENDENCY_RECEIPT");classOwners.put(type,scope);}
                journal.finish(ticket.id(),"STARTING",artifact,mode,"");
                // Nothing after this boundary may be replayed to repair missing receipts or metadata.
                nativeStarted=true;
                var loaded=MineAgentRuntimeServices.javaExtensions(server).load(ticket.id(),path,artifact,className,java.util.Map.of("server",server,"ownerPlayerId",player.getUUID(),"worldId",requestedDraft.worldId(),"taskId",requestedDraft.taskId(),"packageId",requestedDraft.packageId()),dependencyGraph,classOwners);
                if(loaded.extensionClassLoader()==null||loaded.activationMode()!=dev.mineagent.runtime.api.packages.ActivationMode.HOT_RUNTIME)throw new IllegalStateException("JAVA_NOT_LOADED");
                journal.finish(ticket.id(),"START_RETURNED",artifact,mode,"");
                if(!javaDependenciesCurrent(player,guard,sourcePackage,manageGeneration,dependencyGraph,nativeSource))throw new IllegalStateException("JAVA_CONTEXT_CHANGED");
                String packageSource="runtime-package:"+sourcePackage.packageId()+"@"+sourcePackage.canonicalSha256()+"; java-artifact:"+artifact;
                var published=MineAgentRuntimeServices.codeDrafts(server).markExternalPublished(requestedDraft.draftId(),player.getUUID(),expectedRevision,requestedDraft.taskRevision());
                if(!published.accepted())throw new IllegalStateException("JAVA_DRAFT_COMMIT_FAILED");
                if(!MineAgentRuntimeServices.tasks(server).completeStep(requestedDraft.taskId(),requestedDraft.taskRevision(),"publish").accepted())throw new IllegalStateException("JAVA_TASK_COMMIT_FAILED");
                var record=journal.finish(ticket.id(),"PUBLISHED",artifact,mode,"");
                audit(server,player.getUUID().toString(),"JAVA_EXTENSION_PUBLISHED",requestedDraft.draftId().toString(),packageSource);
                sendTaskState(player,"");javaPublicationResult(player,record,"",extra);
            }catch(Exception error){
                String code=java.util.Objects.toString(error.getMessage(),"");if(!code.matches("JAVA_DEPENDENCY_[A-Z_]{1,70}")&&!java.util.Set.of("JAVA_CLASSPATH_RECEIPT","JAVA_CLASSPATH_ENVIRONMENT_CHANGED","JAVA_ARTIFACT_CHANGED","JAVA_CLASSIFICATION_CHANGED","JAVA_NOT_LOADED","JAVA_CONTEXT_CHANGED","JAVA_NATIVE_CONTEXT_CHANGED","JAVA_PACKAGE_STILL_RUNNING","JAVA_PACKAGE_RECEIPT","JAVA_PACKAGE_COMMIT_FAILED","JAVA_DRAFT_COMMIT_FAILED","JAVA_TASK_COMMIT_FAILED","JAVA_PUBLICATION_CAS","JAVA_PUBLICATION_TRANSITION").contains(code))code=nativeStarted?"JAVA_START_OR_COMMIT_UNCERTAIN":"JAVA_PUBLICATION_FAILED";
                try{var record=journal.finish(ticket.id(),nativeStarted?"OUTCOME_UNKNOWN":"CANCELLED",artifact,mode,code);javaPublicationResult(player,record,code,extra);}
                catch(Exception writeFailure){sendCodeState(player,"JAVA_PUBLICATION_RECEIPT_UNCERTAIN",extra);}
                if(nativeStarted)try{var task=MineAgentRuntimeServices.tasks(server).get(requestedDraft.taskId()).orElse(null);if(task!=null&&task.status()==dev.mineagent.runtime.api.task.TaskStatus.RUNNING)MineAgentRuntimeServices.tasks(server).transition(task.taskId(),task.revision(),true,dev.mineagent.runtime.api.task.TaskStatus.PAUSED);}catch(Exception ignored){}
            }finally{if(!nativeStarted)MineAgentRuntimeServices.javaExtensions(server).releaseBeforeStart(ticket.id());}
        }));}catch(Exception dispatchFailure){MineAgentRuntimeServices.javaExtensions(server).releaseBeforeStart(ticket.id());try{javaPublicationResult(player,journal.finish(ticket.id(),"COMPILE_FAILED","","","JAVA_COMPILE_DISPATCH_FAILED"),"JAVA_COMPILE_DISPATCH_FAILED",java.util.Map.of());}catch(Exception writeFailure){sendCodeState(player,"JAVA_PUBLICATION_RECEIPT_UNCERTAIN");}return "JAVA_COMPILE_DISPATCH_FAILED";}
        return "";
    }
    private static String nativeCompileFailure(Throwable failure){for(int depth=0;failure!=null&&depth<16;depth++,failure=failure.getCause()){String code=java.util.Objects.toString(failure.getMessage(),"");if(code.matches("(?:NATIVE_CLASSPATH|JAVA_DEPENDENCY)_[A-Z_]{1,60}")||code.equals("JAVA_COMPILE_FAILED"))return code;}return "JAVA_COMPILE_TRANSPORT_FAILED";}
    private static java.util.Map<String, String> javaCompilationValues(java.util.Map<String, Object> payload) {
        var values = new java.util.LinkedHashMap<String, String>();
        values.put("compile.success", String.valueOf(payload.getOrDefault("success", false)));
        if(payload.get("compileContext") instanceof java.util.Map<?,?> context)for(String key:java.util.Set.of("nativeClasspath","namespace","physicalSide","mappingStatus","environment")){String value=java.util.Objects.toString(context.get(key),"");if(value.length()<=160)values.put("compile."+key,value);}
        values.put("compile.sha256", String.valueOf(payload.getOrDefault("sha256", "")));
        String activation = String.valueOf(payload.getOrDefault("activationMode", ""));
        values.put("compile.activationMode", activation);
        values.put("compile.restartRequired", Boolean.toString(
                "BOOT_EXTENSION".equals(activation) || "WORLD_REOPEN".equals(activation)));
        Object diagnosticsValue = payload.get("diagnostics");
        java.util.List<?> diagnostics = diagnosticsValue instanceof java.util.List<?> list ? list : java.util.List.of();
        int count = Math.min(20, diagnostics.size());
        values.put("compile.diagnosticCount", Integer.toString(count));
        for (int index = 0; index < count; index++) {
            Object diagnostic = diagnostics.get(index);
            Object rawMessage = diagnostic instanceof java.util.Map<?, ?> map ? map.get("message") : diagnostic;
            String message = rawMessage == null ? "" : rawMessage.toString();
            values.put("compile.diagnostic." + index, message.length() <= 512
                    ? message : message.substring(0, 512));
        }
        return values;
    }

    private static String requiredObject(java.util.Map<String, Object> values, String key) {
        String value = String.valueOf(values.getOrDefault(key, ""));
        if (value.isBlank()) {
            throw new IllegalArgumentException("missing " + key);
        }
        return value;
    }

    private static void sendCodeState(ServerPlayer player, String errorCode) {
        sendCodeState(player, errorCode, java.util.Map.of());
    }

    private static void sendCodeState(
            ServerPlayer player,
            String errorCode,
            java.util.Map<String, String> extraValues
    ) {
        sendCodeState(player,errorCode,extraValues,null);
    }
    private static void sendCodeState(ServerPlayer player,String errorCode,java.util.Map<String,String> extraValues,java.util.UUID target){
        if(player.level().getServer().getPlayerList().getPlayer(player.getUUID())!=player)return;
        boolean operator = player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
        var drafts = MineAgentRuntimeServices.codeDrafts(player.level().getServer())
                .allFor(player.getUUID(), false);
        if(target!=null){var requested=MineAgentRuntimeServices.codeDrafts(player.level().getServer()).get(target).orElseThrow(()->new IllegalStateException("STUDIO_NATIVE_DRAFT_MISSING"));if(!requested.ownerPlayerId().equals(player.getUUID())||!requested.worldId().equals(MineAgentRuntimeServices.worldId(player.level().getServer())))throw new SecurityException("STUDIO_NATIVE_DRAFT_OWNER");drafts=java.util.List.of(requested);}
        int count = Math.min(5, drafts.size());
        var values = new java.util.LinkedHashMap<String, String>();
        values.putAll(extraValues);
        values.put("worldId",MineAgentRuntimeServices.worldId(player.level().getServer()).toString());
        if(target!=null)values.put("requestedDraftId",target.toString());
        values.put("draftCount", Integer.toString(count));
        for (int index = 0; index < count; index++) {
            var draft = drafts.get(index);
            String prefix = "draft." + index + ".";
            values.put(prefix + "id", draft.draftId().toString());
            values.put(prefix + "path", draft.path());
            values.put(prefix + "source", draft.source());
            values.put(prefix + "revision", Long.toString(draft.revision()));
            values.put(prefix + "status", draft.status().name());
            var publication=MineAgentRuntimeServices.codeDrafts(player.level().getServer()).javaPublications().latest(draft.draftId()).orElse(null);
            if(publication!=null){
                values.put(prefix+"publication.kind","JAVA");
                values.put(prefix+"publication.id",publication.id().toString());values.put(prefix+"publication.revision",Long.toString(publication.revision()));
                values.put(prefix+"publication.state",publication.state());values.put(prefix+"publication.uncertain",Boolean.toString(publication.uncertain()));values.put(prefix+"publication.error",publication.error());
                values.put(prefix+"publication.nativeClasspath",publication.nativeClasspath());values.put(prefix+"publication.artifact",publication.artifact());values.put(prefix+"publication.activationMode",publication.activationMode());
                values.put(prefix+"publication.loaded",Boolean.toString(MineAgentRuntimeServices.javaExtensions(player.level().getServer()).isLoaded(publication.id())));
                values.put(prefix+"publication.owned",Boolean.toString(publication.owner().equals(player.getUUID())));
            }
            if(!draft.path().toLowerCase(java.util.Locale.ROOT).endsWith(".java")){
                var script=MineAgentRuntimeServices.codeDrafts(player.level().getServer()).scriptPublications().forPackage(draft.ownerPlayerId(),draft.packageId()).stream().filter(r->r.draft().equals(draft.draftId())&&!r.legacyStop()).findFirst().orElse(null);
                if(script!=null){var status=dev.mineagent.runtime.neoforge.ui.StudioScriptRuntime.status(player.level().getServer(),script.id());
                    values.put(prefix+"publication.kind","RHINO");values.put(prefix+"publication.id",script.id().toString());values.put(prefix+"publication.revision",Long.toString(script.revision()));
                    values.put(prefix+"publication.state",script.state());values.put(prefix+"publication.error",script.error());values.put(prefix+"publication.uncertain",Boolean.toString(script.uncertain()));values.put(prefix+"publication.activationMode","HOT_RUNTIME");
                    values.put(prefix+"publication.loaded",Boolean.toString(status.get("loaded")));values.put(prefix+"publication.suspended",Boolean.toString(status.get("suspended")));values.put(prefix+"publication.metadataPending",Boolean.toString(status.get("metadataPending")));values.put(prefix+"publication.owned",Boolean.toString(script.owner().equals(player.getUUID())));
                }
            }
            values.put(prefix + "taskId", draft.taskId().toString());
            values.put(prefix + "taskRevision", Long.toString(draft.taskRevision()));
            values.put(prefix + "packageId", draft.packageId().toString());
            values.put(prefix + "packageRevision", Long.toString(draft.packageRevision()));
            values.put(prefix + "historyCount", Integer.toString(draft.history().size()));
            if (!draft.history().isEmpty()) {
                values.put(prefix + "previousSource", draft.history().getLast());
            }
        }
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(
                player, new MineAgentPayloads.CodeState(errorCode, values));
    }

    private static void applyMemoryCommand(MineAgentPayloads.MemoryCommand payload, ServerPlayer player) {
        var server = player.level().getServer();
        var memories = MineAgentRuntimeServices.memories(server);
        boolean operator = player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
        try {
            if ("refresh".equals(payload.action())) {
                sendMemoryState(player, "");
                return;
            }
            if ("create".equals(payload.action())) {
                var kind = dev.mineagent.runtime.api.memory.MemoryKind.valueOf(required(payload.values(), "kind"));
                if (kind == dev.mineagent.runtime.api.memory.MemoryKind.WORLD_FACT && !operator) {
                    sendMemoryState(player, "FORBIDDEN");
                    return;
                }
                memories.create(player.getUUID(), kind, required(payload.values(), "key"),
                        required(payload.values(), "value"));
                sendMemoryState(player, "");
                return;
            }
            java.util.UUID id = requiredUuid(payload.values(), "memoryId");
            long revision = Long.parseLong(required(payload.values(), "expectedRevision"));
            var entry = memories.get(id).orElseThrow();
            boolean authorized = operator || entry.ownerPlayerId().equals(player.getUUID());
            var result = switch (payload.action()) {
                case "update" -> memories.update(id, revision, authorized, required(payload.values(), "value"));
                case "delete" -> memories.delete(id, revision, authorized);
                default -> throw new IllegalArgumentException("unsupported memory action");
            };
            sendMemoryState(player, result.errorCode());
        } catch (Exception failure) {
            sendMemoryState(player, "INVALID_MEMORY_COMMAND");
        }
    }

    private static void sendMemoryState(ServerPlayer player, String errorCode) {
        boolean operator = player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
        var entries = MineAgentRuntimeServices.memories(player.level().getServer())
                .visibleTo(player.getUUID(), operator);
        int count = Math.min(20, entries.size());
        var values = new java.util.LinkedHashMap<String, String>();
        values.put("memoryCount", Integer.toString(count));
        for (int index = 0; index < count; index++) {
            var entry = entries.get(index);
            String prefix = "memory." + index + ".";
            values.put(prefix + "id", entry.memoryId().toString());
            values.put(prefix + "ownerId", entry.ownerPlayerId().toString());
            values.put(prefix + "kind", entry.kind().name());
            values.put(prefix + "key", entry.key());
            values.put(prefix + "value", entry.value());
            values.put(prefix + "revision", Long.toString(entry.revision()));
        }
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(
                player, new MineAgentPayloads.MemoryState(errorCode, values));
    }

    private static void indexMods(ServerPlayer player) {
        var server = player.level().getServer();
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(
                player, new MineAgentPayloads.ModKnowledgeState("INDEXING", java.util.Map.of("modCount", "0")));
        MineAgentRuntimeServices.worker(server).indexMods(server.getServerDirectory().resolve("mods"))
                .whenComplete((results, failure) -> server.execute(() -> {
                    if (failure != null) {
                        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player,
                                new MineAgentPayloads.ModKnowledgeState(
                                        "MOD_INDEX_FAILED", java.util.Map.of("modCount", "0")));
                        return;
                    }
                    var values = new java.util.LinkedHashMap<String, String>();
                    var successful = results.stream().filter(result -> "mod.index.result".equals(result.type()))
                            .limit(30).toList();
                    values.put("modCount", Integer.toString(successful.size()));
                    for (int index = 0; index < successful.size(); index++) {
                        var result = successful.get(index).payload();
                        String prefix = "mod." + index + ".";
                        values.put(prefix + "id", String.valueOf(result.getOrDefault("modId", "")));
                        values.put(prefix + "name", String.valueOf(result.getOrDefault("displayName", "")));
                        values.put(prefix + "version", String.valueOf(result.getOrDefault("version", "")));
                        values.put(prefix + "sha256", String.valueOf(result.getOrDefault("sha256", "")));
                        values.put(prefix + "classes", String.valueOf(result.getOrDefault("classCount", 0)));
                        values.put(prefix + "sources", String.valueOf(result.getOrDefault("sourceCount", 0)));
                    }
                    net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player,
                            new MineAgentPayloads.ModKnowledgeState("", values));
                }));
    }

    private static void applyBackupCommand(MineAgentPayloads.BackupCommand payload, ServerPlayer player) {
        var server = player.level().getServer();
        boolean operator = player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
        if (!MineAgentRuntimeServices.permissions(server)
                .allowed(player.getUUID(), operator, PermissionAction.RESTORE_BACKUP)) {
            sendBackupState(player, "FORBIDDEN");
            return;
        }
        try {
            if ("refresh".equals(payload.action())) {
                sendBackupState(player, "");
                return;
            }
            if ("create".equals(payload.action())) {
                int radius = Integer.parseInt(payload.values().getOrDefault("radius", "4"));
                if (radius < 1 || radius > 16) {
                    throw new IllegalArgumentException("invalid snapshot radius");
                }
                var center = player.blockPosition();
                String dimension = player.level().dimension().identifier().toString();
                var blocks = new java.util.ArrayList<dev.mineagent.runtime.api.recovery.BlockSnapshot>();
                for (var position : net.minecraft.core.BlockPos.betweenClosed(
                        center.offset(-radius, -radius, -radius), center.offset(radius, radius, radius))) {
                    blocks.add(new dev.mineagent.runtime.api.recovery.BlockSnapshot(
                            dimension, position.getX(), position.getY(), position.getZ(),
                            net.minecraft.commands.arguments.blocks.BlockStateParser.serialize(
                                    player.level().getBlockState(position)),
                            player.level().getBlockEntity(position) == null ? ""
                                    : player.level().getBlockEntity(position)
                                    .saveWithFullMetadata(player.level().registryAccess()).toString()));
                }
                MineAgentRuntimeServices.snapshots(server).create(
                        player.getUUID(), payload.values().getOrDefault("label", "局部快照"), blocks);
                sendBackupState(player, "");
                return;
            }
            if ("restore".equals(payload.action())) {
                java.util.UUID snapshotId = requiredUuid(payload.values(), "snapshotId");
                var plan = MineAgentRuntimeServices.snapshots(server).restorePlan(snapshotId);
                var changes = captureChanges(server, plan);
                restoreBlocks(server, plan);
                MineAgentRuntimeServices.changeJournal(server).record(
                        player.getUUID(), "RESTORE_SNAPSHOT", changes);
                sendBackupState(player, "");
                return;
            }
            if ("undo_change".equals(payload.action())) {
                java.util.UUID changeId = requiredUuid(payload.values(), "changeId");
                long expectedRevision = Long.parseLong(required(payload.values(), "expectedRevision"));
                var journal = MineAgentRuntimeServices.changeJournal(server);
                var entry = journal.get(changeId).orElseThrow();
                boolean authorized = operator || entry.actorId().equals(player.getUUID());
                if (!authorized) {
                    sendBackupState(player, "FORBIDDEN");
                    return;
                }
                if (entry.revision() != expectedRevision || entry.reverted()) {
                    sendBackupState(player, entry.reverted() ? "ALREADY_REVERTED" : "STALE_REVISION");
                    return;
                }
                restoreBlocks(server, journal.revertPlan(changeId));
                var reverted = journal.markReverted(changeId, expectedRevision, true);
                sendBackupState(player, reverted.errorCode());
                return;
            }
            throw new IllegalArgumentException("unsupported backup action");
        } catch (Exception failure) {
            sendBackupState(player, "BACKUP_OPERATION_FAILED");
        }
    }

    private static java.util.List<dev.mineagent.runtime.api.recovery.BlockChange> captureChanges(
            net.minecraft.server.MinecraftServer server,
            java.util.List<dev.mineagent.runtime.api.recovery.BlockSnapshot> after
    ) {
        var changes = new java.util.ArrayList<dev.mineagent.runtime.api.recovery.BlockChange>(after.size());
        for (var target : after) {
            var level = levelFor(server, target.dimension());
            var position = new net.minecraft.core.BlockPos(target.x(), target.y(), target.z());
            var blockEntity = level.getBlockEntity(position);
            var before = new dev.mineagent.runtime.api.recovery.BlockSnapshot(
                    target.dimension(), target.x(), target.y(), target.z(),
                    net.minecraft.commands.arguments.blocks.BlockStateParser.serialize(level.getBlockState(position)),
                    blockEntity == null ? "" : blockEntity.saveWithFullMetadata(level.registryAccess()).toString());
            changes.add(new dev.mineagent.runtime.api.recovery.BlockChange(before, target));
        }
        return java.util.List.copyOf(changes);
    }

    private static void restoreBlocks(
            net.minecraft.server.MinecraftServer server,
            java.util.List<dev.mineagent.runtime.api.recovery.BlockSnapshot> plan
    ) throws Exception {
        for (var block : plan) {
            var level = levelFor(server, block.dimension());
            var state = net.minecraft.commands.arguments.blocks.BlockStateParser.parseForBlock(
                    level.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.BLOCK),
                    block.state(), false).blockState();
            var position = new net.minecraft.core.BlockPos(block.x(), block.y(), block.z());
            level.setBlockAndUpdate(position, state);
            if (!block.blockEntitySnbt().isBlank()) {
                var restored = net.minecraft.world.level.block.entity.BlockEntity.loadStatic(
                        position, state, net.minecraft.nbt.TagParser.parseCompoundFully(block.blockEntitySnbt()),
                        level.registryAccess());
                if (restored != null) {
                    level.setBlockEntity(restored);
                    restored.setChanged();
                }
            }
        }
    }

    public static void restoreBlocksForSmoke(
            net.minecraft.server.MinecraftServer server,
            java.util.List<dev.mineagent.runtime.api.recovery.BlockSnapshot> plan
    ) {
        if (!Boolean.getBoolean("mineagent.smokeTest")) {
            throw new IllegalStateException("smoke restore hook is disabled");
        }
        try {
            restoreBlocks(server, plan);
        } catch (Exception failure) {
            throw new IllegalStateException("smoke restore failed", failure);
        }
    }

    private static net.minecraft.server.level.ServerLevel levelFor(
            net.minecraft.server.MinecraftServer server,
            String dimension
    ) {
        var dimensionKey = net.minecraft.resources.ResourceKey.create(
                net.minecraft.core.registries.Registries.DIMENSION,
                net.minecraft.resources.Identifier.parse(dimension));
        var level = server.getLevel(dimensionKey);
        if (level == null) {
            throw new IllegalStateException("snapshot dimension is unavailable: " + dimension);
        }
        return level;
    }

    private static void sendBackupState(ServerPlayer player, String errorCode) {
        var snapshots = MineAgentRuntimeServices.snapshots(player.level().getServer()).all();
        int count = Math.min(10, snapshots.size());
        var values = new java.util.LinkedHashMap<String, String>();
        values.put("snapshotCount", Integer.toString(count));
        for (int index = 0; index < count; index++) {
            var snapshot = snapshots.get(index);
            String prefix = "snapshot." + index + ".";
            values.put(prefix + "id", snapshot.snapshotId().toString());
            values.put(prefix + "label", snapshot.label());
            values.put(prefix + "blocks", Integer.toString(snapshot.blocks().size()));
            values.put(prefix + "bytes", Long.toString(snapshot.estimatedBytes()));
            values.put(prefix + "expires", Long.toString(snapshot.expiresAtEpochMillis()));
        }
        var changes = MineAgentRuntimeServices.changeJournal(player.level().getServer()).all();
        int changeCount = Math.min(10, changes.size());
        values.put("changeCount", Integer.toString(changeCount));
        for (int index = 0; index < changeCount; index++) {
            var entry = changes.get(index);
            String prefix = "change." + index + ".";
            values.put(prefix + "id", entry.changeId().toString());
            values.put(prefix + "action", entry.action());
            values.put(prefix + "blocks", Integer.toString(entry.changes().size()));
            values.put(prefix + "revision", Long.toString(entry.revision()));
            values.put(prefix + "reverted", Boolean.toString(entry.reverted()));
        }
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(
                player, new MineAgentPayloads.BackupState(errorCode, values));
    }

    private static void applyMediaCommand(MineAgentPayloads.MediaCommand payload, ServerPlayer player) {
        var server = player.level().getServer();
        var media = MineAgentRuntimeServices.media(server);
        media.setExplicitlyAllowedHosts(MineAgentRuntimeServices.mediaAllowedHosts(server));
        boolean operator = player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
        boolean manager = MineAgentRuntimeServices.permissions(server)
                .allowed(player.getUUID(), operator, PermissionAction.CONTROL_PUBLIC_MEDIA);
        try {
            if ("refresh".equals(payload.action())) {
                sendMediaState(player, "");
                return;
            }
            if ("create".equals(payload.action())) {
                if (!manager) {
                    sendMediaState(player, "FORBIDDEN");
                    return;
                }
                media.add(player.getUUID(), dev.mineagent.runtime.api.media.MediaKind.valueOf(
                                payload.values().getOrDefault("kind", "URL")),
                        required(payload.values(), "title"), required(payload.values(), "url"));
                sendMediaStateAll(server, "");
                return;
            }
            java.util.UUID id = requiredUuid(payload.values(), "mediaId");
            long revision = Long.parseLong(required(payload.values(), "expectedRevision"));
            var entry = media.get(id).orElseThrow();
            boolean authorized = manager || entry.ownerPlayerId().equals(player.getUUID());
            dev.mineagent.runtime.api.media.MediaMutationResult result;
            result = switch (payload.action()) {
                case "bind_here" -> media.bind(id, revision, authorized,
                        PENDING_MEDIA_BINDINGS.getOrDefault(player.getUUID(),
                                new dev.mineagent.runtime.api.media.MediaScreenBinding(
                                        player.level().dimension().identifier().toString(),
                                        player.blockPosition().getX(), player.blockPosition().getY(),
                                        player.blockPosition().getZ()).encoded()));
                case "play" -> media.updatePlayback(id, revision, authorized, true,
                        Long.parseLong(payload.values().getOrDefault("positionMillis", "0")), 1.0);
                case "pause" -> media.updatePlayback(id, revision, authorized, false,
                        Long.parseLong(payload.values().getOrDefault("positionMillis", "0")), 1.0);
                default -> throw new IllegalArgumentException("unsupported media action");
            };
            if (result.accepted()) {
                if ("play".equals(payload.action())) {
                    MineAgentRuntimeServices.mediaCoordinator(server).start(result.entry());
                } else if ("pause".equals(payload.action())) {
                    MineAgentRuntimeServices.mediaCoordinator(server).stop(result.entry().mediaId());
                } else if ("bind_here".equals(payload.action()) && result.entry().playing()) {
                    MineAgentRuntimeServices.mediaCoordinator(server).start(result.entry());
                }
            }
            sendMediaStateAll(server, result.errorCode());
        } catch (Exception failure) {
            sendMediaState(player, "INVALID_MEDIA_COMMAND");
        }
    }

    private static void sendMediaStateAll(net.minecraft.server.MinecraftServer server, String errorCode) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (!(player instanceof dev.mineagent.runtime.neoforge.body.MineAgentPlayer)) {
                sendMediaState(player, errorCode);
            }
        }
    }

    public static void sendMediaStateTo(ServerPlayer player) {
        sendMediaState(player, "");
    }

    private static void sendMediaState(ServerPlayer player, String errorCode) {
        var entries = MineAgentRuntimeServices.media(player.level().getServer()).all();
        int count = Math.min(20, entries.size());
        var values = new java.util.LinkedHashMap<String, String>();
        values.put("mediaCount", Integer.toString(count));
        for (int index = 0; index < count; index++) {
            var entry = entries.get(index);
            String prefix = "media." + index + ".";
            values.put(prefix + "id", entry.mediaId().toString());
            values.put(prefix + "kind", entry.kind().name());
            values.put(prefix + "title", entry.title());
            values.put(prefix + "url", entry.sourceUrl());
            values.put(prefix + "binding", entry.screenBinding());
            values.put(prefix + "playing", Boolean.toString(entry.playing()));
            values.put(prefix + "positionMillis", Long.toString(entry.positionMillis()));
            values.put(prefix + "revision", Long.toString(entry.revision()));
        }
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(
                player, new MineAgentPayloads.MediaState(errorCode, values));
    }

    public static void sendMediaFrame(
            net.minecraft.server.MinecraftServer server,
            dev.mineagent.runtime.api.media.MediaEntry entry,
            long positionMillis,
            String sha256,
            byte[] bytes
    ) {
        if (bytes == null || bytes.length < 1 || bytes.length > 16 * 1024 * 1024) {
            throw new IllegalArgumentException("invalid media frame payload");
        }
        var binding = dev.mineagent.runtime.api.media.MediaScreenBinding.parse(entry.screenBinding());
        int chunks = (bytes.length + MEDIA_CHUNK_BYTES - 1) / MEDIA_CHUNK_BYTES;
        var recipients = mediaRecipients(server, binding);
        for (ServerPlayer player : recipients) {
            for (int index = 0; index < chunks; index++) {
                int start = index * MEDIA_CHUNK_BYTES;
                int end = Math.min(bytes.length, start + MEDIA_CHUNK_BYTES);
                net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player,
                        new MineAgentPayloads.MediaFrameChunk(entry.mediaId().toString(), entry.revision(),
                                binding.encoded(), positionMillis, sha256, index, chunks,
                                java.util.Arrays.copyOfRange(bytes, start, end)));
            }
        }
        if (Boolean.getBoolean("mineagent.multiplayerSmokeServer")) {
            MineAgentRuntimeMod.LOGGER.info(
                    "MINEAGENT_SMOKE_MEDIA_FRAME_SENT mediaId={} bytes={} recipients={}",
                    entry.mediaId(), bytes.length, recipients.size());
        }
    }

    public static void sendMediaAudio(
            net.minecraft.server.MinecraftServer server,
            dev.mineagent.runtime.api.media.MediaEntry entry,
            String sha256,
            byte[] bytes
    ) {
        if (bytes == null || bytes.length < 1 || bytes.length > 8 * 1024 * 1024) {
            throw new IllegalArgumentException("invalid media audio payload");
        }
        var binding = dev.mineagent.runtime.api.media.MediaScreenBinding.parse(entry.screenBinding());
        int chunks = (bytes.length + MEDIA_CHUNK_BYTES - 1) / MEDIA_CHUNK_BYTES;
        for (ServerPlayer player : mediaRecipients(server, binding)) {
            for (int index = 0; index < chunks; index++) {
                int start = index * MEDIA_CHUNK_BYTES;
                int end = Math.min(bytes.length, start + MEDIA_CHUNK_BYTES);
                net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player,
                        new MineAgentPayloads.MediaAudioChunk(entry.mediaId().toString(), sha256,
                                index, chunks, binding.x() + 0.5, binding.y() + 0.5, binding.z() + 0.5,
                                java.util.Arrays.copyOfRange(bytes, start, end)));
            }
        }
    }

    private static java.util.List<ServerPlayer> mediaRecipients(
            net.minecraft.server.MinecraftServer server,
            dev.mineagent.runtime.api.media.MediaScreenBinding binding
    ) {
        var center = new net.minecraft.world.phys.Vec3(
                binding.x() + 0.5, binding.y() + 0.5, binding.z() + 0.5);
        return server.getPlayerList().getPlayers().stream()
                .filter(player -> !(player instanceof dev.mineagent.runtime.neoforge.body.MineAgentPlayer))
                .filter(player -> player.level().dimension().identifier().toString().equals(binding.dimension()))
                .filter(player -> player.position().distanceToSqr(center) <= 64.0 * 64.0)
                .toList();
    }

    private static void applyAppearance(MineAgentPayloads.AppearanceCommand payload, ServerPlayer player) {
        var response=appearanceResponse(payload,player);
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player,response.state());
        if(response.refreshPanelSnapshot())net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player,panelSnapshot(player));
    }
    public static MineAgentPayloads.AppearanceState applyAppearanceFromUi(MineAgentPayloads.AppearanceCommand payload,ServerPlayer player){return appearanceResponse(payload,player).state();}
    public static boolean mayManageAppearance(ServerPlayer player,java.util.UUID id){
        var server=player.level().getServer();if(!server.isSameThread()||player instanceof dev.mineagent.runtime.neoforge.body.MineAgentPlayer)return false;
        return MineAgentRuntimeServices.bodies(server).definitions().stream().filter(a->a.agentId().equals(id)).anyMatch(a->operatorOrOwner(player,a));
    }
    public static java.util.Map<String,Object> readAppearanceFromUi(ServerPlayer player,java.util.UUID agentId){
        var server=player.level().getServer();if(!server.isSameThread())throw new IllegalStateException("SERVER_THREAD_REQUIRED");
        var def=MineAgentRuntimeServices.bodies(server).definitions().stream().filter(a->a.agentId().equals(agentId)).findFirst().orElseThrow();
        if(!operatorOrOwner(player,def))throw new SecurityException("FORBIDDEN");
        return appearanceSnapshot(server,agentId);
    }
    private static java.util.Map<String,Object> appearanceSnapshot(net.minecraft.server.MinecraftServer server,java.util.UUID agentId){
        var values=MineAgentRuntimeServices.config(server).snapshot().values();String prefix="agent."+agentId+".";
        var bridge=new dev.mineagent.runtime.neoforge.integration.NeoForgeYsmRuntimeBridge(server);var models=bridge.availableModels();
        var result=new java.util.LinkedHashMap<String,Object>();result.put("agentId",agentId.toString());result.put("revision",Long.parseLong(values.getOrDefault(prefix+"appearanceRevision","0")));
        result.put("model",values.getOrDefault(prefix+"model",""));result.put("texture",values.getOrDefault(prefix+"texture",""));result.put("animation",values.getOrDefault(prefix+"animation",""));
        result.put("diagnostic",bridge.diagnosticCode());result.put("runtimeAvailable",bridge.runtimeAvailable());result.put("version",bridge.version());result.put("checksumVerified",bridge.checksumVerified());
        result.put("models",models.stream().limit(128).toList());result.put("catalogTruncated",models.size()>128);result.put("nativeSelection",bridge.currentSelection(agentId).map(v->java.util.Map.of("model",v.modelId(),"texture",v.textureId(),"animation",v.animationId())).orElse(java.util.Map.of()));
        result.put("modelCount",models.size());result.put("selectionChangeRequiresRestart",false);result.put("unloadRequiresRestart",true);result.put("restartMeaning","Only uninstalling YSM itself requires a restart; changing selection is hot-applied");return result;
    }
    private static dev.mineagent.runtime.api.agent.AgentDefinition taskAppearanceAuthority(net.minecraft.server.MinecraftServer server,dev.mineagent.runtime.api.task.ManagedTask task){
        if(!server.isSameThread()||!MineAgentRuntimeServices.worldId(server).equals(task.worldId())||!dev.mineagent.runtime.core.task.TaskResultFence.current(task,MineAgentRuntimeServices.tasks(server).get(task.taskId()).orElse(null)))throw new IllegalStateException("STALE_TASK");
        var def=MineAgentRuntimeServices.bodies(server).definitions().stream().filter(d->d.agentId().equals(task.agentId())).findFirst().orElseThrow();var owner=server.getPlayerList().getPlayer(task.ownerPlayerId());
        if(!MineAgentRuntimeServices.permissions(server).canMutateAgent(def,task.ownerPlayerId(),owner!=null&&!(owner instanceof dev.mineagent.runtime.neoforge.body.MineAgentPlayer)&&owner.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER)))throw new SecurityException("APPEARANCE_TASK_FORBIDDEN");return def;
    }
    public static java.util.Map<String,Object> readTaskAppearance(net.minecraft.server.MinecraftServer server,dev.mineagent.runtime.api.task.ManagedTask task){taskAppearanceAuthority(server,task);return appearanceSnapshot(server,task.agentId());}
    public static MineAgentPayloads.AppearanceState applyTaskAppearance(net.minecraft.server.MinecraftServer server,dev.mineagent.runtime.api.task.ManagedTask task,java.util.Map<String,String> selection,java.util.UUID operation){
        var def=taskAppearanceAuthority(server,task);var payload=new MineAgentPayloads.AppearanceCommand(task.agentId().toString(),selection.get("model"),selection.get("texture"),selection.get("animation"),Long.parseLong(selection.get("expected_revision")),operation.toString());
        var config=MineAgentRuntimeServices.config(server);String key="agent."+task.agentId()+".appearanceRevision";long revision=Long.parseLong(config.snapshot().values().getOrDefault(key,"0"));
        var ledger=APPEARANCE_REQUESTS.computeIfAbsent(server,ignored->new dev.mineagent.runtime.integrations.ysm.AppearanceRequestLedger(512));
        var transaction=new dev.mineagent.runtime.integrations.ysm.AppearanceCommandTransaction(ledger);
        var fingerprint=new dev.mineagent.runtime.integrations.ysm.AppearanceRequestLedger.Fingerprint(task.agentId(),payload.expectedRevision(),payload.modelId(),payload.textureId(),payload.animationId());
        var outcome=transaction.executeAuthorized(task.ownerPlayerId(),operation,fingerprint,revision,()->Long.parseLong(config.snapshot().values().getOrDefault(key,"0")),()->{taskAppearanceAuthority(server,task);return true;},()->executeAppearanceNative(payload,server,task.agentId(),def,null)).outcome();
        return AppearanceNetworkResponse.from(task.agentId().toString(),operation.toString(),outcome,revision).state();
    }
    private static AppearanceNetworkResponse appearanceResponse(MineAgentPayloads.AppearanceCommand payload,ServerPlayer player){
        var server = player.level().getServer();
        try {
            if(!server.isSameThread()||player instanceof dev.mineagent.runtime.neoforge.body.MineAgentPlayer)throw new SecurityException("FORBIDDEN");
            java.util.UUID requestId = java.util.UUID.fromString(payload.requestId());
            java.util.UUID agentId = java.util.UUID.fromString(payload.agentId());
            var def=MineAgentRuntimeServices.bodies(server).definitions().stream().filter(a->a.agentId().equals(agentId)).findFirst().orElse(null);
            if(def==null||!operatorOrOwner(player,def))throw new SecurityException("FORBIDDEN");
            var config = MineAgentRuntimeServices.config(server);
            String revisionKey = "agent." + agentId + ".appearanceRevision";
            long currentRevision = Long.parseLong(config.snapshot().values().getOrDefault(revisionKey, "0"));
            var ledger = APPEARANCE_REQUESTS.computeIfAbsent(server,
                    ignored -> new dev.mineagent.runtime.integrations.ysm.AppearanceRequestLedger(512));
            var transaction = new dev.mineagent.runtime.integrations.ysm.AppearanceCommandTransaction(ledger);
            var fingerprint = new dev.mineagent.runtime.integrations.ysm.AppearanceRequestLedger.Fingerprint(
                    agentId, payload.expectedRevision(), payload.modelId(),
                    payload.textureId(), payload.animationId());
            var transactionResult = transaction.executeAuthorized(
                    player.getUUID(), requestId, fingerprint, currentRevision,
                    () -> Long.parseLong(config.snapshot().values().getOrDefault(
                            revisionKey, Long.toString(currentRevision))),
                    ()->operatorOrOwner(player,def),
                    () -> executeAppearanceCommand(
                            payload, player, server, agentId, currentRevision));
            var outcome = transactionResult.outcome();
            if (Boolean.getBoolean("mineagent.productionYsmSmokeTest")
                    && transactionResult.action()
                    == dev.mineagent.runtime.integrations.ysm.AppearanceRequestLedger.Action.REPLAY) {
                dev.mineagent.runtime.neoforge.MineAgentRuntimeMod.LOGGER.info(
                        "MINEAGENT_SMOKE_YSM_IDEMPOTENT_REPLAY requestId={} revision={}",
                        payload.requestId(), outcome.revision());
            }
            return AppearanceNetworkResponse.from(payload.agentId(),payload.requestId(),outcome,currentRevision);
        } catch (SecurityException denied) {
            return new AppearanceNetworkResponse(new MineAgentPayloads.AppearanceState(payload.agentId(),payload.requestId(),false,"FORBIDDEN",0),false);
        } catch (RuntimeException failure) {
            return new AppearanceNetworkResponse(new MineAgentPayloads.AppearanceState(payload.agentId(),payload.requestId(),false,"INVALID_APPEARANCE_COMMAND",0),false);
        }
    }

    private static dev.mineagent.runtime.integrations.ysm.AppearanceRequestLedger.Outcome executeAppearanceCommand(
            MineAgentPayloads.AppearanceCommand payload,
            ServerPlayer player,
            net.minecraft.server.MinecraftServer server,
            java.util.UUID agentId,
            long currentRevision
    ) {
        var agent = MineAgentRuntimeServices.bodies(server).definitions().stream()
                .filter(candidate -> candidate.agentId().equals(agentId)).findFirst().orElseThrow();
        if (!operatorOrOwner(player, agent)) {
            if (Boolean.getBoolean("mineagent.productionYsmSmokeTest")) {
                dev.mineagent.runtime.neoforge.MineAgentRuntimeMod.LOGGER.error(
                        "MINEAGENT_SMOKE_YSM_SERVER_REJECTED code=FORBIDDEN player={} owner={}",
                        player.getUUID(), agent.ownerPlayerId());
            }
            return new dev.mineagent.runtime.integrations.ysm.AppearanceRequestLedger.Outcome(
                    false, "FORBIDDEN", currentRevision);
        }
        return executeAppearanceNative(payload,server,agentId,agent,player);
    }
    private static dev.mineagent.runtime.integrations.ysm.AppearanceRequestLedger.Outcome executeAppearanceNative(
            MineAgentPayloads.AppearanceCommand payload,net.minecraft.server.MinecraftServer server,java.util.UUID agentId,
            dev.mineagent.runtime.api.agent.AgentDefinition agent,ServerPlayer smokeViewer) {
        var bodyBefore = MineAgentRuntimeServices.bodies(server).body(agentId).orElseThrow();
        float healthBefore = bodyBefore.getHealth();
        float widthBefore = bodyBefore.getBbWidth();
        float heightBefore = bodyBefore.getBbHeight();
        java.util.List<net.minecraft.world.item.ItemStack> inventoryBefore = new java.util.ArrayList<>();
        for (int slot = 0; slot < bodyBefore.getInventory().getContainerSize(); slot++) {
            inventoryBefore.add(bodyBefore.getInventory().getItem(slot).copy());
        }
        long taskCountBefore = MineAgentRuntimeServices.tasks(server).totalCount();
        var bridge = new dev.mineagent.runtime.neoforge.integration.NeoForgeYsmRuntimeBridge(server);
        var outcome = appearanceTransactions(server).execute(
                new dev.mineagent.runtime.integrations.ysm.AppearanceTransactionCoordinator.Command(
                        agentId,
                        payload.expectedRevision(),
                        new dev.mineagent.runtime.integrations.ysm.AppearanceCommitPlan.Selection(
                                payload.modelId(), payload.textureId(), payload.animationId()),
                        MineAgentRuntimeServices.worldId(server).toString(),
                        "ysm",
                        bridge.version()));
        boolean accepted = outcome.accepted();
        if(accepted){var skinConfig=MineAgentRuntimeServices.config(server);var skinSnapshot=skinConfig.snapshot();String skinPrefix="agent."+agentId+".";if(!skinSnapshot.values().getOrDefault(skinPrefix+"vanillaSkin","").isEmpty()){long skinRevision=Long.parseLong(skinSnapshot.values().getOrDefault(skinPrefix+"skinRevision","0"));if(!skinConfig.apply(new dev.mineagent.runtime.api.config.ConfigPatch(skinSnapshot.revision(),java.util.Map.of(skinPrefix+"vanillaSkin","",skinPrefix+"skinRevision",Long.toString(skinRevision+1))),true).accepted())throw new IllegalStateException("SKIN_MODE_OUTCOME_UNKNOWN");}}

        if (Boolean.getBoolean("mineagent.productionYsmSmokeTest")) {
            dev.mineagent.runtime.neoforge.MineAgentRuntimeMod.LOGGER.info(
                    "MINEAGENT_SMOKE_YSM_SERVER_RESULT accepted={} diagnostic={}",
                    accepted, outcome.errorCode());
        }

        var bodyAfter = MineAgentRuntimeServices.bodies(server).body(agentId).orElseThrow();
        boolean inventoryEqual = inventoryBefore.size() == bodyAfter.getInventory().getContainerSize();
        for (int slot = 0; inventoryEqual && slot < inventoryBefore.size(); slot++) {
            inventoryEqual = net.minecraft.world.item.ItemStack.matches(
                    inventoryBefore.get(slot), bodyAfter.getInventory().getItem(slot));
        }
        if (bodyAfter != bodyBefore || !bodyAfter.getUUID().equals(agentId)
                || !bodyAfter.ownerPlayerId().equals(agent.ownerPlayerId())
                || bodyAfter.getHealth() != healthBefore
                || bodyAfter.getBbWidth() != widthBefore || bodyAfter.getBbHeight() != heightBefore
                || !inventoryEqual || MineAgentRuntimeServices.tasks(server).totalCount() != taskCountBefore) {
            throw new IllegalStateException("YSM appearance changed authoritative AI gameplay state");
        }
        if (accepted && smokeViewer!=null && Boolean.getBoolean("mineagent.productionYsmSmokeTest")) {
            runAppearanceActionSmoke(payload, smokeViewer, server, agentId, outcome.revision(), bodyAfter);
        }
        return outcome;
    }

    private static dev.mineagent.runtime.integrations.ysm.AppearanceTransactionCoordinator
    appearanceTransactions(net.minecraft.server.MinecraftServer server) {
        synchronized (APPEARANCE_TRANSACTIONS) {
            return APPEARANCE_TRANSACTIONS.computeIfAbsent(server, ignored -> {
                var config = MineAgentRuntimeServices.config(server);
                return new dev.mineagent.runtime.integrations.ysm.AppearanceTransactionCoordinator(
                        new dev.mineagent.runtime.integrations.ysm.AppearanceTransactionCoordinator.ConfigAccess() {
                            @Override
                            public dev.mineagent.runtime.api.config.PanelSnapshot snapshot() {
                                return config.snapshot();
                            }

                            @Override
                            public dev.mineagent.runtime.api.config.ConfigPatchResult apply(ConfigPatch patch) {
                                return config.apply(patch, true);
                            }
                        },
                        new WeakServerNativeAccess(server),
                        3);
            });
        }
    }

    private static final class WeakServerNativeAccess
            implements dev.mineagent.runtime.integrations.ysm.AppearanceTransactionCoordinator.NativeAccess {
        private final java.lang.ref.WeakReference<net.minecraft.server.MinecraftServer> serverReference;

        private WeakServerNativeAccess(net.minecraft.server.MinecraftServer server) {
            this.serverReference = new java.lang.ref.WeakReference<>(
                    java.util.Objects.requireNonNull(server, "server"));
        }

        @Override
        public java.util.Optional<dev.mineagent.runtime.integrations.ysm.AppearanceCommitPlan.Selection>
        read(java.util.UUID agentId) {
            var server = serverReference.get();
            if (server == null) {
                return java.util.Optional.empty();
            }
            return new dev.mineagent.runtime.neoforge.integration.NeoForgeYsmRuntimeBridge(server)
                    .currentSelection(agentId);
        }

        @Override
        public dev.mineagent.runtime.integrations.ysm.AppearanceTransactionCoordinator.NativeResult apply(
                java.util.UUID agentId,
                dev.mineagent.runtime.integrations.ysm.AppearanceCommitPlan.Selection selection
        ) {
            var server = serverReference.get();
            if (server == null) {
                return new dev.mineagent.runtime.integrations.ysm.AppearanceTransactionCoordinator.NativeResult(
                        false, "YSM_RUNTIME_UNAVAILABLE");
            }
            var bridge = new dev.mineagent.runtime.neoforge.integration.NeoForgeYsmRuntimeBridge(server);
            var result = new dev.mineagent.runtime.integrations.ysm.YsmAppearanceAdapter(bridge)
                    .apply(new dev.mineagent.runtime.api.appearance.AppearanceRequest(
                            agentId, selection.modelId(), selection.textureId(),
                            selection.animationId(), 0));
            return new dev.mineagent.runtime.integrations.ysm.AppearanceTransactionCoordinator.NativeResult(
                    result.status() == dev.mineagent.runtime.api.appearance.AppearanceStatus.READY,
                    result.diagnosticCode());
        }
    }

    private static void runAppearanceActionSmoke(
            MineAgentPayloads.AppearanceCommand payload,
            ServerPlayer player,
            net.minecraft.server.MinecraftServer server,
            java.util.UUID agentId,
            long revision,
            dev.mineagent.runtime.neoforge.body.MineAgentPlayer bodyAfter
    ) {
        dev.mineagent.runtime.neoforge.MineAgentRuntimeMod.LOGGER.info(
                "MINEAGENT_SMOKE_YSM_SERVER_READBACK_OK model={} texture={} animation={} revision={}",
                payload.modelId(), payload.textureId(), payload.animationId(), revision);
        var observationPosition = player.position().add(player.getLookAngle().scale(4));
        if (!bodyAfter.teleportTo(bodyAfter.level(), observationPosition.x, player.getY(),
                observationPosition.z, java.util.Set.of(), bodyAfter.getYRot(), bodyAfter.getXRot(), true)) {
            throw new IllegalStateException("Could not position AI for graphical observer smoke");
        }
        int copies = 0;
        for (var level : server.getAllLevels()) {
            for (var entity : level.getAllEntities()) {
                if (entity.getUUID().equals(agentId)) {
                    copies++;
                }
            }
        }
        if (copies != 1) {
            throw new IllegalStateException("YSM lifecycle left duplicate/ghost bodies: " + copies);
        }
        bodyAfter.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES, player.position());
        var actionPos = bodyAfter.blockPosition().east();
        bodyAfter.level().setBlockAndUpdate(actionPos,
                net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
        boolean broke = MineAgentRuntimeServices.bodies(server).beginMining(agentId, actionPos);
        var target = net.minecraft.world.entity.EntityType.ZOMBIE.create(
                bodyAfter.level(), net.minecraft.world.entity.EntitySpawnReason.COMMAND);
        if (target == null) {
            throw new IllegalStateException("Could not create YSM action target");
        }
        target.snapTo(bodyAfter.getX(), bodyAfter.getY(), bodyAfter.getZ() + 2, 0, 0);
        bodyAfter.level().addFreshEntity(target);
        float targetHealth = target.getHealth();
        boolean attacked = MineAgentRuntimeServices.bodies(server).attack(agentId, target)
                && target.getHealth() < targetHealth;
        target.discard();
        bodyAfter.setShiftKeyDown(true);
        bodyAfter.setShiftKeyDown(false);
        if (!broke || !attacked) {
            throw new IllegalStateException("YSM presentation replaced a real AI action");
        }
        dev.mineagent.runtime.neoforge.MineAgentRuntimeMod.LOGGER.info(
                "MINEAGENT_SMOKE_YSM_NATIVE_ACTIONS_OK break=true attack=true sneak=true "
                        + "worldResultsAuthoritative=true");
        dev.mineagent.runtime.neoforge.MineAgentRuntimeMod.LOGGER.info(
                "MINEAGENT_SMOKE_YSM_RULE_INVARIANTS_OK identity=true inventory=true health=true "
                        + "collision=true tasks=true permissions=true duplicateBodies=1");
    }

    private static void sendAppearanceState(
            ServerPlayer player,
            String agentId,
            String requestId,
            boolean accepted,
            String code,
            long revision
    ) {
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(
                player, new MineAgentPayloads.AppearanceState(
                        agentId, requestId, accepted, code, revision));
    }

    private static void applyAgentPackageCommand(MineAgentPayloads.PackageCommand payload, ServerPlayer player) {
        var server = player.level().getServer();
        if ("refresh".equals(payload.action())) {
            sendPackageState(player, "");
            return;
        }
        boolean operator = player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
        if (!MineAgentRuntimeServices.permissions(server)
                .allowed(player.getUUID(), operator, PermissionAction.MANAGE_PACKAGES)) {
            sendPackageState(player, "FORBIDDEN");
            return;
        }
        try {
            if ("import".equals(payload.action())) {
                var imported = new com.fasterxml.jackson.databind.ObjectMapper().readValue(
                        required(payload.values(), "json"),
                        dev.mineagent.runtime.api.packages.ContentPackage.class);
                var packages = MineAgentRuntimeServices.contentPackages(server);
                var existing = packages.get(imported.packageId());
                var result = existing.isPresent()
                        ? packages.upgrade(imported, Long.parseLong(
                        payload.values().getOrDefault("expectedRevision",
                                Long.toString(existing.get().revision()))))
                        : packages.install(imported);
                sendPackageState(player, result.errorCode());
                return;
            }
            java.util.UUID id = requiredUuid(payload.values(), "packageId");
            if ("export".equals(payload.action())) {
                var contentPackage = MineAgentRuntimeServices.contentPackages(server).get(id).orElseThrow();
                String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(contentPackage);
                sendPackageState(player, "", java.util.Map.of("exportJson", json));
                return;
            }
            long revision = Long.parseLong(required(payload.values(), "expectedRevision"));
            boolean enabled = Boolean.parseBoolean(required(payload.values(), "enabled"));
            var result = MineAgentRuntimeServices.contentPackages(server).setEnabled(id, revision, enabled);
            if (result.accepted() && result.contentPackage().activationMode()
                    == dev.mineagent.runtime.api.packages.ActivationMode.HOT_RUNTIME) {
                String activationError = applyHotPackageLifecycle(server, player, result.contentPackage(), enabled);
                if (!activationError.isEmpty()) {
                    MineAgentRuntimeServices.contentPackages(server).setEnabled(
                            id, result.contentPackage().revision(), !enabled);
                    sendPackageState(player, activationError);
                    return;
                }
            }
            sendPackageState(player, result.errorCode());
        } catch (Exception failure) {
            sendPackageState(player, "INVALID_PACKAGE_COMMAND");
        }
    }

    private static String applyHotPackageLifecycle(
            net.minecraft.server.MinecraftServer server,
            ServerPlayer actor,
            dev.mineagent.runtime.api.packages.ContentPackage contentPackage,
            boolean enabled
    ) {
        try {
            if (contentPackage.permissions().contains("client.code")) {
                return "";
            }
            if (contentPackage.permissions().contains("runtime.java")) {
                String[] lines = contentPackage.source().split("\\R");
                if (lines.length < 2 || !lines[0].startsWith("java-jar:")
                        || !lines[1].startsWith("entrypoint:")) {
                    return "JAVA_PACKAGE_METADATA_INVALID";
                }
                String hash = lines[0].substring("java-jar:".length()).strip();
                String entrypoint = lines[1].substring("entrypoint:".length()).strip();
                var path = MineAgentRuntimeServices.worker(server).contentPath(hash);
                if (enabled) {
                    MineAgentRuntimeServices.javaExtensions(server).load(
                            path, hash, entrypoint, java.util.Map.of("server", server));
                } else {
                    MineAgentRuntimeServices.javaExtensions(server).unload(path);
                }
                return "";
            }
            if (enabled) {
                var activation = MineAgentRuntimeServices.packages(server).activate(
                        new dev.mineagent.runtime.api.packages.RuntimePackageCandidate(
                                contentPackage.packageId(), contentPackage.revision(), java.util.UUID.randomUUID(),
                                0, contentPackage.activationMode(), "main.js", contentPackage.source()),
                        0, java.util.Map.of("host",
                                new dev.mineagent.runtime.neoforge.scripting.MineAgentScriptHost(
                                        server, actor.getUUID())));
                return activation.activated() ? "" : activation.errorCode();
            }
            MineAgentRuntimeServices.packages(server).unload(contentPackage.packageId());
            return "";
        } catch (Exception failure) {
            MineAgentRuntimeMod.LOGGER.warn("Content package lifecycle failed for {}",
                    contentPackage.packageId(), failure);
            return "PACKAGE_LIFECYCLE_FAILED";
        }
    }

    public static void sendPackageStateTo(ServerPlayer player) {
        sendPackageState(player, "");
    }

    private static void sendPackageState(ServerPlayer player, String errorCode) {
        sendPackageState(player, errorCode, java.util.Map.of());
    }

    private static void sendPackageState(
            ServerPlayer player,
            String errorCode,
            java.util.Map<String, String> extraValues
    ) {
        var packages = MineAgentRuntimeServices.contentPackages(player.level().getServer()).all();
        int count = Math.min(20, packages.size());
        var values = new java.util.LinkedHashMap<String, String>();
        values.putAll(extraValues);
        values.put("packageCount", Integer.toString(count));
        for (int index = 0; index < count; index++) {
            var contentPackage = packages.get(index);
            String prefix = "package." + index + ".";
            values.put(prefix + "id", contentPackage.packageId().toString());
            values.put(prefix + "name", contentPackage.name());
            values.put(prefix + "version", contentPackage.version());
            values.put(prefix + "mode", contentPackage.activationMode().name());
            values.put(prefix + "enabled", Boolean.toString(contentPackage.enabled()));
            values.put(prefix + "revision", Long.toString(contentPackage.revision()));
            values.put(prefix + "sha256", contentPackage.sha256());
            values.put(prefix + "dependencies", Integer.toString(contentPackage.dependencies().size()));
            boolean clientCode = contentPackage.permissions().contains("client.code");
            values.put(prefix + "clientCode", Boolean.toString(clientCode));
            values.put(prefix + "source", clientCode ? contentPackage.source() : "");
            values.put(prefix + "signature", clientCode ? contentPackage.signature() : "");
        }
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(
                player, new MineAgentPayloads.PackageState(errorCode, values));
    }

    private static void applyPermissionCommand(MineAgentPayloads.PermissionCommand payload, ServerPlayer player) {
        var server = player.level().getServer();
        boolean operator = player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
        var permissions = MineAgentRuntimeServices.permissions(server);
        if ("refresh".equals(payload.action())) {
            sendPermissionState(player, "");
            return;
        }
        if (!permissions.allowed(player.getUUID(), operator, PermissionAction.MANAGE_PERMISSIONS)) {
            sendPermissionState(player, "FORBIDDEN");
            return;
        }
        try {
            java.util.UUID target = requiredUuid(payload.values(), "playerId");
            PermissionAction action = PermissionAction.valueOf(required(payload.values(), "permission"));
            boolean enabled = Boolean.parseBoolean(required(payload.values(), "enabled"));
            var actions = new java.util.HashSet<>(permissions.trustedActions(target));
            if (enabled) {
                actions.add(action);
            } else {
                actions.remove(action);
            }

            String persistedValue = actions.stream().map(Enum::name).sorted()
                    .collect(java.util.stream.Collectors.joining(","));
            var config = MineAgentRuntimeServices.config(server);
            var snapshot = config.snapshot();
            var result = config.apply(new ConfigPatch(snapshot.revision(), java.util.Map.of(
                    "permission.player." + target, persistedValue)), true);
            if (!result.accepted()) {
                sendPermissionState(player, result.errorCode());
                return;
            }
            permissions.setTrustedActions(target,actions);
            sendPermissionState(player, "");
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player, panelSnapshot(player));
        } catch (RuntimeException failure) {
            sendPermissionState(player, "INVALID_PERMISSION_COMMAND");
        }
    }

    private static void sendPermissionState(ServerPlayer player, String errorCode) {
        var server = player.level().getServer();
        boolean operator = player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
        var values = new java.util.LinkedHashMap<String, String>();
        values.put("operator", Boolean.toString(operator));
        if (operator) {
            var trusted = MineAgentRuntimeServices.permissions(server).allTrustedActions();
            values.put("playerCount", Integer.toString(Math.min(20, trusted.size())));
            int index = 0;
            for (var entry : trusted.entrySet()) {
                if (index >= 20) {
                    break;
                }
                values.put("player." + index + ".id", entry.getKey().toString());
                values.put("player." + index + ".actions", entry.getValue().stream().map(Enum::name).sorted()
                        .collect(java.util.stream.Collectors.joining(",")));
                index++;
            }
        }
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(
                player, new MineAgentPayloads.PermissionState(errorCode, values));
    }

    private static void audit(
            net.minecraft.server.MinecraftServer server,
            String actor,
            String action,
            String target,
            String payload
    ) {
        try {
            MineAgentRuntimeServices.audit(server).record(actor, action, target, payload);
        } catch (Exception failure) {
            MineAgentRuntimeMod.LOGGER.warn("Failed to persist MineAgent audit event {}", action, failure);
        }
    }

    private static void sendDiagnostics(ServerPlayer player) {
        if (!player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER)) {
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player,
                    new MineAgentPayloads.DiagnosticsState("FORBIDDEN", java.util.Map.of("eventCount", "0")));
            return;
        }
        try {
            var events = MineAgentRuntimeServices.audit(player.level().getServer()).recent(10);
            var values = new java.util.LinkedHashMap<String, String>();
            values.put("eventCount", Integer.toString(events.size()));
            values.put("threadCount", Integer.toString(Thread.getAllStackTraces().size()));
            long usedMemory = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
            values.put("usedMemoryBytes", Long.toString(usedMemory));
            for (int index = 0; index < events.size(); index++) {
                var event = events.get(index);
                String prefix = "event." + index + ".";
                values.put(prefix + "action", event.action());
                values.put(prefix + "actor", event.actor());
                values.put(prefix + "target", event.target());
                values.put(prefix + "payload", event.payload());
                values.put(prefix + "created", Long.toString(event.createdAtEpochMillis()));
            }
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player,
                    new MineAgentPayloads.DiagnosticsState("", values));
        } catch (Exception failure) {
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player,
                    new MineAgentPayloads.DiagnosticsState(
                            "DIAGNOSTICS_FAILED", java.util.Map.of("eventCount", "0")));
        }
    }
}
