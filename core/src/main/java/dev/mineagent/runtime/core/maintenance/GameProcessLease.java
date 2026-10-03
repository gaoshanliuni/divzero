package dev.mineagent.runtime.core.maintenance;

import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;

/** Held for the JVM lifetime, including title screens after leaving a world. */
public final class GameProcessLease implements AutoCloseable {
    private static final Map<Path,GameProcessLease> GAMES=new HashMap<>();
    private final FileChannel channel;private final FileLock lock;
    private GameProcessLease(Path game,boolean shared)throws Exception{
        Path root=game.toRealPath().resolve("mineagent-maintenance");Files.createDirectories(root);if(Files.isSymbolicLink(root)||!root.toRealPath().equals(root))throw new IllegalStateException("MAINTENANCE_DIRECTORY_LINK");Path file=root.resolve("game.lock");if(Files.isSymbolicLink(file))throw new IllegalStateException("MAINTENANCE_FILE_LINK");
        channel=FileChannel.open(file,StandardOpenOption.CREATE,StandardOpenOption.READ,StandardOpenOption.WRITE);
        try{lock=channel.tryLock(0,Long.MAX_VALUE,shared);if(lock==null)throw new IllegalStateException(shared?"MAINTENANCE_IN_PROGRESS":"GAME_STILL_RUNNING");}catch(Exception error){channel.close();throw error;}
    }
    public static synchronized void hold(Path game)throws Exception{Path key=game.toRealPath();if(GAMES.containsKey(key))return;var value=new GameProcessLease(key,true);GAMES.put(key,value);Runtime.getRuntime().addShutdownHook(new Thread(()->{try{value.close();}catch(Exception ignored){}},"divzero-maintenance-lease"));}
    public static GameProcessLease offline(Path game)throws Exception{return new GameProcessLease(game,false);}
    public void close()throws Exception{try{lock.release();}finally{channel.close();}}
    private GameProcessLease(){throw new AssertionError();}
}
