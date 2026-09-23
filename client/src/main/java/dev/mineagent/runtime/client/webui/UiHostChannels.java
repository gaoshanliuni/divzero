package dev.mineagent.runtime.client.webui;
import java.util.Set;
/** Trusted main-frame routing only; membership does not grant server or content authority. */
public final class UiHostChannels {
    private static final Set<String> REMOTE=Set.of("skinUi","preview","nativeChatPreferences","buildingFiles","desktopWindow","javaStudio","preferences","packageAssets","bootExtension","nativeApi","resourcePack","clientScript","packageCatalog","generationHistory","taskHistory","eventManagement","scheduleManagement","deliveryManagement","feedbackHistory","nativeCompatibility","worldRestore","dataPack","settingsAction","agentManagement","agentModelAction","rendererSettings","chatSend","chatRefresh","decisionAction","persistUiState","packageAction","delegateUi","delegateWorldUi","takeoverUi","hudRemember","hudForget","hudResetPreferences","appearanceAction","personaAction","deliveryAction","deliveryOpen","deliveryCodeDownload","deliveryDraftAction","conversationAction");
    private UiHostChannels(){}
    public static boolean remote(String channel){return channel!=null&&REMOTE.contains(channel);}
}
