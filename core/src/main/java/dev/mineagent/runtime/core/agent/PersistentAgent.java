package dev.mineagent.runtime.core.agent;

import dev.mineagent.runtime.api.agent.AgentDefinition;

public record PersistentAgent(long revision, AgentDefinition definition,
                              @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_DEFAULT) long authorityGeneration) {
    public PersistentAgent(long revision,AgentDefinition definition){this(revision,definition,0);}
    public PersistentAgent{if(authorityGeneration<0)throw new IllegalArgumentException("AGENT_AUTHORITY_GENERATION");}
}
