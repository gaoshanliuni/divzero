package dev.mineagent.runtime.worker.provider;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
public final class EventToolSchemas {
    private EventToolSchemas(){}
    private static final String SCRIPT_SCHEMA="""
        {"type":"object","properties":{"package_id":{"type":"string","format":"uuid"},"instance_id":{"type":"string","format":"uuid"},"package_revision":{"type":"integer","minimum":1},"canonical_sha256":{"type":"string","pattern":"^[a-f0-9]{64}$"},"handler":{"type":"string","pattern":"^runtime[.]event[.][A-Za-z][A-Za-z0-9_.:-]{0,63}$"},"max_runs":{"type":"integer","minimum":1,"maximum":1024}},"required":["package_id","instance_id","package_revision","canonical_sha256","handler","max_runs"],"additionalProperties":false}
        """;
    private static final String PUSH_SCHEMA="""
        {"type":"object","properties":{"package_id":{"type":"string","format":"uuid"},"instance_id":{"type":"string","format":"uuid"},"package_revision":{"type":"integer","minimum":1},"canonical_sha256":{"type":"string","pattern":"^[a-f0-9]{64}$"},"entry_path":{"type":"string","maxLength":160},"max_signals":{"type":"integer","minimum":1,"maximum":4096}},"required":["package_id","instance_id","package_revision","canonical_sha256","entry_path","max_signals"],"additionalProperties":false}
        """;
    public static final String PUSH_TARGETS="""
        {"type":"object","properties":{"instance_id":{"type":"string","format":"uuid"},"offset":{"type":"integer","minimum":0,"maximum":128}},"required":["instance_id"],"additionalProperties":false}
        """;
    public static final String HANDLERS="""
        {"type":"object","properties":{"instance_id":{"type":"string","format":"uuid"},"offset":{"type":"integer","minimum":0,"maximum":128}},"required":["instance_id"],"additionalProperties":false}
        """;
    public static String withScript(String source){try{var json=new ObjectMapper();var root=(ObjectNode)json.readTree(source);var p=(ObjectNode)root.get("properties");((com.fasterxml.jackson.databind.node.ArrayNode)p.path("mode").path("enum")).add("SCRIPT").add("STATE_PUSH");p.set("script",json.readTree(SCRIPT_SCHEMA));p.set("push",json.readTree(PUSH_SCHEMA));return json.writeValueAsString(root);}catch(Exception invalid){throw new IllegalStateException("SCRIPT_CONSUMER_SCHEMA",invalid);}}
    public static final String SUBSCRIBE=withScript(subscribe());
    public static final String SCORE_INSPECT="""
        {"type":"object","properties":{"objective":{"type":"string","maxLength":16},"offset":{"type":"integer","minimum":0,"maximum":16384}},"additionalProperties":false}
        """;
    public static final String SCORE_SUBSCRIBE=withScript("""
        {"type":"object","properties":{"objective":{"type":"string","minLength":1,"maxLength":16},"criteria":{"type":"string","minLength":1,"maxLength":128},"holders":{"type":"array","maxItems":64,"uniqueItems":true,"items":{"type":"string","minLength":1,"maxLength":40}},"when":{"type":"object","properties":{"test":{"enum":["ANY_CHANGE","EXISTS","ABSENT","EQ","NEQ","LT","LTE","GT","GTE"]},"value":{"type":"integer","minimum":-2147483648,"maximum":2147483647}},"required":["test"],"additionalProperties":false},"match_mode":{"enum":["ON_CHANGE","BECOMES_TRUE"]},"replacement_policy":{"enum":["PAUSE","REBASE"]},"mode":{"enum":["RECORD_ONLY","AGENT_WAKE"]},"goal":{"type":"string","maxLength":200},"max_wakes":{"type":"integer","minimum":0,"maximum":32},"max_model_calls":{"type":"integer","minimum":0,"maximum":16},"pending_limit":{"type":"integer","minimum":1,"maximum":8},"cooldown_ms":{"type":"integer","minimum":0,"maximum":600000},"ttl_seconds":{"type":"integer","minimum":1,"maximum":604800}},"required":["objective","criteria","mode"],"additionalProperties":false}
        """);
    public static final String OBJECT_INSPECT="""
        {"type":"object","properties":{"instance_id":{"type":"string","format":"uuid"},"offset":{"type":"integer","minimum":0,"maximum":32}},"required":["instance_id"],"additionalProperties":false}
        """;
    public static final String OBJECT_SUBSCRIBE=withScript("""
        {"type":"object","properties":{"package_id":{"type":"string","format":"uuid"},"instance_id":{"type":"string","format":"uuid"},"package_revision":{"type":"integer","minimum":1},"canonical_sha256":{"type":"string","pattern":"^[a-f0-9]{64}$"},"entity_id":{"type":"string","format":"uuid"},"part":{"type":"string","pattern":"^[A-Za-z0-9_.-]{1,64}$"},"asset_sha256":{"type":"string","pattern":"^[a-f0-9]{64}$"},"actor_kinds":{"type":"array","minItems":1,"maxItems":2,"uniqueItems":true,"items":{"enum":["PLAYER","AGENT"]}},"actor_ids":{"type":"array","maxItems":64,"uniqueItems":true,"items":{"type":"string","format":"uuid"}},"mode":{"enum":["RECORD_ONLY","AGENT_WAKE"]},"goal":{"type":"string","maxLength":200},"max_wakes":{"type":"integer","minimum":0,"maximum":32},"max_model_calls":{"type":"integer","minimum":0,"maximum":16},"pending_limit":{"type":"integer","minimum":1,"maximum":8},"cooldown_ms":{"type":"integer","minimum":0,"maximum":600000},"ttl_seconds":{"type":"integer","minimum":1,"maximum":604800}},"required":["package_id","instance_id","package_revision","canonical_sha256","entity_id","part","asset_sha256","actor_kinds","mode"],"additionalProperties":false}
        """);
    public static final String INSPECT="""
        {"type":"object","properties":{"subscription_id":{"type":"string","format":"uuid"},"offset":{"type":"integer","minimum":0,"maximum":131072}},"required":["subscription_id"],"additionalProperties":false}
        """;
    public static final String STATE="""
        {"type":"object","properties":{"subscription_id":{"type":"string","format":"uuid"},"expected_revision":{"type":"integer","minimum":1},"state":{"enum":["ACTIVE","PAUSED","CANCELLED"]}},"required":["subscription_id","expected_revision","state"],"additionalProperties":false}
        """;
    private static String subscribe(){try{var json=new ObjectMapper();var q=(ObjectNode)json.readTree(ObjectDirectoryToolSchemas.QUERY);var properties=(ObjectNode)q.get("properties");properties.set("kind",json.createObjectNode().put("const","PLAYER"));properties.set("cursor",json.createObjectNode().put("type","string").put("maxLength",0));var root=json.createObjectNode().put("type","object").put("additionalProperties",false);var p=root.putObject("properties");p.putObject("sources").put("type","array").put("minItems",1).put("maxItems",2).put("uniqueItems",true).putObject("items").putArray("enum").add("PLAYER_JOIN").add("PLAYER_LEAVE").add("PLAYER_REGION_ENTER").add("PLAYER_REGION_LEAVE");p.putObject("mode").putArray("enum").add("RECORD_ONLY").add("AGENT_WAKE");q.put("description","区域事件必须使用固定 dimension+region 或 near.reference=POSITION 的固定球形范围；不接受移动 ACTOR 圆心，区域源不能与 JOIN/LEAVE 混在一个订阅。name/ids/team 只筛选跟踪主体，limit 不是采样人数截断。其余事件沿用普通目录查询。");p.set("query",q);p.putObject("goal").put("type","string").put("maxLength",200);for(String name:new String[]{"max_wakes","max_model_calls","pending_limit","cooldown_ms","ttl_seconds"})p.putObject(name).put("type","integer").put("minimum",name.equals("ttl_seconds")||name.equals("pending_limit")?1:0).put("maximum",switch(name){case "max_wakes"->32;case "max_model_calls"->16;case "pending_limit"->8;case "cooldown_ms"->600000;default->604800;});root.putArray("required").add("sources").add("mode").add("query");return json.writeValueAsString(root);}catch(Exception e){throw new IllegalStateException("EVENT_TOOL_SCHEMA",e);}}
}
