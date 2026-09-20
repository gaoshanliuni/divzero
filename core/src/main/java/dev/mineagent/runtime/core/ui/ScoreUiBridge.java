package dev.mineagent.runtime.core.ui;

import dev.mineagent.runtime.api.scoreboard.ScoreViewSnapshot;
import dev.mineagent.runtime.api.ui.UiProtocol.Binding;
import dev.mineagent.runtime.core.scoreboard.*;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Thin semantic bridge over existing ScoreView/source data, independent of DOM/layout implementation. */
public final class ScoreUiBridge {
    public record State(UUID sourceId, String sourceReference, long viewRevision, ScoreViewSnapshot snapshot) {}
    private final ScoreboardService scores;
    private final ScoreboardAudienceResolver audiences = new ScoreboardAudienceResolver();
    public ScoreUiBridge(ScoreboardService scores) { this.scores = Objects.requireNonNull(scores); }
    public State read(Binding binding, ScoreAudienceContext viewer) {
        ScoreView view = requireTarget(binding, viewer);
        if (!binding.capabilities().contains("scoreview.read")) throw new SecurityException("SCORE_VIEW_READ_DENIED");
        var source = scores.source(view.sourceId()).orElseThrow(() -> new IllegalStateException("SCORE_SOURCE_MISSING"));
        return new State(source.sourceId(), source.reference(), view.revision(), scores.project(view.viewId()));
    }
    public State patch(Binding binding, ScoreAudienceContext viewer, boolean actorMayEdit,
                       long expectedViewRevision, Map<String, String> patch) throws Exception {
        ScoreView view = requireTarget(binding, viewer);
        if (binding.preview() || !actorMayEdit || !binding.capabilities().contains("scoreview.patch"))
            throw new SecurityException("SCORE_VIEW_WRITE_DENIED");
        read(binding,viewer); // Refuse missing/read-forbidden targets before any persistent layout mutation.
        scores.patchLayout(view.viewId(), binding.ownerPackageId(), expectedViewRevision, patch);
        return read(binding, viewer); // Actual authoritative readback, not the desired values supplied by the page.
    }
    public ScoreView requireTarget(Binding binding, ScoreAudienceContext viewer) {
        if (viewer == null || !binding.viewerPlayerId().equals(viewer.playerId())) throw new SecurityException("VIEWER_MISMATCH");
        ScoreView view;
        try { view = scores.view(UUID.fromString(binding.targetObjectId())).orElseThrow(() -> new IllegalStateException("SCORE_VIEW_MISSING")); }
        catch (IllegalArgumentException invalid) { throw new IllegalStateException("SCORE_VIEW_MISSING"); }
        if (!view.ownerPackageId().equals(binding.ownerPackageId()) || !audiences.visible(view.audience(), viewer))
            throw new SecurityException("SCORE_VIEW_AUDIENCE_OR_PACKAGE");
        if (!view.enabled()) throw new IllegalStateException("SCORE_VIEW_DISABLED");
        return view;
    }
}
