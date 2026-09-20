package dev.mineagent.runtime.core.agent;
import dev.mineagent.runtime.api.agent.AgentDefinition;
/** Captured at request acceptance; later edits do not rewrite already queued prompts or grant world authority. */
public final class PersonaPrompt {
    private PersonaPrompt(){}
    public static String section(AgentDefinition agent,AgentPersonaService.Persona persona){
        if(agent==null||persona==null||!agent.agentId().equals(persona.agentId()))throw new IllegalArgumentException("PERSONA_CONTEXT");
        return "\n[玩家设置的 AI 人设 agentId="+agent.agentId()+" revision="+persona.revision()+"]\n"
                +(persona.text().isBlank()?"默认 AI 玩家：你是 Minecraft 中的 AI 玩家 "+agent.displayName()+"。":persona.text())
                +"\n[人设边界] 人设仅影响表达和角色扮演，不授予权限，不改变任务归属，不代表已经执行任何世界操作。\n";
    }
}
