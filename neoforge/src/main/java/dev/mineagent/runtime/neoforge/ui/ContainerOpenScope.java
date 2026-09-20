package dev.mineagent.runtime.neoforge.ui;
import net.minecraft.server.level.ServerPlayer;
/** Suppress only the native Screen notification during this one explicit server-thread menu open. */
public final class ContainerOpenScope implements AutoCloseable {
    private static final ThreadLocal<ServerPlayer> actor=new ThreadLocal<>();
    public ContainerOpenScope(ServerPlayer player){if(!player.level().getServer().isSameThread()||actor.get()!=null)throw new IllegalStateException("CONTAINER_OPEN_SCOPE");actor.set(player);}
    public static boolean suppress(ServerPlayer player){return actor.get()==player;}
    @Override public void close(){actor.remove();}
}
