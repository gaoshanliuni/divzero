package dev.mineagent.runtime.core.config;

import dev.mineagent.runtime.api.config.ConfigPatch;
import dev.mineagent.runtime.api.config.ConfigPatchResult;
import dev.mineagent.runtime.api.config.PanelSnapshot;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import dev.mineagent.runtime.core.persistence.SqliteConfigRepository;
import dev.mineagent.runtime.core.persistence.StoredConfig;
import java.nio.file.Path;
import java.sql.SQLException;

public final class ServerConfigService implements AutoCloseable {
    private final Map<String, String> publicValues = new LinkedHashMap<>();
    private final Map<String, String> secretValues = new LinkedHashMap<>();
    private long revision;
    private Map<String,Long> permissionGenerations=Map.of();
    public synchronized long permissionGeneration(java.util.UUID player,dev.mineagent.runtime.api.permission.PermissionAction action){return permissionGenerations.getOrDefault(PermissionGenerations.key(player,action),0L);}
    private volatile RuntimeResourceLimits resourceLimits = RuntimeResourceLimits.DEFAULT;
    private final SqliteConfigRepository repository;
    private final java.util.UUID instanceId=java.util.UUID.randomUUID();
    public java.util.UUID instanceId(){return instanceId;}

    public ServerConfigService() {
        this(null);
    }

    private ServerConfigService(SqliteConfigRepository repository) {
        this.repository = repository;
        publicValues.put("runtime.maxAgents", "4");
        publicValues.put("runtime.maxChunkTickets", "100");
        publicValues.put("runtime.agentTicketRadius", "2");
        publicValues.put("runtime.initialized", "false");
        publicValues.put("conversation.contextTokenBudget", "32768");
        publicValues.put("conversation.summary.maxCalls", "8");
        publicValues.put("conversation.summary.inputBudget", "16384");
        publicValues.put("provider.priority", "openai-compatible,ollama");
        publicValues.put(ServiceCallBudget.DAILY, "0");
        publicValues.put(ServiceCallBudget.TASK, "0");
        publicValues.put(ServiceCallBudget.REQUEST, "0");
        publicValues.put(ServiceCallBudget.PAUSED, "false");
        publicValues.put("voice.input.enabled", "false");
        publicValues.put("web.enabled", "true");
        publicValues.put("provider.asr.baseUrl", "");publicValues.put("provider.asr.model", "");publicValues.put("provider.asr.language", "");
        publicValues.put("voice.output.provider", "edge-tts");
        publicValues.put("voice.output.enabled", "true");
        publicValues.put("voice.default", "zh-CN-XiaoxiaoNeural");
        publicValues.put("voice.rate", "+0%");
        publicValues.put("voice.pitch", "+0Hz");
        publicValues.put("voice.volume", "+0%");
        publicValues.put("media.allowedHosts", "");
        publicValues.put("skill.veinMining.maxBlocks", "32");
    }

    public static ServerConfigService open(Path database) throws Exception {
        var repository = new SqliteConfigRepository(database);
        try {
            var service = new ServerConfigService(repository);
            StoredConfig stored = repository.load();
            service.revision = stored.revision();
            service.publicValues.putAll(stored.publicValues());
            service.publicValues.put("voice.input.enabled","false"); // ASR is skipped in the current delivery scope, including legacy settings.
            service.secretValues.putAll(stored.secretValues());
            service.permissionGenerations=repository.permissionGenerations();
            // Invalid stored limits fail loading rather than silently claiming a different budget.
            service.resourceLimits = RuntimeResourceLimits.from(service.publicValues);
            ConversationBudget.from(service.snapshot());
            ServiceCallBudget.from(service.publicValues);
            ProviderOrder.parse(service.publicValues.get("provider.priority"));
            return service;
        } catch (Exception failure) {
            repository.close();
            throw failure;
        }
    }

