package dev.mineagent.runtime.neoforge.chat;
import dev.mineagent.runtime.neoforge.MineAgentRuntimeServices;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;
import java.util.UUID;
/** AI notices use the dedicated named system-chat presentation, independent of player identity. */
public final class AiPlayerChat {
 public static final String STREAM_PART="mineagent.chat.player_stream_part";
 public static boolean send(ServerPlayer recipient,UUID agent,Component content){
  var server=recipient.level().getServer();if(!server.isSameThread())throw new IllegalStateException("AI_CHAT_SERVER_THREAD");
  var definition=MineAgentRuntimeServices.bodies(server).definitions().stream().filter(d->d.agentId().equals(agent)).findFirst().orElse(null);
  if(definition==null)return false;recipient.sendSystemMessage(AiChatMessages.name(definition.displayName()).append(Component.literal(" ")).append(content));return true;
 }
 private AiPlayerChat(){}
}
