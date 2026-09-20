package dev.mineagent.runtime.core.shared;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

/** Thread-safe, one-way invalidation for already queued readers. Capturing a new lease never repairs an old one. */
public final class SharedResourceEpochs implements AutoCloseable {
    private record Key(UUID instance,String namespace){}
    private final ConcurrentHashMap<UUID,AtomicLong> instances=new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Key,AtomicLong> schemas=new ConcurrentHashMap<>();private volatile boolean closed;
    public synchronized BooleanSupplier capture(UUID instance,String namespace){
        if(closed||instance==null||namespace==null||instances.size()>=4096&&!instances.containsKey(instance)||schemas.size()>=4096&&!schemas.containsKey(new Key(instance,namespace)))throw new IllegalStateException("SHARED_RESOURCE_LEASE_BUDGET");
        var i=instances.computeIfAbsent(instance,k->new AtomicLong());var s=schemas.computeIfAbsent(new Key(instance,namespace),k->new AtomicLong());long iv=i.get(),sv=s.get();return ()->!closed&&i.get()==iv&&s.get()==sv;
    }
    public void invalidateInstance(UUID instance){var value=instances.get(instance);if(value!=null)value.incrementAndGet();}
    public void invalidateSchema(UUID instance,String namespace){var value=schemas.get(new Key(instance,namespace));if(value!=null)value.incrementAndGet();var listing=schemas.get(new Key(instance,""));if(listing!=null)listing.incrementAndGet();}
    @Override public synchronized void close(){closed=true;instances.clear();schemas.clear();}
}
