package dev.mineagent.runtime.core.boot;

/** Recovery decisions use durable submission markers and native receipts, never elapsed time. */
public final class BootActivationStep {
    public enum Stage { QUEUED, BUILD_SUBMITTED, INSTALL_SUBMITTED }
    public enum Action { BUILD, INSTALL, WAIT, READY_AT_RESTART, FAILED, UNKNOWN }

    public static Action next(Stage stage, String buildPhase, boolean busy) {
        if (buildPhase == null || buildPhase.isEmpty())
            return stage == Stage.QUEUED ? (busy ? Action.WAIT : Action.BUILD) : Action.UNKNOWN;
        return switch (buildPhase) {
            case "BUILDING", "CACHING", "INSTALLING" -> Action.WAIT;
            case "BUILT" -> stage == Stage.INSTALL_SUBMITTED ? Action.UNKNOWN : busy ? Action.WAIT : Action.INSTALL;
            case "INSTALLED_PENDING_RESTART" -> Action.READY_AT_RESTART;
            case "BUILD_FAILED" -> Action.FAILED;
            default -> Action.UNKNOWN;
        };
    }

    private BootActivationStep() {}
}
