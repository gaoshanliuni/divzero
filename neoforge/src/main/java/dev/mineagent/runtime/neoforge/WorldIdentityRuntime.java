package dev.mineagent.runtime.neoforge;

import dev.mineagent.runtime.core.persistence.WorldSaveIdentity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.permissions.Permissions;
import java.util.*;
import java.util.concurrent.*;

/** Bootstrap-only identity decisions. No world-scoped service starts while a save binding is unresolved. */
public final class WorldIdentityRuntime {
    private static final Map<MinecraftServer,Entry> ENTRIES=new WeakHashMap<>();
    private static final ExecutorService IO=Executors.newVirtualThreadPerTaskExecutor();
    private static final class Entry {WorldSaveIdentity store;String error="";volatile boolean examined,allowed,reopen,initializing,servicesStarted,closed,stopping;CompletableFuture<WorldSaveIdentity.Acceptance> starting;final Map<ServerPlayer,String> notices=new WeakHashMap<>();}
    private WorldIdentityRuntime(){}
    private static Entry open(MinecraftServer server){
        var entry=new Entry();
        try{var legacy=UUID.nameUUIDFromBytes((server.getServerDirectory().toAbsolutePath().normalize()+"|"+server.getWorldData().getLevelName()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            var save=server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT);
            entry.store=WorldSaveIdentity.open(server.getServerDirectory().resolve("mineagent-runtime-data"),save,legacy,knownTemplate(save));
        }catch(Exception failure){String message=Objects.toString(failure.getMessage(),"");entry.error=message.matches("WORLD_[A-Z0-9_]{1,70}")?message:"WORLD_IDENTITY_UNAVAILABLE";}
        return entry;
    }
    private static boolean knownTemplate(java.nio.file.Path save){
        var marker=save.resolve("data/divzero-pvp-map.json");
        try{if(!java.nio.file.Files.isRegularFile(marker,java.nio.file.LinkOption.NOFOLLOW_LINKS)||java.nio.file.Files.size(marker)>1024)return false;
            var data=new com.fasterxml.jackson.databind.ObjectMapper().readTree(java.nio.file.Files.readString(marker));return data.path("schema").asInt()==1&&data.path("map").asText().equals("divzero_pvp");
        }catch(Exception invalid){return false;}
    }
    private static synchronized Entry entry(MinecraftServer server){return ENTRIES.computeIfAbsent(server,WorldIdentityRuntime::open);}
    public static boolean boot(MinecraftServer server){var e=entry(server);synchronized(e){e.examined=true;e.allowed=!e.reopen&&e.store!=null&&e.store.ready();return e.allowed;}}
    public static boolean ready(MinecraftServer server){var e=entry(server);return e.examined&&e.allowed&&!e.reopen&&!e.initializing&&!e.closed&&e.store!=null&&e.store.ready();}
    public static UUID scope(MinecraftServer server){var e=entry(server);if(!e.examined||!e.allowed||e.reopen||e.closed||e.store==null||!e.store.ready())throw new IllegalStateException("WORLD_IDENTITY_NOT_READY");return e.store.scopeId();}
    public static boolean notifyIfPending(ServerPlayer player){
        var server=player.level().getServer();if(ready(server))return true;var e=entry(server);
        if(e.initializing)return false;
        String reason=e.error.isEmpty()?"PENDING":e.error;
        if(!reason.equals(e.notices.put(player,reason))){
            var line=Component.translatable(canManage(player.createCommandSourceStack())?"mineagent.activation.bootstrap.prompt":"mineagent.activation.bootstrap.owner");
            line.append(Component.translatable("mineagent.activation.bootstrap.button").withStyle(style->style.withColor(net.minecraft.ChatFormatting.GREEN).withClickEvent(new net.minecraft.network.chat.ClickEvent.RunCommand("/ai accept"))));
            if(!e.error.isEmpty())line.append(Component.translatable("mineagent.activation.bootstrap.reason",e.error));player.sendSystemMessage(line);
        }
        return false;
    }
    public static void servicesStarted(MinecraftServer server){var e=entry(server);e.servicesStarted=true;e.initializing=false;}
    public static CompletableFuture<WorldSaveIdentity.Acceptance> accept(CommandSourceStack source){
        var server=source.getServer();var e=entry(server);
        if(!server.isSameThread()||!server.isRunning()||e.closed||e.stopping)return CompletableFuture.failedFuture(new IllegalStateException("WORLD_IDENTITY_SERVER_STOPPED"));
        if(ready(server)&&e.servicesStarted)return CompletableFuture.completedFuture(new WorldSaveIdentity.Acceptance(scope(server),"EXISTING"));
        if(e.starting!=null&&!e.starting.isDone())return e.starting;
        if((e.store==null||!e.store.ready())&&!canManage(source))return CompletableFuture.failedFuture(new SecurityException("WORLD_IDENTITY_OWNER_REQUIRED"));
        e.initializing=true;e.examined=true;String actor=source.getPlayer()==null?"SERVER_COMMAND_SOURCE":"PLAYER:"+source.getPlayer().getUUID();
        e.starting=CompletableFuture.supplyAsync(()->{
            try{if(e.closed||e.stopping)throw new IllegalStateException("WORLD_IDENTITY_SERVER_STOPPED");if(e.store==null){var opened=open(server);synchronized(e){if(e.closed||e.stopping){if(opened.store!=null)opened.store.close();throw new IllegalStateException("WORLD_IDENTITY_SERVER_STOPPED");}e.store=opened.store;e.error=opened.error;}}if(e.store==null)throw new IllegalStateException(e.error);return e.store.acceptCurrent(actor);}catch(Exception error){throw new CompletionException(error);}
        },IO).thenCompose(accepted->server.submit(()->{if(e.closed||entry(server)!=e)throw new IllegalStateException("WORLD_IDENTITY_CONTEXT_CHANGED");e.allowed=true;e.reopen=false;return accepted;}))
        .thenCompose(accepted->{var worker=MineAgentRuntimeServices.worker(server);return CompletableFuture.supplyAsync(()->{try{if(e.closed||e.stopping)throw new IllegalStateException("WORLD_IDENTITY_SERVER_STOPPED");worker.start(server.getServerDirectory());return accepted;}catch(Exception error){throw new CompletionException(error);}},IO);})
        .thenCompose(accepted->server.submit(()->{
            if(e.closed||entry(server)!=e)throw new IllegalStateException("WORLD_IDENTITY_CONTEXT_CHANGED");
            e.initializing=false;
            try{MineAgentRuntimeMod.initializeWorldServices(server);servicesStarted(server);
                for(var player:List.copyOf(server.getPlayerList().getPlayers()))if(!(player instanceof dev.mineagent.runtime.neoforge.body.MineAgentPlayer)){WorldActivationRuntime.login(player);server.getCommands().sendCommands(player);}
                e.error="";return accepted;
            }catch(Exception failure){e.initializing=true;throw new CompletionException(failure);}
        }));
        var attempt=e.starting;attempt.whenComplete((value,error)->{if(error!=null)server.execute(()->{if(e.closed||e.starting!=attempt)return;e.initializing=false;e.allowed=false;Throwable cause=error;while(cause.getCause()!=null)cause=cause.getCause();String code=Objects.toString(cause.getMessage(),"");e.error=code.matches("WORLD_[A-Z0-9_]{1,70}")?code:"WORLD_SERVICES_START_FAILED";});});
        return e.starting;
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
        if(!canManage(source)){source.sendFailure(Component.literal("请房主或管理员使用 /ai accept 接入此存档。"));return 0;}
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
            source.sendSuccess(()->Component.literal("身份绑定已保存。使用 /ai accept 即可在本次连接中启用，无需重新进入世界。"),false);return 1;
        }catch(Exception failure){String message=Objects.toString(failure.getMessage(),"");source.sendFailure(Component.literal(message.matches("WORLD_[A-Z0-9_]{1,70}")?message:"WORLD_IDENTITY_CHANGE_FAILED"));return 0;}
    }
    public static int retry(CommandSourceStack source){return WorldActivationRuntime.enable(source,null);}
    public static void stopAccepting(MinecraftServer server){entry(server).stopping=true;}
    public static synchronized void close(MinecraftServer server){var e=ENTRIES.get(server);if(e!=null)synchronized(e){e.closed=true;if(e.store!=null)e.store.close();}var stopped=new Entry();stopped.examined=true;stopped.error="WORLD_IDENTITY_SERVER_STOPPED";ENTRIES.put(server,stopped);}
}
