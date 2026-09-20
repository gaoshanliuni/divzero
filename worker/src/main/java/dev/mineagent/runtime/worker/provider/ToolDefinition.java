package dev.mineagent.runtime.worker.provider;

public record ToolDefinition(String name, String description, String parametersJson) {
    public ToolDefinition {
        if (name == null || !name.matches("[A-Za-z_][A-Za-z0-9_-]{0,63}")
                || description == null || parametersJson == null || parametersJson.isBlank()) {
            throw new IllegalArgumentException("invalid tool definition");
        }
    }
}
