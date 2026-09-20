package dev.mineagent.runtime.core.interaction;

import dev.mineagent.runtime.api.agent.AgentDefinition;
import dev.mineagent.runtime.api.interaction.InteractionInput;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

public final class AddressResolver {
    public AddressResolution resolve(InteractionInput input, List<AgentDefinition> agents) {
        String text = input.text().strip();
        String lowered = text.toLowerCase(Locale.ROOT);
        var mentions=AgentMention.resolve(text,agents.stream().map(AgentDefinition::displayName).toList());
        if(!mentions.isEmpty()){
            var matched=agents.stream().filter(a->mentions.stream().anyMatch(m->m.name().equals(a.displayName()))).toList();
            return mentions.size()==1&&matched.size()==1?resolved(matched.getFirst(),AddressResolutionKind.EXPLICIT_MENTION):new AddressResolution(Optional.empty(),AddressResolutionKind.UNRESOLVED);
        }
        if(text.startsWith("@"))return new AddressResolution(Optional.empty(),AddressResolutionKind.UNRESOLVED);
        for (AgentDefinition agent : agents) {
            String name = agent.displayName().toLowerCase(Locale.ROOT);
            if (lowered.equals(name)
                    || lowered.startsWith(name + " ")
                    || lowered.startsWith(name + "，")
                    || lowered.startsWith(name + ",")) {
                return resolved(agent, AddressResolutionKind.NATURAL_NAME);
            }
        }
        if (input.currentConversationAgentId() != null
                && agents.stream().anyMatch(agent -> agent.agentId().equals(input.currentConversationAgentId()))) {
            return new AddressResolution(Optional.of(input.currentConversationAgentId()),
                    AddressResolutionKind.CURRENT_CONVERSATION);
        }
        return new AddressResolution(Optional.empty(), AddressResolutionKind.UNRESOLVED);
    }

    private static AddressResolution resolved(AgentDefinition agent, AddressResolutionKind kind) {
        return new AddressResolution(Optional.of(agent.agentId()), kind);
    }
}
