package dev.mineagent.runtime.neoforge;

import dev.mineagent.runtime.core.persistence.WorldSaveIdentity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.permissions.Permissions;
import java.util.*;

/** Bootstrap-only identity decisions. No world-scoped service starts while a save binding is unresolved. */
public final class WorldIdentityRuntime {
    private static final Map<MinecraftServer,Entry> ENTRIES=new WeakHashMap<>();
    private static final class Entry {WorldSaveIdentity store;String error="";volatile boolean examined,allowed,reopen;final Map<UUID,Integer> notices=new HashMap<>();}
    private WorldIdentityRuntime(){}
    private static Entry open(MinecraftServer server){
        var entry=new Entry();
        try{var legacy=UUID.nameUUIDFromBytes((server.getServerDirectory().toAbsolutePath().normalize()+"|"+server.getWorldData().getLevelName()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            entry.store=WorldSaveIdentity.open(server.getServerDirectory().resolve("mineagent-runtime-data"),server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT),legacy);
        }catch(Exception failure){String message=Objects.toString(failure.getMessage(),"");entry.error=message.matches("WORLD_[A-Z0-9_]{1,70}")?message:"WORLD_IDENTITY_UNAVAILABLE";}
        return entry;
    }
    private static synchronized Entry entry(MinecraftServer server){return ENTRIES.computeIfAbsent(server,WorldIdentityRuntime::open);}
    public static boolean boot(MinecraftServer server){var e=entry(server);synchronized(e){e.examined=true;e.allowed=!e.reopen&&e.store!=null&&e.store.ready();return e.allowed;}}
    public static boolean ready(MinecraftServer server){var e=entry(server);return e.examined&&e.allowed&&!e.reopen&&e.store!=null&&e.store.ready();}
    public static UUID scope(MinecraftServer server){if(!ready(server))throw new IllegalStateException("WORLD_IDENTITY_NOT_READY");return entry(server).store.scopeId();}
    public static boolean notifyIfPending(ServerPlayer player){
        var server=player.level().getServer();if(ready(server))return true;var e=entry(server);int tick=server.getTickCount();
        if(tick-e.notices.getOrDefault(player.getUUID(),tick-200)>=200){e.notices.put(player.getUUID(),tick);player.sendSystemMessage(Component.literal("[MineAgent] 存档身份尚未接入，功能暂不启动，旧数据没有删除。请世界所有者/管理员执行 /ai identity 查看；选择后需保存并重新打开世界。"));}
        return false;
    }
    public static boolean canManage(CommandSourceStack source){
        var player=source.getPlayer();if(player instanceof dev.mineagent.runtime.neoforge.body.MineAgentPlayer||player!=null&&source.getServer().getPlayerList().getPlayer(player.getUUID())!=player)return false;
        return player==null?source.permissions().hasPermission(Permissions.COMMANDS_OWNER):(source.permissions().hasPermission(Permissions.COMMANDS_OWNER)&&player.permissions().hasPermission(Permissions.COMMANDS_OWNER))||source.getServer().isSingleplayerOwner(player.nameAndId());
    }
    public static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggestToken(CommandSourceStack source,com.mojang.brigadier.suggestion.SuggestionsBuilder builder){
        if(canManage(source))try{var e=entry(source.getServer());if(e.store!=null&&!e.allowed&&!e.reopen)builder.suggest(e.store.status().challenge());}catch(Exception ignored){}
        return builder.buildFuture();
    }
    public static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggestScope(CommandSourceStack source,com.mojang.brigadier.suggestion.SuggestionsBuilder builder){
        if(canManage(source))try{var e=entry(source.getServer());if(e.store!=null){var status=e.store.status();if(status.scopeId()!=null)builder.suggest(status.scopeId().toString());for(var id:status.candidates())builder.suggest(id.toString());}}catch(Exception ignored){}
        return builder.buildFuture();
    }
    public static int status(CommandSourceStack source){
        if(!canManage(source)){source.sendFailure(Component.literal("MineAgent 存档身份待所有者/管理员处理；不会自动读取旧作用域。"));return 0;}
        var e=entry(source.getServer());try{
            if(e.store==null){source.sendFailure(Component.literal(e.error+"；确认没有其它实例占用后，可 /ai identity retry。"));return 0;}
            var s=e.store.status();String state=e.reopen?"REOPEN_REQUIRED":s.state();
            source.sendSuccess(()->Component.literal("MineAgent identity: "+state+" · save="+Objects.toString(s.saveId(),"unbound")+" · scope="+Objects.toString(s.scopeId(),"unbound")+"\n"+s.note()),false);
            if(!ready(source.getServer())&&!e.reopen){
                source.sendSuccess(()->Component.literal("旧名称提示（不是归属证明）："+s.legacyHint()+"\n旧域候选："+s.candidates()+(s.more()?"（还有更多；可按准确 UUID 接入）":"")+"\n确认 token: "+s.challenge()),false);
                source.sendSuccess(()->Component.literal("新域且保留旧数据：/ai identity fresh <token>\n明确认定旧域属于本存档：/ai identity adopt <scopeUUID> <token>\n原目录已不存在的移动：/ai identity relocate <token>\n已复制整个 runtime 数据根的明确恢复：/ai identity restore_root <token>\n操作只绑定身份，不搬移/重签旧数据；fresh 不恢复旧 AI/物件运行，不是合并。旧域可能含会话、任务和位置，请先备份并确认其属于当前存档；先停用共享此 runtime 根的其它服务器。"),false);
            }return 1;
        }catch(Exception failure){source.sendFailure(Component.literal("WORLD_IDENTITY_STATUS_UNAVAILABLE"));return 0;}
    }
    public static int choose(CommandSourceStack source,String choice,String scope,String token){
        if(!source.getServer().isRunning()||!canManage(source)){source.sendFailure(Component.literal("WORLD_IDENTITY_OWNER_REQUIRED"));return 0;}
        var e=entry(source.getServer());try{
            if(e.store==null)throw new IllegalStateException(e.error);if(e.allowed||e.reopen)throw new IllegalStateException("WORLD_IDENTITY_ALREADY_SELECTED");
            e.store.choose(choice,scope==null?null:UUID.fromString(scope),token,source.getPlayer()==null?"SERVER_COMMAND_SOURCE":"PLAYER:"+source.getPlayer().getUUID());e.reopen=true;
            source.sendSuccess(()->Component.literal("身份绑定已保存。旧 payload/ID 未迁写，未启动 Worker 或恢复旧任务。请保存并重新打开世界后继续；本次游戏仍保持 MineAgent 未启动。"),false);return 1;
        }catch(Exception failure){String message=Objects.toString(failure.getMessage(),"");source.sendFailure(Component.literal(message.matches("WORLD_[A-Z0-9_]{1,70}")?message:"WORLD_IDENTITY_CHANGE_FAILED"));return 0;}
    }
    public static synchronized int retry(CommandSourceStack source){
        if(!source.getServer().isRunning()||!canManage(source))return 0;var old=ENTRIES.get(source.getServer());if(old!=null&&(old.allowed||old.reopen)){source.sendFailure(Component.literal("WORLD_IDENTITY_REOPEN_REQUIRED"));return 0;}
        if(old!=null&&old.store!=null)old.store.close();var next=open(source.getServer());next.examined=true;if(next.store!=null&&next.store.ready())next.reopen=true;ENTRIES.put(source.getServer(),next);return status(source);
    }
    public static synchronized void close(MinecraftServer server){var e=ENTRIES.get(server);if(e!=null&&e.store!=null)e.store.close();var stopped=new Entry();stopped.examined=true;stopped.error="WORLD_IDENTITY_SERVER_STOPPED";ENTRIES.put(server,stopped);}
}
