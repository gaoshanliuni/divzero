package dev.mineagent.runtime.client.webui;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public final class WebGuiProfile {
    private WebGuiProfile() {}
    /** Secure first-install defaults. Existing preferences always require an explicit user edit/restart. */
    public static boolean ensureDefaults(Path file) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        try {
            Files.writeString(file, "# MineAgent WebGUI profile: keep Chromium origin isolation enabled\n"
                    + "cef-disable-web-security=false\nenforce-download-checksums=true\n"
                    + "browser-preload-enabled=false\n", StandardOpenOption.CREATE_NEW);
            return true;
        } catch (FileAlreadyExistsException existing) { return false; }
    }
}
