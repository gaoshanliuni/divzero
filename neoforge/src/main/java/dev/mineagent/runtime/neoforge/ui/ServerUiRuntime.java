package dev.mineagent.runtime.neoforge.ui;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mineagent.runtime.api.decision.*;
import dev.mineagent.runtime.api.permission.PermissionAction;
import dev.mineagent.runtime.api.ui.UiProtocol.*;
import dev.mineagent.runtime.core.ui.UiSessionService;
import dev.mineagent.runtime.core.ui.ScoreUiBridge;
import dev.mineagent.runtime.core.scoreboard.*;
import dev.mineagent.runtime.neoforge.MineAgentRuntimeServices;
import dev.mineagent.runtime.neoforge.body.MineAgentPlayer;
import dev.mineagent.runtime.neoforge.network.MineAgentNetwork;
import dev.mineagent.runtime.neoforge.network.MineAgentPayloads;
import dev.mineagent.runtime.neoforge.network.UiPayloads;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import java.time.Clock;
import java.util.*;

/** Common/server side only. Actor is resolved here, not supplied by the webpage or copied from the viewer's OP state. */
@EventBusSubscriber(modid = "mineagent_runtime")
public final class ServerUiRuntime {
    public static final UUID TRUSTED_SHELL_PACKAGE = UUID.fromString("00000000-0000-0000-0000-000000000002");
    public static final Set<String> SHELL_CAPABILITIES=Set.of("feedback.read","delivery.list","delivery.transfer","delivery.lifecycle","shell.read", "conversation.read", "conversation.write", "persona.manage", "settings.manage", "appearance.read", "appearance.apply", "appearance.decide", "chat.send", "chat.refresh", "decision.submit", "decision.defer", "decision.resume", "decision.cancelTask", "worldui.open", "worldui.agent", "worldui.chunk", "worldui.release", "world.inspect", "world.activate", "world.disable", "world.restoreOff", "world.list", "world.moveInspect", "world.move",
                                "agent.manage", "task.manage", "package.generate", "package.cancel", "package.preview", "package.chunk", "package.release", "scoreview.bind", "container.open", "container.agent", "scoreview.worldFront", "scoreview.worldMove", "scoreview.worldDetach", "scoreview.worldDelete", "package.open", "package.hud", "package.hudLease", "package.restore", "package.patchSubmit","package.patchApply","package.patchRollback","package.patchCancel","package.patchPreview","package.patchRebuild", "package.worldPatchSubmit", "package.worldPatchInspect", "package.worldPatchApply", "package.worldPatchRollback", "package.worldPatchCancel", "ui.statePermit", "ui.presentationPermit", "ui.bindPage", "ui.takeoverActivate", "ui.delegate", "ui.stop");
    private static final Map<MinecraftServer, ServerUiRuntime> RUNTIMES = new IdentityHashMap<>();
    private final MinecraftServer server;
    private final ObjectMapper json = new ObjectMapper();
    private final UiSessionService sessions;
    private final ServerContainerRuntime containers;
    private final ServerWorldUiRuntime worldUi;
    private final ServerContentDeliveries deliveries;
    private final ServerUiAgentTasks uiAgents;
    private final DesktopWindowPlans desktopPlans;
    private final dev.mineagent.runtime.core.ui.UiTakeoverService takeovers=new dev.mineagent.runtime.core.ui.UiTakeoverService();
    private final Map<UUID, Long> rateWindows = new HashMap<>();
    private final Map<UUID, Integer> rateCounts = new HashMap<>();
    private final dev.mineagent.runtime.core.ui.UiCandidateViews candidateViews=new dev.mineagent.runtime.core.ui.UiCandidateViews(Clock.systemUTC());
    public static synchronized ServerUiRuntime get(MinecraftServer server) { return RUNTIMES.computeIfAbsent(server, ServerUiRuntime::new); }
    private ServerUiRuntime(MinecraftServer server) {
        this.server = server;
        desktopPlans=new DesktopWindowPlans(server);
        sessions = new UiSessionService(MineAgentRuntimeServices.worldId(server), Clock.systemUTC(), this::authorize, 128, 4096);
        containers=new ServerContainerRuntime(server);
        worldUi=new ServerWorldUiRuntime(server,sessions);
        deliveries=new ServerContentDeliveries(server,sessions);
        uiAgents=new ServerUiAgentTasks(server,this,sessions);
    }
    public UiSessionService sessions() { return sessions; }
    public ServerWorldUiRuntime worldUi(){return worldUi;}
    public ServerContentDeliveries deliveries(){return deliveries;}
    public ServerUiAgentTasks uiAgents(){return uiAgents;}
    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("3");
        registrar.playToServer(UiPayloads.Command.TYPE, UiPayloads.Command.CODEC,
                (p, context) -> context.enqueueWork(() -> {var player=(ServerPlayer)context.player();if(!dev.mineagent.runtime.neoforge.WorldIdentityRuntime.notifyIfPending(player)){context.reply(new UiPayloads.Event(p.requestId(),"error","{\"code\":\"WORLD_IDENTITY_NOT_READY\"}"));return;}get(player.level().getServer()).handle(player,p);}));
        registrar.playToClient(UiPayloads.Event.TYPE, UiPayloads.Event.CODEC);
        registrar.playToClient(dev.mineagent.runtime.neoforge.network.WorldBoardPayload.TYPE,dev.mineagent.runtime.neoforge.network.WorldBoardPayload.CODEC);
    }
    private dev.mineagent.runtime.api.packages.RuntimePackage nativeCompatibilityTarget(ServerPlayer viewer,Request request){
        var binding=sessions.get(viewer.getUUID(),request.sessionId()).orElseThrow().binding();
        if(!binding.ownerPackageId().equals(TRUSTED_SHELL_PACKAGE)||!binding.viewId().equals("runtime-shell")||!binding.entryPath().equals("trusted/shell")||binding.actorKind()!=ActorKind.PLAYER||!binding.actorId().equals(viewer.getUUID())||binding.taskId()!=null||binding.preview())throw new SecurityException("NATIVE_COMPATIBILITY_PERMISSION");
        var args=request.arguments();if(!args.keySet().equals(Set.of("packageId","packageRevision","canonical","environment","pinRevision","approve","confirmed"))||!Set.of("true","false").contains(args.get("approve")))throw new IllegalArgumentException("NATIVE_COMPATIBILITY_INVALID");
        var runtime=ServerPackageRuntime.get(server);var pkg=runtime.ownedPackage(viewer.getUUID(),UUID.fromString(args.get("packageId")),Long.parseLong(args.get("packageRevision"))).orElseThrow(()->new SecurityException("PACKAGE_NOT_OWNED"));
        if(!pkg.canonicalSha256().equals(args.get("canonical")))throw new IllegalStateException("NATIVE_COMPATIBILITY_STALE");
        runtime.nativeCompatibility().authorizeChange(viewer,pkg,"true".equals(args.get("approve")),args.get("environment"),"true".equals(args.get("confirmed")));return pkg;
    }
    private void restoreScope(ServerPlayer viewer,Request request){
        var binding=sessions.get(viewer.getUUID(),request.sessionId()).orElseThrow().binding();
        if(!binding.ownerPackageId().equals(TRUSTED_SHELL_PACKAGE)||!binding.viewId().equals("runtime-shell")||!binding.entryPath().equals("trusted/shell")||binding.actorKind()!=ActorKind.PLAYER||!binding.actorId().equals(viewer.getUUID())||binding.taskId()!=null||binding.preview())throw new SecurityException("RESTORE_SCOPE_DENIED");
    }
    private static String restoreError(Exception failure){String code=Objects.toString(failure.getMessage(),"");return dev.mineagent.runtime.core.packages.WorldActivationLedger.RESUME_ERRORS.contains(code)||dev.mineagent.runtime.core.packages.NativeCompatibilityPolicy.ERROR_CODES.contains(code)?code:"RESTORE_REQUEST_INVALID";}
    private static String nativeCompatibilityError(Exception failure){
        String code=Objects.toString(failure.getMessage(),"");return dev.mineagent.runtime.core.packages.NativeCompatibilityPolicy.ERROR_CODES.contains(code)?code:"NATIVE_COMPATIBILITY_INVALID";
    }
    private Code authorize(Session session) {
        Binding binding = session.binding();
        ServerPlayer viewer = server.getPlayerList().getPlayer(binding.viewerPlayerId());
        if (viewer == null || viewer instanceof MineAgentPlayer) return Code.VIEW_NOT_RENDERED;
        if (!binding.worldId().equals(MineAgentRuntimeServices.worldId(server))) return Code.STALE_VIEW;
        if(dev.mineagent.runtime.api.ui.DeliveryProtocol.bound(binding))return deliveries.authorize(session);
        if(dev.mineagent.runtime.api.ui.WorldUiProtocol.bound(binding)){var access=worldUi.authorize(session);return access==Code.OK&&binding.actorKind()==ActorKind.AGENT?uiAgents.authorize(binding,viewer):access;}
        if(dev.mineagent.runtime.api.ui.ContainerProtocol.bound(binding)){Code nativeAccess=containers.authorize(binding,viewer);return nativeAccess==Code.OK&&binding.actorKind()==ActorKind.AGENT?uiAgents.authorize(binding,viewer):nativeAccess;}
        if (binding.ownerPackageId().equals(TRUSTED_SHELL_PACKAGE)) {
            if (binding.actorKind() != ActorKind.PLAYER || !binding.actorId().equals(binding.viewerPlayerId())) return Code.PERMISSION_DENIED;
            return MineAgentRuntimeServices.permissions(server).allowed(viewer.getUUID(),
                    viewer.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER), PermissionAction.CHAT) ? Code.OK : Code.PERMISSION_DENIED;
        }
        if(dev.mineagent.runtime.api.ui.UiInteractionScope.pageOnly(binding)){
            var owned=ServerPackageRuntime.get(server).ownedPackage(viewer.getUUID(),binding.ownerPackageId(),binding.packageRevision());
            if(owned.isEmpty()||ServerPackageRuntime.uiEntry(owned.get()).filter(binding.entryPath()::equals).isEmpty())return Code.STALE_PACKAGE;
            if(binding.actorKind()==ActorKind.AGENT)return uiAgents.authorize(binding,viewer);
            return binding.actorId().equals(viewer.getUUID())&&binding.taskId()==null&&mayStartUi(viewer)?Code.OK:Code.PERMISSION_DENIED;
        }
        // AGENT bindings require explicit delegation, never inherited viewer OP or inventory access.
        if(binding.preview()){
            var operation=candidateViews.operation(session);if(operation.isEmpty())return Code.STALE_PACKAGE;
            if(ServerPackageRuntime.get(server).candidate(binding.viewerPlayerId(),operation.orElseThrow()).filter(p->p.packageId().equals(binding.ownerPackageId())&&p.revision()==binding.packageRevision()).isEmpty())return Code.STALE_PACKAGE;
        }else if (ServerPackageRuntime.get(server).ownedPackage(viewer.getUUID(), binding.ownerPackageId(), binding.packageRevision()).isEmpty()) return Code.STALE_PACKAGE;
        try { scoreBridge().requireTarget(binding, audience(viewer));
            return binding.actorKind()==ActorKind.AGENT?uiAgents.authorize(binding,viewer)
                    :binding.actorId().equals(binding.viewerPlayerId())?Code.OK:Code.PERMISSION_DENIED; }
        catch (SecurityException denied) { return Code.PERMISSION_DENIED; }
        catch (RuntimeException missing) { return Code.TARGET_NOT_FOUND; }
    }
    private void handle(ServerPlayer viewer, UiPayloads.Command packet) {
        if (!server.isSameThread()) throw new IllegalStateException("SERVER_THREAD_REQUIRED");
        if (viewer instanceof MineAgentPlayer) return;
        long now = System.currentTimeMillis();
        if (now - rateWindows.getOrDefault(viewer.getUUID(), 0L) >= 1000) { rateWindows.put(viewer.getUUID(), now); rateCounts.put(viewer.getUUID(), 0); }
        int count = rateCounts.merge(viewer.getUUID(), 1, Integer::sum);
        if (count > 80) { send(viewer, packet.requestId(), "error", Map.of("code", "RATE_LIMITED")); return; }
        try {
            if(UiMultiplayerSmokeServer.enabled()&&packet.channel().equals("uiMultiFixture")){UiMultiplayerSmokeServer.handle(viewer,packet);return;}
            if(DeliverySmokeServer.enabled()&&packet.channel().equals("deliveryFixture")){DeliverySmokeServer.handle(viewer,packet);return;}
            if(SharedMultiplayerSmokeServer.enabled()&&packet.channel().equals("sharedMultiFixture")){SharedMultiplayerSmokeServer.handle(viewer,packet);return;}
            if(TaskAuthoritySmokeServer.enabled()&&packet.channel().equals("taskAuthorityFixture")){TaskAuthoritySmokeServer.handle(viewer,packet);return;}
            if(UiWorldSwitchSmokeServer.stage()!=0&&packet.channel().equals("uiWorldFixture")){UiWorldSwitchSmokeServer.handle(viewer,packet);return;}
            if(packet.channel().equals("hudContext")){
                if(!MineAgentRuntimeServices.permissions(server).allowed(viewer.getUUID(),viewer.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER),PermissionAction.CHAT))throw new SecurityException("HUD_CONTEXT_DENIED");
                send(viewer,packet.requestId(),"hudContext",Map.of("worldId",MineAgentRuntimeServices.worldId(server),"viewerId",viewer.getUUID()));return;
            }
            if(packet.channel().equals("uiAgentReply")){
                var reply=json.readValue(packet.json(),dev.mineagent.runtime.api.ui.UiAgentRpc.Reply.class);
                if(!reply.requestId().equals(packet.requestId()))throw new IllegalArgumentException("UI_RPC_ID");uiAgents.reply(viewer.getUUID(),reply);return;
            }
            if(packet.channel().equals("uiAgentInterrupt")){
                var data=json.readTree(packet.json());uiAgents.interrupt(viewer.getUUID(),UUID.fromString(data.path("sessionId").asText()),dev.mineagent.runtime.api.ui.UiInterruptSignal.normalize(data.path("signal").asText()));return;
            }
            if (packet.channel().equals("openShell")) {
                ServerPackageRuntime.disconnect(server, viewer.getUUID());
                dev.mineagent.runtime.neoforge.compile.NativeLiveClassAccess.clear(viewer.getUUID());
                for (Session old : sessions.list(viewer.getUUID())) if (old.binding().ownerPackageId().equals(TRUSTED_SHELL_PACKAGE))
                    sessions.close(viewer.getUUID(), old.sessionId());
                Session opened = sessions.open(new Binding("runtime-shell", TRUSTED_SHELL_PACKAGE, 1, "1", "trusted/shell",
                        MineAgentRuntimeServices.worldId(server), viewer.getUUID(), viewer.getUUID(), ActorKind.PLAYER, null, 0,
                        "", false, SHELL_CAPABILITIES),
                        true, true, 1_800_000);
                send(viewer, packet.requestId(), "session", opened); return;
            }
            Request request = json.readValue(packet.json(), Request.class);
            if (!request.operationId().equals(packet.requestId())) throw new IllegalArgumentException("OPERATION_ID");
            if (packet.channel().equals("rendered")) {
                send(viewer, packet.requestId(), "receipt", sessions.rendered(viewer.getUUID(), request.sessionId(), request.pageGeneration()));
                return;
            }
            if (packet.channel().equals("navigateContent")) {
                var current = sessions.get(viewer.getUUID(), request.sessionId()).orElseThrow();
                if(current.binding().actorKind()==ActorKind.AGENT){uiAgents.interrupt(viewer.getUUID(),current.sessionId());throw new IllegalStateException("AGENT_NAVIGATION_INTERRUPTED");}
                if (current.binding().ownerPackageId().equals(TRUSTED_SHELL_PACKAGE)
                        || current.pageGeneration() != request.pageGeneration() || current.controlEpoch() != request.controlEpoch())
                    throw new IllegalArgumentException("STALE_CONTENT_NAVIGATION");
                var next = sessions.navigate(viewer.getUUID(), request.sessionId());
                if(dev.mineagent.runtime.api.ui.DeliveryProtocol.bound(next.binding()))deliveries.navigated(viewer.getUUID(),next);
                send(viewer, packet.requestId(), "receipt", new Receipt(request.operationId(), Code.ACCEPTED, Map.of("session", json.writeValueAsString(next)))); return;
            }
            if (packet.channel().equals("close")) {
                var deliverySession=sessions.get(viewer.getUUID(),request.sessionId());if(deliverySession.isPresent()&&dev.mineagent.runtime.api.ui.DeliveryProtocol.bound(deliverySession.get().binding()))deliveries.closedSession(viewer.getUUID(),deliverySession.get());
                worldUi.closeSession(viewer.getUUID(),request.sessionId());
                uiAgents.interrupt(viewer.getUUID(),request.sessionId(),"SERVER_VIEW_CLOSED");
                containers.closeSession(viewer.getUUID(),request.sessionId());
                sessions.get(viewer.getUUID(),request.sessionId()).ifPresent(s->containers.close(s.binding()));
                takeovers.close(request.sessionId());
                boolean shellClosing=sessions.get(viewer.getUUID(),request.sessionId())
                        .map(s->s.binding().ownerPackageId().equals(TRUSTED_SHELL_PACKAGE)).orElse(false);
                send(viewer, packet.requestId(), "receipt", sessions.close(viewer.getUUID(), request.sessionId()));
                candidateViews.close(request.sessionId());
                if(shellClosing){dev.mineagent.runtime.neoforge.compile.NativeLiveClassAccess.clear(viewer.getUUID());ServerConversations.disconnectIfPresent(server,viewer);worldUi.disconnect(viewer.getUUID());containers.closeViewer(viewer.getUUID());uiAgents.disconnect(viewer.getUUID());ServerPackageRuntime.disconnect(server, viewer.getUUID());} return;
            }
            if (packet.channel().equals("interrupt")) {
                send(viewer, packet.requestId(), "session", sessions.interrupt(viewer.getUUID(), request.sessionId())); return;
            }
            if (!packet.channel().equals("command")) throw new IllegalArgumentException("UI_CHANNEL");
            if(Set.of("studio.read","studio.write").contains(request.action())){javaStudio(viewer,packet.requestId(),request);return;}
            if(Set.of("preferences.read","preferences.write").contains(request.action())){preferences(viewer,packet.requestId(),request);return;}
            if(Set.of("package.assetsRead","package.assetsWrite").contains(request.action())){assets(viewer,packet.requestId(),request);return;}
            if(Set.of("boot.read","boot.change").contains(request.action())){boot(viewer,packet.requestId(),request);return;}
            if(Set.of("nativeApi.read","nativeApi.refresh").contains(request.action())){nativeApi(viewer,packet.requestId(),request);return;}
            if(request.action().equals("package.resourcePrepare")){
                Code access=sessions.checkRead(viewer.getUUID(),request,dev.mineagent.runtime.api.ui.DeliveryProtocol.capability(request.action()));if(access!=Code.OK){send(viewer,packet.requestId(),"receipt",Receipt.of(request.operationId(),access));return;}
                try{restoreScope(viewer,request);var a=request.arguments();if(!a.keySet().equals(Set.of("packageId","packageRevision","canonical")))throw new IllegalArgumentException("RESOURCE_PACK_ARGUMENTS");ServerPackageRuntime.get(server).resourceTarget(viewer.getUUID(),UUID.fromString(a.get("packageId")),Long.parseLong(a.get("packageRevision")),a.get("canonical"));}catch(Exception failure){send(viewer,packet.requestId(),"receipt",new Receipt(request.operationId(),Code.FAILED,Map.of("errorCode","RESOURCE_PACK_SOURCE_UNAVAILABLE")));return;}
            }
            if(request.action().equals("package.clientScriptPrepare")){
                Code access=sessions.checkRead(viewer.getUUID(),request,dev.mineagent.runtime.api.ui.DeliveryProtocol.capability(request.action()));if(access!=Code.OK){send(viewer,packet.requestId(),"receipt",Receipt.of(request.operationId(),access));return;}
                try{restoreScope(viewer,request);var a=request.arguments();if(!a.keySet().equals(Set.of("packageId","packageRevision","canonical")))throw new IllegalArgumentException("CLIENT_SCRIPT_ARGUMENTS");ServerPackageRuntime.get(server).clientScriptTarget(viewer.getUUID(),UUID.fromString(a.get("packageId")),Long.parseLong(a.get("packageRevision")),a.get("canonical"));}catch(Exception failure){send(viewer,packet.requestId(),"receipt",new Receipt(request.operationId(),Code.FAILED,Map.of("errorCode","CLIENT_SCRIPT_SOURCE_UNAVAILABLE")));return;}
            }
            if(request.action().equals("package.clientJavaPrepare")){
                Code access=sessions.checkRead(viewer.getUUID(),request,dev.mineagent.runtime.api.ui.DeliveryProtocol.capability(request.action()));if(access!=Code.OK){send(viewer,packet.requestId(),"receipt",Receipt.of(request.operationId(),access));return;}
                try{restoreScope(viewer,request);var a=request.arguments();if(!a.keySet().equals(Set.of("packageId","packageRevision","canonical")))throw new IllegalArgumentException("CLIENT_JAVA_ARGUMENTS");ServerPackageRuntime.get(server).clientJavaTarget(viewer.getUUID(),UUID.fromString(a.get("packageId")),Long.parseLong(a.get("packageRevision")),a.get("canonical"));}catch(Exception failure){send(viewer,packet.requestId(),"receipt",new Receipt(request.operationId(),Code.FAILED,Map.of("errorCode","CLIENT_JAVA_SOURCE_UNAVAILABLE")));return;}
            }
            if(request.action().equals("dataPack.change")){
                Code access=sessions.checkRead(viewer.getUUID(),request,dev.mineagent.runtime.api.ui.DeliveryProtocol.capability(request.action()));if(access!=Code.OK){send(viewer,packet.requestId(),"receipt",Receipt.of(request.operationId(),access));return;}
                try{restoreScope(viewer,request);dev.mineagent.runtime.neoforge.content.NativeDataPackRuntime.get(server).authorize(viewer,request.operationId(),request.arguments());}
                catch(Exception failure){send(viewer,packet.requestId(),"receipt",new Receipt(request.operationId(),failure instanceof SecurityException?Code.PERMISSION_DENIED:Code.FAILED,Map.of("errorCode",dev.mineagent.runtime.neoforge.content.NativeDataPackRuntime.code(failure))));return;}
            }
            if(request.action().equals("world.restoreResume")){
                Code access=sessions.checkRead(viewer.getUUID(),request,dev.mineagent.runtime.api.ui.DeliveryProtocol.capability(request.action()));if(access!=Code.OK){send(viewer,packet.requestId(),"receipt",Receipt.of(request.operationId(),access));return;}
                try{restoreScope(viewer,request);dev.mineagent.runtime.neoforge.content.WorldContentRuntime.get(server).authorizeResume(viewer,request.operationId(),request.arguments());}
                catch(Exception invalid){send(viewer,packet.requestId(),"receipt",new Receipt(request.operationId(),invalid instanceof SecurityException?Code.PERMISSION_DENIED:Code.FAILED,Map.of("errorCode",restoreError(invalid))));return;}
            }
            if(request.action().equals("world.compatibility")){
                Code access=sessions.checkRead(viewer.getUUID(),request,dev.mineagent.runtime.api.ui.DeliveryProtocol.capability(request.action()));if(access!=Code.OK){send(viewer,packet.requestId(),"receipt",Receipt.of(request.operationId(),access));return;}
                try{nativeCompatibilityTarget(viewer,request);}
                catch(SecurityException denied){send(viewer,packet.requestId(),"receipt",new Receipt(request.operationId(),Code.PERMISSION_DENIED,Map.of("errorCode",nativeCompatibilityError(denied))));return;}
                catch(IllegalArgumentException|IllegalStateException invalid){send(viewer,packet.requestId(),"receipt",new Receipt(request.operationId(),Code.FAILED,Map.of("errorCode",nativeCompatibilityError(invalid))));return;}
            }
            if(Set.of("conversation.read","conversation.write").contains(request.action())){
                Code access=sessions.checkRead(viewer.getUUID(),request,request.action());if(access!=Code.OK){send(viewer,packet.requestId(),"receipt",Receipt.of(request.operationId(),access));return;}
                try{ServerConversations.get(server).authorize(viewer,request.arguments());}catch(SecurityException denied){send(viewer,packet.requestId(),"receipt",Receipt.of(request.operationId(),Code.PERMISSION_DENIED));return;}
            }
            if(Set.of("persona.read","persona.save").contains(request.action())&&!dev.mineagent.runtime.core.agent.AgentPersonaService.mayEdit(personaAgent(UUID.fromString(request.arguments().get("agentId"))),viewer.getUUID(),viewer.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))){send(viewer,packet.requestId(),"receipt",Receipt.of(request.operationId(),Code.PERMISSION_DENIED));return;}
            if(Set.of("package.patchSubmit","package.patchApply","package.patchRollback","package.patchCancel","package.patchRebuild").contains(request.action())){
                var runtime=ServerPackageRuntime.get(server);UUID agent=request.action().equals("package.patchSubmit")?UUID.fromString(request.arguments().get("agentId")):runtime.patchJob(viewer.getUUID(),UUID.fromString(request.arguments().get("operationId"))).orElseThrow().agentId();
                if(!runtime.mayGenerate(viewer.getUUID(),agent)||request.action().equals("package.patchRebuild")&&!runtime.mayRebuildUi(viewer,UUID.fromString(request.arguments().get("operationId")))){send(viewer,packet.requestId(),"receipt",Receipt.of(request.operationId(),Code.PERMISSION_DENIED));return;}
            }
            if(request.action().equals("world.move")&&!dev.mineagent.runtime.neoforge.content.WorldContentRuntime.get(server).mayMoveInstance(viewer,UUID.fromString(request.arguments().get("activationId")),UUID.fromString(request.arguments().get("instanceId")),request.arguments().get("canonical"))){send(viewer,packet.requestId(),"receipt",Receipt.of(request.operationId(),Code.PERMISSION_DENIED));return;}
            if(request.action().startsWith("package.worldPatch")){
                var runtime=ServerPackageRuntime.get(server);UUID agent=request.action().equals("package.worldPatchSubmit")?UUID.fromString(request.arguments().get("agentId")):runtime.worldPatchJob(viewer.getUUID(),UUID.fromString(request.arguments().get("operationId"))).orElseThrow().agentId();
                if(!runtime.mayWorldPatch(viewer.getUUID(),agent)){send(viewer,packet.requestId(),"receipt",Receipt.of(request.operationId(),Code.PERMISSION_DENIED));return;}
            }
            if(Set.of("appearance.read","appearance.apply","appearance.decide").contains(request.action())&&!MineAgentNetwork.mayManageAppearance(viewer,UUID.fromString(request.arguments().get("agentId")))){
                send(viewer,packet.requestId(),"receipt",Receipt.of(request.operationId(),Code.PERMISSION_DENIED));return;
            }
            if(Set.of("package.generate","package.cancel").contains(request.action())){
                Code access=sessions.checkRead(viewer.getUUID(),request,request.action());if(access!=Code.OK){send(viewer,packet.requestId(),"receipt",Receipt.of(request.operationId(),access));return;}
                var runtime=ServerPackageRuntime.get(server);UUID agent=request.action().equals("package.generate")?dev.mineagent.runtime.core.packages.PackageGenerationRequest.parse(request.arguments()).agentId():runtime.generation(viewer.getUUID(),UUID.fromString(request.arguments().get("operationId"))).orElseThrow().agentId();
                if(!runtime.mayGenerate(viewer.getUUID(),agent)){send(viewer,packet.requestId(),"receipt",Receipt.of(request.operationId(),Code.PERMISSION_DENIED));return;}
            }
        if(request.action().equals("task.start")){
                if(!dev.mineagent.runtime.neoforge.task.ServerTaskStart.allowed(viewer,UUID.fromString(request.arguments().get("agentId")))){send(viewer,packet.requestId(),"receipt",Receipt.of(request.operationId(),Code.PERMISSION_DENIED));return;}
                if(dev.mineagent.runtime.neoforge.task.ServerTaskStart.revokedReplay(viewer,request.operationId())){send(viewer,packet.requestId(),"receipt",new Receipt(request.operationId(),Code.STATE_CONFLICT,Map.of("errorCode","TASK_REPLAN_REQUIRED")));return;}
            }
            if(Set.of("decision.submit","decision.resume").contains(request.action())||request.action().equals("chat.send")&&request.arguments().containsKey("decisionId")){
                String denied=MineAgentNetwork.decisionMutationDenial(viewer,UUID.fromString(request.arguments().get("decisionId")));if(!denied.isEmpty()){send(viewer,packet.requestId(),"receipt",new Receipt(request.operationId(),Code.PERMISSION_DENIED,Map.of("errorCode",denied)));return;}
            }
            if(request.action().equals("feedback.submit")){
                Code access=sessions.checkRead(viewer.getUUID(),request,"feedback.submit");if(access!=Code.OK){send(viewer,packet.requestId(),"receipt",Receipt.of(request.operationId(),access));return;}
                try{deliveries.feedback().authorizeRequest(viewer,request);}catch(SecurityException denied){send(viewer,packet.requestId(),"receipt",Receipt.of(request.operationId(),Code.PERMISSION_DENIED));return;}catch(IllegalArgumentException invalid){send(viewer,packet.requestId(),"receipt",new Receipt(request.operationId(),Code.INVALID_REQUEST,Map.of("errorCode","FEEDBACK_ARGUMENTS_INVALID")));return;}
            }
            if (Set.of("desktop.read","delivery.manageRead","task.historyRead","task.schedulesRead","task.eventsRead","feedback.read","feedback.history","feedback.list","feedback.stateRead","delivery.list","delivery.read","delivery.chunk","delivery.codeChunk","delivery.codeRelease","shell.read", "settings.read", "conversation.read", "persona.read", "appearance.read", "package.chunk", "package.resourceChunk", "package.resourceRelease", "package.clientScriptChunk", "package.clientScriptRelease", "package.clientJavaChunk", "package.clientJavaRelease", "scoreview.read", "container.read", "ui.statePermit", "ui.presentationPermit", "world.inspect", "world.restoreRead", "dataPack.read", "world.list", "world.moveInspect", "package.worldPatchInspect", "worldui.read", "worldui.chunk").contains(request.action())) {
                Code access = sessions.checkRead(viewer.getUUID(), request, dev.mineagent.runtime.api.ui.DeliveryProtocol.capability(request.action()));
                if (access != Code.OK) { send(viewer, packet.requestId(), "receipt", Receipt.of(request.operationId(), access)); return; }
                try{send(viewer, packet.requestId(), "receipt", new Receipt(request.operationId(), Code.OBSERVED, execute(viewer, request)));}
                catch(SecurityException denied){send(viewer,packet.requestId(),"receipt",Receipt.of(request.operationId(),Code.PERMISSION_DENIED));}
                catch(IllegalStateException failure){if(!"DECISION_CARD_BUDGET".equals(failure.getMessage()))throw failure;send(viewer,packet.requestId(),"receipt",new Receipt(request.operationId(),Code.FAILED,Map.of("errorCode","DECISION_CARD_BUDGET")));}
                return;
            }
            if(request.action().equals("agent.manage")){try{ServerAgentManagement.authorize(viewer,request.arguments());}catch(SecurityException denied){send(viewer,packet.requestId(),"receipt",Receipt.of(request.operationId(),Code.PERMISSION_DENIED));return;}}
            if(request.action().equals("task.replan")){var source=MineAgentRuntimeServices.tasks(server).get(UUID.fromString(request.arguments().get("sourceTaskId"))).orElseThrow();if(!source.ownerPlayerId().equals(viewer.getUUID())||!dev.mineagent.runtime.neoforge.task.ServerTaskStart.allowed(viewer,source.agentId()))throw new SecurityException("TASK_OWNER");if(dev.mineagent.runtime.neoforge.task.ServerTaskStart.revokedReplay(viewer,request.operationId())){send(viewer,packet.requestId(),"receipt",new Receipt(request.operationId(),Code.STATE_CONFLICT,Map.of("errorCode","TASK_REPLAN_REQUIRED")));return;}}
            if(request.action().equals("settings.write")){try{ServerSettings.authorize(viewer,request.arguments());}catch(SecurityException denied){send(viewer,packet.requestId(),"receipt",Receipt.of(request.operationId(),Code.PERMISSION_DENIED));return;}}
            Receipt receipt = sessions.begin(viewer.getUUID(), request, dev.mineagent.runtime.api.ui.DeliveryProtocol.capability(request.action()), !request.action().equals("shell.read"));
            if (receipt.code() != Code.OK) { send(viewer, packet.requestId(), "receipt", receipt); return; }
            try {
                if(request.action().equals("package.resourcePrepare")){resourcePrepare(viewer,request);return;}
                if(request.action().equals("package.clientScriptPrepare")){clientScriptPrepare(viewer,request);return;}
                if(request.action().equals("package.clientJavaPrepare")){clientJavaPrepare(viewer,request);return;}
                if(request.action().equals("delivery.accept")){deliveries.accept(viewer,request);return;}
                if(request.action().equals("delivery.codeAccept")){deliveries.acceptCode(viewer,request);return;}
                if(request.action().equals("worldui.open")){worldUi.open(viewer,request);return;}
                if(request.action().equals("package.patchRebuild")){
                    ServerPackageRuntime.get(server).rebuildUi(viewer,UUID.fromString(request.arguments().get("operationId")),request.arguments().get("rawHash"),"true".equals(request.arguments().get("confirmed"))).whenComplete((job,error)->server.execute(()->{
                        try{var rebuiltReceipt=error==null?sessions.complete(request,Code.APPLIED,Map.of("state",job.state(),"canonicalSha256",job.candidate().canonicalSha256(),"executionMode","LOCAL_REBUILD_NO_PROVIDER")):sessions.complete(request,Code.FAILED,Map.of("errorCode","UI_PATCH_REBUILD_FAILED"));send(viewer,packet.requestId(),"receipt",rebuiltReceipt);}catch(Exception ignored){/* The local build remains journaled if its UI session closed. */}
                    }));return;
                }
                if (Set.of("package.preview", "package.open", "container.open", "container.agent", "package.hud", "package.restore","package.patchPreview").contains(request.action())) { preview(viewer, request); return; }
                Map<String, String> result = execute(viewer, request);
                Code code = result.containsKey("errorCode") && !result.get("errorCode").isEmpty() ? Code.FAILED
                        : request.action().equals("shell.read") ? Code.OBSERVED : (request.action().startsWith("chat.") || request.action().equals("conversation.write")&&"send".equals(request.arguments().get("kind"))) || Set.of("dataPack.change","world.restoreResume","feedback.submit","worldui.agent","task.start","task.replan","package.generate","package.patchSubmit","package.worldPatchSubmit","ui.delegate","ui.bindPage").contains(request.action()) ? Code.ACCEPTED : Code.APPLIED;
                receipt = sessions.complete(request, code, result);
            } catch (SecurityException denied) {
                receipt = sessions.complete(request, Code.PERMISSION_DENIED, Map.of("errorCode", "FORBIDDEN"));
            } catch (IllegalStateException unavailable) {
                String code = unavailable.getMessage();
                if(dev.mineagent.runtime.core.packages.NativeCompatibilityPolicy.ERROR_CODES.contains(java.util.Objects.toString(code,""))){send(viewer,packet.requestId(),"receipt",sessions.complete(request,Code.FAILED,Map.of("errorCode",code)));return;}
                receipt = sessions.complete(request, Set.of("STALE_VIEW_REVISION","WORLD_UI_STATE_CONFLICT").contains(code)?Code.STATE_CONFLICT:Code.FAILED, Map.of("errorCode", code != null && Set.of(
                        "AGENT_LIMIT","AGENT_NAME_EXISTS","AGENT_NAME_INVALID","AGENT_OPERATION_REUSED","BODY_UNAVAILABLE","BODY_SERVER_STOPPING","AGENT_REQUEST_BUDGET","AGENT_CREATION_REMOVED","AGENT_NOT_FOUND","STALE_AGENT_REVISION","AGENT_MUTATION_REJECTED","TASK_REPLAN_REQUIRED","TASK_REPLAN_NOT_AVAILABLE","STALE_TASK_REVISION","TASK_REPLAN_NOT_PAUSED","TASK_REPLAN_BUDGET","TASK_REPLAN_CONFLICT","GENERATION_BUSY", "GENERATION_LEDGER_FULL", "PROVIDER_NOT_CONFIGURED", "PACKAGE_STORE_UNAVAILABLE", "UI_TRANSFER_BUSY", "ACTIVATION_REQUIRES_LIFECYCLE", "ACTIVATION_LEDGER_FULL", "RESTORE_CONTRACT_MISSING", "STALE_ACTIVATION",
                        "WORLD_UI_AGENT_BODY_BUSY","WORLD_UI_AGENT_OUT_OF_REACH","WORLD_UI_AGENT_NOT_OFFERED","WORLD_UI_AGENT_ENTRY_CHANGED","WORLD_UI_EXPECTATION_ALREADY_PRESENT","UI_DELEGATION_BUDGET","UI_TASK_HISTORY_BUDGET","UI_CONTROL_BUSY","VIEW_NOT_RENDERED",
                        "INSTANCE_MOVE_STALE","INSTANCE_MOVE_NOT_READY","INSTANCE_MOVE_CODE_CHANGED","INSTANCE_MOVE_RECOVERY_REQUIRED","INSTANCE_MOVE_BLOCK_GRID_REQUIRED","INSTANCE_MOVE_BLOCK_ADAPTER_REQUIRED","INSTANCE_MOVE_PART_CHANGED","INSTANCE_MOVE_PART_BUDGET","INSTANCE_MOVE_OCCUPIED","INSTANCE_MOVE_SELF_COLLISION","INSTANCE_MOVE_COLLISION_BUDGET","INSTANCE_MOVE_OUT_OF_WORLD","INSTANCE_MOVE_CHUNK_UNLOADED","INSTANCE_MOVE_NATIVE_FAILED","INSTANCE_MOVE_NATIVE_MISMATCH","INSTANCE_MOVE_STATE_CONFLICT","INSTANCE_MOVE_HANDLER_FAILED","INSTANCE_MOVE_LEDGER_FULL","INSTANCE_MOVE_UNKNOWN",
                        "STALE_VIEW_REVISION", "VIEW_ID_REUSED", "SCORE_VIEW_DISABLED", "SCORE_VIEW_MISSING", "SCORE_SOURCE_MISSING","STALE_PACKAGE","CONTAINER_STATE_CONFLICT","CONTAINER_CLOSED","CONTAINER_OUTCOME_UNKNOWN","CONTAINER_STATE_BUDGET","HUD_ENTRYPOINT_MISSING","WORLD_BOARD_DIMENSION_UNAVAILABLE","WORLD_BOARD_BUDGET","UI_ENTRYPOINT_MISSING","UI_PATCH_BUSY","UI_PATCH_NOT_READY","UI_PATCH_ROLLBACK_CONFLICT","WORLD_UI_STATE_CONFLICT","WORLD_UI_OUTCOME_UNKNOWN","WORLD_UI_NO_REPLY","WORLD_UI_HANDLER_FAILED","WORLD_UI_LEDGER_FULL","WORLD_UI_ALREADY_OPEN","WORLD_UI_CALLBACK_BUDGET").contains(code) ? code : "UI_SERVICE_UNAVAILABLE"));
            } catch (Exception failure) {
                receipt = sessions.complete(request, Code.INVALID_REQUEST, Map.of("errorCode", "INVALID_UI_COMMAND"));
            }
            send(viewer, packet.requestId(), "receipt", receipt);
        } catch (Exception failure) { send(viewer, packet.requestId(), "error", Map.of("code", "INVALID_UI_REQUEST")); }
    }
    private Map<String, String> execute(ServerPlayer viewer, Request request) throws Exception {
        if(Set.of("desktop.start","desktop.read","desktop.cancel").contains(request.action())){
            restoreScope(viewer,request);
            return desktopPlans.handle(viewer,request.action().substring("desktop.".length()),UUID.fromString(request.arguments().get("operationId")),request.arguments(),()->sessions.checkRead(viewer.getUUID(),request,"task.manage")==Code.OK);
        }
        Map<String, String> args = request.arguments();
        if(request.action().equals("settings.read"))return Map.of("state",json.writeValueAsString(ServerSettings.read(viewer)));
        if(request.action().equals("settings.write"))return ServerSettings.write(viewer,args);
        if(Set.of("conversation.read","conversation.write").contains(request.action())){
            if(!MineAgentRuntimeServices.permissions(server).allowed(viewer.getUUID(),viewer.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER),dev.mineagent.runtime.api.permission.PermissionAction.CHAT))throw new SecurityException("CONVERSATION_FORBIDDEN");
            try{return request.action().equals("conversation.read")?ServerConversations.get(server).read(viewer,args):ServerConversations.get(server).write(viewer,request.operationId(),args);}
            catch(IllegalArgumentException|IllegalStateException invalid){String code=invalid.getMessage();if(code==null||!code.matches("(?:CONVERSATION_[A-Z_]+|AUDIT_[A-Z_]+|ASR_[A-Z0-9_]+|STALE_CONVERSATION_REVISION|STALE_CONVERSATION_FOCUS|STALE_MESSAGE_REVISION|OPERATION_ID_REUSED)"))code="INVALID_CONVERSATION_REQUEST";return Map.of("state","{}","errorCode",code);}
        }
        if(Set.of("persona.read","persona.save").contains(request.action())){
            var agent=personaAgent(UUID.fromString(args.get("agentId")));var service=MineAgentRuntimeServices.personas(server);boolean op=viewer.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
            if(request.action().equals("persona.read"))return Map.of("state",json.writeValueAsString(service.read(agent,viewer.getUUID(),op)));
            var result=service.save(agent,viewer.getUUID(),op,request.operationId(),Long.parseLong(args.get("expectedRevision")),args.get("text"));
            return Map.of("state",json.writeValueAsString(result),"errorCode",result.accepted()?"":result.code());
        }
        if(request.action().equals("world.list"))return Map.of("page",json.writeValueAsString(dev.mineagent.runtime.neoforge.content.WorldContentRuntime.get(server).movementPage(viewer,Integer.parseInt(args.getOrDefault("page","0")))));
        if(request.action().equals("world.moveInspect"))return Map.of("instance",json.writeValueAsString(dev.mineagent.runtime.neoforge.content.WorldContentRuntime.get(server).inspectMovement(viewer,UUID.fromString(args.get("activationId")))));
        if(request.action().equals("world.move")){
            var source=json.readValue(args.get("source"),dev.mineagent.runtime.api.packages.RuntimeInstanceLocation.class);
            var target=new dev.mineagent.runtime.api.packages.RuntimeInstanceLocation(source.dimension(),Double.parseDouble(args.get("x")),Double.parseDouble(args.get("y")),Double.parseDouble(args.get("z")),source.yaw(),source.pitch());
            var input=new dev.mineagent.runtime.core.packages.WorldInstanceMoveJournal.Input(request.operationId(),viewer.getUUID(),UUID.fromString(args.get("activationId")),UUID.fromString(args.get("instanceId")),args.get("canonical"),Long.parseLong(args.get("activationRevision")),source,target);
            var result=dev.mineagent.runtime.neoforge.content.WorldContentRuntime.get(server).move(viewer,input,"true".equals(args.get("confirmed")));
            return Map.of("move",json.writeValueAsString(result),"errorCode",result.receipt().state().equals("APPLIED")?"":result.receipt().error().isEmpty()?"INSTANCE_MOVE_UNKNOWN":result.receipt().error());
        }
        if(request.action().equals("feedback.submit"))return deliveries.feedback().submit(viewer,request);
        if(request.action().equals("feedback.read"))return deliveries.feedback().read(viewer,request);
        if(request.action().equals("feedback.stateRead"))return deliveries.feedback().readState(viewer,request);
        if(request.action().equals("feedback.history"))return deliveries.feedback().history(viewer,request);
        if(request.action().equals("feedback.list"))return deliveries.feedback().list(viewer,request);
        if(request.action().equals("delivery.list"))return deliveries.inbox(viewer,Integer.parseInt(args.get("offset")));
        if(request.action().equals("delivery.read"))return deliveries.read(viewer,sessions.get(viewer.getUUID(),request.sessionId()).orElseThrow());
        if(request.action().equals("delivery.chunk"))return deliveries.chunk(viewer,request.sessionId(),UUID.fromString(args.get("transferId")),Integer.parseInt(args.get("offset")));
        if(request.action().equals("delivery.release")){deliveries.release(viewer);return Map.of();}
        if(request.action().equals("delivery.codeChunk"))return deliveries.codeChunk(viewer,request.sessionId(),UUID.fromString(args.get("transferId")),Integer.parseInt(args.get("offset")));
        if(request.action().equals("delivery.codeRelease")){deliveries.release(viewer);return Map.of();}
        if(request.action().equals("delivery.codeState"))return deliveries.codeState(viewer,request);
        if(Set.of("delivery.received","delivery.painted","delivery.closed","delivery.reject","delivery.visibility").contains(request.action()))return deliveries.receipt(viewer,request.action(),args);
        if(Set.of("delivery.manageRead","delivery.manageWrite").contains(request.action())){
            var binding=sessions.get(viewer.getUUID(),request.sessionId()).orElseThrow().binding();
            if(!binding.ownerPackageId().equals(TRUSTED_SHELL_PACKAGE)||!binding.viewId().equals("runtime-shell")||!binding.entryPath().equals("trusted/shell")||binding.actorKind()!=ActorKind.PLAYER||!binding.actorId().equals(viewer.getUUID())||binding.taskId()!=null||binding.preview())throw new SecurityException("DELIVERY_MANAGEMENT_TRUSTED_SHELL_REQUIRED");
            if(request.action().equals("delivery.manageRead")?!Set.of("list","detail").contains(args.get("kind")):!"write".equals(args.get("kind")))throw new IllegalArgumentException("DELIVERY_MANAGEMENT_CHANNEL");
            try{return deliveries.manage(viewer,request.operationId(),args);}catch(IllegalStateException|IllegalArgumentException failure){String code=java.util.Objects.toString(failure.getMessage(),"");return Map.of("errorCode",Set.of("DELIVERY_MANAGEMENT_STALE","DELIVERY_ARCHIVE_NOT_SETTLED","DELIVERY_OPERATION_REUSED","RETENTION_TOTAL_ROW_BUDGET","RETENTION_PAYLOAD_BYTE_BUDGET","DELIVERY_OPERATION_BUDGET").contains(code)?code:"DELIVERY_MANAGEMENT_FAILED");}
        }
        if(request.action().equals("task.historyRead")){
            var binding=sessions.get(viewer.getUUID(),request.sessionId()).orElseThrow().binding();
            if(!binding.ownerPackageId().equals(TRUSTED_SHELL_PACKAGE)||!binding.viewId().equals("runtime-shell")||!binding.entryPath().equals("trusted/shell")||binding.actorKind()!=ActorKind.PLAYER||!binding.actorId().equals(viewer.getUUID())||binding.taskId()!=null||binding.preview())throw new SecurityException("TASK_HISTORY_TRUSTED_SHELL_REQUIRED");
            return dev.mineagent.runtime.neoforge.task.ServerTaskHistory.read(viewer,args);
        }
        if(Set.of("task.schedulesRead","task.schedulesWrite").contains(request.action())){
            var binding=sessions.get(viewer.getUUID(),request.sessionId()).orElseThrow().binding();
            if(!binding.ownerPackageId().equals(TRUSTED_SHELL_PACKAGE)||!binding.viewId().equals("runtime-shell")||!binding.entryPath().equals("trusted/shell")||binding.actorKind()!=ActorKind.PLAYER||!binding.actorId().equals(viewer.getUUID())||binding.taskId()!=null||binding.preview())throw new SecurityException("SCHEDULE_MANAGEMENT_TRUSTED_SHELL_REQUIRED");
            if(request.action().equals("task.schedulesRead")?!Set.of("list","detail","deliveries").contains(args.get("kind")):!"write".equals(args.get("kind")))throw new IllegalArgumentException("SCHEDULE_MANAGEMENT_CHANNEL");
            try{return MineAgentRuntimeServices.schedules(server).manage(viewer,request.operationId(),args);}
            catch(IllegalStateException|IllegalArgumentException failure){String code=failure.getMessage()==null?"":failure.getMessage();return Map.of("errorCode",Set.of("SCHEDULE_STALE","SCHEDULE_EXPIRED","SCHEDULE_EXHAUSTED","SCHEDULE_MISSING","SCHEDULE_OPERATION_REUSED","SCHEDULE_STORAGE_BUDGET","RETENTION_TOTAL_ROW_BUDGET","SCHEDULE_ARCHIVE_REQUIRES_STOPPED","SCHEDULE_CONDITION_UNAVAILABLE").contains(code)?code:"SCHEDULE_MANAGEMENT_REQUEST_FAILED");}
            catch(SecurityException denied){return Map.of("errorCode","SCHEDULE_RESUME_AUTHORITY_REQUIRED".equals(denied.getMessage())?"SCHEDULE_RESUME_AUTHORITY_REQUIRED":"FORBIDDEN");}
        }
        if(Set.of("task.eventsRead","task.eventsWrite").contains(request.action())){
            var session=sessions.get(viewer.getUUID(),request.sessionId()).orElseThrow();var binding=session.binding();
            if(!binding.ownerPackageId().equals(TRUSTED_SHELL_PACKAGE)||!binding.viewId().equals("runtime-shell")||!binding.entryPath().equals("trusted/shell")||binding.actorKind()!=ActorKind.PLAYER||!binding.actorId().equals(viewer.getUUID())||binding.taskId()!=null||binding.preview())throw new SecurityException("EVENT_MANAGEMENT_TRUSTED_SHELL_REQUIRED");
            if(request.action().equals("task.eventsRead")?!Set.of("list","detail").contains(args.get("kind")):!Set.of("state","archive").contains(args.get("kind")))throw new IllegalArgumentException("EVENT_MANAGEMENT_CHANNEL");
            try{return MineAgentRuntimeServices.events(server).manage(viewer,request.operationId(),args);}
            catch(IllegalStateException|IllegalArgumentException failure){String code=failure.getMessage()==null?"":failure.getMessage();return Map.of("errorCode",Set.of("EVENT_SUBSCRIPTION_STALE","EVENT_SUBSCRIPTION_EXPIRED","EVENT_CONSUMER_BUDGET_EXHAUSTED","EVENT_RESUME_SOURCE_UNAVAILABLE","FEEDBACK_CONSUMER_ALREADY_BOUND","EVENT_OPERATION_REUSED","EVENT_STORAGE_BUDGET","EVENT_SUBSCRIPTION_MISSING","EVENT_HISTORY_ITEM_BUDGET","RETENTION_TOTAL_ROW_BUDGET","EVENT_ARCHIVE_REQUIRES_STOPPED").contains(code)?code:"EVENT_MANAGEMENT_REQUEST_FAILED");}
            catch(SecurityException denied){return Map.of("errorCode","EVENT_RESUME_AUTHORITY_REQUIRED".equals(denied.getMessage())?"EVENT_RESUME_AUTHORITY_REQUIRED":"FORBIDDEN");}
        }
        if(request.action().equals("worldui.read"))return worldUi.read(viewer,sessions.get(viewer.getUUID(),request.sessionId()).orElseThrow(),request.operationId(),args);
        if(request.action().equals("worldui.action"))return worldUi.act(viewer,sessions.get(viewer.getUUID(),request.sessionId()).orElseThrow(),request);
        if(request.action().equals("worldui.chunk"))return worldUi.chunk(viewer,request.sessionId(),UUID.fromString(args.get("transferId")),Integer.parseInt(args.get("offset")));
        if(request.action().equals("worldui.release")){worldUi.release(viewer.getUUID());return Map.of();}
        if(request.action().equals("package.worldPatchSubmit")){
            var s=ServerPackageRuntime.get(server).worldPatch(viewer,UUID.fromString(args.get("agentId")),request.operationId(),UUID.fromString(args.get("packageId")),Long.parseLong(args.get("packageRevision")),args.get("prompt"));var j=s.job();return Map.of("operationId",j.operationId().toString(),"taskId",j.taskId().toString(),"state",j.state(),"duplicate",Boolean.toString(s.duplicate()),"errorCode",j.errorCode());
        }
        if(request.action().equals("package.worldPatchInspect"))return ServerPackageRuntime.get(server).inspectWorldPatch(viewer.getUUID(),UUID.fromString(args.get("operationId")),Integer.parseInt(args.getOrDefault("offset","0")),args.getOrDefault("path",""),Integer.parseInt(args.getOrDefault("sourceOffset","0")),Boolean.parseBoolean(args.getOrDefault("before","false")));
        if(Set.of("package.worldPatchApply","package.worldPatchRollback","package.worldPatchCancel").contains(request.action())){
            String action=request.action().equals("package.worldPatchApply")?"apply":request.action().equals("package.worldPatchRollback")?"rollback":"cancel";
            var j=ServerPackageRuntime.get(server).worldPatchAction(viewer.getUUID(),UUID.fromString(args.get("operationId")),action,"true".equals(args.get("confirmed")),args.getOrDefault("canonicalSha256",""));return Map.of("state",j.state(),"headRevision",Long.toString(j.headRevision()),"errorCode",j.errorCode(),"nativeExecuted","false");
        }
        if(request.action().equals("appearance.read"))return Map.of("state",json.writeValueAsString(MineAgentNetwork.readAppearanceFromUi(viewer,UUID.fromString(args.get("agentId")))));
        if(request.action().equals("appearance.decide"))return Map.of("decision",json.writeValueAsString(MineAgentNetwork.openAppearanceDecisionFromUi(viewer,UUID.fromString(args.get("agentId")),Long.parseLong(args.get("expectedRevision")),args.get("model"),args.get("texture"),args.get("animation"),request.operationId())));
        if(request.action().equals("appearance.apply")){
            var response=MineAgentNetwork.applyAppearanceFromUi(new MineAgentPayloads.AppearanceCommand(args.get("agentId"),args.get("model"),args.get("texture"),args.get("animation"),Long.parseLong(args.get("expectedRevision")),request.operationId().toString()),viewer);
            return Map.of("state",json.writeValueAsString(response),"errorCode",response.errorCode());
        }
        if(request.action().equals("world.inspect")){
            UUID pkg=UUID.fromString(args.get("packageId"));long revision=Long.parseLong(args.get("packageRevision"));
            var pack=ServerPackageRuntime.get(server).ownedPackage(viewer.getUUID(),pkg,revision).orElseThrow(()->new SecurityException("PACKAGE_NOT_OWNED"));
            var a=viewer.blockPosition();var runtime=dev.mineagent.runtime.neoforge.content.WorldContentRuntime.get(server);
            return Map.of("definitions",json.writeValueAsString(pack.definitions().values().stream().map(d->Map.of("id",d.definitionId(),"name",d.name().substring(0,Math.min(80,d.name().length())),"kind",d.kind(),"restoreAvailable",pack.entrypoints().get(d.entrypointId())!=null&&pack.entrypoints().get(d.entrypointId()).equals(pack.entrypoints().get(d.entrypointId()+".restore")))).toList()),
                    "activationMode",pack.activationMode().name(),"anchor",json.writeValueAsString(Map.of("dimension",viewer.level().dimension().identifier().toString(),"x",a.getX()+2,"y",a.getY(),"z",a.getZ())),
                    "activations",json.writeValueAsString(runtime.list(viewer.getUUID()).stream().filter(r->r.packageId().equals(pkg)).skip(Long.parseLong(args.getOrDefault("offset","0"))).limit(16).toList()));
        }
        if(Set.of("dataPack.read","dataPack.change").contains(request.action())){
            try{restoreScope(viewer,request);var runtime=dev.mineagent.runtime.neoforge.content.NativeDataPackRuntime.get(server);return Map.of("state",json.writeValueAsString(request.action().equals("dataPack.read")?runtime.read(viewer,args):runtime.submit(viewer,request.operationId(),args)),"executionMode",request.action().equals("dataPack.read")?"OBSERVE_REAL_DATA_PACK_STATE":"EXPLICIT_DATA_RELOAD_JOB");}
            catch(Exception failure){return Map.of("errorCode",dev.mineagent.runtime.neoforge.content.NativeDataPackRuntime.code(failure));}
        }
        if(Set.of("world.restoreRead","world.restoreResume").contains(request.action())){
            try{restoreScope(viewer,request);var runtime=dev.mineagent.runtime.neoforge.content.WorldContentRuntime.get(server);
                if(request.action().equals("world.restoreRead")){if(!args.keySet().equals(Set.of("activationId")))throw new IllegalArgumentException("RESTORE_REQUEST_INVALID");return Map.of("state",json.writeValueAsString(runtime.inspectRestore(viewer,UUID.fromString(args.get("activationId")))),"executionMode","READ_ONLY_NO_RESTORE");}
                var result=runtime.resume(viewer,request.operationId(),args);return Map.of("state",json.writeValueAsString(result),"executionMode","EXPLICIT_RESTORE_QUEUED_NOT_YET_EXECUTED");
            }catch(Exception failure){return Map.of("errorCode",restoreError(failure));}
        }
        if(request.action().equals("world.compatibility")){
            var pkg=nativeCompatibilityTarget(viewer,request);var result=ServerPackageRuntime.get(server).nativeCompatibility().change(viewer,pkg,request.operationId(),Long.parseLong(args.get("pinRevision")),"true".equals(args.get("approve")),args.get("environment"),"true".equals(args.get("confirmed")));
            return Map.of("state",json.writeValueAsString(result),"executionMode","LEGACY_ENV_ATTESTATION_NO_CODE_EXECUTION");
        }
        if(request.action().equals("world.activate")){
            var location=new dev.mineagent.runtime.api.packages.RuntimeInstanceLocation(args.get("dimension"),Double.parseDouble(args.get("x")),Double.parseDouble(args.get("y")),Double.parseDouble(args.get("z")),0,0);
            var runtime=dev.mineagent.runtime.neoforge.content.WorldContentRuntime.get(server);
            var result=runtime.activate(viewer,request.operationId(),UUID.fromString(args.get("packageId")),Long.parseLong(args.get("packageRevision")),UUID.fromString(args.get("definitionId")),location,"true".equals(args.get("confirmed")),"true".equals(args.get("autoRestore")));
            return Map.of("activation",json.writeValueAsString(result),"verifiedBlocks",Integer.toString(runtime.verifiedBlocks(result.instanceId())),"verifiedObjects",Integer.toString(runtime.verifiedObjects(result.instanceId())),"errorCode",result.error());
        }
        if(request.action().equals("world.disable")){var result=dev.mineagent.runtime.neoforge.content.WorldContentRuntime.get(server).disable(viewer,UUID.fromString(args.get("activationId")));return Map.of("activation",json.writeValueAsString(result),"errorCode",result.error());}
        if(request.action().equals("world.restoreOff")){var result=dev.mineagent.runtime.neoforge.content.WorldContentRuntime.get(server).revokeRestore(viewer,UUID.fromString(args.get("activationId")),Long.parseLong(args.get("activationRevision")));return Map.of("activation",json.writeValueAsString(result));}
        if(request.action().equals("container.read")||request.action().equals("container.act")){
            var binding=sessions.get(viewer.getUUID(),request.sessionId()).orElseThrow().binding();
            Object state=request.action().equals("container.read")?containers.read(binding):containers.act(binding,request.operationId(),Long.parseLong(args.get("expectedRevision")),json.readValue(args.get("action"),dev.mineagent.runtime.api.ui.ContainerProtocol.Action.class));
            String body=json.writeValueAsString(state);if(body.length()>24000)throw new IllegalStateException("CONTAINER_STATE_BUDGET");return Map.of("state",body,"executionMode","NATIVE_MENU");
        }
        if(Set.of("scoreview.worldFront","scoreview.worldMove","scoreview.worldDetach","scoreview.worldDelete").contains(request.action())){
            if(!mayEditScores(viewer))throw new SecurityException("WORLD_BOARD_DENIED");
            UUID pkg=UUID.fromString(args.get("packageId")),id=UUID.fromString(args.get("targetViewId"));long expected=Long.parseLong(args.get("expectedViewRevision"));
            if(ServerPackageRuntime.get(server).ownedPackage(viewer.getUUID(),pkg,Long.parseLong(args.get("packageRevision"))).isEmpty())throw new SecurityException("PACKAGE_NOT_OWNED");
            var scores=MineAgentRuntimeServices.scoreboards(server);var view=scores.view(id).orElseThrow(()->new IllegalStateException("SCORE_VIEW_MISSING"));
            if(!view.ownerPackageId().equals(pkg)||!new ScoreboardAudienceResolver().visible(view.audience(),audience(viewer)))throw new SecurityException("SCORE_VIEW_AUDIENCE_OR_PACKAGE");
            if(view.revision()!=expected)throw new IllegalStateException("STALE_VIEW_REVISION");
            if(request.action().equals("scoreview.worldDelete")){scores.deleteView(id);return Map.of("state","VIEW_REMOVED_SOURCE_RETAINED");}
            if(request.action().equals("scoreview.worldDetach")){var updated=scores.detachWorldView(id,pkg,expected);return Map.of("state","WORLD_PROJECTION_REMOVED_SOURCE_RETAINED","viewRevision",Long.toString(updated.revision()));}
            ScoreViewTarget target;
            if(request.action().equals("scoreview.worldFront")){var p=viewer.getEyePosition().add(viewer.getLookAngle().scale(4));target=ScoreViewTarget.world(viewer.level().dimension().identifier().toString(),p.x,p.y,p.z,viewer.getYRot()+180,1.5f);}
            else target=ScoreViewTarget.world(args.get("dimension"),Double.parseDouble(args.get("x")),Double.parseDouble(args.get("y")),Double.parseDouble(args.get("z")),Float.parseFloat(args.get("yaw")),Float.parseFloat(args.get("scale")));
            if(server.getLevel(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,net.minecraft.resources.Identifier.parse(target.dimension())))==null)throw new IllegalStateException("WORLD_BOARD_DIMENSION_UNAVAILABLE");
            var updated=scores.placeWorldView(id,pkg,expected,target,request.action().equals("scoreview.worldFront")?128:Integer.parseInt(args.get("viewDistance")));return Map.of("state","WORLD_PROJECTION_UPDATED","viewRevision",Long.toString(updated.revision()));
        }
        if(request.action().equals("package.hudLease")){
            UUID pkg=UUID.fromString(args.get("packageId")),target=UUID.fromString(args.get("targetViewId"));
            String view=UUID.fromString(args.get("viewId")).toString();long revision=Long.parseLong(args.get("packageRevision"));
            if(sessions.list(viewer.getUUID()).stream().anyMatch(s->s.binding().viewId().equals(view)))throw new IllegalStateException("HUD_LEASE_STILL_ACTIVE");
            var pack=ServerPackageRuntime.get(server).ownedPackage(viewer.getUUID(),pkg,revision).orElseThrow(()->new SecurityException("STALE_PACKAGE"));
            String entry=dev.mineagent.runtime.core.packages.PackageUiEntrypoints.select(pack.entrypoints(),true).orElseThrow(()->new IllegalStateException("HUD_ENTRYPOINT_MISSING"));
            var binding=new Binding(view,pkg,revision,pack.version(),entry,MineAgentRuntimeServices.worldId(server),viewer.getUUID(),viewer.getUUID(),ActorKind.PLAYER,null,0,target.toString(),false,Set.of("scoreview.read"));
            scoreBridge().requireTarget(binding,audience(viewer));
            return Map.of("session",json.writeValueAsString(sessions.open(binding,true,true,hudLeaseMillis())),"state","HUD_READMITTED");
        }
        if(request.action().equals("ui.presentationPermit")){
            UUID pkg=UUID.fromString(args.get("packageId"));long revision=Long.parseLong(args.get("packageRevision"));var source=sessions.get(viewer.getUUID(),UUID.fromString(args.get("sourceSessionId"))).orElseThrow(()->new SecurityException("UI_PRESENTATION_DENIED"));
            if(ServerPackageRuntime.get(server).ownedPackage(viewer.getUUID(),pkg,revision).isEmpty()||!dev.mineagent.runtime.api.ui.UiPresentationPolicy.canAgent(source,viewer.getUUID(),pkg,revision,args.get("viewId")))throw new SecurityException("UI_PRESENTATION_DENIED");
            return Map.of("scope","HOST_PRESENTATION","packageRevision",Long.toString(revision));
        }
        if(request.action().equals("ui.statePermit")){
            UUID pkg=UUID.fromString(args.get("packageId"));long revision=Long.parseLong(args.get("packageRevision"));
            if(ServerPackageRuntime.get(server).ownedPackage(viewer.getUUID(),pkg,revision).isEmpty())throw new SecurityException("UI_STATE_STALE_PACKAGE");
            if(args.containsKey("sourceSessionId")){
                var source=sessions.get(viewer.getUUID(),UUID.fromString(args.get("sourceSessionId"))).orElseThrow(()->new SecurityException("UI_STATE_SESSION"));var b=source.binding();
                if(!dev.mineagent.runtime.api.ui.UiInteractionScope.localStateWriter(source,viewer.getUUID(),pkg,revision))throw new SecurityException("UI_STATE_READ_ONLY");
            }
            return Map.of("scope","CLIENT_UI_STATE","packageRevision",Long.toString(revision));
        }
        if(request.action().equals("ui.bindPage")){
            if(!mayStartUi(viewer))throw new SecurityException("UI_PAGE_BIND_DENIED");
            UUID pkg=UUID.fromString(args.get("packageId"));long revision=Long.parseLong(args.get("packageRevision"));String view=UUID.fromString(args.get("viewId")).toString();
            var pack=ServerPackageRuntime.get(server).ownedPackage(viewer.getUUID(),pkg,revision).orElseThrow(()->new SecurityException("PACKAGE_NOT_OWNED"));
            var binding=new Binding(view,pkg,revision,pack.version(),ServerPackageRuntime.uiEntry(pack).orElseThrow(),MineAgentRuntimeServices.worldId(server),viewer.getUUID(),viewer.getUUID(),ActorKind.PLAYER,null,0,"",true,dev.mineagent.runtime.api.ui.UiInteractionScope.PAGE);
            var existing=sessions.list(viewer.getUUID()).stream().filter(s->s.binding().viewId().equals(view)).findFirst().orElse(null);
            if(existing!=null&&!existing.binding().equals(binding))throw new SecurityException("UI_PAGE_VIEW_REUSED");
            var opened=existing==null?sessions.open(binding,true,true,1_800_000):existing;
            return Map.of("session",json.writeValueAsString(opened),"state","PAGE_ONLY_BINDING","viewId",view);
        }
        if(request.action().equals("agent.manage")){return ServerAgentManagement.write(viewer,request.operationId(),args);}
        if(request.action().equals("task.replan")){if(!args.keySet().equals(Set.of("sourceTaskId","sourceRevision","goal","note","confirmed")))throw new IllegalArgumentException("REPLAN_ARGUMENTS");var task=dev.mineagent.runtime.neoforge.task.ServerTaskStart.replan(viewer,request.operationId(),UUID.fromString(args.get("sourceTaskId")),Long.parseLong(args.get("sourceRevision")),args.get("goal"),args.get("note"),"true".equals(args.get("confirmed")));return Map.of("taskId",task.taskId().toString(),"sourceTaskId",args.get("sourceTaskId"),"state",task.status().name(),"executionMode","FRESH_PLANNING_NO_ACTION_REPLAY");}
        if(request.action().equals("task.start")){
            String scope=args.getOrDefault("scope","UI_PACKAGE");if(!Set.of("GENERAL","UI_PACKAGE").contains(scope))throw new IllegalArgumentException("TASK_SCOPE");
            var task=dev.mineagent.runtime.neoforge.task.ServerTaskStart.start(viewer,request.operationId(),UUID.fromString(args.get("agentId")),args.get("prompt"),50,scope.equals("UI_PACKAGE"));
            return Map.of("taskId",task.taskId().toString(),"state",task.status().name(),"planningScope",scope);
        }
        if(request.action().equals("task.control")){
            var tasks=MineAgentRuntimeServices.tasks(server);var task=tasks.get(UUID.fromString(args.get("taskId"))).orElseThrow();
            if(!task.ownerPlayerId().equals(viewer.getUUID())&&!viewer.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))throw new SecurityException("TASK_OWNER");
            if(args.get("control").equals("resumeWorldWait")){
                var def=MineAgentRuntimeServices.bodies(server).definitions().stream().filter(d->d.agentId().equals(task.agentId())).findFirst().orElse(null);
                if(!MineAgentRuntimeServices.permissions(server).canMutateAgent(def,viewer.getUUID(),viewer.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER)))throw new SecurityException("TASK_OWNER");
                var resumed=MineAgentRuntimeServices.taskExecutor(server).worldActions().resumeWorldWait(task.taskId(),Long.parseLong(args.get("taskRevision")));return Map.of("task",json.writeValueAsString(resumed),"errorCode","");
            }
            String action=args.get("control");var target=switch(action){case "pause"->dev.mineagent.runtime.api.task.TaskStatus.PAUSED;case "cancel"->dev.mineagent.runtime.api.task.TaskStatus.CANCELLED;default->throw new IllegalArgumentException("TASK_CONTROL");};
            var result=tasks.transition(task.taskId(),Long.parseLong(args.get("taskRevision")),true,target);
            MineAgentRuntimeServices.taskExecutor(server).tickWorldActions();
            return Map.of("task",json.writeValueAsString(result.task()),"errorCode",result.errorCode());
        }
        if(request.action().equals("ui.takeoverActivate")){
            var current=sessions.get(viewer.getUUID(),UUID.fromString(args.get("restoreSessionId"))).orElseThrow();
            var binding=takeovers.activate(viewer.getUUID(),current,"true".equals(args.get("confirmed")),mayEditScores(viewer));
            var next=sessions.open(binding,true,true,1_800_000);sessions.close(viewer.getUUID(),current.sessionId());
            MineAgentRuntimeServices.audit(server).record(viewer.getUUID().toString(),"UI_TAKEOVER_ACTIVATED",binding.targetObjectId(),json.writeValueAsString(Map.of("readOnlySession",current,"writeSession",next)));
            return Map.of("sourceSessionId",current.sessionId().toString(),"session",json.writeValueAsString(next));
        }
        if (request.action().equals("shell.read")) {
            var result = new LinkedHashMap<String, String>();
            result.put("settingsVersion",json.writeValueAsString(ServerSettings.version(viewer)));
            result.put("agentManagement",json.writeValueAsString(ServerAgentManagement.view(viewer)));
            var visibleWorldTasks=MineAgentRuntimeServices.tasks(server).recentWorld(viewer.getUUID(),Set.of(),16);
            var worldBatches=MineAgentRuntimeServices.taskExecutor(server).worldActions().listForTasks(viewer.getUUID(),visibleWorldTasks);
            result.put("worldTasks",json.writeValueAsString(visibleWorldTasks.stream().map(t->{
                var b=worldBatches.stream().filter(v->v.taskId().equals(t.taskId())&&v.intent()==t.intentRevision()).max(java.util.Comparator.comparingInt(dev.mineagent.runtime.core.task.WorldActionJournal.Batch::round)).orElse(null);
                var link=MineAgentRuntimeServices.agentUiLinks(server).forTask(t.taskId(),t.intentRevision());
                var item=new LinkedHashMap<String,Object>(Map.of("taskId",t.taskId(),"agentId",t.agentId(),"title",t.title(),"revision",t.revision(),"status",t.status(),"actionState",dev.mineagent.runtime.core.task.TaskAuthorityFence.revoked(t)?"AUTHORITY_REVOKED":b==null?link.map(l->"PACKAGE_"+l.state()).orElse("PLANNING"):dev.mineagent.runtime.core.task.WorldActionJournal.resumableWorldWait(b)?"RESUMABLE_WORLD_WAIT":b.state().equals("EXECUTING")&&b.cursor()<b.actions().size()&&b.actions().get(b.cursor()).tool().equals("await_world_activation")?"WAITING_NATIVE_CONFIRMATION":b.state(),"cursor",b==null?0:b.cursor(),"total",b==null?0:b.actions().size(),"error",dev.mineagent.runtime.core.task.TaskAuthorityFence.revoked(t)?"AUTHORITY_REVOKED":b==null?"":b.error(),"round",b==null?0:b.round()+1));item.put("statusReason",t.lastChangeReason());item.put("canReplan",dev.mineagent.runtime.neoforge.task.ServerTaskStart.canReplan(viewer,t));item.put("replanOf",dev.mineagent.runtime.neoforge.task.ServerTaskStart.replanParent(viewer,t));return item;
            }).toList()));
            var decisionService=MineAgentRuntimeServices.decisions(server);
            var decisionPage=decisionService.pageFor(viewer.getUUID(),Integer.parseInt(args.getOrDefault("decisionPage","0")));
            var pending = decisionPage.requests();
            result.put("decisions", json.writeValueAsString(pending));
            result.put("decisionPaging",json.writeValueAsString(Map.of("page",decisionPage.page(),"pages",decisionPage.pages(),"count",decisionPage.count(),"pendingCount",decisionPage.pendingCount())));
            if(args.containsKey("watchDecisionId"))decisionService.get(UUID.fromString(args.get("watchDecisionId")))
                    .filter(q->q.recipientPlayerId().equals(viewer.getUUID())).ifPresent(q->{try{
                        result.put("decisionUpdate",json.writeValueAsString(q));
                        var answer=decisionService.acceptedAnswer(q.decisionId());if(answer.isPresent())result.put("decisionAnswer",json.writeValueAsString(answer.get()));
                        var effect=decisionService.domainEffect(q.decisionId());if(effect.isPresent())result.put("decisionEffect",json.writeValueAsString(effect.get()));
                    }catch(Exception ex){throw new IllegalStateException("DECISION_SERIALIZATION",ex);}});
            var counts=new HashMap<UUID,Integer>();
            for(var q:decisionService.pendingFor(viewer.getUUID()))MineAgentNetwork.decisionAgent(server,q).ifPresent(a->counts.merge(a,1,Integer::sum));
            result.put("decisionContexts",json.writeValueAsString(pending.stream().filter(q->q.status()==DecisionStatus.OPEN||q.status()==DecisionStatus.DEFERRED)
                    .map(q->MineAgentNetwork.decisionAgent(server,q).map(a->Map.of("decisionId",q.decisionId(),"agentId",a,"revision",q.revision(),"title",q.title(),"status",q.status(),"kind",q.kind(),"agentPendingCount",counts.getOrDefault(a,0))).orElse(null)).filter(Objects::nonNull).toList()));
            result.put("agents", json.writeValueAsString(MineAgentRuntimeServices.bodies(server).definitions().stream()
                    .limit(64).map(a -> Map.of("id", a.agentId().toString(), "name", a.displayName(),"personaEditable",dev.mineagent.runtime.core.agent.AgentPersonaService.mayEdit(a,viewer.getUUID(),viewer.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER)))).toList()));
            // Only the owner's provenance is sent. Never raw model output, credentials or server paths.
            result.put("generationJobs",json.writeValueAsString(ServerPackageRuntime.get(server).generationViews(viewer.getUUID())));
            result.put("packageHeads",json.writeValueAsString(ServerPackageRuntime.get(server).heads(viewer.getUUID()).stream().map(p->Map.ofEntries(Map.entry("packageId",p.packageId()),Map.entry("revision",p.revision()),Map.entry("canonicalSha256",p.canonicalSha256()),Map.entry("uiAvailable",dev.mineagent.runtime.core.packages.PackageUiEntrypoints.select(p.entrypoints(),false).isPresent()),Map.entry("hudAvailable",dev.mineagent.runtime.core.packages.PackageUiEntrypoints.select(p.entrypoints(),true).isPresent()),Map.entry("containerAvailable",dev.mineagent.runtime.core.packages.PackageUiEntrypoints.named(p.entrypoints(),"container").isPresent()),Map.entry("worldAvailable",p.entrypoints().containsKey("server")&&!p.definitions().isEmpty()),Map.entry("dataPackAvailable",Set.of(dev.mineagent.runtime.api.packages.ActivationMode.DATA_RELOAD,dev.mineagent.runtime.api.packages.ActivationMode.WORLD_REOPEN).contains(p.activationMode())),Map.entry("resourcePackAvailable",p.activationMode()==dev.mineagent.runtime.api.packages.ActivationMode.RESOURCE_RELOAD),Map.entry("clientScriptAvailable",p.entrypoints().containsKey("client")),Map.entry("clientJavaAvailable",p.entrypoints().containsKey("client_java")),Map.entry("bootAvailable",p.activationMode()==dev.mineagent.runtime.api.packages.ActivationMode.BOOT_EXTENSION))).toList()));
            result.put("worldPatchJobs",json.writeValueAsString(ServerPackageRuntime.get(server).worldPatchJobs(viewer.getUUID()).stream().limit(16).map(j->Map.of("operationId",j.operationId(),"taskId",j.taskId(),"packageId",j.base().packageId(),"baseRevision",j.base().revision(),"headRevision",j.headRevision(),"state",j.state(),"errorCode",j.errorCode(),"baseHash",j.base().canonicalSha256(),"candidateHash",j.candidate()==null?"":j.candidate().canonicalSha256())).toList()));
            result.put("patchJobs",json.writeValueAsString(ServerPackageRuntime.get(server).patchJobs(viewer.getUUID()).stream().limit(16).map(j->{var p=new LinkedHashMap<String,Object>();p.put("operationId",j.operationId());p.put("taskId",j.taskId());p.put("agentId",j.agentId());p.put("packageId",j.base().packageId());p.put("baseRevision",j.base().revision());p.put("candidateRevision",j.base().revision()+1);p.put("headRevision",j.headRevision());p.put("state",j.state());p.put("errorCode",j.errorCode());p.put("rawOutputSha256",j.rawOutputSha256());p.put("rebuildAllowed",j.state().equals("FAILED")&&ServerPackageRuntime.get(server).mayRebuildUi(viewer,j.operationId()));p.put("rebuilt",ServerPackageRuntime.get(server).uiRebuildEvidence(viewer.getUUID(),j.operationId()).isPresent());p.put("prompt",j.prompt().substring(0,Math.min(256,j.prompt().length())));return p;}).toList()));
            var scores = MineAgentRuntimeServices.scoreboards(server);
            result.put("scoreSources", mayEditScores(viewer) ? json.writeValueAsString(scores.refreshSources().stream().limit(64)
                    .map(s -> Map.of("id", s.sourceId(), "reference", s.reference())).toList()) : "[]");
            Set<UUID> owned = ServerPackageRuntime.get(server).list(viewer.getUUID()).stream().filter(j -> j.state().equals("PUBLISHED"))
                    .map(dev.mineagent.runtime.core.packages.PackageGenerationJob::packageId).collect(java.util.stream.Collectors.toSet());
            var page=scores.viewPage(v->owned.contains(v.ownerPackageId())&&v.enabled()&&new ScoreboardAudienceResolver().visible(v.audience(),audience(viewer)),Integer.parseInt(args.getOrDefault("viewPage","0")),32);
            result.put("scoreViews",json.writeValueAsString(page.views().stream().map(v->Map.of("id",v.viewId(),"packageId",v.ownerPackageId(),"sourceId",v.sourceId(),"revision",v.revision(),"kind",v.kind(),"target",v.target(),"viewDistance",v.layout().getOrDefault("viewDistance","128"))).toList()));
            result.put("scoreViewPaging",json.writeValueAsString(Map.of("page",page.page(),"pages",page.totalPages(),"count",page.totalCount())));
            result.put("uiAgentTasks",json.writeValueAsString(uiAgents.list(viewer.getUUID())));
            return result;
        }
        if(request.action().equals("ui.delegate")){
            if(!"true".equals(args.get("confirmed")))throw new SecurityException("UI_DELEGATION_CONFIRM_REQUIRED");
            var source=sessions.get(viewer.getUUID(),UUID.fromString(args.get("sourceSessionId"))).orElseThrow();
            String mode=args.getOrDefault("presentationOnly","false");if(!Set.of("true","false").contains(mode))throw new IllegalArgumentException("UI_PRESENTATION_MODE");return uiAgents.delegate(viewer,source,UUID.fromString(args.get("agentId")),args.get("goal"),args.get("expectedTitle"),Boolean.parseBoolean(mode));
        }
        if(request.action().equals("worldui.agent")){
            if(!"true".equals(args.get("confirmed")))throw new SecurityException("UI_DELEGATION_CONFIRM_REQUIRED");
            var source=sessions.get(viewer.getUUID(),UUID.fromString(args.get("sourceSessionId"))).orElseThrow();
            return uiAgents.delegateWorld(viewer,source,UUID.fromString(args.get("agentId")),args.get("goal"),args.get("expected"));
        }
        if(request.action().equals("ui.stop")){uiAgents.stop(viewer.getUUID(),UUID.fromString(args.get("taskId")),"USER_INTERRUPTED");return Map.of("status","STOPPED");}
        if (request.action().equals("scoreview.bind")) {
            if (!mayEditScores(viewer)) throw new SecurityException("SCORE_BINDING_DENIED");
            UUID packageId=UUID.fromString(args.get("packageId")); long revision=Long.parseLong(args.get("packageRevision"));
            if(ServerPackageRuntime.get(server).ownedPackage(viewer.getUUID(),packageId,revision).isEmpty()) throw new SecurityException("PACKAGE_NOT_OWNED");
            var scores=MineAgentRuntimeServices.scoreboards(server); scores.refreshSources();
            var source=scores.source(UUID.fromString(args.get("sourceId"))).orElseThrow();
            var view=scores.createView(request.operationId(),source.sourceId(),ScoreViewKind.HUD,packageId,
                    new ScoreAudience(ScoreAudienceKind.PLAYERS,Set.of(viewer.getUUID().toString())), Map.of("title",args.getOrDefault("title",source.reference()),"topN","10"),ScoreViewTarget.hud("TOP_LEFT"));
            return Map.of("viewId",view.viewId().toString(),"viewRevision",Long.toString(view.revision()));
        }
        if (Set.of("scoreview.read","scoreview.patch").contains(request.action())) {
            var binding=sessions.get(viewer.getUUID(),request.sessionId()).orElseThrow().binding();
            var bridge=scoreBridge();
            var state=request.action().equals("scoreview.read") ? bridge.read(binding,audience(viewer))
                    : bridge.patch(binding,audience(viewer),binding.actorKind()==ActorKind.AGENT?uiAgents.authorize(binding,viewer)==Code.OK
                            :binding.actorId().equals(viewer.getUUID()) && mayEditScores(viewer),
                            Long.parseLong(args.get("expectedViewRevision")), json.readValue(args.get("patch"),new com.fasterxml.jackson.core.type.TypeReference<Map<String,String>>(){}));
            var values=new LinkedHashMap<String,String>();values.put("state",json.writeValueAsString(state));
            if(request.action().equals("scoreview.read")&&dev.mineagent.runtime.api.ui.ReadOnlyUiLease.eligible(binding)){
                var pkg=ServerPackageRuntime.get(server).ownedPackage(viewer.getUUID(),binding.ownerPackageId(),binding.packageRevision());
                if(pkg.isPresent()&&dev.mineagent.runtime.core.packages.PackageUiEntrypoints.select(pkg.get().entrypoints(),true).filter(binding.entryPath()::equals).isPresent()){
                    var current=sessions.get(viewer.getUUID(),request.sessionId()).orElseThrow();
                    if(current.expiresAtMillis()-System.currentTimeMillis()<=hudLeaseMillis()/2)
                        values.put("renewedSession",json.writeValueAsString(sessions.renewReadOnly(viewer.getUUID(),request,hudLeaseMillis())));
                }
            }
            return values;
        }
        if(request.action().equals("package.generate")){
            var input=dev.mineagent.runtime.core.packages.PackageGenerationRequest.parse(args);var runtime=ServerPackageRuntime.get(server);
            var submitted=input.sourceOperation()==null?runtime.submit(viewer,input.agentId(),request.operationId(),input.prompt(),input.purpose()):runtime.repair(viewer,input.agentId(),request.operationId(),input.sourceOperation(),input.sourceRevision(),input.sourceSha256(),input.prompt(),true);
            var job=submitted.job();return Map.of("taskId",job.taskId().toString(),"packageId",job.packageId().toString(),"state",job.state(),"duplicate",Boolean.toString(submitted.duplicate()),"errorCode",job.errorCode());
        }
        if(request.action().equals("package.patchSubmit")){
            var submitted=ServerPackageRuntime.get(server).patch(viewer,UUID.fromString(args.get("agentId")),request.operationId(),UUID.fromString(args.get("packageId")),Long.parseLong(args.get("packageRevision")),args.get("prompt"));var j=submitted.job();
            return Map.of("operationId",j.operationId().toString(),"taskId",j.taskId().toString(),"state",j.state(),"duplicate",Boolean.toString(submitted.duplicate()),"errorCode",j.errorCode());
        }
        if(Set.of("package.patchApply","package.patchRollback","package.patchCancel").contains(request.action())){
            String action=request.action().equals("package.patchApply")?"apply":request.action().equals("package.patchRollback")?"rollback":"cancel";
            var j=ServerPackageRuntime.get(server).patchAction(viewer.getUUID(),UUID.fromString(args.get("operationId")),action);
            return Map.of("operationId",j.operationId().toString(),"state",j.state(),"headRevision",Long.toString(j.headRevision()),"errorCode",j.errorCode());
        }
        if (request.action().equals("package.cancel")) {
            var job = ServerPackageRuntime.get(server).cancel(viewer.getUUID(), UUID.fromString(args.get("operationId")));
            return Map.of("taskId", job.taskId().toString(), "state", job.state());
        }
        if(request.action().equals("package.resourceChunk")||request.action().equals("package.resourceRelease")){
            restoreScope(viewer,request);boolean release=request.action().equals("package.resourceRelease");if(!args.keySet().equals(release?Set.of("transferId"):Set.of("transferId","offset")))throw new IllegalArgumentException("RESOURCE_PACK_ARGUMENTS");UUID transfer=UUID.fromString(args.get("transferId"));var runtime=ServerPackageRuntime.get(server);
            if(release){runtime.releaseResources(viewer.getUUID(),request.sessionId(),transfer);return Map.of("state","RESOURCE_TRANSFER_RELEASED");}
            int offset=Integer.parseInt(args.get("offset"));return Map.of("offset",Integer.toString(offset),"bytes",Base64.getEncoder().encodeToString(runtime.resourceChunk(viewer.getUUID(),request.sessionId(),transfer,offset)));
        }
        if(request.action().equals("package.clientScriptChunk")||request.action().equals("package.clientScriptRelease")){
            restoreScope(viewer,request);boolean release=request.action().equals("package.clientScriptRelease");if(!args.keySet().equals(release?Set.of("transferId"):Set.of("transferId","offset")))throw new IllegalArgumentException("CLIENT_SCRIPT_ARGUMENTS");UUID transfer=UUID.fromString(args.get("transferId"));var runtime=ServerPackageRuntime.get(server);
            if(release){runtime.releaseClientScript(viewer.getUUID(),request.sessionId(),transfer);return Map.of("state","CLIENT_SCRIPT_TRANSFER_RELEASED");}
            int offset=Integer.parseInt(args.get("offset"));return Map.of("offset",Integer.toString(offset),"bytes",Base64.getEncoder().encodeToString(runtime.clientScriptChunk(viewer.getUUID(),request.sessionId(),transfer,offset)));
        }
        if(request.action().equals("package.clientJavaChunk")||request.action().equals("package.clientJavaRelease")){
            restoreScope(viewer,request);boolean release=request.action().equals("package.clientJavaRelease");if(!args.keySet().equals(release?Set.of("transferId"):Set.of("transferId","offset")))throw new IllegalArgumentException("CLIENT_JAVA_ARGUMENTS");UUID transfer=UUID.fromString(args.get("transferId"));var runtime=ServerPackageRuntime.get(server);if(release){runtime.releaseClientJava(viewer.getUUID(),request.sessionId(),transfer);return Map.of("state","CLIENT_JAVA_TRANSFER_RELEASED");}int offset=Integer.parseInt(args.get("offset"));return Map.of("offset",Integer.toString(offset),"bytes",Base64.getEncoder().encodeToString(runtime.clientJavaChunk(viewer.getUUID(),request.sessionId(),transfer,offset)));
        }
        if (request.action().equals("package.chunk")) {
            int offset = Integer.parseInt(args.get("offset"));
            byte[] bytes = ServerPackageRuntime.get(server).chunk(viewer.getUUID(), request.sessionId(), UUID.fromString(args.get("transferId")), offset);
            return Map.of("offset", Integer.toString(offset), "bytes", Base64.getEncoder().encodeToString(bytes));
        }
        if (request.action().equals("package.release")) {
            ServerPackageRuntime.disconnect(server, viewer.getUUID()); return Map.of("state", "TRANSFER_RELEASED");
        }
        if (request.action().startsWith("chat.")) {
            String agent = args.getOrDefault("agentId", "");
            if(agent.isBlank())return Map.of("errorCode","CONVERSATION_SELECTION_REQUIRED");
            UUID agentId = UUID.fromString(agent);
            boolean exists = MineAgentRuntimeServices.bodies(server).definitions().stream().anyMatch(a -> a.agentId().equals(agentId));
            if (!exists) throw new IllegalArgumentException("AGENT_NOT_FOUND");
            if (request.action().equals("chat.send")) {
                var routed=MineAgentNetwork.routeDecisionChat(viewer,agentId,args.containsKey("decisionId")?UUID.fromString(args.get("decisionId")):null,args.containsKey("decisionRevision")?Long.parseLong(args.get("decisionRevision")):null,request.operationId(),args.getOrDefault("text",""));
                if(routed.handled()){
                    var values=new LinkedHashMap<String,String>();values.put("transport","DECISION_CHAT");values.put("errorCode",routed.code());
                    if(routed.result()!=null){var result=routed.result();values.put("decision",json.writeValueAsString(result.request()));if(result.accepted()){var answer=MineAgentRuntimeServices.decisions(server).acceptedAnswer(result.request().decisionId()).orElseThrow();values.put("answer",json.writeValueAsString(answer));if(!result.duplicate())MineAgentNetwork.applyUiDecisionEffects(viewer,answer,result.request());}var effect=MineAgentRuntimeServices.decisions(server).domainEffect(result.request().decisionId());if(effect.isPresent())values.put("domainEffect",json.writeValueAsString(effect.orElseThrow()));}
                    return values;
                }
                return Map.of("errorCode","CONVERSATION_ID_REQUIRED");
            }
            return Map.of("errorCode","CONVERSATION_ID_REQUIRED");
        }
        if (request.action().startsWith("decision.")) {
            UUID id = UUID.fromString(args.get("decisionId"));
            var decisionService = MineAgentRuntimeServices.decisions(server);
            DecisionRequest decision = decisionService.get(id).orElseThrow();
            if (!decision.recipientPlayerId().equals(viewer.getUUID())) throw new SecurityException("DECISION_RECIPIENT");
            long revision = Long.parseLong(args.get("expectedRevision"));
            if(request.action().equals("decision.cancelTask")){
                if(!MineAgentRuntimeServices.permissions(server).allowed(viewer.getUUID(),viewer.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER),PermissionAction.CANCEL_AGENT_TASK))throw new SecurityException("TASK_CANCEL_DENIED");
                var result=decisionService.cancelTask(viewer.getUUID(),MineAgentRuntimeServices.tasks(server),id,revision);return Map.of("errorCode",result.errorCode(),"decision",json.writeValueAsString(result.request()));
            }
            if (request.action().equals("decision.submit")) {
                int n = Integer.parseInt(args.getOrDefault("selectedCount", "0"));
                if (n < 0 || n > 64) throw new IllegalArgumentException("SELECTION_COUNT");
                List<String> selected = new ArrayList<>();
                for (int i = 0; i < n; i++) selected.add(Objects.requireNonNull(args.get("selected." + i)));
                var submission = new DecisionAnswerSubmission(id, revision, UUID.fromString(args.get("submissionId")),
                        selected, args.getOrDefault("customText", ""), AnswerSource.UI);
                var result = decisionService.submitForTask(viewer.getUUID(), MineAgentRuntimeServices.tasks(server), submission);
                if (result.accepted() && !result.duplicate()) MineAgentNetwork.applyUiDecisionEffects(viewer, submission, result.request());
                var values = new LinkedHashMap<String, String>();
                values.put("errorCode", result.errorCode()); values.put("decision", json.writeValueAsString(result.request()));
                values.put("duplicate", Boolean.toString(result.duplicate())); values.put("source", "TRUSTED_UI");
                if (result.accepted()) values.put("answer", json.writeValueAsString(decisionService.acceptedAnswer(id).orElseThrow()));
                if(decisionService.domainEffect(id).isPresent())values.put("domainEffect",json.writeValueAsString(decisionService.domainEffect(id).orElseThrow()));
                return values;
            }
            DecisionStatus target = switch (request.action()) { case "decision.defer" -> DecisionStatus.DEFERRED;
                case "decision.resume" -> DecisionStatus.OPEN; default -> throw new IllegalArgumentException("DECISION_ACTION"); };
            var result = decisionService.transition(id, viewer.getUUID(), revision, target, false);
            return Map.of("errorCode", result.errorCode(), "decision", json.writeValueAsString(result.request()));
        }
        throw new IllegalArgumentException("UNSUPPORTED_UI_ACTION");
    }
    private void send(ServerPlayer viewer, UUID id, String channel, Object value) {
        if (server.getPlayerList().getPlayer(viewer.getUUID()) != viewer) return;
        try { PacketDistributor.sendToPlayer(viewer, new UiPayloads.Event(id, channel, json.writeValueAsString(value))); }
        catch (Exception failure) { dev.mineagent.runtime.neoforge.MineAgentRuntimeMod.LOGGER.warn("UI response failed: {}", failure.getClass().getSimpleName()); }
    }
    private void javaStudio(ServerPlayer viewer,UUID packetId,Request request){
        boolean write=request.action().equals("studio.write"),begun=false;
        try{restoreScope(viewer,request);ServerJavaStudio.authorize(viewer);String capability=write?"package.generate":"package.preview";if(sessions.checkRead(viewer.getUUID(),request,capability)!=Code.OK)throw new SecurityException("JAVA_STUDIO_SESSION_CHANGED");
            if(write){if(request.arguments().containsKey("kind"))throw new IllegalArgumentException("JAVA_STUDIO_ARGUMENTS");var receipt=sessions.begin(viewer.getUUID(),request,capability,true);if(receipt.code()!=Code.OK){send(viewer,packetId,"receipt",receipt);return;}begun=true;var value=ServerJavaStudio.write(viewer,request.operationId(),request.arguments());send(viewer,packetId,"receipt",sessions.complete(request,Code.APPLIED,Map.of("state",json.writeValueAsString(value))));}
            else{String value=json.writeValueAsString(ServerJavaStudio.read(viewer,request.arguments()));if(value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>24000)throw new IllegalStateException("JAVA_STUDIO_RESPONSE_LIMIT");send(viewer,packetId,"receipt",new Receipt(request.operationId(),Code.OBSERVED,Map.of("state",value)));}
        }catch(Exception e){var value=Map.of("errorCode",ServerJavaStudio.error(e));send(viewer,packetId,"receipt",begun?sessions.complete(request,Code.FAILED,value):new Receipt(request.operationId(),Code.FAILED,value));}
    }
    private void preferences(ServerPlayer viewer,UUID packetId,Request request){
        boolean write=request.action().equals("preferences.write"),begun=false;
        try{restoreScope(viewer,request);ServerPreferences.authorize(viewer);String capability=write?"conversation.write":"conversation.read";
            if(write!=request.arguments().getOrDefault("kind","").equals("change")||sessions.checkRead(viewer.getUUID(),request,capability)!=Code.OK)throw new SecurityException("PREFERENCE_SESSION_CHANGED");
            if(write){var receipt=sessions.begin(viewer.getUUID(),request,capability,true);if(receipt.code()!=Code.OK){send(viewer,packetId,"receipt",receipt);return;}begun=true;var result=ServerPreferences.write(viewer,request.operationId(),request.arguments());send(viewer,packetId,"receipt",sessions.complete(request,Code.APPLIED,Map.of("state",json.writeValueAsString(result))));}
            else{String result=json.writeValueAsString(ServerPreferences.read(viewer,request.arguments()));if(result.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>24000)throw new IllegalStateException("PREFERENCE_RESPONSE_LIMIT");send(viewer,packetId,"receipt",new Receipt(request.operationId(),Code.OBSERVED,Map.of("state",result)));}
        }catch(Exception e){var values=Map.of("errorCode",ServerPreferences.error(e));send(viewer,packetId,"receipt",begun?sessions.complete(request,Code.FAILED,values):new Receipt(request.operationId(),Code.FAILED,values));}
    }
    private void assets(ServerPlayer viewer,UUID packetId,Request request){
        boolean write=request.action().equals("package.assetsWrite"),begun=false;
        try{
            if(write!=request.arguments().getOrDefault("kind","").equals("change"))throw new IllegalArgumentException("PACKAGE_ASSET_ACTION");restoreScope(viewer,request);ServerPackageAssets.authorize(viewer);
            String capability=write?"package.generate":"package.preview";if(sessions.checkRead(viewer.getUUID(),request,capability)!=Code.OK)throw new SecurityException("PACKAGE_ASSET_SESSION_CHANGED");
            var runtime=ServerPackageRuntime.get(server);var library=runtime.worldLibrary();long generation=MineAgentRuntimeServices.config(server).permissionGeneration(viewer.getUUID(),dev.mineagent.runtime.api.permission.PermissionAction.MANAGE_PACKAGES);
            if(!write){String encoded=json.writeValueAsString(ServerPackageAssets.read(viewer,request.arguments()));if(encoded.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>24000)throw new IllegalStateException("PACKAGE_ASSET_RESPONSE_LIMIT");send(viewer,packetId,"receipt",new Receipt(request.operationId(),Code.OBSERVED,Map.of("state",encoded)));return;}
            var input=ServerPackageAssets.input(viewer,request.operationId(),request.arguments());
            var old=library.assetReceipt(input.world(),input.owner(),input.operation());
            if(old!=null){if(!old.input().equals(input))throw new IllegalStateException("PACKAGE_ASSET_OPERATION_REUSED");if(old.target()!=null&&Set.of("COPY","REUSE_ASSET").contains(input.action()))library.refreshAsset(old.target());send(viewer,packetId,"receipt",new Receipt(request.operationId(),Code.APPLIED,Map.of("state",json.writeValueAsString(old))));return;}
            var source=ServerPackageAssets.source(viewer,input);
            var receipt=sessions.begin(viewer.getUUID(),request,capability,true);if(receipt.code()!=Code.OK){send(viewer,packetId,"receipt",receipt);return;}begun=true;
            if(Set.of("COPY","REUSE_ASSET").contains(input.action())){
                runtime.prepareAssetCopy(source,input.targetId(),input.name()).whenComplete((candidate,failure)->server.execute(()->{
                    try{restoreScope(viewer,request);ServerPackageAssets.authorize(viewer);if(generation!=MineAgentRuntimeServices.config(server).permissionGeneration(viewer.getUUID(),dev.mineagent.runtime.api.permission.PermissionAction.MANAGE_PACKAGES)||sessions.checkRead(viewer.getUUID(),request,capability)!=Code.OK)throw new SecurityException("PACKAGE_ASSET_CONTEXT_CHANGED");ServerPackageAssets.source(viewer,input);if(failure!=null)throw new IllegalStateException(ServerPackageAssets.code(failure));
                        var result=library.installAssetCopy(input,candidate);send(viewer,packetId,"receipt",sessions.complete(request,Code.APPLIED,Map.of("state",json.writeValueAsString(result),"executionMode","ASSET_ONLY_NO_RUNTIME_STATE")));
                    }catch(Exception error){send(viewer,packetId,"receipt",sessions.complete(request,Code.FAILED,Map.of("errorCode",ServerPackageAssets.code(error))));}
                }));return;
            }
            var result=library.assetMetadata(input);send(viewer,packetId,"receipt",sessions.complete(request,Code.APPLIED,Map.of("state",json.writeValueAsString(result),"executionMode","OWNER_ASSET_METADATA_ONLY")));
        }catch(Exception failure){var values=Map.of("errorCode",ServerPackageAssets.code(failure));send(viewer,packetId,"receipt",begun?sessions.complete(request,Code.FAILED,values):new Receipt(request.operationId(),Code.FAILED,values));}
    }
    private void boot(ServerPlayer viewer,UUID packetId,Request request){
        boolean write=request.action().equals("boot.change"),begun=false;
        try{
            String kind=request.arguments().get("kind");if(write?!Set.of("build","change","stageUpgrade","cancelUpgrade").contains(kind):!Set.of("list","diagnostics","plans","dependencies").contains(kind))throw new IllegalArgumentException("BOOT_ARGUMENTS");
            restoreScope(viewer,request);dev.mineagent.runtime.neoforge.boot.NativeBootRuntime.authorize(viewer);var runtime=dev.mineagent.runtime.neoforge.boot.NativeBootRuntime.get(server);runtime.current(viewer,request.arguments());
            String capability=write?"world.activate":"world.inspect";if(sessions.checkRead(viewer.getUUID(),request,capability)!=Code.OK)throw new SecurityException("BOOT_SESSION_CHANGED");
            var config=MineAgentRuntimeServices.config(server);long run=config.permissionGeneration(viewer.getUUID(),dev.mineagent.runtime.api.permission.PermissionAction.RUN_CODE),manage=config.permissionGeneration(viewer.getUUID(),dev.mineagent.runtime.api.permission.PermissionAction.MANAGE_PACKAGES);
            java.util.function.BooleanSupplier current=()->{try{restoreScope(viewer,request);runtime.current(viewer,request.arguments());var actual=MineAgentRuntimeServices.config(server);return sessions.checkRead(viewer.getUUID(),request,capability)==Code.OK&&run==actual.permissionGeneration(viewer.getUUID(),dev.mineagent.runtime.api.permission.PermissionAction.RUN_CODE)&&manage==actual.permissionGeneration(viewer.getUUID(),dev.mineagent.runtime.api.permission.PermissionAction.MANAGE_PACKAGES);}catch(Exception failure){return false;}};
            java.util.function.BooleanSupplier permit=()->{try{return server.submit(current::getAsBoolean).get(3,java.util.concurrent.TimeUnit.SECONDS);}catch(Exception failure){return false;}};
            if(write){var receipt=sessions.begin(viewer.getUUID(),request,capability,true);if(receipt.code()!=Code.OK){send(viewer,packetId,"receipt",receipt);return;}begun=true;
                var result=switch(kind){case "build"->runtime.build(viewer,request.operationId(),request.arguments(),permit);case "stageUpgrade"->runtime.stageUpgrade(viewer,request.operationId(),request.arguments(),permit);case "cancelUpgrade"->runtime.cancelUpgrade(viewer,request.operationId(),request.arguments(),permit);default->runtime.change(viewer,request.operationId(),request.arguments(),permit);};
                send(viewer,packetId,"receipt",sessions.complete(request,Code.ACCEPTED,Map.of("state",json.writeValueAsString(result),"executionMode","EXPLICIT_GLOBAL_BOOT_JOB")));
            }else runtime.read(viewer,request.arguments(),permit).whenComplete((value,failure)->server.execute(()->{try{if(!current.getAsBoolean())throw new SecurityException("BOOT_SESSION_CHANGED");if(failure!=null)throw new IllegalStateException(dev.mineagent.runtime.neoforge.boot.NativeBootRuntime.code(failure));String encoded=json.writeValueAsString(value);if(encoded.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>24000)throw new IllegalStateException("BOOT_RESPONSE_LIMIT");send(viewer,packetId,"receipt",new Receipt(request.operationId(),Code.OBSERVED,Map.of("state",encoded)));}catch(Exception error){send(viewer,packetId,"receipt",new Receipt(request.operationId(),Code.FAILED,Map.of("errorCode",dev.mineagent.runtime.neoforge.boot.NativeBootRuntime.code(error))));}}));
        }catch(Exception failure){var values=Map.of("errorCode",dev.mineagent.runtime.neoforge.boot.NativeBootRuntime.code(failure));send(viewer,packetId,"receipt",begun?sessions.complete(request,Code.FAILED,values):new Receipt(request.operationId(),Code.FAILED,values));}
    }
    private void nativeApi(ServerPlayer viewer,UUID packetId,Request request){
        try{
            if(request.action().equals("nativeApi.refresh")!="refresh".equals(request.arguments().get("kind")))throw new IllegalArgumentException("NATIVE_API_QUERY");
            restoreScope(viewer,request);if(sessions.checkRead(viewer.getUUID(),request,"world.inspect")!=Code.OK)throw new SecurityException("NATIVE_API_SESSION_CHANGED");dev.mineagent.runtime.neoforge.compile.NativeApiCatalog.authorize(viewer);
            var config=MineAgentRuntimeServices.config(server);long runGeneration=config.permissionGeneration(viewer.getUUID(),dev.mineagent.runtime.api.permission.PermissionAction.RUN_CODE),manageGeneration=config.permissionGeneration(viewer.getUUID(),dev.mineagent.runtime.api.permission.PermissionAction.MANAGE_PACKAGES);
            java.util.function.BooleanSupplier current=()->{try{restoreScope(viewer,request);dev.mineagent.runtime.neoforge.compile.NativeApiCatalog.authorize(viewer);return sessions.checkRead(viewer.getUUID(),request,"world.inspect")==Code.OK&&runGeneration==MineAgentRuntimeServices.config(server).permissionGeneration(viewer.getUUID(),dev.mineagent.runtime.api.permission.PermissionAction.RUN_CODE)&&manageGeneration==MineAgentRuntimeServices.config(server).permissionGeneration(viewer.getUUID(),dev.mineagent.runtime.api.permission.PermissionAction.MANAGE_PACKAGES);}catch(Exception denied){return false;}};
            java.util.function.BooleanSupplier permit=()->{try{return server.submit(current::getAsBoolean).get(3,java.util.concurrent.TimeUnit.SECONDS);}catch(Exception unavailable){return false;}};
            var live=Set.of("live_state","loaded","live_members","live_method_body","transformed_members","transformed_method_body").contains(request.arguments().get("kind"));
            var future=live?dev.mineagent.runtime.neoforge.compile.NativeApiCatalog.readLive(viewer,nativeLiveRequest(request.arguments()),permit,request.operationId()):dev.mineagent.runtime.neoforge.compile.NativeApiCatalog.read(viewer,request.arguments(),permit,request.operationId());
            future.whenComplete((result,failure)->server.execute(()->{
                try{if(!current.getAsBoolean())throw new SecurityException("NATIVE_API_SESSION_CHANGED");send(viewer,packetId,"receipt",new Receipt(request.operationId(),failure==null&&!result.containsKey("errorCode")?Code.OBSERVED:Code.FAILED,failure==null?result:Map.of("errorCode","NATIVE_API_UNAVAILABLE")));}
                catch(Exception denied){send(viewer,packetId,"receipt",new Receipt(request.operationId(),Code.PERMISSION_DENIED,Map.of("errorCode","NATIVE_API_CONTEXT_CHANGED")));}
            }));
        }catch(Exception failure){String code=Objects.toString(failure.getMessage(),"");send(viewer,packetId,"receipt",new Receipt(request.operationId(),Code.FAILED,Map.of("errorCode",code.matches("NATIVE_API_[A-Z_]{1,80}")?code:"NATIVE_API_DENIED")));}
    }
    private static dev.mineagent.runtime.core.compile.NativeLiveToolRequest nativeLiveRequest(Map<String,String> args){
        String kind=Objects.toString(args.get("kind"),"");Set<String> expected=switch(kind){case "live_state"->Set.of("kind");case "loaded"->Set.of("kind","search","offset");case "live_members"->Set.of("kind","classToken","search","offset","textOffset");case "live_method_body"->Set.of("kind","classToken","method","descriptor","offset","textOffset");case "transformed_members"->Set.of("kind","snapshot","module","class","search","offset","textOffset");case "transformed_method_body"->Set.of("kind","snapshot","module","class","method","descriptor","offset","textOffset");default->throw new IllegalArgumentException("NATIVE_API_QUERY");};if(!args.keySet().equals(expected))throw new IllegalArgumentException("NATIVE_API_QUERY");
        String tool=switch(kind){case "live_state"->"inspect_native_live_environment";case "loaded"->"inspect_native_loaded_classes";case "live_members"->"inspect_native_live_members";case "live_method_body"->"inspect_native_live_method_body";case "transformed_members"->"inspect_native_transformed_members";default->"inspect_native_transformed_method_body";};
        try{return new dev.mineagent.runtime.core.compile.NativeLiveToolRequest(tool,kind,args.containsKey("classToken")?UUID.fromString(args.get("classToken")):null,args.getOrDefault("snapshot",""),args.getOrDefault("module",""),args.getOrDefault("class",""),args.getOrDefault("search",""),args.getOrDefault("method",""),args.getOrDefault("descriptor",""),Integer.parseInt(args.getOrDefault("offset","0")),Integer.parseInt(args.getOrDefault("textOffset","0")));}catch(Exception invalid){throw new IllegalArgumentException("NATIVE_API_QUERY",invalid);}
    }
    private void resourcePrepare(ServerPlayer viewer,Request request){
        var args=request.arguments();var runtime=ServerPackageRuntime.get(server);UUID id=UUID.fromString(args.get("packageId"));long revision=Long.parseLong(args.get("packageRevision"));String hash=args.get("canonical");
        try{runtime.prepareResources(viewer.getUUID(),id,revision,hash).whenComplete((body,error)->server.execute(()->{
            if(runtime.closed())return;runtime.resourcesPrepared(viewer.getUUID());
            try{if(error!=null)throw new IllegalStateException("RESOURCE_PACK_PREPARE_FAILED");restoreScope(viewer,request);if(server.getPlayerList().getPlayer(viewer.getUUID())!=viewer||sessions.checkRead(viewer.getUUID(),request,"package.preview")!=Code.OK)throw new SecurityException("RESOURCE_PACK_SESSION_CHANGED");var offer=runtime.resourceOffer(viewer.getUUID(),request.sessionId(),id,revision,hash,body);
                send(viewer,request.operationId(),"receipt",sessions.complete(request,Code.ACCEPTED,Map.of("packageId",id.toString(),"packageRevision",Long.toString(revision),"canonical",hash,"transferId",offer.transferId().toString(),"size",Integer.toString(offer.size()),"sha256",offer.sha256(),"executionMode","DOWNLOAD_ONLY_CLIENT_CONSENT_REQUIRED")));
            }catch(Exception failure){send(viewer,request.operationId(),"receipt",sessions.complete(request,Code.FAILED,Map.of("errorCode","RESOURCE_PACK_PREPARE_FAILED")));}
        }));}catch(Exception failure){send(viewer,request.operationId(),"receipt",sessions.complete(request,Code.FAILED,Map.of("errorCode","RESOURCE_PACK_PREPARE_FAILED")));}
    }
    private void clientScriptPrepare(ServerPlayer viewer,Request request){
        var args=request.arguments();var runtime=ServerPackageRuntime.get(server);UUID id=UUID.fromString(args.get("packageId"));long revision=Long.parseLong(args.get("packageRevision"));String hash=args.get("canonical");
        try{runtime.prepareClientScript(viewer.getUUID(),id,revision,hash).whenComplete((body,error)->server.execute(()->{
            if(runtime.closed())return;runtime.clientScriptPrepared(viewer.getUUID());
            try{if(error!=null)throw new IllegalStateException("CLIENT_SCRIPT_PREPARE_FAILED");restoreScope(viewer,request);if(server.getPlayerList().getPlayer(viewer.getUUID())!=viewer||sessions.checkRead(viewer.getUUID(),request,"package.preview")!=Code.OK)throw new SecurityException("CLIENT_SCRIPT_SESSION_CHANGED");var offer=runtime.clientScriptOffer(viewer.getUUID(),request.sessionId(),id,revision,hash,body);
                send(viewer,request.operationId(),"receipt",sessions.complete(request,Code.ACCEPTED,Map.of("packageId",id.toString(),"packageRevision",Long.toString(revision),"canonical",hash,"transferId",offer.transferId().toString(),"size",Integer.toString(offer.size()),"sha256",offer.sha256(),"executionMode","DOWNLOAD_ONLY_SEPARATE_LOCAL_EXECUTION_CONSENT_REQUIRED")));
            }catch(Exception failure){send(viewer,request.operationId(),"receipt",sessions.complete(request,Code.FAILED,Map.of("errorCode","CLIENT_SCRIPT_PREPARE_FAILED")));}
        }));}catch(Exception failure){send(viewer,request.operationId(),"receipt",sessions.complete(request,Code.FAILED,Map.of("errorCode","CLIENT_SCRIPT_PREPARE_FAILED")));}
    }
    private void clientJavaPrepare(ServerPlayer viewer,Request request){
        var args=request.arguments();var runtime=ServerPackageRuntime.get(server);UUID id=UUID.fromString(args.get("packageId"));long revision=Long.parseLong(args.get("packageRevision"));String hash=args.get("canonical");try{runtime.prepareClientJava(viewer.getUUID(),id,revision,hash).whenComplete((body,error)->server.execute(()->{if(runtime.closed())return;runtime.clientJavaPrepared(viewer.getUUID());try{if(error!=null)throw new IllegalStateException("CLIENT_JAVA_PREPARE_FAILED");restoreScope(viewer,request);if(server.getPlayerList().getPlayer(viewer.getUUID())!=viewer||sessions.checkRead(viewer.getUUID(),request,"package.preview")!=Code.OK)throw new SecurityException("CLIENT_JAVA_SESSION_CHANGED");var offer=runtime.clientJavaOffer(viewer.getUUID(),request.sessionId(),id,revision,hash,body);send(viewer,request.operationId(),"receipt",sessions.complete(request,Code.ACCEPTED,Map.of("packageId",id.toString(),"packageRevision",Long.toString(revision),"canonical",hash,"transferId",offer.transferId().toString(),"size",Integer.toString(offer.size()),"sha256",offer.sha256(),"executionMode","DOWNLOAD_SOURCE_ONLY_LOCAL_CLIENT_JAVAC_AND_EXECUTION_CONSENT_REQUIRED")));}catch(Exception failure){send(viewer,request.operationId(),"receipt",sessions.complete(request,Code.FAILED,Map.of("errorCode","CLIENT_JAVA_PREPARE_FAILED")));}}));}catch(Exception failure){send(viewer,request.operationId(),"receipt",sessions.complete(request,Code.FAILED,Map.of("errorCode","CLIENT_JAVA_PREPARE_FAILED")));}
    }
    private void preview(ServerPlayer viewer, Request request) {
        UUID id = UUID.fromString(request.arguments().get("packageId"));
        long revision = Long.parseLong(request.arguments().get("packageRevision"));
        var runtime = ServerPackageRuntime.get(server);
        UUID patch=request.action().equals("package.patchPreview")?UUID.fromString(request.arguments().get("patchOperationId")):null;
        runtime.prepareEntry(viewer.getUUID(), id, revision,patch,Set.of("container.open","container.agent").contains(request.action())?"container":request.action().equals("package.hud")?"hud":"ui").whenComplete((bytes, failure) -> server.execute(() -> {
            runtime.prepared(viewer.getUUID());
            if (runtime.closed()) return;
            Receipt result;
            try {
                Code access = sessions.checkRead(viewer.getUUID(), request, dev.mineagent.runtime.api.ui.DeliveryProtocol.capability(request.action()));
                if (access != Code.OK || failure != null) result = sessions.complete(request, access != Code.OK ? access : Code.FAILED, Map.of("errorCode", "UI_PREVIEW_UNAVAILABLE"));
                else {
                    var offer = runtime.offer(viewer.getUUID(), request.sessionId(), id, revision, bytes,patch);
                    var values=new LinkedHashMap<String,String>(); values.put("transferId",offer.transferId().toString()); values.put("size",Integer.toString(offer.size()));
                    values.put("sha256",offer.sha256());values.put("packageId",id.toString());values.put("packageRevision",Long.toString(revision));
                    if(request.action().equals("container.agent")){
                        if(!"true".equals(request.arguments().get("confirmed")))throw new SecurityException("CONTAINER_CONSENT_REQUIRED");
                        var pkg=runtime.ownedPackage(viewer.getUUID(),id,revision).orElseThrow();
                        var expected=json.readValue(request.arguments().get("expected"),dev.mineagent.runtime.core.ui.ContainerExpectation.class);
                        var opened=uiAgents.openContainer(viewer,sessions.get(viewer.getUUID(),request.sessionId()).orElseThrow(),UUID.fromString(request.arguments().get("agentId")),pkg,request.arguments().get("goal"),expected);
                        values.put("contentSession",json.writeValueAsString(opened));values.put("taskId",opened.binding().taskId().toString());
                    }
                    if(request.action().equals("container.open")){
                        if(!"true".equals(request.arguments().get("confirmed")))throw new SecurityException("CONTAINER_CONSENT_REQUIRED");
                        var pkg=runtime.ownedPackage(viewer.getUUID(),id,revision).orElseThrow();UUID resource=containers.open(viewer,id,revision);
                        try{
                            var binding=new Binding(UUID.randomUUID().toString(),id,revision,pkg.version(),dev.mineagent.runtime.core.packages.PackageUiEntrypoints.named(pkg.entrypoints(),"container").orElseThrow(),MineAgentRuntimeServices.worldId(server),viewer.getUUID(),viewer.getUUID(),ActorKind.PLAYER,null,0,resource.toString(),false,dev.mineagent.runtime.api.ui.ContainerProtocol.CAPABILITIES);
                            var opened=sessions.open(binding,true,true,1_800_000);containers.attach(resource,opened);values.put("contentSession",json.writeValueAsString(opened));
                        }catch(Exception invalid){containers.close(resource);throw invalid;}
                    }
                    if(Set.of("package.open","package.hud","package.restore").contains(request.action())||(patch!=null&&request.arguments().containsKey("targetViewId"))) {
                        var pkg=(patch==null?runtime.ownedPackage(viewer.getUUID(),id,revision):runtime.candidate(viewer.getUUID(),patch)).orElseThrow();
                        var binding=new Binding(UUID.randomUUID().toString(),id,revision,pkg.version(),dev.mineagent.runtime.core.packages.PackageUiEntrypoints.select(pkg.entrypoints(),request.action().equals("package.hud")).orElseThrow(),
                                MineAgentRuntimeServices.worldId(server),viewer.getUUID(),viewer.getUUID(),ActorKind.PLAYER,null,0,
                                UUID.fromString(request.arguments().get("targetViewId")).toString(),patch!=null,
                                dev.mineagent.runtime.core.ui.ScoreContentPolicy.capabilities(request.action(),mayEditScores(viewer)));
                        scoreBridge().requireTarget(binding,audience(viewer));
                        if(patch!=null)candidateViews.pending(binding,patch,System.currentTimeMillis()+1_800_000);
                        Session opened;
                        try{opened=sessions.open(binding,true,true,request.action().equals("package.hud")?hudLeaseMillis():1_800_000);}catch(Exception e){candidateViews.remove(binding.viewId());throw e;}
                        if(patch!=null)candidateViews.admitted(opened);
                        if(request.action().equals("package.restore")){takeovers.register(opened);MineAgentRuntimeServices.audit(server).record(viewer.getUUID().toString(),"UI_TAKEOVER_READ_ONLY",binding.targetObjectId(),json.writeValueAsString(opened));}
                        values.put("contentSession",json.writeValueAsString(opened));
                    }
                    result = sessions.complete(request, Code.ACCEPTED, values);
                }
            } catch (Exception e) { result = sessions.complete(request, Code.FAILED, Map.of("errorCode", "UI_PREVIEW_UNAVAILABLE")); }
            send(viewer, request.operationId(), "receipt", result);
        }));
    }
    public void packageChanged(UUID packageId,long requestedRevision){
        if(!server.isSameThread())throw new IllegalStateException("SERVER_THREAD_REQUIRED");
        containers.closePackage(packageId);
        for(var viewer:server.getPlayerList().getPlayers())if(!(viewer instanceof MineAgentPlayer))
            for(var s:List.copyOf(sessions.list(viewer.getUUID())))if(s.binding().ownerPackageId().equals(packageId)&&s.binding().actorKind()==ActorKind.AGENT)uiAgents.interrupt(viewer.getUUID(),s.sessionId());
        for(var old:sessions.invalidatePackage(packageId)){
            candidateViews.remove(old.binding().viewId());var viewer=server.getPlayerList().getPlayer(old.binding().viewerPlayerId());
            if(viewer!=null)send(viewer,UUID.randomUUID(),"packageViewOutdated",Map.of("viewId",old.binding().viewId(),"sessionId",old.sessionId(),"requestedRevision",requestedRevision));
        }
    }
    private static long hudLeaseMillis(){return Boolean.getBoolean("mineagent.scoreHudRenewSmoke")?6000:1_800_000;}
    ScoreUiBridge scoreBridge() { return new ScoreUiBridge(MineAgentRuntimeServices.scoreboards(server)); }
    ServerContainerRuntime containers(){return containers;}
    private boolean mayEditScores(ServerPlayer player) {
        return MineAgentRuntimeServices.permissions(server).allowed(player.getUUID(),player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER),PermissionAction.MANAGE_SCOREBOARD);
    }
    private dev.mineagent.runtime.api.agent.AgentDefinition personaAgent(UUID agent){return MineAgentRuntimeServices.bodies(server).definitions().stream().filter(d->d.agentId().equals(agent)).findFirst().orElse(null);}
    boolean mayDelegate(ServerPlayer viewer,UUID agent){return viewer!=null&&mayEditScores(viewer)
            &&MineAgentRuntimeServices.permissions(server).canMutateAgent(MineAgentRuntimeServices.bodies(server).definitions().stream().filter(a->a.agentId().equals(agent)).findFirst().orElse(null),
                    viewer.getUUID(),viewer.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER));}
    private boolean mayStartUi(ServerPlayer viewer){return MineAgentRuntimeServices.permissions(server).allowed(viewer.getUUID(),viewer.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER),PermissionAction.START_TASK);}
    boolean mayControlContainerAgent(ServerPlayer viewer,UUID agent){return viewer!=null&&mayStartUi(viewer)&&MineAgentRuntimeServices.permissions(server).canMutateAgent(MineAgentRuntimeServices.bodies(server).definitions().stream().filter(a->a.agentId().equals(agent)).findFirst().orElse(null),viewer.getUUID(),viewer.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER));}
    boolean mayDelegate(ServerPlayer viewer,UUID agent,Binding binding){
        if(dev.mineagent.runtime.api.ui.WorldUiProtocol.bound(binding))return mayControlContainerAgent(viewer,agent);
        if(!dev.mineagent.runtime.api.ui.UiInteractionScope.pageOnly(binding)&&!dev.mineagent.runtime.api.ui.ContainerProtocol.bound(binding))return mayDelegate(viewer,agent);
        return viewer!=null&&mayStartUi(viewer)&&MineAgentRuntimeServices.permissions(server).canMutateAgent(MineAgentRuntimeServices.bodies(server).definitions().stream().filter(a->a.agentId().equals(agent)).findFirst().orElse(null),viewer.getUUID(),viewer.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER));
    }
    ScoreAudienceContext audience(ServerPlayer viewer) {
        var team=viewer.getTeam();
        return new ScoreAudienceContext(viewer.getUUID(),team==null?Set.of():Set.of(team.getName()),Set.of(),Set.of());
    }
    @SubscribeEvent public static void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            ServerUiRuntime runtime;
            synchronized (ServerUiRuntime.class) { runtime = RUNTIMES.get(player.level().getServer()); }
            if (runtime != null) { runtime.desktopPlans.disconnect(player.getUUID());runtime.deliveries.disconnect(player.getUUID());runtime.worldUi.disconnect(player.getUUID());runtime.containers.closeViewer(player.getUUID());runtime.candidateViews.disconnect(player.getUUID());runtime.takeovers.disconnect(player.getUUID());runtime.uiAgents.disconnect(player.getUUID());runtime.sessions.disconnect(player.getUUID()); runtime.rateWindows.remove(player.getUUID()); runtime.rateCounts.remove(player.getUUID()); }
            dev.mineagent.runtime.neoforge.compile.NativeLiveClassAccess.clear(player.getUUID());
            ServerPackageRuntime.disconnect(player.level().getServer(), player.getUUID());
        }
    }
    @SubscribeEvent public static void tick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event){ServerUiRuntime runtime;synchronized(ServerUiRuntime.class){runtime=RUNTIMES.get(event.getServer());}if(runtime!=null){runtime.desktopPlans.tick();runtime.deliveries.tick();runtime.worldUi.expire();runtime.containers.tick();runtime.candidateViews.expire();runtime.uiAgents.tick();if(event.getServer().getTickCount()%20==0)try{MineAgentNetwork.reconcileAppearanceDecisions(event.getServer());}catch(RuntimeException failure){dev.mineagent.runtime.neoforge.MineAgentRuntimeMod.LOGGER.warn("Appearance decision reconciliation failed: {}",failure.getClass().getSimpleName());}}}
    @SubscribeEvent public static synchronized void stopped(ServerStoppedEvent event) {var r=RUNTIMES.remove(event.getServer());if(r!=null){r.desktopPlans.close();r.deliveries.close();r.worldUi.close();r.containers.close();r.candidateViews.clear();r.takeovers.clear();r.uiAgents.close();}}
}
