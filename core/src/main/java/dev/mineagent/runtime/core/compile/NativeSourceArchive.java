package dev.mineagent.runtime.core.compile;

import dev.mineagent.runtime.core.content.ContentAddressedStore;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.zip.*;

/** Captures only an explicitly colocated *-sources.jar; never scans unrelated directories or follows links. */
public final class NativeSourceArchive {
    public record Attachment(String sha256,long size,long bytes,int files,String kind){}
    private NativeSourceArchive(){}
    public static Optional<Attachment> discover(Path binary,ContentAddressedStore content,BooleanSupplier permit,long remainingBytes,int remainingFiles)throws Exception{
        if(binary==null||binary.getParent()==null||!Files.isRegularFile(binary,LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(binary))return Optional.empty();String name=binary.getFileName().toString();if(!name.endsWith(".jar")||name.endsWith("-sources.jar"))return Optional.empty();var names=new LinkedHashSet<String>();if(name.endsWith("-merged.jar"))names.add(name.substring(0,name.length()-"-merged.jar".length())+"-sources.jar");names.add(name.substring(0,name.length()-4)+"-sources.jar");Path parent=binary.toAbsolutePath().normalize().getParent();for(String candidate:names){Path path=parent.resolve(candidate).normalize();if(path.getParent().equals(parent)&&Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)&&!Files.isSymbolicLink(path))return Optional.of(capture(path,content,permit,remainingBytes,remainingFiles));}return Optional.empty();
    }
    public static Attachment capture(Path source,ContentAddressedStore content,BooleanSupplier permit,long maximumBytes,int maximumFiles)throws Exception{
        if(maximumBytes<1||maximumBytes>512L*1024*1024||maximumFiles<1||maximumFiles>100000||!Files.isRegularFile(source,LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(source))throw new IllegalStateException("NATIVE_SOURCE_ARCHIVE_INVALID");long compressed=Files.size(source);if(compressed<1||compressed>Math.min(maximumBytes,128L*1024*1024))throw new IllegalStateException("NATIVE_SOURCE_ARCHIVE_LIMIT");byte[] raw=NativeCompilationSnapshot.read(source,(int)Math.min(Integer.MAX_VALUE,compressed));if(!permit.getAsBoolean())throw new IllegalStateException("NATIVE_CLASSPATH_AUTHORITY_CHANGED");long total=0,auxiliary=0;int files=0,entries=0;var names=new HashSet<String>();try(var zip=new ZipInputStream(new ByteArrayInputStream(raw))){ZipEntry entry;while((entry=zip.getNextEntry())!=null){String name=entry.getName();if(name.startsWith("/")||name.contains("..")||name.contains("\\")||name.length()>1024||!names.add(name)||++entries>maximumFiles+1024)throw new IllegalStateException("NATIVE_SOURCE_ENTRY_INVALID");if(entry.isDirectory())continue;if(!name.endsWith(".java")){byte[] ignored=zip.readNBytes(64*1024+1);auxiliary+=ignored.length;if(ignored.length>64*1024||auxiliary>16L*1024*1024)throw new IllegalStateException("NATIVE_SOURCE_ARCHIVE_LIMIT");continue;}byte[] body=zip.readNBytes(1024*1024+1);total+=body.length;if(body.length>1024*1024||total>maximumBytes||++files>maximumFiles)throw new IllegalStateException("NATIVE_SOURCE_ARCHIVE_LIMIT");if((files&255)==0&&!permit.getAsBoolean())throw new IllegalStateException("NATIVE_CLASSPATH_AUTHORITY_CHANGED");}}
        if(files==0||!permit.getAsBoolean())throw new IllegalStateException(files==0?"NATIVE_SOURCE_ARCHIVE_EMPTY":"NATIVE_CLASSPATH_AUTHORITY_CHANGED");var stored=content.put(raw);return new Attachment(stored.sha256(),stored.size(),total,files,"SIBLING_SOURCES_JAR");
    }
}
