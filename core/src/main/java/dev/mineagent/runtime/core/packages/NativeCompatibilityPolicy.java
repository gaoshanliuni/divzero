package dev.mineagent.runtime.core.packages;

import dev.mineagent.runtime.api.packages.*;
import java.util.*;

public final class NativeCompatibilityPolicy {
    public static final Set<String> ERROR_CODES=Set.of("NATIVE_COMPATIBILITY_REQUIRED","NATIVE_COMPATIBILITY_INVALID","NATIVE_COMPATIBILITY_UNDECLARED","NATIVE_COMPATIBILITY_TARGET_MISSING","NATIVE_COMPATIBILITY_MISMATCH","NATIVE_ENVIRONMENT_UNAVAILABLE","NATIVE_ENVIRONMENT_UNVERIFIED","NATIVE_DECLARATION_CANNOT_BE_OVERRIDDEN","NATIVE_COMPATIBILITY_OPERATION_REUSED","NATIVE_COMPATIBILITY_STALE","NATIVE_COMPATIBILITY_LEDGER_FULL","NATIVE_COMPATIBILITY_PIN_BUDGET","NATIVE_COMPATIBILITY_CONFIRM_REQUIRED","NATIVE_COMPATIBILITY_PERMISSION");
    private NativeCompatibilityPolicy(){}
    public record Environment(String minecraft,String loader,String loaderVersion,String namespace,int javaFeature,String javaRuntime,Map<String,String> mods){
        public Environment{Objects.requireNonNull(minecraft);Objects.requireNonNull(loader);Objects.requireNonNull(loaderVersion);Objects.requireNonNull(namespace);Objects.requireNonNull(javaRuntime);mods=Collections.unmodifiableMap(new TreeMap<>(mods));}
        public Map<String,Object> wire(){return Map.of("minecraft",minecraft,"loader",loader,"loaderVersion",loaderVersion,"namespace",namespace,"javaFeature",javaFeature,"javaRuntime",javaRuntime,"mods",mods);}
        public String fingerprint(){try{return RuntimePackageCanonicalizer.sha256(RuntimePackageCanonicalizer.stableJson(wire()));}catch(Exception e){throw new IllegalStateException("NATIVE_ENVIRONMENT_HASH",e);}}
    }
    public record Result(String code,List<String> issues){public Result{issues=List.copyOf(issues);}public boolean allowed(){return code.equals("NATIVE_COMPATIBLE")||code.equals("NATIVE_NOT_REQUIRED");}}
    public static boolean required(RuntimePackage pkg,String side){
        return required(pkg.activationMode(),pkg.entrypoints(),pkg.resources().values(),side);
    }
    public static boolean required(ActivationMode mode,Map<String,RuntimeEntrypoint> entries,Collection<RuntimeResourceRef> resources,String side){
        if(!Set.of("SERVER","CLIENT").contains(side))throw new IllegalArgumentException("NATIVE_SIDE");
        var nativeSide=side.equals("SERVER")?RuntimeResourceSide.SERVER:RuntimeResourceSide.CLIENT;
        if(entries.values().stream().anyMatch(e->!e.path().startsWith("ui/")&&(e.side()==nativeSide||e.side()==RuntimeResourceSide.COMMON)))return true;
        if(resources.stream().anyMatch(r->!r.path().startsWith("ui/")&&(r.side()==nativeSide||r.side()==RuntimeResourceSide.COMMON)&&(r.path().endsWith(".js")||r.path().endsWith(".mjs")||r.path().endsWith(".jar")||r.path().endsWith(".class"))))return true;
        return side.equals("SERVER")&&(mode==ActivationMode.DATA_RELOAD||mode==ActivationMode.WORLD_REOPEN)
                ||side.equals("CLIENT")&&mode==ActivationMode.RESOURCE_RELOAD;
    }
    public static Result check(RuntimePackage pkg,Environment env,String side){
        return check(pkg.nativeCompatibility(),env,side,required(pkg,side));
    }
    public static Result check(NativeCompatibility contract,Environment env,String side,boolean required){
        if(!required)return new Result("NATIVE_NOT_REQUIRED",List.of());
        if(env==null)return new Result("NATIVE_ENVIRONMENT_UNAVAILABLE",List.of());
        if(env.namespace().equals("unverified"))return new Result("NATIVE_ENVIRONMENT_UNVERIFIED",List.of("NAMESPACE_UNVERIFIED"));
        if(contract==null)return new Result("NATIVE_COMPATIBILITY_UNDECLARED",List.of("旧签名包没有原生兼容声明"));
        var target=contract.targets().get(side);if(target==null)return new Result("NATIVE_COMPATIBILITY_TARGET_MISSING",List.of(side));
        var issues=new ArrayList<String>();
        if(!target.minecraft().equals(env.minecraft()))issues.add("MINECRAFT_VERSION_MISMATCH");
        if(!target.loader().equals(env.loader()))issues.add("LOADER_MISMATCH");
        if(!target.loaderVersion().equals(env.loaderVersion()))issues.add("LOADER_VERSION_MISMATCH");
        if(!target.namespace().equals(env.namespace()))issues.add("NAMESPACE_MISMATCH");
        if(target.javaFeature()!=env.javaFeature())issues.add("JAVA_FEATURE_MISMATCH");
        target.requiredMods().forEach((id,version)->{String actual=env.mods().get(id);if(actual==null)issues.add("MOD_MISSING:"+id);else if(!version.equals(actual))issues.add("MOD_VERSION_MISMATCH:"+id);});
        return new Result(issues.isEmpty()?"NATIVE_COMPATIBLE":"NATIVE_COMPATIBILITY_MISMATCH",issues);
    }
}
