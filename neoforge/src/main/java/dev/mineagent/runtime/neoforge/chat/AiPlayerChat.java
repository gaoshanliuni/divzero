package dev.mineagent.runtime.neoforge.chat;

import dev.mineagent.runtime.neoforge.MineAgentRuntimeServices;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.*;
import java.util.UUID;

/** Native, unsigned player chat from the registered AI profile. Audience remains the requested recipient. */
public final class AiPlayerChat {
 public static final String STREAM_PART="mineagent.chat.player_stream_part";
 public static boolean send(ServerPlayer recipient,UUID agent,Component content){return send(recipient,agent,content,null,"");}
 public static boolean stream(ServerPlayer recipient,UUID agent,UUID operation,String part,Component content){return send(recipient,agent,content,operation,part);}
 public static boolean streamAudience(ServerPlayer requester,java.util.List<ServerPlayer> observers,UUID agent,UUID operation,String part,Component content){
  var server=requester.level().getServer();if(!server.isSameThread())throw new IllegalStateException("AI_CHAT_SERVER_THREAD");var body=MineAgentRuntimeServices.bodies(server).body(agent).orElse(null);if(body==null||server.getPlayerList().getPlayer(agent)!=body)return false;
  var decorated=net.neoforged.neoforge.common.CommonHooks.getServerChatSubmittedDecorator().decorate(body,content);if(decorated==null)return false;
  deliver(requester,body,decorated,operation,part);for(var recipient:observers)if(server.getPlayerList().getPlayer(recipient.getUUID())==recipient)deliver(recipient,body,decorated,operation,"observer:"+part);return true;
 }
 private static boolean send(ServerPlayer recipient,UUID agent,Component content,UUID operation,String part){
  var server=recipient.level().getServer();if(!server.isSameThread())throw new IllegalStateException("AI_CHAT_SERVER_THREAD");
  var body=MineAgentRuntimeServices.bodies(server).body(agent).orElse(null);
  if(body==null||server.getPlayerList().getPlayer(agent)!=body)return false;
  var decorated=net.neoforged.neoforge.common.CommonHooks.getServerChatSubmittedDecorator().decorate(body,content);if(decorated==null)return false;
  deliver(recipient,body,decorated,operation,part);return true;
 }
 private static void deliver(ServerPlayer recipient,ServerPlayer body,Component decorated,UUID operation,String part){
  String plain=decorated.getString();if(plain.length()>240){plain=plain.substring(0,240);if(Character.isHighSurrogate(plain.charAt(plain.length()-1)))plain=plain.substring(0,plain.length()-1);}
  if(operation!=null)decorated=Component.translatableWithFallback(STREAM_PART,"%s",decorated,operation.toString(),body.getUUID().toString(),part);
  var message=PlayerChatMessage.unsigned(body.getUUID(),plain).withUnsignedContent(decorated);
  recipient.sendChatMessage(OutgoingChatMessage.create(message),recipient.shouldFilterMessageTo(body),ChatType.bind(ChatType.CHAT,body));
 }
 private AiPlayerChat(){}
}
