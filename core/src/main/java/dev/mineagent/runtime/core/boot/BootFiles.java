package dev.mineagent.runtime.core.boot;

import dev.mineagent.runtime.core.compile.NativeCompilationSnapshot;
import java.nio.file.*;
import java.nio.channels.FileChannel;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Exact managed filenames only; publishing into the actual FML mods directory is an explicit global action. */
public final class BootFiles {
    private BootFiles(){}
    public static String filename(BootInstallStore.Build b){if(!b.artifact().matches("[a-f0-9]{64}"))throw new IllegalStateException("BOOT_ARTIFACT_HASH");if(!b.slot().isEmpty())return b.slot();return "mineagent-boot-"+b.manifest().packageId()+"-"+b.artifact()+".jar";}
    public static Path directory(Path value)throws Exception {if(Files.exists(value,LinkOption.NOFOLLOW_LINKS)&&(!Files.isDirectory(value,LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(value)))throw new IllegalStateException("BOOT_DIRECTORY_LINK");Files.createDirectories(value);return value.toRealPath();}
    public static byte[] archive(Path path,String expected)throws Exception {byte[] bytes=NativeCompilationSnapshot.read(path,BootExtensionPlan.MAX_ARCHIVE);var artifact=BootArtifact.inspect(bytes,expected);if(!artifact.hash().equals(expected)||artifact.metadata().schema()==2&&artifact.proof()==null)throw new IllegalStateException("BOOT_ARTIFACT_ID_OR_SIGNATURE");return bytes;}
    /** Existing approved cache copies provide the legacy ID and exact content binding without deriving a digest. */
    public static BootArtifact inspect(Path path,Path cache)throws Exception{
        byte[] bytes=NativeCompilationSnapshot.read(path,BootExtensionPlan.MAX_ARCHIVE);BootArtifact artifact;
        try{artifact=BootArtifact.inspect(bytes);}catch(IllegalStateException legacy){
            if(!"BOOT_LEGACY_ID_REQUIRED".equals(legacy.getMessage()))throw legacy;
            String found=null;try(var entries=Files.newDirectoryStream(directory(cache),"*.jar")){for(var entry:entries){String name=entry.getFileName().toString();if(!name.matches("[a-f0-9]{64}\\.jar")||Files.size(entry)!=bytes.length)continue;if(Arrays.equals(NativeCompilationSnapshot.read(entry,BootExtensionPlan.MAX_ARCHIVE),bytes)){String id=name.substring(0,64);if(found!=null&&!found.equals(id))throw new IllegalStateException("BOOT_LEGACY_ID_AMBIGUOUS");found=id;}}}
            if(found==null)throw new IllegalStateException("BOOT_LEGACY_APPROVED_COPY_REQUIRED");artifact=BootArtifact.inspect(bytes,found);
        }
        byte[] approved=archive(directory(cache).resolve(artifact.hash()+".jar"),artifact.hash());
        if(!Arrays.equals(approved,bytes))throw new IllegalStateException("BOOT_ARTIFACT_CONTENT_CHANGED");return artifact;
    }
    public static String identity(Path path,Path cache)throws Exception{return inspect(path,cache).hash();}
    public static void publish(Path dir,String filename,byte[] bytes,String expected,BooleanSupplier permit)throws Exception {
        Path root=directory(dir);if(!filename.matches("(?:[a-f0-9]{64}|mineagent-boot-[a-f0-9-]{36}-[a-f0-9]{64})\\.jar"))throw new IllegalStateException("BOOT_FILENAME");
        var artifact=BootArtifact.inspect(bytes,expected);if(!artifact.hash().equals(expected)||artifact.metadata().schema()==2&&artifact.proof()==null)throw new IllegalStateException("BOOT_ARTIFACT_ID_OR_SIGNATURE");Path target=root.resolve(filename);
        if(Files.exists(target,LinkOption.NOFOLLOW_LINKS)){if(!Arrays.equals(archive(target,expected),bytes))throw new IllegalStateException("BOOT_ARTIFACT_CONTENT_CHANGED");return;}
        Path temporary=Files.createTempFile(root,".mineagent-boot-",".pending");try{Files.write(temporary,bytes);try(var channel=FileChannel.open(temporary,StandardOpenOption.WRITE)){channel.force(true);}if(!permit.getAsBoolean())throw new IllegalStateException("BOOT_AUTHORITY_CHANGED");Files.createLink(target,temporary);if(!Arrays.equals(archive(target,expected),bytes))throw new IllegalStateException("BOOT_ARTIFACT_CONTENT_CHANGED");}finally{Files.deleteIfExists(temporary);}
    }
    public static void remove(BootInstallStore.Build b,Path mods,Path cache,BooleanSupplier permit)throws Exception {
        Path root=directory(mods);if(!root.toString().equals(b.directory()))throw new IllegalStateException("BOOT_MODS_DIRECTORY_CHANGED");Path target=root.resolve(filename(b));
        if(!Files.exists(target,LinkOption.NOFOLLOW_LINKS))return;
        var artifact=inspect(target,cache);artifact.verify(Base64.getDecoder().decode(b.publicKey()),b.manifest().canonicalSha256(),b.nativeClasspath(),b.environment());
        if(!artifact.hash().equals(b.artifact()))throw new IllegalStateException("BOOT_ARTIFACT_CONTENT_CHANGED");
        if(!permit.getAsBoolean())throw new IllegalStateException("BOOT_AUTHORITY_CHANGED");inspect(target,cache);Path disabled=target.resolveSibling(target.getFileName()+".disabled");if(Files.exists(disabled,LinkOption.NOFOLLOW_LINKS))throw new IllegalStateException("BOOT_DISABLED_BACKUP_EXISTS");Files.move(target,disabled,StandardCopyOption.ATOMIC_MOVE);if(Files.exists(target,LinkOption.NOFOLLOW_LINKS))throw new IllegalStateException("BOOT_REMOVE_UNCERTAIN");
    }
}
