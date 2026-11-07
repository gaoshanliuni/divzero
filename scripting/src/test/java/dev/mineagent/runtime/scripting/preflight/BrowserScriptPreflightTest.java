package dev.mineagent.runtime.scripting.preflight;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class BrowserScriptPreflightTest {
    @Test void parsesModernBrowserModulesWithoutExecutingThem() {
        var result = new BrowserScriptPreflight().inspect("export async function f() { const x = await fetch('/data'); return x?.status ?? 0; }");
        assertTrue(result.accepted(), result.diagnostics().toString());
    }
    @Test void rejectsBrowserSyntaxErrorsAndProvablyUnboundedLoops() {
        var preflight = new BrowserScriptPreflight();
        assertFalse(preflight.inspect("export const = ;").accepted());
        assertFalse(preflight.inspect("while(true) { window.sideEffect(); }").accepted());
        assertTrue(preflight.inspect("for(let i=0;i<10;i++) document.body.dataset.x=i;").accepted());
    }
    @Test void doesNotRunTargetCodeOrTreatBrowserGlobalsAsRhinoBindings() {
        System.clearProperty("mineagent.browserPreflight.executed");
        assertTrue(new BrowserScriptPreflight().inspect("Java.type('java.lang.System').setProperty('mineagent.browserPreflight.executed','yes');").accepted());
        assertNull(System.getProperty("mineagent.browserPreflight.executed"));
    }
    @Test void doesNotRejectAConstantLoopWithAnExplicitExitAsProvenInfinite() {
        assertTrue(new BrowserScriptPreflight().inspect("while (true) { break; }").accepted());
    }
}