    public synchronized ConfigPatchResult apply(ConfigPatch patch, boolean operator) {
        if (!operator) {
            return ConfigPatchResult.rejected(snapshot(), "FORBIDDEN");
        }
        if (patch.expectedRevision() != revision) {
            return ConfigPatchResult.rejected(snapshot(), "STALE_REVISION");
        }

        Map<String, String> fieldErrors = validate(patch.values());
        if (!fieldErrors.isEmpty()) {
            return ConfigPatchResult.rejected(snapshot(), "VALIDATION_FAILED", fieldErrors);
        }

        var nextPublic = new LinkedHashMap<>(publicValues);
        var nextSecrets = new LinkedHashMap<>(secretValues);
        patch.values().forEach((key, value) -> {
            if (isSecret(key)) {
                if(value.isBlank())nextSecrets.remove(key);else nextSecrets.put(key, value);
            } else {
                nextPublic.put(key, value);
            }
        });

        var nextLimits = RuntimeResourceLimits.from(nextPublic);
        var nextGenerations=PermissionGenerations.advance(permissionGenerations,publicValues,nextPublic);
        if (repository != null) {
            try {
                boolean saved = repository.save(revision,
                        new StoredConfig(revision + 1, nextPublic, nextSecrets));
                if (!saved) {
                    return ConfigPatchResult.rejected(snapshot(), "STALE_REVISION");
                }
            } catch (SQLException failure) {
                return ConfigPatchResult.rejected(snapshot(), "PERSISTENCE_FAILED");
            }
        }

        publicValues.clear();
        publicValues.putAll(nextPublic);
        secretValues.clear();
        secretValues.putAll(nextSecrets);
        resourceLimits = nextLimits;
        permissionGenerations=nextGenerations;
        revision++;
        return ConfigPatchResult.accepted(snapshot());
    }

    public synchronized PanelSnapshot snapshot() {
        var masked = new LinkedHashMap<>(publicValues);
        secretValues.forEach((key,value)->{if(!value.isBlank())masked.put(key,PanelSnapshot.SECRET_CONFIGURED);});
        return new PanelSnapshot(revision, masked);
    }

    public synchronized Optional<String> secretValue(String key) {
        return Optional.ofNullable(secretValues.get(key));
    }
    /** Immutable, cheap tick/creation read; published only after successful persistence. */
    public RuntimeResourceLimits resourceLimits() { return resourceLimits; }
    public synchronized SpeechProviderConfig speechProvider(){return new SpeechProviderConfig(revision,Boolean.parseBoolean(publicValues.getOrDefault("voice.input.enabled","false")),publicValues.getOrDefault("provider.asr.baseUrl",""),publicValues.getOrDefault("provider.asr.model",""),publicValues.getOrDefault("provider.asr.language",""),secretValues.getOrDefault("provider.asr.apiKey",""));}
    /** Atomic internal Worker input. Never serialize this object into a UI response or log. */
    public record ProviderSnapshot(long revision,Map<String,String> values){
        public ProviderSnapshot{values=Map.copyOf(values);}
        @Override public String toString(){return "ProviderSnapshot[revision="+revision+", values=REDACTED]";}
    }
    public synchronized ProviderSnapshot providerSnapshot(){
        var values=new LinkedHashMap<String,String>();
        values.put("provider.priority",publicValues.getOrDefault("provider.priority",ProviderOrder.DEFAULT));
        for(String key:java.util.List.of("provider.openai.enabled","provider.openai.baseUrl","provider.openai.model","provider.ollama.enabled","provider.ollama.baseUrl","provider.ollama.model","provider.comfyui.enabled","provider.comfyui.baseUrl","provider.comfyui.workflow"))values.put(key,publicValues.getOrDefault(key,key.endsWith(".enabled")?"true":""));
        values.put("provider.openai.apiKey",secretValues.getOrDefault("provider.openai.apiKey",""));return new ProviderSnapshot(revision,values);
    }

    private static boolean isSecret(String key) {
        String normalized = key.toLowerCase(Locale.ROOT);
        return normalized.endsWith("apikey")
                || normalized.endsWith("api_key")
                || normalized.endsWith("token")
                || normalized.endsWith("secret");
    }

