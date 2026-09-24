package dev.mineagent.runtime.core.config;
import java.util.*;
public final class AgentThinkingSettings {
 public static final Set<String> LEVELS=Set.of("off","low","medium","high","max");
 public static String key(UUID world,UUID agent){return "ai.thinking."+world+"."+agent;}
 public static String read(Map<String,String> values,UUID world,UUID agent){String value=values.getOrDefault(key(world,agent),"high");if(!LEVELS.contains(value))throw new IllegalArgumentException("THINKING_LEVEL_INVALID");return value.equals("medium")?"high":value;}
 private AgentThinkingSettings(){}
}
