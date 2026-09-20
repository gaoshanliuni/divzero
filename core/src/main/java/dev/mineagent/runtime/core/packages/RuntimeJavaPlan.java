package dev.mineagent.runtime.core.packages;

import dev.mineagent.runtime.api.packages.*;
import dev.mineagent.runtime.core.content.ContentAddressedStore;
import java.nio.charset.StandardCharsets;

/** SERVER Java sources. RuntimeExtension start/stop remain explicit, not library enabled. */
public final class RuntimeJavaPlan {
    private RuntimeJavaPlan(){}
    public static RuntimeResourceRef source(RuntimePackage pkg){
        var entry=pkg.entrypoints().get("java");if(pkg.activationMode()!=ActivationMode.HOT_RUNTIME||entry==null||entry.side()!=RuntimeResourceSide.SERVER||!entry.path().toLowerCase(java.util.Locale.ROOT).endsWith(".java")||!pkg.definitions().isEmpty())throw new IllegalArgumentException("JAVA_PACKAGE_CONTRACT");
        if(pkg.nativeCompatibility()==null||!pkg.nativeCompatibility().targets().containsKey("SERVER"))throw new IllegalArgumentException("JAVA_PACKAGE_NATIVE_CONTRACT");
        int count=0;long bytes=0;for(var ref:pkg.resources().values())if(!ref.path().startsWith("ui/")&&ref.side()!=RuntimeResourceSide.CLIENT){if(ref.side()!=RuntimeResourceSide.SERVER||!ref.path().toLowerCase(java.util.Locale.ROOT).endsWith(".java")||ref.size()<1||ref.size()>64000)throw new IllegalArgumentException("JAVA_PACKAGE_MIXED_LIFECYCLE");CodeDraftSources.path(ref.path());count++;bytes+=ref.size();}
        if(count>64||bytes>CodeDraftSources.MAX_BYTES||entry.path().startsWith("ui/"))throw new IllegalArgumentException("STUDIO_WORKSPACE_SIZE");
        var ref=pkg.resources().get(entry.path());if(ref==null||ref.side()!=RuntimeResourceSide.SERVER||!ref.sha256().equals(entry.sha256()))throw new IllegalArgumentException("JAVA_PACKAGE_SOURCE_CHANGED");if(ref.size()<1||ref.size()>64000)throw new IllegalArgumentException("JAVA_PACKAGE_SOURCE_LIMIT");return ref;
    }
    public static String read(RuntimePackage pkg,ContentAddressedStore content)throws Exception {
        var ref=source(pkg);byte[] bytes;try(var stream=java.nio.file.Files.newInputStream(content.pathFor(ref.sha256()))){bytes=stream.readNBytes(64001);}
        if(bytes.length!=ref.size()||!RuntimePackageCanonicalizer.sha256(bytes).equals(ref.sha256()))throw new IllegalStateException("JAVA_PACKAGE_SOURCE_CHANGED");
        var decoder=StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT);String result=decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString();if(result.length()>16000)throw new IllegalArgumentException("JAVA_PACKAGE_SOURCE_LIMIT");return result;
    }
}
