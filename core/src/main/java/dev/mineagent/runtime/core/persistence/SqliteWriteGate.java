package dev.mineagent.runtime.core.persistence;

import java.lang.ref.WeakReference;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/** SQLite permits one writer per file. Queue those short transactions fairly, not whole AI tasks. */
public final class SqliteWriteGate {
    private static final Map<Path,WeakReference<ReentrantLock>> LOCKS=new HashMap<>();
    public static synchronized ReentrantLock forFile(Path file){
        LOCKS.values().removeIf(reference->reference.get()==null);
        Path path=file.toAbsolutePath().normalize();
        var reference=LOCKS.get(path);var lock=reference==null?null:reference.get();
        if(lock==null){lock=new ReentrantLock(true);LOCKS.put(path,new WeakReference<>(lock));}
        return lock;
    }
    private SqliteWriteGate(){}
}
