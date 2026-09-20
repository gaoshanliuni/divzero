package dev.mineagent.runtime.core.packages;
import dev.mineagent.runtime.api.packages.*;
/** Metadata fence only. Signature/content verification and registration AST checks remain mandatory at dispatch. */
public final class WorldRestorePolicy {
    private WorldRestorePolicy(){}
    public static String check(WorldActivationLedger.Activation a,RuntimePackage p,RuntimeInstance i,boolean authorized){
        return check(a,p,i,authorized,a.location());
    }
    /** movedLocation must come from the instance's verified APPLIED move journal, never a client parameter. */
    public static String check(WorldActivationLedger.Activation a,RuntimePackage p,RuntimeInstance i,boolean authorized,RuntimeInstanceLocation movedLocation){
        if(!authorized)return "RESTORE_PERMISSION_DENIED";
        if(!a.autoRestore()||!a.state().equals("RESTORE_PENDING"))return "RESTORE_NOT_PENDING";
        return checkSource(a,p,i,authorized,movedLocation);
    }
    public static boolean resumeEligible(WorldActivationLedger.Activation a){return a!=null&&a.state().equals("INTERRUPTED")&&a.autoRestore()&&a.restoreBlock()!=null;}
    public static String checkSource(WorldActivationLedger.Activation a,RuntimePackage p,RuntimeInstance i,boolean authorized,RuntimeInstanceLocation movedLocation){
        if(!authorized)return "RESTORE_PERMISSION_DENIED";
        if(p==null||!p.enabled()||!p.packageId().equals(a.packageId()))return "RESTORE_PACKAGE_UNAVAILABLE";
        if(p.revision()<a.packageRevision()||!p.canonicalSha256().equals(a.canonicalSha256()))return "RESTORE_SOURCE_CHANGED";
        if(p.activationMode()!=ActivationMode.HOT_RUNTIME)return "ACTIVATION_REQUIRES_LIFECYCLE";
        var d=p.definitions().get(a.definitionId());
        if(i==null||d==null||movedLocation==null||!movedLocation.dimension().equals(a.location().dimension())||movedLocation.yaw()!=a.location().yaw()||movedLocation.pitch()!=a.location().pitch()||!i.worldId().equals(a.worldId())||!i.instanceId().equals(a.instanceId())||!i.packageId().equals(a.packageId())||!i.definitionId().equals(a.definitionId())||i.definitionRevision()!=d.revision()||i.stateSchemaVersion()!=d.stateSchemaVersion()||!i.location().equals(movedLocation))return "RESTORE_INSTANCE_CONTEXT";
        var entry=p.entrypoints().get(d.entrypointId());var restore=p.entrypoints().get(d.entrypointId()+".restore");
        return entry!=null&&entry.side()!=RuntimeResourceSide.CLIENT&&entry.equals(restore)?"":"RESTORE_CONTRACT_MISSING";
    }
}
