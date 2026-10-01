package dev.mineagent.runtime.core.config;
import dev.mineagent.runtime.api.config.ConfigPatch;
import java.util.*;

/** A display policy, never chat access or permission to operate an AI. Existing agents stay private. */
public final class AgentMentionDisplay {
    public enum Mode { PRIVATE, PUBLIC }
    public record Setting(Mode mode,long revision){}
    private static String key(UUID world,UUID agent){return "agentMentionDisplay."+world+"."+agent;}
    public static Setting read(Map<String,String> values,UUID world,UUID agent){String prefix=key(world,agent);return new Setting(Mode.valueOf(values.getOrDefault(prefix+".mode","PRIVATE")),Long.parseLong(values.getOrDefault(prefix+".revision","0")));}
    public static Setting create(ServerConfigService config,UUID world,UUID agent){synchronized(config){var old=read(config.snapshot().values(),world,agent);return old.revision()>0?old:save(config,world,agent,0,Mode.PUBLIC);}}
    public static Setting save(ServerConfigService config,UUID world,UUID agent,long expected,Mode mode){synchronized(config){var snapshot=config.snapshot();var old=read(snapshot.values(),world,agent);if(expected!=old.revision())throw new IllegalStateException("聊天展示已变化，请刷新后重试");String prefix=key(world,agent);if(!config.apply(new ConfigPatch(snapshot.revision(),Map.of(prefix+".mode",Objects.requireNonNull(mode).name(),prefix+".revision",Long.toString(expected+1))),true).accepted())throw new IllegalStateException("聊天展示保存失败");return new Setting(mode,expected+1);}}
    public static boolean publicReply(Setting setting,boolean explicitAtMention){return explicitAtMention&&setting.mode()==Mode.PUBLIC;}
    private AgentMentionDisplay(){}
}
