package dev.mineagent.runtime.worker.provider;
public final class NativeKnowledgeToolSchemas {
    private NativeKnowledgeToolSchemas(){}
    public static final String REMEMBER="""
            {"type":"object","properties":{"observation_id":{"type":"string","format":"uuid"},"label":{"type":"string","minLength":1,"maxLength":128}},"required":["observation_id","label"],"additionalProperties":false}
            """;
    public static final String SEARCH="""
            {"type":"object","properties":{"query":{"type":"string","maxLength":128},"offset":{"type":"integer","minimum":0,"maximum":4096}},"required":["query"],"additionalProperties":false}
            """;
    public static final String ID="""
            {"type":"object","properties":{"knowledge_id":{"type":"string","format":"uuid"}},"required":["knowledge_id"],"additionalProperties":false}
            """;
}
