package dev.mineagent.runtime.scripting.preflight;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ScriptPreflightTest {
    private final ScriptPreflight preflight = new ScriptPreflight();

    @Test
    void acceptsFiniteScript() {
        PreflightResult result = preflight.inspect("for (let i = 0; i < 10; i++) { runtime.log(i); }");

        assertTrue(result.accepted());
        assertTrue(result.diagnostics().isEmpty());
    }

    @Test
    void reportsSyntaxErrorsWithoutExecutingScript() {
        PreflightResult result = preflight.inspect("function broken( {");

        assertFalse(result.accepted());
        assertEquals("SYNTAX_ERROR", result.diagnostics().getFirst().code());
    }

    @Test void acceptsConditionalExitAndReturnFromCurrentFunction() {
        assertTrue(preflight.inspect("while (true) { if (finished) break; doWork(); }").accepted());
        assertTrue(preflight.inspect("for (;;) { if (shouldStop()) break; process(); }").accepted());
        assertTrue(preflight.inspect("function run() { while (true) { if (ready()) return 1; step(); } }").accepted());
        assertTrue(preflight.inspect("outer: while(true) { for(var i=0;i<5;i++){ if(done()) break outer; } }").accepted());
    }
    @Test void nestedSwitchFunctionAndUnreachableBreakDoNotExitOuterLoop() {
        assertFalse(preflight.inspect("while(true) { switch(x){case 1: break;} }").accepted());
        assertFalse(preflight.inspect("while(true) { function later(){return 1;} }").accepted());
        assertFalse(preflight.inspect("while(true) { if(false) break; }").accepted());
        assertFalse(preflight.inspect("for(;true;) { try { throw 1; } catch(e) {} }").accepted());
    }

    @Test
    void rejectsObviousInfiniteWhileLoop() {
        PreflightResult result = preflight.inspect("while (true) { runtime.tick(); }");

        assertFalse(result.accepted());
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.code().equals("INFINITE_LOOP")));
    }

    @Test
    void rejectsForLoopWithoutCondition() {
        PreflightResult result = preflight.inspect("for (;;) { runtime.tick(); }");

        assertFalse(result.accepted());
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.code().equals("INFINITE_LOOP")));
    }
}
