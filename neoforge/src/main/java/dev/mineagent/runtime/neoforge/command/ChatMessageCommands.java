package dev.mineagent.runtime.neoforge.command;
import com.mojang.brigadier.CommandDispatcher;import com.mojang.brigadier.arguments.*;
import dev.mineagent.runtime.neoforge.ui.ServerChatMessageSettings;
import net.minecraft.commands.*;import net.minecraft.network.chat.*;import net.minecraft.server.level.ServerPlayer;
import java.util.*;
public final class ChatMessageCommands {
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher){
        dispatcher.register(Commands.literal("ai").then(Commands.literal("msg").executes(c->menu(c.getSource().getPlayerOrException()))
            .then(Commands.literal("thinking").executes(c->menu(c.getSource().getPlayerOrException())).then(Commands.argument("mode",StringArgumentType.word()).suggests((c,b)->{for(String mode:List.of("tail","full"))if(mode.startsWith(b.getRemaining()))b.suggest(mode);return b.buildFuture();}).executes(c->set(c.getSource().getPlayerOrException(),null,null,StringArgumentType.getString(c,"mode")))))
            .then(Commands.literal("limit").executes(c->menu(c.getSource().getPlayerOrException())).then(Commands.argument("count",IntegerArgumentType.integer(1,16384)).suggests((c,b)->{for(int n:List.of(1024,2048,4096,8192,16384))if(Integer.toString(n).startsWith(b.getRemaining()))b.suggest(n);return b.buildFuture();}).executes(c->set(c.getSource().getPlayerOrException(),IntegerArgumentType.getInteger(c,"count"),null))))
            .then(Commands.literal("mark").executes(c->menu(c.getSource().getPlayerOrException())).then(Commands.argument("format",StringArgumentType.greedyString()).suggests((c,b)->{for(String s:List.of("time","date","datetime","off","yyyy-MM-dd HH:mm:ss","消息时间：{yyyy-MM-dd HH:mm:ss}"))if(s.startsWith(b.getRemaining()))b.suggest(s);return b.buildFuture();}).executes(c->set(c.getSource().getPlayerOrException(),null,StringArgumentType.getString(c,"format")))))));
    }
    private static int set(ServerPlayer player,Integer limit,String mark){return set(player,limit,mark,null);}
    private static int set(ServerPlayer player,Integer limit,String mark,String thinking){var server=player.level().getServer();ServerChatMessageSettings.request(player,limit,mark,thinking,null,()->true).thenAccept(result->server.execute(()->{if(server.getPlayerList().getPlayer(player.getUUID())!=player)return;if(Set.of("APPLIED","OBSERVED").contains(result.get("status")))player.sendSystemMessage(Component.translatableWithFallback("mineagent.chat.messages.status","聊天保留：%s 条；AI 名称悬停格式：%s（无额外前缀）；思考：%s",result.get("limit"),result.get("mark"),result.get("thinking")));else player.sendSystemMessage(Component.translatableWithFallback("mineagent.chat.messages.failed","聊天显示设置未确认：%s",result.get("error")));}));return 1;}
    private static int menu(ServerPlayer player){
        var counts=Component.translatableWithFallback("mineagent.chat.messages.limit","聊天保留条数： ");for(int n:List.of(1024,2048,4096,8192,16384))counts.append(button(Integer.toString(n),"/ai msg limit "+n));player.sendSystemMessage(counts);
        var formats=Component.translatableWithFallback("mineagent.chat.messages.mark","悬停 AI 名称查看日期时间： ");for(String value:List.of("time","date","datetime","off"))formats.append(button(value,"/ai msg mark "+value));player.sendSystemMessage(formats);player.sendSystemMessage(Component.translatableWithFallback("mineagent.chat.messages.thinking","思考显示： ").append(button("tail","/ai msg thinking tail")).append(button("full","/ai msg thinking full")));
        player.sendSystemMessage(Component.translatableWithFallback("mineagent.chat.messages.help","自定义示例：/ai msg mark 消息时间：{yyyy-MM-dd HH:mm:ss}（日期格式模板，不是正则匹配）"));return set(player,null,null);
    }
    private static MutableComponent button(String label,String command){return Component.literal("[").append(label.equals("tail")?Component.translatableWithFallback("mineagent.chat.thinking.tail","单行尾部"):label.equals("full")?Component.translatableWithFallback("mineagent.chat.thinking.full","完整思考"):Component.literal(label)).append("] ").withStyle(style->style.withColor(net.minecraft.ChatFormatting.AQUA).withClickEvent(new ClickEvent.RunCommand(command)));}
    private ChatMessageCommands(){}
}
