package dev.mineagent.runtime.client.control;

import dev.mineagent.runtime.api.config.PanelSection;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ControlCenterModelTest {
    @Test void delegatedProviderEditorDoesNotAcquireUnrelatedAdminSections(){var model=ControlCenterModel.forProviderManager();assertTrue(model.select(PanelSection.PROVIDERS));assertFalse(model.sections().contains(PanelSection.BACKUPS));assertFalse(model.sections().contains(PanelSection.DIAGNOSTICS));}
    @Test
    void operatorSeesAllSixteenSectionsInStableOrder() {
        var model = ControlCenterModel.forOperator();

        assertEquals(12, model.sections().size());
        assertEquals(PanelSection.OVERVIEW, model.sections().getFirst());
        assertEquals(PanelSection.DIAGNOSTICS, model.sections().getLast());
    }

    @Test
    void regularPlayerDoesNotSeeAdministrativeSections() {
        var model = ControlCenterModel.forRegularPlayer();

        assertTrue(model.sections().contains(PanelSection.AGENTS));
        assertTrue(model.sections().contains(PanelSection.CONVERSATIONS));
        assertFalse(model.sections().contains(PanelSection.SCOREBOARDS));
        assertFalse(model.sections().contains(PanelSection.PROVIDERS));
        assertTrue(model.sections().contains(PanelSection.PERMISSIONS));
        assertFalse(model.sections().contains(PanelSection.BACKUPS));
        assertFalse(model.sections().contains(PanelSection.DIAGNOSTICS));
    }

    @Test
    void changesTheSelectedSectionOnlyWhenItIsVisible() {
        var playerModel = ControlCenterModel.forRegularPlayer();

        assertTrue(playerModel.select(PanelSection.TASKS));
        assertEquals(PanelSection.TASKS, playerModel.selectedSection());
        assertFalse(playerModel.select(PanelSection.PROVIDERS));
        assertEquals(PanelSection.TASKS, playerModel.selectedSection());
    }

    @Test
    void exposesEverySectionThroughPagedNavigation() {
        var model = ControlCenterModel.forOperator();

        assertEquals(6, model.page(0, 6).size());
        assertEquals(PanelSection.OVERVIEW, model.page(0, 6).getFirst());
        assertEquals(PanelSection.DIAGNOSTICS, model.page(6, 6).getLast());
        assertTrue(model.page(100, 6).isEmpty());
    }
}
