package dev.mineagent.runtime.core.config;

import dev.mineagent.runtime.api.config.ConfigPatch;
import dev.mineagent.runtime.api.config.ConfigPatchResult;
import dev.mineagent.runtime.api.config.PanelSnapshot;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.nio.file.Path;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class ServerConfigServiceTest {
    @Test void webLookupHasAnAdministratorBooleanSwitchWithoutExposingAsrControls(){var config=new ServerConfigService();assertEquals("true",config.snapshot().values().get("web.enabled"));assertTrue(config.apply(new ConfigPatch(0,Map.of("web.enabled","false")),true).accepted());assertEquals("false",config.snapshot().values().get("web.enabled"));assertFalse(WebSettingsCatalog.editableKeys().contains("voice.input.enabled"));}

    @Test void clearedSecretsAreNotAdvertisedAndProviderSnapshotDoesNotMixVersions(){
        var service=new ServerConfigService();service.apply(new ConfigPatch(0,Map.of("provider.openai.apiKey","temporary-secret","provider.openai.model","a")),true);
        assertEquals("temporary-secret",service.providerSnapshot().values().get("provider.openai.apiKey"));
        service.apply(new ConfigPatch(1,Map.of("provider.openai.apiKey","")),true);
        assertTrue(service.secretValue("provider.openai.apiKey").isEmpty());assertFalse(service.snapshot().values().containsKey("provider.openai.apiKey"));
    }
    @Test void webSettingsNeverProjectSecretsEmbeddedInLegacyUrlsOrWorkflows(){
        var projected=WebSettingsCatalog.project(new PanelSnapshot(0,Map.of("provider.openai.baseUrl","https://user:secret@example.test/v1?token=hidden","provider.openai.apiKey","private","provider.comfyui.workflow","private-workflow")));
        assertFalse(projected.toString().contains("secret"));assertFalse(projected.toString().contains("hidden"));assertFalse(projected.toString().contains("private"));
        assertFalse(WebSettingsCatalog.editableKeys().contains("provider.openai.apiKey"));assertFalse(WebSettingsCatalog.editableKeys().contains("runtime.maxAgents"));
    }
    @Test void permissionStateOnlyChangesAfterAValidatedPersistentPatch(){
        var config=new ServerConfigService();var permissions=new dev.mineagent.runtime.core.permission.PermissionService();var id=java.util.UUID.randomUUID();var grants=java.util.Set.of(dev.mineagent.runtime.api.permission.PermissionAction.MANAGE_PROVIDERS);
        assertTrue(PermissionConfig.apply(config,permissions,0,id,grants,true).accepted());
        assertFalse(PermissionConfig.apply(config,permissions,0,id,java.util.Set.of(),true).accepted());assertEquals(grants,permissions.trustedActions(id));
        assertFalse(PermissionConfig.apply(config,permissions,1,id,java.util.Set.of(),false).accepted());assertEquals(grants,permissions.trustedActions(id));
    }
    @TempDir
    Path temporaryDirectory;
    @Test
    void appliesAnOperatorPatchAtomicallyAndMasksSecrets() {
        var service = new ServerConfigService();
        var patch = new ConfigPatch(0, Map.of(
                "provider.openai.baseUrl", "https://example.test/v1",
                "provider.openai.apiKey", "secret-value"
        ));

        ConfigPatchResult result = service.apply(patch, true);

        assertTrue(result.accepted());
        assertEquals(1, result.snapshot().revision());
        assertEquals("https://example.test/v1", result.snapshot().values().get("provider.openai.baseUrl"));
        assertEquals(PanelSnapshot.SECRET_CONFIGURED, result.snapshot().values().get("provider.openai.apiKey"));
        assertEquals("secret-value", service.secretValue("provider.openai.apiKey").orElseThrow());
    }

    @Test
    void rejectsStaleRevisionWithoutChangingState() {
        var service = new ServerConfigService();
        assertTrue(service.apply(new ConfigPatch(0, Map.of("runtime.maxAgents", "4")), true).accepted());

        ConfigPatchResult stale = service.apply(new ConfigPatch(0, Map.of("runtime.maxAgents", "8")), true);

        assertFalse(stale.accepted());
        assertEquals("STALE_REVISION", stale.errorCode());
        assertEquals("4", service.snapshot().values().get("runtime.maxAgents"));
        assertEquals(1, service.snapshot().revision());
    }

    @Test
    void rejectsNonOperatorMutation() {
        var service = new ServerConfigService();

        ConfigPatchResult result = service.apply(new ConfigPatch(0, Map.of("runtime.maxAgents", "8")), false);

        assertFalse(result.accepted());
        assertEquals("FORBIDDEN", result.errorCode());
        assertEquals(0, service.snapshot().revision());
    }

    @Test
    void returnsFieldErrorsWithoutPartiallyApplyingPatch() {
        var service = new ServerConfigService();
        var result = service.apply(new ConfigPatch(0, Map.of(
                "runtime.maxAgents", "0",
                "provider.openai.baseUrl", "https://valid.example/v1"
        )), true);

        assertFalse(result.accepted());
        assertEquals("VALIDATION_FAILED", result.errorCode());
        assertEquals("正整数", result.fieldErrors().get("runtime.maxAgents"));
        assertFalse(service.snapshot().values().containsKey("provider.openai.baseUrl"));
    }

    @Test
    void keepsVoiceInputDisabledInVersionOne() {
        var service = new ServerConfigService();

        var result = service.apply(new ConfigPatch(0, Map.of("voice.input.enabled", "true")), true);

        assertFalse(result.accepted());
        assertEquals("v1 不支持语音输入", result.fieldErrors().get("voice.input.enabled"));
    }

    @Test
    void persistsPublicAndSecretConfigurationAcrossServiceRestart() throws Exception {
        Path database = temporaryDirectory.resolve("runtime.db");
        try (var service = ServerConfigService.open(database)) {
            assertTrue(service.apply(new ConfigPatch(0, Map.of(
                    "provider.openai.model", "gpt-test",
                    "provider.openai.apiKey", "persistent-secret"
            )), true).accepted());
        }

        try (var reopened = ServerConfigService.open(database)) {
            assertEquals(1, reopened.snapshot().revision());
            assertEquals("gpt-test", reopened.snapshot().values().get("provider.openai.model"));
            assertEquals(PanelSnapshot.SECRET_CONFIGURED,
                    reopened.snapshot().values().get("provider.openai.apiKey"));
            assertEquals("persistent-secret", reopened.secretValue("provider.openai.apiKey").orElseThrow());
        }
    }

    @Test
    void validatesEdgeTtsVoiceAndProsodyFields() {
        var service = new ServerConfigService();

        var invalid = service.apply(new ConfigPatch(0, Map.of(
                "voice.default", "bad<voice",
                "voice.rate", "fast",
                "voice.pitch", "200Hz"
        )), true);

        assertFalse(invalid.accepted());
        assertEquals("无效 Edge TTS 声音", invalid.fieldErrors().get("voice.default"));
        assertEquals("应为 -100% 至 +100%", invalid.fieldErrors().get("voice.rate"));
        assertEquals("应为 -100Hz 至 +100Hz", invalid.fieldErrors().get("voice.pitch"));
    }

    @Test
    void validatesComfyUiWorkflowTemplate() {
        var service = new ServerConfigService();

        var result = service.apply(new ConfigPatch(0, Map.of(
                "provider.comfyui.workflow", "{\"node\":{\"text\":\"no placeholder\"}}"
        )), true);

        assertFalse(result.accepted());
        assertEquals("Workflow 必须是包含 ${prompt} 的 JSON 对象",
                result.fieldErrors().get("provider.comfyui.workflow"));
    }

    @Test
    void validatesExplicitMediaNetworkScope() {
        var service = new ServerConfigService();

        var accepted = service.apply(new ConfigPatch(0, Map.of(
                "media.allowedHosts", "media.internal,127.0.0.1")), true);
        var rejected = service.apply(new ConfigPatch(accepted.snapshot().revision(), Map.of(
                "media.allowedHosts", "https://bad.example/path")), true);

        assertTrue(accepted.accepted());
        assertFalse(rejected.accepted());
        assertEquals("使用逗号分隔的主机名或 IP", rejected.fieldErrors().get("media.allowedHosts"));
    }
}
