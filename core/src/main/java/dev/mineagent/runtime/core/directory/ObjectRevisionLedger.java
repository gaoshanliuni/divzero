package dev.mineagent.runtime.core.directory;
import java.util.*;
/** Versions observed metadata changes; not a claim to have observed every intervening Native event. */
public final class ObjectRevisionLedger {
    private record Version(UUID generation,String signature,long revision){}
    private final int maximum;private final Map<String,Version> versions=new HashMap<>();
    public ObjectRevisionLedger(int maximum){if(maximum<1)throw new IllegalArgumentException("DIRECTORY_REVISION_BUDGET");this.maximum=maximum;}
    public long observe(String key,UUID generation,String signature){
        if(key==null||key.isBlank()||key.length()>256||generation==null||signature==null||signature.length()>128)throw new IllegalArgumentException("DIRECTORY_REVISION_KEY");
        var old=versions.get(key);if(old==null&&versions.size()>=maximum)throw new IllegalStateException("DIRECTORY_IDENTITY_BUDGET");
        long revision=old==null||!old.generation().equals(generation)?1:old.signature().equals(signature)?old.revision():Math.addExact(old.revision(),1);
        if(revision>9_007_199_254_740_991L)throw new IllegalStateException("DIRECTORY_REVISION_BUDGET");
        versions.put(key,new Version(generation,signature,revision));return revision;
    }
    public void forget(String key){versions.remove(key);}
    public void clear(){versions.clear();}
}
