package dev.mineagent.runtime.client.webui;
import java.util.List;
import java.util.regex.Pattern;

/** Single catalog for the trusted shell. Package files never extend this allowlist. */
public final class ShellAssetCatalog {
    private ShellAssetCatalog(){}
    public static final List<String> NAMES=List.of("provider-models.mjs","api-settings-cards.mjs","desktop-windows.mjs","studio-workspace.mjs","studio-coder.mjs","studio-editor.mjs","java-studio.mjs","preference-cards.mjs","package-asset-cards.mjs","boot-extension-cards.mjs","native-api-selection.mjs","native-api-cards.mjs","resource-pack-cards.mjs","client-script-cards.mjs","data-pack-cards.mjs","world-restore-cards.mjs","native-compatibility-cards.mjs","package-catalog-cards.mjs","generation-history-cards.mjs","service-call-budget.mjs","feedback-history-cards.mjs","sent-content-cards.mjs","task-history-cards.mjs","schedule-management-cards.mjs","event-management-cards.mjs","server-settings-cards.mjs","server-settings-state.mjs","agent-management-cards.mjs","agent-management-state.mjs","renderer-settings.mjs","index.html","shell.css","shell.mjs","window-state.mjs","view-placement.mjs","hud-state.mjs","escape-policy.mjs","decision-state.mjs","decision-cards.mjs","generation-state.mjs","generation-cards.mjs","world-board-cards.mjs","world-content-cards.mjs","world-patch-cards.mjs","world-action-cards.mjs","appearance-cards.mjs","appearance-state.mjs","persona-cards.mjs","delivery-cards.mjs","delivery-recovery.mjs","persona-state.mjs","conversation-thinking.mjs","conversation-state.mjs","conversation-cards.mjs","conversation-summary.mjs","conversation-budget.mjs","conversation-import.mjs","conversation-voice.mjs","conversation-speech.mjs","world-board-state.mjs","ui-patch-state.mjs","ui-agent-cards.mjs","ui-delegation-state.mjs","input-target.mjs","capture-region.mjs","paint-layout.mjs","native-atlas.mjs","atlas-input.mjs");
    @FunctionalInterface public interface Reader{String read(String name)throws Exception;}
    public static void verifyImports(Reader reader)throws Exception{
        var imports=Pattern.compile("(?m)^\\s*(?:import|export)\\s+(?:[^;'\"\\n]*?\\bfrom\\s*)?['\"]([^'\"\\n]+)['\"]");
        for(String name:NAMES){String source=reader.read(name);if(source==null)throw new IllegalStateException("SHELL_RESOURCE_MISSING: "+name);if(!name.endsWith(".mjs"))continue;var matcher=imports.matcher(source);while(matcher.find()){String target=matcher.group(1);if(!target.startsWith("./")||!NAMES.contains(target.substring(2)))throw new IllegalStateException("SHELL_IMPORT_NOT_SERVED: "+name+" -> "+target);}}
    }
}
