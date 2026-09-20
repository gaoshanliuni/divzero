package dev.mineagent.runtime.worker.provider;
public final class ScheduleToolSchemas {
    private ScheduleToolSchemas(){}
    public static final String CREATE=ScheduleDeliverySchemas.create("""
        {"type":"object","properties":{"kind":{"enum":["WALL_ONCE","WALL_PERIODIC","TICK_ONCE","TICK_PERIODIC","EVENT_CONDITION"]},"mode":{"enum":["RECORD_ONLY","AGENT_WAKE","SCRIPT","STATE_PUSH"]},"script":{"type":"object","properties":{"package_id":{"type":"string","format":"uuid"},"instance_id":{"type":"string","format":"uuid"},"package_revision":{"type":"integer","minimum":1},"canonical_sha256":{"type":"string","pattern":"^[a-f0-9]{64}$"},"handler":{"type":"string","pattern":"^runtime[.]schedule[.][A-Za-z][A-Za-z0-9_.:-]{0,63}$"}},"required":["package_id","instance_id","package_revision","canonical_sha256","handler"],"additionalProperties":false},"push":{"type":"object","properties":{"package_id":{"type":"string","format":"uuid"},"instance_id":{"type":"string","format":"uuid"},"package_revision":{"type":"integer","minimum":1},"canonical_sha256":{"type":"string","pattern":"^[a-f0-9]{64}$"},"entry_path":{"type":"string","maxLength":160}},"required":["package_id","instance_id","package_revision","canonical_sha256","entry_path"],"additionalProperties":false},"goal":{"type":"string","maxLength":200},"max_model_calls":{"type":"integer","minimum":0,"maximum":16},"at":{"type":"string","format":"date-time"},"delay_ms":{"type":"integer","minimum":0,"maximum":2592000000},"delay_ticks":{"type":"integer","minimum":0,"maximum":32000000},"period_ms":{"type":"integer","minimum":1000,"maximum":604800000},"period_ticks":{"type":"integer","minimum":1,"maximum":1000000},"max_occurrences":{"type":"integer","minimum":1,"maximum":32},"ttl_seconds":{"type":"integer","minimum":1,"maximum":2592000},"timezone":{"type":"string"},"missed_policy":{"enum":["SKIP","COALESCE"]},"max_lateness_ms":{"type":"integer","minimum":0,"maximum":10000},"subscription_id":{"type":"string","format":"uuid"},"subscription_revision":{"type":"integer","minimum":1},"fire_if_initially_matched":{"type":"boolean"}},"required":["kind","mode"],"additionalProperties":false}
        """);
    public static final String HANDLERS="""
        {"type":"object","properties":{"instance_id":{"type":"string","format":"uuid"},"offset":{"type":"integer","minimum":0,"maximum":128}},"required":["instance_id"],"additionalProperties":false}
        """;
    public static final String INSPECT="""
        {"type":"object","properties":{"schedule_id":{"type":"string","format":"uuid"},"offset":{"type":"integer","minimum":0,"maximum":8192}},"required":["schedule_id"],"additionalProperties":false}
        """;
    public static final String STATE="""
        {"type":"object","properties":{"schedule_id":{"type":"string","format":"uuid"},"expected_revision":{"type":"integer","minimum":1},"state":{"enum":["ACTIVE","PAUSED","CANCELLED"]}},"required":["schedule_id","expected_revision","state"],"additionalProperties":false}
        """;
    public static final String CLOCK="{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}";
}
