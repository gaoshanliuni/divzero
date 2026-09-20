package dev.mineagent.runtime.core.packages;

import dev.mineagent.runtime.api.packages.*;
import java.util.*;

/** Select signed manifest entries, never guess a file or let HUD sorting replace the editor. */
public final class PackageUiEntrypoints {
    private PackageUiEntrypoints() {}
    public static Optional<String> named(Map<String,RuntimeEntrypoint> entries,String name){return Optional.ofNullable(entries.get(name)).filter(PackageUiEntrypoints::browser).map(RuntimeEntrypoint::path);}
    public static Optional<String> select(Map<String, RuntimeEntrypoint> entries, boolean hud) {
        String name = hud ? "hud" : "ui";
        if (hud || entries.containsKey(name)) return Optional.ofNullable(entries.get(name)).filter(PackageUiEntrypoints::browser).map(RuntimeEntrypoint::path);
        return entries.entrySet().stream().filter(e -> !Set.of("hud","container").contains(e.getKey()))
                .map(Map.Entry::getValue).filter(PackageUiEntrypoints::browser).map(RuntimeEntrypoint::path).sorted().findFirst();
    }
    private static boolean browser(RuntimeEntrypoint entry) {
        return entry.side() != RuntimeResourceSide.SERVER && entry.path().startsWith("ui/") && entry.path().endsWith(".html");
    }
}
