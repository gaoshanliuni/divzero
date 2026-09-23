package dev.mineagent.runtime.client.control;

import dev.mineagent.runtime.api.config.PanelSection;

import java.util.Arrays;
import java.util.List;

public final class ControlCenterModel {
    private final List<PanelSection> sections;
    private PanelSection selectedSection;

    private ControlCenterModel(boolean operator) {
        this(operator,java.util.Set.of());
    }
    private ControlCenterModel(boolean operator,java.util.Set<PanelSection> additionallyVisible){
        sections = Arrays.stream(PanelSection.values())
                .filter(section -> !java.util.Set.of(PanelSection.CODE_STUDIO,PanelSection.MEDIA,PanelSection.SCOREBOARDS,PanelSection.CREATOR).contains(section))
                .filter(section -> operator || !section.operatorOnly() || additionallyVisible.contains(section))
                .toList();
        selectedSection = sections.getFirst();
    }

    public static ControlCenterModel forOperator() {
        return new ControlCenterModel(true);
    }

    public static ControlCenterModel forRegularPlayer() {
        return new ControlCenterModel(false);
    }
    public static ControlCenterModel forProviderManager(){return new ControlCenterModel(false,java.util.Set.of(PanelSection.PROVIDERS));}

    public List<PanelSection> sections() {
        return sections;
    }

    public PanelSection selectedSection() {
        return selectedSection;
    }

    public boolean select(PanelSection section) {
        if (!sections.contains(section)) {
            return false;
        }
        selectedSection = section;
        return true;
    }

    public List<PanelSection> page(int offset, int limit) {
        if (offset < 0 || limit < 1) {
            throw new IllegalArgumentException("invalid page bounds");
        }
        if (offset >= sections.size()) {
            return List.of();
        }
        return sections.subList(offset, Math.min(sections.size(), offset + limit));
    }
}
