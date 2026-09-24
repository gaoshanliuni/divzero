package dev.mineagent.runtime.neoforge.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import dev.mineagent.runtime.neoforge.MineAgentRuntimeServices;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.permissions.Permissions;
import dev.mineagent.runtime.api.permission.PermissionAction;

public final class MineAgentCommands {
    private MineAgentCommands() {
    }

    public static void register(com.mojang.brigadier.CommandDispatcher<CommandSourceStack> dispatcher) {
        ChatPreferenceCommands.register(dispatcher);
        ChatMessageCommands.register(dispatcher);
        dispatcher.register(Commands.literal("ai")
                .then(Commands.literal("files").then(Commands.argument("agent",net.minecraft.commands.arguments.UuidArgument.uuid()).executes(c->dev.mineagent.runtime.neoforge.ui.ServerBuildingFiles.open(c.getSource().getPlayerOrException(),net.minecraft.commands.arguments.UuidArgument.getUuid(c,"agent"))).then(Commands.argument("file",net.minecraft.commands.arguments.UuidArgument.uuid()).executes(c->dev.mineagent.runtime.neoforge.ui.ServerBuildingFiles.open(c.getSource().getPlayerOrException(),net.minecraft.commands.arguments.UuidArgument.getUuid(c,"agent"),net.minecraft.commands.arguments.UuidArgument.getUuid(c,"file").toString())))))
                .then(Commands.literal("accept").executes(c->accept(c.getSource())))
                .then(Commands.literal("interrupt").then(Commands.argument("agent",net.minecraft.commands.arguments.UuidArgument.uuid())
                    .executes(c->interrupt(c.getSource(),net.minecraft.commands.arguments.UuidArgument.getUuid(c,"agent"),""))
                    .then(Commands.argument("message",StringArgumentType.greedyString()).executes(c->interrupt(c.getSource(),net.minecraft.commands.arguments.UuidArgument.getUuid(c,"agent"),StringArgumentType.getString(c,"message"))))))
                .then(Commands.literal("body")
                    .then(Commands.literal("review").then(Commands.argument("operation",net.minecraft.commands.arguments.UuidArgument.uuid()).executes(c->dev.mineagent.runtime.neoforge.task.PlayerBodyAgent.review(c.getSource().getPlayerOrException(),net.minecraft.commands.arguments.UuidArgument.getUuid(c,"operation")))))
                    .then(Commands.literal("stop").executes(c->dev.mineagent.runtime.neoforge.task.PlayerBodyAgent.stop(c.getSource().getPlayerOrException())))
                    .then(Commands.literal("status").executes(c->dev.mineagent.runtime.neoforge.task.PlayerBodyAgent.status(c.getSource().getPlayerOrException()))))
                .then(Commands.literal("commands")
                    .then(Commands.literal("confirm").then(Commands.argument("operation",StringArgumentType.word()).executes(c->dev.mineagent.runtime.neoforge.task.PlayerCommandAgent.decide(c.getSource().getPlayerOrException(),java.util.UUID.fromString(StringArgumentType.getString(c,"operation")),true))))
                    .then(Commands.literal("cancel").then(Commands.argument("operation",StringArgumentType.word()).executes(c->dev.mineagent.runtime.neoforge.task.PlayerCommandAgent.decide(c.getSource().getPlayerOrException(),java.util.UUID.fromString(StringArgumentType.getString(c,"operation")),false)))))
                .then(Commands.literal("identity").executes(c->dev.mineagent.runtime.neoforge.WorldIdentityRuntime.status(c.getSource()))
                    .then(Commands.literal("retry").executes(c->dev.mineagent.runtime.neoforge.WorldIdentityRuntime.retry(c.getSource())))
                    .then(Commands.literal("fresh").then(Commands.argument("token",StringArgumentType.word()).suggests((c,b)->dev.mineagent.runtime.neoforge.WorldIdentityRuntime.suggestToken(c.getSource(),b)).executes(c->dev.mineagent.runtime.neoforge.WorldIdentityRuntime.choose(c.getSource(),"FRESH",null,StringArgumentType.getString(c,"token")))))
                    .then(Commands.literal("adopt").then(Commands.argument("scope",StringArgumentType.word()).suggests((c,b)->dev.mineagent.runtime.neoforge.WorldIdentityRuntime.suggestScope(c.getSource(),b)).then(Commands.argument("token",StringArgumentType.word()).suggests((c,b)->dev.mineagent.runtime.neoforge.WorldIdentityRuntime.suggestToken(c.getSource(),b)).executes(c->dev.mineagent.runtime.neoforge.WorldIdentityRuntime.choose(c.getSource(),"ADOPT",StringArgumentType.getString(c,"scope"),StringArgumentType.getString(c,"token"))))))
                    .then(Commands.literal("relocate").then(Commands.argument("token",StringArgumentType.word()).suggests((c,b)->dev.mineagent.runtime.neoforge.WorldIdentityRuntime.suggestToken(c.getSource(),b)).executes(c->dev.mineagent.runtime.neoforge.WorldIdentityRuntime.choose(c.getSource(),"RELOCATE",null,StringArgumentType.getString(c,"token")))))
                    .then(Commands.literal("restore_root").then(Commands.argument("token",StringArgumentType.word()).suggests((c,b)->dev.mineagent.runtime.neoforge.WorldIdentityRuntime.suggestToken(c.getSource(),b)).executes(c->dev.mineagent.runtime.neoforge.WorldIdentityRuntime.choose(c.getSource(),"RESTORE_ROOT",null,StringArgumentType.getString(c,"token"))))))
                .then(Commands.literal("panel")
                        .executes(context -> openPanel(context.getSource())))
                .then(Commands.literal("create")
                        .requires(source -> source.getPlayer() != null && MineAgentRuntimeServices.permissions(source.getServer())
                                .allowed(source.getPlayer().getUUID(),
                                        source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER),
                                        PermissionAction.CREATE_AGENT))
                        .then(Commands.argument("name", StringArgumentType.string())
                                .executes(context -> create(
                                        context.getSource(),
                                        StringArgumentType.getString(context, "name")
                                ))))
                .then(Commands.literal("list")
                        .executes(context -> list(context.getSource())))
                .then(Commands.literal("debug_spawn")
                        .requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_OWNER))
                        .then(Commands.argument("name", StringArgumentType.string())
                                .executes(context -> debugSpawn(
                                        context.getSource(),
                                        StringArgumentType.getString(context, "name")
                                )))));
    }

    private static int interrupt(CommandSourceStack source,java.util.UUID agent,String message){
        try{return dev.mineagent.runtime.neoforge.ui.ServerConversations.get(source.getServer()).interruptNative(source.getPlayerOrException(),agent,message);}catch(Exception e){source.sendFailure(Component.literal("无法打断："+e.getMessage()));return 0;}
    }
    private static int accept(CommandSourceStack source){
        try{var p=source.getPlayerOrException();if(!source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER)){source.sendFailure(Component.literal("只有启用作弊模式（服务器需管理员权限）才可以使用模组；此命令不会自动提权。"));return 0;}
            var s=source.getServer();var config=MineAgentRuntimeServices.config(s);var actions=java.util.EnumSet.allOf(PermissionAction.class);
            var result=config.apply(new dev.mineagent.runtime.api.config.ConfigPatch(config.snapshot().revision(),java.util.Map.of("runtime.initialized","true","permission.player."+p.getUUID(),actions.stream().map(Enum::name).sorted().collect(java.util.stream.Collectors.joining(",")))),true);
            if(!result.accepted())throw new IllegalStateException(result.errorCode());MineAgentRuntimeServices.permissions(s).setTrustedActions(p.getUUID(),actions);
            source.sendSuccess(()->Component.literal("已授权本人使用模组。电脑命令仍按本机确认执行。"),false);dev.mineagent.runtime.neoforge.network.MineAgentNetwork.sendPanelSnapshot(p);return 1;
        }catch(Exception e){source.sendFailure(Component.literal("授权未完成："+e.getMessage()));return 0;}
    }
    private static int openPanel(CommandSourceStack source) {
        if(!dev.mineagent.runtime.neoforge.WorldIdentityRuntime.ready(source.getServer()))return dev.mineagent.runtime.neoforge.WorldIdentityRuntime.status(source);
        try {
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(
                    source.getPlayerOrException(),
                    new dev.mineagent.runtime.neoforge.network.MineAgentPayloads.OpenPanel("OVERVIEW"));
            return 1;
        } catch (Exception failure) {
            source.sendFailure(Component.literal("只能由游戏内玩家打开控制中心"));
            return 0;
        }
    }

    private static int create(CommandSourceStack source, String name) {
        if(!dev.mineagent.runtime.neoforge.WorldIdentityRuntime.ready(source.getServer()))return dev.mineagent.runtime.neoforge.WorldIdentityRuntime.status(source);
        try {
            var definition = MineAgentRuntimeServices.bodies(source.getServer())
                    .create(name, source.getPlayerOrException());
            MineAgentRuntimeServices.permissions(source.getServer())
                    .registerOwnership(definition.agentId(), definition.ownerPlayerId());
            source.sendSuccess(
                    () -> Component.literal("已创建 AI 玩家 " + definition.displayName()),
                    true
            );
            return 1;
        } catch (Exception failure) {
            source.sendFailure(Component.literal("创建 AI 玩家失败: " + failure.getMessage()));
            return 0;
        }
    }

    private static int list(CommandSourceStack source) {
        if(!dev.mineagent.runtime.neoforge.WorldIdentityRuntime.ready(source.getServer()))return dev.mineagent.runtime.neoforge.WorldIdentityRuntime.status(source);
        var agents = MineAgentRuntimeServices.bodies(source.getServer()).definitions();
        if (agents.isEmpty()) {
            source.sendSuccess(() -> Component.literal("当前没有 AI 玩家"), false);
            return 0;
        }
        String names = agents.stream().map(agent -> agent.displayName()).collect(java.util.stream.Collectors.joining("、"));
        source.sendSuccess(() -> Component.literal("AI 玩家: " + names), false);
        return agents.size();
    }

    private static int debugSpawn(CommandSourceStack source, String name) {
        if(!dev.mineagent.runtime.neoforge.WorldIdentityRuntime.ready(source.getServer()))return dev.mineagent.runtime.neoforge.WorldIdentityRuntime.status(source);
        try {
            var definition = MineAgentRuntimeServices.bodies(source.getServer()).createAt(
                    name,
                    source.getPlayer() == null ? new java.util.UUID(0, 0) : source.getPlayer().getUUID(),
                    source.getLevel(),
                    source.getPosition()
            );
            source.sendSuccess(() -> Component.literal("测试 AI 玩家已生成: " + definition.displayName()), false);
            return 1;
        } catch (Exception failure) {
            source.sendFailure(Component.literal("测试生成失败: " + failure.getMessage()));
            return 0;
        }
    }
}
