package dev.mineagent.runtime.neoforge.content;

import dev.mineagent.runtime.api.packages.RuntimePackage;
import dev.mineagent.runtime.api.permission.PermissionAction;
import dev.mineagent.runtime.core.packages.*;
import dev.mineagent.runtime.neoforge.MineAgentRuntimeServices;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import java.util.*;

/** Server environment admission only. Client-native execution must use its own observed environment and trust gate. */
public final class NativePackageCompatibility implements AutoCloseable {
    private final MinecraftServer server;private final LegacyCompatibilityPins pins;private final NativeCompatibilityPolicy.Environment environment;private final String environmentHash;
    public NativePackageCompatibility(MinecraftServer server)throws Exception{this.server=server;environment=observe();environmentHash=environment.fingerprint();pins=new LegacyCompatibilityPins(server.getServerDirectory().resolve("mineagent-runtime-data/runtime.db"),MineAgentRuntimeServices.worldId(server));}
    public static NativeCompatibilityPolicy.Environment observe(){
        var mods=new TreeMap<String,String>();for(var mod:net.neoforged.fml.ModList.get().getMods())mods.put(mod.getModId(),mod.getVersion().toString());
        if(mods.size()>4096||!mods.containsKey("minecraft")||!mods.containsKey("neoforge"))throw new IllegalStateException("NATIVE_ENVIRONMENT_UNAVAILABLE");
        String namespace=mods.get("minecraft").equals("26.1.2")&&MinecraftServer.class.getName().equals("net.minecraft.server.MinecraftServer")?"official":"unverified";
        return new NativeCompatibilityPolicy.Environment(mods.get("minecraft"),"neoforge",mods.get("neoforge"),namespace,Runtime.version().feature(),Runtime.version().toString(),mods);
    }
    public NativeCompatibilityPolicy.Environment environment(){return environment;}
    public String environmentHash(){return environmentHash;}
    public String check(RuntimePackage pkg,UUID owner){
        var result=NativeCompatibilityPolicy.check(pkg,environment,"SERVER");if(result.code().equals("NATIVE_NOT_REQUIRED"))return "";
        if(!environment.namespace().equals("official"))return "NATIVE_ENVIRONMENT_UNVERIFIED";
        if(result.allowed())return "";
        if(!result.code().equals("NATIVE_COMPATIBILITY_UNDECLARED"))return result.code();
        var config=MineAgentRuntimeServices.config(server);return pins.allowed(key(owner,pkg),config.permissionGeneration(owner,PermissionAction.RUN_CODE),config.permissionGeneration(owner,PermissionAction.MANAGE_PACKAGES))?"":result.code();
    }
    private LegacyCompatibilityPins.Key key(UUID owner,RuntimePackage pkg){return new LegacyCompatibilityPins.Key(owner,pkg.packageId(),pkg.canonicalSha256(),environmentHash);}
    public Map<String,Object> inspect(ServerPlayer viewer,RuntimePackage pkg,int offset){
        if(offset<0||offset>4096)throw new IllegalArgumentException("NATIVE_ENVIRONMENT_PAGE");var result=NativeCompatibilityPolicy.check(pkg,environment,"SERVER");var pin=pins.get(key(viewer.getUUID(),pkg));
        boolean manage=allowed(viewer,PermissionAction.MANAGE_PACKAGES),nativePermission=allowed(viewer,PermissionAction.RUN_CODE);var mods=new LinkedHashMap<String,String>();
        if(manage)environment.mods().entrySet().stream().skip(offset).limit(16).forEach(e->mods.put(e.getKey(),e.getValue()));
        var observed=new LinkedHashMap<String,Object>();observed.put("minecraft",environment.minecraft());observed.put("loader",environment.loader());observed.put("loaderVersion",environment.loaderVersion());observed.put("namespace",environment.namespace());observed.put("javaFeature",environment.javaFeature());observed.put("javaRuntime",environment.javaRuntime());observed.put("mods",mods);
        return Map.ofEntries(Map.entry("packageId",pkg.packageId()),Map.entry("revision",pkg.revision()),Map.entry("hash",pkg.canonicalSha256()),Map.entry("contract",contractView(pkg,offset)),Map.entry("declarationStatus",result.code()),Map.entry("admissionError",check(pkg,viewer.getUUID())),Map.entry("issues",result.issues()),Map.entry("environment",observed),Map.entry("environmentHash",environmentHash),Map.entry("pinRevision",pin==null?0:pin.revision()),Map.entry("pinActive",pin!=null&&pin.active()),Map.entry("canAttest",manage&&nativePermission&&!check(pkg,viewer.getUUID()).isEmpty()&&pkg.nativeCompatibility()==null&&result.code().equals("NATIVE_COMPATIBILITY_UNDECLARED")&&environment.namespace().equals("official")),Map.entry("canWithdraw",pin!=null&&pin.active()),Map.entry("offset",offset),Map.entry("nextOffset",offset+16),Map.entry("more",offset+16<Math.max(manage?environment.mods().size():0,pkg.nativeCompatibility()==null?0:pkg.nativeCompatibility().targets().values().stream().mapToInt(t->t.requiredMods().size()).max().orElse(0))));
    }
    private Map<String,Object> contractView(RuntimePackage pkg,int offset){
        if(pkg.nativeCompatibility()==null)return Map.of();var targets=new TreeMap<String,Object>();
        pkg.nativeCompatibility().targets().forEach((side,target)->{
            var page=new TreeMap<String,String>();target.requiredMods().entrySet().stream().skip(offset).limit(16).forEach(e->page.put(e.getKey(),e.getValue()));
            var values=new TreeMap<String,Object>(target.wire());values.put("requiredMods",page);values.put("requiredModsOffset",offset);values.put("requiredModsTotal",target.requiredMods().size());targets.put(side,values);
        });
        return Map.of("schema",pkg.nativeCompatibility().schema(),"targets",targets,"projection","PAGED_REQUIRED_MODS_NOT_A_MANIFEST");
    }
    public void authorizeChange(ServerPlayer viewer,RuntimePackage pkg,boolean approve,String expectedEnvironment,boolean confirmed){
        if(viewer instanceof dev.mineagent.runtime.neoforge.body.MineAgentPlayer||server.getPlayerList().getPlayer(viewer.getUUID())!=viewer||!server.isSameThread()||!confirmed||!environmentHash.equals(expectedEnvironment))throw new SecurityException("NATIVE_COMPATIBILITY_CONFIRM_REQUIRED");
        if(pkg.nativeCompatibility()!=null)throw new IllegalStateException("NATIVE_DECLARATION_CANNOT_BE_OVERRIDDEN");
        if(approve&&(!allowed(viewer,PermissionAction.RUN_CODE)||!allowed(viewer,PermissionAction.MANAGE_PACKAGES)||!environment.namespace().equals("official")))throw new SecurityException("NATIVE_COMPATIBILITY_PERMISSION");
        if(approve&&!NativeCompatibilityPolicy.required(pkg,"SERVER"))throw new IllegalArgumentException("NATIVE_COMPATIBILITY_REQUIRED");
    }
    public Map<String,Object> change(ServerPlayer viewer,RuntimePackage pkg,UUID operation,long expected,boolean approve,String expectedEnvironment,boolean confirmed)throws Exception{
        authorizeChange(viewer,pkg,approve,expectedEnvironment,confirmed);
        var config=MineAgentRuntimeServices.config(server);var result=pins.change(operation,key(viewer.getUUID(),pkg),expected,approve,config.permissionGeneration(viewer.getUUID(),PermissionAction.RUN_CODE),config.permissionGeneration(viewer.getUUID(),PermissionAction.MANAGE_PACKAGES));
        if(!Set.of("LEGACY_ENV_ATTESTED","LEGACY_ENV_WITHDRAWN").contains(result.code()))throw new IllegalStateException(result.code());
        return Map.of("receipt",result,"current",inspect(viewer,pkg,0));
    }
    private boolean allowed(ServerPlayer player,PermissionAction action){return MineAgentRuntimeServices.permissions(server).allowed(player.getUUID(),player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER),action);}
    @Override public void close()throws Exception{pins.close();}
}
