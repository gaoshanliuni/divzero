package dev.mineagent.runtime.core.config;
import dev.mineagent.runtime.api.config.PanelSnapshot;
import dev.mineagent.runtime.api.permission.PermissionAction;
import java.util.*;

/** Deliberate public projection; arbitrary config keys, credentials and workflow source never enter the browser. */
public final class WebSettingsCatalog {
    private WebSettingsCatalog(){}
    public record Field(String key,String label,String group,String type,String fallback,PermissionAction permission){}
    public static final List<Field> FIELDS=List.of(
        new Field("runtime.chunkMemoryMiB","AI 附加区块估算内存预算（MiB）","服务器资源预算","number","256",PermissionAction.MANAGE_PERMISSIONS),
        new Field("resources.geometry.chunkTickets","几何任务同时加载区块预算","服务器资源预算","number","16",PermissionAction.MANAGE_PERMISSIONS),
        new Field("resources.geometry.chunkLoadsPerTick","每 Tick 几何区块加载数量预算","服务器资源预算","number","2",PermissionAction.MANAGE_PERMISSIONS),
        new Field(dev.mineagent.runtime.core.geometry.GeometryDiskBudget.KEY,"几何存储磁盘安全余量（MiB）","服务器资源预算","number","16",PermissionAction.MANAGE_PERMISSIONS),
        new Field(NavigationResourceBudget.PREFIX+"tickNodes","每 Tick 导航节点预算","服务器资源预算","number","4096",PermissionAction.MANAGE_PERMISSIONS),
        new Field(NavigationResourceBudget.PREFIX+"sliceNodes","单次导航切片节点预算","服务器资源预算","number","96",PermissionAction.MANAGE_PERMISSIONS),
        new Field(NavigationResourceBudget.PREFIX+"searchNodes","单路径搜索节点预算","服务器资源预算","number","131072",PermissionAction.MANAGE_PERMISSIONS),
        new Field(NavigationResourceBudget.PREFIX+"minMillis","高负载时导航时间预算（毫秒）","服务器资源预算","number","1",PermissionAction.MANAGE_PERMISSIONS),
        new Field(NavigationResourceBudget.PREFIX+"maxMillis","空闲时导航时间预算（毫秒）","服务器资源预算","number","8",PermissionAction.MANAGE_PERMISSIONS),
        new Field(NavigationResourceBudget.PREFIX+"targetMillis","导航调度目标 Tick 时间（毫秒）","服务器资源预算","number","45",PermissionAction.MANAGE_PERMISSIONS),

        new Field(ScriptResourceBudget.PREFIX+"packages","已加载脚本包数量告警阈值","服务器资源预算","number","128",PermissionAction.MANAGE_PERMISSIONS),
        new Field(ScriptResourceBudget.PREFIX+"modules","单脚本模块缓存数量告警阈值","服务器资源预算","number","64",PermissionAction.MANAGE_PERMISSIONS),
        new Field(ScriptResourceBudget.PREFIX+"handlers","单脚本事件处理器数量告警阈值","服务器资源预算","number","128",PermissionAction.MANAGE_PERMISSIONS),
        new Field(ScriptResourceBudget.PREFIX+"timers","单脚本定时器数量告警阈值","服务器资源预算","number","128",PermissionAction.MANAGE_PERMISSIONS),
        new Field(ScriptResourceBudget.PREFIX+"resources","单脚本跟踪资源数量告警阈值","服务器资源预算","number","256",PermissionAction.MANAGE_PERMISSIONS),
        new Field(ScriptResourceBudget.PREFIX+"callbacks","每 Tick 脚本回调数量预算","服务器资源预算","number","256",PermissionAction.MANAGE_PERMISSIONS),
        new Field(ScriptResourceBudget.PREFIX+"dispatchMillis","每 Tick 脚本调度时间预算（毫秒）","服务器资源预算","number","5",PermissionAction.MANAGE_PERMISSIONS),
        new Field(ScriptResourceBudget.PREFIX+"pendingCallbacks","待调度脚本回调预算","服务器资源预算","number","65536",PermissionAction.MANAGE_PERMISSIONS),
        new Field(ContentResourceBudget.PREFIX+"physicsSlices","每 Tick 对象模拟数量预算","服务器资源预算","number","256",PermissionAction.MANAGE_PERMISSIONS),
        new Field(ContentResourceBudget.PREFIX+"physicsMillis","每 Tick 对象模拟时间预算（毫秒）","服务器资源预算","number","2",PermissionAction.MANAGE_PERMISSIONS),
        new Field(ContentResourceBudget.PREFIX+"activeRadius","对象活跃距离（格）","服务器资源预算","number","128",PermissionAction.MANAGE_PERMISSIONS),
        new Field(ContentResourceBudget.PREFIX+"modelCacheMiB","模型资源缓存预算（MiB）","服务器资源预算","number","64",PermissionAction.MANAGE_PERMISSIONS),
        new Field(ContentResourceBudget.PREFIX+"instanceCacheMiB","实例状态缓存预算（MiB）","服务器资源预算","number","64",PermissionAction.MANAGE_PERMISSIONS),
        new Field("provider.openai.role.chat","对话模型（留空沿用 AI 当前模型）","任务模型","text","",PermissionAction.MANAGE_PROVIDERS),
        new Field("provider.openai.role.research","资料分析模型（留空沿用 AI 当前模型）","任务模型","text","",PermissionAction.MANAGE_PROVIDERS),
        new Field("provider.openai.role.code","代码生成模型（留空沿用 AI 当前模型）","任务模型","text","",PermissionAction.MANAGE_PROVIDERS),
        new Field("provider.openai.role.review","规划与结果检查模型（留空沿用 AI 当前模型）","任务模型","text","",PermissionAction.MANAGE_PROVIDERS),
        new Field("provider.openai.role.vision","视觉模型（留空沿用 AI 当前模型）","任务模型","text","",PermissionAction.MANAGE_PROVIDERS),
        new Field("provider.openai.role.embedding","语义记忆向量模型（留空使用关键词检索）","任务模型","text","",PermissionAction.MANAGE_PROVIDERS),
        new Field("provider.openai.role.small","轻量任务模型（摘要与标题）（留空沿用 AI 当前模型）","任务模型","text","",PermissionAction.MANAGE_PROVIDERS),
        new Field(ServiceCallBudget.TASK,"每条任务链累计派发次数（0–1000000；不按日重置）","智能服务总预算","number","0",PermissionAction.MANAGE_PROVIDERS),
        new Field(ServiceCallBudget.PAUSED,"暂停新的智能服务调用（不取消在途请求）","智能服务总预算","boolean","false",PermissionAction.MANAGE_PROVIDERS),
        new Field(ServiceCallBudget.DAILY,"全服 UTC 每日派发次数（0–1000000；0 不额外限制）","智能服务总预算","number","0",PermissionAction.MANAGE_PROVIDERS),
        new Field(ServiceCallBudget.REQUEST,"单次工作进程请求派发次数（0–10000；非任务总数）","智能服务总预算","number","0",PermissionAction.MANAGE_PROVIDERS),
        new Field("web.enabled","允许 AI 联网检索公开网页","联网检索","boolean","true",PermissionAction.MANAGE_PROVIDERS),
        new Field("resource.transcription.enabled","允许转写已选择的音频和视频片段","资源转写","boolean","false",PermissionAction.MANAGE_PROVIDERS),
        new Field("provider.asr.baseUrl","语音识别服务地址","资源转写","url","",PermissionAction.MANAGE_PROVIDERS),
        new Field("provider.asr.model","语音识别模型","资源转写","text","",PermissionAction.MANAGE_PROVIDERS),
        new Field("provider.asr.language","识别语言（留空自动识别）","资源转写","text","",PermissionAction.MANAGE_PROVIDERS),
        new Field("provider.priority","文本、摘要与代码生成优先使用","Provider","providerOrder","openai-compatible,ollama",PermissionAction.MANAGE_PROVIDERS),
        new Field("provider.openai.enabled","启用兼容 API","Provider","boolean","true",PermissionAction.MANAGE_PROVIDERS),
        new Field("provider.openai.baseUrl","兼容 API 服务地址","Provider","url","",PermissionAction.MANAGE_PROVIDERS),
        new Field("provider.openai.model","模型名称","Provider","text","gpt-4.1-mini",PermissionAction.MANAGE_PROVIDERS),
        new Field("provider.ollama.enabled","启用 Ollama","Ollama","boolean","true",PermissionAction.MANAGE_PROVIDERS),
        new Field("provider.ollama.baseUrl","Ollama 服务地址","Ollama","url","",PermissionAction.MANAGE_PROVIDERS),
        new Field("provider.ollama.model","Ollama 模型","Ollama","text","qwen3:8b",PermissionAction.MANAGE_PROVIDERS),
        new Field("provider.comfyui.enabled","启用 ComfyUI","ComfyUI","boolean","true",PermissionAction.MANAGE_PROVIDERS),
        new Field("provider.comfyui.baseUrl","ComfyUI 服务地址","ComfyUI","url","",PermissionAction.MANAGE_PROVIDERS),
        new Field("runtime.maxChunkTickets","AI 附加区块票总预算","运行资源","number","100",PermissionAction.MANAGE_PERMISSIONS),
        new Field("runtime.agentTicketRadius","每个 AI 的附加区块半径","运行资源","number","2",PermissionAction.MANAGE_PERMISSIONS),
        new Field("conversation.contextTokenBudget","对话输入词元预算（1024–131072，保守估算）","会话与摘要预算","number","32768",PermissionAction.MANAGE_PERMISSIONS),
        new Field("conversation.summary.maxCalls","每次发送最多新摘要请求数（0–64）","会话与摘要预算","number","8",PermissionAction.MANAGE_PERMISSIONS),
        new Field("conversation.summary.maxConcurrent","后台摘要并发预算（1–64）","会话与摘要预算","number","2",PermissionAction.MANAGE_PERMISSIONS),
        new Field("conversation.summary.outputBudget","摘要正文预算（256–65536，UTF-8 字节）","会话与摘要预算","number","8192",PermissionAction.MANAGE_PERMISSIONS),
        new Field("conversation.summary.inputBudget","每批摘要输入预算（2048–131072，UTF-8 字节）","会话与摘要预算","number","16384",PermissionAction.MANAGE_PERMISSIONS),
        new Field("voice.output.enabled","启用语音输出","运行设置","boolean","true",PermissionAction.MANAGE_PERMISSIONS),
        new Field("voice.default","默认声音","运行设置","text","zh-CN-XiaoxiaoNeural",PermissionAction.MANAGE_PERMISSIONS),
        new Field("voice.rate","语速（-100% 至 +100%）","运行设置","text","+0%",PermissionAction.MANAGE_PERMISSIONS),
        new Field("voice.pitch","音高（-100Hz 至 +100Hz）","运行设置","text","+0Hz",PermissionAction.MANAGE_PERMISSIONS),
        new Field("voice.volume","音量（-100% 至 +100%）","运行设置","text","+0%",PermissionAction.MANAGE_PERMISSIONS),
        new Field("media.allowedHosts","媒体允许主机（逗号分隔）","运行设置","text","",PermissionAction.CONTROL_PUBLIC_MEDIA),
        new Field("skill.veinMining.maxBlocks","连锁采集最大方块数（1–128）","运行设置","number","32",PermissionAction.MANAGE_PERMISSIONS),
        new Field("runtime.initialized","基础配置已完成（不代表连接已测试）","运行设置","boolean","false",PermissionAction.MANAGE_PERMISSIONS)
    );
    public static Set<String> editableKeys(){return FIELDS.stream().map(Field::key).collect(java.util.stream.Collectors.toUnmodifiableSet());}
    public static boolean safeUrl(String value){
        if(value==null||value.isBlank())return true;
        try{var u=java.net.URI.create(value);return Set.of("http","https").contains(Objects.toString(u.getScheme(),"").toLowerCase(Locale.ROOT))&&u.getHost()!=null&&u.getRawUserInfo()==null&&u.getRawQuery()==null&&u.getRawFragment()==null;}
        catch(IllegalArgumentException bad){return false;}
    }
    public static Map<String,String> project(PanelSnapshot snapshot){
        var result=new LinkedHashMap<String,String>();for(var field:FIELDS){String value=snapshot.values().getOrDefault(field.key(),field.fallback());
            if(field.key().equals("provider.priority"))value=String.join(",",ProviderOrder.parse(value));
            result.put(field.key(),field.type().equals("url")&&!safeUrl(value)?"":value);
        }return Map.copyOf(result);
    }
    /** Configuration projection only; deliberately does not probe remote capabilities or call a model. */
    public static Map<String,Object> routing(PanelSnapshot snapshot){
        var values=project(snapshot);String textProvider="",textModel="";
        for(String id:ProviderOrder.parse(values.getOrDefault("provider.priority",ProviderOrder.DEFAULT))){
            String prefix="provider."+(id.equals("openai-compatible")?"openai":"ollama")+".";
            if(configured(values,prefix)){textProvider=id;textModel=values.get(prefix+"model");break;}
        }
        boolean tools=configured(values,"provider.openai.");
        return Map.of("textProvider",textProvider,"textModel",textModel,"toolsProvider",tools?"openai-compatible":"",
                "toolsModel",tools?values.get("provider.openai.model"):"","state","CONFIGURATION_ONLY");
    }
    private static boolean configured(Map<String,String> values,String prefix){
        return "true".equals(values.getOrDefault(prefix+"enabled","true"))&&!values.getOrDefault(prefix+"baseUrl","").isBlank()
                &&safeUrl(values.get(prefix+"baseUrl"))&&!values.getOrDefault(prefix+"model","").isBlank();
    }
}
