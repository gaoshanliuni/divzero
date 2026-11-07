package dev.mineagent.runtime.core.content;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.concurrent.ConcurrentHashMap;

/** Serializes immutable ID publication across instances and processes; no content-derived lock names. */
final class ResourceFileLock {
    private static final ConcurrentHashMap<Path,Object> LOCKS=new ConcurrentHashMap<>();
    @FunctionalInterface interface Operation<T>{T run() throws IOException;}
    static <T> T with(Path file,Operation<T> action)throws IOException{
        Path path=file.toAbsolutePath().normalize();
        synchronized(LOCKS.computeIfAbsent(path,key->new Object())){
            if(Files.isSymbolicLink(path))throw new IOException("RESOURCE_LOCK_LINK");
            try(var channel=FileChannel.open(path,StandardOpenOption.CREATE,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);var lease=channel.lock()){return action.run();}
        }
    }
    private ResourceFileLock(){}
}
