package dev.mineagent.runtime.core.packages;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mineagent.runtime.api.packages.*;
import dev.mineagent.runtime.core.content.ContentAddressedStore;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.channels.FileChannel;
import java.util.*;
import java.util.zip.*;

/** Signed client-resource files. This is not the Runtime Java/Rhino execution path. */
public record ResourcePackPlan(RuntimePackage manifest,Map<String,RuntimeResourceRef> files,long bytes){
    public static final int MAX_BUNDLE=20*1024*1024,MAX_ARCHIVE=18*1024*1024;
    public static final String PREFIX="mineagent-client-";
    @FunctionalInterface public interface Verifier{boolean verify(byte[] value,byte[] signature)throws Exception;}
    public record Resolved(ResourcePackPlan plan,byte[] archive,String archiveHash,Map<String,byte[]> files){}
    public ResourcePackPlan{files=Collections.unmodifiableMap(new TreeMap<>(files));}
    public static ResourcePackPlan inspect(RuntimePackage p){
        if(p.activationMode()!=ActivationMode.RESOURCE_RELOAD)throw new IllegalStateException("RESOURCE_PACK_LIFECYCLE_REQUIRED");var entry=p.entrypoints().get("resourcepack");
        if(entry==null||!entry.path().equals("resourcepack/pack.mcmeta")||entry.side()==RuntimeResourceSide.SERVER||!p.definitions().isEmpty())throw new IllegalStateException("RESOURCE_PACK_ENTRY_REQUIRED");
        if(p.nativeCompatibility()==null||!p.nativeCompatibility().targets().containsKey("CLIENT"))throw new IllegalStateException("RESOURCE_PACK_CLIENT_CONTRACT_REQUIRED");
        var files=new TreeMap<String,RuntimeResourceRef>();long bytes=0;
        for(var ref:p.resources().values()){
            if(ref.path().startsWith("ui/"))continue;
            if(!ref.path().startsWith("resourcepack/")||ref.side()==RuntimeResourceSide.SERVER)throw new IllegalStateException("RESOURCE_PACK_MIXED_LIFECYCLE");String path=ref.path().substring(13);
            if(!path.equals("pack.mcmeta")&&!path.equals("pack.png")&&!path.matches("assets/[a-z0-9_.-]+/[a-z0-9_./-]+")||Arrays.stream(path.split("/",-1)).anyMatch(s->s.isEmpty()||s.equals(".")||s.equals("..")))throw new IllegalStateException("RESOURCE_PACK_PATH");
            if(ref.size()<0||ref.size()>4*1024*1024||files.put(path,ref)!=null)throw new IllegalStateException("RESOURCE_PACK_RESOURCE_LIMIT");bytes+=ref.size();
        }
        if(!files.containsKey("pack.mcmeta")||files.size()<2||files.size()>256||bytes>16*1024*1024)throw new IllegalStateException("RESOURCE_PACK_RESOURCE_LIMIT");
        for(var e:p.entrypoints().entrySet())if(!e.getKey().equals("resourcepack")&&!e.getValue().path().startsWith("ui/"))throw new IllegalStateException("RESOURCE_PACK_MIXED_LIFECYCLE");return new ResourcePackPlan(p,files,bytes);
    }
    public String filename(String fingerprint){if(!fingerprint.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("RESOURCE_PACK_IDENTITY");return PREFIX+fingerprint+"-"+manifest.packageId()+"-"+manifest.canonicalSha256()+".zip";}
    public static boolean managedName(String name){return name!=null&&name.matches("mineagent-client-[a-f0-9]{64}-[a-f0-9-]{36}-[a-f0-9]{64}\\.zip");}
    public byte[] bundle(ContentAddressedStore store)throws Exception{
        var output=new ByteArrayOutputStream();try(var zip=new ZipOutputStream(output)){for(var e:files.entrySet()){byte[] data=store.read(e.getValue().sha256());check(e.getValue(),data);var crc=new CRC32();crc.update(data);var z=new ZipEntry(e.getKey());z.setMethod(ZipEntry.STORED);z.setSize(data.length);z.setCompressedSize(data.length);z.setCrc(crc.getValue());z.setTime(0);zip.putNextEntry(z);zip.write(data);zip.closeEntry();}}
        byte[] archive=output.toByteArray();snapshot(archive);byte[] metadata=new ObjectMapper().writeValueAsBytes(manifest);if(metadata.length>512*1024)throw new IllegalStateException("RESOURCE_PACK_MANIFEST_LIMIT");
        var body=new ByteArrayOutputStream();try(var data=new DataOutputStream(body)){data.writeInt(0x4d415231);data.writeInt(metadata.length);data.write(metadata);data.writeInt(archive.length);data.write(archive);}if(body.size()>MAX_BUNDLE)throw new IllegalStateException("RESOURCE_PACK_BUNDLE_LIMIT");return body.toByteArray();
    }
    public static Resolved decode(byte[] body,UUID id,long revision,String canonical,Verifier verifier)throws Exception{
        if(body.length>MAX_BUNDLE)throw new IllegalStateException("RESOURCE_PACK_BUNDLE_LIMIT");try(var data=new DataInputStream(new ByteArrayInputStream(body))){
            if(data.readInt()!=0x4d415231)throw new IllegalStateException("RESOURCE_PACK_BUNDLE_FORMAT");int n=data.readInt();if(n<1||n>512*1024)throw new IllegalStateException("RESOURCE_PACK_MANIFEST_LIMIT");byte[] metadata=data.readNBytes(n);if(metadata.length!=n)throw new EOFException();var p=new ObjectMapper().readValue(metadata,RuntimePackage.class);
            if(!p.packageId().equals(id)||p.revision()!=revision||!p.canonicalSha256().equals(canonical))throw new IllegalStateException("RESOURCE_PACK_SOURCE_CHANGED");verify(p,verifier);
            int size=data.readInt();if(size<1||size>MAX_ARCHIVE)throw new IllegalStateException("RESOURCE_PACK_ARCHIVE_LIMIT");byte[] image=data.readNBytes(size);if(image.length!=size||data.read()!=-1)throw new IllegalStateException("RESOURCE_PACK_BUNDLE_FORMAT");var plan=inspect(p);return new Resolved(plan,image,RuntimePackageCanonicalizer.sha256(image),plan.snapshot(image));
        }
    }
    public static void verify(RuntimePackage p,Verifier verifier)throws Exception{if(!RuntimePackageCanonicalizer.sha256(p).equals(p.canonicalSha256())||!verifier.verify(p.canonicalSha256().getBytes(StandardCharsets.US_ASCII),Base64.getDecoder().decode(p.signature())))throw new IllegalStateException("RESOURCE_PACK_SIGNATURE");}
    public Map<String,byte[]> snapshot(byte[] image)throws Exception{
        if(image.length>MAX_ARCHIVE)throw new IllegalStateException("RESOURCE_PACK_ARCHIVE_LIMIT");var remaining=new HashMap<>(files);var result=new TreeMap<String,byte[]>();
        try(var zip=new ZipInputStream(new ByteArrayInputStream(image))){ZipEntry entry;int count=0;while((entry=zip.getNextEntry())!=null){var ref=remaining.remove(entry.getName());if(++count>256||entry.isDirectory()||ref==null)throw new IllegalStateException("RESOURCE_PACK_ARCHIVE_CONTENTS");byte[] data=zip.readNBytes(4*1024*1024+1);check(ref,data);if(entry.getName().equals("pack.mcmeta")){if(data.length>65536)throw new IllegalStateException("RESOURCE_PACK_METADATA_INVALID");var metadata=new ObjectMapper().readTree(data);if(metadata==null||!metadata.isObject()||!metadata.path("pack").isObject())throw new IllegalStateException("RESOURCE_PACK_METADATA_INVALID");}result.put(entry.getName(),data);}}
        if(!remaining.isEmpty())throw new IllegalStateException("RESOURCE_PACK_ARCHIVE_CONTENTS");return Collections.unmodifiableMap(result);
    }
    private static void check(RuntimeResourceRef ref,byte[] bytes)throws Exception{if(bytes.length!=ref.size()||!RuntimePackageCanonicalizer.sha256(bytes).equals(ref.sha256()))throw new IllegalStateException("RESOURCE_PACK_RESOURCE_HASH");}
    public static byte[] readArchive(Path file)throws Exception{if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(file))throw new IllegalStateException("RESOURCE_PACK_FILE_LINK");try(var in=Files.newInputStream(file)){byte[] bytes=in.readNBytes(MAX_ARCHIVE+1);if(bytes.length>MAX_ARCHIVE)throw new IllegalStateException("RESOURCE_PACK_ARCHIVE_LIMIT");return bytes;}}
    public static void publish(Path directory,String filename,byte[] bytes,String expected)throws Exception{
        if(!managedName(filename)||!RuntimePackageCanonicalizer.sha256(bytes).equals(expected))throw new IllegalStateException("RESOURCE_PACK_ARCHIVE_HASH");
        if(Files.exists(directory,LinkOption.NOFOLLOW_LINKS)&&(!Files.isDirectory(directory,LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(directory)))throw new IllegalStateException("RESOURCE_PACK_DIRECTORY_LINK");Files.createDirectories(directory);Path root=directory.toRealPath(),target=root.resolve(filename);
        if(Files.exists(target,LinkOption.NOFOLLOW_LINKS)){if(!RuntimePackageCanonicalizer.sha256(readArchive(target)).equals(expected))throw new IllegalStateException("RESOURCE_PACK_FILE_CONFLICT");return;}
        Path temporary=Files.createTempFile(root,".mineagent-resource-",".pending");try{Files.write(temporary,bytes);try(var channel=FileChannel.open(temporary,StandardOpenOption.WRITE)){channel.force(true);}Files.createLink(target,temporary);}finally{Files.deleteIfExists(temporary);}
    }
}
