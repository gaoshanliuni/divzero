# 模型 API 地址预置

2026-11-08：在原有 DeepSeek、OpenAI、GLM 智谱、GLM Z.AI 和 Ollama 基础上，新增以下九家地址。F2 设置中的“模型服务 → 兼容 API 服务地址 → 地址模板”与其他设置入口共用同一份目录。

| 预置 | BaseURL |
| --- | --- |
| Claude | `https://api.anthropic.com/v1/` |
| Google Gemini | `https://generativelanguage.googleapis.com/v1beta/openai/` |
| Kimi 月之暗面 | `https://api.moonshot.cn/v1/` |
| Qwen 阿里云 | `https://dashscope.aliyuncs.com/compatible-mode/v1/` |
| MiniMax | `https://api.minimax.cn/v1/` |
| 豆包 火山方舟 | `https://ark.cn-beijing.volces.com/api/v3/` |
| MiMo 小米 | `https://api.xiaomimimo.com/v1/` |
| OpenRouter | `https://openrouter.ai/api/v1/` |
| SiliconFlow 硅基流动 | `https://api.siliconflow.cn/v1/` |

选择模板只填入地址草稿，点击保存后才生效。API Key 和模型名称仍需配置；自定义模型 ID、手动地址与其他地域/代理入口继续可用。切换已有密钥使用的地址时，保留原有确认流程。预置选择本身不调用模型，也不代表账户权限或模型能力已经验证。

地址采用官方 OpenAI 兼容接口，客户端在基础地址后追加 `models`、`chat/completions`，保留完整路径。Claude 的兼容入口并不等于原生 Messages API 的全部功能；不同服务的工具、图片、推理和模型目录能力仍由现有能力设置/检测和实际返回决定。豆包可以填写自己的模型 ID 或推理接入点 ID。

国内平台默认使用国内通用地址。阿里云官方仍支持这里的通用域名，也提供业务空间专属域名；可手动覆盖。MiniMax 使用核对时官方国内文档的 `api.minimax.cn`，国际站为 `api.minimax.io`，不自动混用不同站点的密钥。

核对来源：

- [Claude 的 OpenAI 兼容文档](https://platform.claude.com/docs/en/cli-sdks-libraries/libraries/openai-sdk)
- [Gemini 的 OpenAI 兼容文档](https://ai.google.dev/gemini-api/docs/openai)
- [Kimi API 常见问题](https://www.kimi.com/help/kimi-api/api-troubleshooting)
- [阿里云 OpenAI 兼容接口](https://help.aliyun.com/zh/model-studio/compatibility-of-openai-with-dashscope)
- [MiniMax 国内 OpenAI 接口](https://platform.minimax.cn/docs/api-reference/text-openai-api)
- [火山方舟地址与认证](https://docs.volcengine.com/docs/ark/base-url-and-authentication?lang=zh)
- [MiMo 首次 API 调用](https://mimo.mi.com/docs/en-US/quick-start/summary/first-api-call)
- [OpenRouter 开发者入口](https://openrouter.ai/developers)
- [SiliconFlow 快速开始](https://api-docs.siliconflow.cn/docs/userguide/quickstart)

验证状态：源码编译、请求路径与设置界面回归进行中；尚未使用九家真实密钥逐个发起模型生成请求。未自动发布 Release 或替换正式主包。
