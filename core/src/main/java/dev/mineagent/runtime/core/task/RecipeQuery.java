package dev.mineagent.runtime.core.task;

import java.util.*;

/** Prefer the requested recipe over every recipe which happens to consume its output. */
public final class RecipeQuery {
    public static Set<String> exactIds(Collection<String> ids, String query) {
        String wanted=Objects.requireNonNull(query).strip().toLowerCase(Locale.ROOT);
        if(wanted.isEmpty())return Set.of();
        var matches=new LinkedHashSet<String>();
        for(String id:ids){String normalized=id.toLowerCase(Locale.ROOT);int colon=normalized.indexOf(':');
            if(normalized.equals(wanted)||!wanted.contains(":")&&colon>=0&&normalized.substring(colon+1).equals(wanted))matches.add(id);
        }
        return Collections.unmodifiableSet(matches);
    }
    private RecipeQuery(){}
}
