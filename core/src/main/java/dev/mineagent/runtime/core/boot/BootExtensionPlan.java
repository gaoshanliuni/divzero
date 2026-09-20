package dev.mineagent.runtime.core.boot;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mineagent.runtime.api.packages.*;
import dev.mineagent.runtime.core.content.ContentAddressedStore;
import dev.mineagent.runtime.core.compile.NativeCompilationSnapshot;
import dev.mineagent.runtime.core.packages.*;
import java.nio.charset.*;
import java.nio.ByteBuffer;
import java.util.*;

/** Source-only BOOT package contract. It does not approve, install or execute any generated class. */
public record BootExtensionPlan(RuntimePackage manifest, RuntimeResourceSide side, String modId,
        String entrypoint, List<String> mixins, Map<String,String> sources, Map<String,byte[]> resources) {
    public static final String DESCRIPTOR="boot/extension.json";
    public static final int MAX_ARCHIVE=24*1024*1024;
    public BootExtensionPlan {
        mixins=List.copyOf(mixins);sources=Collections.unmodifiableMap(new TreeMap<>(sources));
        var copied=new TreeMap<String,byte[]>();resources.forEach((k,v)->copied.put(k,v.clone()));resources=Collections.unmodifiableMap(copied);
    }
    public static void validate(RuntimePackage pkg) {
        if(pkg.activationMode()!=ActivationMode.BOOT_EXTENSION)throw new IllegalStateException("BOOT_LIFECYCLE_REQUIRED");
        if(pkg.dependencies().size()>BootDependencyGraph.MAX_NODES||pkg.dependencies().containsKey(pkg.packageId())||pkg.dependencies().values().stream().anyMatch(v->v==null||!v.matches("[0-9A-Za-z_.+-]{1,64}")))throw new IllegalStateException("BOOT_DEPENDENCY_DECLARATION");
        var entry=pkg.entrypoints().get("boot");
        if(entry==null||!entry.path().equals(DESCRIPTOR)||!pkg.definitions().isEmpty())throw new IllegalStateException("BOOT_ENTRY_REQUIRED");
        if(pkg.nativeCompatibility()==null)throw new IllegalStateException("BOOT_NATIVE_CONTRACT_REQUIRED");
        for(String side:List.of("CLIENT","SERVER"))if(entry.side()==RuntimeResourceSide.COMMON||entry.side().name().equals(side))
            if(!pkg.nativeCompatibility().targets().containsKey(side))throw new IllegalStateException("BOOT_NATIVE_CONTRACT_REQUIRED");
        long total=0;int java=0;
        for(var ref:pkg.resources().values()) {
            if(ref.path().startsWith("ui/"))continue;
            if(ref.side()!=entry.side()||ref.size()<0||ref.size()>4*1024*1024)throw new IllegalStateException("BOOT_RESOURCE_CONTRACT");
            String path=ref.path();if(!path.equals(DESCRIPTOR)&&!path.startsWith("boot/src/")&&!path.startsWith("boot/resources/"))throw new IllegalStateException("BOOT_MIXED_LIFECYCLE");
            if(path.startsWith("boot/src/")){String name=path.substring(9);if(!name.matches("(?:[A-Za-z_$][A-Za-z0-9_$]*/)*[A-Za-z_$][A-Za-z0-9_$]*\\.java")||forbiddenClass(name.substring(0,name.length()-5)))throw new IllegalStateException("BOOT_SOURCE_PATH");java++;}
            if(path.startsWith("boot/resources/"))validateResource(path.substring(15));
            total+=ref.size();
        }
        if(java<1||java>64||total>16*1024*1024||pkg.resources().size()>256)throw new IllegalStateException("BOOT_RESOURCE_LIMIT");
        for(var e:pkg.entrypoints().entrySet())if(!e.getKey().equals("boot")&&!e.getValue().path().startsWith("ui/"))throw new IllegalStateException("BOOT_MIXED_LIFECYCLE");
    }
    public static BootExtensionPlan read(RuntimePackage pkg,ContentAddressedStore content)throws Exception {
        validate(pkg);var source=new TreeMap<String,String>();var resources=new TreeMap<String,byte[]>();byte[] descriptor=null;long javaBytes=0;
        for(var ref:pkg.resources().values()) {
            if(ref.path().startsWith("ui/"))continue;
            byte[] bytes=NativeCompilationSnapshot.read(content.pathFor(ref.sha256()),4*1024*1024);
            if(bytes.length!=ref.size()||!RuntimePackageCanonicalizer.sha256(bytes).equals(ref.sha256()))throw new IllegalStateException("BOOT_RESOURCE_HASH");
            if(ref.path().equals(DESCRIPTOR))descriptor=bytes;
            else if(ref.path().startsWith("boot/src/")){javaBytes+=bytes.length;source.put(ref.path().substring(9),utf8(bytes));}
            else resources.put(ref.path().substring(15),bytes);
        }
        if(descriptor==null||descriptor.length>65536||javaBytes>4*1024*1024)throw new IllegalStateException("BOOT_DESCRIPTOR_LIMIT");
        var root=new ObjectMapper().readTree(descriptor);if(root==null||!root.isObject())throw new IllegalStateException("BOOT_DESCRIPTOR");
        var keys=new HashSet<String>();root.fieldNames().forEachRemaining(keys::add);
        if(!keys.equals(Set.of("schema","modId","entrypoint","mixins"))||!root.path("schema").isInt()||root.path("schema").asInt()!=1)throw new IllegalStateException("BOOT_DESCRIPTOR");
        String id=root.path("modId").asText(),entry=root.path("entrypoint").asText();
        if(!id.matches("[a-z][a-z0-9_]{1,63}")||Set.of("minecraft","neoforge","mineagent_runtime").contains(id)||!entry.matches("(?:[A-Za-z_$][A-Za-z0-9_$]*\\.)+[A-Za-z_$][A-Za-z0-9_$]*")||entry.length()>240||forbiddenClass(entry.replace('.','/'))||!source.containsKey(entry.replace('.','/')+".java"))throw new IllegalStateException("BOOT_DESCRIPTOR_IDENTITY");
        try{java.lang.module.ModuleDescriptor.newAutomaticModule(id).build();}catch(IllegalArgumentException invalid){throw new IllegalStateException("BOOT_DESCRIPTOR_MODULE_ID");}
        var mixins=new ArrayList<String>();var list=root.path("mixins");if(!list.isArray()||list.size()>8)throw new IllegalStateException("BOOT_MIXINS");
        for(var value:list){if(!value.isTextual())throw new IllegalStateException("BOOT_MIXINS");String file=value.asText();if(!file.matches("[a-z0-9_.-]{1,128}\\.json")||!resources.containsKey(file)||mixins.contains(file))throw new IllegalStateException("BOOT_MIXINS");var json=new ObjectMapper().readTree(resources.get(file));if(!json.isObject())throw new IllegalStateException("BOOT_MIXINS");mixins.add(file);}
        return new BootExtensionPlan(pkg,pkg.entrypoints().get("boot").side(),id,entry,mixins,source,resources);
    }
    public void requireEnvironment(NativeCompilationSnapshot snapshot){requireEnvironment(snapshot,null);}
    public void requireEnvironment(NativeCompilationSnapshot snapshot,BootReplacement replacement) {
        String side=snapshot.physicalSide();if(this.side!=RuntimeResourceSide.COMMON&&!this.side.name().equals(side))throw new IllegalStateException("BOOT_PHYSICAL_SIDE_MISMATCH");
        var result=NativeCompatibilityPolicy.check(manifest.nativeCompatibility(),snapshot.environment(),side,true);
        if(!result.allowed())throw new IllegalStateException("BOOT_ENVIRONMENT_MISMATCH");
        if(replacement!=null)replacement.requirePlan(this);
        if(snapshot.environment().mods().containsKey(modId)&&replacement==null)throw new IllegalStateException("BOOT_MOD_ID_ALREADY_LOADED");
    }
    public static boolean forbiddenClass(String path){return List.of("java/","javax/","jdk/","sun/","com/sun/","net/minecraft/","net/neoforged/","dev/mineagent/runtime/","dev/mineagent/boot/").stream().anyMatch(path::startsWith)||path.equals("module-info");}
    private static void validateResource(String path){String lower=path.toLowerCase(Locale.ROOT);if(path.length()>240||path.startsWith("/")||path.contains("\\")||Arrays.stream(path.split("/",-1)).anyMatch(s->s.isEmpty()||s.equals(".")||s.equals(".."))||lower.endsWith(".class")||lower.endsWith(".jar")||lower.startsWith("meta-inf/")&&!path.equals("META-INF/accesstransformer.cfg"))throw new IllegalStateException("BOOT_RESOURCE_PATH");}
    private static String utf8(byte[] bytes)throws CharacterCodingException{return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();}
}
