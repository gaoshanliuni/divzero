package dev.mineagent.runtime.core.packages;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mineagent.runtime.api.packages.*;
import dev.mineagent.runtime.core.crypto.IdentitySigner;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Versioned authentication of actual manifests and resource bytes, independent from resource IDs.
 * JCA performs Ed25519 authentication internally; DivZero never computes a file/content digest.
 * Verifying a resource proves consistency with its envelope, not trust in its publisher. Callers
 * must separately verifyManifest against the already approved server key before accepting content.
 */
public final class PackageSignatures {
    private static final String PREFIX="DVZ2:";
    private static final ObjectMapper JSON=new ObjectMapper()
            .enable(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    public record Envelope(String publicKey,Map<String,String> resources,String manifestSignature){
        public Envelope{Objects.requireNonNull(publicKey);resources=Map.copyOf(resources);Objects.requireNonNull(manifestSignature);if(resources.size()>1024)throw new IllegalArgumentException("PACKAGE_SIGNATURE_RESOURCE_LIMIT");}
    }
    @FunctionalInterface public interface Reader{byte[] read(String resourceId)throws Exception;}
    @FunctionalInterface public interface Verifier{boolean verify(byte[] payload,byte[] signature)throws Exception;}
    public static boolean modern(RuntimePackage p){return p.signature().startsWith(PREFIX);}
    public static RuntimePackage signNew(RuntimePackage draft,Reader source,IdentitySigner signer)throws Exception{return signVersion(draft,ResourceIds.create(),source,signer);}
    public static RuntimePackage signVersion(RuntimePackage draft,String versionId,Reader source,IdentitySigner signer)throws Exception{
        var proofs=new TreeMap<String,String>();
        for(var ref:new TreeMap<>(draft.resources()).values()){
            byte[] bytes=source.read(ref.sha256());if(bytes.length!=ref.size())throw new IllegalArgumentException("PACKAGE_RESOURCE_SIZE: "+ref.path());
            if(!proofs.containsKey(ref.sha256()))proofs.put(ref.sha256(),Base64.getEncoder().encodeToString(signer.sign(resourcePayload(ref,bytes))));
        }
        return seal(draft,versionId,proofs,signer);
    }
    public static RuntimePackage copySigned(RuntimePackage source,RuntimePackage target,IdentitySigner signer)throws Exception{
        if(!source.resources().equals(target.resources())||!verifyManifest(source,(bytes,proof)->IdentitySigner.verify(signer.publicKeyEncoded(),bytes,proof)))throw new IllegalArgumentException("PACKAGE_COPY_SIGNATURE_OR_RESOURCES");
        return seal(target,ResourceIds.create(),envelope(source).resources(),signer);
    }
    private static RuntimePackage seal(RuntimePackage draft,String id,Map<String,String> proofs,IdentitySigner signer)throws Exception{
        String key=Base64.getEncoder().encodeToString(signer.publicKeyEncoded());var unsigned=copy(draft,ResourceIds.require(id),"");
        String proof=Base64.getEncoder().encodeToString(signer.sign(manifestPayload(unsigned,key,proofs)));
        return copy(unsigned,id,PREFIX+Base64.getEncoder().encodeToString(JSON.writeValueAsBytes(new Envelope(key,proofs,proof))));
    }
    public static boolean verifyManifest(RuntimePackage p,Verifier trusted)throws Exception{
        var envelope=envelope(p);return trusted.verify(manifestPayload(p,envelope.publicKey(),envelope.resources()),Base64.getDecoder().decode(envelope.manifestSignature()));
    }
    public static void requireResource(RuntimePackage p,RuntimeResourceRef ref,byte[] bytes)throws Exception{
        if(!ref.equals(p.resources().get(ref.path()))||bytes==null||bytes.length!=ref.size())throw new IllegalArgumentException("PACKAGE_RESOURCE_CONTEXT: "+ref.path());
        var envelope=envelope(p);String signature=envelope.resources().get(ref.sha256());
        if(signature==null||!IdentitySigner.verify(Base64.getDecoder().decode(envelope.publicKey()),resourcePayload(ref,bytes),Base64.getDecoder().decode(signature)))throw new SecurityException("PACKAGE_RESOURCE_SIGNATURE: "+ref.path());
    }
    private static byte[] manifestPayload(RuntimePackage p,String key,Map<String,String> resources)throws Exception{
        return JSON.writeValueAsBytes(Map.of("format","divzero-signed-resources/2","versionId",p.canonicalSha256(),"publicKey",key,"manifest",JSON.readTree(RuntimePackageCanonicalizer.canonicalManifest(p)),"resourceSignatures",new TreeMap<>(resources)));
    }
    private static byte[] resourcePayload(RuntimeResourceRef ref,byte[] bytes)throws Exception{
        if(bytes.length>64*1024*1024)throw new IllegalArgumentException("PACKAGE_SIGNATURE_RESOURCE_BYTES");
        var out=new ByteArrayOutputStream(bytes.length+128);out.write("DivZero resource v2\n".getBytes(StandardCharsets.US_ASCII));out.write(ref.sha256().getBytes(StandardCharsets.US_ASCII));out.write('\n');out.write(Long.toString(ref.size()).getBytes(StandardCharsets.US_ASCII));out.write('\n');out.write(bytes);return out.toByteArray();
    }
    private static Envelope envelope(RuntimePackage p)throws Exception{
        if(!modern(p)||p.signature().length()>512*1024)throw new IllegalArgumentException("PACKAGE_SIGNATURE_VERSION_REQUIRES_MIGRATION");
        var value=JSON.readValue(Base64.getDecoder().decode(p.signature().substring(PREFIX.length())),Envelope.class);
        var required=new TreeSet<String>();for(var ref:p.resources().values())required.add(ref.sha256());if(!required.equals(value.resources().keySet()))throw new IllegalArgumentException("PACKAGE_SIGNATURE_RESOURCE_SET");
        IdentitySigner.keyId(Base64.getDecoder().decode(value.publicKey()));return value;
    }
    private static RuntimePackage copy(RuntimePackage p,String id,String signature){return new RuntimePackage(p.packageId(),p.type(),p.name(),p.version(),p.activationMode(),p.dependencies(),p.permissions(),p.entrypoints(),p.definitions(),p.resources(),p.origin(),p.enabled(),p.revision(),id,signature,p.updatedAtEpochMillis(),p.nativeCompatibility());}
    private PackageSignatures(){}
}
