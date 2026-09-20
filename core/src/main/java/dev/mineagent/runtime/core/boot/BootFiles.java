package dev.mineagent.runtime.core.boot;

import dev.mineagent.runtime.core.compile.NativeCompilationSnapshot;
import dev.mineagent.runtime.core.packages.RuntimePackageCanonicalizer;
import java.nio.file.*;
import java.nio.channels.FileChannel;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Exact managed filenames only; publishing into the actual FML mods directory is an explicit global action. */
public final class BootFiles {
    private BootFiles(){}
    public static String filename(BootInstallStore.Build b){if(!b.artifact().matches("[a-f0-9]{64}"))throw new IllegalStateException("BOOT_ARTIFACT_HASH");if(!b.slot().isEmpty())return b.slot();return "mineagent-boot-"+b.manifest().packageId()+"-"+b.artifact()+".jar";}
    public static Path directory(Path value)throws Exception {if(Files.exists(value,LinkOption.NOFOLLOW_LINKS)&&(!Files.isDirectory(value,LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(value)))throw new IllegalStateException("BOOT_DIRECTORY_LINK");Files.createDirectories(value);return value.toRealPath();}
    public static byte[] archive(Path path,String expected)throws Exception {byte[] bytes=NativeCompilationSnapshot.read(path,BootExtensionPlan.MAX_ARCHIVE);if(!RuntimePackageCanonicalizer.sha256(bytes).equals(expected))throw new IllegalStateException("BOOT_ARTIFACT_HASH");return bytes;}
    public static void publish(Path dir,String filename,byte[] bytes,String expected,BooleanSupplier permit)throws Exception {
        Path root=directory(dir);if(!filename.matches("(?:[a-f0-9]{64}|mineagent-boot-[a-f0-9-]{36}-[a-f0-9]{64})\\.jar"))throw new IllegalStateException("BOOT_FILENAME");
        if(!RuntimePackageCanonicalizer.sha256(bytes).equals(expected))throw new IllegalStateException("BOOT_ARTIFACT_HASH");Path target=root.resolve(filename);
        if(Files.exists(target,LinkOption.NOFOLLOW_LINKS)){archive(target,expected);return;}
        Path temporary=Files.createTempFile(root,".mineagent-boot-",".pending");try{Files.write(temporary,bytes);try(var channel=FileChannel.open(temporary,StandardOpenOption.WRITE)){channel.force(true);}if(!permit.getAsBoolean())throw new IllegalStateException("BOOT_AUTHORITY_CHANGED");Files.createLink(target,temporary);archive(target,expected);}finally{Files.deleteIfExists(temporary);}
    }
    public static void remove(BootInstallStore.Build b,Path mods,Path cache,BooleanSupplier permit)throws Exception {
        Path root=directory(mods);if(!root.toString().equals(b.directory()))throw new IllegalStateException("BOOT_MODS_DIRECTORY_CHANGED");Path target=root.resolve(filename(b));
        if(!Files.exists(target,LinkOption.NOFOLLOW_LINKS))return;
        archive(target,b.artifact());Path backup=directory(cache).resolve(b.artifact()+".jar");if(Files.exists(backup,LinkOption.NOFOLLOW_LINKS))archive(backup,b.artifact());else Files.createLink(backup,target);
        if(!permit.getAsBoolean())throw new IllegalStateException("BOOT_AUTHORITY_CHANGED");archive(target,b.artifact());Files.delete(target);if(Files.exists(target,LinkOption.NOFOLLOW_LINKS))throw new IllegalStateException("BOOT_REMOVE_UNCERTAIN");
    }
}
