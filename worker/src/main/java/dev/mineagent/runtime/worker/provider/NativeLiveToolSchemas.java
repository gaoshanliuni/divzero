package dev.mineagent.runtime.worker.provider;
public final class NativeLiveToolSchemas {
    private NativeLiveToolSchemas(){}
    public static final String EMPTY="""
            {"type":"object","properties":{},"additionalProperties":false}
            """;
    public static final String LOADED="""
            {"type":"object","properties":{"query":{"type":"string","maxLength":128},"offset":{"type":"integer","minimum":0,"maximum":300000}},"required":["query"],"additionalProperties":false}
            """;
    public static final String LIVE_MEMBERS="""
            {"type":"object","properties":{"class_token":{"type":"string","format":"uuid"},"query":{"type":"string","maxLength":128},"offset":{"type":"integer","minimum":0,"maximum":131070},"text_offset":{"type":"integer","minimum":0,"maximum":4000000}},"required":["class_token","query"],"additionalProperties":false}
            """;
    public static final String LIVE_BODY="""
            {"type":"object","properties":{"class_token":{"type":"string","format":"uuid"},"method":{"type":"string","minLength":1,"maxLength":512},"descriptor":{"type":"string","minLength":3,"maxLength":2048},"offset":{"type":"integer","minimum":0,"maximum":131070},"text_offset":{"type":"integer","minimum":0,"maximum":4000000}},"required":["class_token","method","descriptor"],"additionalProperties":false}
            """;
    public static final String TRANSFORMED_MEMBERS="""
            {"type":"object","properties":{"snapshot":{"type":"string","pattern":"^[a-f0-9]{64}$"},"module":{"type":"string","minLength":1,"maxLength":160},"class":{"type":"string","minLength":1,"maxLength":512},"query":{"type":"string","maxLength":128},"offset":{"type":"integer","minimum":0,"maximum":131070},"text_offset":{"type":"integer","minimum":0,"maximum":4000000}},"required":["snapshot","module","class","query"],"additionalProperties":false}
            """;
    public static final String TRANSFORMED_BODY="""
            {"type":"object","properties":{"snapshot":{"type":"string","pattern":"^[a-f0-9]{64}$"},"module":{"type":"string","minLength":1,"maxLength":160},"class":{"type":"string","minLength":1,"maxLength":512},"method":{"type":"string","minLength":1,"maxLength":512},"descriptor":{"type":"string","minLength":3,"maxLength":2048},"offset":{"type":"integer","minimum":0,"maximum":131070},"text_offset":{"type":"integer","minimum":0,"maximum":4000000}},"required":["snapshot","module","class","method","descriptor"],"additionalProperties":false}
            """;
}
