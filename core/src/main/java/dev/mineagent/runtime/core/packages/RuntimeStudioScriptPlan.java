package dev.mineagent.runtime.core.packages;

import dev.mineagent.runtime.api.packages.*;
import dev.mineagent.runtime.core.content.ContentAddressedStore;
import java.nio.charset.StandardCharsets;

/** Owned Rhino modules with one explicit entry, distinct from registered WorldContent instances. */
public final class RuntimeStudioScriptPlan {
    private RuntimeStudioScriptPlan(){}
    public static boolean path(String path){return path!=null&&(path.toLowerCase(java.util.Locale.ROOT).endsWith(".js")||path.toLowerCase(java.util.Locale.ROOT).endsWith(".mjs"));}
    public static RuntimeResourceRef source(RuntimePackage pkg){
        var entry=pkg.entrypoints().get("studio_script");if(pkg.activationMode()!=ActivationMode.HOT_RUNTIME||entry==null||entry.side()!=RuntimeResourceSide.SERVER||!path(entry.path())||!pkg.definitions().isEmpty()||pkg.entrypoints().containsKey("java"))throw new IllegalArgumentException("STUDIO_SCRIPT_PACKAGE_CONTRACT");
        if(pkg.nativeCompatibility()==null||!pkg.nativeCompatibility().targets().containsKey("SERVER"))throw new IllegalArgumentException("STUDIO_SCRIPT_NATIVE_CONTRACT");
        int count=0;long bytes=0;for(var ref:pkg.resources().values())if(!ref.path().startsWith("ui/")&&ref.side()!=RuntimeResourceSide.CLIENT){if(ref.side()!=RuntimeResourceSide.SERVER||!path(ref.path())||ref.size()<1||ref.size()>64000)throw new IllegalArgumentException("STUDIO_SCRIPT_MIXED_LIFECYCLE");CodeDraftSources.path(ref.path());count++;bytes+=ref.size();}
        if(count>64||bytes>CodeDraftSources.MAX_BYTES||entry.path().startsWith("ui/"))throw new IllegalArgumentException("STUDIO_WORKSPACE_SIZE");
        for(var other:pkg.entrypoints().entrySet())if(!other.getKey().equals("studio_script")&&(other.getValue().side()!=RuntimeResourceSide.CLIENT||!other.getValue().path().startsWith("ui/")&&!other.getValue().path().startsWith("client/")))throw new IllegalArgumentException("STUDIO_SCRIPT_MIXED_LIFECYCLE");
        var ref=pkg.resources().get(entry.path());if(ref==null||ref.side()!=RuntimeResourceSide.SERVER||!ref.sha256().equals(entry.sha256()))throw new IllegalArgumentException("STUDIO_SCRIPT_SOURCE_CHANGED");if(ref.size()<1||ref.size()>64000)throw new IllegalArgumentException("STUDIO_SCRIPT_SOURCE_LIMIT");return ref;
    }
    public static String read(RuntimePackage pkg,ContentAddressedStore content)throws Exception {var ref=source(pkg);byte[] bytes;try(var in=java.nio.file.Files.newInputStream(content.pathFor(ref.sha256()))){bytes=in.readNBytes(64001);}if(bytes.length!=ref.size()||!RuntimePackageCanonicalizer.sha256(bytes).equals(ref.sha256()))throw new IllegalStateException("STUDIO_SCRIPT_SOURCE_CHANGED");String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString();if(text.length()>16000)throw new IllegalArgumentException("STUDIO_SCRIPT_SOURCE_LIMIT");return text;}
}
