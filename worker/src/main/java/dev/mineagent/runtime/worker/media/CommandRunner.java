package dev.mineagent.runtime.worker.media;

import java.util.List;

@FunctionalInterface
public interface CommandRunner {
    ToolResult run(List<String> arguments);
}
