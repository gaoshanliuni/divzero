package dev.mineagent.runtime.core.conversation;

public final class CooperationTools {
    public static ConversationTools.Definition definition(String name){var value=switch(name){
        case "start_cooperation"->ConversationTools.tool(name,"保存同一玩家名下多个AI的持久协作计划。plan含name、nodes和可选supplies。每个节点{id,agent:AI的agent_id,intent:完整set_behavior_mode参数,after:[前置节点ID]}；intent.kind支持GATHER/HANDOFF/PROCESS/CONSTRUCT，仅AI身体，不接管真人。依赖只在前置技能实际完成后放行；同一AI一次执行一个节点，其他AI可并行。HANDOFF的target为接收AI实际身体UUID；CONSTRUCT须该AI名下已保存的building_id/building_revision。supplies:[{gather, handoff, construction}]引用三个节点；施工缺少该物品时重新委派采集和交接，使用新操作ID，未知结果或玩家新指令时暂停，不重放。先inspect_owned_agents与inspect_behavior确认目标、工具、范围和库存；不能抢占已有工作。STARTED只表示计划已保存，后续inspect_cooperation读取实际进度，不在模型里轮询等待。","\"plan\":{\"type\":\"object\"}","\"plan\"");
        case "inspect_cooperation"->ConversationTools.tool(name,"读取本人、当前协调AI的协作计划和各步骤真实状态、操作ID、补给轮次及等待原因。省略id分页列出计划；id为start_cooperation返回的UUID。","\"id\":{\"type\":\"string\",\"format\":\"uuid\"},\"offset\":{\"type\":\"integer\",\"minimum\":0}","");
        case "control_cooperation"->ConversationTools.tool(name,"按读取的revision暂停、恢复或停止协作计划及其尚未完成的子技能。恢复会重新核对所有者、权限、身体、玩家最新指令和每个旧操作；未知子动作需先通过control_behavior reconcile核对。停止不可恢复，不重建被取消的子任务。","\"id\":{\"type\":\"string\",\"format\":\"uuid\"},\"expected_revision\":{\"type\":\"integer\",\"minimum\":1},\"action\":{\"enum\":[\"pause\",\"resume\",\"stop\"]}","\"id\",\"expected_revision\",\"action\"");
        default->throw new IllegalArgumentException("COOP_TOOL");
    };if(!name.equals("start_cooperation"))return value;
        try{
            var json=new com.fasterxml.jackson.databind.ObjectMapper();var schema=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(value.parameters());var plan=(com.fasterxml.jackson.databind.node.ObjectNode)schema.path("properties").path("plan");plan.put("additionalProperties",false);plan.putArray("required").add("name").add("nodes");var props=plan.putObject("properties");props.putObject("name").put("type","string").put("minLength",1).put("maxLength",128);
            var node=props.putObject("nodes").put("type","array").put("minItems",1).put("maxItems",128).putObject("items").put("type","object").put("additionalProperties",false);node.putArray("required").add("id").add("agent").add("intent");var fields=node.putObject("properties");fields.putObject("id").put("type","string").put("pattern","^[A-Za-z][A-Za-z0-9_-]{0,63}$");fields.putObject("agent").put("type","string").put("format","uuid");fields.putObject("after").put("type","array").putObject("items").put("type","string");
            var intent=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(dev.mineagent.runtime.core.task.SkillTools.definition("set_behavior_mode").parameters());((com.fasterxml.jackson.databind.node.ObjectNode)intent.path("properties").path("kind")).putArray("enum").add("GATHER").add("HANDOFF").add("CONSTRUCT").add("PROCESS");((com.fasterxml.jackson.databind.node.ObjectNode)intent.path("properties").path("actor")).putArray("enum").add("ai");fields.set("intent",intent);
            var supply=props.putObject("supplies").put("type","array").put("maxItems",128).putObject("items").put("type","object").put("additionalProperties",false);var required=supply.putArray("required");var links=supply.putObject("properties");for(String key:java.util.List.of("gather","handoff","construction")){required.add(key);links.putObject(key).put("type","string");}
            return new ConversationTools.Definition(name,value.description(),schema.toString());
        }catch(java.io.IOException invalid){throw new IllegalStateException("COOP_TOOL_SCHEMA",invalid);}
    }
    private CooperationTools(){}
}
