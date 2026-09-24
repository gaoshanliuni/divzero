package dev.mineagent.runtime.core.conversation;
import java.util.*;
/** Response permission is independent from operator/world-task privileges. */
public record AgentChatPolicy(String mode,Set<UUID> allow,Set<UUID> deny,long revision){
 public enum Decision {ALLOW,DENY,ASK}
 public AgentChatPolicy{if(!Set.of("ASK","ALLOW_ALL","DENY_ALL","ALLOW_LIST").contains(mode)||revision<0||allow==null||deny==null||!Collections.disjoint(allow,deny))throw new IllegalArgumentException("CHAT_POLICY_INVALID");allow=Set.copyOf(allow);deny=Set.copyOf(deny);}
 public static AgentChatPolicy defaults(){return new AgentChatPolicy("ASK",Set.of(),Set.of(),0);}
 public Decision decision(UUID owner,UUID caller){if(owner.equals(caller))return Decision.ALLOW;return switch(mode){case "ALLOW_ALL"->Decision.ALLOW;case "DENY_ALL"->Decision.DENY;default->allow.contains(caller)?Decision.ALLOW:deny.contains(caller)||mode.equals("ALLOW_LIST")?Decision.DENY:Decision.ASK;};}
 public AgentChatPolicy remember(UUID player,boolean allowed){var a=new HashSet<>(allow);var d=new HashSet<>(deny);a.remove(player);d.remove(player);(allowed?a:d).add(player);return new AgentChatPolicy(mode,a,d,revision+1);}
}
