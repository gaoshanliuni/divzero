package dev.mineagent.runtime.core.task;
import com.fasterxml.jackson.databind.*;import dev.mineagent.runtime.core.conversation.ConversationTools.Definition;import java.util.*;

public final class SkillTools {
    public static final Set<String> START=Set.of("start_skill","follow_entity","patrol_route","guard_area","farm_area","fish_at","wander_area","combat_entity");
    public static final Set<String> TOOLS=Set.of("start_skill","follow_entity","patrol_route","guard_area","farm_area","fish_at","wander_area","combat_entity","inspect_skills","control_skill");
    public static SkillSpec.Kind kind(String name){return switch(name){case "follow_entity"->SkillSpec.Kind.FOLLOW;case "patrol_route"->SkillSpec.Kind.PATROL;case "guard_area"->SkillSpec.Kind.GUARD;case "farm_area"->SkillSpec.Kind.FARM;case "fish_at"->SkillSpec.Kind.FISH;case "wander_area"->SkillSpec.Kind.WANDER;case "combat_entity"->SkillSpec.Kind.COMBAT;default->null;};}
    public static String canonical(String name,String raw){try{if(raw==null||raw.length()>16000)throw new IllegalArgumentException();var json=new ObjectMapper().enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);var n=json.readTree(raw);if(n==null||!n.isObject())throw new IllegalArgumentException();if(START.contains(name))SkillSpec.parse(n,kind(name));else if(name.equals("inspect_skills")){if(n.size()!=0)throw new IllegalArgumentException();}else if(name.equals("control_skill")){if(n.size()!=3||!n.path("id").isTextual()||n.path("id").asText().length()>96||!n.path("expected_revision").isIntegralNumber()||n.path("expected_revision").asLong()<1||!Set.of("pause","resume","stop","reconcile").contains(n.path("action").asText()))throw new IllegalArgumentException();}else throw new IllegalArgumentException();return n.toString();}catch(Exception e){throw new IllegalArgumentException("SKILL_TOOL_ARGUMENTS",e);}}
    public static Definition definition(String name){
        var json=new ObjectMapper();var schema=json.createObjectNode().put("type","object").put("additionalProperties",false);var props=schema.putObject("properties");var required=schema.putArray("required");
        String description;
        if(name.equals("inspect_skills")){description="查询当前AI及本人接管技能的定义、revision、生命周期、阶段、真实计数、暂停原因、子动作回执和作物适配器。";}
        else if(name.equals("control_skill")){props.putObject("id").put("type","string");props.putObject("expected_revision").put("type","integer").put("minimum",1);props.putObject("action").putArray("enum").add("pause").add("resume").add("stop").add("reconcile");required.add("id").add("expected_revision").add("action");description="暂停、恢复、取消或先读实际世界/库存核对不确定子动作。取消终止会话，不自动恢复；reconcile只观察并归档旧操作，不重新执行。";}
        else {
            props.putObject("id").put("type","string").put("pattern","^[A-Za-z][A-Za-z0-9_-]{0,95}$");props.putObject("expected_revision").put("type","integer").put("const",0);props.putObject("actor").putArray("enum").add("ai").add("player");props.putObject("dimension").put("type","string");props.putObject("target").put("type","string");
            var vector=json.createObjectNode().put("type","array").put("minItems",3).put("maxItems",3);vector.putObject("items").put("type","number");props.set("min",vector);props.set("max",vector);props.putObject("route").put("type","array").put("maxItems",128).set("items",vector);
            for(var flag:List.of("repeat","defend","allow_teleport","ping_pong","till"))props.putObject(flag).put("type","boolean");for(var number:List.of("start_distance","stop_distance"))props.putObject(number).put("type","number");for(var number:List.of("dwell_ticks","limit"))props.putObject(number).put("type","integer").put("minimum",0);props.putObject("crop").put("type","string");
            if(name.equals("start_skill")){var kinds=props.putObject("kind").putArray("enum");for(var k:SkillSpec.Kind.values())kinds.add(k.name());required.add("kind");}required.add("id").add("expected_revision").add("dimension");
            description="提交持续本地技能"+(kind(name)==null?"":kind(name).name())+"。actor默认ai；只有玩家明确要求接管本人时才player，使用真实客户端输入，Esc停止。FOLLOW/COMBAT需target实体UUID（AI跟随本人可$owner）；PATROL需route坐标数组，可ping_pong往返；FARM/FISH/WANDER/GUARD需min/max区域。开始距离默认6、停止距离2.5，前者必须更大。repeat默认true；defend默认true，工作遇怪暂时中断后重检恢复；limit=0持续，正数限制巡逻轮数/补种/渔获。FARM必须有原生种子，成熟收获后补种，不直接改作物年龄；crop可指定作物方块ID。FISH需原生鱼竿和岸边，等实际咬钩收竿。COMBAT不攻击玩家，保留原生冷却、弹道与物品消耗。allow_teleport默认false，只有明确要求且具权限才启用。STARTED只表示意图保存，不是完成；模型不逐格规划，不在聊天里循环轮询，后续inspect_skills查状态。";
        }
        return new Definition(name,description,schema.toString());
    }
    private SkillTools(){}
}
