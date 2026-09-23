package dev.mineagent.runtime.core.config;
import dev.mineagent.runtime.api.config.PanelSnapshot;
import dev.mineagent.runtime.api.permission.PermissionAction;
import java.util.*;

/** Deliberate public projection; arbitrary config keys, credentials and workflow source never enter the browser. */
public final class WebSettingsCatalog {
    private WebSettingsCatalog(){}
    public record Field(String key,String label,String group,String type,String fallback,PermissionAction permission){}
    public static final List<Field> FIELDS=List.of(
        new Field(ServiceCallBudget.TASK,"每条 Task 派生链累计派发次数（0–1000000；不按日重置）","智能服务总预算","number","0",PermissionAction.MANAGE_PROVIDERS),
        new Field(ServiceCallBudget.PAUSED,"暂停新的智能服务调用（不取消在途请求）","智能服务总预算","boolean","false",PermissionAction.MANAGE_PROVIDERS),
        new Field(ServiceCallBudget.DAILY,"全服 UTC 每日派发次数（0–1000000；0 不额外限制）","智能服务总预算","number","0",PermissionAction.MANAGE_PROVIDERS),
        new Field(ServiceCallBudget.REQUEST,"单个 Worker requestId 派发次数（0–10000；非 Task 总数）","智能服务总预算","number","0",PermissionAction.MANAGE_PROVIDERS),
        new Field("web.enabled","允许 AI 联网检索公开网页","联网检索","boolean","true",PermissionAction.MANAGE_PROVIDERS),
        new Field("provider.priority","文本、摘要与代码生成优先使用","Provider","providerOrder","openai-compatible,ollama",PermissionAction.MANAGE_PROVIDERS),
        new Field("provider.openai.enabled","启用 OpenAI-compatible","Provider","boolean","true",PermissionAction.MANAGE_PROVIDERS),
        new Field("provider.openai.baseUrl","OpenAI-compatible Base URL","Provider","url","",PermissionAction.MANAGE_PROVIDERS),
        new Field("provider.openai.model","模型名称","Provider","text","gpt-4.1-mini",PermissionAction.MANAGE_PROVIDERS),
        new Field("provider.ollama.enabled","启用 Ollama","Ollama","boolean","true",PermissionAction.MANAGE_PROVIDERS),
        new Field("provider.ollama.baseUrl","Ollama Base URL","Ollama","url","",PermissionAction.MANAGE_PROVIDERS),
        new Field("provider.ollama.model","Ollama 模型","Ollama","text","qwen3:8b",PermissionAction.MANAGE_PROVIDERS),
        new Field("provider.comfyui.enabled","启用 ComfyUI","ComfyUI","boolean","true",PermissionAction.MANAGE_PROVIDERS),
        new Field("provider.comfyui.baseUrl","ComfyUI Base URL","ComfyUI","url","",PermissionAction.MANAGE_PROVIDERS),
        new Field("runtime.maxChunkTickets","AI 附加区块票预算（0–100）","运行资源","number","100",PermissionAction.MANAGE_PERMISSIONS),
        new Field("runtime.agentTicketRadius","每个 AI 的附加区块半径（0–2）","运行资源","number","2",PermissionAction.MANAGE_PERMISSIONS),
        new Field("conversation.contextTokenBudget","对话输入 token budget（1024–131072，保守估算）","会话与摘要预算","number","32768",PermissionAction.MANAGE_PERMISSIONS),
        new Field("conversation.summary.maxCalls","每次发送最多新摘要请求数（0–64）","会话与摘要预算","number","8",PermissionAction.MANAGE_PERMISSIONS),
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
