package dev.mineagent.runtime.neoforge.ui;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mineagent.runtime.api.config.*;
import dev.mineagent.runtime.api.permission.PermissionAction;
import dev.mineagent.runtime.core.config.*;
import dev.mineagent.runtime.neoforge.MineAgentRuntimeServices;
import dev.mineagent.runtime.neoforge.body.MineAgentPlayer;
import dev.mineagent.runtime.neoforge.network.MineAgentNetwork;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import java.util.*;

/** Fixed trusted-shell settings, backed by the existing configuration and authority services. */
public final class ServerSettings {
    private static final ObjectMapper JSON=new ObjectMapper();private ServerSettings(){}
    private static boolean allowed(ServerPlayer p,PermissionAction action){return !(p instanceof MineAgentPlayer)&&MineAgentRuntimeServices.permissions(p.level().getServer()).allowed(p.getUUID(),p.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER),action);}
    public static void authorize(ServerPlayer viewer,Map<String,String> args)throws Exception{
        if("permissions".equals(args.get("kind"))){if(!allowed(viewer,PermissionAction.MANAGE_PERMISSIONS))throw new SecurityException("SETTINGS_FORBIDDEN");return;}
        var node=JSON.readTree(args.getOrDefault("values","{}"));for(var entry:node.properties()){var field=WebSettingsCatalog.FIELDS.stream().filter(f->f.key().equals(entry.getKey())).findFirst().orElseThrow(()->new SecurityException("SETTINGS_FIELD_DENIED"));if(!allowed(viewer,field.permission()))throw new SecurityException("SETTINGS_FORBIDDEN");}
    }
    public static Map<String,Object> version(ServerPlayer viewer){return Map.of("revision",MineAgentRuntimeServices.config(viewer.level().getServer()).snapshot().revision(),"providers",allowed(viewer,PermissionAction.MANAGE_PROVIDERS),"permissions",allowed(viewer,PermissionAction.MANAGE_PERMISSIONS),"media",allowed(viewer,PermissionAction.CONTROL_PUBLIC_MEDIA),"resources",MineAgentRuntimeServices.bodies(viewer.level().getServer()).resourceStatus());}
    public static Map<String,Object> read(ServerPlayer viewer){
        var server=viewer.level().getServer();var config=MineAgentRuntimeServices.config(server);var snapshot=config.snapshot();var values=WebSettingsCatalog.project(snapshot);
        boolean provider=allowed(viewer,PermissionAction.MANAGE_PROVIDERS),permissions=allowed(viewer,PermissionAction.MANAGE_PERMISSIONS);
        var fields=WebSettingsCatalog.FIELDS.stream().filter(f->allowed(viewer,f.permission())).map(f->{var item=new LinkedHashMap<String,Object>();item.put("key",f.key());item.put("label",f.label());item.put("group",f.group());item.put("type",f.type());item.put("value",values.get(f.key()));item.put("warning",f.type().equals("url")&&!WebSettingsCatalog.safeUrl(snapshot.values().get(f.key()))?"已有地址含不支持的私密参数，网页不显示该地址。请明确替换或通过原生设置处理。":"");return item;}).toList();
        var result=new LinkedHashMap<String,Object>();result.put("revision",snapshot.revision());result.put("fields",fields);result.put("canProviders",provider);result.put("canPermissions",permissions);result.put("keyConfigured",provider&&snapshot.values().containsKey("provider.openai.apiKey"));result.put("workflowConfigured",provider&&!snapshot.values().getOrDefault("provider.comfyui.workflow","").isBlank());result.put("workerReady",MineAgentRuntimeServices.workerReady(server));
        var limits=new LinkedHashMap<>(MineAgentRuntimeServices.bodies(server).resourceStatus());limits.put("editable",permissions);result.put("limits",limits);
        result.put("asrKeyConfigured",provider&&snapshot.values().containsKey("provider.asr.apiKey"));
        result.put("providerRouting",provider?WebSettingsCatalog.routing(snapshot):Map.of());
        result.put("serviceBudget",provider?dev.mineagent.runtime.core.persistence.ServiceCallLedger.snapshot(
                server.getServerDirectory().resolve("mineagent-runtime-data/runtime.db"),java.time.Clock.systemUTC()):Map.of());
        result.put("providerNotes","文本、摘要与代码生成按顺序选择已启用且参数完整的 Provider；HTTP/超时失败不自动换 Provider。通用 Agent 工具规划与嵌入仍走 OpenAI-compatible，不能因文本顺序改为 Ollama 就冒称已支持其工具接口。图像先选 OpenAI-compatible，仅其未配置时选 ComfyUI（需 Workflow），派发失败不自动换服务。保存/读取不调用模型；配置齐全不代表远程服务、模型或 Key 已验证。");
        result.put("players",permissions?server.getPlayerList().getPlayers().stream().filter(p->!(p instanceof MineAgentPlayer)).map(p->Map.of("id",p.getUUID(),"name",p.getGameProfile().name())).toList():List.of());
        result.put("grants",permissions?MineAgentRuntimeServices.permissions(server).allTrustedActions().entrySet().stream().limit(64).map(e->Map.of("playerId",e.getKey(),"actions",e.getValue().stream().map(Enum::name).sorted().toList())).toList():List.of());
        result.put("permissionActions",permissions?Arrays.stream(PermissionAction.values()).filter(a->a!=PermissionAction.CHAT&&a!=PermissionAction.START_TASK).map(Enum::name).toList():List.of());return result;
    }
    public static Map<String,String> write(ServerPlayer viewer,Map<String,String> args)throws Exception{
        if(viewer instanceof MineAgentPlayer)throw new SecurityException("SETTINGS_VIEWER_REQUIRED");var server=viewer.level().getServer();if(!server.isSameThread())throw new IllegalStateException("RESOURCE_LIMITS_SERVER_THREAD_REQUIRED");var config=MineAgentRuntimeServices.config(server);String kind=args.get("kind");ConfigPatchResult result;
        if("permissions".equals(kind)){
            if(!allowed(viewer,PermissionAction.MANAGE_PERMISSIONS))throw new SecurityException("SETTINGS_FORBIDDEN");
            if(!args.keySet().equals(Set.of("kind","revision","playerId","actions","confirmed"))||!"true".equals(args.get("confirmed")))throw new IllegalArgumentException("SETTINGS_CONFIRM_REQUIRED");
            var nodes=JSON.readTree(args.get("actions"));if(!nodes.isArray()||nodes.size()>PermissionAction.values().length)throw new IllegalArgumentException("PERMISSION_ACTIONS_INVALID");var grants=EnumSet.noneOf(PermissionAction.class);for(var node:nodes){if(!node.isTextual())throw new IllegalArgumentException();grants.add(PermissionAction.valueOf(node.textValue()));}
            result=PermissionConfig.apply(config,MineAgentRuntimeServices.permissions(server),Long.parseLong(args.get("revision")),UUID.fromString(args.get("playerId")),grants,true);
        }else if("save".equals(kind)){
            if(!args.keySet().equals(Set.of("kind","revision","values","providerChangeConfirmed")))throw new IllegalArgumentException("SETTINGS_ARGUMENTS");
            var node=JSON.readTree(args.get("values"));if(!node.isObject()||node.isEmpty()||node.size()>WebSettingsCatalog.FIELDS.size())throw new IllegalArgumentException("SETTINGS_ARGUMENTS");var changes=new LinkedHashMap<String,String>();
            for(var entry:node.properties()){if(!WebSettingsCatalog.editableKeys().contains(entry.getKey())||!entry.getValue().isTextual())throw new IllegalArgumentException("SETTINGS_PUBLIC_FIELDS_ONLY");var field=WebSettingsCatalog.FIELDS.stream().filter(f->f.key().equals(entry.getKey())).findFirst().orElseThrow();if(!allowed(viewer,field.permission()))throw new SecurityException("SETTINGS_FORBIDDEN");changes.put(entry.getKey(),entry.getValue().textValue());}
            var snapshot=config.snapshot();if(changes.containsKey("provider.openai.baseUrl")&&!changes.get("provider.openai.baseUrl").equals(snapshot.values().getOrDefault("provider.openai.baseUrl",""))&&config.secretValue("provider.openai.apiKey").filter(v->!v.isBlank()).isPresent()&&!"true".equals(args.get("providerChangeConfirmed")))throw new IllegalArgumentException("PROVIDER_ADDRESS_CONFIRM_REQUIRED");
            if(changes.containsKey("provider.asr.baseUrl")&&!changes.get("provider.asr.baseUrl").equals(snapshot.values().getOrDefault("provider.asr.baseUrl",""))&&config.secretValue("provider.asr.apiKey").filter(v->!v.isBlank()).isPresent()&&!"true".equals(args.get("providerChangeConfirmed")))throw new IllegalArgumentException("PROVIDER_ADDRESS_CONFIRM_REQUIRED");
            result=config.apply(new ConfigPatch(Long.parseLong(args.get("revision")),changes),true);
        }else throw new IllegalArgumentException("SETTINGS_ACTION_INVALID");
        if(result.accepted()){
            if("save".equals(kind))ServerProviderModels.changed(viewer,JSON.readTree(args.get("values")).properties().stream().map(Map.Entry::getKey).collect(java.util.stream.Collectors.toSet()));
            MineAgentRuntimeServices.bodies(server).refreshResourceLimits();
            MineAgentNetwork.sendPanelSnapshot(viewer);
        }
        return Map.of("state",JSON.writeValueAsString(read(viewer)),"errorCode",result.errorCode(),"fieldErrors",JSON.writeValueAsString(result.fieldErrors()),"revision",Long.toString(result.snapshot().revision()));
    }
}
