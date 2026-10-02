package dev.mineagent.runtime.core.conversation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;

/** Agent selection is routing, never an alternate player/permission identity. */
public final class AgentToolScope {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final Set<String> TARGETABLE=Set.of("observe","stop_actions","inspect_operations",
        "inspect_agent_settings","set_agent_setting","inspect_behavior","inspect_skills","set_behavior_mode","start_skill","set_combat_policy","control_behavior","control_skill",
        "follow_entity","patrol_route","guard_area","farm_area","fish_at","wander_area","combat_entity",
        "inspect_agent_body","control_agent_body","set_actor_enhancements","inspect_persona","set_persona",
        "inspect_appearance","set_appearance","inspect_skins","open_skin_ui","export_current_skin","create_skin_png","set_skin_png",
        "inspect_chat_settings","set_chat_settings","set_chat_color");
    public static boolean supports(String tool){return TARGETABLE.contains(tool);}
    public static UUID target(String tool,ObjectNode args,UUID current){
        if(!args.has("agent_id"))return current;
        if(!supports(tool)||!args.get("agent_id").isTextual())throw new IllegalArgumentException("AGENT_TARGET_ARGUMENTS");
        return UUID.fromString(args.get("agent_id").asText());
    }
    public static boolean sameOwner(UUID viewer,UUID sourceOwner,UUID targetOwner){return viewer!=null&&viewer.equals(sourceOwner)&&viewer.equals(targetOwner);}
    public static ConversationTools.Definition decorate(ConversationTools.Definition d){
        if(!supports(d.name()))return d;
        try{var schema=(ObjectNode)JSON.readTree(d.parameters());((ObjectNode)schema.get("properties")).putObject("agent_id").put("type","string").put("format","uuid").put("description","省略为当前AI；可填inspect_owned_agents返回的同一所有者AI的agent_id。不是actor或跟随target。");
            return new ConversationTools.Definition(d.name(),d.description()+" 可用 agent_id 指定当前玩家名下另一 AI；先读取该目标的状态与版本。",schema.toString());
        }catch(java.io.IOException error){throw new IllegalStateException("AGENT_TARGET_SCHEMA",error);}
    }
    public static ConversationTools.Definition definition(String name){
        var schema=JSON.createObjectNode().put("type","object").put("additionalProperties",false);var p=schema.putObject("properties");var required=schema.putArray("required");String description;
        if(name.equals("inspect_owned_agents")){
            p.putObject("query").put("type","string").put("maxLength",128);p.putObject("offset").put("type","integer").put("minimum",0);
            description="分页列出当前玩家创建且与当前对话AI同一所有者的全部AI，含agent_id、名字、维度、位置、身体状态；每页16个，nextOffset=-1结束。所有AI跟随我：遍历全部页，逐个用agent_id调用follow_entity(target=$owner)，分别核对回执。不能用实体名猜UUID或只改自己后宣称全部完成。";
        }else if(name.equals("inspect_agent_settings")){
            description="读取目标AI的名字和revision、实际游戏模式、自动重生策略及其revision、当前语言模型选择与revision；不返回API Key。人设/行为/外观等分别使用对应inspect工具。";
        }else if(name.equals("set_agent_setting")){
            p.putObject("setting").put("type","string").putArray("enum").add("name").add("game_mode").add("auto_respawn").add("model");required.add("setting");
            p.putObject("expected_revision").put("type","integer").put("minimum",0);p.putObject("name").put("type","string").put("minLength",1).put("maxLength",48);
            p.putObject("mode").put("type","string").putArray("enum").add("SURVIVAL").add("CREATIVE").add("ADVENTURE");p.putObject("expected_mode").put("type","string");p.putObject("enabled").put("type","boolean");
            p.putObject("model_mode").put("type","string").putArray("enum").add("DEFAULT").add("CUSTOM");p.putObject("model").put("type","string").put("maxLength",256);p.putObject("base_url").put("type","string").put("maxLength",2048);
            description="按玩家要求修改一个AI设置，先inspect_agent_settings。name需name与AI的expected_revision；game_mode需mode与expected_mode；auto_respawn需enabled与由respawn.revision取得的expected_revision；model需model_mode、expected_revision、model和base_url（来自model读取，DEFAULT用空字符串）。模型仍使用现有Provider权限和地址绑定。只修改所选字段，不修改协作权限、不创建/删除AI。";
        }else throw new IllegalArgumentException("AGENT_SETTING_TOOL");
        return new ConversationTools.Definition(name,description,schema.toString());
    }
    private AgentToolScope(){}
}
