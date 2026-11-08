package dev.mineagent.runtime.core.config;

import java.util.List;
import java.util.Optional;

/** Shared address templates. Choosing a template does not authenticate or call a provider. */
public final class ProviderBaseUrls {
    public record Preset(String name, String baseUrl) {}
    public static final List<Preset> ALL = List.of(
            new Preset("DeepSeek", "https://api.deepseek.com/v1/"),
            new Preset("OpenAI", "https://api.openai.com/v1/"),
            new Preset("Claude", "https://api.anthropic.com/v1/"),
            new Preset("Google Gemini", "https://generativelanguage.googleapis.com/v1beta/openai/"),
            new Preset("Kimi 月之暗面", "https://api.moonshot.cn/v1/"),
            new Preset("Qwen 阿里云", "https://dashscope.aliyuncs.com/compatible-mode/v1/"),
            new Preset("MiniMax", "https://api.minimax.cn/v1/"),
            new Preset("豆包 火山方舟", "https://ark.cn-beijing.volces.com/api/v3/"),
            new Preset("MiMo 小米", "https://api.xiaomimimo.com/v1/"),
            new Preset("OpenRouter", "https://openrouter.ai/api/v1/"),
            new Preset("SiliconFlow 硅基流动", "https://api.siliconflow.cn/v1/"),
            new Preset("GLM 智谱", "https://open.bigmodel.cn/api/paas/v4/"),
            new Preset("GLM Z.AI", "https://api.z.ai/api/paas/v4/"),
            new Preset("Ollama", "http://localhost:11434/v1/")
    );
    public static Optional<Preset> named(String name) { return ALL.stream().filter(p -> p.name().equals(name)).findFirst(); }
    public static Optional<Preset> forAddress(String address) {
        String value = address == null ? "" : address.strip().replaceFirst("/+$", "");
        return ALL.stream().filter(p -> p.baseUrl().replaceFirst("/+$", "").equals(value)).findFirst();
    }
    private ProviderBaseUrls() {}
}
