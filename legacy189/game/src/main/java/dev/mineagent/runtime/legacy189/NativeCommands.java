package dev.mineagent.runtime.legacy189;

import net.minecraft.command.*;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.BlockPos;
import net.minecraft.util.ChatComponentText;
import java.util.*;

public class NativeCommands extends CommandBase {
    @Override public String getCommandName() { return "ai"; }
    @Override public String getCommandUsage(ICommandSender sender) { return "/ai enable|disable|status|create <name>|list|remove <name>|stop|offhand|swap"; }
    @Override public int getRequiredPermissionLevel() { return 0; }
    @Override public void processCommand(ICommandSender sender, String[] args) throws CommandException {
        EntityPlayerMP player = getCommandSenderAsPlayer(sender);
        String action = args.length == 0 ? "status" : args[0];
        try {
            if (action.equals("enable") || action.equals("disable")) {
                boolean enabled = action.equals("enable"); NativeRuntime.data().enabled(player.getUniqueID(), enabled);
                if (!enabled) for (NativeAgent body : NativeRuntime.bodies()) if (body.owner.equals(player.getUniqueID())) body.stopActions();
                NativeNetwork.sync(player); tell(player, enabled ? "当前世界已启用。" : "当前世界已禁用，身体动作已停止。");
            } else if (action.equals("status")) {
                tell(player, "Forge 1.8.9 移植开发中；全部功能尚未完成。世界：" + NativeRuntime.data().identity());
                tell(player, "已接入：世界启用、原生玩家身体、副手存取/换装。现代战斗、完整工作区与服务仍待接入。");
            } else if (action.equals("create")) {
                if (args.length < 2) throw new IllegalArgumentException("请输入 AI 名称");
                NativeAgent body = NativeRuntime.create(player, join(args, 1)); tell(player, "已创建 " + body.displayName + " · " + body.getUniqueID());
            } else if (action.equals("list")) {
                for (NativeAgent body : NativeRuntime.bodies()) if (body.owner.equals(player.getUniqueID())) tell(player, body.displayName + " · " + body.getUniqueID());
            } else if (action.equals("remove")) {
                if (args.length < 2) throw new IllegalArgumentException("请输入 AI 名称或 UUID");
                NativeRuntime.delete(player, join(args, 1)); tell(player, "已移除指定 AI。");
            } else if (action.equals("stop")) {
                for (NativeAgent body : NativeRuntime.bodies()) if (body.owner.equals(player.getUniqueID())) body.stopActions();
                tell(player, "本人的 AI 身体动作已停止。");
            } else if (action.equals("offhand")) player.openGui(LegacyMod.instance, 0, player.worldObj, 0, 0, 0);
            else if (action.equals("swap")) NativeOffhand.get(player).swap(NativeOffhand.get(player).revision());
            else throw new IllegalArgumentException(getCommandUsage(sender));
        } catch (RuntimeException error) { throw new CommandException(error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage()); }
    }
    private static String join(String[] args, int start) {
        StringBuilder value = new StringBuilder();
        for (int i = start; i < args.length; i++) { if (i > start) value.append(' '); value.append(args[i]); }
        return value.toString();
    }
    private static void tell(EntityPlayerMP player, String text) { player.addChatMessage(new ChatComponentText("[DivZero] " + text)); }
    @Override public List<String> addTabCompletionOptions(ICommandSender sender, String[] args, BlockPos pos) {
        return args.length == 1 ? getListOfStringsMatchingLastWord(args, "enable", "disable", "status", "create", "list", "remove", "stop", "offhand", "swap") : Collections.<String>emptyList();
    }
}
