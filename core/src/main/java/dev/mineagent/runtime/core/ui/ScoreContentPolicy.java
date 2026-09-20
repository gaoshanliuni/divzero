package dev.mineagent.runtime.core.ui;

import java.util.Set;

/** A passive HUD is not an editor, a takeover restoration or a delegatable Agent source. */
public final class ScoreContentPolicy {
    private ScoreContentPolicy() {}
    public static Set<String> capabilities(String action, boolean mayEdit) {
        if (!Set.of("package.open", "package.hud", "package.restore", "package.patchPreview").contains(action))
            throw new IllegalArgumentException("CONTENT_ACTION");
        return action.equals("package.open") && mayEdit ? Set.of("scoreview.read", "scoreview.patch") : Set.of("scoreview.read");
    }
}
