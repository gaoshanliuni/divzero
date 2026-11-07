package dev.mineagent.runtime.core.content;

import dev.mineagent.runtime.api.packages.ResourceIds;
import java.util.*;

/** Bounded, process-local identities for small observed states. Tokens are never authentication or file integrity. */
public final class ObservationTokens {
    private final int maximumEntries;private final long maximumCharacters;
    private final LinkedHashMap<String,String> values=new LinkedHashMap<>(16,.75f,true);private long characters;
    public ObservationTokens(int maximumEntries,long maximumCharacters){if(maximumEntries<1||maximumCharacters<1)throw new IllegalArgumentException("OBSERVATION_TOKEN_BUDGET");this.maximumEntries=maximumEntries;this.maximumCharacters=maximumCharacters;}
    public synchronized String token(String snapshot){
        Objects.requireNonNull(snapshot);if(snapshot.length()>maximumCharacters)throw new IllegalArgumentException("OBSERVATION_SNAPSHOT_TOO_LARGE");var existing=values.get(snapshot);if(existing!=null)return existing;
        String id=ResourceIds.create();values.put(snapshot,id);characters+=snapshot.length();var oldest=values.entrySet().iterator();while(values.size()>maximumEntries||characters>maximumCharacters){var row=oldest.next();characters-=row.getKey().length();oldest.remove();}return id;
    }
    public synchronized boolean matches(String token,String snapshot){return token!=null&&token.equals(values.get(snapshot));}
    public synchronized void clear(){values.clear();characters=0;}
}
