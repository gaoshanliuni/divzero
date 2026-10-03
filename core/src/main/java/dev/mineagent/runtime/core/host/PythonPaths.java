package dev.mineagent.runtime.core.host;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Short, Mod-owned interpreter storage; user scripts and results stay in the game instance. */
public final class PythonPaths {
    public static Path runtimeRoot(Path game)throws Exception{
        String configured=System.getProperty("divzero.pythonRuntimeRoot","");Path base;
        if(!configured.isBlank())base=Path.of(configured).toAbsolutePath().normalize();
        else{String local=System.getenv("LOCALAPPDATA");base=local==null||local.isBlank()?game.toRealPath().resolve("mineagent-host/unavailable-runtime"):Path.of(local).toRealPath().resolve("DivZero/python");}
        String identity=game.toRealPath().toString().toLowerCase(Locale.ROOT);String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8)));
        return base.resolve(hash.substring(0,24));
    }
    public static void prepare(Path game,boolean create)throws Exception{
        Path root=runtimeRoot(game);if(create)Files.createDirectories(root);if(!Files.isDirectory(root,LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(root)||!root.toRealPath().equals(root))throw new IllegalStateException("PYTHON_RUNTIME_PATH_CHANGED");
        Path owner=root.resolve("game.owner");String expected=game.toRealPath().toString();
        if(Files.exists(owner,LinkOption.NOFOLLOW_LINKS)){if(!Files.isRegularFile(owner,LinkOption.NOFOLLOW_LINKS)||Files.size(owner)>8192||!Files.readString(owner).equalsIgnoreCase(expected))throw new IllegalStateException("PYTHON_RUNTIME_OWNER_CHANGED");}
        else if(create)Files.writeString(owner,expected,StandardOpenOption.CREATE_NEW);else throw new IllegalStateException("PYTHON_RUNTIME_OWNER_MISSING");
    }
    private PythonPaths(){}
}
