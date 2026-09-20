package dev.mineagent.runtime.neoforge.ui;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mineagent.runtime.api.agent.AgentMode;
import dev.mineagent.runtime.api.task.TaskStatus;
import dev.mineagent.runtime.neoforge.MineAgentRuntimeServices;
import dev.mineagent.runtime.neoforge.body.MineAgentPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import java.nio.file.Files;
import java.util.*;

/** Opt-in fixture creates an owned Agent only. Generation must be requested by actual trusted-page controls. */
@EventBusSubscriber(modid = "mineagent_runtime")
public final class PackageDeliverySmokeServer {
    private static UUID agent;
    public static volatile String fixtureAgentId;
    public static volatile boolean verified;
    @SubscribeEvent public static void tick(ServerTickEvent.Post event) throws Exception {
        if (!Boolean.getBoolean("mineagent.packageDeliverySmoke")) return;
        var server = event.getServer();
        var viewer = server.getPlayerList().getPlayers().stream().filter(p -> !(p instanceof MineAgentPlayer)).findFirst().orElse(null);
        if (viewer == null) return;
        if (agent == null) {
            String resume=System.getProperty("mineagent.packageDeliveryResume", "");
            if (resume.isBlank()) {
                var body = MineAgentRuntimeServices.bodies(server).create("Pkg Fixture " + UUID.randomUUID().toString().substring(0, 8), viewer, AgentMode.CREATOR);
                agent = body.agentId(); MineAgentRuntimeServices.permissions(server).registerOwnership(agent, viewer.getUUID());
            } else {
                agent=ServerPackageRuntime.get(server).list(viewer.getUUID()).stream()
                        .filter(j -> j.operationId().toString().equals(resume) && j.state().equals("PUBLISHED"))
                        .findFirst().orElseThrow(() -> new IllegalStateException("SMOKE_RESUME_NOT_OWNED")).agentId();
            }
            fixtureAgentId = agent.toString();
        }
        if (verified) return;
        var runtime = ServerPackageRuntime.get(server);
        for (var job : runtime.list(viewer.getUUID())) {
            if (!job.agentId().equals(agent)) continue;
            if (Set.of("FAILED", "STALE", "INTERRUPTED").contains(job.state())) throw new IllegalStateException("PACKAGE_DELIVERY_GENERATION_FAILED: " + job.errorCode());
            if (!job.state().equals("PUBLISHED")) continue;
            var pkg = runtime.ownedPackage(viewer.getUUID(), job.packageId(), job.packageRevision()).orElseThrow();
            var task = MineAgentRuntimeServices.tasks(server).get(job.taskId()).orElseThrow();
            if (pkg.enabled() || task.status() != TaskStatus.COMPLETED || runtime.list(viewer.getUUID()).stream().filter(j -> j.agentId().equals(agent)).count() != 1)
                throw new IllegalStateException("PACKAGE_DELIVERY_SERVER_MISMATCH");
            var root = server.getServerDirectory().resolve("package-delivery-evidence"); Files.createDirectories(root);
            var json = new ObjectMapper();
            Files.writeString(root.resolve("server.json"), json.writeValueAsString(Map.of("job", job, "task", task, "package", pkg,
                    "mode", "LIVE_PROVIDER_SERVER_ENTRY_NOT_GAMEPLAY", "duplicateSubmissionProtected", true)));
            var store = new dev.mineagent.runtime.core.content.ContentAddressedStore(server.getServerDirectory().resolve("mineagent-runtime-data/content"));
            Files.write(root.resolve("model-output.json"), store.read(job.rawOutputSha256()));
            for (var ref : pkg.resources().values()) {
                PathGuard.write(root, ref.path(), store.read(ref.sha256()));
            }
            verified = true;
            dev.mineagent.runtime.neoforge.MineAgentRuntimeMod.LOGGER.info("MINEAGENT_PACKAGE_DELIVERY_SERVER_OK task={} package={}", job.taskId(), job.packageId());
        }
    }
    private static class PathGuard {
        static void write(java.nio.file.Path root, String path, byte[] content) throws Exception {
            root = root.toAbsolutePath().normalize();
            var target = root.resolve(path).normalize(); if (!target.startsWith(root)) throw new IllegalStateException("EVIDENCE_PATH");
            Files.createDirectories(target.getParent()); Files.write(target, content);
        }
    }
}
