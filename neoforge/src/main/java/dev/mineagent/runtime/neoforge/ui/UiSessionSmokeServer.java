package dev.mineagent.runtime.neoforge.ui;

import com.google.gson.Gson;
import dev.mineagent.runtime.api.decision.*;
import dev.mineagent.runtime.neoforge.MineAgentRuntimeMod;
import dev.mineagent.runtime.neoforge.MineAgentRuntimeServices;
import dev.mineagent.runtime.neoforge.body.MineAgentPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import java.nio.file.Files;
import java.util.*;

/** Explicit development fixture; never activated in a normal game or used as a model fallback. */
@EventBusSubscriber(modid = "mineagent_runtime")
public final class UiSessionSmokeServer {
    private static volatile UUID decision;
    public static UUID decisionId(){return decision;}
    private static UUID viewer;
    private static UUID taskId;
    public static volatile boolean verified;
    private UiSessionSmokeServer() {}
    @SubscribeEvent public static void tick(ServerTickEvent.Post event) throws Exception {
        if (!Boolean.getBoolean("mineagent.uiSessionSmokeTest")) return;
        var server = event.getServer();
        if (decision == null) {
            var player = server.getPlayerList().getPlayers().stream().filter(p -> !(p instanceof MineAgentPlayer)).findFirst().orElse(null);
            if (player == null) return;
            viewer = player.getUUID(); decision = UUID.randomUUID();
            var bodies = MineAgentRuntimeServices.bodies(server);
            var agent = bodies.definitions().stream().filter(a -> a.ownerPlayerId().equals(viewer) && a.displayName().equals("UI Flow Fixture"))
                    .findFirst().orElseGet(() -> bodies.create("UI Flow Fixture", player, dev.mineagent.runtime.api.agent.AgentMode.CREATOR));
            if (MineAgentRuntimeServices.permissions(server).owner(agent.agentId()).isEmpty())
                MineAgentRuntimeServices.permissions(server).registerOwnership(agent.agentId(), viewer);
            var task = MineAgentRuntimeServices.tasks(server).create(agent.agentId(), viewer, "UI 任务恢复回归", 1,
                    List.of(new dev.mineagent.runtime.core.task.TaskStepSpec("build", Set.of()), new dev.mineagent.runtime.core.task.TaskStepSpec("scan", Set.of())));
            taskId = task.taskId();
            MineAgentRuntimeServices.decisions(server).openForTask(new DecisionRequest(decision, 1, viewer, task.revision(), DecisionKind.DESIGN,
                    "WebGUI 选择卡回归", "选择方案并附上中文意见；关闭窗口不得自动提交。",
                    List.of(new DecisionOption("a", "方案 A", "保留默认外观"), new DecisionOption("b", "方案 B", "只提交设计意图")),
                    SelectionMode.SINGLE, 1, 1, true, DecisionStatus.OPEN), MineAgentRuntimeServices.tasks(server), taskId, Set.of("build"));
            MineAgentRuntimeMod.LOGGER.info("MINEAGENT_UI_SESSION_DECISION_CREATED id={}", decision);
        }
        var service = MineAgentRuntimeServices.decisions(server);
        var state = service.get(decision).orElseThrow();
        if (state.status() != DecisionStatus.RESOLVED || verified) return;
        var task = MineAgentRuntimeServices.tasks(server).get(taskId).orElseThrow();
        if (!task.runnableStepIds().contains("build")) return;
        if (!task.runnableStepIds().contains("scan") || !task.lastChangeReason().contains("中文输入回归：保留数据"))
            throw new IllegalStateException("UI_TASK_RESUME_MISMATCH");
        var answer = service.acceptedAnswer(decision).orElseThrow();
        if (!answer.selectedOptionIds().equals(List.of("b")) || !answer.customText().equals("中文输入回归：保留数据") || answer.source() != AnswerSource.UI)
            throw new IllegalStateException("UI_DECISION_READBACK_MISMATCH");
        var session = ServerUiRuntime.get(server).sessions().list(viewer).getFirst();
        if (!session.binding().actorId().equals(viewer)) throw new IllegalStateException("UI_ACTOR_MISMATCH");
        try {
            Files.writeString(server.getServerDirectory().resolve("ui-session-server-evidence.json"), new Gson().toJson(Map.of(
                    "decision", state, "submission", answer, "session", session, "task", task, "evidence", "REAL_SERVER_READBACK_NOT_LLM_GENERATION")));
        } catch (Exception failure) { throw new IllegalStateException(failure); }
        verified = true;
        MineAgentRuntimeMod.LOGGER.info("MINEAGENT_UI_SESSION_SERVER_READBACK_OK submission={} revision={}", answer.submissionId(), state.revision());
    }
}
