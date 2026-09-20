package dev.mineagent.runtime.worker.provider;
public final class NativeApiToolSchemas {
    private NativeApiToolSchemas(){}
    public static final String EMPTY="""
            {"type":"object","properties":{},"additionalProperties":false}
            """;
    public static final String MODULES="""
            {"type":"object","properties":{"snapshot":{"type":"string","pattern":"^[a-f0-9]{64}$"},"offset":{"type":"integer","minimum":0,"maximum":512}},"required":["snapshot"],"additionalProperties":false}
            """;
    public static final String CLASSES="""
            {"type":"object","properties":{"snapshot":{"type":"string","pattern":"^[a-f0-9]{64}$"},"module":{"type":"string","minLength":1,"maxLength":160},"query":{"type":"string","maxLength":128},"offset":{"type":"integer","minimum":0,"maximum":300000}},"required":["snapshot","module","query"],"additionalProperties":false}
            """;
    public static final String MEMBERS="""
            {"type":"object","properties":{"snapshot":{"type":"string","pattern":"^[a-f0-9]{64}$"},"module":{"type":"string","minLength":1,"maxLength":160},"class":{"type":"string","minLength":1,"maxLength":512},"query":{"type":"string","maxLength":128},"offset":{"type":"integer","minimum":0,"maximum":131070},"text_offset":{"type":"integer","minimum":0,"maximum":4000000}},"required":["snapshot","module","class","query"],"additionalProperties":false}
            """;
    public static final String METHOD_BODY="""
            {"type":"object","properties":{"snapshot":{"type":"string","pattern":"^[a-f0-9]{64}$"},"module":{"type":"string","minLength":1,"maxLength":160},"class":{"type":"string","minLength":1,"maxLength":512},"method":{"type":"string","minLength":1,"maxLength":512},"descriptor":{"type":"string","minLength":3,"maxLength":2048},"offset":{"type":"integer","minimum":0,"maximum":131070},"text_offset":{"type":"integer","minimum":0,"maximum":4000000}},"required":["snapshot","module","class","method","descriptor"],"additionalProperties":false}
            """;
    public static final String SOURCE="""
            {"type":"object","properties":{"snapshot":{"type":"string","pattern":"^[a-f0-9]{64}$"},"module":{"type":"string","minLength":1,"maxLength":160},"class":{"type":"string","minLength":1,"maxLength":512},"method":{"type":"string","minLength":1,"maxLength":512},"descriptor":{"type":"string","minLength":3,"maxLength":2048},"offset":{"type":"integer","minimum":0,"maximum":200000},"text_offset":{"type":"integer","minimum":0,"maximum":4000000}},"required":["snapshot","module","class"],"additionalProperties":false}
            """;
}
