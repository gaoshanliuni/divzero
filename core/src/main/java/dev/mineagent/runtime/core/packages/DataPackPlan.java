package dev.mineagent.runtime.core.packages;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mineagent.runtime.api.packages.*;
import dev.mineagent.runtime.core.content.ContentAddressedStore;
import dev.mineagent.runtime.core.crypto.IdentitySigner;
import java.nio.file.*;
import java.nio.channels.FileChannel;
import java.util.*;
import java.util.zip.*;
import java.nio.charset.StandardCharsets;

/** Exact signed data-pack files, not a recipe/function generator or a substitute for the Minecraft reload consumer. */
public record DataPackPlan(RuntimePackage manifest,Map<String,RuntimeResourceRef> files,long bytes) {
    public static final Set<String> PLAN_ERRORS=Set.of("DATA_PACK_LIFECYCLE_REQUIRED","DATA_PACK_ENTRY_REQUIRED","DATA_PACK_PERMISSION_REQUIRED","DATA_PACK_MIXED_NATIVE_LIFECYCLE","DATA_PACK_PATH","DATA_PACK_WORLD_REOPEN_REQUIRED","DATA_PACK_RESOURCE_LIMIT");
    public static final String PREFIX="mineagent-data-";
    public DataPackPlan{files=Collections.unmodifiableMap(new TreeMap<>(files));}
    public static DataPackPlan inspect(RuntimePackage p){
        if(!Set.of(ActivationMode.DATA_RELOAD,ActivationMode.WORLD_REOPEN).contains(p.activationMode()))throw new IllegalStateException("DATA_PACK_LIFECYCLE_REQUIRED");
        var entry=p.entrypoints().get("datapack");
        if(entry==null||!entry.path().equals("datapack/pack.mcmeta")||entry.side()!=RuntimeResourceSide.SERVER||!p.definitions().isEmpty())throw new IllegalStateException("DATA_PACK_ENTRY_REQUIRED");
        if(p.nativeCompatibility()==null||!p.nativeCompatibility().targets().containsKey("SERVER"))throw new IllegalStateException("NATIVE_COMPATIBILITY_REQUIRED");
        if(!p.permissions().contains("RUN_CODE"))throw new IllegalStateException("DATA_PACK_PERMISSION_REQUIRED");
        var files=new TreeMap<String,RuntimeResourceRef>();long total=0;
        for(var ref:p.resources().values()){
            if(ref.path().startsWith("ui/"))continue;
            if(!ref.path().startsWith("datapack/")||ref.side()!=RuntimeResourceSide.SERVER)throw new IllegalStateException("DATA_PACK_MIXED_NATIVE_LIFECYCLE");
            String path=ref.path().substring(9);
            if(!path.equals("pack.mcmeta")&&!path.matches("data/[a-z0-9_.-]+/[a-z0-9_./-]+"))throw new IllegalStateException("DATA_PACK_PATH");
            if(Arrays.stream(path.split("/",-1)).anyMatch(s->s.isEmpty()||s.equals(".")||s.equals("..")))throw new IllegalStateException("DATA_PACK_PATH");
            if(p.activationMode()==ActivationMode.DATA_RELOAD&&path.matches("data/[^/]+/(?:dimension|dimension_type|worldgen)(?:/.*)?"))throw new IllegalStateException("DATA_PACK_WORLD_REOPEN_REQUIRED");
            if(ref.size()<0||ref.size()>4*1024*1024||files.put(path,ref)!=null)throw new IllegalStateException("DATA_PACK_RESOURCE_LIMIT");total+=ref.size();
        }
        if(!files.containsKey("pack.mcmeta")||files.size()<2||files.size()>256||total>16*1024*1024)throw new IllegalStateException("DATA_PACK_RESOURCE_LIMIT");
        for(var e:p.entrypoints().entrySet())if(!e.getKey().equals("datapack")&&!e.getValue().path().startsWith("ui/"))throw new IllegalStateException("DATA_PACK_MIXED_NATIVE_LIFECYCLE");
        return new DataPackPlan(p,files,total);
    }
    public boolean reopensWorld(){return manifest.activationMode()==ActivationMode.WORLD_REOPEN;}
    public String filename(){return (reopensWorld()?"mineagent-world-":PREFIX)+manifest.packageId()+"-"+manifest.canonicalSha256()+".zip";}
    public String packId(){return "file/"+filename();}
    public static boolean managedName(String name){return name!=null&&name.matches("mineagent-(?:data|world)-[a-f0-9-]{36}-[a-f0-9]{64}\\.zip");}
    public void verifySignature(byte[] publicKey)throws Exception{
        if(!manifest.canonicalSha256().equals(RuntimePackageCanonicalizer.sha256(manifest))||!IdentitySigner.verify(publicKey,manifest.canonicalSha256().getBytes(StandardCharsets.US_ASCII),Base64.getDecoder().decode(manifest.signature())))throw new IllegalStateException("DATA_PACK_SIGNATURE");
    }
    /** Called on the file worker. Publishing never replaces an existing archive. */
    public String publish(Path save,ContentAddressedStore content)throws Exception{
        Path root=save.toRealPath(),directory=root.resolve("datapacks");
        if(Files.exists(directory,LinkOption.NOFOLLOW_LINKS)&&(!Files.isDirectory(directory,LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(directory)))throw new IllegalStateException("DATA_PACK_DIRECTORY_LINK");
        Files.createDirectories(directory);if(!directory.toRealPath().getParent().equals(root))throw new IllegalStateException("DATA_PACK_DIRECTORY_LINK");
        Path target=directory.resolve(filename());if(Files.exists(target,LinkOption.NOFOLLOW_LINKS))return verifyArchive(target);
        Path temporary=Files.createTempFile(directory,".mineagent-data-",".pending");
        try{
            try(var zip=new ZipOutputStream(Files.newOutputStream(temporary))){
                for(var e:files.entrySet()){
                    if(Thread.currentThread().isInterrupted())throw new java.io.InterruptedIOException("DATA_PACK_BUILD_INTERRUPTED");
                    byte[] bytes=content.read(e.getValue().sha256());if(bytes.length!=e.getValue().size()||!RuntimePackageCanonicalizer.sha256(bytes).equals(e.getValue().sha256()))throw new IllegalStateException("DATA_PACK_RESOURCE_HASH");
                    var crc=new CRC32();crc.update(bytes);var entry=new ZipEntry(e.getKey());entry.setMethod(ZipEntry.STORED);entry.setSize(bytes.length);entry.setCompressedSize(bytes.length);entry.setCrc(crc.getValue());entry.setTime(0);zip.putNextEntry(entry);zip.write(bytes);zip.closeEntry();
                }
            }
            try(var channel=FileChannel.open(temporary,StandardOpenOption.WRITE)){channel.force(true);}
            Files.createLink(target,temporary);return verifyArchive(target);
        }finally{Files.deleteIfExists(temporary);}
    }
    public record Snapshot(String hash,Map<String,byte[]> files){}
    public String verifyArchive(Path archive)throws Exception{return snapshot(archive).hash();}
    /** Reads one bounded archive image so verification and the bytes served by Minecraft cannot refer to different files. */
    public Snapshot snapshot(Path archive)throws Exception{
        if(!Files.isRegularFile(archive,LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(archive))throw new IllegalStateException("DATA_PACK_ARCHIVE_INVALID");
        byte[] image;try(var in=Files.newInputStream(archive)){image=in.readNBytes(18*1024*1024+1);}if(image.length>18*1024*1024)throw new IllegalStateException("DATA_PACK_RESOURCE_LIMIT");
        var remaining=new HashMap<>(files);var decoded=new TreeMap<String,byte[]>();
        try(var zip=new ZipInputStream(new java.io.ByteArrayInputStream(image))){
            ZipEntry entry;int count=0;while((entry=zip.getNextEntry())!=null){
                var ref=remaining.remove(entry.getName());if(++count>256||entry.isDirectory()||ref==null)throw new IllegalStateException("DATA_PACK_ARCHIVE_INVALID");
                byte[] bytes=zip.readNBytes(4*1024*1024+1);if(bytes.length!=ref.size()||!RuntimePackageCanonicalizer.sha256(bytes).equals(ref.sha256()))throw new IllegalStateException("DATA_PACK_RESOURCE_HASH");
                if(entry.getName().equals("pack.mcmeta")){if(bytes.length>65536)throw new IllegalStateException("DATA_PACK_METADATA_INVALID");var node=new ObjectMapper().readTree(bytes);if(node==null||!node.isObject()||!node.path("pack").isObject())throw new IllegalStateException("DATA_PACK_METADATA_INVALID");}
                decoded.put(entry.getName(),bytes);
            }
        }
        if(!remaining.isEmpty())throw new IllegalStateException("DATA_PACK_ARCHIVE_INVALID");return new Snapshot(RuntimePackageCanonicalizer.sha256(image),Collections.unmodifiableMap(decoded));
    }
}
