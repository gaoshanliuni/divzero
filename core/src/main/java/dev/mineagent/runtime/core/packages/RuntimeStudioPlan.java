package dev.mineagent.runtime.core.packages;

import dev.mineagent.runtime.api.packages.*;
import dev.mineagent.runtime.core.content.ContentAddressedStore;

/** Shared Code Studio source contract; historical Java table/channel names remain wire-compatible. */
public final class RuntimeStudioPlan {
    private RuntimeStudioPlan(){}
    public static boolean java(RuntimePackage pkg){return pkg.entrypoints().containsKey("java");}
    public static RuntimeResourceRef source(RuntimePackage pkg){if(java(pkg)&&pkg.entrypoints().containsKey("studio_script"))throw new IllegalArgumentException("STUDIO_LANGUAGE_CHANGED");return java(pkg)?RuntimeJavaPlan.source(pkg):RuntimeStudioScriptPlan.source(pkg);}
    public static String read(RuntimePackage pkg,ContentAddressedStore store)throws Exception{source(pkg);return java(pkg)?RuntimeJavaPlan.read(pkg,store):RuntimeStudioScriptPlan.read(pkg,store);}
    public static java.util.Map<String,CodeDraft.SourceRef> refs(RuntimePackage pkg){
        var entry=source(pkg);boolean javaSource=java(pkg);var refs=new java.util.TreeMap<String,CodeDraft.SourceRef>();for(var ref:pkg.resources().values())if(ref.side()==RuntimeResourceSide.SERVER&&!ref.path().startsWith("ui/")&&(CodeDraftSources.java(ref.path())==javaSource))refs.put(ref.path(),new CodeDraft.SourceRef(ref.sha256(),Math.toIntExact(ref.size())));
        CodeDraftSources.validate(entry.path(),refs);return java.util.Collections.unmodifiableMap(refs);
    }
    public static String fingerprint(RuntimePackage pkg)throws Exception{return CodeDraftSources.fingerprint(source(pkg).path(),refs(pkg),pkg.dependencies());}
    public static java.util.Map<String,String> readAll(RuntimePackage pkg,ContentAddressedStore store)throws Exception {var result=new java.util.TreeMap<String,String>();for(var entry:refs(pkg).entrySet())result.put(entry.getKey(),CodeDraftSources.read(store,entry.getValue()));return java.util.Collections.unmodifiableMap(result);}
}
