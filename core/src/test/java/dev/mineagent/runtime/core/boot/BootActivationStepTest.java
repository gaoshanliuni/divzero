package dev.mineagent.runtime.core.boot;

import org.junit.jupiter.api.Test;
import static dev.mineagent.runtime.core.boot.BootActivationStep.*;
import static org.junit.jupiter.api.Assertions.*;

class BootActivationStepTest {
    @Test void busyRuntimeKeepsUnsubmittedWorkQueued() {
        assertEquals(Action.WAIT,next(Stage.QUEUED,"",true));
        assertEquals(Action.BUILD,next(Stage.QUEUED,"",false));
        assertEquals(Action.WAIT,next(Stage.BUILD_SUBMITTED,"BUILT",true));
        assertEquals(Action.INSTALL,next(Stage.BUILD_SUBMITTED,"BUILT",false));
    }
    @Test void crashBetweenMarkerAndCallNeverReplaysTheWrite() {
        assertEquals(Action.UNKNOWN,next(Stage.BUILD_SUBMITTED,"",false));
        assertEquals(Action.UNKNOWN,next(Stage.INSTALL_SUBMITTED,"BUILT",false));
        assertEquals(Action.UNKNOWN,next(Stage.INSTALL_SUBMITTED,"FILE_STATE_UNKNOWN",false));
        assertEquals(Action.UNKNOWN,next(Stage.BUILD_SUBMITTED,"BUILD_UNKNOWN",false));
    }
    @Test void actualReceiptsDistinguishWaitingFailureAndPendingRestart() {
        assertEquals(Action.WAIT,next(Stage.BUILD_SUBMITTED,"CACHING",false));
        assertEquals(Action.WAIT,next(Stage.INSTALL_SUBMITTED,"INSTALLING",false));
        assertEquals(Action.FAILED,next(Stage.BUILD_SUBMITTED,"BUILD_FAILED",false));
        assertEquals(Action.READY_AT_RESTART,next(Stage.INSTALL_SUBMITTED,"INSTALLED_PENDING_RESTART",false));
        assertEquals(Action.UNKNOWN,next(Stage.INSTALL_SUBMITTED,"REMOVED_PENDING_RESTART",false));
    }
}