    private static Map<String, String> validate(Map<String, String> values) {
        var errors = new LinkedHashMap<String, String>();
        values.forEach((key, value) -> {
            if (key == null || key.isBlank() || key.length() > 256) {
                errors.put(String.valueOf(key), "无效配置键");
                return;
            }
            if (value == null || value.length() > 16_384) {
                errors.put(key, "值过长");
                return;
            }
            switch (key) {
                case ServiceCallBudget.DAILY, ServiceCallBudget.REQUEST, ServiceCallBudget.PAUSED, ServiceCallBudget.TASK -> {
                    try { ServiceCallBudget.from(Map.of(key,value)); }
                    catch(IllegalArgumentException invalid) { errors.put(key, key.equals(ServiceCallBudget.PAUSED)?"应为 true 或 false":(key.equals(ServiceCallBudget.DAILY)||key.equals(ServiceCallBudget.TASK))?"0–1000000 整数；0 不额外限制":"0–10000 整数；0 不额外限制"); }
                }
                case "runtime.maxAgents" -> validateRange(key, value, 1, 4, "1–4", errors);
                case "runtime.maxChunkTickets" -> validateRange(key, value, 0, 100, "0–100", errors);
                case "runtime.agentTicketRadius" -> validateRange(key, value, 0, 2, "0–2", errors);
                case "conversation.contextTokenBudget" -> validateRange(key, value, 1024, 131072, "1024–131072", errors);
                case "conversation.summary.maxCalls" -> validateRange(key, value, 0, 64, "0–64（0 禁止新摘要请求）", errors);
                case "conversation.summary.inputBudget" -> validateRange(key, value, 2048, 131072, "2048–131072", errors);
                case "skill.veinMining.maxBlocks" -> validateRange(key, value, 1, 128, "1–128", errors);
                case "voice.input.enabled" -> {
                    if(!"false".equals(value))errors.put(key,"v1 不支持语音输入");
                }
                case "voice.output.enabled" -> {
                    if (!("true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value))) {
                        errors.put(key, "应为 true 或 false");
                    }
                }
                case "web.enabled","provider.openai.enabled","provider.ollama.enabled","provider.comfyui.enabled" -> {
                    if(!java.util.Set.of("true","false").contains(value))errors.put(key,"应为 true 或 false");
                }
                case "provider.priority" -> {
                    try { ProviderOrder.parse(value); }
                    catch (IllegalArgumentException invalid) { errors.put(key,"填写 openai-compatible,ollama 或 ollama,openai-compatible"); }
                }
                case "provider.openai.model","provider.ollama.model","provider.asr.model" -> {
                    if(value.length()>256||value.chars().anyMatch(Character::isISOControl))errors.put(key,"模型名称最多 256 字符且不得含控制字符");
                }
                case "provider.asr.language" -> {if(!value.matches("(?:[A-Za-z]{2,3}(?:-[A-Za-z0-9]{2,8})?)?"))errors.put(key,"留空自动识别，或填写 zh / en 等语言代码");}
                case "runtime.initialized" -> {
                    if (!("true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value))) {
                        errors.put(key, "应为 true 或 false");
                    }
                }
                case "voice.default" -> validateVoice(key, value, errors);
                case "voice.rate", "voice.volume" -> {
                    if (!value.matches("[+-](?:100|[0-9]{1,2})%")) {
                        errors.put(key, "应为 -100% 至 +100%");
                    }
                }
                case "voice.pitch" -> {
                    if (!value.matches("[+-](?:100|[0-9]{1,2})Hz")) {
                        errors.put(key, "应为 -100Hz 至 +100Hz");
                    }
                }
                case "provider.openai.baseUrl", "provider.ollama.baseUrl", "provider.comfyui.baseUrl", "provider.asr.baseUrl" -> {
                    if (!value.isBlank()) {
                        try {
                            var uri = java.net.URI.create(value);
                            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))||uri.getHost()==null||uri.getRawUserInfo()!=null||uri.getRawQuery()!=null||uri.getRawFragment()!=null) {
                                errors.put(key, "使用带主机名的 HTTP(S) 地址；用户名、密码、查询参数及片段不得放入 Base URL");
                            }
                        } catch (IllegalArgumentException invalid) {
                            errors.put(key, "无效 URL");
                        }
                    }
                }
                case "provider.comfyui.workflow" -> {
                    if (!value.isBlank()) {
                        try {
                            var json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(value);
                            if (!json.isObject() || !value.contains("${prompt}")) {
                                errors.put(key, "Workflow 必须是包含 ${prompt} 的 JSON 对象");
                            }
                        } catch (Exception invalid) {
                            errors.put(key, "Workflow 必须是包含 ${prompt} 的 JSON 对象");
                        }
                    }
                }
                case "media.allowedHosts" -> {
                    var hosts = java.util.Arrays.stream(value.split(","))
                            .map(String::strip).filter(host -> !host.isBlank()).toList();
                    if (hosts.size() > 64 || hosts.stream().anyMatch(host -> host.length() > 253
                            || !(host.matches("[A-Za-z0-9.-]+") || host.matches("[A-Fa-f0-9:]+")))) {
                        errors.put(key, "使用逗号分隔的主机名或 IP");
                    }
                }
                default -> {
                    if (key.startsWith("agent.") && key.endsWith(".voice")) {
                        validateVoice(key, value, errors);
                    }
                }
            }
        });
        return Map.copyOf(errors);
    }

    private static void validateRange(
            String key,
            String value,
            int minimum,
            int maximum,
            String expected,
            Map<String, String> errors
    ) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < minimum || parsed > maximum) {
                errors.put(key, expected);
            }
        } catch (NumberFormatException invalid) {
            errors.put(key, expected);
        }
    }

    private static void validateVoice(String key, String value, Map<String, String> errors) {
        if (!value.matches("[A-Za-z0-9-]{3,80}")) {
            errors.put(key, "无效 Edge TTS 声音");
        }
    }

    @Override
    public synchronized void close() throws SQLException {
        if (repository != null) {
            repository.close();
        }
    }
}
